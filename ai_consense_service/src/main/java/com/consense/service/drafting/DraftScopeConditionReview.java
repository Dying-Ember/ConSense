package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Reference-only omission checks. Current-source meaning, adoption and native binding stay separate. */
final class DraftScopeConditionReview {
    private DraftScopeConditionReview() { }
    private static final String TWO="twoEnvelopeTendering",FORMAT="electronicTendering",WTO="wtoGpaApplies";
    private static final List<String> INPUT_KEYS=Arrays.asList(TWO,FORMAT,WTO);
    private static final Map<String,List<Map<String,Object>>> CASES=referenceCases();

    /** Copy only packet containers that gain references; never mutate saved source observations. */
    static Map<String,Object> enrich(Map<String,Object> payload) {
        Map<String,Object> result=new LinkedHashMap<>(payload);List<Object> instructions=new ArrayList<>();
        for(Object raw:list(payload.get("instructions"))) {
            Map<String,Object> instruction=new LinkedHashMap<>(map(raw));List<Object> references=new ArrayList<>();
            for(Object value:list(instruction.get("fixedActionReferences"))) {
                Map<String,Object> reference=new LinkedHashMap<>(map(value));List<Map<String,Object>> cases=CASES.get(text(reference.get("actionId")));
                if(cases!=null){reference.put("reviewOnly",true);reference.put("referenceRuleVersion",DraftBusinessRules.RULE_VERSION);reference.put("referenceCases",copyCases(cases));}
                references.add(reference);
            }
            if(instruction.containsKey("fixedActionReferences"))instruction.put("fixedActionReferences",references);instructions.add(instruction);
        }
        result.put("instructions",instructions);return result;
    }

    /** Reference gaps request a bounded review; this helper never inserts conditions or actions. */
    static List<Object> review(Map<String,Object> payload,JsonNode response) {
        List<Object> requests=new ArrayList<>();if(response==null||!response.path("scopeRules").isArray())return requests;
        Map<String,List<Map<String,Object>>> owners=new HashMap<>();for(Object raw:list(payload.get("instructions"))) {
            Map<String,Object> instruction=map(raw);owners.computeIfAbsent(text(instruction.get("sourceTaskId")),key->new ArrayList<>()).add(instruction);
        }
        Map<String,String> aliases=aliases(payload);Set<String> seen=new HashSet<>();
        for(JsonNode rule:response.path("scopeRules")) {
            String owner=rule.path("sourceTaskId").asText(),scope=rule.path("scopeId").asText(),ruleId=rule.path("id").asText();
            if("check".equals(rule.path("action").asText())||owner.isEmpty()||scope.isEmpty()||ruleId.isEmpty())continue;
            List<Map<String,Object>> matches=owners.getOrDefault(owner,Collections.emptyList());if(matches.size()!=1)continue;
            Map<String,Object> instruction=matches.get(0);if(!ownsScope(payload,instruction,scope))continue;
            Set<String> dependencies=new LinkedHashSet<>();List<Object> cases=new ArrayList<>();List<String> actionIds=new ArrayList<>();
            for(Object raw:list(instruction.get("fixedActionReferences"))) {
                String actionId=text(map(raw).get("actionId"));List<Map<String,Object>> table=CASES.get(actionId);if(table==null||actionIds.contains(actionId)||!mayTreatNonPending(rule,table,aliases))continue;
                actionIds.add(actionId);dependencies.addAll(dependencies(table));for(Map<String,Object> row:table){Map<String,Object> example=copy(row);example.put("actionId",actionId);cases.add(example);}
            }
            if(dependencies.isEmpty()||!seen.add(owner+"\n"+scope+"\n"+ruleId))continue;
            Set<String> referenced=new HashSet<>();for(JsonNode clause:rule.path("when").path("clauses"))referenced.add(clause.path("inputId").asText());
            List<String> missingKeys=new ArrayList<>(),missingAliases=new ArrayList<>(),unresolvedKeys=new ArrayList<>();
            for(String key:dependencies){String alias=aliases.get(key);if(alias==null){unresolvedKeys.add(key);continue;}if(!referenced.contains(alias)){missingKeys.add(key);missingAliases.add(alias);}}
            String alignment=text(map(instruction.get("currentTemplateAlignment")).get("definitionStatus"));
            if(missingKeys.isEmpty()&&unresolvedKeys.isEmpty()&&"exact_instruction_reference".equals(alignment))continue;
            String reason=!"exact_instruction_reference".equals(alignment)?"scope_reference_current_alignment_requires_review":!unresolvedKeys.isEmpty()?"scope_reference_input_mapping_unresolved":"scope_reference_condition_inputs_unaccounted";
            requests.add(obj("sourceTaskId",owner,"scopeIds",Collections.singletonList(scope),"ruleIds",Collections.singletonList(ruleId),"reason",reason,
                    "missingReferenceInputAliases",missingAliases,"missingReferenceInputKeys",missingKeys,"unresolvedReferenceInputKeys",unresolvedKeys,
                    "referenceActionIds",actionIds,"referenceCases",cases,"reviewOnly",true,
                    "reviewInstruction","Account for these reference dependencies using current-source evidence. Repair only the named pending rule, request missing current evidence, or retain an explicit check with the difference explained. Reference cases are hypothetical, not project answers or current editing authority; a pending reference route requires separately adopted target-specific procedure wording, not automatic deletion."));
        }
        reviewSemanticScopes(payload,response,owners,requests);
        reviewCoverage(payload,response,owners,aliases,requests);return requests;
    }

