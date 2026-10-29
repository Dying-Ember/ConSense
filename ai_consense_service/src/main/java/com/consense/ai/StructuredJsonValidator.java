package com.consense.ai;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.consense.ai.StructuredOutputValidationException.Reason.*;

/**
 * Deliberately bounded schema subset used by VettingOutputSchema, not a general JSON Schema engine.
 * Supports type=array/object/string/integer; array items/minItems/maxItems; object properties/required/
 * boolean additionalProperties; string enum/minLength/maxLength; and nonnegative 32-bit integer
 * minimum/maximum. The only union is a sole anyOf with exactly one plain null and one object branch.
 * Every other keyword, type, boolean schema, union, reference, or schema-valued additionalProperties
 * is rejected before dispatch. Other nodes require a supported type. Missing items/properties is unrestricted.
 * Bounds are nonnegative 32-bit integer nodes; schema nesting beyond 64 levels is unsupported.
 * String length counts Unicode code points, including supplementary characters, rather than UTF-16 units.
 * The gateway additionally requires a direct array with no null or nested-array top-level elements.
 * Evidence/source truth and semantic quality remain separate checks after this structural gate.
 */
final class StructuredJsonValidator {
    private static final Logger LOG = LoggerFactory.getLogger(StructuredJsonValidator.class);
    // Independent mapper: ordinary/Drafting JSON extraction and mapping retain their existing settings.
    private static final ObjectMapper STRICT_MAPPER = JsonUtils.mapper().copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ARRAY_KEYS = keys("type", "items", "minItems", "maxItems");
    private static final Set<String> OBJECT_KEYS = keys("type", "properties", "required", "additionalProperties");
    private static final Set<String> STRING_KEYS = keys("type", "enum", "minLength", "maxLength");
    private static final Set<String> INTEGER_KEYS = keys("type", "minimum", "maximum");
    private static final int MAX_SCHEMA_DEPTH = 64;

    private StructuredJsonValidator() { }

    static Schema compile(JsonNode schema) {
        Schema compiled = compileNode(schema, 0);
        if (!"array".equals(compiled.type)) throw failure(INVALID_SCHEMA);
        return compiled;
    }

    static <T> List<T> decode(String raw, Class<T> elementType, Schema schema) {
        return decodeArray(strictJson(raw),elementType,schema);
    }

    static <T> List<T> decodeRecords(String raw, Class<T> elementType, Schema schema) {
        JsonNode root=strictJson(raw);
        if(!root.isObject()||root.size()!=1||!root.has("records"))throw mismatch("$","records_wrapper",null,root);
        return decodeArray(root.get("records"),elementType,schema);
    }

    static <T> List<T> decodeValidRecords(String raw, Class<T> elementType, Schema schema, boolean recordsObject, List<Integer> rejectedOrdinals, Collection<String> allowedItems, List<String> unverifiedItems) {
        JsonNode root = strictJson(raw);
        if (recordsObject) {
            if (!root.isObject() || root.size()!=1 || !root.has("records")) throw mismatch("$","records_wrapper",null,root);
            root=root.get("records");
        }
        if (root==null || !root.isArray()) throw mismatch("$","root_array",schema,root);
        checkBounds(root.size(),schema,"$",root,"minItems","maxItems");
        if (schema.items==null) throw failure(INVALID_SCHEMA);
        List<T> accepted=new ArrayList<>(); List<Integer> rejected=new ArrayList<>(); Set<String> affected=new LinkedHashSet<>(),allowed=new HashSet<>(allowedItems); int ordinal=0;
        for (JsonNode item:root) {
            try {
                if (item==null || item.isNull() || item.isArray()) throw mismatch("$["+ordinal+"]","flat_array_item",schema.items,item);
                if (schema.items!=null) validate(item,schema.items,"$["+ordinal+"]");
                accepted.add(STRICT_MAPPER.convertValue(item,elementType));
            } catch (StructuredOutputValidationException | IllegalArgumentException invalid) {
                rejected.add(ordinal);
                JsonNode assessments=item==null?null:item.get("checklistAssessments");
                if(item!=null&&item.isObject()&&assessments!=null&&assessments.isArray())for(JsonNode assessment:assessments) {
                    JsonNode id=assessment.isObject()?assessment.get("itemId"):null;
                    if(id!=null&&id.isTextual()&&allowed.contains(id.textValue()))affected.add(id.textValue());
                }
            }
            ordinal++;
        }
        if (accepted.isEmpty() || rejected.isEmpty()) throw failure(SCHEMA_MISMATCH);
        rejectedOrdinals.addAll(rejected);unverifiedItems.addAll(affected);return accepted;
    }

