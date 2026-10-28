package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Small receipt summaries; source text and model responses stay in their existing receipt fields. */
final class DraftDiscoveryProbe {
    private DraftDiscoveryProbe() { }
    static final String BUSINESS_REFERENCE_COLLAPSE="identified_business_reference_reduced_to_verification";
    static final String SUPPLIED_DEFINITION_DUPLICATED="supplied_business_definition_duplicated",DANGLING_BRANCH="dangling_model_branch_reference";
    static final class Request {
        final Map<String,Object> receipt,summary;
        final Map<String,Set<String>> scopes=new LinkedHashMap<>();
        final Map<String,Set<String>> ids=new LinkedHashMap<>();
        final Set<String> tasks=new LinkedHashSet<>();
        final Set<String> knownRootAliases=new HashSet<>();
        final Map<String,JsonNode> knownDefinitions=new LinkedHashMap<>();
        final Map<String,BusinessExpectation> business=new LinkedHashMap<>();
        final Set<String> existingBusinessKeys=new LinkedHashSet<>(),collapsedBusiness=new LinkedHashSet<>(),reportedBusiness=new HashSet<>();
        final Set<String> acceptedBusinessOwners=new HashSet<>(),acceptedValueIds=new HashSet<>();
        final Map<String,Set<String>> acceptedReferences=new HashMap<>();
        final boolean grouped;
        Request(Map<String,Object> receipt,Map<String,Object> summary,boolean grouped){this.receipt=receipt;this.summary=summary;this.grouped=grouped;}
    }
    static Request begin(Map<String,Object> receipt,String prompt,String user,Object payload,String taskId,int round) {
        return begin(receipt,prompt,user,payload,taskId,round,null);
    }
    static Request begin(Map<String,Object> receipt,String prompt,String user,Object payload,String taskId,int round,Request previous) {
        JsonNode supplied=JsonUtils.mapper().valueToTree(payload),source=supplied.has("group")?supplied.path("group"):supplied.has("sourceTask")?supplied.path("sourceTask"):supplied;
        Map<String,Object> summary=obj("round",round,"inputSha256",DraftAdoption.hash(prompt+"\n"+user),"inputChars",prompt.length()+user.length(),"reasons",new LinkedHashMap<>(),"stopReasons",new ArrayList<>());
        Request request=new Request(receipt,summary,source.has("instructions"));Map<String,Set<String>> ids=request.ids;for(String kind:Arrays.asList("task","scope","condition","guidance","relationship","member"))ids.put(kind,new TreeSet<>());
        if(previous!=null){request.tasks.addAll(previous.tasks);for(Map.Entry<String,Set<String>> entry:previous.scopes.entrySet())request.scopes.put(entry.getKey(),new LinkedHashSet<>(entry.getValue()));for(String kind:ids.keySet())ids.get(kind).addAll(previous.ids.get(kind));request.business.putAll(previous.business);request.existingBusinessKeys.addAll(previous.existingBusinessKeys);}
        if(request.grouped){
            Map<String,JsonNode> evidence=new HashMap<>();for(JsonNode row:source.path("sharedEvidence"))evidence.put(row.path("ref").asText(),row);
            for(JsonNode instruction:source.path("instructions")){String owner=instruction.path("sourceTaskId").asText();request.tasks.add(owner);Set<String> allowed=request.scopes.computeIfAbsent(owner,key->new LinkedHashSet<>());for(JsonNode ref:instruction.path("evidenceRefs")){JsonNode row=evidence.get(ref.asText());if(row!=null)addIdentity(ids,allowed,row);}}
        }else{
            request.tasks.add(taskId);Set<String> allowed=request.scopes.computeIfAbsent(taskId,key->new LinkedHashSet<>());
            for(String collection:Arrays.asList("scopeCandidates","sourceConditions","relatedGuidanceNotes","sourceRelationships")){String kind="scopeCandidates".equals(collection)?"scope":"sourceConditions".equals(collection)?"condition":"relatedGuidanceNotes".equals(collection)?"guidance":"relationship";for(JsonNode row:source.path(collection)){String id=row.path("id").asText();ids.get(kind).add(id);if("scope".equals(kind))allowed.add(id);}}
        }
        JsonNode context=supplied.has("supplement")?supplied.path("supplement").path("currentSourceContext"):supplied.path("currentSourceContext");
        if(context.isArray())for(JsonNode row:context){Set<String> allowed=request.scopes.get(row.path("sourceTaskId").asText());if(allowed!=null)for(JsonNode item:row.path("context").path("items"))addIdentity(ids,allowed,item);}
        else for(JsonNode item:context.path("items"))addIdentity(ids,request.scopes.get(taskId),item);
        if(previous!=null){request.knownRootAliases.addAll(previous.knownRootAliases);request.knownDefinitions.putAll(previous.knownDefinitions);}for(JsonNode row:source.path("knownRoots")){String id=row.path("id").asText();if(!id.isEmpty()){request.knownRootAliases.add(id);JsonNode definition=definition(row);if(definition!=null)request.knownDefinitions.put(id,definition);}}
        if(request.grouped)businessExpectations(request,source);
        ids.get("task").addAll(request.tasks);Map<String,Object> namespaces=new LinkedHashMap<>();for(Map.Entry<String,Set<String>> entry:ids.entrySet()){entry.getValue().remove("");namespaces.put(entry.getKey(),obj("count",entry.getValue().size(),"sha256",DraftAdoption.hash(JsonUtils.write(entry.getValue()))));}summary.put("allowedIds",namespaces);Map<String,Object> ownership=new TreeMap<>();for(Map.Entry<String,Set<String>> entry:request.scopes.entrySet())ownership.put(entry.getKey(),new TreeSet<>(entry.getValue()));summary.put("scopeOwnershipSha256",DraftAdoption.hash(JsonUtils.write(ownership)));
        receipt.computeIfAbsent("diagnostics",key->new ArrayList<>());DraftBusinessRules.list(receipt.get("diagnostics")).add(summary);return request;
    }
    private static void addIdentity(Map<String,Set<String>> ids,Set<String> scopes,JsonNode row){String kind=row.path("kind").asText(),id=row.path("id").asText();if(ids.containsKey(kind)&&!id.isEmpty())ids.get(kind).add(id);if(scopes!=null&&"scope".equals(kind))scopes.add(id);}