    /** A business-meaning reference cannot bind an unproven current target, even for a keep branch. */
    private static void reviewSemanticScopes(Map<String,Object> payload,JsonNode response,Map<String,List<Map<String,Object>>> owners,List<Object> requests) {
        Map<String,Integer> ruleCounts=new HashMap<>();for(JsonNode rule:response.path("scopeRules")){String id=rule.path("id").asText();ruleCounts.put(id,ruleCounts.getOrDefault(id,0)+1);}
        for(Object raw:list(payload.get("instructions"))) {
            Map<String,Object> instruction=map(raw),alignment=map(instruction.get("currentTemplateAlignment"));String owner=text(instruction.get("sourceTaskId")),source=text(instruction.get("sourceId"));
            if(owner.isEmpty()||owners.getOrDefault(owner,Collections.emptyList()).size()!=1
                    ||!"definition_reference_only".equals(alignment.get("definitionStatus"))||!"requires_review".equals(alignment.get("scopeStatus"))
                    ||!"current_instruction_reference".equals(alignment.get("referenceKind"))||!source.equals(text(alignment.get("sourceId"))))continue;
            List<Object> references=new ArrayList<>();Set<String> actions=new LinkedHashSet<>();
            for(Object value:list(alignment.get("fixedActionSemanticReferences"))) {
                Map<String,Object> reference=map(value);String action=text(reference.get("actionId"));
                if(action.isEmpty()||!"fixed_action_semantic_reference".equals(reference.get("referenceKind"))
                        ||!Boolean.FALSE.equals(reference.get("editableAuthority"))||!"requires_review".equals(reference.get("scopeStatus"))||!actions.add(action))continue;
                references.add(copy(reference));
            }
            if(references.isEmpty())continue;
            for(Object value:new LinkedHashSet<>(list(instruction.get("scopeObligationIds")))) {
                String scope=text(value);if(!list(alignment.get("currentScopeIds")).contains(scope)||!ownsScope(payload,instruction,scope))continue;
                List<Object> rules=new ArrayList<>();for(JsonNode rule:response.path("scopeRules")) {
                    String id=rule.path("id").asText();if(!id.isEmpty()&&ruleCounts.get(id)==1&&owner.equals(rule.path("sourceTaskId").asText())
                            &&scope.equals(rule.path("scopeId").asText())&&!"check".equals(rule.path("action").asText()))rules.add(id);
                }
                if(rules.isEmpty())continue;
                requests.add(obj("sourceTaskId",owner,"sourceTaskIds",Collections.singletonList(owner),"scopeIds",Collections.singletonList(scope),"ruleIds",rules,
                        "reason","scope_semantic_reference_without_bound_target","referenceActionIds",new ArrayList<>(actions),"semanticReferences",references,"reviewOnly",true,"editableAuthority",false,
                        "reviewInstruction","The fixed action supplies business meaning only; it does not prove the current amendment target. Repair only these named pending rules under their existing IDs, owners and scopes. Request current parent/member evidence through the existing source tools if needed. An instruction to amend is not permission to delete, and whole-provision replacement wording must not be applied to a partial scope. A semantic reference, a newly fetched parent, or a model-authored refinedQuote does not by itself establish an independent source-bound new scope. Until such a scope has been independently established, return check for these rules, including keep, and retain the unresolved scope and condition evidence. Do not borrow historical coordinates, enlarge a supplied scope, or invent replacement wording."));
            }
        }
    }

