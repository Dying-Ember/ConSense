package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Post-atomic suggestions only; neither model output nor a matching label adopts a definition or value. */
final class DraftBaselineMatcher {
    private static final String METHOD="post_atomic_semantic";
    private DraftBaselineMatcher() { }

    static Map<String,Object> catalog() {
        List<Object> fields=new ArrayList<>();for(Map.Entry<String,Map<String,Object>> entry:definitions().entrySet()){
            Map<String,Object> definition=entry.getValue(),compact=obj("key",entry.getKey(),"parentKey",parentKey(entry.getKey()));
            for(String key:Arrays.asList("label","description","kind","unit","optional","options","condition"))if(definition.containsKey(key))compact.put(key,definition.get(key));fields.add(compact);
        }return obj("ruleVersion",DraftBusinessRules.RULE_VERSION,"fields",fields);
    }
    private static Map<String,Map<String,Object>> definitions() {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Map.Entry<String,Map<String,Object>> entry:DraftCatalogAlignment.definitions().entrySet())if(!"contractTitle".equals(entry.getKey())&&!entry.getKey().startsWith("contractTitle.")&&!Boolean.TRUE.equals(entry.getValue().get("hidden"))&&(!"id".equals(entry.getValue().get("key"))||Boolean.FALSE.equals(entry.getValue().get("hidden"))))result.put(entry.getKey(),entry.getValue());return result;
    }
    private static boolean eligible(Map<String,Object> node){return node!=null&&!"excluded".equals(node.get("status"))&&!"source_seed".equals(node.get("definitionKind"))&&Arrays.asList("input","decision","content").contains(text(map(node.get("role")).get("kind")));}

    static List<Object> focusDefinitions(List<?> focus,Map<String,Map<String,Object>> nodes){List<Object> definitions=new ArrayList<>();for(Object id:focus){Map<String,Object> node=nodes.get(text(id));if(!eligible(node))continue;Map<String,Object> definition=obj("id",id);for(String key:Arrays.asList("parentId","questionZh","questionEn","optional","options","unit","definitionScope","correction"))if(node.containsKey(key))definition.put(key,node.get(key));definitions.add(definition);}return definitions;}

    static Map<String,Object> read(JsonNode response,List<?> focus,Map<String,Map<String,Object>> nodes) {
        Map<String,Object> alignments=new LinkedHashMap<>();List<Object> issues=new ArrayList<>();for(Object id:focus)if(eligible(nodes.get(text(id))))alignments.put(text(id),unavailable());
        if(alignments.isEmpty())return obj("alignments",alignments,"issues",issues);
        JsonNode rows=response.path("catalogMatches");if(!rows.isArray()){issues.add(obj("reason","catalog_matches_array_required"));return obj("alignments",alignments,"issues",issues);}
        Map<String,Map<String,Object>> baseline=definitions();Set<String> seen=new HashSet<>();
        for(JsonNode row:rows){String id=row.path("nodeId").asText();if(!alignments.containsKey(id)){issues.add(obj("nodeId",id,"reason","catalog_match_node_outside_focus"));continue;}
            if(!seen.add(id)){alignments.put(id,unavailable());issues.add(obj("nodeId",id,"reason","catalog_match_node_duplicated"));continue;}
            List<Object> candidates=new ArrayList<>();Set<String> keys=new HashSet<>();boolean valid=row.path("candidates").isArray();String source=text(nodes.get(id).get("semanticSourceQuote"));
            for(JsonNode candidate:row.path("candidates")){String key=candidate.path("baselineKey").asText(),quote=candidate.path("sourceQuote").asText(),reason=candidate.path("reason").asText();JsonNode confidence=candidate.path("confidence");
                if(!baseline.containsKey(key)||!keys.add(key)||quote.trim().isEmpty()||!source.contains(quote)||reason.trim().isEmpty()||!confidence.isNumber()||!Double.isFinite(confidence.asDouble())||confidence.asDouble()<0||confidence.asDouble()>1||candidate.hasNonNull("baselineVersion")&&!DraftBusinessRules.RULE_VERSION.equals(candidate.path("baselineVersion").asText())){valid=false;continue;}
                candidates.add(obj("baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"reason",reason,"sourceQuote",quote,"confidence",confidence.asDouble(),"compatible",false,"issues",new ArrayList<>()));
            }
            if(!valid){alignments.put(id,unavailable());issues.add(obj("nodeId",id,"reason","catalog_match_candidate_invalid"));continue;}
            Map<String,Object> alignment=unavailable();alignment.put("proposalStatus",candidates.isEmpty()?"unmatched":"matched");alignment.put("proposalReason",candidates.isEmpty()?"No equivalent fixed variable was proposed; review this as a separate variable.":"Review the proposed fixed-variable meaning against the atomic source evidence.");alignment.put("proposedMatches",candidates);alignments.put(id,alignment);
        }
        for(String id:alignments.keySet())if(!seen.contains(id))issues.add(obj("nodeId",id,"reason","catalog_match_node_unanswered"));
        return obj("alignments",alignments,"issues",issues);
    }

    static void apply(List<Map<String,Object>> nodes,List<Map<String,Object>> receipts) {
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();for(Map<String,Object> node:nodes)byId.put(text(node.get("id")),node);Map<String,Object> proposed=new LinkedHashMap<>();
        for(Map<String,Object> receipt:receipts)proposed.putAll(map(receipt.get("catalogAlignments")));
        Map<String,Map<String,Object>> baseline=definitions();for(Map<String,Object> node:nodes)if(eligible(node)&&!Boolean.TRUE.equals(map(node.get("catalogAlignment")).get("confirmed"))){
            String id=text(node.get("id"));Map<String,Object> alignment=proposed.containsKey(id)?JsonUtils.readMap(JsonUtils.write(proposed.get(id))):unavailable();List<Object> candidates=new ArrayList<>();
            for(Object raw:list(alignment.get("proposedMatches"))){Map<String,Object> candidate=JsonUtils.readMap(JsonUtils.write(raw));String key=text(candidate.get("baselineKey"));List<Object> problems=new ArrayList<>();
                if(!DraftBusinessRules.RULE_VERSION.equals(candidate.get("baselineVersion"))||!baseline.containsKey(key))problems.add("baseline_version_changed");else problems.addAll(compatibility(node,key,baseline,byId,proposed));candidate.put("issues",problems);candidate.put("compatible",problems.isEmpty());candidates.add(candidate);
            }
            alignment.put("proposedMatches",candidates);alignment.put("status","unreviewed");alignment.put("confirmed",false);alignment.remove("baselineKey");alignment.remove("baselineVersion");
            if(!"unavailable".equals(alignment.get("proposalStatus")))alignment.put("proposalStatus",candidates.isEmpty()?"unmatched":candidates.size()==1&&Boolean.TRUE.equals(map(candidates.get(0)).get("compatible"))?"matched":"ambiguous");node.put("catalogAlignment",alignment);
        }
    }
    static void refreshSuggestions(Map<String,Object> existing,Map<String,Object> proposed){Map<String,Object> current=map(existing.get("catalogAlignment")),suggestion=map(proposed.get("catalogAlignment"));if(!METHOD.equals(suggestion.get("proposalMethod"))||Boolean.TRUE.equals(current.get("confirmed"))||!current.isEmpty()&&!"unreviewed".equals(current.get("status")))return;Map<String,Object> updated=new LinkedHashMap<>(current);updated.putIfAbsent("status","unreviewed");updated.putIfAbsent("confirmed",false);for(String key:Arrays.asList("proposalMethod","proposalStatus","proposalReason","proposedMatches"))if(suggestion.containsKey(key))updated.put(key,suggestion.get(key));existing.put("catalogAlignment",updated);}
    private static List<Object> compatibility(Map<String,Object> node,String key,Map<String,Map<String,Object>> baseline,Map<String,Map<String,Object>> nodes,Map<String,Object> proposals) {
        Map<String,Object> definition=baseline.get(key);List<Object> issues=new ArrayList<>();if(!kind(text(definition.get("kind"))).equals(text(map(node.get("type")).get("kind"))))issues.add("type_mismatch");
        Object unit=map(node.get("type")).get("unit");if(unit==null)unit=node.get("unit");if(!text(unit).trim().equals(text(definition.get("unit")).trim()))issues.add("unit_mismatch");
        if(Boolean.TRUE.equals(node.get("optional"))!=Boolean.TRUE.equals(definition.get("optional")))issues.add("optional_mismatch");
        if(Arrays.asList("choice","multiselect").contains(kind(text(definition.get("kind"))))&&!optionValues(node).equals(optionValues(definition)))issues.add("option_domain_mismatch");
        String expected=parentKey(key),parent=text(node.get("parentId"));if(!expected.isEmpty()){
            String mapped=proposedKey(nodes.get(parent),proposals);if(parent.isEmpty()||mapped.isEmpty())issues.add("parent_mapping_required");else if(!expected.equals(mapped))issues.add("parent_mismatch");
        }else if(!parent.isEmpty()&&text(node.get("definitionScope")).trim().isEmpty()&&text(map(node.get("catalogAlignment")).get("scope")).trim().isEmpty())issues.add("parent_mapping_required");
        for(Object raw:list(definition.get("columnFields"))){Map<String,Object> child=map(raw);if(Boolean.TRUE.equals(child.get("optional"))||Boolean.TRUE.equals(child.get("hidden")))continue;String childKey=key+"."+child.get("key");boolean found=false;
            for(Map<String,Object> actual:nodes.values())if(eligible(actual)&&Objects.equals(node.get("id"),actual.get("parentId"))&&childKey.equals(proposedKey(actual,proposals)))found=true;if(!found&&!issues.contains("child_mapping_required"))issues.add("child_mapping_required");
        }return issues;
    }
    private static String proposedKey(Map<String,Object> node,Map<String,Object> proposals){if(node==null)return "";Map<String,Object> current=map(node.get("catalogAlignment"));if(Boolean.TRUE.equals(current.get("confirmed"))&&"matched".equals(current.get("status")))return text(current.get("baselineKey"));Map<String,Object> proposal=map(proposals.get(text(node.get("id"))));List<?> candidates=list(proposal.get("proposedMatches"));return !"unavailable".equals(proposal.get("proposalStatus"))&&candidates.size()==1?text(map(candidates.get(0)).get("baselineKey")):"";}
    private static Set<String> optionValues(Map<String,Object> node){Set<String> values=new LinkedHashSet<>();for(Object raw:list(node.get("options")))values.add(text(map(raw).get("value")));return values;}
    private static String kind(String value){if(Arrays.asList("contract","object").contains(value))return "record";if(Arrays.asList("bills","table","records").contains(value))return "list";return Arrays.asList("textarea","longtext").contains(value)?"text":value;}
    private static String parentKey(String key){int dot=key.lastIndexOf('.');return dot<0?"":key.substring(0,dot);}
    private static Map<String,Object> unavailable(){return obj("status","unreviewed","confirmed",false,"proposalMethod",METHOD,"proposalStatus","unavailable","proposalReason","No validated semantic catalog suggestion is available; review the fixed catalog manually.","proposedMatches",new ArrayList<>());}
}