    static void response(Request request,JsonNode response) {
        request.collapsedBusiness.clear();
        request.summary.put("raw",counts(response));
        if(!response.isObject()){reason(request,"response_object_required",Collections.emptyMap(),true);return;}
        for(String field:Arrays.asList("nodes","requirements"))if(!response.path(field).isArray()&&!evidenceOnly(response))reason(request,field+"_array_required",Collections.emptyMap(),true);
        if(request.grouped&&!response.path("taskOutcomes").isArray()&&!evidenceOnly(response))reason(request,"task_outcomes_array_required",Collections.emptyMap(),true);
        for(JsonNode node:response.path("nodes")){
            String owner=request.grouped?node.path("sourceTaskId").asText():request.tasks.iterator().next();Map<String,Object> example=obj("nodeId",node.path("id").asText(),"taskId",owner);
            if(!request.tasks.contains(owner))reason(request,"response_owner_not_supplied",example,true);
            for(String field:Arrays.asList("branches","effects","options"))if(node.has(field)&&!node.path(field).isArray())reason(request,"node_"+field+"_array_required",example,true);
            duplicateDefinition(request,node,example);
            Set<String> branches=new HashSet<>();for(JsonNode branch:node.path("branches"))branches.add(branch.path("id").asText());
            for(JsonNode effect:node.path("effects")){String effectOwner=request.grouped?effect.path("sourceTaskId").asText(owner):owner,scope=effect.path("scopeId").asText(),branch=effect.path("branchId").asText();Map<String,Object> target=obj("nodeId",node.path("id").asText(),"taskId",effectOwner,"scopeId",scope);if(!request.tasks.contains(effectOwner))reason(request,"response_owner_not_supplied",target,true);else if(!scope.isEmpty()&&!request.scopes.getOrDefault(effectOwner,Collections.emptySet()).contains(scope))reason(request,"effect_scope_not_supplied_to_owner",target,true);if(!branch.isEmpty()&&!branches.contains(branch)){target.put("branchId",branch);reason(request,DANGLING_BRANCH,target,true);}}
        }
        if(response.has("scopeRules")&&!response.path("scopeRules").isArray())reason(request,"scope_rules_array_required",Collections.emptyMap(),true);
        for(JsonNode rule:response.path("scopeRules")){String owner=rule.path("sourceTaskId").asText(),scope=rule.path("scopeId").asText();Map<String,Object> example=obj("scopeRuleId",rule.path("id").asText(),"taskId",owner,"scopeId",scope);if(!request.tasks.contains(owner))reason(request,"scope_rule_owner_not_supplied",example,true);else if(!request.scopes.getOrDefault(owner,Collections.emptySet()).contains(scope))reason(request,"scope_rule_scope_not_supplied_to_owner",example,true);}
        businessClaims(request,response);
    }
    private static void duplicateDefinition(Request request,JsonNode node,Map<String,Object> example){
        if(!"input".equals(node.path("role").asText())||request.knownRootAliases.contains(node.path("id").asText()))return;
        JsonNode definition=definition(node);if(definition==null)return;String match=null;
        for(Map.Entry<String,JsonNode> known:request.knownDefinitions.entrySet())if(definition.equals(known.getValue())){if(match!=null)return;match=known.getKey();}
        if(match!=null){Map<String,Object> duplicate=new LinkedHashMap<>(example);duplicate.put("knownRootId",match);reason(request,SUPPLIED_DEFINITION_DUPLICATED,duplicate,true);}
    }
    /** Exact complete definitions only; no topic, keyword or synonym matching. */
    private static JsonNode definition(JsonNode node){
        JsonNode question=node.path("questionEn"),type=node.path("type"),options=node.path("options");
        if(!question.isTextual()||question.asText().trim().isEmpty()||!options.isArray()||(!type.isTextual()&&!type.isObject()))return null;
        ObjectNode normalizedType=type.isTextual()?JsonUtils.mapper().createObjectNode().put("kind",type.asText()):(ObjectNode)type.deepCopy();String kind=normalizedType.path("kind").asText();
        if(!Arrays.asList("boolean","number","text","date","choice","multiselect","record","list").contains(kind))return null;
        if(Arrays.asList("choice","multiselect").contains(kind)&&options.isEmpty())return null;
        for(JsonNode option:options)if(!option.isObject()||!option.hasNonNull("value"))return null;
        if(node.hasNonNull("unit")){if(normalizedType.has("unit")&&!normalizedType.path("unit").equals(node.path("unit")))return null;normalizedType.set("unit",node.path("unit").deepCopy());}
        ObjectNode definition=JsonUtils.mapper().createObjectNode();definition.set("questionEn",question.deepCopy());definition.set("type",normalizedType);definition.set("options",options.deepCopy());
        for(String field:Arrays.asList("questionZh","definitionScope","parentId","answerDomain","fixedValue"))if(node.hasNonNull(field))definition.set(field,node.path(field).deepCopy());return definition;
    }
    static void accepted(Request request,Map<String,Object> accepted) {
        accepted(request,accepted,Collections.emptySet());
    }
    static void accepted(Request request,Map<String,Object> accepted,Set<String> repairableRequirementIds) {
        accepted(request,accepted,repairableRequirementIds,Collections.emptySet());
    }
    static void accepted(Request request,Map<String,Object> accepted,Set<String> repairableRequirementIds,Set<String> repairableLocalScopeRuleIds) {
        request.acceptedBusinessOwners.clear();request.acceptedValueIds.clear();request.acceptedReferences.clear();
        Set<Map<String,Object>> deferred=deferredRequirementQuoteIssues(request,accepted,repairableRequirementIds);
        int deferredQuotes=deferred.size();deferred.addAll(deferredLocalScopeIssues(request,accepted,repairableLocalScopeRuleIds));
        Map<String,Object> counts=obj("nodes",0,"requirements",0,"branches",0,"effects",0,"compiledNodes",0,"compiledRequirements",0,"compiledBranches",0,"compiledEffects",0,"retainedUnresolvedEffects",0,"completeScopeEffects",0,"verificationRequirements",0,"scopeDuties",0,"missingScopeDuties",0,"unrepresentedScopeDuties",0);
        collect(request,accepted,counts,deferred);request.summary.put("accepted",counts);
        Map<String,Object> raw=map(request.summary.get("raw"));for(String part:Arrays.asList("nodes","requirements","branches","effects")){String title=Character.toUpperCase(part.charAt(0))+part.substring(1);int lost=Math.max(0,number(raw.get(part))-(number(counts.get(part))-number(counts.get("compiled"+title))));if(lost>0){request.summary.put("removed"+title,lost);reason(request,"response_parts_not_retained",obj("part",part),!("requirements".equals(part)&&lost==deferredQuotes));}}
        for(String owner:request.collapsedBusiness){BusinessExpectation expected=request.business.get(owner);if(expected==null||intersects(expected.tasks,request.acceptedBusinessOwners))continue;
            boolean shared=false;for(String task:expected.tasks)shared|=intersects(request.acceptedReferences.getOrDefault(task,Collections.emptySet()),request.acceptedValueIds);
            if(!shared&&request.reportedBusiness.add(owner)){request.summary.computeIfAbsent("businessReferenceFailures",key->new ArrayList<>());DraftBusinessRules.list(request.summary.get("businessReferenceFailures")).add(expected.summary("All supplied local tasks claimed identified with verification only, without a business input or a valid shared value reference."));reason(request,BUSINESS_REFERENCE_COLLAPSE,obj("taskId",owner,"part","business_definition"),true);}
        }
    }
    /** The caller proves a unique owned rule and every bad reference is an accepted local from that reply. */
    private static Set<Map<String,Object>> deferredLocalScopeIssues(Request request,Map<String,Object> accepted,Set<String> allowed){
        Set<Map<String,Object>> deferred=Collections.newSetFromMap(new IdentityHashMap<>());request.summary.remove("deferredLocalScopeRepairs");if(!request.grouped||allowed==null||allowed.isEmpty())return deferred;
        Map<String,Integer> counts=new HashMap<>();for(Object raw:list(accepted.get("issues"))){Map<String,Object> issue=map(raw);if("SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED".equals(issue.get("reason")))counts.merge(text(issue.get("scopeRuleId")),1,Integer::sum);}
        List<Object> repairs=new ArrayList<>();for(Object raw:list(accepted.get("issues"))){Map<String,Object> issue=map(raw);String id=text(issue.get("scopeRuleId")),owner=text(issue.get("sourceTaskId")),scope=text(issue.get("scopeId"));if(!"SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED".equals(issue.get("reason"))||!allowed.contains(id)||counts.getOrDefault(id,0)!=1||!request.tasks.contains(owner)||!request.scopes.getOrDefault(owner,Collections.emptySet()).contains(scope))continue;deferred.add(issue);repairs.add(obj("sourceTaskId",owner,"scopeRuleId",id,"scopeId",scope,"reason",issue.get("reason"),"status","awaiting_bounded_repair"));}
        if(!repairs.isEmpty())request.summary.put("deferredLocalScopeRepairs",repairs);return deferred;
    }
    /** The caller proves kind/node references and unique model IDs; this round verifies the actual source rejection. */
    private static Set<Map<String,Object>> deferredRequirementQuoteIssues(Request request,Map<String,Object> accepted,Set<String> allowed){
        Set<Map<String,Object>> deferred=Collections.newSetFromMap(new IdentityHashMap<>());request.summary.remove("deferredRequirementQuoteRepairs");
        if(!request.grouped||allowed==null||allowed.isEmpty())return deferred;
        Map<String,Integer> occurrences=new HashMap<>(),owners=new HashMap<>();requirementIssueCounts(accepted,occurrences);
        for(Object raw:list(accepted.get("taskReceipts")))owners.merge(text(map(raw).get("taskId")),1,Integer::sum);
        List<Object> repairs=new ArrayList<>();
        for(Object raw:list(accepted.get("taskReceipts"))){Map<String,Object> child=map(raw);String owner=text(child.get("taskId"));
            if(owner.isEmpty()||!request.tasks.contains(owner)||owners.getOrDefault(owner,0)!=1||!list(child.get("taskReceipts")).isEmpty()||(!text(child.get("owningTaskId")).isEmpty()&&!owner.equals(text(child.get("owningTaskId")))))continue;
            for(Object value:list(child.get("issues"))){Map<String,Object> issue=map(value);String id=text(issue.get("requirementId")),diagnostic=text(map(issue.get("quoteDiagnostic")).get("reason"));
                if(id.isEmpty()||!allowed.contains(id)||occurrences.getOrDefault(id,0)!=1||!"unsupported_requirement_quote_kind_or_node_reference".equals(issue.get("reason"))||!"requirement_quote_not_in_authorized_evidence".equals(diagnostic)
                        ||(!text(issue.get("sourceTaskId")).isEmpty()&&!owner.equals(text(issue.get("sourceTaskId"))))||(!text(issue.get("taskId")).isEmpty()&&!owner.equals(text(issue.get("taskId")))))continue;
                deferred.add(issue);repairs.add(obj("sourceTaskId",owner,"requirementId",id,"reason",issue.get("reason"),"quoteDiagnostic",obj("reason",diagnostic),"status","awaiting_bounded_repair"));
            }
        }
        if(!repairs.isEmpty())request.summary.put("deferredRequirementQuoteRepairs",repairs);return deferred;
    }
    private static void requirementIssueCounts(Map<String,Object> receipt,Map<String,Integer> counts){
        for(Object raw:list(receipt.get("issues"))){String id=text(map(raw).get("requirementId"));if(!id.isEmpty())counts.merge(id,1,Integer::sum);}
        for(Object node:list(receipt.get("nodes")))for(Object raw:list(map(node).get("discoveryRejectedParts"))){String id=text(map(raw).get("requirementId"));if(!id.isEmpty())counts.merge(id,1,Integer::sum);}
        for(Object child:list(receipt.get("taskReceipts")))requirementIssueCounts(map(child),counts);
    }
    private static void collect(Request request,Map<String,Object> receipt,Map<String,Object> counts,Set<Map<String,Object>> deferred){
        for(Object raw:list(receipt.get("issues"))){Map<String,Object> item=map(raw);if(deferred.contains(item))reason(request,text(item.get("reason")),item,false);else issue(request,item);}String failure=text(receipt.get("reason"));if(!failure.isEmpty())issue(request,obj("reason",failure));
        for(Object child:list(receipt.get("taskReceipts")))collect(request,map(child),counts,deferred);
        String owner=text(receipt.get("taskId"));for(Object raw:list(receipt.get("requirements"))){Map<String,Object> requirement=map(raw);String task=text(requirement.get("sourceTaskId"));if(task.isEmpty())task=owner;for(Object id:list(requirement.get("nodeIds")))request.acceptedReferences.computeIfAbsent(task,key->new HashSet<>()).add(text(id));}
        for(Object raw:list(receipt.get("nodes"))){Map<String,Object> node=map(raw);String role=text(map(node.get("role")).get("kind")),task=text(node.get("sourceTaskId"));if(task.isEmpty())task=owner;if(businessInput(role))request.acceptedBusinessOwners.add(task);if(valueRole(role)&&!"source_seed".equals(node.get("definitionKind")))request.acceptedValueIds.add(text(node.get("id")));}
        add(counts,"requirements",list(receipt.get("requirements")).size());for(Object raw:list(receipt.get("requirements"))){Map<String,Object> requirement=map(raw);if("verification".equals(requirement.get("kind")))add(counts,"verificationRequirements",1);if(compiled(requirement))add(counts,"compiledRequirements",1);}Map<String,Object> coverage=map(receipt.get("scopeCoverage"));add(counts,"scopeDuties",list(coverage.get("scopeIds")).size());add(counts,"missingScopeDuties",list(coverage.get("missingScopeIds")).size());add(counts,"unrepresentedScopeDuties",list(coverage.get("unrepresentedScopeIds")).size());
        for(Object raw:list(receipt.get("nodes"))){Map<String,Object> node=map(raw);int branches=list(map(node.get("correction")).get("branches")).size(),effects=list(node.get("editTargets")).size();add(counts,"nodes",1);add(counts,"branches",branches);add(counts,"effects",effects);if(compiled(node)){add(counts,"compiledNodes",1);add(counts,"compiledBranches",branches);add(counts,"compiledEffects",effects);}for(Object target:list(node.get("editTargets"))){if(Boolean.TRUE.equals(map(target).get("needsRebind"))||"check".equals(map(target).get("action")))add(counts,"retainedUnresolvedEffects",1);else if(Boolean.TRUE.equals(map(target).get("scopeComplete")))add(counts,"completeScopeEffects",1);}for(Object rejected:list(node.get("discoveryRejectedParts")))issue(request,map(rejected));}
    }
    private static boolean compiled(Map<String,Object> item){return "scope_rules_compilation".equals(item.get("origin"))&&!text(item.get("compiledScopeRuleId")).isEmpty();}