    /** An exact current body may reuse finite reference cases, never historical editing authority. */
    private static void reviewCoverage(Map<String,Object> payload,JsonNode response,Map<String,List<Map<String,Object>>> owners,Map<String,String> aliases,List<Object> requests) {
        Map<String,CoverageScope> scopes=new LinkedHashMap<>();
        for(Object raw:list(payload.get("instructions"))) {
            Map<String,Object> instruction=map(raw),alignment=map(instruction.get("currentTemplateAlignment"));String source=text(instruction.get("sourceId"));
            String owner=text(instruction.get("sourceTaskId"));if(owner.isEmpty()||owners.getOrDefault(owner,Collections.emptyList()).size()!=1||!"exact_instruction_reference".equals(alignment.get("definitionStatus"))||!"current_observation_complete".equals(alignment.get("scopeStatus"))||!"current_instruction_reference".equals(alignment.get("referenceKind"))||!source.equals(text(alignment.get("sourceId"))))continue;
            for(Object value:list(instruction.get("scopeObligationIds"))) {
                String scope=text(value);if(!ownsScope(payload,instruction,scope)||!list(alignment.get("currentScopeIds")).contains(scope)||!completeScope(payload,source,scope))continue;
                for(Object ref:list(instruction.get("fixedActionReferences"))) {String action=text(map(ref).get("actionId"));if(!CASES.containsKey(action))continue;String key=source+"\n"+scope+"\n"+action;scopes.computeIfAbsent(key,ignored->new CoverageScope(source,scope,action,instruction));}
            }
        }
        Map<String,Integer> ids=new HashMap<>();for(JsonNode rule:response.path("scopeRules")){String id=rule.path("id").asText();ids.put(id,ids.getOrDefault(id,0)+1);}
        for(CoverageScope scope:scopes.values()) {
            List<JsonNode> rules=new ArrayList<>();Set<String> ruleIds=new LinkedHashSet<>(),taskIds=new LinkedHashSet<>();
            for(JsonNode rule:response.path("scopeRules")) {
                String owner=rule.path("sourceTaskId").asText(),id=rule.path("id").asText();List<Map<String,Object>> candidates=owners.getOrDefault(owner,Collections.emptyList());
                if(id.isEmpty()||ids.get(id)!=1||!scope.scope.equals(rule.path("scopeId").asText())||candidates.size()!=1||!scope.source.equals(text(candidates.get(0).get("sourceId")))||!ownsScope(payload,candidates.get(0),scope.scope))continue;
                rules.add(rule);ruleIds.add(id);taskIds.add(owner);
            }
            String primary=taskIds.isEmpty()?text(scope.instruction.get("sourceTaskId")):taskIds.iterator().next();if(taskIds.isEmpty())taskIds.add(primary);
            List<Object> missing=new ArrayList<>();for(Map<String,Object> row:CASES.get(scope.action)) {
                String expected="retain".equals(row.get("action"))?"keep":"delete".equals(row.get("action"))?"delete":"not_used".equals(row.get("action"))?"unused":"";
                if(expected.isEmpty()){boolean treated=false;for(JsonNode rule:rules)if(!"check".equals(rule.path("action").asText())&&Boolean.TRUE.equals(matches(rule.path("when"),map(row.get("inputs")),aliases)))treated=true;if(treated){Map<String,Object> gap=copy(row);gap.put("actionId",scope.action);gap.put("expectedAction","check");gap.put("coverageStatus","pending_case_treated");missing.add(gap);}continue;}
                boolean covered=false,conflict=false,unknown=false;for(JsonNode rule:rules) {
                    String action=rule.path("action").asText();if("check".equals(action))continue;Boolean match=matches(rule.path("when"),map(row.get("inputs")),aliases);
                    if(match==null){unknown=true;continue;}if(!match)continue;if(expected.equals(action))covered=true;else conflict=true;
                }
                if(covered&&!conflict&&!unknown)continue;Map<String,Object> gap=copy(row);gap.put("actionId",scope.action);gap.put("expectedAction",expected);gap.put("coverageStatus",conflict?"conflicting_actions":unknown?"unknown":"missing");missing.add(gap);
            }
            if(missing.isEmpty())continue;Map<String,Object> request=null;for(Object raw:requests){Map<String,Object> prior=map(raw);if(taskIds.contains(text(prior.get("sourceTaskId")))&&list(prior.get("scopeIds")).contains(scope.scope)){request=prior;break;}}
            if(request==null){request=obj("sourceTaskId",primary,"scopeIds",Collections.singletonList(scope.scope),"reason","scope_reference_cases_uncovered","referenceActionIds",Collections.singletonList(scope.action),"referenceCases",copyCases(CASES.get(scope.action)),"reviewOnly",true);requests.add(request);}
            request.put("sourceTaskIds",new ArrayList<>(taskIds));Set<Object> repairs=new LinkedHashSet<>(list(request.get("ruleIds")));repairs.addAll(ruleIds);request.put("ruleIds",new ArrayList<>(repairs));
            List<Object> gaps=new ArrayList<>(list(request.get("missingReferenceCases")));gaps.addAll(missing);request.put("missingReferenceCases",gaps);
            request.put("reviewInstruction","Review every listed finite reference case against this exact current-source scope. Add an explicit supported branch using its expectedAction for each missing non-pending case, repair only the named pending rules under their original owners, or retain check with the source difference or unknown premise explained. Rules for the same current scope may be shared; do not duplicate guidance duties. These cases are hypothetical reference checks, not project answers or permission to edit. Pending procedure cases do not imply deletion; unused preserves the numbered provision as Not used instead of deleting it.");
        }
    }

