package com.consense.service.advice;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.dto.AdviceDtos.CitationVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Constructor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored protocol counterexamples; no model, source-quality or legal acceptance. */
class AdviceCitationMetadataCompletionTest {
    @Test void admittedSeedTextCanCompleteExplicitCitationMetadataWhileContextStaysUnknown()throws Exception{
        List<CitationVO> selected=sources();for(CitationVO source:selected){source.setCanonicalEvidence(Collections.singletonList(new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer()));
            source.setCanonicalSourceUnit(JsonUtils.parse("{\"status\":\"unknown\",\"reason\":\"additional_source_context_deferred\",\"semanticScopeVerified\":false}"));}
        Object publication=publication(selected,new HashSet<>(Arrays.asList("[1]","[2]")));begin(publication,false,false);
        AdviceService.AnswerRecord answer=answer("Supported [1] and explicit [2].");ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
        assertTrue(errors(publication).isEmpty(),errors(publication).toString());assertEquals(Arrays.asList("[1]","[2]"),answer.getSections().get(1).getCitations());
        assertEquals("unknown",selected.get(0).getCanonicalSourceUnit().path("status").asText());assertFalse(selected.get(0).getCanonicalSourceUnit().path("semanticScopeVerified").asBoolean());
    }
    @Test void synchronousCompletionChangesOnlyOuterMetadataAndKeepsRawAttempt() throws Exception {
        Object publication=publication(sources());begin(publication,false,false);
        AdviceService.AnswerRecord answer=answer("Supported [1] and explicit [2].");
        AdvisoryHarness.Section raw=answer.getSections().get(1);String before=JsonUtils.write(raw);
        ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
        assertEquals(Arrays.asList("[1]","[2]"),answer.getSections().get(1).getCitations());
        assertEquals(before,JsonUtils.write(raw));assertEquals(raw.getContent(),answer.getSections().get(1).getContent());
        assertEquals(JsonUtils.write(raw.getChecks()),JsonUtils.write(answer.getSections().get(1).getChecks()));
        assertTrue(errors(publication).isEmpty(),errors(publication).toString());
        ReflectionTestUtils.invokeMethod(publication,"recordAttempt",0,answer);
        Map<?,?> attempt=((List<Map<?,?>>)ReflectionTestUtils.getField(publication,"attempts")).get(0);
        assertEquals(0,attempt.get("attempt"));assertEquals("raw_model_sections_observed",attempt.get("sectionsScope"));
        assertEquals(before,JsonUtils.write(((List<?>)attempt.get("sections")).get(1)));
        List<?> changes=(List<?>)attempt.get("citationMetadataCompletions");assertEquals(1,changes.size());
        Map<?,?> change=(Map<?,?>)changes.get(0);assertEquals("analysis",change.get("sectionId"));
        assertEquals(Collections.singletonList("[1]"),change.get("declaredCitations"));
        assertEquals(Arrays.asList("[1]","[2]"),change.get("explicitBodyAliases"));
        assertEquals(Collections.singletonList("[2]"),change.get("addedAliases"));
        assertEquals(Arrays.asList("[1]","[2]"),change.get("normalizedCitations"));
        assertEquals(VettingCorpus.hash(before),change.get("rawSerializedSectionSha256"));
        assertEquals(VettingCorpus.hash(JsonUtils.write(answer.getSections().get(1))),change.get("normalizedSerializedSectionSha256"));
        assertEquals("application_serialized_section_not_provider_bytes",change.get("hashScope"));
    }
    @Test void streamedAndFinalCandidatesNormalizeToTheSameCopies() throws Exception {
        Object publication=publication(sources());begin(publication,true,false);AdviceService.AnswerRecord answer=answer("Supported [1] and [2].");
        String raw=JsonUtils.write(answer.getSections());for(AdvisoryHarness.Section section:answer.getSections())accept(publication,section);
        assertEquals(raw,JsonUtils.write(ReflectionTestUtils.getField(publication,"proposed")));
        assertEquals(Arrays.asList("[1]","[2]"),accepted(publication).get(1).getCitations());
        assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",true));
        assertTrue(errors(publication).isEmpty());assertEquals(2,accepted(publication).size());
    }
    @Test void completionPreservesDeclaredOrderAndOnlyAppendsExplicitEndpoints() throws Exception {
        List<CitationVO> sources=sources();CitationVO fourth=new CitationVO();fourth.setEvidenceId("[4]");fourth.setSourceRole("tender");sources.add(fourth);
        Object publication=publication(sources);begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Inventory [1]–[4]; repeat [4].");
        answer.getSections().get(1).setCitations(Collections.singletonList("[2]"));
        answer.getSections().get(1).getChecks().get(1).setCitations(Collections.singletonList("[2]"));
        ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
        assertEquals(Arrays.asList("[2]","[1]","[4]"),answer.getSections().get(1).getCitations());assertTrue(errors(publication).isEmpty());
    }
    @Test void unknownAndMalformedBodyAliasesRemainRejected() throws Exception {
        for(String alias:Arrays.asList("[999]","[01]")){Object publication=publication(sources());begin(publication,false,false);
            AdviceService.AnswerRecord answer=answer("Supported [1], unknown "+alias);ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
            assertFalse(errors(publication).isEmpty(),alias);assertEquals(1,accepted(publication).size());}
    }
    @Test void originalUnknownDuplicateAndEmptyArraysAreNotCleanedOrPromoted() throws Exception {
        for(List<String> refs:Arrays.asList(Arrays.asList("[1]","[999]"),Arrays.asList("[1]","[1]"),Collections.<String>emptyList())){
            Object publication=publication(sources());begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Supported [1] and [2].");answer.getSections().get(1).setCitations(refs);
            ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
            assertFalse(errors(publication).isEmpty(),refs.toString());assertEquals(1,accepted(publication).size());assertEquals(refs,answer.getSections().get(1).getCitations());}
    }
    @Test void standardOnlyDeclaredBasisCannotAcquireTenderAdoptionFromBody() throws Exception {
        List<CitationVO> sources=sources();sources.get(0).setSourceRole("standard");sources.get(0).setApplicabilityContextComplete(true);
        sources.get(0).setFileLabel("General Specification");sources.get(1).setContent("The General Specification is incorporated.");
        Object publication=publication(sources);begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Library statement [1]; inventory mentions tender [2].");
        ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
        assertFalse(errors(publication).isEmpty());assertEquals(1,accepted(publication).size());assertEquals(Collections.singletonList("[1]"),answer.getSections().get(1).getCitations());
    }
    @Test void limitsAndUncertaintyNeverReceiveCitationCompletion() throws Exception {
        for(String kind:Arrays.asList("limits","uncertainty")){Object publication=publication(sources());begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Forbidden prose [1] and [2].");
            AdvisoryHarness.Section section=answer.getSections().get(1);section.setKind(kind);section.setBasisScope("insufficient");
            ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
            assertFalse(errors(publication).isEmpty());assertEquals(1,accepted(publication).size());assertEquals(Collections.singletonList("[1]"),section.getCitations());}
    }
    @Test void checkOnlyAliasIsNotGuessedIntoOuterMetadata() throws Exception {
        Object publication=publication(sources());begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Supported [1].");
        answer.getSections().get(1).getChecks().get(1).setCitations(Arrays.asList("[1]","[2]"));
        ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);assertFalse(errors(publication).isEmpty());assertEquals(1,accepted(publication).size());
    }
    @Test void graphOnlyAliasIsNotGuessedButExplicitBodyAliasCanCompleteFlowchartMetadata() throws Exception {
        Object rejected=publication(sources());begin(rejected,false,false);AdviceService.AnswerRecord absent=flowchart("Supported relation [1].");
        ReflectionTestUtils.invokeMethod(rejected,"finish",absent,"Explain the clause",false);assertFalse(errors(rejected).isEmpty());assertEquals(1,accepted(rejected).size());
        Object publication=publication(sources());begin(publication,false,false);AdviceService.AnswerRecord explicit=flowchart("Supported relation [1] and [2].");
        String graph=JsonUtils.write(explicit.getSections().get(1).getFlowchartPlan());ReflectionTestUtils.invokeMethod(publication,"finish",explicit,"Explain the clause",false);
        assertTrue(errors(publication).isEmpty(),errors(publication).toString());assertEquals(graph,JsonUtils.write(explicit.getSections().get(1).getFlowchartPlan()));
    }
    @Test void appendedSourceStillMustPassCanonicalAndStandardAdoptionGates() throws Exception {
        for(boolean canonical:Arrays.asList(true,false)){List<CitationVO> sources=sources();
            if(canonical)sources.get(1).setCanonicalSourceUnit(JsonUtils.parse("{\"status\":\"partial\"}"));else sources.get(1).setSourceRole("standard");
            Object publication=publication(sources);begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Supported [1] and [2].");
            ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);assertFalse(errors(publication).isEmpty());assertEquals(1,accepted(publication).size());}
    }
    @Test void streamedNarrativeOrCheckRewriteStillFailsRawFinalComparison() throws Exception {
        for(boolean narrative:Arrays.asList(true,false)){Object publication=publication(sources());begin(publication,true,false);AdviceService.AnswerRecord raw=answer("Supported [1] and [2].");for(AdvisoryHarness.Section section:raw.getSections())accept(publication,section);
            AdviceService.AnswerRecord changed=JsonUtils.mapper().convertValue(JsonUtils.mapper().valueToTree(raw),AdviceService.AnswerRecord.class);
            if(narrative)changed.getSections().get(1).setContent("Changed [1] and [2].");else changed.getSections().get(1).getChecks().get(0).setReason("Changed check");
            assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(publication,"finish",changed,"Explain the clause",true));}
    }
    @Test void normalizedPublishedPrefixSurvivesRepairAndCannotBeResubmitted() throws Exception {
        Object publication=publication(sources());begin(publication,true,false);AdviceService.AnswerRecord raw=answer("Supported [1] and [2].");for(AdvisoryHarness.Section section:raw.getSections())accept(publication,section);
        assertEquals(2,accepted(publication).size());String prefix=JsonUtils.write(accepted(publication));begin(publication,true,true);
        AdviceService.AnswerRecord envelope=answer("unused");envelope.setSections(Collections.emptyList());ReflectionTestUtils.invokeMethod(publication,"finish",envelope,"Explain the clause",true);
        assertEquals(prefix,JsonUtils.write(envelope.getSections()));assertTrue(errors(publication).isEmpty());
        assertThrows(BizException.class,()->accept(publication,raw.getSections().get(1)));
    }
    @Test void knownCheckOnlyAliasReportsPreciseFeedbackWithoutPromotion() throws Exception {
        List<CitationVO> sources=sources();CitationVO extra=new CitationVO();extra.setEvidenceId("[14]");extra.setSourceRole("tender");sources.add(extra);
        Object publication=publication(sources);begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Supported [1].");
        AdvisoryHarness.Section raw=answer.getSections().get(1);raw.getChecks().get(4).setCitations(Arrays.asList("[1]","[14]"));String before=JsonUtils.write(raw);
        ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
        assertEquals(1,accepted(publication).size());assertEquals(before,JsonUtils.write(raw));assertEquals(Collections.singletonList("[1]"),raw.getCitations());
        assertTrue(errors(publication).stream().allMatch(e->e.contains("section.checks[4].citations: every alias must also be in section.citations; missing aliases: [\"[14]\"]")),"KNOWN_CHECK_ONLY_MISSING_ALIAS_FEEDBACK_NOT_PRESENT");
        String repair=ReflectionTestUtils.invokeMethod(publication,"repairInstruction",before);
        assertTrue(repair.contains("section.checks[4].citations"));assertTrue(repair.contains("[14]"));
        assertTrue(repair.contains("verify the named aliases against the original sources"));assertTrue(repair.contains("Never add an alias solely to clear validation"));
        assertTrue(repair.contains("delete a required check"));assertTrue(repair.contains("missing_evidence/fail into pass"));
        assertTrue(((List<?>)ReflectionTestUtils.getField(publication,"citationMetadataCompletions")).isEmpty());
    }
    @Test void unknownNestedAliasStillFailsBeforeAnyMetadataPromotion() throws Exception {
        Object publication=publication(sources());begin(publication,false,false);AdviceService.AnswerRecord answer=answer("Supported [1].");
        AdvisoryHarness.Section raw=answer.getSections().get(1);raw.getChecks().get(4).setCitations(Arrays.asList("[1]","[999]"));String before=JsonUtils.write(raw);
        ReflectionTestUtils.invokeMethod(publication,"finish",answer,"Explain the clause",false);
        assertEquals(1,accepted(publication).size());assertEquals(before,JsonUtils.write(raw));
        assertEquals("limits",accepted(publication).get(0).getKind());assertEquals(Collections.singletonList("[1]"),raw.getCitations());
        assertFalse(errors(publication).isEmpty(),"UNKNOWN_ALIAS_MUST_HAVE_REJECTION");
        String expected="analysis: answer.checks[4].citations[1]: value must be one of [\"[1]\",\"[2]\",\"[3]\"]";
        assertTrue(errors(publication).stream().allMatch(expected::equals),"UNKNOWN_ALIAS_MUST_FAIL_CURRENT_SCHEMA_ENUM");
        assertTrue(errors(publication).stream().noneMatch(e->e.contains("missing aliases:")||e.contains("every alias must also be in section.citations")),"UNKNOWN_ALIAS_MUST_NOT_ENTER_MISSING_ALIAS_REPAIR_PATH");
        assertTrue(((List<?>)ReflectionTestUtils.getField(publication,"citationMetadataCompletions")).isEmpty());
    }
    @Test void preciseSiblingRejectionKeepsIndependentCompletionAndImmutableRepairPrefix() throws Exception {
        List<CitationVO> sources=sources();CitationVO extra=new CitationVO();extra.setEvidenceId("[14]");extra.setSourceRole("tender");sources.add(extra);
        Object publication=publication(sources);begin(publication,true,false);AdviceService.AnswerRecord answer=answer("Supported [1] and [2].");
        for(AdvisoryHarness.Section raw:answer.getSections())accept(publication,raw);
        AdvisoryHarness.Section sibling=JsonUtils.read(JsonUtils.write(answer.getSections().get(1)),AdvisoryHarness.Section.class);sibling.setId("rejected-sibling");
        sibling.getChecks().get(4).setCitations(Arrays.asList("[1]","[14]"));accept(publication,sibling);
        assertEquals(2,accepted(publication).size());assertEquals(Arrays.asList("[1]","[2]"),accepted(publication).get(1).getCitations());
        assertTrue(errors(publication).get(0).contains("missing aliases: [\"[14]\"]"),"SIBLING_MISSING_ALIAS_FEEDBACK_NOT_PRESENT");String prefix=JsonUtils.write(accepted(publication));
        begin(publication,true,true);AdviceService.AnswerRecord repair=answer("unused");repair.setSections(Collections.emptyList());
        ReflectionTestUtils.invokeMethod(publication,"finish",repair,"Explain the clause",true);assertEquals(prefix,JsonUtils.write(repair.getSections()));
        assertThrows(BizException.class,()->accept(publication,answer.getSections().get(1)));
    }
    private AdviceService.AnswerRecord answer(String text){return JsonUtils.read(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":"+JsonUtils.write(text)+",\"citations\":[\"[1]\"]}"),AdviceService.AnswerRecord.class);}
    private AdviceService.AnswerRecord flowchart(String text){AdviceService.AnswerRecord answer=answer(text);AdvisoryHarness.Section section=answer.getSections().get(1);section.setKind("flowchart");
        section.setFlowchartPlan(JsonUtils.read("{\"nodes\":[{\"id\":\"s\",\"label\":\"\",\"kind\":\"start\",\"citations\":[]},{\"id\":\"e\",\"label\":\"Supported outcome\",\"kind\":\"end\",\"citations\":[\"[2]\"]}],\"edges\":[{\"from\":\"s\",\"to\":\"e\",\"condition\":\"\",\"actor\":\"\",\"action\":\"\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[2]\"]}]}",AdvisoryHarness.GraphPlan.class));return answer;}
    private void begin(Object publication,boolean stream,boolean repair){ReflectionTestUtils.invokeMethod(publication,"beginAttempt",stream,repair);}
    private void accept(Object publication,AdvisoryHarness.Section section){ReflectionTestUtils.invokeMethod(publication,"accept",section);}
    private List<String> errors(Object publication){return (List<String>)ReflectionTestUtils.getField(publication,"errors");}
    private List<AdvisoryHarness.Section> accepted(Object publication){return (List<AdvisoryHarness.Section>)ReflectionTestUtils.getField(publication,"accepted");}
    private List<CitationVO> sources(){List<CitationVO> result=new ArrayList<>();for(int i=1;i<=3;i++){CitationVO source=new CitationVO();source.setEvidenceId("["+i+"]");source.setSourceRole("tender");result.add(source);}return result;}
    private Object publication(List<CitationVO> sources)throws Exception {
        AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);
        Class<?> context=Arrays.stream(AdviceService.class.getDeclaredClasses()).filter(type->type.getSimpleName().equals("QuestionContext")).findFirst().get();Constructor<?> qc=context.getDeclaredConstructor(String.class,List.class);qc.setAccessible(true);
        Class<?> type=Arrays.stream(AdviceService.class.getDeclaredClasses()).filter(item->item.getSimpleName().equals("Publication")).findFirst().get();Constructor<?> pc=type.getDeclaredConstructor(AdviceService.class,List.class,boolean.class,AdviceProgress.class,Runnable.class,context);pc.setAccessible(true);
        return pc.newInstance(service,sources,false,AdviceProgress.NONE,(Runnable)()->{},qc.newInstance("Explain the clause",Collections.emptyList()));
    }
    private Object publication(List<CitationVO> sources,Set<String> textAliases)throws Exception{
        AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);
        Class<?> context=Arrays.stream(AdviceService.class.getDeclaredClasses()).filter(type->type.getSimpleName().equals("QuestionContext")).findFirst().get();Constructor<?> qc=context.getDeclaredConstructor(String.class,List.class);qc.setAccessible(true);
        Class<?> type=Class.forName(AdviceService.class.getName()+"$Publication");Constructor<?> pc=type.getDeclaredConstructor(AdviceService.class,List.class,boolean.class,AdviceProgress.class,Runnable.class,Runnable.class,Runnable.class,Set.class,context);pc.setAccessible(true);
        return pc.newInstance(service,sources,false,AdviceProgress.NONE,(Runnable)()->{},(Runnable)()->{},(Runnable)()->{},textAliases,qc.newInstance("Explain the clause",Collections.emptyList()));
    }
}