    /** Only registered current instruction references with fully supplied business definitions enter this probe. */
    private static void businessExpectations(Request request,JsonNode source) {
        for(JsonNode key:source.path("reviewedRootKeys"))request.existingBusinessKeys.add(root(key.asText()));
        for(JsonNode field:source.path("restoredDefinitionReferences"))request.existingBusinessKeys.add(root(field.path("baselineKey").asText()));
        Set<String> supplied=new HashSet<>();for(JsonNode field:source.path("fixedContext").path("fields"))
            if(!field.path("key").asText().isEmpty()&&field.path("parentKey").asText().isEmpty()&&Arrays.asList("boolean","number","text","date","choice","multiselect","record","list").contains(field.path("kind").asText()))supplied.add(field.path("key").asText());
        for(JsonNode instruction:source.path("instructions")) {
            JsonNode alignment=instruction.path("currentTemplateAlignment");String task=instruction.path("sourceTaskId").asText(),sourceId=instruction.path("sourceId").asText();
            if(task.isEmpty()||sourceId.isEmpty()||!"guidance".equals(instruction.path("kind").asText())||!"exact_instruction_reference".equals(alignment.path("definitionStatus").asText())
                    ||!"current_instruction_reference".equals(alignment.path("referenceKind").asText())||!"current_observation_complete".equals(alignment.path("scopeStatus").asText())
                    ||!instruction.path("unresolvedConditionIds").isArray()||!instruction.path("unresolvedConditionIds").isEmpty())continue;
            Set<String> expected=new TreeSet<>(),registered=new HashSet<>(),scopes=new HashSet<>(),owners=new TreeSet<>();
            for(JsonNode action:instruction.path("fixedActionReferences"))for(JsonNode key:action.path("inputKeys"))registered.add(root(key.asText()));
            for(JsonNode key:alignment.path("fixedRootKeys")){String name=root(key.asText());if(supplied.contains(name)&&registered.contains(name))expected.add(name);}
            for(JsonNode id:instruction.path("scopeObligationIds"))if(request.scopes.getOrDefault(task,Collections.emptySet()).contains(id.asText()))scopes.add(id.asText());
            if(expected.isEmpty()||scopes.isEmpty())continue;
            for(JsonNode candidate:source.path("instructions"))if(sourceId.equals(candidate.path("sourceId").asText()))for(JsonNode id:candidate.path("scopeObligationIds"))if(scopes.contains(id.asText()))owners.add(candidate.path("sourceTaskId").asText());
            owners.remove("");owners.add(task);request.business.put(task,new BusinessExpectation(task,expected,owners));
        }
        // Existing graph IDs are not exposed here; any relevant restored root conservatively exempts its cohort.
        request.business.values().removeIf(expected->intersects(expected.keys,request.existingBusinessKeys));
        if(!request.business.isEmpty()){List<Object> expectations=new ArrayList<>();for(BusinessExpectation expected:request.business.values())expectations.add(expected.summary("Registered current instruction and full business definition are supplied; this is a capability probe, not semantic confirmation."));request.summary.put("businessReferenceExpectations",expectations);}
    }

