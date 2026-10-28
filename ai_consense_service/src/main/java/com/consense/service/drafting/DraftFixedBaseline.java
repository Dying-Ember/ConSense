package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Fixed definitions for an exact original package. The descriptor grants no editing authority. */
final class DraftFixedBaseline {
    static final String KIND="fixed_catalog",COVERAGE="fixed_rules_with_unresolved_scopes";
    private static final List<String> DOCUMENTS=Arrays.asList("NTT","SCT","SCC");
    private DraftFixedBaseline() { }

    /** Only a blank graph may receive these candidates; reviewed or discovered work wins. */
    static Map<String,Object> restore(Map<String,Object> graph) {
        if(!nodes(graph).isEmpty()||!exactPackage(graph))return graph;
        Map<String,Object> result=copy(graph),state=state(result);
        Map<String,Map<String,Object>> definitions=definitions();Map<String,Object> ids=nodeIds(definitions);
        List<Object> variables=new ArrayList<>();List<String> roots=new ArrayList<>();
        for(Map.Entry<String,Map<String,Object>> entry:definitions.entrySet()) {
            String key=entry.getKey();Map<String,Object> node=candidate(result,key,entry.getValue(),ids);
            variables.add(node);if(!key.contains("."))roots.add(text(node.get("id")));
        }
        state.put("variables",variables);state.put("blank",false);
        state.put("fixedRuleBaseline",obj("kind",KIND,"version",1,"ruleVersion",DraftBusinessRules.RULE_VERSION,
                "sourceIdentities",sourceIdentities(result),"nodeIdsByBaselineKey",ids,"coverage",COVERAGE,"projectAnswersImported",false));
        state.put("baselineRestoration",obj("kind","fixed_catalog_candidates","status","partial_requires_review",
                "fieldIds",roots,"projectAnswersImported",false));
        DraftReviewGraph.validate(result);return result;
    }

    /** Recompute the package and catalog identity; never trust the saved descriptor alone. */
    static boolean installed(Map<String,Object> graph) {
        if(!exactPackage(graph))return false;
        Map<String,Object> descriptor=map(state(graph).get("fixedRuleBaseline"));
        return KIND.equals(descriptor.get("kind"))&&Objects.equals(1,descriptor.get("version"))
                &&DraftBusinessRules.RULE_VERSION.equals(descriptor.get("ruleVersion"))
                &&COVERAGE.equals(descriptor.get("coverage"))&&Boolean.FALSE.equals(descriptor.get("projectAnswersImported"))
                &&sourceIdentities(graph).equals(map(descriptor.get("sourceIdentities")))
                &&nodeIds(definitions()).equals(map(descriptor.get("nodeIdsByBaselineKey")));
    }