    private static boolean completeScope(Map<String,Object> payload,String source,String scope){for(Object raw:list(payload.get("sharedEvidence"))){Map<String,Object> row=map(raw);if("scope".equals(row.get("kind"))&&source.equals(text(row.get("sourceId")))&&scope.equals(text(row.get("id"))))return Boolean.TRUE.equals(row.get("scopeComplete"));}return false;}
    private static boolean mayTreatNonPending(JsonNode rule,List<Map<String,Object>> cases,Map<String,String> aliases){for(Map<String,Object> row:cases)if(!"pending".equals(row.get("action"))&&!Boolean.FALSE.equals(matches(rule.path("when"),map(row.get("inputs")),aliases)))return true;return false;}
    /** Finite assigned facts use three-valued logic; unknown facts never override a decisive term. */
    private static Boolean matches(JsonNode when,Map<String,Object> values,Map<String,String> aliases) {
        if(!when.isObject()||!fields(when,"mode","clauses")||!Arrays.asList("all","any").contains(when.path("mode").asText())||!when.path("clauses").isArray()||when.path("clauses").isEmpty())return null;
        boolean all="all".equals(when.path("mode").asText()),unknown=false;for(JsonNode clause:when.path("clauses")) {
            Boolean term=matchesClause(clause,values,aliases);if(all&&Boolean.FALSE.equals(term))return false;if(!all&&Boolean.TRUE.equals(term))return true;unknown|=term==null;
        }return unknown?null:all;
    }
    private static Boolean matchesClause(JsonNode clause,Map<String,Object> values,Map<String,String> aliases){
        if(!clause.isObject()||!fields(clause,"inputId","operator","value")||!clause.path("inputId").isTextual()||!clause.path("operator").isTextual())return null;
        String key=null;for(String candidate:values.keySet())if(clause.path("inputId").asText().equals(aliases.get(candidate))){key=candidate;break;}if(key==null)return null;
        Object actual=values.get(key);String operator=clause.path("operator").asText();
        if("yes".equals(operator)||"no".equals(operator))return actual instanceof Boolean?Objects.equals(actual,"yes".equals(operator)):null;
        if(!Arrays.asList("eq","ne").contains(operator)||!clause.hasNonNull("value")||!clause.path("value").isValueNode())return null;
        boolean same=Objects.equals(JsonUtils.mapper().valueToTree(actual),clause.get("value"));return "eq".equals(operator)?same:!same;
    }
    private static boolean fields(JsonNode node,String... allowed){Set<String> names=new HashSet<>(Arrays.asList(allowed));Iterator<String> fields=node.fieldNames();while(fields.hasNext())if(!names.contains(fields.next()))return false;return true;}
    private static final class CoverageScope {final String source,scope,action;final Map<String,Object> instruction;CoverageScope(String source,String scope,String action,Map<String,Object> instruction){this.source=source;this.scope=scope;this.action=action;this.instruction=instruction;}}

    private static boolean ownsScope(Map<String,Object> payload,Map<String,Object> instruction,String scope) {
        String source=text(instruction.get("sourceId"));if(source.isEmpty()||!list(instruction.get("scopeObligationIds")).contains(scope))return false;
        String ref=source+"|scope|"+scope;if(!list(instruction.get("evidenceRefs")).contains(ref))return false;
        int count=0;for(Object raw:list(payload.get("sharedEvidence"))){Map<String,Object> evidence=map(raw);if("scope".equals(evidence.get("kind"))&&source.equals(text(evidence.get("sourceId")))&&scope.equals(text(evidence.get("id")))&&ref.equals(text(evidence.get("ref"))))count++;}return count==1;
    }

