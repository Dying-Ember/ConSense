package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftDiscoveryProbeTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),NOTE="Select the issue format.",BODY="Complete current alternative.";

    @Test void wrongBranchNamespaceStopsBeforeTheAutomaticRepairAndRetainsTheQuestion() {
        DraftAutomaticDiscovery.Plan plan=plan(true);Map<String,Object> draft=draft(plan,"keep",true);Map<String,Object> node=map(list(draft.get("nodes")).get(0));map(list(node.get("branches")).get(0)).put("scopeId","scope-a");map(list(node.get("branches")).get(0)).put("scopeMode","keep");String raw=JsonUtils.write(draft);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertEquals("partial",result.get("status"));assertEquals(raw,result.get("rawResponse"));assertFalse(list(result.get("stopReasons")).isEmpty());assertEquals(1,list(child(result).get("nodes")).size());assertTrue(list(map(list(child(result).get("nodes")).get(0)).get("editTargets")).isEmpty());
        Map<String,Object> diagnostic=map(list(result.get("diagnostics")).get(0));assertEquals(1,map(diagnostic.get("raw")).get("branches"));assertEquals(0,map(diagnostic.get("accepted")).get("branches"));assertEquals(64,text(diagnostic.get("inputSha256")).length());assertFalse(diagnostic.containsKey("rawResponse"));
    }
    @Test void anInputMayTestItsOwnAdoptedAnswerAndIncompleteSourceRemainsNonfatal() {
        DraftAutomaticDiscovery.Plan plan=plan(false);String raw=JsonUtils.write(draft(plan,"check",true));int[] calls={0};Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertTrue(list(result.get("stopReasons")).isEmpty());assertEquals("partial",result.get("status"));Map<String,Object> diagnostic=map(list(result.get("diagnostics")).get(0));assertEquals(1,map(diagnostic.get("accepted")).get("retainedUnresolvedEffects"));assertEquals(0,map(diagnostic.get("accepted")).get("completeScopeEffects"));
    }
    @Test void stoppingBeforeSupplementPreservesTheAlreadyReceivedDraftAndUnresolvedScope() {
        DraftAutomaticDiscovery.Plan plan=plan(true);String raw=JsonUtils.write(draft(plan,null,false));int[] calls={0};Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{if(++calls[0]==1)return raw;throw new BizException(4012,"AUTOMATION_CANCEL_REQUESTED");},plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(2,calls[0]);assertEquals("partial",result.get("status"));assertEquals(raw,result.get("rawResponse"));assertEquals(1,list(child(result).get("nodes")).size());assertEquals(1,list(child(result).get("requirements")).size());assertFalse(list(map(child(result).get("scopeCoverage")).get("missingScopeIds")).isEmpty());assertTrue(list(result.get("stopReasons")).isEmpty());assertEquals("AUTOMATION_CANCEL_REQUESTED",map(list(result.get("evidenceRounds")).get(0)).get("reason"));
    }
    @Test void anEvidenceOnlyRoundIsNotMistakenForARejectedSchema() {
        DraftAutomaticDiscovery.Plan plan=plan(true);int[] calls={0};String[] task={null},observation={null},scope={null},member={null};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{JsonNode wire=JsonUtils.parse(user);if(++calls[0]==1){JsonNode instruction=wire.path("instructions").path(0);task[0]=instruction.path("sourceTaskId").asText();observation[0]=instruction.path("sourceObservationId").asText();scope[0]=instruction.path("scopeObligationIds").path(0).asText();assertEquals("t1",task[0]);return JsonUtils.write(obj("sourceSearchRequests",Collections.singletonList(obj("sourceTaskId",task[0],"query",BODY))));}
            assertEquals(task[0],wire.path("group").path("instructions").path(0).path("sourceTaskId").asText());assertEquals(scope[0],wire.path("group").path("instructions").path(0).path("scopeObligationIds").path(0).asText());if(calls[0]==2){JsonNode hit=wire.path("supplement").path("sourceSearchResults").path(0).path("search").path("results").path(0);assertEquals("member",hit.path("kind").asText());member[0]=hit.path("id").asText();assertTrue(member[0].matches("m[0-9]+"));assertTrue(hit.path("previewOnly").asBoolean());return JsonUtils.write(obj("sourceContextRequests",Collections.singletonList(obj("sourceTaskId",task[0],"kind","member","id",member[0]))));}
            JsonNode context=wire.path("supplement").path("currentSourceContext").path(0).path("context").path("items").path(0);assertEquals(member[0],context.path("id").asText());assertEquals(BODY,context.path("text").asText());Map<String,Object> proposed=draft(plan,"keep",true),node=map(list(proposed.get("nodes")).get(0)),requirement=map(list(proposed.get("requirements")).get(0));node.put("sourceTaskId",task[0]);node.put("sourceObservationId",observation[0]);map(list(node.get("effects")).get(0)).put("scopeId",scope[0]);requirement.put("sourceTaskId",task[0]);requirement.put("sourceObservationId",observation[0]);map(list(proposed.get("taskOutcomes")).get(0)).put("sourceTaskId",task[0]);return JsonUtils.write(proposed);},plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(3,calls[0]);assertTrue(list(result.get("stopReasons")).isEmpty());assertEquals("completed",result.get("status"));assertEquals(1,list(child(result).get("nodes")).size());JsonNode secondSupplement=JsonUtils.mapper().valueToTree(map(list(result.get("evidenceRounds")).get(1)).get("supplement"));assertEquals("body",secondSupplement.path("currentSourceContext").path(0).path("context").path("items").path(0).path("id").asText());JsonNode initialRaw=JsonUtils.parse(JsonUtils.extractJson(text(result.get("rawResponse"))));assertEquals("t1",initialRaw.path("sourceSearchRequests").path(0).path("sourceTaskId").asText());
    }
    @Test void aChangedMeaningWithTheSameLocalIdStopsAndPreservesTheEarlierDraft() {
        DraftAutomaticDiscovery.Plan plan=plan(true);String task=text(plan.tasks().get(0).get("taskId"));Map<String,Object> original=draft(plan,"keep",true);original.put("sourceContextRequests",Collections.singletonList(obj("sourceTaskId",task,"kind","scope","id","scope-a")));String first=JsonUtils.write(original);Map<String,Object> changed=JsonUtils.readMap(first);map(list(changed.get("nodes")).get(0)).put("nameEn","A different independent meaning");String second=JsonUtils.write(changed);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->++calls[0]==1?first:second,plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(2,calls[0]);assertTrue(list(result.get("stopReasons")).contains("source_context_candidate_conflict"));assertEquals(first,result.get("rawResponse"));assertEquals("Issue format",map(list(child(result).get("nodes")).get(0)).get("nameEn"));
    }
    @Test void anInvalidToolOwnerStopsBeforeAnySupplementCallAndKeepsValidCandidates() {
        DraftAutomaticDiscovery.Plan plan=plan(true);Map<String,Object> proposed=draft(plan,"keep",true);proposed.put("sourceContextRequests",Collections.singletonList(obj("sourceTaskId","not-a-supplied-task","kind","scope","id","scope-a")));String raw=JsonUtils.write(proposed);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertTrue(list(result.get("stopReasons")).contains("group_source_request_task_required"));assertEquals(1,list(child(result).get("nodes")).size());assertEquals(raw,result.get("rawResponse"));
    }
    @Test void ownershipAndNamespaceDiagnosticsKeepOnlyThreeExamplesAndNoText() {
        Map<String,Object> receipt=new LinkedHashMap<>(),payload=obj("instructions",Arrays.asList(obj("sourceTaskId","t1","evidenceRefs",Collections.singletonList("s|scope|a")),obj("sourceTaskId","t2","evidenceRefs",Collections.emptyList())),"sharedEvidence",Collections.singletonList(obj("ref","s|scope|a","kind","scope","id","a","sourceId","s","text","Source words must stay outside diagnostics")));
        DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(receipt,"prompt","user",payload,"",0);List<Object> nodes=new ArrayList<>();for(int i=0;i<5;i++)nodes.add(obj("id","n"+i,"sourceTaskId","t2","effects",Collections.singletonList(obj("scopeId","a","action","check"))));DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(obj("nodes",nodes,"requirements",Collections.emptyList(),"taskOutcomes",Collections.emptyList())));
        Map<String,Object> reason=map(map(request.summary.get("reasons")).get("effect_scope_not_supplied_to_owner"));assertEquals(5,reason.get("count"));assertEquals(3,list(reason.get("examples")).size());assertEquals(2,map(map(request.summary.get("allowedIds")).get("task")).get("count"));assertFalse(JsonUtils.write(receipt).contains("Source words"));
    }
    @Test void aRequestedScopeKeepsItsAuthorityAcrossLaterRoundsButNotForOtherOwners() {
        Map<String,Object> receipt=new LinkedHashMap<>(),payload=obj("instructions",Arrays.asList(obj("sourceTaskId","t1","evidenceRefs",Collections.emptyList()),obj("sourceTaskId","t2","evidenceRefs",Collections.emptyList())),"sharedEvidence",Collections.emptyList());DraftDiscoveryProbe.Request first=DraftDiscoveryProbe.begin(receipt,"p","u",payload,"",0);
        Map<String,Object> context=obj("group",payload,"supplement",obj("currentSourceContext",Collections.singletonList(obj("sourceTaskId","t1","context",obj("items",Collections.singletonList(obj("kind","scope","id","later")))))));DraftDiscoveryProbe.Request second=DraftDiscoveryProbe.begin(receipt,"p","u",context,"",1,first),third=DraftDiscoveryProbe.begin(receipt,"p","u",obj("group",payload),"",2,second);
        JsonNode valid=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","n","sourceTaskId","t1","effects",Collections.singletonList(obj("scopeId","later","action","check")))),"requirements",Collections.emptyList(),"taskOutcomes",Collections.emptyList()));DraftDiscoveryProbe.response(third,valid);assertTrue(list(receipt.get("stopReasons")).isEmpty());((ObjectNode)valid.path("nodes").path(0)).put("sourceTaskId","t2");DraftDiscoveryProbe.response(third,valid);assertEquals(Collections.singletonList("effect_scope_not_supplied_to_owner"),receipt.get("stopReasons"));
    }

    @Test void exactCurrentBusinessReferenceCannotBeClaimedIdentifiedAsOnlyScopeReviews() {
        Map<String,Object> response=verificationDraft();DraftDiscoveryProbe.Request request=businessProbe(businessPayload(),response);
        assertTrue(list(request.receipt.get("stopReasons")).contains(DraftDiscoveryProbe.BUSINESS_REFERENCE_COLLAPSE));
        Map<String,Object> failure=map(list(request.summary.get("businessReferenceFailures")).get(0));
        assertEquals("t1",failure.get("taskId"));assertEquals(Arrays.asList("electronicTendering","otherTenderingArrangement","twoEnvelopeTendering"),failure.get("expectedKeys"));assertEquals(Arrays.asList("t1","t2"),failure.get("taskIds"));assertFalse(text(failure.get("why")).isEmpty());
        assertFalse(JsonUtils.write(request.receipt).contains("Original source words"));assertTrue(list(response.get("nodes")).isEmpty());
        request.receipt.put("status","completed");DraftDiscoveryProbe.finish(request.receipt,false);assertEquals("completed",request.receipt.get("status"));assertFalse(DraftDiscoveryProbe.stopped(request.receipt,false));DraftDiscoveryProbe.finish(request.receipt,true);assertEquals("partial",request.receipt.get("status"));
    }
    @Test void anUnrelatedBusinessNodeDoesNotHideTheLocalBusinessDefinitionCollapse() {
        Map<String,Object> response=verificationDraft();DraftBusinessRules.list(response.get("nodes")).add(rawBusinessNode("other","t3","input"));map(list(response.get("requirements")).get(2)).put("nodeIds",Collections.singletonList("other"));
        assertTrue(list(businessProbe(businessPayload(),response).receipt.get("stopReasons")).contains(DraftDiscoveryProbe.BUSINESS_REFERENCE_COLLAPSE));
    }
    @Test void aBusinessInputOwnedByTheSameScopeMarkerPreventsAFalseStop() {
        Map<String,Object> response=verificationDraft();DraftBusinessRules.list(response.get("nodes")).add(rawBusinessNode("format","t2","input"));
        assertTrue(list(businessProbe(businessPayload(),response).receipt.get("stopReasons")).isEmpty());
    }
    @Test void aSameScopeIdFromAnotherSourceDoesNotEnterTheLocalCohort() {
        Map<String,Object> payload=businessPayload(),response=verificationDraft();map(list(payload.get("instructions")).get(1)).put("sourceId","another-source");DraftBusinessRules.list(response.get("nodes")).add(rawBusinessNode("other","t2","input"));
        DraftDiscoveryProbe.Request request=businessProbe(payload,response);assertTrue(list(request.receipt.get("stopReasons")).contains(DraftDiscoveryProbe.BUSINESS_REFERENCE_COLLAPSE));assertEquals(Collections.singletonList("t1"),map(list(request.summary.get("businessReferenceFailures")).get(0)).get("taskIds"));
    }
    @Test void requestedEvidenceAndUnresolvedCohortOutcomesDeferTheBusinessProbe() {
        for(String field:Arrays.asList("sourceContextRequests","sourceSearchRequests","baselineContextRequests","baselineSearchRequests")){
            Map<String,Object> response=verificationDraft();response.put(field,Collections.singletonList("more-evidence"));assertTrue(list(businessProbe(businessPayload(),response).receipt.get("stopReasons")).isEmpty(),field);
        }
        for(String status:Arrays.asList("unresolved","no_requirement")){
            Map<String,Object> response=verificationDraft();map(list(response.get("taskOutcomes")).get(1)).put("status",status);assertTrue(list(businessProbe(businessPayload(),response).receipt.get("stopReasons")).isEmpty(),status);
        }
        Map<String,Object> missing=verificationDraft();list(missing.get("requirements")).remove(1);assertTrue(list(businessProbe(businessPayload(),missing).receipt.get("stopReasons")).isEmpty());
        Map<String,Object> repeated=verificationDraft();DraftBusinessRules.list(repeated.get("taskOutcomes")).add(obj("sourceTaskId","t2","status","identified"));assertTrue(list(businessProbe(businessPayload(),repeated).receipt.get("stopReasons")).isEmpty());
    }
    @Test void relevantExistingRootsAreConservativelyExemptButUnrelatedRootsAreNot() {
        for(String collection:Arrays.asList("reviewedRootKeys","restoredDefinitionReferences")){
            Map<String,Object> related=businessPayload();related.put(collection,Collections.singletonList("reviewedRootKeys".equals(collection)?"electronicTendering":obj("baselineKey","electronicTendering")));
            DraftDiscoveryProbe.Request exempt=businessProbe(related,verificationDraft());assertTrue(list(exempt.receipt.get("stopReasons")).isEmpty());assertFalse(exempt.summary.containsKey("businessReferenceExpectations"));
            Map<String,Object> unrelated=businessPayload();unrelated.put(collection,Collections.singletonList("reviewedRootKeys".equals(collection)?"unrelatedRoot":obj("baselineKey","unrelatedRoot")));
            assertTrue(list(businessProbe(unrelated,verificationDraft()).receipt.get("stopReasons")).contains(DraftDiscoveryProbe.BUSINESS_REFERENCE_COLLAPSE));
        }
    }
    @Test void incompleteOrMerelyCandidateReferencesDoNotCreateABusinessExpectation() {
        for(String gap:Arrays.asList("ambiguous","lexical","unknown-scope","unknown-condition","missing-condition-list","missing-definitions","missing-actions","missing-scope","marker-only")){
            Map<String,Object> payload=businessPayload(),instruction=map(list(payload.get("instructions")).get(0)),alignment=map(instruction.get("currentTemplateAlignment"));
            if("ambiguous".equals(gap))alignment.put("definitionStatus","ambiguous_instruction_reference");
            if("lexical".equals(gap))alignment.put("referenceKind","fixed_definition_candidate");
            if("unknown-scope".equals(gap))alignment.put("scopeStatus","requires_review");
            if("unknown-condition".equals(gap))instruction.put("unresolvedConditionIds",Collections.singletonList("unknown"));
            if("missing-condition-list".equals(gap))instruction.remove("unresolvedConditionIds");
            if("missing-definitions".equals(gap))map(payload.get("fixedContext")).put("fields",Collections.emptyList());
            if("missing-actions".equals(gap))instruction.put("fixedActionReferences",Collections.emptyList());
            if("missing-scope".equals(gap))instruction.put("scopeObligationIds",Collections.singletonList("not-supplied"));
            if("marker-only".equals(gap))instruction.put("kind","marker");
            DraftDiscoveryProbe.Request request=businessProbe(payload,verificationDraft());assertTrue(list(request.receipt.get("stopReasons")).isEmpty(),gap);assertFalse(request.summary.containsKey("businessReferenceExpectations"),gap);
        }
    }
    @Test void aCohortMayReferenceAValueBearingRootOwnedElsewhere() {
        for(String role:Arrays.asList("input","decision","content","derived","fixed","system")){
            Map<String,Object> response=verificationDraft();DraftBusinessRules.list(response.get("nodes")).add(rawBusinessNode("shared","t3",role));map(list(response.get("requirements")).get(0)).put("nodeIds",Collections.singletonList("shared"));
            assertTrue(list(businessProbe(businessPayload(),response).receipt.get("stopReasons")).isEmpty(),role);
        }
        Map<String,Object> verification=verificationDraft();DraftBusinessRules.list(verification.get("nodes")).add(rawBusinessNode("check","t3","verification"));map(list(verification.get("requirements")).get(0)).put("nodeIds",Collections.singletonList("check"));assertTrue(list(businessProbe(businessPayload(),verification).receipt.get("stopReasons")).contains(DraftDiscoveryProbe.BUSINESS_REFERENCE_COLLAPSE));
    }
    @Test void aPriorRoundAcceptedBusinessInputPreventsAFalseStopOnVerificationOnlyDelta() {
        Map<String,Object> receipt=new LinkedHashMap<>(),payload=businessPayload(),response=verificationDraft();DraftDiscoveryProbe.Request previous=DraftDiscoveryProbe.begin(receipt,"p","u",payload,"",0),request=DraftDiscoveryProbe.begin(receipt,"p","u",obj("group",payload),"",1,previous);
        DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(response));Map<String,Object> retained=acceptedDraft(response);DraftBusinessRules.list(retained.get("taskReceipts")).add(obj("taskId","t2","nodes",Collections.singletonList(obj("id","prior","role",obj("kind","input"))),"requirements",Collections.emptyList()));DraftDiscoveryProbe.accepted(request,retained);
        assertTrue(list(receipt.get("stopReasons")).isEmpty());
    }
    @Test void acceptedSharedReferencesExcludeSourceSeedsAndRespectReceiptOwnership() {
        for(boolean seed:Arrays.asList(false,true)){
            Map<String,Object> receipt=new LinkedHashMap<>(),response=verificationDraft();DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(receipt,"p","u",businessPayload(),"",0);DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(response));Map<String,Object> retained=acceptedDraft(response),child=map(list(retained.get("taskReceipts")).get(0));map(list(child.get("requirements")).get(0)).put("nodeIds",Collections.singletonList("prior"));DraftBusinessRules.list(retained.get("taskReceipts")).add(obj("taskId","t3","nodes",Collections.singletonList(obj("id","prior","role",obj("kind","input"),"definitionKind",seed?"source_seed":"discovered")),"requirements",Collections.emptyList()));DraftDiscoveryProbe.accepted(request,retained);
            assertEquals(seed,list(receipt.get("stopReasons")).contains(DraftDiscoveryProbe.BUSINESS_REFERENCE_COLLAPSE));
        }
    }

    @Test void knownRootReferencesAndRuleOnlyPatchesDoNotLookLikeLostBusinessInputs(){
        Map<String,Object> payload=businessPayload();payload.put("knownRoots",Collections.singletonList(obj("id","k1")));
        Map<String,Object> refs=verificationDraft();map(list(refs.get("requirements")).get(0)).put("nodeIds",Collections.singletonList("k1"));assertTrue(list(businessProbe(payload,refs).receipt.get("stopReasons")).isEmpty());
        Map<String,Object> patch=verificationDraft();DraftBusinessRules.list(patch.get("nodes")).add(obj("id","k1","sourceTaskId","t1"));assertTrue(list(businessProbe(payload,patch).receipt.get("stopReasons")).isEmpty());
        assertTrue(list(businessProbe(payload,verificationDraft()).receipt.get("stopReasons")).contains(DraftDiscoveryProbe.BUSINESS_REFERENCE_COLLAPSE));
    }
    @Test void aUniqueCompleteSuppliedDefinitionStopsADuplicateInputWithoutChangingTheRawDraft(){
        Map<String,Object> payload=definitionPayload(),draft=probeDraft(Collections.singletonList(definitionInput()));JsonNode raw=JsonUtils.mapper().valueToTree(draft),before=raw.deepCopy();
        DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(new LinkedHashMap<>(),"p","u",payload,"",0);DraftDiscoveryProbe.response(request,raw);
        assertEquals(Collections.singletonList(DraftDiscoveryProbe.SUPPLIED_DEFINITION_DUPLICATED),request.receipt.get("stopReasons"));assertEquals(before,raw);assertEquals(1,map(request.summary.get("raw")).get("nodes"));
        Map<String,Object> reason=map(map(request.summary.get("reasons")).get(DraftDiscoveryProbe.SUPPLIED_DEFINITION_DUPLICATED));assertEquals("k1",map(list(reason.get("examples")).get(0)).get("knownRootId"));assertFalse(JsonUtils.write(request.summary).contains("BQ issue format"));
        Map<String,Object> follow=new LinkedHashMap<>(payload);follow.remove("knownRoots");DraftDiscoveryProbe.Request next=DraftDiscoveryProbe.begin(new LinkedHashMap<>(),"p","u",obj("group",follow),"",1,request);DraftDiscoveryProbe.response(next,raw);assertTrue(list(next.receipt.get("stopReasons")).contains(DraftDiscoveryProbe.SUPPLIED_DEFINITION_DUPLICATED));
    }
    @Test void uncertainDifferentOrNonInputDefinitionsAreNotAutomaticallyDeclaredDuplicates(){
        for(String difference:Arrays.asList("question","translated-question","type","option-value","option-label","scope","unit","answer-domain","parent","missing-question","missing-options","ambiguous","known-patch","derived","verification")){
            Map<String,Object> payload=definitionPayload(),node=definitionInput();
            if("question".equals(difference))node.put("questionEn","BQ submission format");
            if("translated-question".equals(difference))node.put("questionZh","BQ提交格式");
            if("type".equals(difference))node.put("type","multiselect");
            if("option-value".equals(difference))map(list(node.get("options")).get(0)).put("value","Electronic");
            if("option-label".equals(difference))map(list(node.get("options")).get(0)).put("labelEn","Submission using L10Pro");
            if("scope".equals(difference))node.put("definitionScope",obj("kind","section","id","SCT5"));
            if("unit".equals(difference))node.put("unit","copies");
            if("answer-domain".equals(difference))node.put("answerDomain",Collections.singletonList("Alternative A only"));
            if("parent".equals(difference))node.put("parentId","other-input");
            if("missing-question".equals(difference))node.remove("questionEn");
            if("missing-options".equals(difference))node.remove("options");
            if("ambiguous".equals(difference)){Map<String,Object> second=knownDefinition();second.put("id","k2");payload.put("knownRoots",Arrays.asList(knownDefinition(),second));}
            if("known-patch".equals(difference))node=obj("id","k1","sourceTaskId","t1","branches",Collections.emptyList(),"effects",Collections.emptyList());
            if(Arrays.asList("derived","verification").contains(difference))node.put("role",difference);
            DraftDiscoveryProbe.Request request=rawProbe(payload,probeDraft(Collections.singletonList(node)));assertTrue(list(request.receipt.get("stopReasons")).isEmpty(),difference);
        }
    }
    @Test void anEffectMayOnlyReferenceABranchOnItsOwnRawNode(){
        Map<String,Object> own=rawBusinessNode("own","t1","derived"),other=rawBusinessNode("other","t1","derived");other.put("branches",Collections.singletonList(obj("id","elsewhere")));own.put("effects",Collections.singletonList(obj("scopeId","s1","branchId","elsewhere","action","delete")));
        Map<String,Object> draft=probeDraft(Arrays.asList(own,other));String before=JsonUtils.write(draft);DraftDiscoveryProbe.Request request=rawProbe(definitionPayload(),draft);assertEquals(Collections.singletonList(DraftDiscoveryProbe.DANGLING_BRANCH),request.receipt.get("stopReasons"));assertEquals(before,JsonUtils.write(draft));
        own.put("branches",Collections.singletonList(obj("id","elsewhere")));assertTrue(list(rawProbe(definitionPayload(),draft).receipt.get("stopReasons")).isEmpty());
        own.put("branches",Collections.emptyList());map(list(own.get("effects")).get(0)).put("branchId","");assertTrue(list(rawProbe(definitionPayload(),draft).receipt.get("stopReasons")).isEmpty());
    }
    @Test void scopeRuleCompilationHasSeparateCountsAndCannotHideLostModelParts(){
        for(boolean modelPartsLost:Arrays.asList(false,true)){
            Map<String,Object> draft=probeDraft(Collections.emptyList());draft.put("scopeRules",Collections.singletonList(obj("id","rule","sourceTaskId","t1","scopeId","s1","action","check")));
            if(modelPartsLost){Map<String,Object> model=rawBusinessNode("model","t1","derived");model.put("branches",Collections.singletonList(obj("id","yes")));model.put("effects",Collections.singletonList(obj("scopeId","s1","branchId","yes","action","keep")));draft.put("nodes",Collections.singletonList(model));draft.put("requirements",Collections.singletonList(obj("id","model-requirement","sourceTaskId","t1","kind","amendment","nodeIds",Collections.singletonList("model"))));}
            DraftDiscoveryProbe.Request request=rawProbe(definitionPayload(),draft);DraftDiscoveryProbe.accepted(request,compiledReceipt());Map<String,Object> raw=map(request.summary.get("raw")),accepted=map(request.summary.get("accepted"));
            assertEquals(1,raw.get("scopeRules"));assertEquals(modelPartsLost?1:0,raw.get("nodes"));for(String part:Arrays.asList("Nodes","Requirements","Branches","Effects")){assertEquals(1,accepted.get("compiled"+part));if(modelPartsLost)assertEquals(1,request.summary.get("removed"+part));else assertFalse(request.summary.containsKey("removed"+part));}
            assertEquals(modelPartsLost,list(request.receipt.get("stopReasons")).contains("response_parts_not_retained"));
        }
    }
    @Test void scopeRuleOwnershipAndCompilerFailuresStopWithoutInterpretingTheCondition(){
        for(String invalid:Arrays.asList("owner","scope","array")){
            Map<String,Object> rule=obj("id","rule","sourceTaskId","owner".equals(invalid)?"t2":"t1","scopeId","scope".equals(invalid)?"other":"s1","action","check"),draft=probeDraft(Collections.emptyList());draft.put("scopeRules","array".equals(invalid)?rule:Collections.singletonList(rule));String before=JsonUtils.write(draft);
            DraftDiscoveryProbe.Request request=rawProbe(definitionPayload(),draft);assertFalse(list(request.receipt.get("stopReasons")).isEmpty(),invalid);assertEquals(before,JsonUtils.write(draft));
        }
        DraftDiscoveryProbe.Request request=rawProbe(definitionPayload(),probeDraft(Collections.emptyList()));DraftDiscoveryProbe.accepted(request,obj("issues",Arrays.asList(obj("reason","SCOPE_RULE_NONEMPTY_CONDITION_REQUIRED"),obj("reason","scope_rule_owner_invalid"))));assertEquals(Arrays.asList("SCOPE_RULE_NONEMPTY_CONDITION_REQUIRED","scope_rule_owner_invalid"),request.receipt.get("stopReasons"));
        Map<String,Object> mixed=obj("scopeRules",Collections.emptyList(),"sourceContextRequests",Collections.singletonList(obj("sourceTaskId","t1")));assertFalse(DraftDiscoveryProbe.evidenceOnly(JsonUtils.mapper().valueToTree(mixed)));assertTrue(list(rawProbe(definitionPayload(),mixed).receipt.get("stopReasons")).contains("nodes_array_required"));
    }
    private static DraftDiscoveryProbe.Request rawProbe(Map<String,Object> payload,Map<String,Object> draft){DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(new LinkedHashMap<>(),"p","u",payload,"",0);DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(draft));return request;}
    private static Map<String,Object> knownDefinition(){return obj("id","k1","questionEn","BQ issue format","questionZh","BQ发放格式","type",obj("kind","choice"),"options",Arrays.asList(obj("value","L10Pro","labelEn","L10Pro electronic issue"),obj("value","Hardcopy","labelEn","Hardcopy issue")));}
    private static Map<String,Object> definitionInput(){Map<String,Object> node=knownDefinition();node.putAll(rawBusinessNode("n1","t1","input"));node.put("type","choice");return node;}
    private static Map<String,Object> definitionPayload(){return obj("knownRoots",Collections.singletonList(knownDefinition()),"instructions",Collections.singletonList(obj("sourceTaskId","t1","evidenceRefs",Collections.singletonList("scope-ref"))),"sharedEvidence",Collections.singletonList(obj("ref","scope-ref","kind","scope","id","s1")));}
    private static Map<String,Object> probeDraft(List<Map<String,Object>> nodes){return obj("nodes",nodes,"requirements",Collections.emptyList(),"taskOutcomes",Collections.emptyList());}
    private static Map<String,Object> compiledReceipt(){return obj("taskId","t1","nodes",Collections.singletonList(obj("id","compiled","role",obj("kind","derived"),"origin","scope_rules_compilation","compiledScopeRuleId","rule","correction",obj("branches",Collections.singletonList(obj("id","yes"))),"editTargets",Collections.singletonList(obj("scopeId","s1","action","keep")))),"requirements",Collections.singletonList(obj("id","compiled-requirement","origin","scope_rules_compilation","compiledScopeRuleId","rule","kind","amendment","nodeIds",Collections.singletonList("compiled"))));}
    private static DraftDiscoveryProbe.Request businessProbe(Map<String,Object> payload,Map<String,Object> response) {
        DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(new LinkedHashMap<>(),"p","u",payload,"",0);DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(response));DraftDiscoveryProbe.accepted(request,acceptedDraft(response));return request;
    }
    private static Map<String,Object> rawBusinessNode(String id,String task,String role){return obj("id",id,"sourceTaskId",task,"role",role,"branches",Collections.emptyList(),"effects",Collections.emptyList());}
    private static Map<String,Object> businessPayload() {
        List<String> roots=Arrays.asList("twoEnvelopeTendering","electronicTendering","otherTenderingArrangement");Map<String,Object> instruction=obj("sourceTaskId","t1","sourceId","source","kind","guidance","text","Original source words must not be copied to diagnostics","evidenceRefs",Collections.singletonList("source|scope|s1"),"scopeObligationIds",Collections.singletonList("s1"),"unresolvedConditionIds",Collections.emptyList(),"fixedActionReferences",Collections.singletonList(obj("inputKeys",roots)),"currentTemplateAlignment",obj("definitionStatus","exact_instruction_reference","referenceKind","current_instruction_reference","scopeStatus","current_observation_complete","fixedRootKeys",roots));
        return obj("instructions",Arrays.asList(instruction,obj("sourceTaskId","t2","sourceId","source","kind","marker","evidenceRefs",Collections.singletonList("source|scope|s1"),"scopeObligationIds",Collections.singletonList("s1")),obj("sourceTaskId","t3","sourceId","source","kind","marker","evidenceRefs",Collections.singletonList("source|scope|s2"),"scopeObligationIds",Collections.singletonList("s2"))),"sharedEvidence",Arrays.asList(obj("ref","source|scope|s1","kind","scope","id","s1","sourceId","source"),obj("ref","source|scope|s2","kind","scope","id","s2","sourceId","source")),"fixedContext",obj("fields",Arrays.asList(obj("key","twoEnvelopeTendering","parentKey","","kind","boolean"),obj("key","electronicTendering","parentKey","","kind","choice","options",Arrays.asList(obj("value","L10Pro"),obj("value","Hardcopy"))),obj("key","otherTenderingArrangement","parentKey","","kind","text"))),"reviewedRootKeys",Collections.emptyList(),"restoredDefinitionReferences",Collections.emptyList());
    }
    private static Map<String,Object> verificationDraft() {
        List<Object> requirements=new ArrayList<>(),outcomes=new ArrayList<>();for(String task:Arrays.asList("t1","t2","t3")){requirements.add(obj("id","r-"+task,"sourceTaskId",task,"kind","verification","nodeIds",Collections.emptyList()));outcomes.add(obj("sourceTaskId",task,"status","identified"));}return obj("nodes",new ArrayList<>(),"requirements",requirements,"taskOutcomes",outcomes);
    }
    private static Map<String,Object> acceptedDraft(Map<String,Object> response) {
        Map<String,Map<String,Object>> children=new LinkedHashMap<>();for(Object raw:list(response.get("requirements"))){Map<String,Object> requirement=new LinkedHashMap<>(map(raw));String task=text(requirement.remove("sourceTaskId"));Map<String,Object> child=children.computeIfAbsent(task,key->obj("taskId",key,"requirements",new ArrayList<>(),"nodes",new ArrayList<>()));DraftBusinessRules.list(child.get("requirements")).add(requirement);}
        for(Object raw:list(response.get("nodes"))){Map<String,Object> node=new LinkedHashMap<>(map(raw));String task=text(node.remove("sourceTaskId"));node.put("role",obj("kind",node.get("role")));Map<String,Object> child=children.computeIfAbsent(task,key->obj("taskId",key,"requirements",new ArrayList<>(),"nodes",new ArrayList<>()));DraftBusinessRules.list(child.get("nodes")).add(node);}return obj("taskReceipts",new ArrayList<>(children.values()));
    }

    private static Map<String,Object> child(Map<String,Object> result){return map(list(result.get("taskReceipts")).get(0));}
    private static Map<String,Object> draft(DraftAutomaticDiscovery.Plan plan,String action,boolean branch) {
        String task=text(plan.tasks().get(0).get("taskId"));Map<String,Object> node=obj("id","format","sourceTaskId",task,"type","boolean","role","input","nameEn","Issue format","sourceQuote",NOTE,"sourceObservationId","note-a","options",Collections.emptyList(),"branches",branch?Collections.singletonList(obj("id","yes","mode","all","clauses",Collections.singletonList(obj("inputId","format","operator","yes")))):Collections.emptyList(),"effects",action==null?Collections.emptyList():Collections.singletonList(obj("scopeId","scope-a","action",action,"branchId",branch?"yes":"")));
        return obj("nodes",Collections.singletonList(node),"requirements",Collections.singletonList(obj("id","r","sourceTaskId",task,"sourceQuote",NOTE,"sourceObservationId","note-a","kind","input","nodeIds",Collections.singletonList("format"))),"taskOutcomes",Collections.singletonList(obj("sourceTaskId",task,"status",action==null?"unresolved":"identified","reason","Review the bound source scope")));
    }
    private static DraftAutomaticDiscovery.Plan plan(boolean complete) {
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("fileKey","SCT").put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx").put("editionId","synthetic").put("analysisRevision","a1").put("analysisInputSha256",SHA).put("analysisSha256",SHA);for(String collection:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(collection);
        source.withArray("guidanceNotes").addObject().put("id","note-a").put("text",NOTE).put("captureComplete",true);ObjectNode member=source.withArray("sourceMembers").addObject().put("id","body").put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/body");member.putObject("textObservation").put("text",BODY);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-body").put("text",BODY).put("sourceSha256",SHA).put("paragraphSha256",SHA);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());ObjectNode scope=source.withArray("groups").addObject().put("id","scope-a").put("scopeComplete",complete);scope.putArray("guidanceNoteIds").add("note-a");scope.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",BODY.length());return DraftAutomaticDiscovery.plan(Collections.singletonList(source));
    }
}
