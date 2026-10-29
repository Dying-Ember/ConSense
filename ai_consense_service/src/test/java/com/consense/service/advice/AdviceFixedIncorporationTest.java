package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.FixedBaselineRetrievalClient;
import com.consense.service.vetting.FixedReferenceEvidence;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Constructor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authored source carriers at the real Advice mapper/publication seam; no model, API or live data. */
class AdviceFixedIncorporationTest {
    static final String TITLE="General Conditions of Contract for Building Works 2013 Edition (version 1.1)";
    static final String SHA=String.join("",Collections.nCopies(64,"a"));
    final AdviceService advice=mock(AdviceService.class,CALLS_REAL_METHODS);

    List<CitationVO> sources(String name,String adoption,boolean current)throws Exception {
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);
        ReflectionTestUtils.setField(advice,"fixedBaseline",fixed);
        JsonNode p=JsonUtils.parse("{\"fileLabel\":\"GCC\",\"sourceHash\":\""+SHA+"\",\"originalSourceSha256\":\""+SHA+"\",\"pageNo\":58,\"content\":\"14.1 The Contractor shall deliver the statement to the Surveyor.\",\"fixedReferenceDocumentTitle\":\""+TITLE+"\",\"sourceQuality\":{\"family\":\"GCC\"},\"fixedDevelopmentBaseline\":true}");
        Constructor<FixedReferenceEvidence> ctor=FixedReferenceEvidence.class.getDeclaredConstructor(String.class,double.class,JsonNode.class,JsonNode.class,JsonNode.class);ctor.setAccessible(true);
        FixedReferenceEvidence e=ctor.newInstance("fixed:gcc",.9,p,null,null);
        when(fixed.evidence(any())).thenReturn(e);
        CitationVO standard=ReflectionTestUtils.invokeMethod(advice,"toCitation",e.asHit());standard.setEvidenceId("[1]");
        Map<String,Object> raw=new LinkedHashMap<>();raw.put("fileLabel",name);raw.put("documentId","9");raw.put("sourceHash",SHA);raw.put("sourceRole","tender");raw.put("content",adoption);
        com.fasterxml.jackson.databind.node.ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("status",current?"complete_observed_unit":"unknown");unit.put("reason",current?"":"additional_source_context_deferred");unit.put("semanticScopeVerified",false);
        unit.putArray("requiredMemberIds").add("project:coc");com.fasterxml.jackson.databind.node.ObjectNode member=unit.putArray("members").addObject();member.put("id","project:coc");member.put("unitId","coc-unit");member.put("documentId","9");member.put("role","tender");member.put("sourceHash",SHA);member.put("sourceRevisionSha256",SHA);member.set("parts",JsonUtils.parse("[{\"blockId\":\"authored\"}]"));
        EvidencePointer pointer=new EvidencePointer();pointer.setCanonicalChunkId("project:coc");pointer.setUnitId("coc-unit");pointer.setDocumentId(9L);pointer.setJavaParsedSourceHash(SHA);pointer.setCanonicalSourceHash(SHA);pointer.setOriginalSourceSha256(SHA);pointer.setSourceRevisionSha256(SHA);pointer.setParts(member.path("parts"));
        raw.put("canonicalSourceUnit",unit);raw.put("canonicalEvidence",Collections.singletonList(pointer));
        CitationVO tender=ReflectionTestUtils.invokeMethod(advice,"toCitation",new VectorStore.SearchHit("project:coc",.8,raw));tender.setEvidenceId("[2]");
        return Arrays.asList(standard,tender);
    }
    boolean accepts(List<CitationVO> sources,List<String> aliases){return ReflectionTestUtils.invokeMethod(advice,"incorporationSupported","project_contract",aliases,sources);}
    @Test void matchingCurrentCoCAndFixedVersionCanPublishTheirJointlyCitedRule()throws Exception {
        List<CitationVO> sources=sources("09 - CoC.docx","The Contract comprises the "+TITLE+".",true);
        assertTrue(accepts(sources,Arrays.asList("[1]","[2]")),"A source-bound fixed standard plus the selected matching CoC must not be permanently rejected");
        assertFalse(accepts(sources,Collections.singletonList("[1]")),"The analysis must cite the actual adoption evidence too");
        ReflectionTestUtils.invokeMethod(advice,"completeFixedApplicability",sources);
        assertTrue(sources.get(0).getApplicabilityContextComplete());
        assertEquals(Collections.singletonList("[2]"),ReflectionTestUtils.<List<String>>invokeMethod(AdviceService.class,"adoptionDependencies",sources,Collections.singletonList("[1]")));
        AdviceService.AnswerRecord answer=JsonUtils.read(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Under the adopted GCC the Contractor supplies an interim statement [1][2].\",\"citations\":[\"[1]\",\"[2]\"]}"),AdviceService.AnswerRecord.class);
        for(AdvisoryHarness.Section section:answer.getSections())AdvisoryHarness.validate(section,sources,false,(basis,refs)->ReflectionTestUtils.invokeMethod(advice,"incorporationSupported",basis,refs,sources));
        assertEquals(Arrays.asList("[1]","[2]"),answer.getSections().get(1).getCitations());
    }
    @Test void wrongEditionWrongVersionInspectionOnlyAndUnverifiedSourceRemainUnsupported()throws Exception {
        for(String text:Arrays.asList(TITLE.replace("2013","2020"),TITLE.replace("1.1","1.2"),"For inspection only: "+TITLE,"The General Conditions of Contract apply."))
            assertFalse(accepts(sources("09 - CoC.docx",text,true),Arrays.asList("[1]","[2]")),text);
        String adoption="The Contract comprises the "+TITLE+".";
        assertFalse(accepts(sources("08 - SCT.docx",adoption,true),Arrays.asList("[1]","[2]")));
        assertFalse(accepts(sources("09 - CoC.docx",adoption,false),Arrays.asList("[1]","[2]")));
    }
    @Test void aSourceOutsideTheAdmittedAliasesCannotSupplyAdoption()throws Exception {
        List<CitationVO> sources=sources("09 - CoC.docx",TITLE,true);
        assertFalse(accepts(Collections.singletonList(sources.get(0)),Collections.singletonList("[1]")));
        assertFalse(accepts(sources,Collections.singletonList("[1]")));
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(advice,"incorporationSupported","reference_only",Collections.singletonList("[1]"),sources));
    }
    @Test void fullVersionBoundaryAndExplicitNonIncorporationCannotBecomeAdoption()throws Exception {
        for(String text:Arrays.asList(TITLE.replace("1.1","1.1.2"),TITLE+" is not incorporated into this Contract.",TITLE+" and "+TITLE.replace("2013","2020")))
            assertFalse(accepts(sources("09 - CoC.docx",text,true),Arrays.asList("[1]","[2]")),text);
        assertTrue(accepts(sources("09 - CoC.docx","The Contract comprises the "+TITLE+".",true),Arrays.asList("[1]","[2]")));
        assertFalse(accepts(sources("09 - CoC.docx","The Contract comprises the General Conditions of Tender for Building Works 2020 Edition (version 2.0). The Contract comprises the "+TITLE+".",true),Arrays.asList("[1]","[2]")),"A multi-sentence carrier is not a complete direct adoption statement");
    }
    @Test void titleOrFilenameCannotTurnNegativeConditionalOrUnrelatedReferenceIntoAdoption()throws Exception {
        for(String text:Arrays.asList(TITLE, "The following publication is referenced: "+TITLE+".",
                TITLE+" shall not apply to this Contract.", "The Contract does not comprise the "+TITLE+".",
                "If approved, the Contract comprises the "+TITLE+".", "The Contract may comprise the "+TITLE+".",
                "For reference only: the Contract comprises the "+TITLE+".",
                "The witness incorrectly asserted that the Contract comprises the "+TITLE+".",
                "The Contract comprises the "+TITLE+" solely as a reference.",
                "The Contract comprises the "+TITLE+", contingent on approval.",
                "The Contract comprises the "+TITLE+" when signed.",
                "The Contract comprises the "+TITLE+" on condition that all parties sign.",
                "The Contract comprises the "+TITLE+", but it is inapplicable.",
                "The Contract comprises the "+TITLE+", but it was later superseded.",
                "The Contract comprises the "+TITLE+" if it is approved.",
                "In another project, the Contract comprises the "+TITLE+".",
                "The Contract comprises the "+TITLE+" for a different project.",
                "The Contract comprises the "+TITLE+", subject to approval.",
                "The Contract comprises the drawings. For reference, see "+TITLE+".")) {
            List<CitationVO> evidence=sources("09 - CoC.docx",text,true);
            assertFalse(accepts(evidence,Arrays.asList("[1]","[2]")),text);
            ReflectionTestUtils.invokeMethod(advice,"completeFixedApplicability",evidence);
            assertFalse(evidence.get(0).getApplicabilityContextComplete(),text);
        }
    }
    @Test void explicitDirectAdoptionIsAcceptedInEitherDirection()throws Exception {
        for(String text:Arrays.asList("The Contract comprises the "+TITLE+".",
                "This Contract shall include the "+TITLE+".", TITLE+" is part of the Contract."))
            assertTrue(accepts(sources("09 - CoC.docx",text,true),Arrays.asList("[1]","[2]")),text);
    }
    @Test void incompleteOrUnboundCocCannotMarkStandardApplicableOrSupplyAdoptionEvenWhenItsTextWasAdmitted()throws Exception {
        for(String defect:Arrays.asList("unknown","no-unit","no-pointer","wrong-document","wrong-member","wrong-source","empty-parts","missing-required-member","changed-parts")){
            List<CitationVO> evidence=sources("09 - CoC.docx","The Contract comprises the "+TITLE+".",true);CitationVO coc=evidence.get(1);
            if("unknown".equals(defect)){((com.fasterxml.jackson.databind.node.ObjectNode)coc.getCanonicalSourceUnit()).put("status","unknown").put("reason","additional_source_context_deferred").put("semanticScopeVerified",false);}
            if("no-unit".equals(defect))coc.setCanonicalSourceUnit(null);
            if("no-pointer".equals(defect))coc.setCanonicalEvidence(Collections.emptyList());
            if("wrong-document".equals(defect))coc.getCanonicalEvidence().get(0).setDocumentId(10L);
            if("wrong-member".equals(defect))coc.getCanonicalEvidence().get(0).setCanonicalChunkId("another-member");
            if("wrong-source".equals(defect))coc.getCanonicalEvidence().get(0).setJavaParsedSourceHash("b".repeat(64));
            if("empty-parts".equals(defect))coc.getCanonicalEvidence().get(0).setParts(JsonUtils.parse("[]"));
            if("missing-required-member".equals(defect))((com.fasterxml.jackson.databind.node.ArrayNode)coc.getCanonicalSourceUnit().path("requiredMemberIds")).add("missing");
            if("changed-parts".equals(defect))coc.getCanonicalEvidence().get(0).setParts(JsonUtils.parse("[{\"blockId\":\"different\"}]"));
            ReflectionTestUtils.invokeMethod(advice,"completeFixedApplicability",evidence);
            assertFalse(evidence.get(0).getApplicabilityContextComplete(),defect);
            assertFalse(ReflectionTestUtils.<Boolean>invokeMethod(advice,"incorporationSupported","project_contract",Arrays.asList("[1]","[2]"),evidence,Collections.singleton("[2]")),defect);
            List<String> dependencies=ReflectionTestUtils.invokeMethod(AdviceService.class,"adoptionDependencies",evidence,Collections.singletonList("[1]"));
            assertTrue(dependencies.isEmpty(),defect);
            assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(advice,"incorporationSupported","reference_only",Collections.singletonList("[1]"),evidence),"Standard-only interpretation remains available: "+defect);
        }
    }
}