    private static Map<String,String> aliases(Map<String,Object> payload) {
        Map<String,String> result=new HashMap<>();Set<String> blocked=new HashSet<>();Map<String,Integer> idCounts=new HashMap<>();for(Object raw:list(payload.get("knownRoots"))){String id=text(map(raw).get("id"));idCounts.put(id,idCounts.getOrDefault(id,0)+1);}
        for(Object raw:list(payload.get("knownRoots"))) {
            Map<String,Object> node=map(raw);String id=text(node.get("id")),key=text(node.get("baselineKey"));
            if(key.isEmpty())key=text(map(node.get("currentDefinitionReference")).get("baselineKey"));if(key.isEmpty())key=text(map(node.get("catalogAlignment")).get("baselineKey"));
            if(key.isEmpty()&&INPUT_KEYS.contains(id))key=id;
            if(!INPUT_KEYS.contains(key))continue;
            if(id.isEmpty()||idCounts.get(id)!=1||"excluded".equals(node.get("status"))||"verification".equals(text(map(node.get("role")).get("kind")))||result.putIfAbsent(key,id)!=null)blocked.add(key);
        }
        for(String key:blocked)result.remove(key);return result;
    }

    private static Set<String> dependencies(List<Map<String,Object>> rows) {
        Set<String> result=new LinkedHashSet<>();for(String key:INPUT_KEYS)for(Map<String,Object> a:rows)for(Map<String,Object> b:rows) {
            Map<String,Object> av=new LinkedHashMap<>(map(a.get("inputs"))),bv=new LinkedHashMap<>(map(b.get("inputs")));Object first=av.remove(key),second=bv.remove(key);
            if(!Objects.equals(first,second)&&av.equals(bv)&&!Objects.equals(a.get("action"),b.get("action")))result.add(key);
        }return result;
    }

    private static Map<String,List<Map<String,Object>>> referenceCases() {
        // shortcut: finite BQ and observed WTO references only; other domains need evidenced scenarios.
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();for(String action:Arrays.asList("NTT-2-L10PRO","NTT-2-PAPER","NTT-3C-L10PRO","NTT-3C-PAPER"))result.put(action,new ArrayList<>());
        for(boolean two:new boolean[]{false,true})for(String format:Arrays.asList("L10Pro","Hardcopy")) {
            Map<String,Object> inputs=obj(TWO,two,FORMAT,format);Map<String,Object> plan=DraftBusinessRules.plan(inputs);
            for(Object raw:list(plan.get("actions"))){Map<String,Object> action=map(raw);String id=text(action.get("id"));if(result.containsKey(id))result.get(id).add(Collections.unmodifiableMap(obj("inputs",Collections.unmodifiableMap(new LinkedHashMap<>(inputs)),"action",action.get("action"))));}
        }
        result.put("NTT-9-WTO",new ArrayList<>());for(boolean applies:new boolean[]{false,true}){Map<String,Object> inputs=obj(WTO,applies);for(Object raw:list(DraftBusinessRules.plan(inputs).get("actions"))){Map<String,Object> action=map(raw);if("NTT-9-WTO".equals(action.get("id")))result.get("NTT-9-WTO").add(Collections.unmodifiableMap(obj("inputs",Collections.unmodifiableMap(new LinkedHashMap<>(inputs)),"action",action.get("action"))));}}
        for(Map.Entry<String,List<Map<String,Object>>> entry:result.entrySet()){
            if(entry.getValue().size()!=("NTT-9-WTO".equals(entry.getKey())?2:4))throw new IllegalStateException("Incomplete scope reference cases: "+entry.getKey());Set<String> inputs=dependencies(entry.getValue());Map<String,Map<String,Object>> unique=new LinkedHashMap<>();
            for(Map<String,Object> row:entry.getValue()){Map<String,Object> projected=new LinkedHashMap<>();for(String key:inputs)projected.put(key,map(row.get("inputs")).get(key));Map<String,Object> example=Collections.unmodifiableMap(obj("inputs",Collections.unmodifiableMap(projected),"action",row.get("action")));unique.putIfAbsent(JsonUtils.write(example),example);}entry.setValue(Collections.unmodifiableList(new ArrayList<>(unique.values())));
        }return Collections.unmodifiableMap(result);
    }
    private static List<Object> copyCases(List<Map<String,Object>> cases){List<Object> result=new ArrayList<>();for(Map<String,Object> row:cases)result.add(copy(row));return result;}
    private static Map<String,Object> copy(Map<String,Object> row){return JsonUtils.readMap(JsonUtils.write(row));}
}
