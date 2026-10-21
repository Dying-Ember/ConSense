package com.consense.service.advice;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Authored admitted-source carriers. Tests actual Publication.accept before progress.section. */
class AdviceClauseAttributionPublicationTest {
    @Test void derivedOrderRecapLosingItsGoverningQualifierNeverReachesProgress() throws Exception {
        List<CitationVO> sources=Arrays.asList(source("1","For external works only, the following rules apply.",null,false),
                source("2","Where the documents differ, ALPHA shall prevail over BETA and GAMMA; BETA shall prevail over GAMMA.",null,false));
        for(String text:Arrays.asList("For external works only, the stated priority rules apply [1][2]. Therefore: ALPHA > BETA > GAMMA [2].",
                "仅对外部工程适用以下规则 [1][2]。因此：ALPHA > BETA > GAMMA [2]。",
                "Therefore: ALPHA > BETA > GAMMA [1][2].")) {
            Fixture f=fixture(sources,text.contains("因此"));begin(f,false);accept(f,opening(f));
            accept(f,section(f,"The supplied source gives a conditional document rule [1].","sec-independent"));
            AdvisoryHarness.Section bad=section(f,text,"sec-order");bad.setCitations(Arrays.asList("[1]","[2]"));String unchanged=JsonUtils.write(bad);accept(f,bad);
            assertEquals(Arrays.asList("limits","sec-independent"),ids(f));assertEquals(2,f.published.size());
            assertTrue(errors(f).toString().contains("derived_precedence_order_lost_qualifier"));assertEquals(unchanged,JsonUtils.write(bad));
            String repair=ReflectionTestUtils.invokeMethod(f.publication,"repairInstruction","AUTHORED rejected order");
            assertTrue(repair.contains("SAME operative sentence"));assertTrue(repair.contains("replace only this shorthand"));
            assertTrue(repair.contains("precedence/substitution and cross-reference incorporation"));
        }
    }

    @Test void fullLocalConditionAndItsGoverningAliasesKeepAUsefulDerivedOrder() throws Exception {
        List<CitationVO> sources=Arrays.asList(source("1","For external works only, the following rules apply.",null,false),
                source("2","Where the documents differ, ALPHA shall prevail over BETA and GAMMA; BETA shall prevail over GAMMA.",null,false));
        for(String text:Arrays.asList("For external works only, ALPHA > BETA > GAMMA [1][2].","仅对外部工程，ALPHA > BETA > GAMMA [1][2]。",
                "ALPHA > BETA > GAMMA only for external works [1][2].","ALPHA > BETA > GAMMA，仅对外部工程适用 [1][2]。")) {
            Fixture f=fixture(sources,text.contains("仅对"));begin(f,false);accept(f,opening(f));AdvisoryHarness.Section good=section(f,text,"sec-order");good.setCitations(Arrays.asList("[1]","[2]"));accept(f,good);
            assertTrue(errors(f).isEmpty(),errors(f).toString());assertEquals(2,f.published.size());
        }
    }

    @Test void wholeSourceQuotesMathAndOrdinaryArrowsAreNotReclassifiedAsUnsupportedOrders() throws Exception {
        String literal="For external works only, ALPHA > BETA > GAMMA is the stated order of precedence.";
        for(String text:Arrays.asList("The source says: \""+literal+"\" [1]","> "+literal+"\n[1]",
                "9 > 7 > 3; x > y; ALPHA -> BETA -> GAMMA; ALPHA >= BETA; ALPHA → BETA [1].")) {
            Fixture f=fixture(Collections.singletonList(source("1",literal,null,false)),false);begin(f,false);accept(f,opening(f));accept(f,section(f,text,"sec-available"));
            assertTrue(errors(f).isEmpty(),errors(f).toString());assertEquals(2,f.published.size());
        }
        Fixture unrelated=fixture(Collections.singletonList(source("1","If x is larger than y, compare the stated values of ALPHA, BETA and GAMMA.",null,false)),false);
        begin(unrelated,false);accept(unrelated,opening(unrelated));accept(unrelated,section(unrelated,"ALPHA > BETA > GAMMA is an authored comparison [1].","sec-math"));
        assertTrue(errors(unrelated).isEmpty(),"No source priority wording means this diagnostic does not classify the expression as a document-precedence claim");
    }