    /** Provenance for a fixed definition, not for an amendment range or project answer. */
    static boolean exactSource(Map<String,Object> graph,Map<String,Object> node) {
        if(!installed(graph)||!DraftCatalogAlignment.businessCandidate(node))return false;
        String key=text(map(node.get("catalogAlignment")).get("baselineKey"));
        Map<String,Map<String,Object>> definitions=definitions();Map<String,Object> definition=definitions.get(key);
        if(definition==null)return false;Map<String,Object> ids=nodeIds(definitions);
        Map<String,Object> expected=candidate(graph,key,definition,ids);
        for(String property:Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","optional","options","docIds"))
            if(!Objects.equals(expected.get(property),node.get(property)))return false;
        Map<String,Object> alignment=map(node.get("catalogAlignment"));
        if(!"matched".equals(alignment.get("status"))||!DraftBusinessRules.RULE_VERSION.equals(alignment.get("baselineVersion"))
                ||!text(alignment.get("scope")).isEmpty()||!(alignment.get("confirmed") instanceof Boolean))return false;
        if(node.containsKey("definitionOrigin")||!text(node.get("definitionScope")).isEmpty()||node.containsKey("fixedValue")
                ||!map(node.get("correction")).isEmpty()||!list(node.get("editTargets")).isEmpty()||!list(node.get("relatedInputIds")).isEmpty()
                ||node.containsKey("factProjectionReview")||node.containsKey("splitReview")
                ||!text(node.get("unit")).isEmpty()&&!Objects.equals(node.get("unit"),map(expected.get("type")).get("unit")))return false;
        if(!sameCondition(map(expected.get("applicability")),map(node.get("applicability"))))return false;
        // A changed parent or list column must not retain fixed-rule interpretation through its old key.
        Map<String,Map<String,Object>> byId=byId(graph);if(byId==null)return false;
        if(!text(node.get("parentId")).isEmpty()) {
            Map<String,Object> parent=byId.get(text(node.get("parentId")));
            if(parent==null||!key.substring(0,key.lastIndexOf('.')).equals(map(parent.get("catalogAlignment")).get("baselineKey"))||!exactSource(graph,parent))return false;
        }
        Map<String,Object> current=byId.get(text(node.get("id")));if(current==null)return false;
        return current==node||exactSource(graph,current);
    }

    /** Called only by the explicit human group-confirmation action, before ordinary review stamping. */
    static Map<String,Object> confirmGroup(Map<String,Object> graph,List<String> rootIds,String reason) {
        if(!installed(graph))throw invalid("FIXED_BASELINE_SOURCE_OR_RULE_CHANGED");
        if(reason==null||reason.trim().isEmpty())throw invalid("FIXED_BASELINE_REVIEW_REASON_REQUIRED");
        if(rootIds==null||rootIds.isEmpty()||new HashSet<>(rootIds).size()!=rootIds.size())throw invalid("FIXED_BASELINE_GROUP_REQUIRED");
        Map<String,Object> result=copy(graph);Map<String,Map<String,Object>> byId=byId(result);
        if(byId==null)throw invalid("FIXED_BASELINE_NODE_IDENTITY_CHANGED");
        Set<String> selected=new LinkedHashSet<>();
        for(String id:rootIds) {
            Map<String,Object> root=byId.get(id);
            if(root==null||!text(root.get("parentId")).isEmpty()||!exactSource(result,root))throw invalid("FIXED_BASELINE_DEFINITION_CHANGED: "+id);
            String key=text(map(root.get("catalogAlignment")).get("baselineKey"));
            for(Map.Entry<String,Object> entry:map(map(state(result).get("fixedRuleBaseline")).get("nodeIdsByBaselineKey")).entrySet())
                if(entry.getKey().equals(key)||entry.getKey().startsWith(key+"."))selected.add(text(entry.getValue()));
        }
        for(String id:selected) {
            Map<String,Object> node=byId.get(id);if(!exactSource(result,node))throw invalid("FIXED_BASELINE_DEFINITION_CHANGED: "+id);
            for(Object raw:list(map(node.get("applicability")).get("clauses"))) {
                String dependency=text(map(raw).get("inputId"));Map<String,Object> source=byId.get(dependency);
                if(!selected.contains(dependency)&&(!exactSource(result,source)||!Boolean.TRUE.equals(map(source.get("catalogAlignment")).get("confirmed"))))
                    throw invalid("FIXED_BASELINE_DEPENDENCY_REVIEW_REQUIRED: "+dependency);
            }
        }
        for(String id:selected) {
            Map<String,Object> node=byId.get(id),alignment=map(node.get("catalogAlignment"));
            alignment.put("confirmed",true);alignment.put("reason",reason.trim());node.put("status","confirmed");
            node.put("definitionReview",obj("confirmed",true,"basis",null));
        }
        // Parent prerequisites are fingerprinted first, so dependent conditions use their final review state.
        Set<String> stamped=new HashSet<>();for(String id:selected)confirmCondition(result,id,byId,selected,stamped,new HashSet<>(),reason.trim());
        DraftReviewGraph.validate(result);return result;
    }

    static void confirmCondition(Map<String,Object> graph,String id,Map<String,Map<String,Object>> nodes,Set<String> selected,
                                         Set<String> stamped,Set<String> visiting,String reason) {
        if(stamped.contains(id))return;if(!visiting.add(id))throw invalid("FIXED_BASELINE_CONDITION_CYCLE");
        Map<String,Object> node=nodes.get(id),condition=map(node.get("applicability"));
        if(!condition.isEmpty()) {
            for(Object raw:list(condition.get("clauses"))){String dependency=text(map(raw).get("inputId"));if(selected.contains(dependency))confirmCondition(graph,dependency,nodes,selected,stamped,visiting,reason);}
            condition.put("confirmed",true);condition.put("reason",reason);
            condition.put("bindingFingerprint",DraftReviewGraph.applicabilityFingerprint(graph,node,list(condition.get("clauses")),nodes));
        }
        visiting.remove(id);stamped.add(id);
    }

    static Map<String,Object> candidate(Map<String,Object> graph,String key,Map<String,Object> field,Map<String,Object> ids) {
        int dot=key.lastIndexOf('.');String parent=dot<0?null:text(ids.get(key.substring(0,dot)));
        Map<String,Object> type=obj("kind",kind(field));if(field.containsKey("unit"))type.put("unit",field.get("unit"));
        List<Object> options=new ArrayList<>();for(Object raw:list(field.get("options"))){Map<String,Object> option=map(raw);options.add(obj("value",option.get("value"),"labelZh",label(option.get("label"),false),"labelEn",label(option.get("label"),true)));}
        Map<String,Object> node=obj("id",ids.get(key),"parentId",parent,"nameZh",label(field.get("label"),false),"nameEn",label(field.get("label"),true),
                "questionZh",label(field.get("label"),false),"questionEn",label(field.get("label"),true),"type",type,"role",obj("kind","input","zh","变量","en","Variable"),
                "optional",Boolean.TRUE.equals(field.get("optional")),"options",options,"docIds",selectedIds(graph),
                "sourceAnchors",new ArrayList<>(),"editTargets",new ArrayList<>(),"sourceUnitIds",new ArrayList<>(),"relatedInputIds",new ArrayList<>(),
                "relations",new ArrayList<>(),"targets",new ArrayList<>(),"evidence",new ArrayList<>(),
                "status","pending","definitionKind","fixed_catalog","definitionReview",obj("confirmed",false),
                "catalogAlignment",obj("status","matched","baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",false));
        List<Object> clauses=new ArrayList<>();for(Object raw:list(map(field.get("condition")).get("all"))) {
            Map<String,Object> term=map(raw);String dependency=text(term.get("field"));if(!ids.containsKey(dependency))throw invalid("FIXED_BASELINE_CONDITION_REFERENCE_MISSING: "+dependency);
            Map<String,Object> clause=obj("inputId",ids.get(dependency),"baselineKey",dependency,"operator",term.get("operator"));if(term.containsKey("value"))clause.put("value",term.get("value"));clauses.add(clause);
        }
        if(!clauses.isEmpty())node.put("applicability",obj("kind","fixed_catalog","ruleVersion",DraftBusinessRules.RULE_VERSION,"targetBaselineKey",key,
                "mode","all","clauses",clauses,"confirmed",false,"reason","Restored explicit catalog condition; human group review is required."));
        return node;
    }
    private static boolean sameCondition(Map<String,Object> expected,Map<String,Object> actual) {
        if(expected.isEmpty())return actual.isEmpty();
        for(String key:Arrays.asList("kind","ruleVersion","targetBaselineKey","mode","clauses"))if(!Objects.equals(expected.get(key),actual.get(key)))return false;
        return actual.get("confirmed") instanceof Boolean&&actual.get("reason") instanceof String
                &&new HashSet<>(Arrays.asList("kind","ruleVersion","targetBaselineKey","mode","clauses","confirmed","reason","bindingFingerprint")).containsAll(actual.keySet());
    }
    private static boolean exactPackage(Map<String,Object> graph) {
        Map<String,Object> selection=map(graph.get("selectedDocuments"));if(!selection.keySet().equals(new LinkedHashSet<>(DOCUMENTS)))return false;
        for(String file:DOCUMENTS) {
            Map<String,Object> found=null;for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if(text(selection.get(file)).equals(text(source.get("id")))){if(found!=null)return false;found=source;}}
            if(found==null||!file.equals(found.get("fileKey"))||!"bound".equals(found.get("evidenceStatus"))||!"docx".equals(found.get("format"))
                    ||!DraftTemplateReadingSources.catalogueSourceVerified(file,text(found.get("sourceSha256"))))return false;
        }
        return true;
    }
    private static Map<String,Object> sourceIdentities(Map<String,Object> graph) {
        Map<String,Object> result=new LinkedHashMap<>(),selection=map(graph.get("selectedDocuments"));
        for(String file:DOCUMENTS)for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if(text(selection.get(file)).equals(text(source.get("id"))))result.put(file,obj("sourceId",text(source.get("id")),"sourceSha256",source.get("sourceSha256")));}
        return result;
    }
    private static List<Object> selectedIds(Map<String,Object> graph){List<Object> ids=new ArrayList<>();for(String file:DOCUMENTS)ids.add(map(graph.get("selectedDocuments")).get(file));return ids;}
    private static Map<String,Map<String,Object>> definitions() {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(Map.Entry<String,Map<String,Object>> entry:DraftCatalogAlignment.definitions().entrySet()) {
            String key=entry.getKey();Map<String,Object> field=entry.getValue();int dot=key.lastIndexOf('.');
            if(key.equals("contractTitle")||key.startsWith("contractTitle.")||Boolean.TRUE.equals(field.get("hidden"))||"id".equals(field.get("key"))&&!Boolean.FALSE.equals(field.get("hidden")))continue;
            if(dot>=0&&!result.containsKey(key.substring(0,dot)))continue;result.put(key,field);
        }
        return result;
    }
    private static Map<String,Object> nodeIds(Map<String,Map<String,Object>> definitions) {
        Map<String,Object> result=new LinkedHashMap<>();for(String key:definitions.keySet())result.put(key,key.length()<=57?"fixed-"+key:"fixed-"+DraftAdoption.hash(key).substring(0,32));return result;
    }
    private static Map<String,Map<String,Object>> byId(Map<String,Object> graph) {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Map<String,Object> node:nodes(graph))if(result.put(text(node.get("id")),node)!=null)return null;return result;
    }
    private static String kind(Map<String,Object> field){String value=text(field.get("kind"));return Arrays.asList("contract","object").contains(value)?"record":Arrays.asList("bills","table","records").contains(value)?"list":Arrays.asList("textarea","longtext").contains(value)?"text":value;}
    private static String label(Object raw,boolean english){Map<String,Object> labels=map(raw);String value=text(labels.get(english?"en":"zhHans"));if(value.isEmpty())value=text(labels.get(english?"zhHans":"zhHant"));return value.isEmpty()?text(labels.get("en")):value;}
    private static Map<String,Object> copy(Map<String,Object> graph){return JsonUtils.readMap(JsonUtils.write(graph));}
    private static BizException invalid(String code){return new BizException(4007,code);}
}
