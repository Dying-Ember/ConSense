package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Human-reviewed definition references; neither catalog hints nor mappings adopt project values. */
public final class DraftCatalogAlignment {
    private DraftCatalogAlignment() { }

    public static Map<String,Object> catalog() { return DraftBusinessRules.catalog(); }
    public static Map<String,Object> reference(String key) {
        if(key==null||key.length()>160)throw invalid("GRAPH_CATALOG_KEY_INVALID");
        Map<String,Object> context=DraftBaselineMatcher.requestedContext(Collections.singletonList(key));List<?> fields=list(context.get("fields"));
        if(fields.size()!=1)throw invalid("GRAPH_CATALOG_KEY_INVALID: "+key);
        Map<String,Object> field=map(fields.get(0));return obj("ruleVersion",DraftBusinessRules.RULE_VERSION,"baselineKey",key,"referenceKind","historical_catalog_reference_only","editableAuthority",false,"sourceContext",field.get("sourceContext"));
    }

    static Map<String,Map<String,Object>> definitions() {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(Map<String,Object> field:DraftBusinessRules.fields())index(field,"",result);
        return result;
    }
    private static void index(Map<String,Object> field,String parent,Map<String,Map<String,Object>> result) {
        String key=parent.isEmpty()?text(field.get("key")):parent+"."+field.get("key");result.put(key,field);
        for(Object child:list(field.get("columnFields")))index(map(child),key,result);
    }