    @Test void rejectedOrderRepairKeepsAcceptedUnitsAndCanReturnCompleteConditionalProse() throws Exception {
        List<CitationVO> sources=Arrays.asList(source("1","For external works only, the following rules apply.",null,false),
                source("2","Where the documents differ, ALPHA shall prevail over BETA and GAMMA; BETA shall prevail over GAMMA.",null,false),
                source("3","When clause numbers match except suffix, ALPHA takes precedence over and substitutes BETA.",null,false),
                source("4","If an included provision refers to an excluded provision, the referred provision is incorporated for operation.",null,false));
        Fixture f=fixture(sources,false);begin(f,false);accept(f,opening(f));accept(f,section(f,"The supplied source gives a conditional document rule [1].","sec-independent"));
        String prefix=JsonUtils.write(accepted(f));AdvisoryHarness.Section bad=section(f,"Therefore: ALPHA > BETA > GAMMA [1][2].","sec-order");bad.setCitations(Arrays.asList("[1]","[2]"));accept(f,bad);
        assertTrue(errors(f).toString().contains("derived_precedence_order_lost_qualifier"));begin(f,true);
        AdvisoryHarness.Section fixed=section(f,"For external works only, when the documents differ, ALPHA prevails over BETA and GAMMA; within that same scope BETA prevails over GAMMA [1][2].","sec-fixed");fixed.setCitations(Arrays.asList("[1]","[2]"));accept(f,fixed);
        assertTrue(errors(f).isEmpty());assertEquals(prefix,JsonUtils.write(accepted(f).subList(0,2)));assertEquals(Arrays.asList("limits","sec-independent","sec-fixed"),ids(f));
        for(int index=2;index<4;index++) {
            CitationVO current=sources.get(index);AdvisoryHarness.Section operation=section(f,current.getContent()+" "+current.getEvidenceId(),"sec-operation-"+index);
            operation.setCitations(Collections.singletonList(current.getEvidenceId()));for(AdvisoryHarness.Check check:operation.getChecks())if(!check.getCitations().isEmpty())check.setCitations(Collections.singletonList(current.getEvidenceId()));
            accept(f,operation);
        }
        assertTrue(errors(f).isEmpty());assertEquals(5,f.published.size());assertEquals(prefix,JsonUtils.write(accepted(f).subList(0,2)));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="consense.advice.conditionSummaryReplay",matches=".+")
    void exactPublished44ParagraphAndFinalSourceMappingAreRejectedBeforeProgress() throws Exception {
        java.nio.file.Path path=java.nio.file.Paths.get(System.getProperty("consense.advice.conditionSummaryReplay"));byte[] original=java.nio.file.Files.readAllBytes(path);
        assertEquals("7ab3c8b8d2bb84275bfb6d6f2420823fe477eea9a01828040711cd78f79a0e33",com.consense.common.Utf8.sha256(new String(original,java.nio.charset.StandardCharsets.UTF_8)));
        JsonNode history=JsonUtils.parse(new String(original,java.nio.charset.StandardCharsets.UTF_8)),message=null;
        for(JsonNode row:history.path("data"))if(row.path("id").asInt()==44&&row.path("sessionId").asText().equals("166682ce-3e72-4f01-858c-a06cba8cd61d")){assertNull(message);message=row;}
        assertNotNull(message);List<CitationVO> sources=JsonUtils.readList(JsonUtils.write(message.path("sources")),CitationVO.class);assertEquals(21,sources.size());
        List<String> paragraphs=new ArrayList<>();for(String paragraph:message.path("content").path("zhHans").asText().split("\\n\\n"))if(paragraph.contains(" > "))paragraphs.add(paragraph);
        assertEquals(1,paragraphs.size());List<String> finalAliases=new ArrayList<>();message.path("citations").forEach(alias->finalAliases.add(alias.asText()));
        Fixture f=fixture(sources,true);begin(f,false);accept(f,opening(f));
        // Exact published prose + real final aliases; checks are an explicit authored carrier, not provider section reconstruction.
        AdvisoryHarness.Section carrier=section(f,paragraphs.get(0),"sec-published44-carrier");carrier.setCitations(finalAliases);
        for(AdvisoryHarness.Check check:carrier.getChecks())if(!check.getCitations().isEmpty())check.setCitations(Collections.singletonList(finalAliases.get(0)));
        String unmodified=JsonUtils.write(carrier);accept(f,carrier);
        assertEquals(Collections.singletonList("limits"),ids(f));assertEquals(1,f.published.size());assertTrue(errors(f).toString().contains("derived_precedence_order_lost_qualifier"));
        assertTrue(errors(f).toString().contains("[16]"));assertEquals(unmodified,JsonUtils.write(carrier));assertArrayEquals(original,java.nio.file.Files.readAllBytes(path));
    }

