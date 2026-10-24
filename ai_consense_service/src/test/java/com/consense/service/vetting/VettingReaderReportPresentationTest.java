package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import com.consense.web.dto.CanonicalEvidenceDtos;
import com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO;
import com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic reader projection contracts; no official sample answer supplies expected content. */
class VettingReaderReportPresentationTest {
    private Object readerMethod(String name,Class<?>[] types,Object... arguments) throws Exception {
        try {java.lang.reflect.Method method=VettingReportPresentation.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,arguments);}
        catch(NoSuchMethodException missing){throw new AssertionError("READER_PROJECTION_MISSING: "+name,missing);}
    }
    @SuppressWarnings("unchecked") private List<VettingReportPresentation.Table> readerComments(List<FindingVO> findings,CoverageVO coverage) throws Exception {return (List<VettingReportPresentation.Table>)readerMethod("readerComments",new Class<?>[]{List.class,CoverageVO.class},findings,coverage);}
    private String readerLocation(String value) throws Exception {return (String)readerMethod("readerLocation",new Class<?>[]{String.class},value);}
    private String readerNarrative(String value,FindingVO finding) throws Exception {return (String)readerMethod("readerNarrative",new Class<?>[]{String.class,FindingVO.class},value,finding);}
    private FindingVO candidate() throws Exception {
        java.lang.reflect.Method method=VettingSampleReportAlignmentTest.class.getDeclaredMethod("finding",String.class,String.class,String.class,String.class);
        method.setAccessible(true);
        FindingVO finding=(FindingVO)method.invoke(new VettingSampleReportAlignmentTest(),"SCT","synthetic-project.docx","Clause 99.8","Not located");
        finding.setCode("VT-SYNTHETIC");finding.setStatus("OPEN");finding.setSeverity("high");
        finding.setBody(LocalizedText.of("Candidate alleges Clause 99.8 applies; attribution is unconfirmed.","Candidate alleges Clause 99.8 applies; attribution is unconfirmed.","Candidate alleges Clause 99.8 applies; attribution is unconfirmed."));
        finding.setImpact(LocalizedText.of("FULL IMPACT END","FULL IMPACT END","FULL IMPACT END"));
        finding.setSuggestion(LocalizedText.of("FULL SUGGESTION END","FULL SUGGESTION END","FULL SUGGESTION END"));
        finding.setExpected("FULL EXPECTED BASIS END");
        finding.getEvidence().get(0).setAnchor("body/4/paragraph");
        finding.getEvidence().get(0).setQuote("The synthetic source shall preserve every qualifying condition. QUOTE-END");
        finding.getEvidence().get(0).setPacketId("topic-99-packet-0-input-opaque-hash");
        finding.getEvidence().get(0).setPacketSourceSnapshotSha256("OPAQUE-SNAPSHOT-HASH");
        return finding;
    }
    private Project project() {Project p=new Project();p.setId("synthetic-reader-project");p.setContractNo("SYNTHETIC-1");p.setNameEn("Synthetic reader test");return p;}
    private String compact(String value){return value.replaceAll("\\s+","");}
    private String wordText(byte[] bytes) throws Exception {
        try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text=new StringBuilder();
            for(IBodyElement element:document.getBodyElements())text.append(element instanceof XWPFParagraph?((XWPFParagraph)element).getText():element instanceof XWPFTable?((XWPFTable)element).getText():"").append('\n');
            return text.toString();
        }
    }
    private String pdfText(byte[] bytes) throws Exception {try(PDDocument document=PDDocument.load(bytes)){return new PDFTextStripper().getText(document);}}

    @Test void readerKeepsLiteralCandidateMeaningAndDoesNotPromoteRawBodyPositionToClause() throws Exception {
        FindingVO finding=candidate();String before=JsonUtils.write(finding),auditBefore=JsonUtils.write(VettingReportPresentation.comments(Collections.singletonList(finding),null));
        VettingReportPresentation.Table reader=readerComments(Collections.singletonList(finding),null).get(0);
        assertEquals(5,reader.getHeadings().size());assertEquals(1,reader.getRows().size());
        assertTrue(reader.getRows().get(0).get(2).contains("Body paragraph 4"));
        assertTrue(reader.getRows().get(0).get(2).contains("formal clause ownership unconfirmed"));
        assertFalse(reader.getRows().get(0).get(2).contains("Clause 4"));assertFalse(reader.getRows().get(0).get(2).contains("Clause 99.8"));
        String comment=reader.getRows().get(0).get(3);
        for(String literal:Arrays.asList("Review suggestion (not confirmed)",finding.getBody().pick("en"),"FULL IMPACT END","FULL SUGGESTION END","FULL EXPECTED BASIS END"))assertTrue(comment.contains(literal));
        for(String technical:Arrays.asList("Issue class:","Sub-dimension:","Checklist connection:","Finding source packet snapshot:"))assertFalse(comment.contains(technical));
        assertEquals(before,JsonUtils.write(finding));
        assertEquals(auditBefore,JsonUtils.write(VettingReportPresentation.comments(Collections.singletonList(finding),null)));
        assertTrue(VettingReportPresentation.comments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(2).contains("Clause 99.8"),"Historical JSON/audit projection stays unchanged");
    }
    @Test void allExistingHumanDecisionsAndTimestampsRemainLiteralInReaderRemarks() throws Exception {
        for(Boolean decision:Arrays.asList(Boolean.TRUE,Boolean.FALSE,null)) {
            FindingVO finding=candidate();finding.setStatus("HANDLED");finding.setReviewRemarks("HUMAN REPLY END\nKeep the source exception.");finding.setActionTaken("ACTUAL ACTION END");
            finding.setAddendumRequired(decision);finding.setReviewUpdatedAt(Instant.parse("2026-10-20T00:00:00Z"));String before=JsonUtils.write(finding);
            String remarks=readerComments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(4);
            assertTrue(remarks.contains("HUMAN REPLY END\nKeep the source exception."));assertTrue(remarks.contains("ACTUAL ACTION END"));assertTrue(remarks.contains("2026-10-20T00:00:00Z"));
            assertTrue(remarks.contains("Review status: Handled"));
            if(decision!=null)assertTrue(remarks.contains(decision?"Include in tender addendum: Required":"Include in tender addendum: Not required"));
            else assertFalse(remarks.contains("Include in tender addendum:"),"An absent human decision is not invented");
            assertEquals(before,JsonUtils.write(finding));
        }
    }
    @Test void readerWordGivesTranslatedHumanRemarksQuarterWidthWithoutChangingColumnsOrSavedMeaning() throws Exception {
        FindingVO finding=candidate();finding.setReviewRemarks("English translation: The same payment cannot be due on both day 14 and day 21. Refer to QS; no real-project adoption is confirmed.");finding.setActionTaken("English translation: Retain both original texts and source locations until QS confirms priority.");String before=JsonUtils.write(finding);
        VettingReportPresentation.Table model=readerComments(Collections.singletonList(finding),null).get(0);
        assertArrayEquals(new int[]{7,10,15,43,25},model.getWidths());assertEquals(100,Arrays.stream(model.getWidths()).sum());
        assertEquals(VettingReportPresentation.comments(Collections.singletonList(finding),null).get(0).getHeadings(),model.getHeadings());
        assertArrayEquals(model.getWidths(),readerComments(Collections.emptyList(),null).get(0).getWidths());
        try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(new VettingReportWriter().docx(project(),Collections.singletonList(finding),null,"en")))){
            XWPFTable table=word.getTables().stream().filter(t->t.getRow(0).getTableCells().stream().anyMatch(c->c.getText().contains("Remarks by Project Team"))).findFirst().orElseThrow(AssertionError::new);
            XWPFTableRow row=table.getRow(2);assertEquals(5,row.getTableCells().size());
            assertEquals(Integer.toString(9986*25/100),row.getCell(4).getCTTc().getTcPr().getTcW().getW().toString());
            assertEquals(Integer.toString(9986*43/100),row.getCell(3).getCTTc().getTcPr().getTcW().getW().toString());
            assertTrue(row.getCell(4).getText().contains(finding.getReviewRemarks()));assertTrue(row.getCell(4).getText().contains(finding.getActionTaken()));
        }
        assertEquals(before,JsonUtils.write(finding));
    }
    @Test void unknownAndUnsupportedLocationsStayUnknownWithoutInventedPhysicalPages() throws Exception {
        for(String raw:Arrays.asList("body/4/table/row/1","/word/header3.xml/w:p[1]","")) {
            String location=readerLocation(raw);
            assertFalse(location.contains("body/"));assertFalse(location.contains("header3.xml"));assertTrue(location.toLowerCase(Locale.ROOT).contains("unconfirmed"));
            assertFalse(location.contains("Clause 4"));assertFalse(location.contains("Page 4"));
        }
    }
    @Test void bothFormatsShareTheSameReaderMeaningAndKeepFullVerbatimEvidence() throws Exception {
        FindingVO finding=candidate();finding.setReviewRemarks("LITERAL HUMAN REVIEW END");finding.setActionTaken("LITERAL ACTION END");finding.setAddendumRequired(false);
        String before=JsonUtils.write(finding);VettingReportWriter writer=new VettingReportWriter();
        for(String text:Arrays.asList(wordText(writer.docx(project(),Collections.singletonList(finding),null,"en")),pdfText(writer.pdf(project(),Collections.singletonList(finding),null,"en")))) {
            String normalized=compact(text);
            for(String technical:Arrays.asList("topic-99-packet-0-input-opaque-hash","OPAQUE-SNAPSHOT-HASH","Finding source packet snapshot:","Global call status","Report snapshot:","body/4/paragraph"))assertFalse(normalized.contains(compact(technical)),"READER_AUDIT_FIELD_LEAK: "+technical);
            for(String literal:Arrays.asList(finding.getBody().pick("en"),"FULL IMPACT END","FULL SUGGESTION END","FULL EXPECTED BASIS END","LITERAL HUMAN REVIEW END","LITERAL ACTION END","Include in tender addendum: Not required",finding.getEvidence().get(0).getQuote(),"formal clause ownership unconfirmed","formal clause numbers stated below are unconfirmed"))assertTrue(normalized.contains(compact(literal)),literal);
        }
        assertEquals(before,JsonUtils.write(finding));
    }
    @Test void absentHumanFieldsDoNotCreateThreeRepeatedPlaceholderLines() throws Exception {
        FindingVO finding=candidate();finding.setReviewRemarks(null);finding.setActionTaken(null);finding.setAddendumRequired(null);
        String remarks=readerComments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(4);
        assertEquals("Review status: Awaiting project-team review",remarks);
        assertFalse(remarks.contains("Not recorded"));assertFalse(remarks.contains("Undecided"));
    }
    @Test void onlyExactKnownCanonicalIdentitiesBecomeUnconfirmedHumanPositions() throws Exception {
        FindingVO finding=candidate();CanonicalEvidenceDtos.EvidencePointer pointer=new CanonicalEvidenceDtos.EvidencePointer();pointer.setCanonicalChunkId("canonical:KNOWN-RECORD");
        finding.getEvidence().get(0).setCanonicalEvidence(pointer);String before=JsonUtils.write(finding);
        String original="canonical:KNOWN-RECORD states TWO; body/4 states THREE; canonical:OTHER-RECORD is unverified; canonical:KNOWN-RECORD-SUFFIX stays distinct; body/4/paragraph/child stays distinct.";
        String shown=readerNarrative(original,finding);
        assertFalse(shown.contains("canonical:KNOWN-RECORD states"));assertFalse(shown.contains("body/4 states"));
        assertTrue(shown.contains("TWO"));assertTrue(shown.contains("THREE"));assertTrue(shown.contains("canonical:OTHER-RECORD is unverified"));assertTrue(shown.contains("canonical:KNOWN-RECORD-SUFFIX stays distinct"));assertTrue(shown.contains("Original source location unverified stays distinct."));
        assertFalse(shown.contains("Body paragraph 4 (formal clause ownership unconfirmed) stays distinct."),"A longer unbound anchor must not inherit the known prefix location");
        assertTrue(shown.contains("Body paragraph 4 (formal clause ownership unconfirmed)"));assertEquals(before,JsonUtils.write(finding));
    }
    @Test void completeTransportOrParsingDoesNotBecomeACompleteSubstantiveCountercheck() throws Exception {
        DocumentCoverageVO document=new DocumentCoverageVO("doc","SCT","synthetic-project.docx","PARSED",100,100,5,5,Collections.emptyList());
        CoverageVO coverage=new CoverageVO(1,1,Collections.singletonList(document),Collections.emptyList());
        String rawBefore=JsonUtils.write(coverage);
        VettingReportPresentation.Table reader=readerComments(Collections.emptyList(),coverage).get(0);
        String summary=reader.getRows().get(0).get(3);
        assertTrue(summary.contains("Full source and substantive countercheck have not been established"));
        assertFalse(summary.contains("full_countercheck_unproven"));assertFalse(summary.contains("no issues"));assertEquals(rawBefore,JsonUtils.write(coverage));
        FindingVO finding=candidate();finding.getEvidence().get(0).setLocated(false);
        String word=wordText(new VettingReportWriter().docx(project(),Collections.singletonList(finding),null,"en"));
        assertTrue(word.contains("Evidence awaiting location"));assertFalse(word.contains("Evidence partly located"));
    }
    @Test void everySourcePositionAndExistingReviewedClauseIsRetainedWithoutUpgradingItsUnknownNeighbour() throws Exception {
        FindingVO finding=candidate();FindingEvidence first=finding.getEvidence().get(0);first.setPageNo("P7");
        FindingEvidence second=JsonUtils.read(JsonUtils.write(first),FindingEvidence.class);second.setAnchor("body/6/paragraph");second.setPageNo("P8");second.setClauseId("SCT7.2");
        FindingEvidence third=JsonUtils.read(JsonUtils.write(first),FindingEvidence.class);third.setAnchor("body/9/paragraph");third.setPageNo("P10");
        CanonicalEvidenceDtos.EvidencePointer reviewed=new CanonicalEvidenceDtos.EvidencePointer();reviewed.setAdoption(JsonUtils.parse("{\"reviewStatus\":\"explicit_source_reviewed_selection\",\"clause\":{\"clauseId\":\"SCT8\"}}"));third.setCanonicalEvidence(reviewed);
        finding.setEvidence(Arrays.asList(first,second,third));String before=JsonUtils.write(finding);
        List<String> row=readerComments(Collections.singletonList(finding),null).get(0).getRows().get(0);String position=row.get(2);
        for(String literal:Arrays.asList("Body paragraph 4 (formal clause ownership unconfirmed)","Recorded source clause: SCT7.2; Body paragraph 6","Recorded source clause: SCT8; Body paragraph 9"))assertTrue(position.contains(literal),literal);
        assertEquals("P7 / P8 / P10",row.get(1));assertFalse(position.contains("Clause 99.8"));assertEquals(before,JsonUtils.write(finding));
    }
    @Test void assessmentQuotationsMissingMaterialAndBusinessCheckStatesSurviveBothFormats() throws Exception {
        FindingVO finding=candidate();FindingEvidence evidence=JsonUtils.read(JsonUtils.write(finding.getEvidence().get(0)),FindingEvidence.class);evidence.setAnchor("body/9/paragraph");evidence.setQuote("FULL ASSESSMENT QUALIFYING SOURCE END");
        SemanticPacketVO packet=new SemanticPacketVO();packet.setCheckState("insufficient_evidence");packet.setVerifiedAssessments(Collections.singletonList(new VerifiedAssessmentVO("insufficient_context","FULL ASSESSMENT TITLE END","FULL ASSESSMENT COMMENT END at body/9/paragraph",Collections.singletonList(evidence))));
        packet.setFixedBaselineEvidence(Collections.singletonList(JsonUtils.parse("{\"projectApplicability\":\"unknown\",\"qualifiersComplete\":\"unknown\",\"sourceUnit\":{\"missingMemberIds\":[\"required-a\"]}}")));
        SemanticTopicVO topic=new SemanticTopicVO();topic.setTopicIndex(3);topic.setTopic("Missing attachment and conditions");topic.setCheckState("insufficient_evidence");topic.setPendingSourceRequestCount(2);topic.setBudgetDroppedGroups(1);topic.setPacketAudits(Collections.singletonList(packet));
        SemanticSourceRequestVO request=new SemanticSourceRequestVO();request.setMissingChunkIds(Arrays.asList("a","b"));request.setUnknownReason("The referenced source attachment is unavailable.");topic.setSourceRequests(Collections.singletonList(request));
        CoverageVO coverage=new CoverageVO(1,1,Collections.singletonList(new DocumentCoverageVO("doc","SCT","synthetic-project.docx","PARSED",10,10,1,1,Collections.emptyList())),Collections.emptyList());coverage.setSemanticTopics(Collections.singletonList(topic));
        VettingJobVO job=new VettingJobVO();job.setCoverage(coverage);String before=JsonUtils.write(job);VettingReportWriter writer=new VettingReportWriter();
        for(String text:Arrays.asList(wordText(writer.docx(project(),Collections.singletonList(finding),job,"en")),pdfText(writer.pdf(project(),Collections.singletonList(finding),job,"en")))) {
            String normalized=compact(text);
            for(String literal:Arrays.asList("Source evidence or context is insufficient","FULL ASSESSMENT TITLE END","FULL ASSESSMENT COMMENT END",evidence.getQuote(),"Body paragraph 9","pending source requests 2","source groups omitted 1","Required source excerpts missing from a recorded request: 2","The referenced source attachment is unavailable.","Required parts of the reference source are missing","Reference applicability: unknown"))assertTrue(normalized.contains(compact(literal)),literal);
            assertFalse(text.contains("body/9/paragraph"));assertFalse(text.contains("Global call status"));
        }
        assertEquals(before,JsonUtils.write(job));
    }
    @Test void exactDuplicateNarrativeIsShownOnceWhileDistinctBusinessMeaningsRemainLiteral() throws Exception {
        FindingVO finding=candidate();finding.setSource("model");finding.setBody(LocalizedText.same("Exact repeated statement."));finding.setBusinessDetails(new BusinessDetailsVO("technical_code","Exact repeated statement.",Collections.singletonList("Distinct pending fact END"),"amend",Collections.emptyList()));
        String before=JsonUtils.write(finding),comment=readerComments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(3);
        assertEquals(1,comment.split(java.util.regex.Pattern.quote("Exact repeated statement."),-1).length-1);
        for(String literal:Arrays.asList("Original model candidate; formal clause numbers stated below are unconfirmed","FULL IMPACT END","FULL EXPECTED BASIS END","FULL SUGGESTION END","Distinct pending fact END"))assertTrue(comment.contains(literal),literal);
        assertEquals(before,JsonUtils.write(finding));
    }
    @Test void existingBusinessActionAndValidChecklistLinkAreFallbacksWithoutDuplicateAdvice() throws Exception {
        FindingVO finding=candidate();finding.setSuggestion(null);finding.setBusinessDetails(new BusinessDetailsVO("technical_code","Distinct basis",Collections.emptyList(),"seek_clarification",Arrays.asList("APP-I-026","NOT-A-CATALOG-ROW")));
        String before=JsonUtils.write(finding),comment=readerComments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(3);
        assertTrue(comment.contains("Suggested action: Seek clarification and review the source before deciding any amendment."));
        assertTrue(comment.contains("Related Appendix I countercheck: 8. PRE.B6.590 (row APP-I-026)"));assertFalse(comment.contains("NOT-A-CATALOG-ROW"));assertFalse(comment.contains("CK-019"));
        assertEquals(before,JsonUtils.write(finding));finding.setSuggestion(LocalizedText.same("The existing full recommendation END"));
        comment=readerComments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(3);
        assertTrue(comment.contains("The existing full recommendation END"));assertFalse(comment.contains("Seek clarification and review the source before deciding any amendment."));
    }
    @Test void readerChecklistExplainsExcludedAutomationWithoutChangingManualOrPublicAuditValues() throws Exception {
        VettingChecklistCatalog.Item item=VettingChecklistCatalog.items().stream().filter(i->"APP-I-046".equals(i.getId())).findFirst().orElseThrow(AssertionError::new);
        List<VettingChecklistCatalog.Result> manual=Collections.singletonList(new VettingChecklistCatalog.Result(item.getId(),"not_fulfilled","HUMAN PRICING REASON END"));
        AutomaticItemVO automaticItem=new AutomaticItemVO(item.getId(),"out_of_scope",item.getScopeNote(),Collections.emptyList());
        List<AutomaticGroupVO> automatic=Collections.singletonList(new AutomaticGroupVO("test","out_of_scope","","",false,Collections.singletonList(item.getId()),Collections.singletonList(automaticItem)));
        String before=JsonUtils.write(Arrays.asList(manual,automatic)),auditBefore=JsonUtils.write(VettingReportPresentation.checklist(manual,automatic));
        String reader=JsonUtils.write(VettingReportPresentation.readerChecklist(manual,automatic));
        assertTrue(reader.contains("Outside automated review scope; no automatic countercheck performed"));assertFalse(reader.contains("Unknown status: out_of_scope"));
        assertTrue(reader.contains("Checked and not fulfilled"));assertTrue(reader.contains("HUMAN PRICING REASON END"));assertFalse(reader.contains(item.getScopeNote()));
        assertTrue(auditBefore.contains("Unknown status: out_of_scope"));assertTrue(auditBefore.contains(item.getScopeNote()));
        assertEquals(auditBefore,JsonUtils.write(VettingReportPresentation.checklist(manual,automatic)));assertEquals(before,JsonUtils.write(Arrays.asList(manual,automatic)));
        automaticItem.setReason(item.getScopeNote()+" CUSTOM AUTOMATIC REASON END");
        reader=JsonUtils.write(VettingReportPresentation.readerChecklist(manual,automatic));assertTrue(reader.contains(item.getScopeNote()+" CUSTOM AUTOMATIC REASON END"),"Only an exactly duplicated catalog reason may be omitted");
        VettingJobVO job=new VettingJobVO();job.setStatus("COMPLETED");VettingReportWriter writer=new VettingReportWriter();
        for(String text:Arrays.asList(wordText(writer.docx(project(),Collections.emptyList(),job,"en",manual)),pdfText(writer.pdf(project(),Collections.emptyList(),job,"en",manual)))) {
            String normalized=compact(text);assertTrue(normalized.contains(compact("Outside automated review scope; no automatic countercheck performed")));assertFalse(normalized.contains("Unknownstatus:out_of_scope"));
            assertTrue(normalized.contains("HUMANPRICINGREASONEND"));assertTrue(normalized.contains("Checkedandnotfulfilled"));
        }
    }
    @Test void unknownNarrativeBodyPositionsNeverBorrowLocatedEvidenceAndQuotesStayVerbatim() throws Exception {
        FindingVO finding=candidate();FindingEvidence source=finding.getEvidence().get(0);source.setAnchor("body/8/paragraph");
        String narrative="Distinct model meaning at body/3; exact source body/8; extended body/8/paragraph/child.";
        String quote="Original verbatim source says body/3 and body/8/paragraph/child. QUOTE-BODY-END";source.setQuote(quote);finding.setBody(LocalizedText.same(narrative));
        String before=JsonUtils.write(finding),audit=JsonUtils.write(VettingReportPresentation.comments(Collections.singletonList(finding),null));
        String shown=readerNarrative(narrative,finding);assertTrue(shown.contains("Distinct model meaning at Original source location unverified"));assertTrue(shown.contains("exact source Body paragraph 8"));
        assertTrue(shown.contains("extended Original source location unverified"));assertFalse(shown.contains("Body paragraph 3"));assertFalse(shown.contains("Clause 3"));
        assertEquals(1,shown.split("Body paragraph 8",-1).length-1,"The longer unknown token is not mapped from its known prefix");
        assertEquals("未知位置Original source location unverified，后续限定完整；Original source location unverified: preserve the exception.",readerNarrative("未知位置body/3，后续限定完整；body/4/paragraph/child: preserve the exception.",finding));
        VettingReportWriter writer=new VettingReportWriter();
        for(String text:Arrays.asList(wordText(writer.docx(project(),Collections.singletonList(finding),null,"en")),pdfText(writer.pdf(project(),Collections.singletonList(finding),null,"en")))) {
            assertTrue(compact(text).contains(compact(quote)),"Source quotations bypass narrative sanitization");assertTrue(compact(text).contains(compact(shown)));assertFalse(text.contains("Body paragraph 3"));
        }
        assertEquals(before,JsonUtils.write(finding));assertEquals(audit,JsonUtils.write(VettingReportPresentation.comments(Collections.singletonList(finding),null)));
    }
    @Test void readerScopeKeepsRecordedPlanStatesSpecificSubjectsAndWarnings() throws Exception {
        String full="规则与语义仅处理已采用的 canonical 来源观察；未采用区域、整份原件完整性、编辑状态及采用关系保持声明的未知。字符分母仅为已采用观察的 UTF16 范围并集，不代表原件全文覆盖。";
        String shortWarning="片段与字符统计以已采用 canonical 观察为分母；未采用区域及原件完整性未确认。";
        String zero="语义审查按 17 个主题检索，并计划 0 组项目资料引用比较；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。";
        String positive=zero.replace("计划 0 组","计划 3 组");
        VettingReportWriter writer=new VettingReportWriter();java.lang.reflect.Method warning=VettingReportWriter.class.getDeclaredMethod("readerWarning",String.class,String.class);warning.setAccessible(true);
        String fullShown=(String)warning.invoke(writer,full+" CUSTOM FULL WARNING END","en"),shortShown=(String)warning.invoke(writer,shortWarning,"en");
        for(String literal:Arrays.asList("Unadopted regions","completeness of the entire original","editing status","adoption relationships","union of character ranges","CUSTOM FULL WARNING END"))assertTrue(fullShown.contains(literal),literal);
        assertTrue(shortShown.contains("adopted source observations as the denominator"));assertTrue(shortShown.contains("unadopted regions and original-source completeness are unconfirmed"));assertFalse(shortShown.contains("editing status"));assertFalse(shortShown.contains("applicability"));
        String paymentQuery="payment interim certificates minimum amount general conditions deleted not used reference",programmeQuery="programme commencement completion contract duration two envelope tender evaluation";
        SemanticTopicVO insufficient=new SemanticTopicVO();insufficient.setTopicIndex(99);insufficient.setTopic(paymentQuery);insufficient.setCheckState("insufficient_evidence");insufficient.setPendingSourceRequestCount(2);
        SemanticSourceRequestVO request=new SemanticSourceRequestVO();request.setMissingChunkIds(Arrays.asList("opaque-a","opaque-b"));request.setUnknownReason("The revised insurance schedule is missing. SPECIFIC SOURCE GAP END");insufficient.setSourceRequests(Collections.singletonList(request));
        SemanticTopicVO incomplete=new SemanticTopicVO();incomplete.setTopicIndex(12);incomplete.setTopic(programmeQuery);incomplete.setCheckState("not_completed");
        SemanticTopicVO issue=new SemanticTopicVO();issue.setTopicIndex(777);issue.setTopic("Project information comparison: REF-A, REF-B");issue.setCheckState("issue");
        SemanticTopicVO custom=new SemanticTopicVO();custom.setTopicIndex(4);custom.setTopic("Tailored warranty comparison: trade-A versus trade-B. SPECIFIC CUSTOM OBJECT END");custom.setCheckState("unknown");
        CoverageVO coverage=new CoverageVO(1,1,Collections.singletonList(new DocumentCoverageVO("doc","SCT","synthetic-project.docx","PARSED",10,10,1,1,Collections.singletonList(shortWarning))),Arrays.asList(full+" CUSTOM FULL WARNING END",zero,positive,"No parseable General Summary supplied. SPECIFIC WARNING END"));coverage.setSemanticTopics(Arrays.asList(custom,incomplete,issue,insufficient));
        VettingJobVO job=new VettingJobVO();job.setCoverage(coverage);String before=JsonUtils.write(job);
        for(String text:Arrays.asList(wordText(writer.docx(project(),Collections.emptyList(),job,"en")),pdfText(writer.pdf(project(),Collections.emptyList(),job,"en")))) {
            String normalized=compact(text);
            for(String literal:Arrays.asList("No project-reference comparisons were planned in this recorded review scope; this plan does not establish whether other comparisons occurred.","Project-reference comparisons were planned in this recorded review scope; completion is unconfirmed.","Source evidence or context is insufficient","Substantive source review incomplete or unconfirmed","Candidate issues recorded in reviewed excerpts","Recorded source check 99: Interim payment certificates","Recorded source check 12: Programme, commencement/completion, contract duration and two-envelope tender evaluation","Recorded source check 777: Project information comparison: REF-A, REF-B","Recorded source check 4: "+custom.getTopic(),"pending source requests 2","Required source excerpts missing from a recorded request: 2",request.getUnknownReason(),"CUSTOM FULL WARNING END","No parseable General Summary supplied. SPECIFIC WARNING END"))assertTrue(normalized.contains(compact(literal)),literal);
            for(String internal:Arrays.asList("canonical","17 topics","plans 0","plans 3",paymentQuery,programmeQuery,"opaque-a","opaque-b"))assertFalse(normalized.contains(compact(internal)),internal);
        }
        assertEquals(before,JsonUtils.write(job));
    }
    @Test void differentUnboundRawLocationsKeepAllTitleBodyAndFactParagraphsAfterDisplayMapping() throws Exception {
        FindingVO finding=candidate();finding.getEvidence().get(0).setAnchor("body/8/paragraph");finding.setTitle(LocalizedText.same("Reference at body/3 requires review."));finding.setBody(LocalizedText.same("Reference at body/5 requires review."));
        finding.setBusinessDetails(new BusinessDetailsVO("sub","Reference at body/7 requires review.",Collections.emptyList(),"amend",Collections.emptyList()));
        String before=JsonUtils.write(finding),audit=JsonUtils.write(VettingReportPresentation.comments(Collections.singletonList(finding),null));
        String display="Reference at Original source location unverified requires review.",comment=readerComments(Collections.singletonList(finding),null).get(0).getRows().get(0).get(3);
        assertEquals(3,comment.split(java.util.regex.Pattern.quote(display),-1).length-1,"Different raw title/body/fact text may not be merged just because their displayed unknown locations coincide");
        assertTrue(comment.contains("Basis recorded for review: "+display));
        VettingReportWriter writer=new VettingReportWriter();
        for(String text:Arrays.asList(wordText(writer.docx(project(),Collections.singletonList(finding),null,"en")),pdfText(writer.pdf(project(),Collections.singletonList(finding),null,"en"))))assertEquals(3,compact(text).split(java.util.regex.Pattern.quote(compact(display)),-1).length-1);
        assertEquals(before,JsonUtils.write(finding));assertEquals(audit,JsonUtils.write(VettingReportPresentation.comments(Collections.singletonList(finding),null)));
    }
    @Test void sharedContainerQualificationsUsePresentLeaderWithoutMergingDistinctMembersOrHumanFields() throws Exception {
        List<FindingVO> findings=new ArrayList<>();
        for(int i=0;i<16;i++) {
            FindingVO finding=candidate();finding.setCode("VT-MEMBER-"+i);finding.setBody(LocalizedText.same("DISTINCT BODY "+i+" END"));finding.setTitle(LocalizedText.same("DISTINCT TITLE "+i+" END"));
            finding.setBusinessDetails(new BusinessDetailsVO("sub","DISTINCT FACT "+i+" END",Collections.singletonList("DISTINCT PENDING "+i+" END"),"amend",Collections.singletonList("APP-I-026")));
            finding.setImpact(LocalizedText.same("DISTINCT IMPACT "+i+" END"));finding.setExpected("DISTINCT EXPECTED "+i+" END");finding.setSuggestion(LocalizedText.same("DISTINCT SUGGESTION "+i+" END"));finding.setReviewRemarks("DISTINCT HUMAN "+i+" END");finding.setActionTaken("DISTINCT ACTION "+i+" END");finding.setAddendumRequired(i%2==0);
            finding.setSourceCandidateContainer(new SourceCandidateContainerVO("source-candidates:OPAQUE-A","exact_bound_evidence","unresolved_issue_equivalence",Arrays.asList("ABSENT-HISTORICAL-LEADER","VT-MEMBER-0"),16,null));findings.add(finding);
        }
        FindingVO other=candidate();other.setCode("VT-OTHER");other.setSourceCandidateContainer(new SourceCandidateContainerVO("source-candidates:OPAQUE-B","exact_bound_evidence","unresolved_issue_equivalence",Collections.singletonList("VT-OTHER"),1,null));findings.add(other);
        String before=JsonUtils.write(findings),audit=JsonUtils.write(VettingReportPresentation.comments(findings,null));
        VettingReportPresentation.Table table=readerComments(findings,null).get(0);assertEquals(17,table.getFindingCodes().size());assertEquals(17,table.getRows().size());
        String all=JsonUtils.write(table);assertEquals(2,all.split("independent legal problem count is undetermined",-1).length-1);assertFalse(all.contains("OPAQUE-A"));assertFalse(all.contains("OPAQUE-B"));assertFalse(all.contains("ABSENT-HISTORICAL-LEADER"));
        for(int i=0;i<16;i++) {
            String comment=table.getRows().get(i).get(3),remarks=table.getRows().get(i).get(4);
            for(String field:Arrays.asList("BODY","TITLE","FACT","PENDING","IMPACT","EXPECTED","SUGGESTION"))assertTrue(comment.contains("DISTINCT "+field+" "+i+" END"));
            assertTrue(comment.contains("formal clause numbers stated below are unconfirmed"));assertTrue(comment.contains("8. PRE.B6.590 (row APP-I-026)"));assertTrue(remarks.contains("DISTINCT HUMAN "+i+" END"));assertTrue(remarks.contains("DISTINCT ACTION "+i+" END"));
            assertTrue(remarks.contains(i%2==0?"Include in tender addendum: Required":"Include in tender addendum: Not required"));
            if(i>0){assertTrue(comment.contains("same-source group qualifications: see candidate VT-MEMBER-0"));assertFalse(comment.contains("Review suggestion (not confirmed)"));}
        }
        List<FindingVO> reordered=Arrays.asList(findings.get(9),findings.get(2));List<List<String>> rows=readerComments(reordered,null).get(0).getRows();
        assertTrue(rows.get(0).get(3).contains("Same-source group note at candidate VT-MEMBER-9"));assertTrue(rows.get(1).get(3).contains("see candidate VT-MEMBER-9"));
        String single=readerComments(Collections.singletonList(findings.get(2)),null).get(0).getRows().get(0).get(3);assertTrue(single.contains("Review suggestion (not confirmed)"));assertTrue(single.contains("Same-source group note at candidate VT-MEMBER-2"));assertTrue(single.contains("independent legal problem count is undetermined"));
        assertEquals(before,JsonUtils.write(findings));assertEquals(audit,JsonUtils.write(VettingReportPresentation.comments(findings,null)));
    }
}
