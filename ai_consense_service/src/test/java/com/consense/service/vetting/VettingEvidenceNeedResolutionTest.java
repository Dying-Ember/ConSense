package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored saved assessments test lineage and aggregation, not model or source accuracy. */
class VettingEvidenceNeedResolutionTest {
    @Test void progressiveContinuationMayDeferUncitedBackgroundWhileRetainingEveryQuotedSource(){
        SemanticPacketVO original=parent(),partial=child("need-1","insufficient_context","a","b"),terminal=child("next","consistent","a","b","c");
        partial.getSubmittedChunkIds().add("uncited-background");partial.setCheckState("insufficient_evidence");
        partial.setVerifiedInsufficientContextAssessments(1);partial.setVerifiedConsistentAssessments(0);
        String gap="AUTHORED remaining PRE2.1 provision";partial.getVerifiedAssessments().get(0).setComment(gap);
        partial.getVerifiedAssessments().get(0).setPendingFacts(Collections.singletonList(gap));
        SemanticEvidenceNeedVO next=need("next",Collections.emptyList(),Collections.singletonList("a"),0);next.setQuery(gap);partial.getEvidenceNeeds().add(next);
        terminal.setPacketId("terminal");terminal.setParentPacketId(partial.getPacketId());rebind(terminal);
        assertTrue(VettingEvidenceNeedResolution.resolve(Arrays.asList(original,partial,terminal)).supersedesVerified(original.getPacketId(),0));
        assertFalse(terminal.getSubmittedChunkIds().contains("uncited-background"));
        assertEquals(2,partial.getVerifiedAssessments().get(0).getEvidence().size());
    }
    @Test void splitContinuationsRequireCompatibleFinalDispositionsBeforeSupersedingOneOriginalAssessment(){
        for(boolean conflict:new boolean[]{false,true}){
            SemanticPacketVO original=parent(),partial=child("need-1","insufficient_context","a","b");
            partial.setCheckState("insufficient_evidence");partial.setVerifiedConsistentAssessments(0);partial.setVerifiedInsufficientContextAssessments(2);partial.setReturnedAssessments(2);
            String firstGap="AUTHORED missing PRE2.1",secondGap="AUTHORED missing PRE2.2";
            partial.getVerifiedAssessments().get(0).setComment(firstGap);partial.getVerifiedAssessments().get(0).setPendingFacts(Collections.singletonList(firstGap));
            partial.getVerifiedAssessments().add(new VerifiedAssessmentVO("insufficient_context","AUTHORED second pending",secondGap,Arrays.asList(quote(partial,"a"),quote(partial,"b")),Collections.singletonList(secondGap)));
            SemanticEvidenceNeedVO firstNeed=need("n1",Collections.emptyList(),Collections.singletonList("a"),0),secondNeed=need("n2",Collections.emptyList(),Collections.singletonList("a"),1);firstNeed.setQuery(firstGap);secondNeed.setQuery(secondGap);partial.getEvidenceNeeds().add(firstNeed);partial.getEvidenceNeeds().add(secondNeed);
            SemanticPacketVO first=child("n1","consistent","a","b","c"),second=child("n2",conflict?"issue":"consistent","a","b","d");first.setPacketId("final-one");second.setPacketId("final-two");first.setParentPacketId(partial.getPacketId());second.setParentPacketId(partial.getPacketId());rebind(first);rebind(second);
            VettingEvidenceNeedResolution.Result result=VettingEvidenceNeedResolution.resolve(Arrays.asList(original,partial,first,second));
            assertTrue(result.resolvedNeed(partial.getPacketId(),"n1"));assertTrue(result.resolvedNeed(partial.getPacketId(),"n2"));
            assertEquals(!conflict,result.supersedesVerified(original.getPacketId(),0),"Two valid but contradictory continuations cannot silently resolve one original judgment");
        }
    }
    @Test void boundedProgressiveChainRequiresEveryEarlierQuotedAuthorityAndExactNeedLineage(){
        for(String defect:Arrays.asList("valid","lost_prior_quote","changed_prior_authority","missing_prior_context","wrong_child_need","cycle","unbound_original_query","missing_continuation")){
            SemanticPacketVO original=parent(),partial=child("need-1","insufficient_context","a","b"),finalPacket=child("next-need","consistent","a","b","c");
            original.getEvidenceNeeds().get(0).setOriginalQueries(Collections.singletonList(original.getEvidenceNeeds().get(0).getQuery()));
            partial.setCheckState("insufficient_evidence");partial.setVerifiedInsufficientContextAssessments(1);partial.setVerifiedConsistentAssessments(0);
            String remaining="AUTHORED remaining PRE2.1 provision";partial.getVerifiedAssessments().get(0).setComment(remaining);partial.getVerifiedAssessments().get(0).setPendingFacts(Collections.singletonList(remaining));
            SemanticEvidenceNeedVO next=need("next-need",Collections.emptyList(),Collections.singletonList("a"),0);next.setQuery(remaining);next.setOriginalQueries(Collections.singletonList(remaining));partial.getEvidenceNeeds().add(next);
            finalPacket.setPacketId("final");finalPacket.setParentPacketId(partial.getPacketId());rebind(finalPacket);
            if("lost_prior_quote".equals(defect))finalPacket.getVerifiedAssessments().get(0).getEvidence().remove(1);
            if("changed_prior_authority".equals(defect))finalPacket.getVerifiedAssessments().get(0).getEvidence().get(1).setSourceHash(VettingCorpus.hash("CHANGED prior authority"));
            if("missing_prior_context".equals(defect))finalPacket.getSubmittedChunkIds().remove("b");
            if("wrong_child_need".equals(defect))finalPacket.setRequestedEvidenceNeedId("unrelated");
            if("cycle".equals(defect))finalPacket.setParentPacketId(original.getPacketId());
            if("unbound_original_query".equals(defect))original.getEvidenceNeeds().get(0).setOriginalQueries(Arrays.asList(original.getEvidenceNeeds().get(0).getQuery(),"FORGED unrelated target"));
            if("missing_continuation".equals(defect))partial.getEvidenceNeeds().clear();
            String before=JsonUtils.write(original.getVerifiedAssessments());
            assertEquals("valid".equals(defect),VettingEvidenceNeedResolution.resolve(Arrays.asList(original,partial,finalPacket)).supersedesVerified(original.getPacketId(),0),defect);
            assertEquals(before,JsonUtils.write(original.getVerifiedAssessments()),"Historical assessments remain unchanged");
        }
    }
    private final String policy=VettingCorpus.hash("AUTHORED policy");
    @Test void exactSupplementReplacesPendingEffectiveStateWithoutChangingHistory() {
        SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");
        List<SemanticPacketVO> packets=Arrays.asList(parent,child);
        String history=JsonUtils.write(packets);
        VettingEvidenceNeedResolution.Result resolved=VettingEvidenceNeedResolution.resolve(packets);
        assertEquals(0,resolved.unresolvedNeeds());assertTrue(resolved.supersedesVerified("parent",0));
        SemanticTopicVO topic=new SemanticTopicVO();topic.setStatus("completed");topic.setPacketAudits(packets);
        VettingPacketCoverage.finish(topic,null);
        assertEquals("checked_pass",topic.getCheckState());assertEquals(history,JsonUtils.write(packets));
        assertEquals("insufficient_evidence",parent.getCheckState());assertEquals("insufficient_context",parent.getVerifiedAssessments().get(0).getAssessment());
    }
    @Test void everyNeedForTheSameAssessmentMustResolve() {
        SemanticPacketVO parent=parent();parent.getEvidenceNeeds().add(need("need-2",Collections.emptyList(),Collections.singletonList("a"),0));
        SemanticPacketVO first=child("need-1","consistent","a","b");
        VettingEvidenceNeedResolution.Result partial=VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,first));
        assertEquals(1,partial.unresolvedNeeds());assertFalse(partial.supersedesVerified("parent",0));
        SemanticPacketVO second=child("need-2","consistent","a","c");second.setPacketId("second");rebind(second);
        assertTrue(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,first,second)).supersedesVerified("parent",0));
    }
    @Test void unrelatedFailedOrDuplicateChildrenCannotCloseANeed() {
        for(String mutation:Arrays.asList("parent","need","failed","duplicate")) {
            SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");List<SemanticPacketVO> packets=new ArrayList<>(Arrays.asList(parent,child));
            if("parent".equals(mutation))child.setParentPacketId("other");
            if("need".equals(mutation))child.setRequestedEvidenceNeedId("other");
            if("failed".equals(mutation))child.setStatus("failed");
            if("duplicate".equals(mutation)){SemanticPacketVO duplicate=child("need-1","consistent","a","c");duplicate.setPacketId("duplicate");rebind(duplicate);packets.add(duplicate);}
            assertFalse(VettingEvidenceNeedResolution.resolve(packets).supersedesVerified("parent",0),mutation);
        }
    }
    @Test void newEvidenceMustBeQuotedAndOriginalSourceIdentityMustMatch() {
        for(String mutation:Arrays.asList("unquoted","old_revision","unlocated","no_original","pending","conflict","wrong_role")) {
            SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");
            List<FindingEvidence> quotes=child.getVerifiedAssessments().get(0).getEvidence();
            if("unquoted".equals(mutation))quotes.remove(1);
            if("old_revision".equals(mutation))quotes.get(0).setOriginalSourceSha256(VettingCorpus.hash("other bytes"));
            if("unlocated".equals(mutation))quotes.get(1).setLocated(false);
            if("no_original".equals(mutation))quotes.remove(0);
            if("wrong_role".equals(mutation))quotes.get(0).setSourceRole("standard");
            if("pending".equals(mutation))child.getVerifiedAssessments().get(0).setAssessment("insufficient_context");
            if("conflict".equals(mutation))child.getVerifiedAssessments().add(new VerifiedAssessmentVO("issue","AUTHORED contrary decision","AUTHORED",new ArrayList<>(quotes)));
            assertFalse(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesVerified("parent",0),mutation);
        }
    }
    @Test void eachAffectedOriginalAssessmentNeedsItsOwnSupportedFinalOutcome() {
        SemanticPacketVO parent=parent();parent.getSubmittedChunkIds().add("c");
        parent.getVerifiedAssessments().add(new VerifiedAssessmentVO("insufficient_context","AUTHORED other source","AUTHORED required counterpart",new ArrayList<>(Collections.singletonList(quote(parent,"c")))));
        parent.getEvidenceNeeds().get(0).getAffectedChunkIds().add("c");parent.getEvidenceNeeds().get(0).getVerifiedAssessmentIndexes().add(1);
        SemanticPacketVO child=child("need-1","consistent","a","b");child.getSubmittedChunkIds().add("c");
        assertFalse(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesVerified("parent",0));
    }
    @Test void exactNeedMayUseAnotherValidatedSentenceWithinTheSameSourceChunk() {
        SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");
        child.getVerifiedAssessments().get(0).getEvidence().get(0).setQuote("AUTHORED different relevant sentence confirming the original obligation");
        assertTrue(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesVerified("parent",0));
    }
    @Test void mergedChecklistNeedStillRequiresEveryOriginalGenericAssessment() {
        SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");String item="APP-I-011";
        parent.getEvidenceNeeds().get(0).getChecklistItemIds().add(item);
        parent.getChecklistAssessments().add(check(parent,item,"insufficient_evidence","a"));child.getChecklistAssessments().add(check(child,item,"fulfilled","a","b"));
        parent.getSubmittedChunkIds().add("c");child.getSubmittedChunkIds().add("c");
        parent.getVerifiedAssessments().add(new VerifiedAssessmentVO("insufficient_context","AUTHORED another original","AUTHORED required counterpart",Collections.singletonList(quote(parent,"c"))));
        parent.getEvidenceNeeds().get(0).getAffectedChunkIds().add("c");parent.getEvidenceNeeds().get(0).getVerifiedAssessmentIndexes().add(1);
        assertFalse(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesChecklist("parent",item,"AUTHORED required counterpart",Collections.singletonList("a")));
    }
    @Test void allAffectedOriginalsStayInContextWithoutRequiringRepeatedQuotes() {
        SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");
        parent.getSubmittedChunkIds().add("c");child.getSubmittedChunkIds().add("c");
        parent.getVerifiedAssessments().get(0).getEvidence().add(quote(parent,"c"));parent.getEvidenceNeeds().get(0).getAffectedChunkIds().add("c");
        assertTrue(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesVerified("parent",0));
        child.getSubmittedChunkIds().remove("c");
        assertFalse(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesVerified("parent",0));
    }
    @Test void oneLinkedNeedDoesNotSuppressAnotherPendingReasonOnTheSameChecklistRow() {
        SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");String item="APP-I-011";
        parent.getEvidenceNeeds().get(0).getChecklistItemIds().add(item);
        parent.getChecklistAssessments().add(check(parent,item,"insufficient_evidence","a"));child.getChecklistAssessments().add(check(child,item,"fulfilled","a","b"));
        ChecklistAssessmentVO other=check(parent,item,"insufficient_evidence","a");other.setReason("AUTHORED separate unresolved matter");parent.getChecklistAssessments().add(other);
        VettingEvidenceNeedResolution.Result resolution=VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child));
        assertTrue(resolution.supersedesChecklist("parent",item,"AUTHORED required counterpart",Collections.singletonList("a")));
        assertFalse(resolution.supersedesChecklist("parent",item,other.getReason(),Collections.singletonList("a")));
    }
    @Test void malformedNeedCannotDiscardAnInvalidIndexAndResolveTheRest() {
        SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");
        parent.getEvidenceNeeds().get(0).getVerifiedAssessmentIndexes().add(-1);
        assertFalse(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesVerified("parent",0));
    }
    @Test void oneResolvedRowCannotHideAnUntrackedRowOnTheSameOriginalAssessment() {
        assertLinkedRowsOutcome(false);
    }
    @Test void allResolvedRowsAllowTheirSharedOriginalAssessmentToBeSuperseded() {
        assertLinkedRowsOutcome(true);
    }
    @Test void exactPendingFactNeedResolvesItsChecklistAndOriginalAssessment() {
        SemanticPacketVO parent=parent(),child=child("need-1","issue","a","b");String item=pendingFactRow(parent,child);
        VettingEvidenceNeedResolution.Result resolution=VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child));
        assertTrue(resolution.supersedesVerified("parent",0));assertEquals("superseded",resolution.effectiveCheckState(parent));
        AutomaticItemVO row=automaticRow(parent,child,item);assertEquals("not_fulfilled",row.getStatus());assertEquals(2,row.getAssessments().size());
    }
    @Test void pendingFactNeedsKeepExactIndexFactAndOtherPendingRows() {
        for(String mutation:Arrays.asList("wrong_index","missing_fact","untracked_same_row","unresolved_need")) {
            SemanticPacketVO parent=parent(),child=child("need-1","issue","a","b");String item=pendingFactRow(parent,child);
            if("missing_fact".equals(mutation))parent.getVerifiedAssessments().get(0).getPendingFacts().clear();
            if("wrong_index".equals(mutation))parent.getChecklistAssessments().get(0).setVerifiedAssessmentIndex(1);
            if("untracked_same_row".equals(mutation)){
                ChecklistAssessmentVO other=check(parent,item,"insufficient_evidence","a");other.setReason(parent.getChecklistAssessments().get(0).getReason());other.setVerifiedAssessmentIndex(1);parent.getChecklistAssessments().add(other);
                parent.getVerifiedAssessments().add(new VerifiedAssessmentVO("insufficient_context","AUTHORED other assessment","AUTHORED separate context",Collections.singletonList(quote(parent,"a"))));parent.setVerifiedInsufficientContextAssessments(2);
            }
            if("unresolved_need".equals(mutation)){
                String query="Whether another PSS appendix supplies the missing scope";parent.getVerifiedAssessments().get(0).getPendingFacts().add(query);
                SemanticEvidenceNeedVO other=need("need-2",Collections.singletonList(item),Collections.singletonList("a"),0);other.setQuery(query);parent.getEvidenceNeeds().add(other);
            }
            VettingEvidenceNeedResolution.Result resolution=VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child));
            assertEquals("insufficient_evidence",resolution.effectiveCheckState(parent),mutation);assertEquals("insufficient_evidence",automaticRow(parent,child,item).getStatus(),mutation);
        }
    }
    private String pendingFactRow(SemanticPacketVO parent,SemanticPacketVO child) {
        String item=VettingChecklistCatalog.items().stream().filter(i->"in_scope".equals(i.getReviewScope())&&VettingChecklistRequirementRelations.automaticEligible(i.getId(),"not_fulfilled")).findFirst().orElseThrow().getId();
        String query="Whether the PSS or SL further delineates applicability by Works part";
        parent.getVerifiedAssessments().get(0).setPendingFacts(new ArrayList<>(Collections.singletonList(query)));
        parent.getEvidenceNeeds().get(0).setQuery(query);parent.getEvidenceNeeds().get(0).getChecklistItemIds().add(item);
        ChecklistAssessmentVO row=check(parent,item,"insufficient_evidence","a");row.setReason("AUTHORED source scope remains uncertain");row.setVerifiedAssessmentIndex(0);parent.getChecklistAssessments().add(row);
        child.getChecklistAssessments().add(check(child,item,"not_fulfilled","a","b"));child.setCheckState("issue");child.setAcceptedFindings(1);return item;
    }
    private AutomaticItemVO automaticRow(SemanticPacketVO parent,SemanticPacketVO child,String item) {
        ObjectNode coverage=JsonUtils.mapper().createObjectNode(),result=JsonUtils.mapper().createObjectNode();result.putObject("promptPolicy").put("sha256",policy);
        coverage.putArray("semanticTopics").addObject().set("packetAudits",JsonUtils.mapper().valueToTree(Arrays.asList(parent,child)));
        return VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,result).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(r->item.equals(r.getItemId())).findFirst().orElseThrow();
    }
    private void assertLinkedRowsOutcome(boolean resolveBoth) {
        SemanticPacketVO parent=parent(),first=child("need-1","consistent","a","b");
        String firstItem="APP-I-011",secondItem="APP-I-012",secondReason="AUTHORED second unresolved requirement";
        ChecklistAssessmentVO firstRow=check(parent,firstItem,"insufficient_evidence","a"),secondRow=check(parent,secondItem,"insufficient_evidence","a");
        firstRow.setVerifiedAssessmentIndex(0);secondRow.setVerifiedAssessmentIndex(0);secondRow.setReason(secondReason);
        parent.getChecklistAssessments().add(firstRow);parent.getChecklistAssessments().add(secondRow);
        parent.getEvidenceNeeds().get(0).getChecklistItemIds().add(firstItem);first.getChecklistAssessments().add(check(first,firstItem,"fulfilled","a","b"));
        List<SemanticPacketVO> packets=new ArrayList<>(Arrays.asList(parent,first));
        if(resolveBoth) {
            SemanticEvidenceNeedVO secondNeed=need("need-2",Collections.singletonList(secondItem),Collections.singletonList("a"),0);secondNeed.setQuery(secondReason);parent.getEvidenceNeeds().add(secondNeed);
            SemanticPacketVO second=child("need-2","consistent","a","c");second.setPacketId("second");rebind(second);
            second.getChecklistAssessments().add(check(second,secondItem,"fulfilled","a","c"));packets.add(second);
        }
        assertEquals(resolveBoth,VettingEvidenceNeedResolution.resolve(packets).supersedesVerified("parent",0));
        SemanticTopicVO topic=new SemanticTopicVO();topic.setStatus("completed");topic.setPacketAudits(packets);VettingPacketCoverage.finish(topic,null);
        assertEquals(resolveBoth?"checked_pass":"insufficient_evidence",topic.getCheckState());
        assertEquals(2,parent.getChecklistAssessments().size());assertEquals("insufficient_context",parent.getVerifiedAssessments().get(0).getAssessment());
    }
    @Test void acceptedIssueCanResolveGenericNeedWithoutRemovingTheIssue() {
        SemanticPacketVO parent=parent(),child=child("need-1","issue","a","b");child.setCheckState("issue");child.setAcceptedFindings(1);
        SemanticTopicVO topic=new SemanticTopicVO();topic.setStatus("completed");topic.setPacketAudits(Arrays.asList(parent,child));
        VettingPacketCoverage.finish(topic,null);assertEquals("issue",topic.getCheckState());
        assertTrue(VettingEvidenceNeedResolution.resolve(topic.getPacketAudits()).supersedesVerified("parent",0));
    }
    @Test void checklistShowsFinalDecisionButRetainsBothHistoricalAssessments() {
        String item=VettingChecklistCatalog.items().stream().filter(i->"in_scope".equals(i.getReviewScope())&&VettingChecklistRequirementRelations.automaticEligible(i.getId(),"not_fulfilled")).findFirst().orElseThrow().getId();
        SemanticPacketVO parent=parent(),child=child("need-1","issue","a","b");
        parent.getEvidenceNeeds().get(0).getChecklistItemIds().add(item);
        parent.getChecklistAssessments().add(check(parent,item,"insufficient_evidence","a"));child.getChecklistAssessments().add(check(child,item,"not_fulfilled","a","b"));
        ObjectNode coverage=JsonUtils.mapper().createObjectNode(),result=JsonUtils.mapper().createObjectNode();
        result.putObject("promptPolicy").put("sha256",policy);
        coverage.putArray("semanticTopics").addObject().set("packetAudits",JsonUtils.mapper().valueToTree(Arrays.asList(parent,child)));
        String before=JsonUtils.write(coverage);
        AutomaticItemVO row=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,result).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(r->item.equals(r.getItemId())).findFirst().orElseThrow();
        assertEquals("not_fulfilled",row.getStatus());assertEquals(2,row.getAssessments().size());assertEquals(before,JsonUtils.write(coverage));
        ((ObjectNode)coverage.path("semanticTopics").get(0).path("packetAudits").get(1)).put("requestedEvidenceNeedId","unrelated");
        row=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,result).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(r->item.equals(r.getItemId())).findFirst().orElseThrow();
        assertEquals("insufficient_evidence",row.getStatus());
    }
    @Test void checklistNeedsCannotBeSatisfiedByAnotherRowOrAChangedPolicy() {
        for(String mutation:Arrays.asList("item","policy","source","remaining_need")) {
            SemanticPacketVO parent=parent(),child=child("need-1","consistent","a","b");String item="APP-I-011";
            parent.getEvidenceNeeds().get(0).getChecklistItemIds().add(item);parent.getChecklistAssessments().add(check(parent,item,"insufficient_evidence","a"));
            ChecklistAssessmentVO outcome=check(child,item,"fulfilled","a","b");child.getChecklistAssessments().add(outcome);
            if("item".equals(mutation))outcome.setItemId("APP-I-012");
            if("policy".equals(mutation))outcome.setPolicySha256(VettingCorpus.hash("other policy"));
            if("source".equals(mutation))outcome.getEvidence().get(0).setSourceHash(VettingCorpus.hash("other revision"));
            if("remaining_need".equals(mutation))parent.getEvidenceNeeds().add(need("need-2",Collections.singletonList(item),Collections.singletonList("a"),0));
            assertFalse(VettingEvidenceNeedResolution.resolve(Arrays.asList(parent,child)).supersedesChecklist("parent",item,"AUTHORED required counterpart",Collections.singletonList("a")),mutation);
        }
    }
    private SemanticPacketVO parent() {
        SemanticPacketVO parent=packet("parent","a");parent.setCheckState("insufficient_evidence");parent.setVerifiedInsufficientContextAssessments(1);
        parent.getVerifiedAssessments().add(new VerifiedAssessmentVO("insufficient_context","AUTHORED pending","AUTHORED required counterpart",new ArrayList<>(Collections.singletonList(quote(parent,"a")))));
        parent.getEvidenceNeeds().add(need("need-1",Collections.emptyList(),Collections.singletonList("a"),0));return parent;
    }
    private SemanticPacketVO child(String need,String state,String... ids) {
        SemanticPacketVO child=packet("child",ids);child.setParentPacketId("parent");child.setRequestedEvidenceNeedId(need);child.setCheckState("checked_pass");child.setVerifiedConsistentAssessments(1);
        child.getVerifiedAssessments().add(new VerifiedAssessmentVO(state,"AUTHORED final","AUTHORED answer",Arrays.stream(ids).map(id->quote(child,id)).collect(Collectors.toCollection(ArrayList::new))));return child;
    }
    private SemanticPacketVO packet(String id,String... chunks) {
        SemanticPacketVO packet=new SemanticPacketVO();packet.setPacketId(id);packet.setSourceSnapshotSha256(VettingCorpus.hash(id));packet.setActualGatewayCallStarted(true);packet.setStatus("completed");
        packet.setInputBudgetStatus("provider_estimated_fit");packet.setSubmittedChunkIds(new ArrayList<>(Arrays.asList(chunks)));packet.setReturnedAssessments(1);return packet;
    }
    private SemanticEvidenceNeedVO need(String id,List<String> items,List<String> chunks,Integer... indexes) {
        SemanticEvidenceNeedVO need=new SemanticEvidenceNeedVO();need.setNeedId(id);need.setQuery("AUTHORED required counterpart");need.setChecklistItemIds(new ArrayList<>(items));need.setAffectedChunkIds(new ArrayList<>(chunks));need.setVerifiedAssessmentIndexes(new ArrayList<>(Arrays.asList(indexes)));return need;
    }
    private FindingEvidence quote(SemanticPacketVO packet,String id) {
        FindingEvidence quote=new FindingEvidence();quote.setLocated(true);quote.setQuote("AUTHORED exact quoted source "+id);quote.setSide("source");quote.setSourceRole("tender");quote.setDocumentId(id);
        quote.setSourceHash(VettingCorpus.hash("revision "+id));quote.setOriginalSourceSha256(VettingCorpus.hash("original "+id));quote.setPacketId(packet.getPacketId());quote.setPacketSourceSnapshotSha256(packet.getSourceSnapshotSha256());
        EvidencePointer pointer=new EvidencePointer();pointer.setCanonicalChunkId(id);quote.setCanonicalEvidence(pointer);return quote;
    }
    private ChecklistAssessmentVO check(SemanticPacketVO packet,String item,String status,String... ids) {
        ChecklistAssessmentVO check=new ChecklistAssessmentVO();check.setItemId(item);check.setStatus(status);check.setReason("insufficient_evidence".equals(status)?"AUTHORED required counterpart":"AUTHORED source-bound conclusion");check.setIssueTypeCode("not_fulfilled".equals(status)?"REQ":null);
        check.setPolicySha256(policy);check.setPacketId(packet.getPacketId());check.setSourceSnapshotSha256(packet.getSourceSnapshotSha256());check.setEvidenceChunkIds(new ArrayList<>(Arrays.asList(ids)));
        check.setEvidence(Arrays.stream(ids).map(id->quote(packet,id)).collect(Collectors.toCollection(ArrayList::new)));return check;
    }
    private void rebind(SemanticPacketVO packet){for(VerifiedAssessmentVO record:packet.getVerifiedAssessments())for(FindingEvidence quote:record.getEvidence())quote.setPacketId(packet.getPacketId());}
}