    @Test void unknownChineseAndEnglishFormalAttributionNeverReachesProgress() throws Exception {
        for(String text:Arrays.asList("Under Clause 1 of this Specification, keep a record [1].","根据第 1 条，保留记录 [1]。","第 2 条规定保留记录 [1]。","第 4 条限定了范围 [1]。","上述规则受第1条和第2条约束 [1]。","It is not unknown whether Clause 1 requires a record [1].","Not not under Clause 1, keep a record [1].","并非不能确认第1条规定保留记录 [1]。")){
            Fixture f=fixture(Collections.singletonList(source("1","1. Keep a record.",null,false)),text.contains("条"));
            begin(f,false);accept(f,opening(f));AdvisoryHarness.Section bad=section(f,text,"sec-bad");String original=JsonUtils.write(bad);accept(f,bad);
            assertEquals(1,f.published.size(),"An unsupported formal attribution must be withheld before progress.section");
            assertTrue(errors(f).toString().contains("unsupported_formal_clause_attribution"));assertEquals(original,JsonUtils.write(bad));
        }
        for(String kind:Arrays.asList("limits","uncertainty"))for(String text:Arrays.asList("Under Clause 1, keep a record.","It is unknown whether Clause 1 requires a record.","Not under Clause 1, keep a record.","The source refers to Clause 1 of the General Conditions.")){
            Fixture f=fixture(Collections.singletonList(source("1","1. Keep a record.",null,false)),false);begin(f,false);accept(f,opening(f));
            AdvisoryHarness.Section gap=opening(f);gap.setId("sec-gap");gap.setKind(kind);gap.setContent(text);if("uncertainty".equals(kind))gap.setGaps(Collections.singletonList("other_fact"));accept(f,gap);
            assertEquals(1,f.published.size());assertTrue(errors(f).toString().contains("limits/uncertainty must be empty"));assertFalse(errors(f).toString().contains("unsupported_formal_clause_attribution"),"Existing empty-gap contract rejects this kind before it can publish text; no new owner RED is claimed");
        }
    }

    @Test void repairNamesEveryRejectedDesignationAndKeepsUtf16OffsetsWithoutMutatingClaims() throws Exception {
        Fixture f=fixture(Collections.singletonList(source("1","1. Keep a record.",null,false)),true);begin(f,false);accept(f,opening(f));
        String first="🙂 根据第 7 条，保留记录 [1]。",second="第 9 条规定保留记录 [1]。";
        AdvisoryHarness.Section a=section(f,first,"sec-first"),b=section(f,second,"sec-second");String original=JsonUtils.write(Arrays.asList(a,b));
        accept(f,a);accept(f,b);assertEquals(1,f.published.size());assertEquals(2,errors(f).size());
        String repair=ReflectionTestUtils.invokeMethod(f.publication,"repairInstruction","AUTHORED rejected candidate");
        for(String designation:Arrays.asList("第 7 条","第 9 条"))assertTrue(errors(f).stream().anyMatch(error->error.contains("designation="+JsonUtils.write(designation))),designation);
        assertTrue(repair.contains(JsonUtils.write(errors(f))));
        int start=first.indexOf("第 7 条");assertTrue(errors(f).get(0).contains("at "+start+".."+(start+"第 7 条".length())));
        assertTrue(repair.contains("offsetUnit=utf16"));assertTrue(repair.contains("deleting only this designation does not repair unsupported substance"));
        assertTrue(repair.contains("governing conditions, trigger, effect and exceptions"));assertTrue(repair.contains("independently supported rule/mechanism sections"));
        assertEquals(original,JsonUtils.write(Arrays.asList(a,b)));assertEquals(Arrays.asList("limits"),ids(f));
    }

