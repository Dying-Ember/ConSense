package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure interpretation of the saved review. Display links never become executable dependencies. */
public final class DraftReviewGraph {
    private DraftReviewGraph() { }
    private static final Set<String> TYPES = set("boolean","number","text","date","choice","multiselect","record","list");
    private static final Set<String> OPERATORS = set("yes","no","eq","ne","gt","ge","lt","le","has","lacks","known","unknown","empty","notempty");
    private static final Set<String> ACTIONS = set("keep","replace","fill","insert","delete","unused","check");
    private static final Set<String> ROLES = set("input","decision","content","derived","verification","system","fixed");
    public static Map<String,Object> state(Map<String,Object> graph) {
        Object nested=graph.get("graph"); if(nested instanceof Map)return map(nested);
        nested=graph.get("state"); return nested instanceof Map?map(nested):graph;
    }
    public static List<Map<String,Object>> nodes(Map<String,Object> graph) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Object value:list(state(graph).get("variables")))result.add(map(value));
        return result;
    }
    public static void validate(Map<String,Object> graph) {
        if(!(state(graph).get("variables") instanceof List))throw invalid("GRAPH_VARIABLES_REQUIRED");
        DraftCatalogAlignment.validate(graph);
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();
        for(Map<String,Object> node:nodes(graph)) {
            String id=text(node.get("id"));
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")||byId.put(id,node)!=null)throw invalid("GRAPH_ID_INVALID_OR_DUPLICATED: "+id);
            if(!TYPES.contains(kind(node))||!ROLES.contains(role(node)))throw invalid("GRAPH_TYPE_OR_ROLE_UNSUPPORTED: "+id);
            if(!active(node))continue;
            if(text(node.get("nameZh")).trim().isEmpty()&&text(node.get("nameEn")).trim().isEmpty())throw invalid("GRAPH_NAME_REQUIRED: "+id);
            Set<String> options=new HashSet<>();
            for(Object raw:list(node.get("options")))if(text(map(raw).get("value")).isEmpty()||!options.add(text(map(raw).get("value"))))throw invalid("GRAPH_OPTION_INVALID: "+id);
            if(set("choice","multiselect").contains(kind(node))&&options.isEmpty())throw invalid("GRAPH_OPTIONS_REQUIRED: "+id);
        }
        Map<String,Set<String>> dependencies=new LinkedHashMap<>(),valueDependencies=new LinkedHashMap<>();
        for(Map<String,Object> node:byId.values())if(active(node)) {
            String id=text(node.get("id")),parent=text(node.get("parentId")); Set<String> refs=new LinkedHashSet<>(),valueRefs=new LinkedHashSet<>();
            validateFactProjection(graph,node,byId);
            Set<String> declared=new HashSet<>();for(Object doc:list(node.get("docIds")))declared.add(text(doc));
            for(String group:Arrays.asList("sourceAnchors","editTargets","evidence"))for(Object raw:list(node.get(group))){String doc=text(map(raw).get("docId"));if(!doc.isEmpty()&&!declared.contains(doc))throw invalid("GRAPH_SOURCE_OUTSIDE_DECLARED_DOCUMENTS: "+id);}
            if(!parent.isEmpty()) {
                requireNode(byId,parent); if(!set("record","list").contains(kind(byId.get(parent))))throw invalid("GRAPH_PARENT_NOT_A_GROUP: "+id);
                if(!"list".equals(kind(byId.get(parent))))valueRefs.add(parent);
            }
            if(node.containsKey("applicability")) {
                Map<String,Object> applicability=map(node.get("applicability"));
                if(!input(node)||!parent.isEmpty()||!"fixed_catalog".equals(applicability.get("kind"))||!"all".equals(applicability.get("mode"))||!(applicability.get("confirmed") instanceof Boolean)||!(applicability.get("reason") instanceof String)||text(applicability.get("reason")).trim().isEmpty()||!(applicability.get("clauses") instanceof List)||list(applicability.get("clauses")).isEmpty())throw invalid("GRAPH_APPLICABILITY_INVALID: "+id);
                boolean bound=applicabilityBindingValid(graph,node,byId);
                for(Object raw:list(applicability.get("clauses"))) {
                    Map<String,Object> clause=map(raw);String ref=text(clause.get("inputId"));
                    if(ref.isEmpty()||text(clause.get("baselineKey")).isEmpty()||text(clause.get("operator")).isEmpty())throw invalid("GRAPH_APPLICABILITY_CLAUSE_INVALID: "+id);
                    if(bound&&byId.containsKey(ref)&&active(byId.get(ref))){if(sourceCheck(byId.get(ref)))throw invalid("GRAPH_SOURCE_CHECK_NOT_A_VALUE: "+ref);refs.add(ref);valueRefs.add(ref);}
                }
            }
            Set<String> cases=new HashSet<>();
            boolean rebind=Boolean.TRUE.equals(map(node.get("correction")).get("needsRebind"));
            for(Object raw:branches(node)) {
                Map<String,Object> branch=map(raw); String caseId=text(branch.get("id"));
                if(caseId.isEmpty()||!cases.add(caseId)||!set("all","any").contains(text(branch.get("mode")))||list(branch.get("clauses")).isEmpty())throw invalid("GRAPH_CASE_INVALID: "+id);
                String scope=text(branch.get("scopeId")),mode=text(branch.get("scopeMode"));
                if(!scope.isEmpty()) {requireValueNode(byId,scope,rebind); if((!rebind&&!"list".equals(kind(byId.get(scope))))||!set("each","any","all","none").contains(mode))throw invalid("GRAPH_SCOPE_INVALID: "+id);refs.add(scope);if("derived".equals(role(node)))valueRefs.add(scope);}
                else if(!mode.isEmpty())throw invalid("GRAPH_SCOPE_REQUIRED: "+id);
                for(Object term:list(branch.get("clauses"))) {
                    Map<String,Object> clause=map(term); String input=text(clause.get("inputId"));requireValueNode(byId,input,rebind);
                    if(!OPERATORS.contains(text(clause.get("operator"))))throw invalid("GRAPH_OPERATOR_UNSUPPORTED: "+id);
                    if(!rebind)validateOperator(byId.get(input),clause);
                    if(!rebind&&listAncestor(byId,input)!=null&&!Objects.equals(scope,listAncestor(byId,input)))throw invalid("GRAPH_SCOPE_CROSSES_LISTS: "+id);
                    refs.add(input);
                    if("derived".equals(role(node)))valueRefs.add(input);
                }
            }
            for(Object raw:list(node.get("editTargets"))) {
                Map<String,Object> target=map(raw);
                if(!ACTIONS.contains(actionKind(target.get("action"))))throw invalid("GRAPH_ACTION_UNSUPPORTED: "+id);
                boolean targetRebind=rebind||Boolean.TRUE.equals(target.get("needsRebind"));
                String branch=text(target.get("branchId"));if(!targetRebind&&!branch.isEmpty()&&!cases.contains(branch))throw invalid("GRAPH_TARGET_CASE_MISSING: "+id);
                String value=text(target.get("valueSourceId"));if(!value.isEmpty()){requireValueNode(byId,value,targetRebind);if(!value.equals(id))refs.add(value);}
                String itemKey=text(target.get("itemKey"));if(!itemKey.isEmpty()) {
                    requireValueNode(byId,itemKey,targetRebind);
                    if(!targetRebind){String itemScope="";for(Object branchRaw:branches(node)){Map<String,Object> condition=map(branchRaw);if(branch.equals(text(condition.get("id")))&&"each".equals(text(condition.get("scopeMode"))))itemScope=text(condition.get("scopeId"));}
                        if(itemScope.isEmpty()||!itemScope.equals(listAncestor(byId,itemKey))||!set("boolean","number","text","date","choice").contains(kind(byId.get(itemKey))))throw invalid("GRAPH_ITEM_SCOPE_INVALID: "+id);}
                    refs.add(itemKey);
                }
                if("join".equals(text(target.get("valueMode")))) {
                    String listId=text(target.get("listId"));requireValueNode(byId,listId,targetRebind);
                    if(!targetRebind&&(!"list".equals(kind(byId.get(listId)))||value.isEmpty()||!listId.equals(listAncestor(byId,value))))throw invalid("GRAPH_JOIN_SCOPE_INVALID: "+id);
                    if(!targetRebind&&!branch.isEmpty())for(Object branchRaw:branches(node)){Map<String,Object> condition=map(branchRaw);if(branch.equals(text(condition.get("id")))&&"each".equals(text(condition.get("scopeMode")))&&!listId.equals(text(condition.get("scopeId"))))throw invalid("GRAPH_JOIN_SCOPE_INVALID: "+id);}
                    refs.add(listId);
                }
            }
            dependencies.put(id,refs);
            if(!sourceCheck(node))valueDependencies.put(id,valueRefs);
        }
        // Branch scope supplies list dependencies; record children also read their parent.
        for(Map<String,Object> node:byId.values()) {
            Set<String> ancestors=new HashSet<>();String id=text(node.get("id"));
            while(!id.isEmpty()){if(!ancestors.add(id))throw invalid("GRAPH_PARENT_CYCLE");id=text(map(byId.get(id)).get("parentId"));}
        }
        for(String id:valueDependencies.keySet())cycle(id,valueDependencies,new HashSet<>(),new HashSet<>());
        validateEditions(graph,byId,dependencies);
        Context context=new Context(graph,Collections.emptyMap());
        for(Map<String,Object> node:byId.values())if(active(node)){
            Map<String,Object> field=withChildren(node,context,false);
            if(set("fixed","system").contains(role(node))&&node.get("fixedValue")!=null)typedValue(field,node.get("fixedValue"));
            if("derived".equals(role(node))&&!ruleNeedsReview(node))
                for(Object raw:branches(node))if(map(raw).get("resultValue")!=null)typedValue(field,map(raw).get("resultValue"));
        }
    }
    private static void validateFactProjection(Map<String,Object> graph,Map<String,Object> node,Map<String,Map<String,Object>> nodes) {
        if(!node.containsKey("factProjectionReview"))return;
        Map<String,Object> review=map(node.get("factProjectionReview")),original=map(review.get("original")),root=nodes.get(text(review.get("rootId")));
        boolean unchanged=Objects.equals(node.get("correction"),review.get("convertedCorrection"));
        Map<String,Object> resolution=map(review.get("previousRuleResolution"));
        if(original.get("correction")!=null) {
            if(!(original.get("targetBindings") instanceof List)||!set("pending","resolved").contains(text(resolution.get("status"))))throw invalid("GRAPH_FACT_PROJECTION_HISTORY_REQUIRED: "+node.get("id"));
            Set<String> ids=new HashSet<>();for(Object raw:list(original.get("targetBindings"))){Map<String,Object> target=map(raw);String targetId=text(target.get("id"));if(targetId.isEmpty()||!ids.add(targetId))throw invalid("GRAPH_FACT_PROJECTION_HISTORY_INVALID: "+node.get("id"));}
            if("resolved".equals(resolution.get("status"))) {
                String decision=text(resolution.get("decision"));if(text(resolution.get("reason")).trim().isEmpty()||!set("replace","rebuilt").contains(decision))throw invalid("GRAPH_FACT_PROJECTION_HISTORY_REASON_REQUIRED: "+node.get("id"));
                Set<String> prior=ruleInputs(map(original.get("correction")));prior.remove(text(node.get("id")));
                if("replace".equals(decision)&&(!unchanged||!prior.isEmpty()||!"rule".equals(text(map(original.get("correction")).get("kind")))))throw invalid("GRAPH_FACT_PROJECTION_EXTRA_CONDITIONS_REQUIRE_REBUILD: "+node.get("id"));
                if("rebuilt".equals(decision)){Set<String> current=ruleInputs(map(node.get("correction")));if(unchanged||!current.containsAll(prior)||!current.contains(text(review.get("rootId"))))throw invalid("GRAPH_FACT_PROJECTION_REBUILD_INCOMPLETE: "+node.get("id"));}
            }
        }
        // A user-edited rule keeps its history and saves as unresolved rather than silently inheriting the shortcut.
        if(!unchanged&&!("resolved".equals(resolution.get("status"))&&"rebuilt".equals(resolution.get("decision"))))return;
        String id=text(node.get("id")),key=text(review.get("baselineKey")),selected=text(review.get("branchValue"));Object polarity=review.get("branchPolarity");
        if(root==null||!active(root)||DraftCatalogAlignment.hasUnverifiedSccSource(graph,node)||DraftCatalogAlignment.hasUnverifiedSccSource(graph,root)||!"boolean".equals(kind(node))||!"derived".equals(role(node))||!"choice".equals(kind(root))||!input(root)||!Boolean.TRUE.equals(review.get("exhaustiveConfirmed"))||!(polarity instanceof Boolean)||!set("input","decision","content").contains(text(map(original.get("role")).get("kind"))))throw invalid("GRAPH_FACT_PROJECTION_INVALID: "+id);
        Map<String,Object> rootAlignment=map(root.get("catalogAlignment"));if(!"matched".equals(rootAlignment.get("status"))||!Boolean.TRUE.equals(rootAlignment.get("confirmed"))||!key.equals(rootAlignment.get("baselineKey"))||!DraftBusinessRules.RULE_VERSION.equals(rootAlignment.get("baselineVersion")))throw invalid("GRAPH_FACT_PROJECTION_ROOT_INVALID: "+id);
        String origin=text(review.get("origin"));
        if("manual".equals(origin)) {
            if(!(review.get("reason") instanceof String)||text(review.get("reason")).trim().isEmpty())throw invalid("GRAPH_FACT_PROJECTION_REASON_REQUIRED: "+id);
        } else {
            if(!origin.isEmpty()&&!"model".equals(origin))throw invalid("GRAPH_FACT_PROJECTION_ORIGIN_INVALID: "+id);
            boolean suggested=false;for(Object raw:list(map(original.get("catalogAlignment")).get("proposedMatches"))){Map<String,Object> candidate=map(raw),projection=map(candidate.get("factProjection"));if(key.equals(candidate.get("baselineKey"))&&!"conflict".equals(candidate.get("semanticStatus"))&&selected.equals(projection.get("branchValue"))&&Objects.equals(polarity,projection.get("branchPolarity")))suggested=true;}
            if(!suggested)throw invalid("GRAPH_FACT_PROJECTION_SUGGESTION_REQUIRED: "+id);
        }
        Set<String> options=new LinkedHashSet<>();for(Object raw:list(root.get("options")))options.add(text(map(raw).get("value")));
        if(options.size()!=2||!options.contains(selected))throw invalid("GRAPH_FACT_PROJECTION_BRANCH_INVALID: "+id);
        if(!unchanged)return;
        if(branches(node).size()!=2)throw invalid("GRAPH_FACT_PROJECTION_BRANCH_INVALID: "+id);
        Set<String> seen=new HashSet<>();for(Object raw:branches(node)){Map<String,Object> branch=map(raw);List<?> clauses=list(branch.get("clauses"));if(!"all".equals(branch.get("mode"))||clauses.size()!=1||!(branch.get("resultValue") instanceof Boolean))throw invalid("GRAPH_FACT_PROJECTION_BRANCH_INVALID: "+id);Map<String,Object> clause=map(clauses.get(0));String value=text(clause.get("value"));if(!text(root.get("id")).equals(clause.get("inputId"))||!"eq".equals(clause.get("operator"))||!options.contains(value)||!seen.add(value)||!Objects.equals(branch.get("resultValue"),selected.equals(value)?polarity:!((Boolean)polarity)))throw invalid("GRAPH_FACT_PROJECTION_BRANCH_INVALID: "+id);}
    }
    private static Set<String> ruleInputs(Map<String,Object> correction) {
        Set<String> result=new LinkedHashSet<>();List<Object> cases=new ArrayList<>(list(correction.get("branches")));if(!list(correction.get("clauses")).isEmpty())cases.add(correction);
        for(Object raw:cases){Map<String,Object> branch=map(raw);if(!text(branch.get("scopeId")).isEmpty())result.add(text(branch.get("scopeId")));for(Object value:list(branch.get("clauses"))){String id=text(map(value).get("inputId"));if(!id.isEmpty())result.add(id);}}
        return result;
    }
    static boolean applicabilityBindingValid(Map<String,Object> graph,Map<String,Object> node,Map<String,Map<String,Object>> nodes) {
        Map<String,Object> rule=map(node.get("applicability")),alignment=map(node.get("catalogAlignment"));
        String target=text(rule.get("targetBaselineKey"));
        if(!Boolean.TRUE.equals(rule.get("confirmed"))||!DraftBusinessRules.RULE_VERSION.equals(rule.get("ruleVersion"))||!"matched".equals(alignment.get("status"))||!Boolean.TRUE.equals(alignment.get("confirmed"))||!target.equals(alignment.get("baselineKey"))||!DraftBusinessRules.RULE_VERSION.equals(alignment.get("baselineVersion"))||DraftCatalogAlignment.hasUnverifiedSccSource(graph,node))return false;
        List<?> expected=list(map(map(DraftCatalogAlignment.definitions().get(target)).get("condition")).get("all")),actual=list(rule.get("clauses"));
        String fingerprint=applicabilityFingerprint(graph,node,actual,nodes);
        if(expected.isEmpty()||expected.size()!=actual.size()||fingerprint==null||fingerprint.isEmpty()||!fingerprint.equals(rule.get("bindingFingerprint")))return false;
        for(int i=0;i<expected.size();i++) {
            Map<String,Object> spec=map(expected.get(i)),term=map(actual.get(i));String key=text(spec.get("field"));Map<String,Object> source=nodes.get(text(term.get("inputId"))),sourceAlignment=map(source==null?null:source.get("catalogAlignment"));
            if(!key.equals(term.get("baselineKey"))||!Objects.equals(spec.get("operator"),term.get("operator"))||!Objects.equals(spec.get("value"),term.get("value"))||source==null||!active(source)||!input(source)||!text(source.get("parentId")).isEmpty()||!"matched".equals(sourceAlignment.get("status"))||!Boolean.TRUE.equals(sourceAlignment.get("confirmed"))||!key.equals(sourceAlignment.get("baselineKey"))||!DraftBusinessRules.RULE_VERSION.equals(sourceAlignment.get("baselineVersion"))||DraftCatalogAlignment.hasUnverifiedSccSource(graph,source))return false;
        }
        return true;
    }
    static String applicabilityFingerprint(Map<String,Object> graph,Map<String,Object> target,List<?> terms,Map<String,Map<String,Object>> nodes) {
        Set<String> ids=new TreeSet<>();ids.add(text(target.get("id")));for(Object raw:terms)ids.add(text(map(raw).get("inputId")));
        List<Object> identities=new ArrayList<>();Set<String> documents=new HashSet<>();
        for(String id:ids){Map<String,Object> node=nodes.get(id);if(node==null)return "";Map<String,Object> identity=new LinkedHashMap<>();
            for(String key:Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","options","optional","definitionScope","semanticSourceQuote","docIds","sourceAnchors"))if(node.containsKey(key))identity.put(key,node.get(key));
            if(!id.equals(text(target.get("id")))&&node.containsKey("applicability"))identity.put("applicability",node.get("applicability"));
            Map<String,Object> alignment=map(node.get("catalogAlignment")),mapping=new LinkedHashMap<>();for(String key:Arrays.asList("status","baselineKey","baselineVersion","confirmed","scope"))if(alignment.containsKey(key))mapping.put(key,alignment.get(key));identity.put("catalogAlignment",mapping);
            for(Object value:list(node.get("docIds")))documents.add(text(value));for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);for(String key:Arrays.asList("docId","sourceDocumentId","editionId"))if(!text(anchor.get(key)).isEmpty())documents.add(text(anchor.get(key)));}identities.add(identity);
        }
        List<Map<String,Object>> sources=new ArrayList<>();for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);boolean referenced=false;for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId"))if(documents.contains(text(source.get(key))))referenced=true;if(!referenced)continue;
            Map<String,Object> identity=new LinkedHashMap<>();for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId","fileKey","format","sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(source.containsKey(key))identity.put(key,source.get(key));identity.put("selected",map(graph.get("selectedDocuments")).get(text(source.get("fileKey"))));sources.add(identity);
        }
        sources.sort(Comparator.comparing(JsonUtils::write));return DraftScopeObservationIntegrity.reference(obj("nodes",identities,"sources",sources));
    }
    private static void validateEditions(Map<String,Object> graph,Map<String,Map<String,Object>> nodes,Map<String,Set<String>> dependencies) {
        Map<String,String> formats=new HashMap<>();
        for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if("SCC".equalsIgnoreCase(text(source.get("fileKey"))))for(String key:Arrays.asList("id","docId","editionId"))if(!text(source.get(key)).isEmpty())formats.put(text(source.get(key)),text(source.get("id")));}
        for(Map<String,Object> node:nodes.values())if(active(node)) {
            Set<String> editions=new HashSet<>(); collectReachableEditions(text(node.get("id")),nodes,dependencies,formats,editions,new HashSet<>());
            if(editions.size()>1)throw invalid("GRAPH_SCC_EDITIONS_MUST_STAY_SEPARATE: "+node.get("id"));
        }
    }
    private static void collectEditions(Map<String,Object> node,Map<String,String> formats,Set<String> editions) {
        for(Object id:list(node.get("docIds")))if(formats.containsKey(text(id)))editions.add(formats.get(text(id)));
        for(String group:Arrays.asList("sourceAnchors","editTargets","evidence"))for(Object raw:list(node.get(group))){String id=text(map(raw).get("docId"));if(formats.containsKey(id))editions.add(formats.get(id));}
    }
    private static void collectReachableEditions(String id,Map<String,Map<String,Object>> nodes,Map<String,Set<String>> dependencies,Map<String,String> formats,Set<String> editions,Set<String> seen) {
        if(!seen.add(id))return;Map<String,Object> node=nodes.get(id);if(node==null)return;collectEditions(node,formats,editions);
        String parent=text(node.get("parentId"));if(!parent.isEmpty())collectReachableEditions(parent,nodes,dependencies,formats,editions,seen);
        for(String ref:dependencies.getOrDefault(id,Collections.emptySet()))collectReachableEditions(ref,nodes,dependencies,formats,editions,seen);
    }
    private static void cycle(String id,Map<String,Set<String>> edges,Set<String> visiting,Set<String> done) {
        if(done.contains(id))return;if(!visiting.add(id))throw invalid("GRAPH_DEPENDENCY_CYCLE: "+id);
        for(String next:edges.getOrDefault(id,Collections.emptySet()))cycle(next,edges,visiting,done);
        visiting.remove(id);done.add(id);
    }
    private static String listAncestor(Map<String,Map<String,Object>> nodes,String id) {
        Set<String> seen=new HashSet<>();String parent=text(map(nodes.get(id)).get("parentId"));
        while(!parent.isEmpty()&&seen.add(parent)){Map<String,Object> node=map(nodes.get(parent));if("list".equals(kind(node)))return parent;parent=text(node.get("parentId"));}return null;
    }
    private static void validateOperator(Map<String,Object> field,Map<String,Object> clause) {
        String operator=text(clause.get("operator")),type=kind(field);
        if(set("yes","no").contains(operator)&&!"boolean".equals(type))throw invalid("GRAPH_BOOLEAN_OPERATOR_TYPE");
        if(set("gt","ge","lt","le").contains(operator)) {
            if(!set("number","date").contains(type))throw invalid("GRAPH_ORDER_OPERATOR_TYPE");
            if(text(clause.get("value")).isEmpty())throw invalid("GRAPH_COMPARISON_VALUE_REQUIRED");
            validateValue(field,clause.get("value"));
        }
        if(set("has","lacks").contains(operator)) {
            if(!"multiselect".equals(type))throw invalid("GRAPH_MEMBERSHIP_OPERATOR_TYPE");
            Map<String,Object> scalar=new LinkedHashMap<>(field);scalar.put("type",obj("kind","choice"));validateValue(scalar,clause.get("value"));
        }
        if(set("empty","notempty").contains(operator)&&!set("text","record","list","multiselect").contains(type))throw invalid("GRAPH_EMPTY_OPERATOR_TYPE");
        if(set("eq","ne").contains(operator)){if(set("record","list","multiselect").contains(type))throw invalid("GRAPH_EQUALITY_REQUIRES_SCALAR");validateValue(field,clause.get("value"));}
    }
    private static List<?> branches(Map<String,Object> node) {
        Map<String,Object> correction=map(node.get("correction"));
        if(correction.get("branches") instanceof List)return list(correction.get("branches"));
        if("rule".equals(text(correction.get("kind")))&&!list(correction.get("clauses")).isEmpty()) {
            Map<String,Object> branch=new LinkedHashMap<>(correction);branch.put("id","case-1");branch.putIfAbsent("mode","all");return Collections.singletonList(branch);
        }
        return Collections.emptyList();
    }
    public static List<Map<String,Object>> fields(Map<String,Object> graph) {
        List<Map<String,Object>> result=new ArrayList<>();Context context=new Context(graph,Collections.emptyMap());
        for(Map<String,Object> node:context.nodes.values())if(context.selected(text(node.get("id")))&&text(node.get("parentId")).isEmpty()&&DraftCatalogAlignment.businessCandidate(node))result.add(withChildren(node,context,true,true));
        return result;
    }
    private static Map<String,Object> withChildren(Map<String,Object> node,Context context) {
        return withChildren(node,context,true);
    }
    private static Map<String,Object> withChildren(Map<String,Object> node,Context context,boolean selectedOnly) {
        return withChildren(node,context,selectedOnly,false);
    }
    private static Map<String,Object> withChildren(Map<String,Object> node,Context context,boolean selectedOnly,boolean businessOnly) {
        Map<String,Object> result=new LinkedHashMap<>(node);result.remove("baselineDefinition");List<Map<String,Object>> children=new ArrayList<>();
        Map<String,Object> definition=context.baselineDefinitions.get(text(node.get("id")));if(definition!=null){result.put("baselineDefinition",definition);Map<String,Object> label=map(definition.get("label"));result.put("nameZh",label.getOrDefault("zhHans",label.get("zhHant")));result.put("nameEn",label.get("en"));}
        for(Map<String,Object> child:context.nodes.values())if(active(child)&&(!businessOnly||DraftCatalogAlignment.businessCandidate(child))&&(!selectedOnly||context.selected(text(child.get("id"))))&&Objects.equals(node.get("id"),child.get("parentId")))children.add(withChildren(child,context,selectedOnly,businessOnly));
        result.put("children",children);return result;
    }
    public static String fieldHash(Map<String,Object> field) {
        List<Object> children=new ArrayList<>();for(Object child:list(field.get("children")))children.add(fieldHash(map(child)));
        Map<String,Object> meaning=new LinkedHashMap<>();
        for(String key:Arrays.asList("id","type","role","options","parentId","questionZh","questionEn","fixedValue"))meaning.put(key,field.get(key));
        if(field.containsKey("applicability"))meaning.put("applicability",field.get("applicability"));
        meaning.put("children",children);meaning.putAll(DraftCatalogAlignment.meaning(field));return DraftAdoption.hash(JsonUtils.write(meaning));
    }
    /** Business definitions travel to inference; original anchors and edit coordinates stay in the bound graph. */
    public static List<Map<String,Object>> extractionDefinitions(List<Map<String,Object>> fields) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> field:fields) {
            if(!DraftCatalogAlignment.businessCandidate(field))continue;
            Map<String,Object> definition=new LinkedHashMap<>();
            for(String key:Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","options","fixedValue","optional","correction","applicability","relatedInputIds","semanticSourceQuote","unit","definitionScope","baselineDefinition"))
                if(field.containsKey(key))definition.put(key,field.get(key));
            if(field.containsKey("catalogAlignment")){Map<String,Object> alignment=new LinkedHashMap<>();for(String key:Arrays.asList("status","baselineKey","baselineVersion","confirmed","reason","scope"))if(map(field.get("catalogAlignment")).containsKey(key))alignment.put(key,map(field.get("catalogAlignment")).get(key));definition.put("catalogAlignment",alignment);}
            Map<String,Object> guidance=map(field.get("guidance"));
            if(!guidance.isEmpty()){Map<String,Object> text=new LinkedHashMap<>();for(String key:Arrays.asList("zh","en"))if(guidance.containsKey(key))text.put(key,guidance.get(key));definition.put("guidance",text);}
            List<Map<String,Object>> children=new ArrayList<>();for(Object child:list(field.get("children")))children.add(map(child));
            definition.put("children",extractionDefinitions(children));result.add(definition);
        }
        return result;
    }
    public static List<List<Map<String,Object>>> extractionDefinitionBatches(List<Map<String,Object>> fields) {
        List<List<Map<String,Object>>> batches=new ArrayList<>();List<Map<String,Object>> batch=new ArrayList<>();int chars=2;
        for(Map<String,Object> field:extractionDefinitions(fields)) {
            int size=JsonUtils.write(field).length()+1;
            if(size+2>48000)throw new BizException(4007,"A complete field definition exceeds the extraction request budget; retain it for manual review.");
            if(chars+size>48000){batches.add(batch);batch=new ArrayList<>();chars=2;}
            batch.add(field);chars+=size;
        }
        if(!batch.isEmpty())batches.add(batch);return batches;
    }
    public static Map<String,Object> catalog(Map<String,Object> graph) {
        List<Map<String,Object>> fields=new ArrayList<>(),system=new ArrayList<>();
        Context context=new Context(graph,Collections.emptyMap());
        for(Map<String,Object> field:fields(graph))fields.add(catalogField(field,context));
        for(Map<String,Object> node:nodes(graph))if(active(node)&&!sourceCheck(node)&&context.selected(text(node.get("id")))&&"derived".equals(role(node)))system.add(catalogField(withChildren(node,context),context));
        return obj("ruleVersion","review-"+DraftAdoption.hash(JsonUtils.write(graph)),"groups",Collections.singletonList(obj("id","reviewed","label",label("本次图谱的项目变量","Project variables from this graph"),"fields",fields)),"systemFields",system);
    }
    private static Map<String,Object> catalogField(Map<String,Object> node,Context context) {
        List<Map<String,Object>> children=new ArrayList<>(),options=new ArrayList<>(),affects=new ArrayList<>();
        for(Object child:list(node.get("children")))if(DraftCatalogAlignment.businessCandidate(map(child)))children.add(catalogField(map(child),context));
        Map<String,Object> baseline=map(node.get("baselineDefinition")),baselineLabel=map(baseline.get("label"));
        for(Object raw:list(baseline.containsKey("options")?baseline.get("options"):node.get("options"))){Map<String,Object> option=map(raw),optionLabel=map(option.get("label"));options.add(obj("value",option.get("value"),"label",optionLabel.isEmpty()?label(text(option.get("labelZh")),text(option.get("labelEn"))):LocalizedText.of(text(optionLabel.get("zhHans")),text(optionLabel.get("zhHant")),text(optionLabel.get("en")))));}
        for(Object raw:list(node.get("editTargets"))){Map<String,Object> target=map(raw);String document=context.document(text(target.get("docId")));if(document!=null)affects.add(obj("document",document,"clause",target.getOrDefault("text",""),"source",target.get("text")));}
        Map<String,Object> result=obj("key",node.get("id"),"label",baselineLabel.isEmpty()?label(text(node.get("nameZh")),text(node.get("nameEn"))):LocalizedText.of(text(baselineLabel.get("zhHans")),text(baselineLabel.get("zhHant")),text(baselineLabel.get("en"))),"kind","record".equals(kind(node))?"object":kind(node),"optional",Boolean.TRUE.equals(node.get("optional")),"options",options,"columnFields",children,"affects",affects,"description",label(text(map(node.get("guidance")).get("zh")),text(map(node.get("guidance")).get("en"))));
        if(node.containsKey("applicability")) {
            if(applicabilityBindingValid(context.graph,node,context.nodes)) {
                List<Object> terms=new ArrayList<>();for(Object raw:list(map(node.get("applicability")).get("clauses"))) {Map<String,Object> term=map(raw),mapped=obj("field",term.get("inputId"),"operator",term.get("operator"),"value",term.get("value"));
                    String childKey="includesComponent".equals(term.get("operator"))?"designResponsibilities.component":"countGt".equals(term.get("operator"))?"oldValuableTrees.serial":"";
                    if(!childKey.isEmpty()){String property="includesComponent".equals(term.get("operator"))?"componentKey":"identityKey";mapped.put(property,"");for(Map<String,Object> child:context.nodes.values())if(Objects.equals(child.get("parentId"),term.get("inputId"))&&childKey.equals(map(context.baselineDefinitions.get(text(child.get("id")))).get("baselineKey")))mapped.put(property,child.get("id"));}
                    terms.add(mapped);
                }result.put("condition",obj("all",terms));
            } else {result.put("applicabilityReviewRequired",true);result.put("condition",obj("reviewRequired",true,"all",Collections.emptyList()));}
        }
        Object unit=map(node.get("type")).get("unit");if(unit==null)unit=node.get("unit");if(unit!=null)result.put("unit",unit);
        for(String key:Arrays.asList("definitionScope","catalogAlignment","baselineDefinition"))if(node.containsKey(key))result.put(key,node.get(key));
        if("id".equals(text(node.get("id"))))result.put("hidden",false);
        return result;
    }
    public static String normalize(Map<String,Object> field,String raw) {
        if(raw==null)return "";if("text".equals(kind(field)))return raw;if(raw.trim().isEmpty())return "";
        Object value=decode(raw);
        Object normalized=validateValue(field,value);
        return normalized instanceof String?(String)normalized:JsonUtils.write(normalized);
    }
    public static Object decode(String value) {
        try{return JsonUtils.mapper().readValue(value,Object.class);}catch(Exception ignored){return value;}
    }
    private static Object validateValue(Map<String,Object> field,Object value) {
        String kind=kind(field);
        if("boolean".equals(kind)){Boolean result=DraftBusinessRules.truth(value);if(result==null)throw invalid("GRAPH_BOOLEAN_REQUIRED");return result;}
        if("number".equals(kind)){try{BigDecimal number=new BigDecimal(String.valueOf(value));if(!Double.isFinite(number.doubleValue()))throw invalid("GRAPH_FINITE_NUMBER_REQUIRED");BigDecimal normalized=number.stripTrailingZeros();return normalized.scale()<0?normalized.setScale(0):normalized;}catch(NumberFormatException bad){throw invalid("GRAPH_NUMBER_REQUIRED");}}
        if("text".equals(kind)||"date".equals(kind)){if(!(value instanceof String))throw invalid("GRAPH_TEXT_REQUIRED");if("date".equals(kind))try{LocalDate.parse((String)value);}catch(RuntimeException bad){throw invalid("GRAPH_ISO_DATE_REQUIRED");}return value;}
        if("choice".equals(kind)){for(Object raw:list(field.get("options")))if(Objects.equals(text(map(raw).get("value")),text(value)))return text(map(raw).get("value"));throw invalid("GRAPH_CHOICE_INVALID");}
        if("multiselect".equals(kind)) {
            if(!(value instanceof List))throw invalid("GRAPH_ARRAY_REQUIRED");List<Object> result=new ArrayList<>();Set<String> seen=new HashSet<>();
            Map<String,Object> scalar=new LinkedHashMap<>(field);scalar.put("type",obj("kind","choice"));
            for(Object item:list(value)){Object choice=validateValue(scalar,item);if(!seen.add(text(choice)))throw invalid("GRAPH_DUPLICATE_CHOICE");result.add(choice);}return result;
        }
        if("record".equals(kind))return validateRecord(field,value);
        if("list".equals(kind)){if(!(value instanceof List))throw invalid("GRAPH_ARRAY_REQUIRED");List<Object> rows=new ArrayList<>();Set<String> ids=new HashSet<>();for(Object row:list(value)){Map<String,Object> normalized=map(validateRecord(field,row));String itemId=text(normalized.get("_itemId"));if(!itemId.isEmpty()&&!ids.add(itemId))throw invalid("GRAPH_DUPLICATE_ITEM_ID");rows.add(normalized);}return rows;}
        throw invalid("GRAPH_VALUE_TYPE_UNSUPPORTED");
    }
    private static Object validateRecord(Map<String,Object> field,Object value) {
        if(!(value instanceof Map))throw invalid("GRAPH_RECORD_REQUIRED");Map<String,Object> actual=map(value),result=new LinkedHashMap<>();Set<String> keys=new HashSet<>();
        if("list".equals(kind(field))&&actual.containsKey("_itemId")){String id=text(actual.get("_itemId"));if(!(actual.get("_itemId") instanceof String)||!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"))throw invalid("GRAPH_ITEM_ID_INVALID");keys.add("_itemId");result.put("_itemId",id);}
        for(Object raw:list(field.get("children"))){Map<String,Object> child=map(raw);if(!input(child))continue;String key=text(child.get("id"));keys.add(key);if(actual.containsKey(key)&&actual.get(key)!=null)result.put(key,validateValue(child,actual.get(key)));else if(!Boolean.TRUE.equals(child.get("optional")))throw invalid("GRAPH_DETAIL_REQUIRED: "+key);}
        if(!keys.containsAll(actual.keySet()))throw invalid("GRAPH_UNKNOWN_DETAIL");return result;
    }
    public static Map<String,Object> plan(Map<String,Object> graph,Map<String,Object> adopted) {
        return plan(graph,adopted,false);
    }
    public static Map<String,Object> plan(Map<String,Object> graph,Map<String,Object> adopted,boolean requireReviewedNodes) {
        return plan(graph,adopted,Collections.emptyMap(),requireReviewedNodes);
    }
    public static Map<String,Object> plan(Map<String,Object> graph,Map<String,Object> adopted,Map<String,Object> historical,boolean requireReviewedNodes) {
        validate(graph);Context context=new Context(graph,adopted,requireReviewedNodes);Map<String,Object> effective=new LinkedHashMap<>(),derived=new LinkedHashMap<>(),derivedItems=new LinkedHashMap<>();
        if(requireReviewedNodes)for(Map<String,Object> node:context.nodes.values())if(active(node)&&context.selected(text(node.get("id")))&&!context.reviewed(node))context.issue(text(node.get("id")),"NodeReviewRequired","此问题或规则尚未核对确认，相关修改保留原文。 / This question or rule is not reviewed; related source text is preserved.");
        List<Map<String,Object>> inputFields=fields(graph);Map<String,String> invalidInputs=new LinkedHashMap<>();
        for(Map<String,Object> field:inputFields) {
            String id=text(field.get("id"));Object value=adopted.get(id);if(!context.reviewed(field)||value==null)continue;
            try{context.answers.put(id,validateValue(field,value));}catch(BizException bad){context.invalidAnswers.add(id);invalidInputs.put(id,bad.getMessage());}
        }
        Map<String,Object> applicabilityStates=new LinkedHashMap<>();
        for(Map<String,Object> field:inputFields) {
            String id=text(field.get("id"));if(!context.reviewed(field))continue;
            Boolean applicable=context.applicability(field,new HashSet<>());if(field.containsKey("applicability"))applicabilityStates.put(id,applicable==null?"unknown":applicable?"yes":"no");
            if(applicable==null){context.issue(id,"InputApplicabilityUnknown","变量适用条件尚未确定或需重新核对；已保存的答案保留。 / Input applicability is unresolved or requires review; saved answers are retained.");continue;}
            if(!applicable)continue;
            if(invalidInputs.containsKey(id)){context.issue(id,"InputInvalid",invalidInputs.get(id));continue;}
            if(!context.answers.containsKey(id)){if(!Boolean.TRUE.equals(field.get("optional")))context.issue(id,"InputMissing","请采用本项目答案；未知不等于否。 / Adopt a project answer; unknown is not No.");continue;}
            effective.put(id,context.answers.get(id));
        }
        for(Map<String,Object> node:context.nodes.values())if(active(node)&&"derived".equals(role(node))&&node.containsKey("factProjectionReview")) {
            String id=text(node.get("id"));Map<String,Object> historicalEntry=map(historical.get(id));Boolean old=DraftBusinessRules.truth(historicalEntry.get("value"));if(old==null)continue;
            Map<String,Object> projection=map(node.get("factProjectionReview")),resolution=map(projection.get("historicalAnswerResolution"));Object root=context.value(text(projection.get("rootId")),Collections.emptyMap(),new HashSet<>()),value=null;
            if(root!=null&&!ruleNeedsReview(node)&&projection.get("branchPolarity") instanceof Boolean)value=Objects.equals(text(root),text(projection.get("branchValue")))?projection.get("branchPolarity"):!((Boolean)projection.get("branchPolarity"));
            String evidenceFingerprint=text(historicalEntry.get("evidenceFingerprint"));boolean acknowledged=root!=null&&!evidenceFingerprint.isEmpty()&&evidenceFingerprint.equals(text(resolution.get("evidenceFingerprint")))&&Boolean.TRUE.equals(resolution.get("confirmed"))&&"use_derived".equals(resolution.get("decision"))&&!text(resolution.get("reason")).trim().isEmpty()&&Objects.equals(old,resolution.get("oldValue"))&&Objects.equals(root,resolution.get("rootValue"));
            if(!acknowledged){context.projectionPending.add(id);boolean conflict=value!=null&&!Objects.equals(value,old);String kind=conflict?"HistoricalProjectionAnswerConflict":"HistoricalProjectionAnswerReview";context.issue(id,kind,conflict?"保留的旧答案与共同选择推导结果不一致，请复核；旧答案未删除。 / The preserved historical answer conflicts with the shared choice; review it.":"保留的旧答案未用于本次推导，请复核；旧答案未删除。 / The preserved historical answer is not used for this projection; review it.");for(Map<String,Object> issue:context.issues)if((kind+":"+id).equals(issue.get("id"))){issue.put("rootId",projection.get("rootId"));issue.put("rootValue",root);issue.put("oldValue",old);issue.put("derivedValue",value);issue.put("evidenceFingerprint",historicalEntry.get("evidenceFingerprint"));break;}}
        }
        for(Map<String,Object> node:context.nodes.values())if(active(node)&&context.selected(text(node.get("id")))) {
            String id=text(node.get("id"));
            if(!DraftCatalogAlignment.reviewed(node))context.issue(id,"CatalogAlignmentReview","变量定义对应关系仍待人工确认。 / Review the variable definition alignment before use.");
            if(ruleNeedsReview(node))context.issue(id,"SchemaNeedsReview","规则结构仍待修订，不能执行。 / The rule structure requires review before execution.");
            if(!"derived".equals(role(node)))continue;
            String scope=listAncestor(context.nodes,id);
            if(scope==null){Object value=context.value(id,Collections.emptyMap(),new HashSet<>());if(value!=null)derived.put(id,value);}
            else {
                Object collection=context.value(scope,Collections.emptyMap(),new HashSet<>());List<Object> values=new ArrayList<>();int index=0;
                if(collection instanceof List)for(Object raw:list(collection)){Map<String,Object> row=map(raw);Map<String,Map<String,Object>> rows=context.rowScope(scope,row);values.add(obj("itemId",row.get("_itemId"),"rowIndex",index++,"value",context.value(id,rows,new HashSet<>())));}
                derivedItems.put(id,values);
            }
        }
        List<Map<String,Object>> actions=new ArrayList<>();
        for(Map<String,Object> owner:context.nodes.values())if(active(owner)&&context.selected(text(owner.get("id")))) {
            int index=0;for(Object raw:list(owner.get("editTargets"))) {
                Map<String,Object> target=map(raw);String targetId=text(target.get("id"));if(targetId.isEmpty())targetId=owner.get("id")+"-target-"+(index++);
                String document=context.document(text(target.get("docId")));
                if(document==null){if(!context.knownSource(text(target.get("docId"))))context.issue(targetId,"UnknownTargetSource","修改目标来源不存在，不能省略。 / The target source is missing and cannot be omitted.");continue;}
                Map<String,Object> branch=context.branch(owner,text(target.get("branchId")));Map<String,Map<String,Object>> rows=Collections.emptyMap();
                List<Map<String,Object>> scopeResults=context.scopeResults(branch);boolean each=branch!=null&&"each".equals(text(branch.get("scopeMode")))&&!text(branch.get("scopeId")).isEmpty();
                Boolean applies;
                if(each&&"join".equals(text(target.get("valueMode"))))applies=context.matchingRowsKnown(scopeResults);
                else if(each) {
                    rows=context.placedRow(target,branch,scopeResults);
                    applies=scopeResults!=null&&scopeResults.isEmpty()?Boolean.FALSE:rows==null?null:context.condition(branch,rows,new HashSet<>());
                    if(rows==null&&!(scopeResults!=null&&scopeResults.isEmpty()))context.issue(targetId,"ItemPlacementRequired","逐项编辑需要唯一项目身份及该项的原件定位。 / Each-item edits need one unique item identity and its exact source placement.");
                } else applies=branch==null?Boolean.TRUE:context.condition(branch,rows,new HashSet<>());
                boolean projectionBlocked=context.projectionBlocked(text(owner.get("id")),new HashSet<>());
                boolean bondTarget="NTT".equals(document)&&text(target.get("text")).matches("(?is).*\\bG1a\\b.*"),legacyBond=bondTarget&&context.dependsOnBaseline(text(owner.get("id")),"foundationIncluded",new HashSet<>());
                boolean incompleteBond=bondTarget&&context.dependsOnBaseline(text(owner.get("id")),"combinedContractWithFoundationWorks",new HashSet<>())&&!context.completeBondCondition(text(owner.get("id")),new HashSet<>());
                if(legacyBond||incompleteBond){applies=null;context.issue(targetId,"LegacyBondConditionReview","NTT 10 需要完整的联合合约及39个月条件；请核对后重建规则，或人工确认准确目标改文。 / NTT 10 requires the complete combined-contract and 39-month conditions; review the rule or explicitly adopt the exact target amendment.");}
                if(!context.reviewed(owner)||projectionBlocked||(!text(target.get("branchId")).isEmpty()&&branch==null)||ruleNeedsReview(owner)||Boolean.TRUE.equals(target.get("needsRebind")))applies=null;
                String action=Boolean.FALSE.equals(applies)?"not_adopted":applies==null?"pending":actionKind(target.get("action"));Object replacement=null;
                if("keep".equals(action))action="retain";
                if("check".equals(action)){action="pending";context.issue(targetId,"ManualCheck","请核对原文与本次处理范围。 / Review the source and proposed scope.");}
                if("replace".equals(action)||"fill".equals(action)||"insert".equals(action)) {
                    boolean insertion="insert".equals(action);action=insertion?"insert":"amend";replacement=context.replacement(target,branch,rows);
                    if(replacement==null){action="pending";context.issue(targetId,"AnswerUnknown","改文所需答案仍未确定。 / The answer supplying this text is unknown.");}
                    if("bound".equals(text(map(target.get("nativeBinding")).get("status")))&&!(insertion?hasInsertionPoint(target):hasReplacementPlacement(target))){action="pending";context.issue(targetId,"ReplacementPlacementRequired","请明确插入点或替换文字所落的选区；其他已选替换选区将清空。 / Choose the exact insertion point or the selected span receiving replacement text; other selected replacement spans will be cleared.");}
                }
                if("unused".equals(action)){action="not_used";replacement="Not used";}
                if("delete".equals(action))replacement="";
                if("pending".equals(action))context.issue(targetId,"ConditionUnknown","条件或同项作用范围仍未确定。 / The condition or item scope is unresolved.");
                Map<String,Object> result=obj("id",targetId,"action",action,"document",document,"clause",target.getOrDefault("text",""),"sourceText",target.get("text"),"value",replacement,"fieldKeys",Collections.singletonList(owner.get("id")),"inputKeys",context.references(owner,branch),"detail",label(text(map(owner.get("guidance")).get("zh")),text(map(owner.get("guidance")).get("en"))),"nativeBinding",target.get("nativeBinding"),"target",target,"overrideable",true);
                if(projectionBlocked){result.put("overrideable",false);result.put("historicalProjectionBlocked",true);}
                if(each){result.put("scopeId",branch.get("scopeId"));result.put("scopeResults",scopeResults);result.put("itemId",target.get("itemId"));}
                if(!set("retain","not_adopted","pending").contains(action)&&!"bound".equals(text(map(target.get("nativeBinding")).get("status")))){result.put("action","pending");context.issue(targetId,"SourceBindingRequired","修改位置未准确绑定到本版可编辑原件。 / Bind the exact range to this editable source edition.");}
                actions.add(result);
            }
        }
        return obj("ruleVersion",catalog(graph).get("ruleVersion"),"inputValues",adopted,"effectiveValues",effective,"applicabilityStates",applicabilityStates,"derived",derived,"derivedItems",derivedItems,"actions",actions,"unresolved",context.issues);
    }
    public static void deferConflictingActions(Map<String,Object> plan) {
        List<?> actions=list(plan.get("actions"));Set<Integer> conflicts=new LinkedHashSet<>();
        for(int i=0;i<actions.size();i++)for(int j=i+1;j<actions.size();j++){
            Map<String,Object> left=map(actions.get(i)),right=map(actions.get(j));
            if(!Objects.equals(left.get("document"),right.get("document"))||"not_adopted".equals(left.get("action"))||"not_adopted".equals(right.get("action")))continue;
            if(set("retain","pending").contains(text(left.get("action")))&&set("retain","pending").contains(text(right.get("action"))))continue;
            for(Object a:list(map(left.get("nativeBinding")).get("spans")))for(Object b:list(map(right.get("nativeBinding")).get("spans"))){
                Map<String,Object> first=map(a),second=map(b);
                if(first.get("paragraphId")==null||!Objects.equals(first.get("paragraphId"),second.get("paragraphId")))continue;
                if(!(first.get("start") instanceof Number)||!(first.get("end") instanceof Number)||!(second.get("start") instanceof Number)||!(second.get("end") instanceof Number))continue;
                if(!Objects.equals(first.get("channel"),second.get("channel"))||DocxTemplateEditor.overlappingSpans(integer(first.get("start")),integer(first.get("end")),integer(second.get("start")),integer(second.get("end")))){conflicts.add(i);conflicts.add(j);}
            }
        }
        List<Object> issues=new ArrayList<>(list(plan.get("unresolved")));
        for(int index:conflicts){Map<String,Object> action=map(actions.get(index));action.put("action","pending");
            String issueId="OverlappingEdits:"+text(action.get("id"));
            if(issues.stream().noneMatch(raw->issueId.equals(map(raw).get("id"))))issues.add(obj("id",issueId,"kind","OverlappingEdits","inputKeys",Collections.singletonList(action.get("id")),"message",label("修改范围与另一项处理重叠，已保留原文；请在原文中核对并确定唯一处理方案。","This range overlaps another treatment and retains its source wording. Review the source and choose one consistent treatment.")));
        }
        plan.put("unresolved",issues);
    }
    private static final class Context {
        final Map<String,Object> graph;final Map<String,Map<String,Object>> nodes=new LinkedHashMap<>(),baselineDefinitions;final Map<String,Object> answers=new LinkedHashMap<>();final List<Map<String,Object>> issues=new ArrayList<>();final Set<String> issueIds=new HashSet<>(),projectionPending=new HashSet<>(),invalidAnswers=new HashSet<>();
        final Map<String,Set<String>> dependencies=new HashMap<>();final Map<String,String> editions=new HashMap<>();final Map<String,Boolean> selection=new HashMap<>(),applicabilityCache=new HashMap<>();final boolean requireReviewedNodes;
        Context(Map<String,Object> graph,Map<String,Object> adopted){
            this(graph,adopted,false);
        }
        Context(Map<String,Object> graph,Map<String,Object> adopted,boolean requireReviewedNodes){
            this.graph=graph;this.requireReviewedNodes=requireReviewedNodes;for(Map<String,Object> node:nodes(graph))nodes.put(text(node.get("id")),node);
            baselineDefinitions=DraftCatalogAlignment.confirmedDefinitions(graph);
            for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if("SCC".equalsIgnoreCase(text(source.get("fileKey"))))for(String key:Arrays.asList("id","docId","editionId"))if(!text(source.get(key)).isEmpty())editions.put(text(source.get(key)),text(source.get("id")));}
            for(Map<String,Object> node:nodes.values()){Set<String> refs=new HashSet<>();for(Object raw:branches(node)){Map<String,Object> branch=map(raw);if(!text(branch.get("scopeId")).isEmpty())refs.add(text(branch.get("scopeId")));for(Object term:list(branch.get("clauses")))refs.add(text(map(term).get("inputId")));}if(node.containsKey("applicability")&&applicabilityBindingValid(graph,node,nodes))for(Object raw:list(map(node.get("applicability")).get("clauses")))refs.add(text(map(raw).get("inputId")));for(Object raw:list(node.get("editTargets"))){Map<String,Object> target=map(raw);for(String key:Arrays.asList("valueSourceId","listId","itemKey"))if(!text(target.get(key)).isEmpty())refs.add(text(target.get(key)));}dependencies.put(text(node.get("id")),refs);}
        }
        boolean reviewed(Map<String,Object> node){return !requireReviewedNodes||"confirmed".equals(text(node.get("status")))&&DraftCatalogAlignment.reviewed(node);}
        boolean projectionBlocked(String id,Set<String> visited){if(!visited.add(id))return false;if(projectionPending.contains(id))return true;for(String dependency:dependencies.getOrDefault(id,Collections.emptySet()))if(projectionBlocked(dependency,visited))return true;return false;}
        boolean dependsOnBaseline(String id,String key,Set<String> visited){if(!visited.add(id))return false;Map<String,Object> node=nodes.get(id);if(key.equals(id)||key.equals(text(map(map(node).get("catalogAlignment")).get("baselineKey"))))return true;for(String dependency:dependencies.getOrDefault(id,Collections.emptySet()))if(dependsOnBaseline(dependency,key,visited))return true;return false;}
        boolean completeBondCondition(String id,Set<String> visited){
            if(!visited.add(id))return false;Map<String,Object> node=nodes.get(id);
            for(Object raw:branches(map(node))){Map<String,Object> branch=map(raw);if(!"all".equals(text(branch.get("mode"))))continue;boolean combined=false,duration=false;
                for(Object termRaw:list(branch.get("clauses"))){Map<String,Object> term=map(termRaw),source=nodes.get(text(term.get("inputId")));String sourceId=text(term.get("inputId")),key=text(map(map(source).get("catalogAlignment")).get("baselineKey")),operator=text(term.get("operator"));if(key.isEmpty())key=sourceId;
                    if("combinedContractWithFoundationWorks".equals(key)&&("yes".equals(operator)||"eq".equals(operator)&&Boolean.TRUE.equals(DraftBusinessRules.truth(term.get("value")))))combined=true;
                    if("periodAtLeast39Months".equals(key)&&("yes".equals(operator)||"eq".equals(operator)&&Boolean.TRUE.equals(DraftBusinessRules.truth(term.get("value")))))duration=true;
                    if("contractPeriodMonths".equals(key)&&"ge".equals(operator)&&Objects.equals(DraftBusinessRules.number(term.get("value")),39d))duration=true;
                }if(combined&&duration)return true;
            }
            for(String dependency:dependencies.getOrDefault(id,Collections.emptySet()))if(completeBondCondition(dependency,visited))return true;return false;
        }
        boolean selected(String id){if(selection.containsKey(id))return selection.get(id);Set<String> found=new HashSet<>();collectReachableEditions(id,nodes,dependencies,editions,found,new HashSet<>());String selected=text(map(graph.get("selectedDocuments")).get("SCC"));selected=editions.getOrDefault(selected,selected);boolean result=found.isEmpty()||(found.size()==1&&found.contains(selected));selection.put(id,result);return result;}
        void issue(String id,String kind,String message){if(issueIds.add(kind+":"+id))issues.add(obj("id",kind+":"+id,"kind",kind,"inputKeys",Collections.singletonList(id),"message",label(message,message)));}
        String document(String sourceId) {
            Map<String,Object> selected=map(graph.get("selectedDocuments"));
            for(String file:Arrays.asList("NTT","SCT","SCC")) {
                if(Objects.equals(sourceId,text(selected.get(file))))return file;
                for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if(Objects.equals(text(source.get("id")),text(selected.get(file)))&&(sourceId.equals(text(source.get("docId")))||sourceId.equals(text(source.get("editionId")))))return file;}
            }
            if(selected.isEmpty()&&set("NTT","SCT","SCC").contains(sourceId))return sourceId;
            return null;
        }
        boolean knownSource(String id){for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);for(String key:Arrays.asList("id","docId","editionId"))if(!id.isEmpty()&&id.equals(text(source.get(key))))return true;}return map(graph.get("selectedDocuments")).containsValue(id);}
        Map<String,Map<String,Object>> rowScope(String id,Map<String,Object> row){Map<String,Map<String,Object>> rows=new HashMap<>();rows.put(id,row);return rows;}
        Boolean applicability(Map<String,Object> node,Set<String> visiting) {
            if(!node.containsKey("applicability"))return true;
            String id=text(node.get("id"));if(applicabilityCache.containsKey(id))return applicabilityCache.get(id);
            Boolean result=computeApplicability(node,visiting);applicabilityCache.put(id,result);return result;
        }
        private Boolean computeApplicability(Map<String,Object> node,Set<String> visiting) {
            if(!applicabilityBindingValid(graph,node,nodes))return null;
            Map<String,Object> rule=map(node.get("applicability"));boolean unknown=false;
            for(Object raw:list(rule.get("clauses"))) {
                Map<String,Object> term=map(raw);String id=text(term.get("inputId")),key=text(term.get("baselineKey"));Map<String,Object> source=nodes.get(id);
                if(source==null||!reviewed(source)||!selected(id)||invalidAnswers.contains(id)){unknown=true;continue;}
                if(source.containsKey("applicability")&&!Boolean.TRUE.equals(applicability(source,visiting))){unknown=true;continue;}
                Object actual=value(id,Collections.emptyMap(),visiting);
                if(actual instanceof List) {
                    List<Object> rows=new ArrayList<>();for(Object rowRaw:list(actual)){Map<String,Object> row=map(rowRaw),converted=new LinkedHashMap<>();for(Map.Entry<String,Object> item:row.entrySet()){String baseline=text(map(baselineDefinitions.get(item.getKey())).get("baselineKey"));if(baseline.startsWith(key+"."))converted.put(baseline.substring(key.length()+1),item.getValue());}rows.add(converted);}
                    boolean incomplete=false;if("includesComponent".equals(term.get("operator")))for(Object row:rows)if(!DraftBusinessRules.answered(map(row).get("component")))incomplete=true;
                    if(incomplete){unknown=true;continue;}
                    actual=rows;
                }
                Map<String,Object> canonical=new LinkedHashMap<>();if(actual!=null)canonical.put(key,actual);
                Boolean matches=DraftBusinessRules.condition(obj("all",Collections.singletonList(obj("field",key,"operator",term.get("operator"),"value",term.get("value")))),canonical);
                if(Boolean.FALSE.equals(matches))return false;if(matches==null)unknown=true;
            }
            return unknown?null:true;
        }
        Object value(String id,Map<String,Map<String,Object>> rows,Set<String> visiting) {
            if(!visiting.add(id)){issue(id,"GraphCycle","存在循环依赖。 / Cyclic dependency.");return null;}
            try {
                Map<String,Object> node=nodes.get(id);if(node==null||sourceCheck(node)||!active(node)||!selected(id)||!reviewed(node)||projectionPending.contains(id))return null;
                if(input(node)&&!Boolean.TRUE.equals(applicability(node,visiting)))return null;
                String parent=text(node.get("parentId"));
                if(!parent.isEmpty()) {
                    Map<String,Object> row=rows.get(parent);
                    if(row==null){Object parentValue=value(parent,rows,visiting);if(!(parentValue instanceof Map))return null;row=map(parentValue);rows=new HashMap<>(rows);rows.put(parent,row);}
                    if(input(node))return row.get(id);
                }
                if(answers.containsKey(id))return answers.get(id);
                if(set("fixed","system").contains(role(node))&&node.get("fixedValue")!=null)return typedValue(withChildren(node,this),node.get("fixedValue"));
                if(!"derived".equals(role(node))||ruleNeedsReview(node))return null;
                List<Map<String,Object>> matches=new ArrayList<>();boolean unknown=false;
                for(Object raw:branches(node)){Map<String,Object> branch=map(raw);Boolean truth=condition(branch,rows,visiting);if(truth==null)unknown=true;else if(truth)matches.add(branch);}
                if(matches.size()>1){issue(id,"BranchConflict","多个条件同时成立，请纠正规则。 / Multiple cases match; correct the rule.");return null;}
                if(matches.isEmpty()&&!unknown&&compiledScopePredicate(node))return false;
                if(matches.size()!=1||unknown){issue(id,"DerivedUnknown","派生答案尚不能唯一确定。 / The derived answer is not uniquely determined.");return null;}
                if(matches.get(0).get("resultValue")==null){issue(id,"DerivedUnknown","派生规则尚未声明结果。 / The matching rule has no declared result.");return null;}return typedValue(withChildren(node,this),matches.get(0).get("resultValue"));
            } finally {visiting.remove(id);}
        }
        private boolean compiledScopePredicate(Map<String,Object> node){
            if(!"scope_rules_compilation".equals(node.get("origin"))||text(node.get("compiledScopeRuleId")).isEmpty()||!"preserved_as_branch".equals(node.get("conditionDisposition"))||!"boolean".equals(kind(node)))return false;
            List<?> rules=branches(node);if(rules.size()!=1)return false;Map<String,Object> branch=map(rules.get(0));
            return text(branch.get("scopeId")).isEmpty()&&text(branch.get("scopeMode")).isEmpty()&&Boolean.TRUE.equals(DraftBusinessRules.truth(branch.get("resultValue")));
        }
        Map<String,Object> branch(Map<String,Object> owner,String id){if(id.isEmpty())return null;for(Object raw:branches(owner))if(id.equals(text(map(raw).get("id"))))return map(raw);return null;}
        Boolean condition(Map<String,Object> branch,Map<String,Map<String,Object>> rows,Set<String> visiting) {
            String scope=text(branch.get("scopeId")),mode=text(branch.get("scopeMode"));
            if(!scope.isEmpty()&&!rows.containsKey(scope)) {
                Object collection=value(scope,rows,visiting);if(!(collection instanceof List)||"each".equals(mode))return null;
                List<Boolean> results=new ArrayList<>();for(Object raw:list(collection)){Map<String,Map<String,Object>> scoped=new HashMap<>(rows);scoped.put(scope,map(raw));results.add(terms(branch,scoped,visiting));}
                Boolean any=combine(results,false),all=combine(results,true);return "all".equals(mode)?all:"none".equals(mode)?any==null?null:!any:any;
            }
            return terms(branch,rows,visiting);
        }
        Boolean terms(Map<String,Object> branch,Map<String,Map<String,Object>> rows,Set<String> visiting){List<Boolean> results=new ArrayList<>();for(Object raw:list(branch.get("clauses"))){Map<String,Object> term=map(raw);String id=text(term.get("inputId"));results.add(compare(nodes.get(id),value(id,rows,visiting),text(term.get("operator")),term.get("value")));}return combine(results,"all".equals(text(branch.get("mode"))));}
        List<Map<String,Object>> scopeResults(Map<String,Object> branch){
            if(branch==null||text(branch.get("scopeId")).isEmpty())return null;String scope=text(branch.get("scopeId"));Object collection=value(scope,Collections.emptyMap(),new HashSet<>());if(!(collection instanceof List))return null;
            List<Map<String,Object>> results=new ArrayList<>();int index=0;for(Object raw:list(collection)){Map<String,Object> row=map(raw);results.add(obj("itemId",row.get("_itemId"),"rowIndex",index++,"matches",condition(branch,rowScope(scope,row),new HashSet<>())));}return results;
        }
        Boolean matchingRowsKnown(List<Map<String,Object>> results){if(results==null)return null;boolean match=false;for(Map<String,Object> result:results){if(result.get("matches")==null)return null;if(Boolean.TRUE.equals(result.get("matches")))match=true;}return match;}
        Map<String,Map<String,Object>> placedRow(Map<String,Object> target,Map<String,Object> branch,List<Map<String,Object>> results){
            if(results==null)return null;String scope=text(branch.get("scopeId")),itemId=text(target.get("itemId")),itemKey=text(target.get("itemKey"));Object collection=value(scope,Collections.emptyMap(),new HashSet<>());Map<String,Map<String,Object>> selected=null;int matches=0;
            if(!itemKey.isEmpty()&&(!scope.equals(listAncestor(nodes,itemKey))||!set("boolean","number","text","date","choice").contains(kind(map(nodes.get(itemKey))))))return null;
            for(Object raw:list(collection)){Map<String,Object> row=map(raw);Map<String,Map<String,Object>> rows=rowScope(scope,row);boolean match=!itemId.isEmpty()?itemId.equals(text(row.get("_itemId"))):!itemKey.isEmpty()&&target.containsKey("itemValue")&&Boolean.TRUE.equals(compare(nodes.get(itemKey),value(itemKey,rows,new HashSet<>()),"eq",target.get("itemValue")));if(match){selected=rows;matches++;}}
            return matches==1?selected:null;
        }
        Object replacement(Map<String,Object> target,Map<String,Object> branch,Map<String,Map<String,Object>> scoped) {
            String source=text(target.get("valueSourceId"));if(source.isEmpty())return target.containsKey("replacementEn")?target.get("replacementEn"):null;
            if("join".equals(text(target.get("valueMode")))) {
                String listId=text(target.get("listId"));Object collection=value(listId,Collections.emptyMap(),new HashSet<>());if(!(collection instanceof List))return null;
                boolean filtered=branch!=null&&"each".equals(text(branch.get("scopeMode")))&&listId.equals(text(branch.get("scopeId")));
                List<String> pieces=new ArrayList<>();for(Object raw:list(collection)){Map<String,Map<String,Object>> rows=rowScope(listId,map(raw));if(filtered){Boolean matches=condition(branch,rows,new HashSet<>());if(matches==null)return null;if(!matches)continue;}Object value=value(source,rows,new HashSet<>());if(value==null)return null;pieces.add(render(value));}
                return text(target.get("prefix"))+String.join(text(target.get("separator")),pieces)+text(target.get("suffix"));
            }
            Object value=value(source,scoped==null?Collections.emptyMap():scoped,new HashSet<>());return value==null?null:text(target.get("prefix"))+render(value)+text(target.get("suffix"));
        }
        List<String> references(Map<String,Object> owner,Map<String,Object> branch){Set<String> result=new LinkedHashSet<>();if(input(owner))result.add(text(owner.get("id")));if(branch!=null)for(Object term:list(branch.get("clauses")))result.add(text(map(term).get("inputId")));return new ArrayList<>(result);}
    }
    private static Boolean compare(Map<String,Object> field,Object actual,String operator,Object expected) {
        if("known".equals(operator))return actual!=null;if("unknown".equals(operator))return actual==null;if(actual==null)return null;
        if("yes".equals(operator)||"no".equals(operator)){Boolean value=DraftBusinessRules.truth(actual);return value==null?null:"yes".equals(operator)==value;}
        if("empty".equals(operator)||"notempty".equals(operator)){boolean empty=actual instanceof Collection?((Collection<?>)actual).isEmpty():actual instanceof Map?((Map<?,?>)actual).isEmpty():actual instanceof String&&((String)actual).isEmpty();return "empty".equals(operator)==empty;}
        if("has".equals(operator)||"lacks".equals(operator)){if(!(actual instanceof Collection))return null;boolean has=false;for(Object item:(Collection<?>)actual)if(Objects.equals(text(item),text(expected)))has=true;return "has".equals(operator)==has;}
        if("eq".equals(operator)||"ne".equals(operator)){Boolean equal=equal(field,actual,expected);return equal==null?null:"eq".equals(operator)==equal;}
        try{int order="number".equals(kind(map(field)))?new BigDecimal(text(actual)).compareTo(new BigDecimal(text(expected))):LocalDate.parse(text(actual)).compareTo(LocalDate.parse(text(expected)));switch(operator){case "gt":return order>0;case "ge":return order>=0;case "lt":return order<0;case "le":return order<=0;default:return null;}}catch(RuntimeException bad){return null;}
    }
    private static Boolean equal(Map<String,Object> field,Object left,Object right){try{String type=kind(map(field));if("boolean".equals(type)){Boolean a=DraftBusinessRules.truth(left),b=DraftBusinessRules.truth(right);return a==null||b==null?null:a.equals(b);}if("number".equals(type))return new BigDecimal(text(left)).compareTo(new BigDecimal(text(right)))==0;if(set("text","choice","date").contains(type))return Objects.equals(text(left),text(right));return null;}catch(RuntimeException bad){return null;}}
    private static Object typedValue(Map<String,Object> node,Object raw){if(raw==null)return null;String supplied=raw instanceof Map||raw instanceof Collection?JsonUtils.write(raw):text(raw);String normalized=normalize(node,supplied);if(normalized.isEmpty()&&!"text".equals(kind(node)))return null;return set("text","date","choice").contains(kind(node))?normalized:decode(normalized);}
    private static Boolean combine(List<Boolean> terms,boolean all){boolean unknown=false;for(Boolean term:terms){if(term==null)unknown=true;else if(all&&!term)return false;else if(!all&&term)return true;}return unknown?null:all;}
    private static String render(Object value){return value instanceof Map||value instanceof Collection?JsonUtils.write(value):text(value);}

    public static DocxEditResult compile(byte[] original,String fileKey,Map<String,Object> graph,Map<String,Object> plan,Map<String,String> bookmarks,NativeLayoutPlan layout) {
        return compile(original,fileKey,graph,plan,bookmarks,layout,false);
    }
    public static DocxEditResult compile(byte[] original,String fileKey,Map<String,Object> graph,Map<String,Object> plan,Map<String,String> bookmarks,NativeLayoutPlan layout,boolean incompleteDraft) {
        DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(original);
        return editor.applyWithParagraphBookmarks(original,compileBatch(index,fileKey,plan,incompleteDraft),bookmarks,!bookmarks.isEmpty(),layout);
    }
    static SourceEditBatch compileBatch(TemplateIndex index,String fileKey,Map<String,Object> plan,boolean incompleteDraft) {
        List<Edit> edits=new ArrayList<>();
        Map<String,List<int[]>> occupied=new HashMap<>();
        for(Object raw:list(plan.get("actions"))) {
            Map<String,Object> action=map(raw);if(!fileKey.equals(text(action.get("document"))))continue;
            String kind=text(action.get("action")),id=text(action.get("id"));if(set("retain","not_adopted").contains(kind))continue;
            if("pending".equals(kind)){if(incompleteDraft)continue;throw invalid("GRAPH_ACTION_UNRESOLVED: "+id);}
            Map<String,Object> binding=map(action.get("nativeBinding"));
            if(!"bound".equals(text(binding.get("status")))||!index.getSourceSha256().equals(binding.get("sourceSha256")))throw invalid("GRAPH_SOURCE_BINDING_CHANGED: "+id);
            List<?> spans=list(binding.get("spans"));if(spans.isEmpty())throw invalid("GRAPH_EDIT_SPAN_REQUIRED: "+id);
            String replacement=action.get("value")==null?null:render(action.get("value"));
            Map<String,Object> target=new LinkedHashMap<>(map(action.get("target")));if(target.isEmpty())target.putAll(action);target.put("nativeBinding",binding);
            int placement=replacementSpanIndex(target);
            if("amend".equals(kind)&&(replacement==null||placement<0))throw invalid("GRAPH_REPLACEMENT_PLACEMENT_REQUIRED: "+id);
            if("insert".equals(kind)&&(replacement==null||!hasInsertionPoint(target)))throw invalid("GRAPH_INSERTION_POINT_REQUIRED: "+id);
            if(!set("amend","insert","delete","not_used").contains(kind))throw invalid("GRAPH_EDIT_ACTION_UNSUPPORTED: "+id);
            int n=0;for(Object spanRaw:spans) {
                Map<String,Object> span=map(spanRaw);Paragraph paragraph=index.paragraph(text(span.get("paragraphId")));
                if(!paragraph.getSha256().equals(span.get("paragraphSha256")))throw invalid("GRAPH_PARAGRAPH_CHANGED: "+id);
                int start=integer(span.get("start")),end=integer(span.get("end"));String expected=text(span.get("expectedText"));
                if(incompleteDraft)for(Object otherRaw:list(plan.get("actions"))){
                    Map<String,Object> other=map(otherRaw);if(!fileKey.equals(text(other.get("document")))||!"pending".equals(other.get("action")))continue;
                    for(Object pendingRaw:list(map(other.get("nativeBinding")).get("spans"))){Map<String,Object> pending=map(pendingRaw);
                        if(Objects.equals(span.get("paragraphId"),pending.get("paragraphId"))&&DocxTemplateEditor.overlappingSpans(start,end,integer(pending.get("start")),integer(pending.get("end"))))throw invalid("GRAPH_PENDING_EDIT_OVERLAP: "+id);
                    }
                }
                if("insert".equals(kind)!= "insert".equals(text(span.get("operation")))||"insert".equals(kind)&&start!=end||!"insert".equals(kind)&&start==end)throw invalid("GRAPH_INSERTION_ACTION_MISMATCH: "+id);
                for(int[] range:occupied.computeIfAbsent(paragraph.getId(),ignored->new ArrayList<>()))if(DocxTemplateEditor.overlappingSpans(start,end,range[0],range[1]))throw invalid("GRAPH_OVERLAPPING_EDITS: "+id);
                occupied.get(paragraph.getId()).add(new int[]{start,end});
                String next="insert".equals(kind)||"amend".equals(kind)&&n==placement?replacement:"not_used".equals(kind)&&n==0?"Not used":"";
                String channel=text(span.get("channel"));
                if("editableText".equals(channel))edits.add("insert".equals(kind)?Edit.insertText(id+"-"+n,paragraph,start,next):Edit.replaceSpan(id+"-"+n,paragraph,start,end,expected,next));
                else if("catalogText".equals(channel)&&!"insert".equals(kind))edits.add(Edit.replaceCatalogSpan(id+"-"+n,paragraph,start,end,expected,next));
                else throw invalid("GRAPH_TEXT_CHANNEL_REQUIRED: "+id);
                n++;
            }
        }
        return new SourceEditBatch(index.getSourceSha256(),edits);
    }
    public static boolean hasReplacementPlacement(Map<String,Object> target){return replacementSpanIndex(target)>=0;}
    private static int replacementSpanIndex(Map<String,Object> target) {
        List<?> spans=list(map(target.get("nativeBinding")).get("spans"));if(spans.isEmpty())return -1;
        for(Object raw:spans)if("insert".equals(text(map(raw).get("operation"))))return -1;
        if(spans.size()==1)return 0;
        Object value=map(target.get("replacementPlacement")).get("spanIndex");if(!(value instanceof Number))return -1;
        Number index=(Number)value;int selected=index.intValue();return index.doubleValue()==selected&&selected>=0&&selected<spans.size()?selected:-1;
    }
    public static boolean hasInsertionPoint(Map<String,Object> target) {
        List<?> spans=list(map(target.get("nativeBinding")).get("spans"));if(spans.size()!=1)return false;Map<String,Object> span=map(spans.get(0));
        Object start=span.get("start"),end=span.get("end");return "insert".equals(text(span.get("operation")))&&start instanceof Number&&end instanceof Number&&((Number)start).doubleValue()==((Number)start).intValue()&&((Number)end).doubleValue()==((Number)end).intValue()&&((Number)start).intValue()==((Number)end).intValue()&&"".equals(span.get("expectedText"));
    }
    private static int integer(Object value){if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue())throw invalid("GRAPH_SPAN_INTEGER_REQUIRED");return ((Number)value).intValue();}
    private static void requireNode(Map<String,Map<String,Object>> nodes,String id){if(!nodes.containsKey(id)||!active(nodes.get(id)))throw invalid("GRAPH_REFERENCE_MISSING: "+id);}
    public static boolean ruleNeedsReview(Map<String,Object> node){Map<String,Object> correction=map(node.get("correction")),split=node.get("splitReview")!=null?map(node.get("splitReview")):"split".equals(text(correction.get("kind")))?correction:null,review=map(node.get("factProjectionReview")),previous=map(review.get("previousRuleResolution"));boolean hasPrevious=map(review.get("original")).get("correction")!=null,rebuilt="resolved".equals(previous.get("status"))&&"rebuilt".equals(previous.get("decision"));return Boolean.TRUE.equals(correction.get("needsRebind"))||hasPrevious&&!"resolved".equals(previous.get("status"))||node.containsKey("factProjectionReview")&&!Objects.equals(node.get("correction"),review.get("convertedCorrection"))&&!rebuilt||split!=null&&!set("all","any").contains(text(split.get("mode")));}
    private static void requireValueNode(Map<String,Map<String,Object>> nodes,String id,boolean needsRebind){if(!needsRebind)requireNode(nodes,id);if(nodes.containsKey(id)&&sourceCheck(nodes.get(id)))throw invalid("GRAPH_SOURCE_CHECK_NOT_A_VALUE: "+id);}
    private static boolean active(Map<String,Object> node){return !"excluded".equals(text(node.get("status")));}
    private static boolean sourceCheck(Map<String,Object> node){return "verification".equals(role(node))||"source_seed".equals(text(node.get("definitionKind")));}
    private static boolean input(Map<String,Object> node){return !sourceCheck(node)&&set("input","decision","content").contains(role(node));}
    private static String actionKind(Object raw){String value=text(raw);return "retain".equals(value)?"keep":"not_used".equals(value)?"unused":value;}
    private static String kind(Map<String,Object> node){return text(map(node.get("type")).get("kind"));}
    private static String role(Map<String,Object> node){return text(map(node.get("role")).get("kind"));}
    public static String text(Object value){return value==null?"":String.valueOf(value);}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    public static List<?> list(Object value){return value instanceof List?(List<?>)value:Collections.emptyList();}
    private static Set<String> set(String... values){return new HashSet<>(Arrays.asList(values));}
    public static Map<String,Object> obj(Object... pairs){Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)value.put(text(pairs[i]),pairs[i+1]);return value;}
    private static LocalizedText label(String zh,String en){return LocalizedText.of(zh,zh,en);}
    private static BizException invalid(String message){return new BizException(4012,message);}
}
