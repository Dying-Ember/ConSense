package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Function;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Mechanical scope-rule projection only; ReviewDiscovery still validates current-source authority. */
final class DraftScopeRuleDraft {
    private DraftScopeRuleDraft() { }
    private static final String ORIGIN="scope_rules_compilation";
    private static final Set<String> ACTIONS=set("keep","delete","unused","check","replace","fill","insert");
    private static final Set<String> OPERATORS=set("yes","no","eq","ne","gt","ge","lt","le","has","lacks","known","unknown","empty","notempty");
    private static final Set<String> VALUE_OPERATORS=set("eq","ne","gt","ge","lt","le","has","lacks");
    private static final Set<String> RULE_FIELDS=set("id","sourceTaskId","scopeId","when","action","replacementEn","valueSourceId","refinedQuote");

    static Result compile(JsonNode response,Map<String,String> instructionQuotes,Map<String,Map<String,Object>> knownRoots) {
        return compile(response,instructionQuotes,knownRoots,rule->knownRoots);
    }
    static Result compile(JsonNode response,Map<String,String> instructionQuotes,Map<String,Map<String,Object>> knownRoots,Function<JsonNode,Map<String,Map<String,Object>>> suppliedRoots) {
        if(response==null||!response.isObject())throw invalid("SCOPE_RULE_RESPONSE_OBJECT_REQUIRED");
        ObjectNode projected=response.deepCopy();List<Map<String,Object>> compilations=new ArrayList<>();List<Object> issues=new ArrayList<>();
        if(!response.has("scopeRules"))return new Result(projected,compilations,issues);
        if(!response.path("scopeRules").isArray()){issues.add(obj("reason","SCOPE_RULES_ARRAY_REQUIRED"));return new Result(projected,compilations,issues);}
        if(response.path("scopeRules").size()>100){issues.add(obj("reason","SCOPE_RULE_LIMIT_100"));return new Result(projected,compilations,issues);}
        if(response.has("nodes")&&!response.path("nodes").isArray()||response.has("requirements")&&!response.path("requirements").isArray()) {
            issues.add(obj("reason","SCOPE_RULE_RESPONSE_ARRAY_REQUIRED"));return new Result(projected,compilations,issues);
        }
        ArrayNode nodes=projected.has("nodes")?(ArrayNode)projected.get("nodes"):projected.putArray("nodes");
        ArrayNode requirements=projected.has("requirements")?(ArrayNode)projected.get("requirements"):projected.putArray("requirements");
        Set<String> occupied=new HashSet<>(),requirementIds=new HashSet<>();Map<String,Integer> ruleCounts=new HashMap<>();
        for(JsonNode node:nodes)occupied.add(node.path("id").asText());for(JsonNode requirement:requirements)requirementIds.add(requirement.path("id").asText());
        for(JsonNode rule:response.path("scopeRules")){String id=rule.path("id").asText();ruleCounts.put(id,ruleCounts.getOrDefault(id,0)+1);}
        int index=0;for(JsonNode rule:response.path("scopeRules")) {
            int ruleIndex=index++;String id=rule.path("id").asText(),owner=rule.path("sourceTaskId").asText(),scope=rule.path("scopeId").asText();
            Map<String,Map<String,Object>> visibleRoots=suppliedRoots.apply(rule);
            try {
                fields(rule,RULE_FIELDS,"SCOPE_RULE_FIELD_UNSUPPORTED");
                if(!rule.path("id").isTextual()||!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw invalid("SCOPE_RULE_ID_INVALID");
                if(ruleCounts.get(id)!=1||occupied.contains(id)||visibleRoots.containsKey(id))throw invalid("SCOPE_RULE_ID_DUPLICATED");
                owner=requiredText(rule,"sourceTaskId");scope=requiredText(rule,"scopeId");
                String quote=instructionQuotes.get(owner);if(quote==null||quote.trim().isEmpty())throw invalid("SCOPE_RULE_OWNER_INSTRUCTION_REQUIRED");
                String action=requiredText(rule,"action");if(!ACTIONS.contains(action))throw invalid("SCOPE_RULE_ACTION_UNSUPPORTED");
                boolean check="check".equals(action);JsonNode when=rule.path("when");LinkedHashSet<String> inputs=new LinkedHashSet<>();
                if(!when.isMissingNode()&&!when.isNull()) {
                    fields(when,set("mode","clauses"),"SCOPE_RULE_WHEN_FIELD_UNSUPPORTED");
                    if(!set("all","any").contains(requiredText(when,"mode"))||!when.path("clauses").isArray())throw invalid("SCOPE_RULE_WHEN_INVALID");
                    if(when.path("clauses").size()>100)throw invalid("SCOPE_RULE_CLAUSE_LIMIT_100");
                    for(JsonNode clause:when.path("clauses")) {
                        fields(clause,set("inputId","operator","value"),"SCOPE_RULE_CLAUSE_FIELD_UNSUPPORTED");
                        String input=requiredText(clause,"inputId"),operator=requiredText(clause,"operator");requireKnownValue(input,visibleRoots);inputs.add(input);
                        if(!OPERATORS.contains(operator))throw invalid("SCOPE_RULE_OPERATOR_UNSUPPORTED");
                        if(!check&&"unknown".equals(operator))throw invalid("SCOPE_RULE_UNKNOWN_VALUE_IS_NOT_AUTHORITY");
                        if(VALUE_OPERATORS.contains(operator)&&(!clause.hasNonNull("value")||!clause.path("value").isValueNode()))throw invalid("SCOPE_RULE_COMPARISON_VALUE_REQUIRED");
                    }
                }
                if(!check&&(when.isMissingNode()||when.isNull()||when.path("clauses").isEmpty()))throw invalid("SCOPE_RULE_NONEMPTY_CONDITION_REQUIRED");
                if(rule.hasNonNull("replacementEn")&&(!rule.path("replacementEn").isTextual()||rule.path("replacementEn").asText().trim().isEmpty()))throw invalid("SCOPE_RULE_REPLACEMENT_TEXT_REQUIRED");
                if(rule.hasNonNull("refinedQuote")&&(!rule.path("refinedQuote").isTextual()||rule.path("refinedQuote").asText().isEmpty()))throw invalid("SCOPE_RULE_REFINED_QUOTE_REQUIRED");
                String valueSource="";if(rule.hasNonNull("valueSourceId")){valueSource=requiredText(rule,"valueSourceId");requireKnownValue(valueSource,visibleRoots);}
                boolean canonicalUnused="unused".equals(action)&&rule.hasNonNull("replacementEn")&&"Not used".equals(rule.path("replacementEn").asText().trim());
                if((rule.hasNonNull("replacementEn")&&!canonicalUnused||!valueSource.isEmpty())&&!set("replace","fill","insert").contains(action))throw invalid("SCOPE_RULE_ACTION_VALUE_UNEXPECTED");
                if(nodes.size()>=100)throw invalid("SCOPE_RULE_COMBINED_NODE_LIMIT_100");
                String requirementId="csr-"+DraftAdoption.hash(id+"\n"+owner+"\n"+scope).substring(0,20);
                if(requirementIds.contains(requirementId))throw invalid("SCOPE_RULE_REQUIREMENT_ID_COLLISION");

                String excerpt=quote.replaceAll("\\s+"," ").trim();if(excerpt.length()>90)excerpt=excerpt.substring(0,90)+"…";
                ObjectNode node=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceTaskId",owner)
                    .put("nameZh",actionLabel(action)+"范围："+excerpt).put("nameEn",(check?"Review":action)+" scope: "+excerpt)
                    .put("questionZh",check?"核对当前范围及待定条件":"按当前条件处理范围").put("questionEn",check?"Review the current scope and unresolved conditions":"Treat the current scope according to its condition")
                    .put("type","boolean").put("role",check?"verification":"derived").put("optional",false).put("sourceQuote",quote)
                    .put("compiledScopeRuleId",id).put("origin",ORIGIN);
                node.putArray("options");ArrayNode branches=node.putArray("branches");
                if(!check){ObjectNode branch=branches.addObject().put("id","match").put("mode",when.path("mode").asText()).put("resultValue",true);branch.set("clauses",when.path("clauses").deepCopy());}
                ObjectNode effect=node.putArray("effects").addObject().put("sourceTaskId",owner).put("scopeId",scope).put("action",action);
                if(!check)effect.put("branchId","match");if(rule.hasNonNull("replacementEn")&&!canonicalUnused)effect.set("replacementEn",rule.get("replacementEn").deepCopy());if(!valueSource.isEmpty())effect.put("valueSourceId",valueSource);
                if(rule.hasNonNull("refinedQuote"))effect.set("refinedQuote",rule.get("refinedQuote").deepCopy());
                ObjectNode requirement=JsonUtils.mapper().createObjectNode().put("id",requirementId).put("sourceTaskId",owner).put("sourceQuote",quote)
                    .put("kind",check?"verification":"amendment").put("reason","Compiled from scopeRules["+ruleIndex+"] ("+id+"); this is a harness projection, not a separate model-written requirement. Current-source scope and conditions still require review.")
                    .put("compiledScopeRuleId",id).put("origin",ORIGIN);requirement.putArray("nodeIds").add(id);
                nodes.add(node);requirements.add(requirement);occupied.add(id);requirementIds.add(requirementId);
                Map<String,Object> compilation=obj("origin",ORIGIN,"compiledScopeRuleId",id,"ruleIndex",ruleIndex,"sourceTaskId",owner,"scopeId",scope,"nodeId",id,"requirementId",requirementId,"action",action,"inputIds",new ArrayList<>(inputs),"valueSourceId",valueSource,"conditionDisposition",check?"review_only":"preserved_as_branch");
                if(canonicalUnused)compilation.put("normalizations",Collections.singletonList(obj("kind","unused_canonical_replacement_omitted","field","replacementEn","canonicalValue","Not used","retained","original_scope_rule")));
                compilations.add(compilation);
            }catch(IllegalArgumentException failure){issues.add(obj("reason",failure.getMessage(),"ruleIndex",ruleIndex,"scopeRuleId",id,"sourceTaskId",owner,"scopeId",scope,"retained","original_scope_rule"));}
        }
        return new Result(projected,compilations,issues);
    }

    static final class Result {
        private final JsonNode response;private final List<Map<String,Object>> compilations;private final List<Object> issues;
        private Result(JsonNode response,List<Map<String,Object>> compilations,List<Object> issues){this.response=response;this.compilations=compilations;this.issues=issues;}
        JsonNode response(){return response.deepCopy();}
        List<Map<String,Object>> compilations(){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> record:compilations)result.add(JsonUtils.readMap(JsonUtils.write(record)));return result;}
        List<Object> issues(){List<Object> result=new ArrayList<>();for(Object issue:issues)result.add(JsonUtils.readMap(JsonUtils.write(issue)));return result;}
    }
    private static void requireKnownValue(String alias,Map<String,Map<String,Object>> knownRoots) {
        Map<String,Object> node=knownRoots.get(alias);if(node==null||"excluded".equals(node.get("status"))||!set("input","decision","content","derived","system","fixed").contains(text(map(node.get("role")).get("kind"))))throw invalid("SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED");
    }
    private static void fields(JsonNode node,Set<String> allowed,String error){if(!node.isObject())throw invalid(error);Iterator<String> fields=node.fieldNames();while(fields.hasNext())if(!allowed.contains(fields.next()))throw invalid(error);}
    private static String requiredText(JsonNode node,String field){if(!node.path(field).isTextual()||node.path(field).asText().trim().isEmpty())throw invalid("SCOPE_RULE_TEXT_REQUIRED: "+field);return node.path(field).asText();}
    private static Set<String> set(String... values){return new HashSet<>(Arrays.asList(values));}
    private static String actionLabel(String action){switch(action){case "keep":return "保留";case "delete":return "删除";case "unused":return "标为不适用";case "replace":return "替换";case "fill":return "填写";case "insert":return "插入";default:return "核对";}}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