    @Test void reviewedAdoptionAndAdmittedNativeExplicitDeclarationBothRemainUsable() throws Exception {
        JsonNode projected=JsonUtils.mapper().readTree(getClass().getResourceAsStream("/oer/canonical/authored-three-page-projection.json")).path("chunks").get(0);
        assertEquals(projected.path("clauseId"),projected.path("adoption").path("clause").path("clauseId"));
        assertEquals(projected.path("clauseHeadingLocation"),projected.path("adoption").path("clauseHeadingLocation"));assertFalse(projected.path("adoption").path("clause").has("clauseHeadingLocation"));
        for(boolean reviewed:Arrays.asList(true,false)){
            Fixture f=fixture(Collections.singletonList(source("1","Keep a record.","5",reviewed)),false);begin(f,false);accept(f,opening(f));
            accept(f,section(f,"Clause 5 requires a record [1].","sec-rule"));
            assertTrue(errors(f).isEmpty(),"Existing explicit declaration must not require a new human-review gate");assertEquals(2,f.published.size());
        }
    }

    @Test void differentExplicitNumberAndAnotherSentenceSourceCannotDischargeLocalClaim() throws Exception {
        for(boolean otherDocument:Arrays.asList(false,true)){
            CitationVO first=source("1","Keep a record.",otherDocument?null:"5",false);
            List<CitationVO> sources=new ArrayList<>();sources.add(first);if(otherDocument)sources.add(source("2","Other source.","1",false));
            Fixture f=fixture(sources,false);begin(f,false);accept(f,opening(f));AdvisoryHarness.Section bad=section(f,"Under Clause 1, keep a record [1]. Other supplied wording remains separate [2].","sec-bad");
            if(otherDocument)bad.setCitations(Arrays.asList("[1]","[2]"));else bad.setContent("Under Clause 1, keep a record [1].");
            accept(f,bad);assertEquals(1,f.published.size());assertTrue(errors(f).toString().contains("unsupported_formal_clause_attribution"));
        }
    }

    @Test void detachedMemberCannotCreateAFormalDeclaration() throws Exception {
        for(boolean missingPointer:Arrays.asList(false,true)){
            CitationVO source=source("1","Keep a record.","5",false);
            if(missingPointer)source.setCanonicalEvidence(Collections.emptyList());else source.getCanonicalEvidence().get(0).setCanonicalChunkId("another-member");
            Fixture f=fixture(Collections.singletonList(source),false);begin(f,false);accept(f,opening(f));accept(f,section(f,"Clause 5 requires a record [1].","sec-bad"));
            assertEquals(1,f.published.size());assertTrue(errors(f).toString().contains("unsupported_formal_clause_attribution"));
        }
    }

    @Test void trueExternalReferenceDeletionOperandAndOrdinaryItemOrderRemainAvailable() throws Exception {
        String cross="If the certificate is disputed, follow Clause 5 of the General Conditions. Clauses 1.08 to 1.55 of GS CEW are deleted.";
        for(String text:Arrays.asList("The excerpt refers the dispute to Clause 5 of the General Conditions; the target text remains unknown [1].","原文指向 Clause 5 of the General Conditions，目标全文仍未知 [1]。","The excerpt deletes Clauses 1.08 to 1.55 of GS CEW [1].","原文删除 Clauses 1.08 to 1.55 of GS CEW [1]。","The first supplied paragraph concerns records [1].","第1条路径涉及记录 [1]。","It is unknown whether Clause 5 requires a record [1].","It is unclear whether Clause 5 requires a record [1].","We cannot confirm whether Clause 5 requires a record [1].","The source does not establish whether Clause 5 requires a record [1].","Not under Clause 5, keep a record [1].","尚未确认第5条规定保留记录 [1]。","并非根据第5条，保留记录 [1]。","Clause 5 does not require a record [1].","第5条未规定保留记录 [1]。")){
            Fixture f=fixture(Collections.singletonList(source("1",cross,null,false)),text.contains("原文")||text.contains("路径")||text.contains("条"));begin(f,false);accept(f,opening(f));accept(f,section(f,text,"sec-rule"));
            assertTrue(errors(f).isEmpty(),text);assertEquals(2,f.published.size(),text);
        }
    }

