package com.consense.ai;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingSemanticReview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static com.consense.ai.StructuredOutputValidationException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Structural protocol only: an accepted reference is still untrusted at the source/requirement gate. */
class StructuredJsonSourceReferenceSchemaTest {
    private static JsonNode actualSchema() {
        try {
            Method method = Class.forName("com.consense.service.vetting.VettingOutputSchema")
                    .getDeclaredMethod("forChunkIds", Collection.class);
            method.setAccessible(true);
            return (JsonNode) method.invoke(null, Collections.singletonList("submitted-1"));
        } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
    }

    private static LlmClient provider(String raw) {
        LlmClient provider = mock(LlmClient.class);
        when(provider.available()).thenReturn(true);
        when(provider.chatStructured(anyList(), any())).thenReturn(raw);
        return provider;
    }

    private static List<JsonNode> decode(String raw, JsonNode schema) {
        return new AiGateway(provider(raw)).completeStructuredJsonList("Synthetic system", "Synthetic source", JsonNode.class, schema);
    }

    private static void rejected(String raw, JsonNode schema, StructuredOutputValidationException.Reason reason) {
        LlmClient provider = provider(raw);
        List<String> captured = new ArrayList<>();
        assertEquals(reason, assertThrows(StructuredOutputValidationException.class,
                () -> new AiGateway(provider).completeStructuredJsonList("Synthetic system", "Synthetic source", JsonNode.class, schema, captured), raw).getReason(), raw);
        assertEquals(Collections.singletonList(raw), captured);
        verify(provider, times(1)).chatStructured(anyList(), any());
        verify(provider, never()).chat(anyList());
    }

    private static void badSchema(JsonNode schema, StructuredOutputValidationException.Reason reason) {
        LlmClient provider = mock(LlmClient.class);
        Map<String, Long> timings = new LinkedHashMap<>();
        assertEquals(reason, assertThrows(StructuredOutputValidationException.class,
                () -> new AiGateway(provider).completeStructuredJsonList("system", "source", JsonNode.class, schema, new ArrayList<>(), timings), schema.toString()).getReason());
        verifyNoInteractions(provider);
        assertEquals(Collections.singleton("structured_schema_validation"), timings.keySet());
    }