    private static void businessClaims(Request request,JsonNode response) {
        if(request.business.isEmpty()||!response.path("nodes").isArray()||!response.path("requirements").isArray()||!response.path("taskOutcomes").isArray())return;
        for(String key:Arrays.asList("sourceContextRequests","sourceSearchRequests","baselineContextRequests","baselineSearchRequests"))if(response.path(key).isArray()&&!response.path(key).isEmpty())return;
        Map<String,String> outcomes=new HashMap<>();Set<String> repeated=new HashSet<>();for(JsonNode outcome:response.path("taskOutcomes")){String owner=outcome.path("sourceTaskId").asText();if(outcomes.put(owner,outcome.path("status").asText())!=null)repeated.add(owner);}
        for(BusinessExpectation expected:request.business.values()) {
            boolean claim=true;Set<String> represented=new HashSet<>(),references=new HashSet<>();
            for(String task:expected.tasks)if(!"identified".equals(outcomes.get(task))||repeated.contains(task))claim=false;
            for(JsonNode requirement:response.path("requirements"))if(expected.tasks.contains(requirement.path("sourceTaskId").asText())){
                represented.add(requirement.path("sourceTaskId").asText());if(!"verification".equals(requirement.path("kind").asText()))claim=false;
                for(JsonNode ref:requirement.path("nodeIds"))references.add(ref.asText());
            }
            if(!claim||!represented.containsAll(expected.tasks)||intersects(references,request.knownRootAliases))continue;
            for(JsonNode node:response.path("nodes")){String role=node.path("role").asText();if(((businessInput(role)||request.knownRootAliases.contains(node.path("id").asText()))&&expected.tasks.contains(node.path("sourceTaskId").asText()))||(valueRole(role)&&references.contains(node.path("id").asText()))){claim=false;break;}}
            if(claim)request.collapsedBusiness.add(expected.owner);
        }
    }