    @Test void completeLiteralQuotesSurviveButQuotedTokenCannotAuthorizeUnquotedPredicate() throws Exception {
        for(String text:Arrays.asList("The source says: \"Clause 5 requires a record.\" [1]","> Clause 5 requires a record.\n[1]","原文写明：“Clause 5 requires a record.” [1]","The supplied source states Clause 5 requires a record [1].","The supplied source states Clause 5 requires a record\t[1]\t.","The supplied source states Clause 5 requires a record[1].")){
            Fixture f=fixture(Collections.singletonList(source("1","Clause 5 requires a record.",null,false)),text.contains("原文"));begin(f,false);accept(f,opening(f));accept(f,section(f,text,"sec-quote"));
            assertTrue(errors(f).isEmpty(),text);assertEquals(2,f.published.size());
        }
        Fixture referring=fixture(Collections.singletonList(source("1","Under Clause 5, keep a record.",null,false)),false);begin(referring,false);accept(referring,opening(referring));accept(referring,section(referring,"Under Clause 5, keep a record [1].","sec-reference"));assertTrue(errors(referring).isEmpty());assertEquals(2,referring.published.size());
        for(String text:Arrays.asList("The supplied source states Clause 5 requires  a record [1].","\"Clause 5\" requires notice within 14 days [1].","“Clause 5”要求14天内通知 [1]。","`Clause 5` requires notice within 14 days [1].","「Clause 5」 requires notice within 14 days [1].","Under \"Clause 5\", give notice [1].","The source says: \"Clause 5 requires notice within 14 days.\" [1]")){
            Fixture f=fixture(Collections.singletonList(source("1",text.startsWith("The supplied source states")?"Clause 5 requires a record.":"Follow Clause 5 of the General Conditions.",null,false)),text.contains("要求"));begin(f,false);accept(f,opening(f));accept(f,section(f,text,"sec-bad"));
            assertEquals(1,f.published.size(),text);assertTrue(errors(f).toString().contains("unsupported_formal_clause_attribution"));
        }
    }

    @Test void graphNodeAndEdgeFormalAttributionIsRejectedBeforeProgress() throws Exception {
        for(boolean edgeClaim:Arrays.asList(false,true))for(boolean chinese:Arrays.asList(false,true)){
            Fixture f=fixture(Collections.singletonList(source("1","1. Keep a record.",null,false)),chinese);begin(f,false);accept(f,opening(f));AdvisoryHarness.Section graph=section(f,chinese?"原文涉及保留记录 [1]。":"The source concerns keeping a record [1].","sec-graph");graph.setKind("flowchart");
            graph.setFlowchartPlan(JsonUtils.read("{\"nodes\":[{\"id\":\"start-rule\",\"label\":\"\",\"kind\":\"start\",\"citations\":[]},{\"id\":\"finish-rule\",\"label\":\"Keep a record\",\"kind\":\"end\",\"citations\":[\"[1]\"]}],\"edges\":[{\"from\":\"start-rule\",\"to\":\"finish-rule\",\"condition\":\"\",\"actor\":\"\",\"action\":\"\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]}]}",AdvisoryHarness.GraphPlan.class));
            Fixture control=fixture(Collections.singletonList(source("1","1. Keep a record.",null,false)),chinese);begin(control,false);accept(control,opening(control));
            accept(control,JsonUtils.mapper().convertValue(graph,AdvisoryHarness.Section.class));assertTrue(errors(control).isEmpty(),"The same neutral graph must pass all existing structural and publication gates");assertEquals(2,control.published.size());
            if(edgeClaim)graph.getFlowchartPlan().getEdges().get(0).setCondition(chinese?"根据第1条，保留记录":"According to Clause 1, keep a record");else graph.getFlowchartPlan().getNodes().get(1).setLabel(chinese?"第1条：记录义务":"Clause 1: record obligation");
            accept(f,graph);assertEquals(1,f.published.size());assertTrue(errors(f).toString().contains(edgeClaim?"edges[0].condition":"nodes[1].label"));
        }
    }

    @Test void unsupportedOwnershipDoesNotDiscardIndependentSupportedSection() throws Exception {
        Fixture f=fixture(Collections.singletonList(source("1","1. Keep a record.",null,false)),false);begin(f,false);accept(f,opening(f));
        accept(f,section(f,"Clause 1 requires a record [1].","sec-bad"));accept(f,section(f,"The supplied item concerns keeping a record [1].","sec-independent"));
        assertEquals(2,f.published.size());assertEquals(Arrays.asList("limits","sec-independent"),ids(f));assertTrue(errors(f).toString().contains("unsupported_formal_clause_attribution"));
    }

