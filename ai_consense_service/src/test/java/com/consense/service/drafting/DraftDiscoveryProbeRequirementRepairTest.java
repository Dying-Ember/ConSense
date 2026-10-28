package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftDiscoveryProbeRequirementRepairTest {
    private static final String REJECTED="unsupported_requirement_quote_kind_or_node_reference",QUOTE="requirement_quote_not_in_authorized_evidence",LOST="response_parts_not_retained";

    @Test void onlyTheNamedActualQuoteRejectionIsDeferredWithoutChangingRawOrAcceptedEvidence(){
        Map<String,Object> raw=raw(1),accepted=accepted(child("t1",quote("r1")));String before=JsonUtils.write(accepted),rawBefore=JsonUtils.write(raw);
        DraftDiscoveryProbe.Request request=request(raw);DraftDiscoveryProbe.accepted(request,accepted,new HashSet<>(Arrays.asList("r1","absent")));
        assertTrue(list(request.receipt.get("stopReasons")).isEmpty());assertEquals(before,JsonUtils.write(accepted));assertEquals(rawBefore,JsonUtils.write(raw));
        assertEquals(1,request.summary.get("removedRequirements"));assertEquals(1,map(map(request.summary.get("reasons")).get(REJECTED)).get("count"));assertEquals(1,map(map(request.summary.get("reasons")).get(LOST)).get("count"));
        List<?> deferred=list(request.summary.get("deferredRequirementQuoteRepairs"));assertEquals(1,deferred.size());assertEquals("t1",map(deferred.get(0)).get("sourceTaskId"));assertEquals("r1",map(deferred.get(0)).get("requirementId"));assertEquals(QUOTE,map(map(deferred.get(0)).get("quoteDiagnostic")).get("reason"));
    }

    @Test void theLegacyCallerAndFollowingRoundStillStopOnAnUnrepairedQuote(){
        Map<String,Object> accepted=accepted(child("t1",quote("r1")));DraftDiscoveryProbe.Request legacy=request(raw(1));DraftDiscoveryProbe.accepted(legacy,accepted);assertTrue(list(legacy.receipt.get("stopReasons")).containsAll(Arrays.asList(REJECTED,LOST)));
        DraftDiscoveryProbe.Request first=request(raw(1));DraftDiscoveryProbe.accepted(first,accepted,Collections.singleton("r1"));
        DraftDiscoveryProbe.Request next=DraftDiscoveryProbe.begin(first.receipt,"p","u",payload(),"",1,first);DraftDiscoveryProbe.response(next,JsonUtils.mapper().valueToTree(raw(1)));DraftDiscoveryProbe.accepted(next,accepted);
        assertTrue(list(next.receipt.get("stopReasons")).containsAll(Arrays.asList(REJECTED,LOST)));assertFalse(next.summary.containsKey("deferredRequirementQuoteRepairs"));assertTrue(first.summary.containsKey("deferredRequirementQuoteRepairs"));
    }

    @Test void absentAmbiguousOrUnverifiedQuoteDiagnosticsAndUnlistedIdsRemainFatal(){
        for(String diagnostic:Arrays.asList("","requirement_quote_ambiguous_in_authorized_evidence","requirement_quote_current_ranges_unverified",QUOTE)){
            Map<String,Object> issue=quote("r1");issue.put("quoteDiagnostic",obj("reason",diagnostic));DraftDiscoveryProbe.Request request=request(raw(1));
            DraftDiscoveryProbe.accepted(request,accepted(child("t1",issue)),Collections.singleton(QUOTE.equals(diagnostic)?"other":"r1"));
            assertTrue(list(request.receipt.get("stopReasons")).contains(REJECTED),diagnostic);assertFalse(request.summary.containsKey("deferredRequirementQuoteRepairs"),diagnostic);
        }
    }

    @Test void duplicateRequirementIdsAndDuplicateOwnersCannotBorrowAnException(){
        for(boolean sameOwner:Arrays.asList(false,true)){
            DraftDiscoveryProbe.Request request=request(raw(2));DraftDiscoveryProbe.accepted(request,accepted(child("t1",quote("r1")),child(sameOwner?"t1":"t2",quote("r1"))),Collections.singleton("r1"));
            assertTrue(list(request.receipt.get("stopReasons")).contains(REJECTED));assertFalse(request.summary.containsKey("deferredRequirementQuoteRepairs"));
        }
        Map<String,Object> duplicateOwner=accepted(child("t1",quote("r1")),child("t1"));DraftDiscoveryProbe.Request request=request(raw(1));DraftDiscoveryProbe.accepted(request,duplicateOwner,Collections.singleton("r1"));assertTrue(list(request.receipt.get("stopReasons")).contains(REJECTED));assertFalse(request.summary.containsKey("deferredRequirementQuoteRepairs"));
    }

    @Test void missingUnknownMismatchedOrNestedSourceOwnersRemainFatal(){
        for(String mismatch:Arrays.asList("missing","unknown","owningTaskId","sourceTaskId","taskId","nested","top")){
            Map<String,Object> issue=quote("r1"),child=child("missing".equals(mismatch)?"":"unknown".equals(mismatch)?"t9":"t1",issue),accepted=accepted(child);
            if("owningTaskId".equals(mismatch))child.put(mismatch,"t2");if(Arrays.asList("sourceTaskId","taskId").contains(mismatch))issue.put(mismatch,"t2");
            if("nested".equals(mismatch))accepted=accepted(obj("taskId","group","taskReceipts",Collections.singletonList(child)));
            if("top".equals(mismatch))accepted=obj("issues",Collections.singletonList(issue),"taskReceipts",Collections.emptyList());
            DraftDiscoveryProbe.Request request=request(raw(1));DraftDiscoveryProbe.accepted(request,accepted,Collections.singleton("r1"));assertTrue(list(request.receipt.get("stopReasons")).contains(REJECTED),mismatch);assertFalse(request.summary.containsKey("deferredRequirementQuoteRepairs"),mismatch);
        }
    }

    @Test void aRepeatedIdInGroupIssuesOrNodeRejectionsPreventsDeferral(){
        for(boolean node:Arrays.asList(false,true)){
            Map<String,Object> child=child("t1",quote("r1")),accepted=accepted(child);
            if(node)child.put("nodes",Collections.singletonList(obj("discoveryRejectedParts",Collections.singletonList(quote("r1")))));else accepted.put("issues",Collections.singletonList(quote("r1")));
            DraftDiscoveryProbe.Request request=request(raw(1));DraftDiscoveryProbe.accepted(request,accepted,Collections.singleton("r1"));assertTrue(list(request.receipt.get("stopReasons")).contains(REJECTED));assertFalse(request.summary.containsKey("deferredRequirementQuoteRepairs"));
        }
    }

    @Test void unrelatedRequirementLossAndEveryOtherLostPartStayFatalEvenWithOneDeferredQuote(){
        DraftDiscoveryProbe.Request extra=request(raw(2));DraftDiscoveryProbe.accepted(extra,accepted(child("t1",quote("r1"))),Collections.singleton("r1"));assertTrue(list(extra.receipt.get("stopReasons")).contains(LOST));assertEquals(2,extra.summary.get("removedRequirements"));
        for(String part:Arrays.asList("nodes","branches","effects")){
            Map<String,Object> raw=raw(1),node=obj("id","n1","sourceTaskId","t1","role","derived","branches",Collections.emptyList(),"effects",Collections.emptyList());
            if("branches".equals(part))node.put(part,Collections.singletonList(obj("id","b1")));if("effects".equals(part))node.put(part,Collections.singletonList(obj("action","check")));raw.put("nodes",Collections.singletonList(node));
            DraftDiscoveryProbe.Request request=request(raw);DraftDiscoveryProbe.accepted(request,accepted(child("t1",quote("r1"))),Collections.singleton("r1"));assertTrue(list(request.receipt.get("stopReasons")).contains(LOST),part);assertEquals(1,request.summary.get("removed"+Character.toUpperCase(part.charAt(0))+part.substring(1)),part);
        }
    }

    @Test void compilerRequirementsCannotMaskAnAdditionalLostModelRequirement(){
        Map<String,Object> child=child("t1",quote("r1"));child.put("requirements",Collections.singletonList(obj("origin","scope_rules_compilation","compiledScopeRuleId","rule","kind","amendment","nodeIds",Collections.emptyList())));
        DraftDiscoveryProbe.Request request=request(raw(2));DraftDiscoveryProbe.accepted(request,accepted(child),Collections.singleton("r1"));assertEquals(2,request.summary.get("removedRequirements"));assertTrue(list(request.receipt.get("stopReasons")).contains(LOST));
    }

    @Test void otherOwnerKindNodeAndScopeFailuresRemainFatalBesideTheDeferredQuote(){
        for(String code:Arrays.asList("group_requirement_task_required","node_without_supported_requirement_mapping","group_identified_without_supported_requirement","SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED",REJECTED)){
            Map<String,Object> other=obj("reason",code,"requirementId","bad");DraftDiscoveryProbe.Request request=request(raw(1));DraftDiscoveryProbe.accepted(request,accepted(child("t1",quote("r1"),other)),Collections.singleton("r1"));
            assertTrue(list(request.receipt.get("stopReasons")).contains(code),code);assertEquals(1,list(request.summary.get("deferredRequirementQuoteRepairs")).size(),code);
        }
    }

    @Test void nonGroupedRequestsNeverUseTheGroupRepairException(){
        DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(new LinkedHashMap<>(),"p","u",obj("scopeCandidates",Collections.emptyList()),"t1",0);DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(raw(1)));DraftDiscoveryProbe.accepted(request,child("t1",quote("r1")),Collections.singleton("r1"));assertTrue(list(request.receipt.get("stopReasons")).contains(REJECTED));assertFalse(request.summary.containsKey("deferredRequirementQuoteRepairs"));
    }

    private static DraftDiscoveryProbe.Request request(Map<String,Object> raw){DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(new LinkedHashMap<>(),"p","u",payload(),"",0);DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(raw));return request;}
    private static Map<String,Object> payload(){return obj("instructions",Arrays.asList(obj("sourceTaskId","t1","evidenceRefs",Collections.emptyList()),obj("sourceTaskId","t2","evidenceRefs",Collections.emptyList())),"sharedEvidence",Collections.emptyList());}
    private static Map<String,Object> raw(int requirements){List<Object> values=new ArrayList<>();for(int i=1;i<=requirements;i++)values.add(obj("id","r"+i,"sourceTaskId","t1","kind","verification","nodeIds",Collections.emptyList(),"sourceQuote","Unmatched quote"));return obj("nodes",Collections.emptyList(),"requirements",values,"taskOutcomes",Collections.emptyList());}
    @SafeVarargs private static Map<String,Object> child(String owner,Map<String,Object>... issues){return obj("taskId",owner,"owningTaskId",owner,"issues",Arrays.asList(issues),"nodes",Collections.emptyList(),"requirements",Collections.emptyList());}
    @SafeVarargs private static Map<String,Object> accepted(Map<String,Object>... children){return obj("taskReceipts",Arrays.asList(children));}
    private static Map<String,Object> quote(String id){return obj("requirementId",id,"reason",REJECTED,"quoteDiagnostic",obj("reason",QUOTE,"matchingObservationCount",0));}
}