    static Map<String,Map<String,Object>> confirmedDefinitions(Map<String,Object> graph) {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>(),baseline=definitions();
        try{validate(graph);}catch(BizException invalid){return result;}
        for(Map<String,Object> node:nodes(graph)) {
            Map<String,Object> alignment=map(node.get("catalogAlignment"));String key=text(alignment.get("baselineKey"));
            if(!businessCandidate(node)||!"matched".equals(alignment.get("status"))||!Boolean.TRUE.equals(alignment.get("confirmed")))continue;
            if(hasUnverifiedSccSource(graph,node))continue;
            Map<String,Object> field=baseline.get(key);if(field==null)continue;
            int dot=key.lastIndexOf('.');Map<String,Object> type=obj("kind",baselineKind(field));if(field.containsKey("unit"))type.put("unit",field.get("unit"));
            Map<String,Object> definition=obj("baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"parentKey",dot<0?"":key.substring(0,dot),"label",field.get("label"),"type",type,"optional",Boolean.TRUE.equals(field.get("optional")));
            for(String property:Arrays.asList("options","description"))if(field.containsKey(property))definition.put(property,field.get(property));
            result.put(text(node.get("id")),JsonUtils.readMap(JsonUtils.write(definition)));
        }
        // A column cannot acquire a confirmed definition through an unreviewed parent.
        boolean removed;do{removed=false;for(Map<String,Object> node:nodes(graph)){Map<String,Object> definition=result.get(text(node.get("id")));if(definition==null||text(definition.get("parentKey")).isEmpty())continue;Map<String,Object> parent=result.get(text(node.get("parentId")));if(parent==null||!Objects.equals(parent.get("baselineKey"),definition.get("parentKey"))){result.remove(text(node.get("id")));removed=true;}}}while(removed);
        return result;
    }
    static boolean hasUnverifiedSccSource(Map<String,Object> graph,Map<String,Object> node) {
        Set<String> ids=new HashSet<>();for(Object id:list(node.get("docIds")))ids.add(text(id));for(String group:Arrays.asList("sourceAnchors","editTargets","evidence"))for(Object raw:list(node.get(group))){Map<String,Object> anchor=map(raw);ids.add(text(anchor.get("docId")));ids.add(text(anchor.get("sourceDocumentId")));ids.add(text(anchor.get("editionId")));}
        for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if(!"SCC".equalsIgnoreCase(text(source.get("fileKey"))))continue;for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId"))if(!text(source.get(key)).isEmpty()&&ids.contains(text(source.get(key)))&&(!"bound".equals(source.get("evidenceStatus"))||!DraftTemplateReadingSources.catalogueSourceVerified("SCC",text(source.get("sourceSha256")))))return true;}
        return false;
    }

    public static void validate(Map<String,Object> graph) {
        Map<String,Map<String,Object>> baseline=definitions(),byId=new LinkedHashMap<>();
        for(Map<String,Object> node:nodes(graph))byId.put(text(node.get("id")),node);
        Map<String,Set<String>> assigned=new HashMap<>();
        for(Map<String,Object> node:byId.values()) {
            if(node.containsKey("definitionOrigin")) {
                Map<String,Object> origin=object(node.get("definitionOrigin"),"GRAPH_DEFINITION_ORIGIN_INVALID");
                if(!"manual".equals(origin.get("kind"))||blank(origin.get("reason")))throw invalid("GRAPH_MANUAL_DEFINITION_REASON_REQUIRED");
                if(!"input".equals(map(node.get("role")).get("kind")))throw invalid("GRAPH_MANUAL_DEFINITION_MUST_BE_INPUT");
            }
            for(String key:Arrays.asList("unit","definitionScope"))if(node.containsKey(key)&&!(node.get(key) instanceof String))throw invalid("GRAPH_DEFINITION_METADATA_INVALID: "+key);
            if(!node.containsKey("catalogAlignment"))continue;
            Map<String,Object> alignment=object(node.get("catalogAlignment"),"GRAPH_CATALOG_ALIGNMENT_INVALID");
            String status=text(alignment.get("status")),key=text(alignment.get("baselineKey"));
            if(!Arrays.asList("unreviewed","matched","new","ambiguous").contains(status)||!(alignment.get("confirmed") instanceof Boolean))throw invalid("GRAPH_CATALOG_ALIGNMENT_INVALID");
            if(alignment.containsKey("scope")&&!(alignment.get("scope") instanceof String))throw invalid("GRAPH_CATALOG_SCOPE_INVALID");
            if("matched".equals(status)&&key.isEmpty()||"new".equals(status)&&!key.isEmpty())throw invalid("GRAPH_CATALOG_KEY_INVALID");
            if(!key.isEmpty())requireDefinition(baseline,key,alignment.get("baselineVersion"));
            if(!Boolean.TRUE.equals(alignment.get("confirmed")))continue;
            if(!Arrays.asList("matched","new").contains(status)||blank(alignment.get("reason")))throw invalid("GRAPH_CATALOG_REVIEW_REASON_REQUIRED");
            if("excluded".equals(node.get("status")))continue;
            if(!"matched".equals(status))continue;
            if(!businessCandidate(node))throw invalid("GRAPH_CATALOG_INPUT_REQUIRED");
            compatible(node,baseline.get(key));
            String parent=text(node.get("parentId")),scope=text(alignment.get("scope")).trim();
            int dot=key.lastIndexOf('.');
            if(dot>=0) {
                Map<String,Object> parentNode=byId.get(parent),parentMapping=map(parentNode==null?null:parentNode.get("catalogAlignment"));
                if(!"matched".equals(parentMapping.get("status"))||!key.substring(0,dot).equals(parentMapping.get("baselineKey")))throw invalid("GRAPH_CATALOG_PARENT_MISMATCH: "+node.get("id"));
            } else if(!parent.isEmpty()&&scope.isEmpty())throw invalid("GRAPH_CATALOG_SCOPE_REQUIRED: "+node.get("id"));
            String identity=parent+"\n"+scope;
            if(!assigned.computeIfAbsent(key,ignored->new HashSet<>()).add(identity))throw invalid("GRAPH_CATALOG_DUPLICATE_MAPPING: "+key);
            for(Object raw:list(baseline.get(key).get("columnFields"))) {
                Map<String,Object> child=map(raw);if(Boolean.TRUE.equals(child.get("optional")))continue;
                String childKey=key+"."+child.get("key");boolean found=false;
                for(Map<String,Object> actual:byId.values())if(!"excluded".equals(actual.get("status"))&&Objects.equals(node.get("id"),actual.get("parentId"))) {
                    Map<String,Object> mapping=map(actual.get("catalogAlignment"));
                    if("matched".equals(mapping.get("status"))&&Boolean.TRUE.equals(mapping.get("confirmed"))&&childKey.equals(mapping.get("baselineKey")))found=true;
                }
                if(!found)throw invalid("GRAPH_CATALOG_CHILD_MAPPING_REQUIRED: "+childKey);
            }
        }
        if(state(graph).containsKey("catalogReview")) {
            Map<String,Object> review=object(state(graph).get("catalogReview"),"GRAPH_CATALOG_REVIEW_INVALID");
            Map<String,Object> exclusions=object(review.get("exclusions"),"GRAPH_CATALOG_EXCLUSIONS_INVALID");
            for(Map.Entry<String,Object> entry:exclusions.entrySet()) {
                Map<String,Object> decision=object(entry.getValue(),"GRAPH_CATALOG_EXCLUSION_INVALID");
                requireDefinition(baseline,entry.getKey(),decision.get("baselineVersion"));
                if(blank(decision.get("reason")))throw invalid("GRAPH_CATALOG_EXCLUSION_REASON_REQUIRED");
                if(assigned.containsKey(entry.getKey()))throw invalid("GRAPH_CATALOG_EXCLUSION_HAS_MAPPING: "+entry.getKey());
            }
        }
    }
    private static void requireDefinition(Map<String,Map<String,Object>> baseline,String key,Object version) {
        if(!baseline.containsKey(key)||"contractTitle".equals(key)||key.startsWith("contractTitle."))throw invalid("GRAPH_CATALOG_KEY_INVALID: "+key);
        if(!DraftBusinessRules.RULE_VERSION.equals(version))throw invalid("GRAPH_CATALOG_VERSION_CHANGED");
    }
    private static void compatible(Map<String,Object> node,Map<String,Object> baseline) {
        String kind=text(map(node.get("type")).get("kind")),expected=baselineKind(baseline);
        if(!kind.equals(expected))throw invalid("GRAPH_CATALOG_TYPE_MISMATCH: "+node.get("id"));
        Object unit=map(node.get("type")).get("unit");if(unit==null)unit=node.get("unit");
        if(!text(unit).equals(text(baseline.get("unit"))))throw invalid("GRAPH_CATALOG_UNIT_MISMATCH: "+node.get("id"));
        if(Arrays.asList("choice","multiselect").contains(kind)&&!optionValues(node).equals(optionValues(baseline)))throw invalid("GRAPH_CATALOG_OPTIONS_MISMATCH: "+node.get("id"));
        if(Boolean.TRUE.equals(node.get("optional"))!=Boolean.TRUE.equals(baseline.get("optional")))throw invalid("GRAPH_CATALOG_OPTIONAL_MISMATCH: "+node.get("id"));
    }
    private static String baselineKind(Map<String,Object> baseline){String kind=text(baseline.get("kind"));return Arrays.asList("contract","object").contains(kind)?"record":Arrays.asList("bills","table","records").contains(kind)?"list":Arrays.asList("textarea","longtext").contains(kind)?"text":kind;}
    private static Set<Object> optionValues(Map<String,Object> field) {
        Set<Object> result=new HashSet<>();for(Object raw:list(field.get("options")))result.add(map(raw).get("value"));return result;
    }
    public static boolean reviewed(Map<String,Object> node) {
        return "verification".equals(map(node.get("role")).get("kind"))||"source_seed".equals(node.get("definitionKind"))||Boolean.TRUE.equals(map(node.get("pdfReferenceAlignment")).get("confirmed"))||!node.containsKey("catalogAlignment")||Boolean.TRUE.equals(map(node.get("catalogAlignment")).get("confirmed"));
    }
    static boolean businessCandidate(Map<String,Object> node) {
        if(node==null||"excluded".equals(node.get("status"))||"source_seed".equals(node.get("definitionKind"))||!Arrays.asList("input","decision","content").contains(text(map(node.get("role")).get("kind"))))return false;
        Map<String,Object> review=map(node.get("editorialReview"));
        return !(Boolean.TRUE.equals(review.get("confirmed"))&&"editorial".equals(review.get("kind")));
    }
    public static boolean standaloneManualInput(Map<String,Object> node) {
        Map<String,Object> origin=map(node.get("definitionOrigin")),correction=map(node.get("correction"));
        return "manual".equals(origin.get("kind"))&&!blank(origin.get("reason"))&&"input".equals(map(node.get("role")).get("kind"))
                &&list(node.get("editTargets")).isEmpty()&&list(correction.get("branches")).isEmpty()&&list(correction.get("clauses")).isEmpty();
    }
    public static Map<String,Object> meaning(Map<String,Object> node) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:Arrays.asList("unit","definitionScope"))if(node.containsKey(key))result.put(key,node.get(key));
        if(node.containsKey("pdfReferenceAlignment")){Map<String,Object> identity=new LinkedHashMap<>(),alignment=map(node.get("pdfReferenceAlignment"));for(String key:Arrays.asList("status","confirmed","sourceId","sourceSha256","inputRef","basis"))if(alignment.containsKey(key))identity.put(key,alignment.get(key));result.put("pdfReferenceAlignment",identity);}
        if("manual".equals(map(node.get("definitionOrigin")).get("kind")))result.put("optional",Boolean.TRUE.equals(node.get("optional")));
        if(node.containsKey("catalogAlignment")) {
            Map<String,Object> identity=new LinkedHashMap<>(),mapping=map(node.get("catalogAlignment"));
            for(String key:Arrays.asList("status","baselineKey","baselineVersion","scope"))if(mapping.containsKey(key))identity.put(key,mapping.get(key));
            result.put("catalogAlignment",identity);result.put("optional",Boolean.TRUE.equals(node.get("optional")));
        }
        return result;
    }
    private static boolean blank(Object value) { return !(value instanceof String)||((String)value).trim().isEmpty(); }
    private static Map<String,Object> object(Object value,String message) { if(!(value instanceof Map))throw invalid(message);return map(value); }
    private static BizException invalid(String message) { return new BizException(4012,message); }
}