    @Test void actualOneRepairReceivesOwnerErrorAndDoesNotPermitThirdGeneration() throws Exception {
        for(boolean correct:Arrays.asList(false,true)){
            AdviceResponsesTransportTest.Fixture transport=new AdviceResponsesTransportTest.Fixture();
            ObjectNode bad=valid("Under Clause 1, keep a record [1]."),replacement=valid("The item beginning '1.' requires a record [1].");String raw=bad.toString();
            transport.http.counter(1000);transport.http.generation(raw);transport.http.counter(1500);transport.http.generation(correct?replacement.toString():raw);
            AdviceService service=new AdviceService(null,null,null,null,transport.props,null,null,null,null,null,null);
            Fixture f=fixture(service,Collections.singletonList(source("1","1. Keep a record.",null,false)),false,AdviceProgress.NONE);
            AdviceService.AnswerRecord result=ReflectionTestUtils.invokeMethod(service,"completeAdvice",AdvisoryHarness.system("AUTHORED rules"),"AUTHORED current evidence",null,transport.operation(),null,AdviceResponsesTransportTest.SOURCE,(Runnable)()->{},f.publication,"Explain the uploaded clause");
            assertEquals(4,transport.http.bodies.size(),"Exactly two admitted attempts, including fake input counts");
            String user=JsonUtils.parse(transport.http.bodies.get(3)).path("input").get(0).path("content").asText();
            assertTrue(user.contains("unsupported_formal_clause_attribution"));assertTrue(user.contains(JsonUtils.write(raw)));assertTrue(user.contains("retain source-supported correct content"));
            JsonNode feedback=JsonUtils.parse(user.substring(user.indexOf("Application rejected: ")+"Application rejected: ".length(),user.indexOf(". Preserve the original question")));
            assertTrue(feedback.isArray());assertTrue(feedback.get(0).asText().contains("designation="+JsonUtils.write("Clause 1")));assertTrue(feedback.get(0).asText().contains("offsetUnit=utf16"));
            assertTrue(user.contains("deleting only this designation does not repair unsupported substance"));
            String instructions=JsonUtils.parse(transport.http.bodies.get(3)).path("instructions").asText();
            assertTrue(instructions.contains("Use one independently checkable rule/mechanism per analysis section"));
            assertTrue(instructions.contains("State that rule once as a complete condition-to-effect explanation, including its governing conditions, actual trigger subject, applicability, effect, exceptions and supporting continuation."));
            assertEquals(correct?2:1,accepted(f).size());assertEquals(correct?2:1,ids(f).size());assertEquals(2,((List<?>)ReflectionTestUtils.getField(f.publication,"attempts")).size());assertNotNull(result);
        }
    }

    @Test void ownerRepairRetainsImmutableStreamedPrefixAndOnlyAddsUnpublishedUnit() throws Exception {
        Fixture f=fixture(Collections.singletonList(source("1","1. Keep a record.",null,false)),false);begin(f,false);accept(f,opening(f));accept(f,section(f,"The supplied item concerns keeping a record [1].","sec-independent"));
        String prefix=JsonUtils.write(accepted(f));accept(f,section(f,"Clause 1 requires a record [1].","sec-bad"));assertEquals(2,f.published.size());
        String repair=ReflectionTestUtils.invokeMethod(f.publication,"repairInstruction","AUTHORED raw rejected candidate");assertTrue(repair.contains("unsupported_formal_clause_attribution"));assertTrue(repair.contains("omit every one of these IDs"));
        begin(f,true);accept(f,section(f,"The item beginning '1.' requires a record [1].","sec-repaired"));
        assertEquals(prefix,JsonUtils.write(accepted(f).subList(0,2)));assertEquals(3,f.published.size());
        BizException repeated=assertThrows(BizException.class,()->accept(f,opening(f)));assertTrue(repeated.getMessage().contains("accepted section ID"));
    }

