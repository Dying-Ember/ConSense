package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdviceFixedReferenceFocusTest {
    @Test void explicitRequestedParentKeepsItsEntireTextAndRequiredGroupWithoutContentsOrTangentialClauses(){
        VectorStore.SearchHit contents=hit("contents","GCC",null,"CONTENTS\n13.6 Liability\n13.7 Insurance\n14.1 Interim payments\n17.1 Disputes","document","content");
        VectorStore.SearchHit disputes=hit("17.1","GCC","17.1","17.1 "+"Full operative dispute text. ".repeat(800),"clause","body"),continued=hit("continued","GCC",null,"Required continuation.","document","body");
        disputes.getPayload().put("fixedReferenceSourceUnit",JsonUtils.parse("{\"requiredMemberIds\":[\"17.1\",\"continued\"]}"));
        List<VectorStore.SearchHit> all=Arrays.asList(contents,hit("definitions","GCC","1.1","Definitions.","clause","body"),disputes,hit("confidentiality","GCC","4.4","Confidentiality.","clause","body"),continued);
        List<VectorStore.SearchHit> result=AdviceService.focusedFixedReferenceHits(all,"Draw GCC17.1 for the selected contract.");
        assertEquals(Arrays.asList(disputes,continued),result);assertSame(disputes,result.get(0));assertTrue(disputes.text().length()>21615);
    }
    @Test void naturalPaymentQuestionUsesOperativeRankInsteadOfContentsReferences(){
        VectorStore.SearchHit payment=hit("payment","GCC","14.1","14.1 The Contractor shall submit the interim statement.","clause","body");
        assertEquals(Collections.singletonList(payment),AdviceService.focusedFixedReferenceHits(Arrays.asList(hit("index","GCC",null,"Clause Page\n17.1 Disputes\n1.1 Definitions","document","content"),payment),"What information is required for an interim payment application?"));
    }
    @Test void aDocumentVersionNumberCannotBecomeARequestedClause(){
        String question="Under GCC 2013 Edition (version 1.1), what information is required for interim payment?";
        VectorStore.SearchHit payment=hit("payment","GCC","14.1","14.1 Interim payment particulars.","clause","body"),definitions=hit("definitions","GCC","1.1","1.1 Definitions.","clause","body");
        assertTrue(AdviceService.focusedFixedReferenceHits(Arrays.asList(payment,definitions),question).contains(payment),"Edition/version metadata must not focus an otherwise natural payment question on definitions");assertFalse(com.consense.service.vetting.FixedReferenceEvidence.matchesRequestedClause(definitions,question));
        Set<String> requested=ReflectionTestUtils.invokeMethod(com.consense.service.vetting.OerBusinessEvidence.class,"requestedAdviceClauses",question);assertTrue(requested.isEmpty(),"The version is not an SCC amendment target");
    }
    @Test void naturalPrecedenceQuestionKeepsItsOwnPrimaryOperativeBody(){
        VectorStore.SearchHit precedence=hit("precedence","GCC","1.1","1.1 The documents shall be construed together; the stated precedence applies.","clause","body");
        assertEquals(Collections.singletonList(precedence),AdviceService.focusedFixedReferenceHits(Arrays.asList(hit("index","GCC",null,"CONTENTS\n17.1 Avoidance and resolution","document","content"),precedence),"How should the selected contract documents be read if their requirements differ?"));
    }
    @Test void explicitFamilyDoesNotSelectTheSameNumberFromAnotherStandard(){
        VectorStore.SearchHit gcc=hit("gcc","GCC","17.1","GCC text.","clause","body"),sl=hit("sl","SL","17.1","SL text.","clause","body");
        for(String question:Arrays.asList("Explain GCC 17.1.","Explain GCC Clause17.1.","Explain GCC clause 17.1.")){assertEquals(Collections.singletonList(gcc),AdviceService.focusedFixedReferenceHits(Arrays.asList(sl,gcc),question),question);assertTrue(com.consense.service.vetting.FixedReferenceEvidence.matchesRequestedClause(gcc,question));assertFalse(com.consense.service.vetting.FixedReferenceEvidence.matchesRequestedClause(sl,question));}
    }
    @Test void observedLabelFallbackIsUsedWhenTheDeclaredClauseFieldIsNull(){
        VectorStore.SearchHit gcc=hit("gcc","GCC",null,"Operative text.","clause","body");gcc.getPayload().put("observedLabelRaw","17.1");
        assertEquals(Collections.singletonList(gcc),AdviceService.focusedFixedReferenceHits(Arrays.asList(hit("other","GCC","1.1","Other text.","clause","body"),gcc),"GCC17.1"));
    }
    @Test void aQuestionAboutContentsRetainsTheContentsAndOlderUnlabelledCarriersArePreserved(){
        List<VectorStore.SearchHit> contents=Arrays.asList(hit("index","GCC",null,"CONTENTS","document","content"),hit("gcc","GCC","17.1","Body.","clause","body"));
        assertSame(contents,AdviceService.focusedFixedReferenceHits(contents,"Where is GCC17.1 in the table of contents?"));
        List<VectorStore.SearchHit> legacy=Collections.singletonList(hit("legacy","GCC",null,"A preserved old carrier.","",""));assertSame(legacy,AdviceService.focusedFixedReferenceHits(legacy,"Explain GCC17.1"));
    }
    @Test void missingRequiredGroupMemberCannotBeSilentlyDiscarded(){
        VectorStore.SearchHit gcc=hit("gcc","GCC","17.1","Body.","clause","body");gcc.getPayload().put("fixedReferenceSourceUnit",JsonUtils.parse("{\"requiredMemberIds\":[\"gcc\",\"missing\"]}"));
        assertThrows(IllegalStateException.class,()->AdviceService.focusedFixedReferenceHits(Collections.singletonList(gcc),"GCC17.1"));
    }
    @Test void actualFrozenRoleCarrierIsDisplayedAsStandardWithoutRelabellingAnUncheckedCarrier(){
        VectorStore.SearchHit gcc=hit("gcc","GCC","17.1","Body.","clause","body");assertFalse(gcc.getPayload().containsKey("sourceRole"));assertEquals("standard",AdviceService.modelSourceRole(gcc));
        AdviceService advice=mock(AdviceService.class,CALLS_REAL_METHODS);String entry=ReflectionTestUtils.invokeMethod(advice,"boundedEvidenceEntry",gcc,1);assertTrue(entry.contains("role=standard"));assertFalse(entry.contains("role=null"));
        gcc.getPayload().remove("fixedDevelopmentBaseline");assertNull(AdviceService.modelSourceRole(gcc));gcc.getPayload().put("sourceRole","tender");assertEquals("tender",AdviceService.modelSourceRole(gcc));
    }
    @Test void rejectionObservationUsesFiniteCodesWithoutCopyingRejectedSourceBodies(){
        assertEquals("adoption_or_basis",AdviceService.rejectionCode("section.basisScope: adoption or requested basis is not supported"));
        assertEquals("precedence_scope",AdviceService.rejectionCode("derived_precedence_order_lost_qualifier"));assertEquals("citation_contract",AdviceService.rejectionCode("section.citations: invalid alias"));
        assertEquals("language_contract",AdviceService.rejectionCode("Answer language mismatch"));assertEquals("unresolved_evidence",AdviceService.rejectionCode("section has unresolved gaps"));
        assertEquals("section_contract",AdviceService.rejectionCode("PRIVATE_SOURCE_BODY_DO_NOT_LOG"));
    }
    private static VectorStore.SearchHit hit(String id,String family,String clause,String text,String kind,String sourceRole){
        Map<String,Object> p=new LinkedHashMap<>();p.put("fileKey",family);p.put("fileLabel",family);p.put("clauseId",clause);p.put("observedLabelRaw",clause);p.put("observedNodeKind",kind);p.put("observedSourceRole",sourceRole);p.put("role","standard");p.put("fixedDevelopmentBaseline",true);p.put("content",text);p.put("originalSourceSha256","e".repeat(64));return new VectorStore.SearchHit(id,.9,p);
    }
}