    private static boolean businessInput(String role){return Arrays.asList("input","decision","content").contains(role);}
    private static boolean valueRole(String role){return businessInput(role)||Arrays.asList("derived","fixed","system").contains(role);}
    private static boolean intersects(Set<String> left,Set<String> right){for(String value:left)if(right.contains(value))return true;return false;}
    private static String root(String key){int dot=key.indexOf('.');return dot<0?key:key.substring(0,dot);}
    private static final class BusinessExpectation {
        final String owner;final Set<String> keys,tasks;
        BusinessExpectation(String owner,Set<String> keys,Set<String> tasks){this.owner=owner;this.keys=keys;this.tasks=tasks;}
        Map<String,Object> summary(String why){return obj("taskId",owner,"expectedKeys",new ArrayList<>(keys),"taskIds",new ArrayList<>(tasks),"why",why);}
    }
    static void issues(Request request,List<Object> issues){for(Object raw:issues)issue(request,map(raw));}
    private static void issue(Request request,Map<String,Object> issue){String code=text(issue.get("reason"));int colon=code.indexOf(':');if(colon>=0)code=code.substring(0,colon);if(code.isEmpty())return;boolean fatal=code.startsWith("DISCOVERY_")||code.startsWith("GRAPH_")||code.toLowerCase(Locale.ROOT).startsWith("scope_rule")||Arrays.asList("requirements_array_required","group_nodes_array_required","group_requirement_task_required","group_node_task_unavailable","group_outcome_task_unavailable","group_task_outcome_duplicated","group_task_outcome_missing_or_invalid","group_source_request_task_required","group_source_requests_array_required","source_context_requests_array_required","source_context_request_invalid","source_search_queries_array_required","source_search_query_invalid","source_context_response_object_required","source_context_candidate_object_required","source_context_candidate_conflict","source_context_rule_conflict","unsupported_requirement_quote_kind_or_node_reference","node_without_supported_requirement_mapping","group_no_requirement_conflicts_with_candidates","group_identified_without_supported_requirement","group_nodes_invalid","group_result_conversion_failed","EXPLICIT_SCOPE_QUOTE_MISMATCH").contains(code);reason(request,code,issue,fatal);}
    static void incomplete(Request request){reason(request,"response_incomplete",Collections.emptyMap(),true);}
    static boolean evidenceOnly(JsonNode response){if(!response.isObject()||response.has("nodes")||response.has("requirements")||response.has("taskOutcomes")||response.has("scopeRules"))return false;for(String field:Arrays.asList("sourceContextRequests","sourceSearchRequests","baselineContextRequests","baselineSearchRequests"))if(response.path(field).isArray()&&!response.path(field).isEmpty())return true;return false;}
    static void finish(Map<String,Object> receipt,boolean failFast){if(stopped(receipt,failFast)&&"completed".equals(receipt.get("status")))receipt.put("status","partial");}
    static boolean stopped(Map<String,Object> receipt,boolean failFast){return failFast&&!list(receipt.get("stopReasons")).isEmpty();}
    private static void reason(Request request,String code,Map<String,Object> source,boolean fatal){
        Map<String,Object> reasons=map(request.summary.get("reasons"));Map<String,Object> item=map(reasons.computeIfAbsent(code,key->obj("count",0,"examples",new ArrayList<>())));add(item,"count",1);List<Object> examples=DraftBusinessRules.list(item.get("examples"));if(examples.size()<3){Map<String,Object> example=new LinkedHashMap<>();for(String key:Arrays.asList("taskId","sourceTaskId","nodeId","id","scopeId","sourceScopeId","requirementId","knownRootId","branchId","scopeRuleId","part"))if(source.containsKey(key))example.put(key,source.get(key));if(!example.isEmpty()&&!examples.contains(example))examples.add(example);}
        if(fatal){for(Map<String,Object> target:Arrays.asList(request.summary,request.receipt)){target.computeIfAbsent("stopReasons",key->new ArrayList<>());List<Object> stops=DraftBusinessRules.list(target.get("stopReasons"));if(!stops.contains(code))stops.add(code);}}
    }
    private static Map<String,Object> counts(JsonNode response){int branches=0,effects=0;for(JsonNode node:response.path("nodes")){branches+=node.path("branches").isArray()?node.path("branches").size():0;effects+=node.path("effects").isArray()?node.path("effects").size():0;}return obj("nodes",response.path("nodes").isArray()?response.path("nodes").size():0,"requirements",response.path("requirements").isArray()?response.path("requirements").size():0,"branches",branches,"effects",effects,"scopeRules",response.path("scopeRules").isArray()?response.path("scopeRules").size():0);}
    private static void add(Map<String,Object> counts,String key,int amount){counts.put(key,number(counts.get(key))+amount);}
    private static int number(Object value){return value instanceof Number?((Number)value).intValue():0;}
}