    private static final class Fixture {Object publication;boolean chinese;List<String> published=new ArrayList<>();}
    private Fixture fixture(List<CitationVO> sources,boolean chinese)throws Exception {
        Fixture f=new Fixture();AdviceProgress progress=new AdviceProgress(){public boolean streaming(){return true;}public void section(String text,List<String> refs,List<CitationVO> cited){f.published.add(text);}};
        return fixture(new AdviceService(null,null,null,null,null,null,null,null,null,null,null),sources,chinese,progress,f);
    }
    private Fixture fixture(AdviceService service,List<CitationVO> sources,boolean chinese,AdviceProgress progress)throws Exception{return fixture(service,sources,chinese,progress,new Fixture());}
    private Fixture fixture(AdviceService service,List<CitationVO> sources,boolean chinese,AdviceProgress progress,Fixture f)throws Exception {
        f.chinese=chinese;Class<?> context=Class.forName(AdviceService.class.getName()+"$QuestionContext");Constructor<?> qc=context.getDeclaredConstructor(String.class,List.class);qc.setAccessible(true);
        Class<?> type=Class.forName(AdviceService.class.getName()+"$Publication");Constructor<?> pc=type.getDeclaredConstructor(AdviceService.class,List.class,boolean.class,AdviceProgress.class,Runnable.class,context);pc.setAccessible(true);
        f.publication=pc.newInstance(service,sources,false,progress,(Runnable)()->{},qc.newInstance(chinese?"解释已提供的条文":"Explain the uploaded clause",Collections.emptyList()));return f;
    }
    private void begin(Fixture f,boolean repair){ReflectionTestUtils.invokeMethod(f.publication,"beginAttempt",true,repair);}
    private void accept(Fixture f,AdvisoryHarness.Section section){ReflectionTestUtils.invokeMethod(f.publication,"accept",section);}
    private List<String> errors(Fixture f){return (List<String>)ReflectionTestUtils.getField(f.publication,"errors");}
    private List<AdvisoryHarness.Section> accepted(Fixture f){return (List<AdvisoryHarness.Section>)ReflectionTestUtils.getField(f.publication,"accepted");}
    private List<String> ids(Fixture f){List<String> ids=new ArrayList<>();for(AdvisoryHarness.Section section:accepted(f))ids.add(section.getId());return ids;}
    private ObjectNode valid(String text){return (ObjectNode)JsonUtils.parse(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":"+JsonUtils.write(text)+",\"citations\":[\"[1]\"]}"));}
    private AdvisoryHarness.Section opening(Fixture f){AdviceService.AnswerRecord answer=JsonUtils.mapper().convertValue(valid("Supported wording [1]."),AdviceService.AnswerRecord.class);AdvisoryHarness.Section section=answer.getSections().get(0);section.setLanguage(f.chinese?"zhHans":"en");return section;}
    private AdvisoryHarness.Section section(Fixture f,String text,String id){AdviceService.AnswerRecord answer=JsonUtils.mapper().convertValue(valid(text),AdviceService.AnswerRecord.class);AdvisoryHarness.Section section=answer.getSections().get(1);section.setId(id);section.setLanguage(f.chinese?"zhHans":"en");return section;}
    private CitationVO source(String doc,String literal,String clause,boolean reviewed){
        CitationVO source=new CitationVO("Authored source",null,"body/authored",literal,1,"["+doc+"]",doc,"authored-source-"+doc,"tender",true);
        ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("status","complete_observed_unit");ObjectNode member=unit.putArray("members").addObject();member.put("id","member-"+doc);member.put("unitId","unit-"+doc);
        ObjectNode part=member.putArray("parts").addObject();part.put("text",literal);part.put("anchor","body/authored");part.putObject("readingContext").put("ownerStatus","unknown").put("semanticStatus","unverified");
        EvidencePointer pointer=new EvidencePointer();pointer.setDocumentId(Long.valueOf(doc));pointer.setCanonicalChunkId("member-"+doc);pointer.setUnitId("unit-"+doc);pointer.setParts(member.path("parts").deepCopy());
        if(clause!=null){if(reviewed){ObjectNode adoption=JsonUtils.mapper().createObjectNode();adoption.put("reviewStatus","explicit_source_reviewed_selection");adoption.putArray("reviewedAttributes").add("clauseHierarchy");adoption.putObject("clause").put("clauseId",clause).put("headingUnitId","heading-"+clause);adoption.put("clauseHeadingLocation","body/heading-"+clause);pointer.setAdoption(adoption);}else{member.put("clauseId",clause);member.put("clauseHeadingLocation","body/heading-"+clause);}}
        source.setCanonicalSourceUnit(unit);source.setCanonicalEvidence(Collections.singletonList(pointer));return source;
    }
}