    private static ObjectNode arraySchema(JsonNode items) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("type", "array"); root.set("items", items); return root;
    }

    private static ObjectNode indexedObject() {
        return (ObjectNode) JsonUtils.parse("{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3}},\"required\":[\"index\"],\"additionalProperties\":false}");
    }

    private static ObjectNode nullableUnion(JsonNode object, boolean reversed) {
        ObjectNode union = JsonUtils.mapper().createObjectNode();
        ArrayNode branches = union.putArray("anyOf");
        JsonNode nil = JsonUtils.parse("{\"type\":\"null\"}");
        branches.add(reversed ? object : nil); branches.add(reversed ? nil : object); return union;
    }

    private static ObjectNode nullableSchema(boolean reversed) {
        ObjectNode item = (ObjectNode) JsonUtils.parse("{\"type\":\"object\",\"properties\":{},\"required\":[\"support\"],\"additionalProperties\":false}");
        ((ObjectNode) item.get("properties")).set("support", nullableUnion(indexedObject(), reversed));
        return arraySchema(item);
    }

    private static ObjectNode productionRow(boolean objectSupport) {
        ObjectNode row = (ObjectNode) JsonUtils.parse("{\"assessment\":\"insufficient_context\",\"type\":\"reference\",\"severity\":\"medium\",\"title\":\"Synthetic shape\",\"comment\":\"Synthetic protocol fixture\",\"impact\":\"Fixture impact\",\"suggestion\":\"Fixture suggestion\",\"subDimension\":\"\",\"factBasis\":\"\",\"pendingFacts\":[],\"suggestedAction\":\"review\",\"evidence\":[{\"chunkId\":\"submitted-1\",\"side\":\"source\",\"quote\":\"Synthetic source quotation\"}]}");
        ObjectNode check = row.putArray("checklistAssessments").addObject();
        check.put("itemId", actualSchema().path("items").path("properties").path("checklistAssessments")
                .path("items").path("properties").path("itemId").path("enum").get(0).textValue());
        check.put("status", "insufficient_evidence").put("reason", "Unverified source relation remains unknown");
        check.set("support", JsonUtils.parse("null"));
        if (objectSupport) {
            ObjectNode support = check.putObject("support"); support.put("kind", "sl_clause_selection");
            ArrayNode facts = support.putArray("facts");
            facts.add(fact("selected_specification_clause", 0, "Synthetic selected specification clause"));
            facts.add(fact("sl_guidance_criterion", 15, "Synthetic SL guidance criterion"));
            facts.add(fact("project_contract_type_or_selection_fact", 0, "Synthetic project contract selection fact"));
        }
        return row;
    }

    private static ObjectNode fact(String slot, int evidence, String text) {
        ObjectNode fact = JsonUtils.mapper().createObjectNode();
        fact.put("slot", slot).put("evidenceIndex", evidence).put("text", text);
        return fact;
    }

    private static ObjectNode check(ObjectNode row) { return (ObjectNode) row.path("checklistAssessments").get(0); }
    private static ObjectNode support(ObjectNode row) { return (ObjectNode) check(row).get("support"); }
    private static ObjectNode firstFact(ObjectNode row) { return (ObjectNode) support(row).path("facts").get(0); }
    private static String rows(JsonNode... rows) { return JsonUtils.write(Arrays.asList(rows)); }

    @Test void productionSchemaCompilesAndMapsRequiredNullSupportWithoutCertifyingTruth() {
        ObjectNode row = productionRow(false);
        LlmClient provider = provider(rows(row));
        List<VettingSemanticReview.Record> decoded = new AiGateway(provider).completeStructuredJsonList("system", "source", VettingSemanticReview.Record.class, actualSchema());
        assertEquals(1, decoded.size());
        assertNull(decoded.get(0).getChecklistAssessments().get(0).getSupport());
        assertEquals("insufficient_evidence", decoded.get(0).getChecklistAssessments().get(0).getStatus());
        assertEquals(row, decode(rows(row), actualSchema()).get(0));
        verify(provider, times(1)).chatStructured(anyList(), any());
    }

    @Test void productionSchemaMapsStrictTypedTextReferencesWithoutSourceCertification() {
        ObjectNode row = productionRow(true);
        List<VettingSemanticReview.Record> decoded = new AiGateway(provider(rows(row)))
                .completeStructuredJsonList("system", "source", VettingSemanticReview.Record.class, actualSchema());
        VettingSemanticReview.ChecklistSupport support = decoded.get(0).getChecklistAssessments().get(0).getSupport();
        assertEquals("sl_clause_selection", support.getKind()); assertEquals(3, support.getFacts().size());
        assertEquals("Synthetic selected specification clause", support.getFacts().get(0).getText());
        assertEquals(Integer.valueOf(15), support.getFacts().get(1).getEvidenceIndex());
        assertEquals("Synthetic SL guidance criterion", support.getFacts().get(1).getText());
        assertEquals("Synthetic project contract selection fact", support.getFacts().get(2).getText());
        for(VettingSemanticReview.ChecklistFactRef fact:support.getFacts()) {
            assertNull(fact.getNativePositionIndex());assertNull(fact.getStartUtf16());assertNull(fact.getEndUtf16());
        }
        assertEquals(row, decode(rows(row), actualSchema()).get(0));
    }

    @Test void productionSchemaRejectsMalformedIndexesLegacyNumericFieldsAndMissingTextAtomically() {
        Map<String, String[]> invalid = new LinkedHashMap<>();
        invalid.put("evidenceIndex", new String[]{"-1", "16", "2147483648", "0.0", "0e0", "\"0\"", "true", "null"});
        invalid.put("nativePositionIndex", new String[]{"-1", "4096", "2147483648", "0.0", "\"0\"", "null"});
        invalid.put("startUtf16", new String[]{"-1", "2401", "2147483648", "0.0", "\"0\"", "null"});
        invalid.put("endUtf16", new String[]{"0", "2401", "2147483648", "1.0", "\"1\"", "null"});
        for (Map.Entry<String, String[]> field : invalid.entrySet()) {
            for (String value : field.getValue()) {
                ObjectNode bad = productionRow(true); firstFact(bad).set(field.getKey(), JsonUtils.parse(value));
                rejected(rows(productionRow(false), bad), actualSchema(), SCHEMA_MISMATCH);
            }
            if("evidenceIndex".equals(field.getKey())) {
                ObjectNode bad = productionRow(true); firstFact(bad).remove(field.getKey());
                rejected(rows(productionRow(false), bad), actualSchema(), SCHEMA_MISMATCH);
            }
        }
        ObjectNode missing=productionRow(true);firstFact(missing).remove("text");rejected(rows(missing),actualSchema(),SCHEMA_MISMATCH);
        for(String value:Arrays.asList("\"\"","null","1","true")) {
            ObjectNode bad=productionRow(true);firstFact(bad).set("text",JsonUtils.parse(value));rejected(rows(bad),actualSchema(),SCHEMA_MISMATCH);
        }
    }

    @Test void productionSchemaRejectsMissingSupportExtraVerdictsAndIncompleteFacts() {
        ObjectNode missing = productionRow(false); check(missing).remove("support");
        rejected(rows(missing), actualSchema(), SCHEMA_MISMATCH);
        for (String key : Arrays.asList("verified", "requirementRelation", "value")) {
            ObjectNode bad = productionRow(true); support(bad).put(key, "model supplied");
            rejected(rows(bad), actualSchema(), SCHEMA_MISMATCH);
            bad = productionRow(true); firstFact(bad).put(key, "model supplied");
            rejected(rows(bad), actualSchema(), SCHEMA_MISMATCH);
        }
        ObjectNode shortFacts = productionRow(true); ((ArrayNode) support(shortFacts).get("facts")).remove(2);
        rejected(rows(shortFacts), actualSchema(), SCHEMA_MISMATCH);
        ObjectNode longFacts = productionRow(true); ((ArrayNode) support(longFacts).get("facts")).add(firstFact(longFacts).deepCopy());
        rejected(rows(longFacts), actualSchema(), SCHEMA_MISMATCH);
        ObjectNode nullFact = productionRow(true); ((ArrayNode) support(nullFact).get("facts")).set(0, JsonUtils.parse("null"));
        rejected(rows(nullFact), actualSchema(), SCHEMA_MISMATCH);
        ObjectNode unknownSlot = productionRow(true); firstFact(unknownSlot).put("slot", "server_verified_fact");
        rejected(rows(unknownSlot), actualSchema(), SCHEMA_MISMATCH);
    }

    @Test void nullableObjectUnionAcceptsBothBranchOrdersButStillRequiresItsProperty() {
        for (boolean reversed : Arrays.asList(false, true)) {
            JsonNode schema = nullableSchema(reversed);
            assertEquals(2, decode("[{\"support\":null},{\"support\":{\"index\":0}}]", schema).size());
            for (String raw : Arrays.asList("[{}]", "[null]", "[[{\"support\":null}]]", "[{\"support\":0}]", "[{\"support\":[]}]"))
                rejected(raw, schema, SCHEMA_MISMATCH);
        }
    }

    @Test void nullableObjectUnionValidatesAllObjectConstraintsWithoutCoercion() {
        for (String raw : Arrays.asList("[{\"support\":{}}]", "[{\"support\":{\"index\":-1}}]", "[{\"support\":{\"index\":4}}]",
                "[{\"support\":{\"index\":\"0\"}}]", "[{\"support\":{\"index\":null}}]", "[{\"support\":{\"index\":0,\"extra\":true}}]"))
            rejected(raw, nullableSchema(false), SCHEMA_MISMATCH);
    }

    @Test void nullableUnionRejectsUnknownKeywordsInEveryBranchBeforeDispatch() {
        for (String json : Arrays.asList(
                "{\"anyOf\":[{\"type\":\"null\"},{\"type\":\"object\"}],\"description\":\"ignored?\"}",
                "{\"anyOf\":[{\"type\":\"null\"},{\"type\":\"object\"}],\"type\":\"object\"}",
                "{\"anyOf\":[{\"type\":\"null\",\"description\":\"ignored?\"},{\"type\":\"object\"}]}",
                "{\"anyOf\":[{\"type\":\"null\"},{\"type\":\"object\",\"pattern\":\"x\"}]}",
                "{\"anyOf\":[{\"type\":\"null\"},{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}]}"))
            badSchema(arraySchema(JsonUtils.parse(json)), UNSUPPORTED_SCHEMA);
    }

    @Test void otherUnionsAndStandaloneNullRemainOutsideTheSupportedSubset() {
        for (String json : Arrays.asList("{\"anyOf\":[]}", "{\"anyOf\":[{\"type\":\"null\"}]}",
                "{\"anyOf\":[{\"type\":\"null\"},{\"type\":\"string\"}]}",
                "{\"anyOf\":[{\"type\":\"object\"},{\"type\":\"object\"}]}",
                "{\"anyOf\":[{\"type\":\"null\"},{\"type\":\"null\"}]}",
                "{\"anyOf\":[{\"type\":\"null\"},{\"type\":\"object\"},{\"type\":\"string\"}]}",
                "{\"type\":\"null\"}", "{\"type\":\"number\"}"))
            badSchema(arraySchema(JsonUtils.parse(json)), UNSUPPORTED_SCHEMA);
        badSchema(arraySchema(JsonUtils.parse("{\"type\":[\"object\",\"null\"]}")), INVALID_SCHEMA);
        badSchema(nullableUnion(indexedObject(), false), INVALID_SCHEMA);
    }

    @Test void malformedNullableUnionDeclarationsFailBeforeProviderAvailability() {
        for (String json : Arrays.asList("{\"anyOf\":null}", "{\"anyOf\":{}}", "{\"anyOf\":[null,{\"type\":\"object\"}]}",
                "{\"anyOf\":[{\"type\":\"null\"},{}]}", "{\"anyOf\":[{\"type\":\"null\"},{\"type\":true}]}"))
            badSchema(arraySchema(JsonUtils.parse(json)), INVALID_SCHEMA);
    }

    @Test void integerSubsetKeepsNonnegative32BitAndInclusiveRangeLimitsWithoutCoercion() {
        JsonNode unrestricted = arraySchema(JsonUtils.parse("{\"type\":\"integer\"}"));
        assertEquals(2, decode("[0,2147483647]", unrestricted).size());
        for (String raw : Arrays.asList("[-1]", "[2147483648]", "[9007199254740993]", "[1.0]", "[1e0]", "[\"1\"]", "[true]", "[null]", "[{}]", "[[1]]"))
            rejected(raw, unrestricted, SCHEMA_MISMATCH);
        JsonNode bounded = arraySchema(JsonUtils.parse("{\"type\":\"integer\",\"minimum\":2,\"maximum\":3}"));
        assertEquals(2, decode("[2,3]", bounded).size());
        rejected("[1]", bounded, SCHEMA_MISMATCH); rejected("[4]", bounded, SCHEMA_MISMATCH);
    }

    @Test void integerBoundsAndUnknownKeywordsAreValidatedBeforeDispatch() {
        for (String json : Arrays.asList("{\"type\":\"integer\",\"minimum\":-1}", "{\"type\":\"integer\",\"maximum\":-1}",
                "{\"type\":\"integer\",\"minimum\":1.0}", "{\"type\":\"integer\",\"maximum\":\"3\"}",
                "{\"type\":\"integer\",\"maximum\":2147483648}", "{\"type\":\"integer\",\"minimum\":null}",
                "{\"type\":\"integer\",\"minimum\":3,\"maximum\":2}"))
            badSchema(arraySchema(JsonUtils.parse(json)), INVALID_SCHEMA);
        for (String json : Arrays.asList("{\"type\":\"integer\",\"exclusiveMinimum\":0}", "{\"type\":\"integer\",\"enum\":[0]}",
                "{\"type\":\"integer\",\"minLength\":0}", "{\"type\":\"integer\",\"multipleOf\":1}"))
            badSchema(arraySchema(JsonUtils.parse(json)), UNSUPPORTED_SCHEMA);
    }

    @Test void nullableBranchesStillCountTowardTheExistingSchemaDepthLimit() {
        for (int wrappers : Arrays.asList(31, 32)) {
            JsonNode node = JsonUtils.parse("{\"type\":\"object\"}");
            for (int i = 0; i < wrappers; i++) {
                ObjectNode parent = JsonUtils.mapper().createObjectNode(); parent.put("type", "object");
                parent.putObject("properties").set("next", nullableUnion(node, false)); node = parent;
            }
            JsonNode schema = arraySchema(node);
            if (wrappers == 31) assertTrue(decode("[]", schema).isEmpty());
            else badSchema(schema, UNSUPPORTED_SCHEMA);
        }
    }

    @Test void providerCannotWeakenCompiledIntegerConstraintsByMutatingTheSuppliedUnion() {
        ObjectNode schema = nullableSchema(false); JsonNode original = schema.deepCopy();
        LlmClient provider = mock(LlmClient.class); when(provider.available()).thenReturn(true);
        when(provider.chatStructured(anyList(), any())).thenAnswer(call -> {
            JsonNode sent = call.getArgument(1);
            ((ObjectNode) sent.path("items").path("properties").path("support").path("anyOf").get(1)
                    .path("properties").path("index")).put("maximum", 4);
            return "[{\"support\":{\"index\":4}}]";
        });
        assertEquals(SCHEMA_MISMATCH, assertThrows(StructuredOutputValidationException.class,
                () -> new AiGateway(provider).completeStructuredJsonList("system", "source", JsonNode.class, schema)).getReason());
        assertEquals(original, schema); verify(provider, times(1)).chatStructured(anyList(), any());
    }

    @Test void strictWholeArrayJsonGatesRemainInForceInsideNullableReferenceObjects() {
        JsonNode schema = nullableSchema(false);
        for (String raw : Arrays.asList("[{\"support\":null,\"support\":{\"index\":0}}]",
                "[{\"support\":{\"index\":0,\"index\":1}}]", "[{\"support\":null}] []",
                "```json\n[{\"support\":null}]\n```", "[{\"support\":null},{\"support\":", "/* comment */[]"))
            rejected(raw, schema, INVALID_JSON);
        rejected("{\"items\":[{\"support\":null}]}", schema, SCHEMA_MISMATCH);
        rejected("[{\"support\":null},{\"support\":{\"index\":4}}]", schema, SCHEMA_MISMATCH);
    }
}
