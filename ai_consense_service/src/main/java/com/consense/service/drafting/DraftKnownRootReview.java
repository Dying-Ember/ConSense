package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Human group review of current instruction references; it never confirms amendment ranges. */
final class DraftKnownRootReview {
    private DraftKnownRootReview() { }

    static boolean candidate(Map<String,Object> graph,Map<String,Object> field) {
        Map<String,Object> reference=map(field.get("currentDefinitionReference")),alignment=map(field.get("catalogAlignment"));
        return "current_fixed_reference".equals(field.get("definitionKind"))&&text(field.get("parentId")).isEmpty()
                &&"pending".equals(field.get("status"))&&!Boolean.TRUE.equals(field.get("manual"))
                &&"registered_current_instruction".equals(reference.get("kind"))&&Boolean.FALSE.equals(reference.get("existing"))
                &&DraftBusinessRules.RULE_VERSION.equals(reference.get("ruleVersion"))
                &&"matched".equals(alignment.get("status"))&&!Boolean.TRUE.equals(alignment.get("confirmed"))
                &&DraftBusinessRules.RULE_VERSION.equals(alignment.get("baselineVersion"))
                &&!text(reference.get("baselineKey")).isEmpty()&&Objects.equals(reference.get("baselineKey"),alignment.get("baselineKey"));
    }

    static Map<String,Object> confirmGroup(Map<String,Object> graph,List<JsonNode> readings,List<String> selectedIds,String reason) {
        if(reason==null||reason.trim().isEmpty())throw invalid("KNOWN_ROOT_REVIEW_REASON_REQUIRED");
        if(selectedIds==null||selectedIds.isEmpty()||selectedIds.contains(null)||new HashSet<>(selectedIds).size()!=selectedIds.size())throw invalid("KNOWN_ROOT_REVIEW_SELECTION_REQUIRED");
        Map<String,Object> result=JsonUtils.readMap(JsonUtils.write(graph)),fresh=JsonUtils.readMap(JsonUtils.write(graph));
        state(fresh).put("variables",new ArrayList<>());
        Map<String,JsonNode> bySource=new LinkedHashMap<>();for(JsonNode reading:readings)if(bySource.put(reading.path("id").asText(),reading)!=null)throw invalid("KNOWN_ROOT_REVIEW_SOURCE_AMBIGUOUS");
        DraftKnownRoots.Result rebuilt=DraftKnownRoots.build(bySource,DraftReviewDiscovery.sourceTasks(readings),DraftDiscoveryGroups.currentTemplateIndexes(bySource),fresh);
        Map<String,Map<String,Object>> actual=index(nodes(result)),expected=index(rebuilt.roots());Set<String> selected=new LinkedHashSet<>();
        for(String id:selectedIds){Map<String,Object> root=actual.get(id);if(root!=null&&candidate(result,root)){
            if(!expected.containsKey(id))throw invalid("KNOWN_ROOT_REVIEW_CURRENT_REFERENCE_REQUIRED: "+id);
            selected.add(id);for(Map<String,Object> child:expected.values())if(descendsFrom(text(child.get("id")),id,expected))selected.add(text(child.get("id")));
        }}
        if(selected.isEmpty())return result;
        for(String id:selected){Map<String,Object> node=actual.get(id),definition=expected.get(id);
            if(node==null||!rebuilt.accepts(node)||!sourceProofsEqual(node,definition))throw invalid("KNOWN_ROOT_REVIEW_DEFINITION_CHANGED: "+id);
            for(Object raw:list(map(definition.get("applicability")).get("clauses"))){String dependency=text(map(raw).get("inputId"));
                if(!selected.contains(dependency)&&!reviewedPrerequisite(actual.get(dependency),expected.get(dependency)))throw invalid("KNOWN_ROOT_REVIEW_DEPENDENCY_REQUIRED: "+dependency);
            }
        }
        for(String id:selected){Map<String,Object> node=actual.get(id),alignment=map(node.get("catalogAlignment"));alignment.put("confirmed",true);alignment.put("reason",reason.trim());node.put("status","confirmed");node.put("definitionReview",obj("confirmed",true,"basis",null));}
        Set<String> stamped=new HashSet<>();for(String id:selected)DraftFixedBaseline.confirmCondition(result,id,actual,selected,stamped,new HashSet<>(),reason.trim());
        validate(result);return result;
    }

    private static boolean reviewedPrerequisite(Map<String,Object> actual,Map<String,Object> expected) {
        if(actual==null||expected==null||!"confirmed".equals(actual.get("status"))||!DraftCatalogAlignment.reviewed(actual)||!Boolean.TRUE.equals(map(actual.get("definitionReview")).get("confirmed"))||!sourceProofsEqual(actual,expected))return false;
        Map<String,Object> normalized=JsonUtils.readMap(JsonUtils.write(actual));
        Map<String,Object> alignment=map(normalized.get("catalogAlignment")),wanted=map(expected.get("catalogAlignment"));
        for(String property:Arrays.asList("status","baselineKey","baselineVersion","scope"))if(!same(alignment.get(property),wanted.get(property)))return false;
        Map<String,Object> condition=map(normalized.get("applicability")),expectedCondition=map(expected.get("applicability"));
        for(String property:Arrays.asList("kind","ruleVersion","targetBaselineKey","mode","clauses"))if(!same(condition.get(property),expectedCondition.get(property)))return false;
        for(String property:Arrays.asList("status","manual","catalogAlignment","definitionReview","applicability")){if(expected.containsKey(property))normalized.put(property,expected.get(property));else normalized.remove(property);}
        for(String property:Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","optional","options","unit","fixedValue","definitionScope","definitionKind"))if(!same(normalized.get(property),expected.get(property)))return false;
        return true;
    }
    private static boolean sourceProofsEqual(Map<String,Object> actual,Map<String,Object> expected) {
        if(expected==null||!same(map(actual.get("currentDefinitionReference")).get("sourceReferences"),map(expected.get("currentDefinitionReference")).get("sourceReferences")))return false;
        List<?> wanted=list(expected.get("sourceAnchors"));if(wanted.isEmpty())return false;
        for(Object raw:wanted){Map<String,Object> proof=map(raw);boolean found=false;for(Object candidate:list(actual.get("sourceAnchors"))){Map<String,Object> anchor=map(candidate);boolean matches=true;
            for(String property:Arrays.asList("docId","sourceDocumentId","sourceSha256","sourceObservationId","sourceObservation","observedRanges","text","analysisRevision","analysisInputSha256","analysisSha256"))if(!same(anchor.get(property),proof.get(property))){matches=false;break;}
            if(matches){found=true;break;}
        }if(!found)return false;}return true;
    }
    private static boolean same(Object first,Object second){return Objects.equals(JsonUtils.parse(JsonUtils.write(first)),JsonUtils.parse(JsonUtils.write(second)));}
    private static BizException invalid(String code){return new BizException(4007,code);}
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> nodes){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Map<String,Object> node:nodes)if(result.put(text(node.get("id")),node)!=null)throw invalid("KNOWN_ROOT_REVIEW_NODE_AMBIGUOUS");return result;}
    private static boolean descendsFrom(String id,String parent,Map<String,Map<String,Object>> nodes){Set<String> seen=new HashSet<>();while(nodes.containsKey(id)&&seen.add(id)){id=text(nodes.get(id).get("parentId"));if(parent.equals(id))return true;if(id.isEmpty())return false;}return false;}
}
