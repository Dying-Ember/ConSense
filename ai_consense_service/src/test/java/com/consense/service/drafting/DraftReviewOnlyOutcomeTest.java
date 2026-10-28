package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Source-only boundaries for retained checks and contradictory outcomes; no live model, API or browser. */
class DraftReviewOnlyOutcomeTest {
    private static final String PENDING="group_no_requirement_with_review_only_candidates_pending",FATAL="group_no_requirement_conflicts_with_candidates";

    @Test void originalCheckNeedsNoExecutableRefinementAndRetainsItsOriginalScopeAndDiagnostic(){
        Map<String,Object> evaluated=evaluated("check"),target=target(evaluated);target.put("text","G1\nG1a");target.put("needsRebind",true);target.put("scopeComplete",false);String before=JsonUtils.write(evaluated);
        assertTrue(DraftAutomaticDiscovery.scopeRefinementRequests(evaluated).isEmpty());assertEquals(before,JsonUtils.write(evaluated));assertEquals("UNIQUE_SOURCE_BOUND_SUBRANGE_REQUIRED",target.get("refinementIssue"));
    }

    @Test void aDeleteDowngradedToCheckStillRequiresAnExactSourceBoundRefinement(){
        Map<String,Object> evaluated=evaluated("delete");String before=JsonUtils.write(evaluated);List<Object> requests=DraftAutomaticDiscovery.scopeRefinementRequests(evaluated);assertEquals(1,requests.size());Map<String,Object> request=map(requests.get(0));
        assertEquals("source_scope_refinement_not_bound",request.get("reason"));assertEquals("t1",request.get("sourceTaskId"));assertEquals(Collections.singletonList("r1"),request.get("ruleIds"));assertEquals(Collections.singletonList("s1"),request.get("scopeIds"));assertEquals(before,JsonUtils.write(evaluated));
    }

    @Test void aMissingOriginalActionCannotAcquireTheCheckExemption(){Map<String,Object> evaluated=evaluated("check");target(evaluated).remove("proposedAction");assertEquals(1,DraftAutomaticDiscovery.scopeRefinementRequests(evaluated).size());}

    @Test void build22ReferenceOnlyCandidatesStayVisibleAndPendingWithoutAProbeStop(){
        Map<String,Object> child=child();child.put("scopeCoverage",obj("status","unresolved","scopeIds",Collections.singletonList("s1"),"missingScopeIds",Collections.singletonList("s1"),"unrepresentedScopeIds",Collections.singletonList("s1")));String before=JsonUtils.write(child);
        assertEquals(PENDING,DraftAutomaticDiscovery.noRequirementCandidateReason(child));assertEquals(before,JsonUtils.write(child));
        list(child.get("issues")).add(obj("reason",DraftAutomaticDiscovery.noRequirementCandidateReason(child)));DraftDiscoveryProbe.Request request=probe(child);DraftDiscoveryProbe.accepted(request,obj("taskReceipts",Collections.singletonList(child)));
        assertTrue(list(request.receipt.get("stopReasons")).isEmpty());assertEquals(1,list(child.get("requirements")).size());assertEquals("unresolved",map(child.get("scopeCoverage")).get("status"));assertEquals(Collections.singletonList("s1"),map(child.get("scopeCoverage")).get("missingScopeIds"));assertEquals(1,map(request.summary.get("accepted")).get("requirements"));
    }

    @Test void onlyVerificationNodesWhoseOriginalTargetsAreChecksAreReviewOnly(){
        Map<String,Object> child=child(),node=node("check");child.put("nodes",Collections.singletonList(node));assertEquals(PENDING,DraftAutomaticDiscovery.noRequirementCandidateReason(child));
        for(String role:Arrays.asList("input","decision","content","derived","fixed","system")){Map<String,Object> candidate=JsonUtils.readMap(JsonUtils.write(child));map(map(list(candidate.get("nodes")).get(0)).get("role")).put("kind",role);assertEquals(FATAL,DraftAutomaticDiscovery.noRequirementCandidateReason(candidate),role);}
        map(list(node.get("editTargets")).get(0)).put("proposedAction","delete");assertEquals(FATAL,DraftAutomaticDiscovery.noRequirementCandidateReason(child),"A downgraded delete must remain strict");
    }

    @Test void businessRequirementsAndExecutableTargetsRetainTheFatalProbeBoundary(){
        for(String kind:Arrays.asList("input","choice","amendment")){Map<String,Object> child=child();map(list(child.get("requirements")).get(0)).put("kind",kind);assertFatal(child);}
        for(String action:Arrays.asList("keep","delete","unused","replace","fill","insert")){Map<String,Object> child=child();child.put("nodes",Collections.singletonList(node(action)));assertFatal(child);}
        Map<String,Object> child=child(),node=node("check");map(list(node.get("editTargets")).get(0)).put("action","delete");child.put("nodes",Collections.singletonList(node));assertFatal(child);
    }

    private static void assertFatal(Map<String,Object> child){assertEquals(FATAL,DraftAutomaticDiscovery.noRequirementCandidateReason(child));list(child.get("issues")).add(obj("reason",DraftAutomaticDiscovery.noRequirementCandidateReason(child)));DraftDiscoveryProbe.Request request=probe(child);DraftDiscoveryProbe.accepted(request,obj("taskReceipts",Collections.singletonList(child)));assertTrue(list(request.receipt.get("stopReasons")).contains(FATAL));}
    private static DraftDiscoveryProbe.Request probe(Map<String,Object> child){Map<String,Object> raw=obj("nodes",Collections.emptyList(),"requirements",Collections.singletonList(obj("id","q1","sourceTaskId","t1","kind","reference","nodeIds",Collections.emptyList())),"taskOutcomes",Collections.singletonList(obj("sourceTaskId","t1","status","no_requirement")));DraftDiscoveryProbe.Request request=DraftDiscoveryProbe.begin(new LinkedHashMap<>(),"p","u",obj("instructions",Collections.singletonList(obj("sourceTaskId","t1","evidenceRefs",Collections.emptyList())),"sharedEvidence",Collections.emptyList()),"",0);DraftDiscoveryProbe.response(request,JsonUtils.mapper().valueToTree(raw));return request;}
    private static Map<String,Object> child(){return obj("taskId","t1","nodes",new ArrayList<>(),"requirements",new ArrayList<>(Collections.singletonList(obj("id","q1","kind","reference","nodeIds",Collections.emptyList(),"sourceQuote","The exact current bond reference."))),"issues",new ArrayList<>());}
    private static Map<String,Object> node(String proposed){return obj("id","r1","origin","scope_rules_compilation","compiledScopeRuleId","r1","sourceTaskId","t1","role",obj("kind","verification"),"editTargets",new ArrayList<>(Collections.singletonList(obj("action","check","proposedAction",proposed,"sourceTaskId","t1","sourceScopeId","s1","refinementIssue","UNIQUE_SOURCE_BOUND_SUBRANGE_REQUIRED"))));}
    private static Map<String,Object> evaluated(String proposed){return obj("taskReceipts",Collections.singletonList(obj("taskId","t1","nodes",Collections.singletonList(node(proposed)))));}
    private static Map<String,Object> target(Map<String,Object> evaluated){return map(list(map(list(map(list(evaluated.get("taskReceipts")).get(0)).get("nodes")).get(0)).get("editTargets")).get(0));}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List?(List<Object>)value:Collections.emptyList();}
}
