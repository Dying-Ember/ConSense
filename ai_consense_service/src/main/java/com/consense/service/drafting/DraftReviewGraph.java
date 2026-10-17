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
    private static final Set<String> ACTIONS = set("keep","replace","fill","delete","unused","check");
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
        Map<String,Set<String>> dependencies=new LinkedHashMap<>();
        for(Map<String,Object> node:byId.values())if(active(node)) {
            String id=text(node.get("id")),parent=text(node.get("parentId")); Set<String> refs=new LinkedHashSet<>();
            Set<String> declared=new HashSet<>();for(Object doc:list(node.get("docIds")))declared.add(text(doc));
            for(String group:Arrays.asList("sourceAnchors","editTargets","evidence"))for(Object raw:list(node.get(group))){String doc=text(map(raw).get("docId"));if(!doc.isEmpty()&&!declared.contains(doc))throw invalid("GRAPH_SOURCE_OUTSIDE_DECLARED_DOCUMENTS: "+id);}
            if(!parent.isEmpty()) {
                requireNode(byId,parent); if(!set("record","list").contains(kind(byId.get(parent))))throw invalid("GRAPH_PARENT_NOT_A_GROUP: "+id);
            }
            Set<String> cases=new HashSet<>();
            boolean rebind=Boolean.TRUE.equals(map(node.get("correction")).get("needsRebind"));
            for(Object raw:branches(node)) {
                Map<String,Object> branch=map(raw); String caseId=text(branch.get("id"));
                if(caseId.isEmpty()||!cases.add(caseId)||!set("all","any").contains(text(branch.get("mode")))||list(branch.get("clauses")).isEmpty())throw invalid("GRAPH_CASE_INVALID: "+id);
                String scope=text(branch.get("scopeId")),mode=text(branch.get("scopeMode"));
                if(!scope.isEmpty()) {if(!rebind)requireNode(byId,scope); if((!rebind&&!"list".equals(kind(byId.get(scope))))||!set("each","any","all","none").contains(mode))throw invalid("GRAPH_SCOPE_INVALID: "+id);refs.add(scope);}
                else if(!mode.isEmpty())throw invalid("GRAPH_SCOPE_REQUIRED: "+id);
                for(Object term:list(branch.get("clauses"))) {
                    Map<String,Object> clause=map(term); String input=text(clause.get("inputId"));if(!rebind)requireNode(byId,input);
                    if(!OPERATORS.contains(text(clause.get("operator"))))throw invalid("GRAPH_OPERATOR_UNSUPPORTED: "+id);
                    if(!rebind)validateOperator(byId.get(input),clause);
                    if(!rebind&&listAncestor(byId,input)!=null&&!Objects.equals(scope,listAncestor(byId,input)))throw invalid("GRAPH_SCOPE_CROSSES_LISTS: "+id);
                    refs.add(input);
                }
                if(!rebind&&"derived".equals(role(node))&&set("boolean","choice").contains(kind(node))&&branch.containsKey("resultValue"))normalize(node,text(branch.get("resultValue")));
            }
            for(Object raw:list(node.get("editTargets"))) {
                Map<String,Object> target=map(raw);
                if(!ACTIONS.contains(actionKind(target.get("action"))))throw invalid("GRAPH_ACTION_UNSUPPORTED: "+id);
                boolean targetRebind=rebind||Boolean.TRUE.equals(target.get("needsRebind"));
                String branch=text(target.get("branchId"));if(!targetRebind&&!branch.isEmpty()&&!cases.contains(branch))throw invalid("GRAPH_TARGET_CASE_MISSING: "+id);
                String value=text(target.get("valueSourceId"));if(!value.isEmpty()){if(!targetRebind)requireNode(byId,value);if(!value.equals(id))refs.add(value);}
                String itemKey=text(target.get("itemKey"));if(!itemKey.isEmpty()) {
                    if(!targetRebind){requireNode(byId,itemKey);String itemScope="";for(Object branchRaw:branches(node)){Map<String,Object> condition=map(branchRaw);if(branch.equals(text(condition.get("id")))&&"each".equals(text(condition.get("scopeMode"))))itemScope=text(condition.get("scopeId"));}
                        if(itemScope.isEmpty()||!itemScope.equals(listAncestor(byId,itemKey))||!set("boolean","number","text","date","choice").contains(kind(byId.get(itemKey))))throw invalid("GRAPH_ITEM_SCOPE_INVALID: "+id);}
                    refs.add(itemKey);
                }
                if("join".equals(text(target.get("valueMode")))) {
                    String listId=text(target.get("listId"));if(!targetRebind)requireNode(byId,listId);
                    if(!targetRebind&&(!"list".equals(kind(byId.get(listId)))||value.isEmpty()||!listId.equals(listAncestor(byId,value))))throw invalid("GRAPH_JOIN_SCOPE_INVALID: "+id);
                    if(!targetRebind&&!branch.isEmpty())for(Object branchRaw:branches(node)){Map<String,Object> condition=map(branchRaw);if(branch.equals(text(condition.get("id")))&&"each".equals(text(condition.get("scopeMode")))&&!listId.equals(text(condition.get("scopeId"))))throw invalid("GRAPH_JOIN_SCOPE_INVALID: "+id);}
                    refs.add(listId);
                }
            }
            dependencies.put(id,refs);
        }
        // Parent membership is validated separately; it is not a value dependency of the parent.
        for(Map<String,Object> node:byId.values()) {
            Set<String> ancestors=new HashSet<>();String id=text(node.get("id"));
            while(!id.isEmpty()){if(!ancestors.add(id))throw invalid("GRAPH_PARENT_CYCLE");id=text(map(byId.get(id)).get("parentId"));}
        }
        Map<String,Set<String>> valueDependencies=new LinkedHashMap<>();
        for(String id:dependencies.keySet())if("derived".equals(role(byId.get(id)))){Set<String> values=new LinkedHashSet<>();for(String ref:dependencies.get(id))if(byId.containsKey(ref)&&"derived".equals(role(byId.get(ref))))values.add(ref);valueDependencies.put(id,values);}
        for(String id:valueDependencies.keySet())cycle(id,valueDependencies,new HashSet<>(),new HashSet<>());
        validateEditions(graph,byId,dependencies);
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
        for(Map<String,Object> node:context.nodes.values())if(active(node)&&context.selected(text(node.get("id")))&&text(node.get("parentId")).isEmpty()&&input(node))result.add(withChildren(node,context));
        return result;
    }
    private static Map<String,Object> withChildren(Map<String,Object> node,Context context) {
        Map<String,Object> result=new LinkedHashMap<>(node);List<Map<String,Object>> children=new ArrayList<>();
        for(Map<String,Object> child:context.nodes.values())if(active(child)&&context.selected(text(child.get("id")))&&Objects.equals(node.get("id"),child.get("parentId")))children.add(withChildren(child,context));
        result.put("children",children);return result;
    }
    public static String fieldHash(Map<String,Object> field) {
        List<Object> children=new ArrayList<>();for(Object child:list(field.get("children")))children.add(fieldHash(map(child)));
        Map<String,Object> meaning=new LinkedHashMap<>();
        for(String key:Arrays.asList("id","type","role","options","parentId","questionZh","questionEn","fixedValue"))meaning.put(key,field.get(key));
        meaning.put("children",children);return DraftAdoption.hash(JsonUtils.write(meaning));
    }
    public static Map<String,Object> catalog(Map<String,Object> graph) {
        List<Map<String,Object>> fields=new ArrayList<>(),system=new ArrayList<>();
        Context context=new Context(graph,Collections.emptyMap());
        for(Map<String,Object> field:fields(graph))fields.add(catalogField(field,context));
        for(Map<String,Object> node:nodes(graph))if(active(node)&&context.selected(text(node.get("id")))&&"derived".equals(role(node)))system.add(catalogField(withChildren(node,context),context));
        return obj("ruleVersion","review-"+DraftAdoption.hash(JsonUtils.write(graph)),"groups",Collections.singletonList(obj("id","reviewed","label",label("已审核的项目问题","Reviewed project questions"),"fields",fields)),"systemFields",system);
    }
    private static Map<String,Object> catalogField(Map<String,Object> node,Context context) {
        List<Map<String,Object>> children=new ArrayList<>(),options=new ArrayList<>(),affects=new ArrayList<>();
        for(Object child:list(node.get("children")))if(input(map(child)))children.add(catalogField(map(child),context));
        for(Object raw:list(node.get("options"))){Map<String,Object> option=map(raw);options.add(obj("value",option.get("value"),"label",label(text(option.get("labelZh")),text(option.get("labelEn")))));}
        for(Object raw:list(node.get("editTargets"))){Map<String,Object> target=map(raw);String document=context.document(text(target.get("docId")));if(document!=null)affects.add(obj("document",document,"clause",target.getOrDefault("text",""),"source",target.get("text")));}
        return obj("key",node.get("id"),"label",label(text(node.get("nameZh")),text(node.get("nameEn"))),"kind","record".equals(kind(node))?"object":kind(node),"optional",Boolean.TRUE.equals(node.get("optional")),"options",options,"columnFields",children,"affects",affects,"description",label(text(map(node.get("guidance")).get("zh")),text(map(node.get("guidance")).get("en"))));
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
        validate(graph);Context context=new Context(graph,adopted);Map<String,Object> effective=new LinkedHashMap<>(),derived=new LinkedHashMap<>(),derivedItems=new LinkedHashMap<>();
        for(Map<String,Object> field:fields(graph)) {
            String id=text(field.get("id"));Object value=adopted.get(id);
            if(value==null){if(!Boolean.TRUE.equals(field.get("optional")))context.issue(id,"InputMissing","请采用本项目答案；未知不等于否。 / Adopt a project answer; unknown is not No.");continue;}
            try{Object normalized=validateValue(field,value);context.answers.put(id,normalized);effective.put(id,normalized);}catch(BizException bad){context.answers.remove(id);context.issue(id,"InputInvalid",bad.getMessage());}
        }
        for(Map<String,Object> node:context.nodes.values())if(active(node)&&context.selected(text(node.get("id")))) {
            String id=text(node.get("id"));Map<String,Object> correction=map(node.get("correction"));
            if(Boolean.TRUE.equals(correction.get("needsRebind"))||"split".equals(text(correction.get("kind"))))context.issue(id,"SchemaNeedsReview","规则结构仍待修订，不能执行。 / The rule structure requires review before execution.");
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
                if((!text(target.get("branchId")).isEmpty()&&branch==null)||Boolean.TRUE.equals(map(owner.get("correction")).get("needsRebind"))||Boolean.TRUE.equals(target.get("needsRebind")))applies=null;
                String action=Boolean.FALSE.equals(applies)?"not_adopted":applies==null?"pending":actionKind(target.get("action"));Object replacement=null;
                if("keep".equals(action))action="retain";
                if("check".equals(action)){action="pending";context.issue(targetId,"ManualCheck","请核对原文与本次处理范围。 / Review the source and proposed scope.");}
                if("replace".equals(action)||"fill".equals(action)) {
                    action="amend";replacement=context.replacement(target,branch,rows);
                    if(replacement==null){action="pending";context.issue(targetId,"AnswerUnknown","改文所需答案仍未确定。 / The answer supplying this text is unknown.");}
                }
                if("unused".equals(action)){action="not_used";replacement="Not used";}
                if("delete".equals(action))replacement="";
                if("pending".equals(action))context.issue(targetId,"ConditionUnknown","条件或同项作用范围仍未确定。 / The condition or item scope is unresolved.");
                Map<String,Object> result=obj("id",targetId,"action",action,"document",document,"clause",target.getOrDefault("text",""),"sourceText",target.get("text"),"value",replacement,"fieldKeys",Collections.singletonList(owner.get("id")),"inputKeys",context.references(owner,branch),"detail",label(text(map(owner.get("guidance")).get("zh")),text(map(owner.get("guidance")).get("en"))),"nativeBinding",target.get("nativeBinding"),"target",target,"overrideable",true);
                if(each){result.put("scopeId",branch.get("scopeId"));result.put("scopeResults",scopeResults);result.put("itemId",target.get("itemId"));}
                if(!set("retain","not_adopted","pending").contains(action)&&!"bound".equals(text(map(target.get("nativeBinding")).get("status")))){result.put("action","pending");context.issue(targetId,"SourceBindingRequired","修改位置未准确绑定到本版可编辑原件。 / Bind the exact range to this editable source edition.");}
                actions.add(result);
            }
        }
        return obj("ruleVersion",catalog(graph).get("ruleVersion"),"inputValues",adopted,"effectiveValues",effective,"derived",derived,"derivedItems",derivedItems,"actions",actions,"unresolved",context.issues);
    }
    private static final class Context {
        final Map<String,Object> graph;final Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();final Map<String,Object> answers=new LinkedHashMap<>();final List<Map<String,Object>> issues=new ArrayList<>();final Set<String> issueIds=new HashSet<>();
        final Map<String,Set<String>> dependencies=new HashMap<>();final Map<String,String> editions=new HashMap<>();final Map<String,Boolean> selection=new HashMap<>();
        Context(Map<String,Object> graph,Map<String,Object> adopted){
            this.graph=graph;for(Map<String,Object> node:nodes(graph))nodes.put(text(node.get("id")),node);
            for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if("SCC".equalsIgnoreCase(text(source.get("fileKey"))))for(String key:Arrays.asList("id","docId","editionId"))if(!text(source.get(key)).isEmpty())editions.put(text(source.get(key)),text(source.get("id")));}
            for(Map<String,Object> node:nodes.values()){Set<String> refs=new HashSet<>();for(Object raw:branches(node)){Map<String,Object> branch=map(raw);if(!text(branch.get("scopeId")).isEmpty())refs.add(text(branch.get("scopeId")));for(Object term:list(branch.get("clauses")))refs.add(text(map(term).get("inputId")));}for(Object raw:list(node.get("editTargets"))){Map<String,Object> target=map(raw);for(String key:Arrays.asList("valueSourceId","listId","itemKey"))if(!text(target.get(key)).isEmpty())refs.add(text(target.get(key)));}dependencies.put(text(node.get("id")),refs);}
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
        Object value(String id,Map<String,Map<String,Object>> rows,Set<String> visiting) {
            if(!visiting.add(id)){issue(id,"GraphCycle","存在循环依赖。 / Cyclic dependency.");return null;}
            try {
                Map<String,Object> node=nodes.get(id);if(node==null||!active(node)||!selected(id))return null;
                String parent=text(node.get("parentId"));
                if(!parent.isEmpty()) {
                    Map<String,Object> row=rows.get(parent);
                    if(row==null){Object parentValue=value(parent,rows,visiting);if(!(parentValue instanceof Map))return null;row=map(parentValue);rows=new HashMap<>(rows);rows.put(parent,row);}
                    if(input(node))return row.get(id);
                }
                if(answers.containsKey(id))return answers.get(id);
                if(set("fixed","system").contains(role(node))&&node.get("fixedValue")!=null)return typedValue(node,text(node.get("fixedValue")));
                if(!"derived".equals(role(node))||Boolean.TRUE.equals(map(node.get("correction")).get("needsRebind")))return null;
                List<Map<String,Object>> matches=new ArrayList<>();boolean unknown=false;
                for(Object raw:branches(node)){Map<String,Object> branch=map(raw);Boolean truth=condition(branch,rows,visiting);if(truth==null)unknown=true;else if(truth)matches.add(branch);}
                if(matches.size()>1){issue(id,"BranchConflict","多个条件同时成立，请纠正规则。 / Multiple cases match; correct the rule.");return null;}
                if(matches.size()!=1||unknown){issue(id,"DerivedUnknown","派生答案尚不能唯一确定。 / The derived answer is not uniquely determined.");return null;}
                if(matches.get(0).get("resultValue")==null){issue(id,"DerivedUnknown","派生规则尚未声明结果。 / The matching rule has no declared result.");return null;}return typedValue(node,text(matches.get(0).get("resultValue")));
            } finally {visiting.remove(id);}
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
    private static Object typedValue(Map<String,Object> node,String raw){if(raw==null)return null;String normalized=normalize(node,raw);if(normalized.isEmpty()&&!"text".equals(kind(node)))return null;return set("text","date","choice").contains(kind(node))?normalized:decode(normalized);}
    private static Boolean combine(List<Boolean> terms,boolean all){boolean unknown=false;for(Boolean term:terms){if(term==null)unknown=true;else if(all&&!term)return false;else if(!all&&term)return true;}return unknown?null:all;}
    private static String render(Object value){return value instanceof Map||value instanceof Collection?JsonUtils.write(value):text(value);}

    public static DocxEditResult compile(byte[] original,String fileKey,Map<String,Object> graph,Map<String,Object> plan,Map<String,String> bookmarks,NativeLayoutPlan layout) {
        DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(original);List<Edit> edits=new ArrayList<>();
        Map<String,List<int[]>> occupied=new HashMap<>();
        for(Object raw:list(plan.get("actions"))) {
            Map<String,Object> action=map(raw);if(!fileKey.equals(text(action.get("document"))))continue;
            String kind=text(action.get("action")),id=text(action.get("id"));if(set("retain","not_adopted").contains(kind))continue;
            if("pending".equals(kind))throw invalid("GRAPH_ACTION_UNRESOLVED: "+id);
            Map<String,Object> binding=map(action.get("nativeBinding"));
            if(!"bound".equals(text(binding.get("status")))||!index.getSourceSha256().equals(binding.get("sourceSha256")))throw invalid("GRAPH_SOURCE_BINDING_CHANGED: "+id);
            List<?> spans=list(binding.get("spans"));if(spans.isEmpty())throw invalid("GRAPH_EDIT_SPAN_REQUIRED: "+id);
            String replacement=action.get("value")==null?null:render(action.get("value"));
            if("amend".equals(kind)&&(replacement==null||spans.size()!=1))throw invalid("GRAPH_REPLACEMENT_PLACEMENT_REQUIRED: "+id);
            if(!set("amend","delete","not_used").contains(kind))throw invalid("GRAPH_EDIT_ACTION_UNSUPPORTED: "+id);
            int n=0;for(Object spanRaw:spans) {
                Map<String,Object> span=map(spanRaw);Paragraph paragraph=index.paragraph(text(span.get("paragraphId")));
                if(!paragraph.getSha256().equals(span.get("paragraphSha256")))throw invalid("GRAPH_PARAGRAPH_CHANGED: "+id);
                int start=integer(span.get("start")),end=integer(span.get("end"));String expected=text(span.get("expectedText"));
                for(int[] range:occupied.computeIfAbsent(paragraph.getId(),ignored->new ArrayList<>()))if(start<range[1]&&end>range[0])throw invalid("GRAPH_OVERLAPPING_EDITS: "+id);
                occupied.get(paragraph.getId()).add(new int[]{start,end});
                String next="amend".equals(kind)?replacement:"not_used".equals(kind)&&n==0?"Not used":"";
                String channel=text(span.get("channel"));
                if("editableText".equals(channel))edits.add(Edit.replaceSpan(id+"-"+n,paragraph,start,end,expected,next));
                else if("catalogText".equals(channel))edits.add(Edit.replaceCatalogSpan(id+"-"+n,paragraph,start,end,expected,next));
                else throw invalid("GRAPH_TEXT_CHANNEL_REQUIRED: "+id);
                n++;
            }
        }
        return editor.applyWithParagraphBookmarks(original,new SourceEditBatch(index.getSourceSha256(),edits),bookmarks,!bookmarks.isEmpty(),layout);
    }
    private static int integer(Object value){if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue())throw invalid("GRAPH_SPAN_INTEGER_REQUIRED");return ((Number)value).intValue();}
    private static void requireNode(Map<String,Map<String,Object>> nodes,String id){if(!nodes.containsKey(id)||!active(nodes.get(id)))throw invalid("GRAPH_REFERENCE_MISSING: "+id);}
    private static boolean active(Map<String,Object> node){return !"excluded".equals(text(node.get("status")));}
    private static boolean input(Map<String,Object> node){return set("input","decision","content").contains(role(node));}
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