    private static JsonNode strictJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw jsonFailure(raw);
        try { return STRICT_MAPPER.readTree(raw); }
        catch (Exception invalid) { throw jsonFailure(raw); }
    }

    private static <T> List<T> decodeArray(JsonNode root,Class<T> elementType,Schema schema) {
        String rejectionReason = "none";
        try {
            if (root == null || !root.isArray()) throw mismatch("$","root_array",schema,root);
            int ordinal=0;for (JsonNode item : root) {
                if (item == null || item.isNull() || item.isArray()) throw mismatch("$["+ordinal+"]","flat_array_item",schema.items,item);
                ordinal++;
            }
            validate(root, schema,"$");
            try {
                return STRICT_MAPPER.convertValue(root,
                        STRICT_MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
            } catch (Exception incompatibleElementType) { throw failure(ELEMENT_MAPPING_FAILED); }
        } catch (StructuredOutputValidationException rejected) {
            rejectionReason = rejected.getReason().name();
            throw rejected;
        } finally {
            try {
                LOG.info("Structured array decode rootType={} arrayItems={} expectedMinItems={} expectedMaxItems={} rejectionReason={} threadId={}",
                        root == null ? "MISSING" : root.getNodeType().name(),
                        root != null && root.isArray() ? root.size() : -1,
                        schema.minimum == null ? -1 : schema.minimum,
                        schema.maximum == null ? -1 : schema.maximum,
                        rejectionReason, Thread.currentThread().getId());
            } catch (Throwable ignored) { /* Diagnostics cannot change structured decoding. */ }
        }
    }

    private static Schema compileNode(JsonNode node, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) throw failure(UNSUPPORTED_SCHEMA);
        if (node == null || !node.isObject()) throw failure(INVALID_SCHEMA);
        if (node.has("anyOf")) return compileNullableObject(node, depth);
        if (!node.path("type").isTextual()) throw failure(INVALID_SCHEMA);
        String type = node.get("type").textValue();
        Set<String> supported;
        switch (type) {
            case "array": supported = ARRAY_KEYS; break;
            case "object": supported = OBJECT_KEYS; break;
            case "string": supported = STRING_KEYS; break;
            case "integer": supported = INTEGER_KEYS; break;
            default: throw failure(UNSUPPORTED_SCHEMA);
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) if (!supported.contains(names.next())) throw failure(UNSUPPORTED_SCHEMA);
        Schema schema = new Schema(type);
        if ("array".equals(type)) {
            schema.minimum = bound(node, "minItems"); schema.maximum = bound(node, "maxItems");
            if (node.has("items")) schema.items = compileNode(node.get("items"), depth + 1);
        } else if ("object".equals(type)) {
            if (node.has("properties")) {
                JsonNode properties = node.get("properties");
                if (!properties.isObject()) throw failure(INVALID_SCHEMA);
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    schema.properties.put(field.getKey(), compileNode(field.getValue(), depth + 1));
                }
            }
            if (node.has("required")) {
                JsonNode required = node.get("required");
                if (!required.isArray()) throw failure(INVALID_SCHEMA);
                for (JsonNode field : required) {
                    if (!field.isTextual() || !schema.required.add(field.textValue())) throw failure(INVALID_SCHEMA);
                }
            }
            if (node.has("additionalProperties")) {
                if (!node.get("additionalProperties").isBoolean()) throw failure(UNSUPPORTED_SCHEMA);
                schema.allowAdditional = node.get("additionalProperties").booleanValue();
            }
        } else if ("integer".equals(type)) {
            schema.minimum = bound(node, "minimum"); schema.maximum = bound(node, "maximum");
        } else {
            schema.minimum = bound(node, "minLength"); schema.maximum = bound(node, "maxLength");
            if (node.has("enum")) {
                JsonNode allowed = node.get("enum");
                if (!allowed.isArray()) throw failure(INVALID_SCHEMA);
                schema.allowedStrings = new HashSet<>();
                // Empty supplied-ID enum is meaningful: [] may pass, but no evidence ID can pass.
                for (JsonNode value : allowed) {
                    if (!value.isTextual() || !schema.allowedStrings.add(value.textValue())) throw failure(INVALID_SCHEMA);
                }
            }
        }
        if (schema.minimum != null && schema.maximum != null && schema.minimum > schema.maximum) throw failure(INVALID_SCHEMA);
        return schema;
    }

    private static Schema compileNullableObject(JsonNode node, int depth) {
        if (node.size() != 1) throw failure(UNSUPPORTED_SCHEMA);
        JsonNode alternatives = node.get("anyOf");
        if (!alternatives.isArray()) throw failure(INVALID_SCHEMA);
        if (alternatives.size() != 2) throw failure(UNSUPPORTED_SCHEMA);
        JsonNode object = null;
        boolean hasNull = false;
        for (JsonNode branch : alternatives) {
            if (!branch.isObject() || !branch.path("type").isTextual()) throw failure(INVALID_SCHEMA);
            if ("null".equals(branch.get("type").textValue()) && !hasNull) {
                if (branch.size() != 1) throw failure(UNSUPPORTED_SCHEMA);
                hasNull = true;
            } else if ("object".equals(branch.get("type").textValue()) && object == null) {
                object = branch;
            } else throw failure(UNSUPPORTED_SCHEMA);
        }
        if (!hasNull || object == null) throw failure(UNSUPPORTED_SCHEMA);
        Schema schema = compileNode(object, depth + 1);
        schema.nullable = true;
        return schema;
    }

    private static Integer bound(JsonNode node, String name) {
        if (!node.has(name)) return null;
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw failure(INVALID_SCHEMA);
        return value.intValue();
    }

    private static void validate(JsonNode value, Schema schema,String path) {
        if (value.isNull() && schema.nullable) return;
        switch (schema.type) {
            case "array":
                if (!value.isArray()) throw mismatch(path,"type",schema,value);
                checkBounds(value.size(),schema,path,value,"minItems","maxItems");
                if (schema.items != null){int ordinal=0;for (JsonNode item : value)validate(item,schema.items,path+"["+(ordinal++)+"]");}
                break;
            case "object":
                if (!value.isObject()) throw mismatch(path,"type",schema,value);
                for (String required : schema.required) if (!value.has(required)) throw mismatch(propertyPath(path,required),"required",schema.properties.get(required),null);
                Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    Schema property = schema.properties.get(field.getKey());
                    if (property != null) validate(field.getValue(),property,propertyPath(path,field.getKey()));
                    else if (!schema.allowAdditional) throw mismatch(propertyPath(path,"__unexpected__"),"additionalProperties",null,field.getValue());
                }
                break;
            case "integer":
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
                    throw mismatch(path,"integer_type_or_range",schema,value);
                checkBounds(value.intValue(),schema,path,value,"minimum","maximum");
                break;
            case "string":
                if (!value.isTextual()) throw mismatch(path,"type",schema,value);
                String text = value.textValue();
                checkBounds(text.codePointCount(0,text.length()),schema,path,value,"minLength","maxLength");
                if (schema.allowedStrings != null && !schema.allowedStrings.contains(text)) throw mismatch(path,"enum",schema,value);
                break;
            default: throw failure(UNSUPPORTED_SCHEMA);
        }
    }

    private static void checkBounds(int size,Schema schema,String path,JsonNode value,String minimum,String maximum) {
        if(schema.minimum!=null&&size<schema.minimum)throw mismatch(path,minimum,schema,value);
        if(schema.maximum!=null&&size>schema.maximum)throw mismatch(path,maximum,schema,value);
    }

    private static StructuredOutputValidationException failure(StructuredOutputValidationException.Reason reason) {
        return diagnostic(reason,"$","schema_or_mapping",null,null,-1);
    }
    private static StructuredOutputValidationException jsonFailure(String raw){return diagnostic(INVALID_JSON,"$","invalid_json",null,null,raw==null?-1:raw.length());}
    private static StructuredOutputValidationException mismatch(String path,String constraint,Schema schema,JsonNode value){
        int length=value==null?-1:value.isTextual()?value.textValue().codePointCount(0,value.textValue().length()):value.isContainerNode()?value.size():-1;
        return diagnostic(SCHEMA_MISMATCH,path,constraint,schema,value,length);
    }
    private static String propertyPath(String path,String property){return path+"."+(property.matches("[A-Za-z_][A-Za-z0-9_]{0,47}")?property:"__schema_property__");}
    private static StructuredOutputValidationException diagnostic(StructuredOutputValidationException.Reason reason,String path,String constraint,Schema schema,JsonNode value,int length){
        String safePath=path.length()<=160&&path.matches("\\$(?:\\[[0-9]{1,6}\\]|\\.[A-Za-z_][A-Za-z0-9_]{0,47}){0,24}")?path:"$.path_limit";
        Map<String,Object> fields=new LinkedHashMap<>();fields.put("schemaPath",safePath);fields.put("constraint",constraint);
        fields.put("expectedType",schema==null?"unspecified":schema.type);fields.put("actualType",value==null?"MISSING":value.getNodeType().name());fields.put("actualLength",length);
        fields.put("minimum",schema==null||schema.minimum==null?-1:schema.minimum);fields.put("maximum",schema==null||schema.maximum==null?-1:schema.maximum);
        try{LOG.info("Structured validation failure reason={} schemaPath={} constraint={} expectedType={} actualType={} actualLength={} minimum={} maximum={} threadId={}",
                reason.name(),safePath,constraint,fields.get("expectedType"),fields.get("actualType"),length,fields.get("minimum"),fields.get("maximum"),Thread.currentThread().getId());}
        catch(Throwable ignored){/* Scalar diagnostics cannot change validation. */}
        return new StructuredOutputValidationException(reason,fields);
    }

    private static Set<String> keys(String... names) { return new HashSet<>(Arrays.asList(names)); }

    static final class Schema {
        final String type;
        Integer minimum, maximum;
        Schema items;
        final Map<String, Schema> properties = new LinkedHashMap<>();
        final Set<String> required = new HashSet<>();
        Set<String> allowedStrings;
        boolean allowAdditional = true;
        boolean nullable;
        Schema(String type) { this.type = type; }
    }
}
