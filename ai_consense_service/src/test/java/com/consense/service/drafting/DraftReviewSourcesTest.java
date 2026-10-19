package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.OerTemplateEvidence;
import com.consense.document.ParseCoverage;
import com.consense.domain.SourceDocument;
import com.consense.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic packages and persisted observations only; no upload, parser, OCR, API or database. */
class DraftReviewSourcesTest {
    @TempDir Path directory;
    private DraftReviewSources sources;

    @BeforeEach void setup() {
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(directory.toString());
        sources=new DraftReviewSources(new StorageService(properties),properties);
    }

    @Test void readingUsesActualEditableParagraphsWithoutInventingWordPagesOrEvidence() throws Exception {
        byte[] bytes=docx("A\t42 months", "Foundation works");SourceDocument source=source(1,"NTT",bytes);
        ObjectNode reading=sources.reading(source);
        assertEquals("docx",reading.path("format").asText());assertTrue(reading.path("canEdit").asBoolean());
        assertEquals(DraftTemplateRouting.sha(bytes),reading.path("sourceSha256").asText());
        assertEquals("ntt",reading.path("docId").asText());assertEquals("missing",reading.path("evidenceStatus").asText());
        assertEquals("unknown_requires_rendition",reading.path("physicalWordPagesStatus").asText());
        assertEquals(2,reading.path("paragraphs").size());assertTrue(reading.path("guidanceNotes").isEmpty());
        DocxTemplateEditor.Paragraph paragraph=new DocxTemplateEditor().inspect(bytes).mainParagraph(1);
        assertEquals(paragraph.getText(),reading.path("paragraphs").path(0).path("text").asText());
        assertEquals(paragraph.getSha256(),reading.path("paragraphs").path(0).path("paragraphSha256").asText());
        JsonNode position=reading.path("paragraphs").path(0).path("sourcePosition");
        String word="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";
        assertEquals(DraftTemplateRouting.sha(bytes),position.path("sourceSha256").asText());
        assertEquals("word/document.xml",position.path("partName").asText());
        assertEquals("/"+word+"document[1]/"+word+"body[1]/"+word+"p[1]",position.path("nodePath").asText());
        assertArrayEquals(bytes,Files.readAllBytes(Paths.get(source.getStoragePath())));
        assertEquals("PARSED",source.getParseStatus());assertFalse(reading.has("storagePath"));
    }

    @Test void explicitSpanPinsSourceParagraphTextAndUtf16Coordinates() throws Exception {
        byte[] bytes=docx("A😀 42 months");SourceDocument source=source(1,"NTT",bytes);
        ObjectNode passage=passage(bytes,0,4,6);passage.put("docId","ntt");
        JsonNode bound=sources.bind(source,passage);assertEquals("bound",bound.path("status").asText());
        assertEquals("42",bound.path("spans").path(0).path("expectedText").asText());
        ObjectNode stale=passage.deepCopy();((ObjectNode)stale.path("native")).put("sourceSha256",zeros());
        assertEquals("unsafe",sources.bind(source,stale).path("status").asText());
        ObjectNode wrongHash=passage.deepCopy();((ObjectNode)wrongHash.path("native")).put("paragraphSha256",zeros());
        assertEquals("unsafe",sources.bind(source,wrongHash).path("status").asText());
        ObjectNode malformed=passage.deepCopy();malformed.put("native","invalid");
        assertEquals("INVALID_NATIVE_BINDING",sources.bind(source,malformed).path("reason").asText());
        ObjectNode splitSurrogate=passage(bytes,0,2,3);
        assertEquals("INVALID_UTF16_BOUNDARY",sources.bind(source,splitSurrogate).path("reason").asText());
        passage.put("docId","scc-pdf");assertEquals("SOURCE_EDITION_MISMATCH",sources.bind(source,passage).path("reason").asText());
    }

    @Test void uniqueExactLegacyTextMayBindButRepeatedOrSparseSelectionsCannotExpand() throws Exception {
        byte[] bytes=docx("one exact phrase", "repeated repeated");SourceDocument source=source(1,"SCT",bytes);
        ObjectNode passage=JsonUtils.mapper().createObjectNode().put("docId","sct").put("text","exact phrase");
        assertEquals("bound",sources.bind(source,passage).path("status").asText());
        passage.put("text","repeated");assertEquals("ambiguous",sources.bind(source,passage).path("status").asText());
        passage.put("text","missing");assertEquals("missing",sources.bind(source,passage).path("status").asText());
        passage.put("text","exact phrase");passage.putArray("wordIds").add(1).add(3);
        assertEquals("SPARSE_SELECTION_REQUIRES_NATIVE_SPANS",sources.bind(source,passage).path("reason").asText());
    }

    @Test void sparseNativeSpansRetainTheirOwnBoundariesAndRejectOverlap() throws Exception {
        byte[] bytes=docx("one GAP two");SourceDocument source=source(1,"SCC",bytes);
        ObjectNode first=passage(bytes,0,0,3),second=passage(bytes,0,8,11),selected=JsonUtils.mapper().createObjectNode();
        selected.put("docId","scc-word");selected.putArray("wordIds").add(0).add(2);
        selected.putObject("native").putArray("spans").add(first.path("native")).add(second.path("native"));
        JsonNode result=sources.bind(source,selected);assertEquals("bound",result.path("status").asText());
        assertEquals(2,result.path("spans").size());assertEquals(3,result.path("spans").path(0).path("end").asInt());
        assertEquals(8,result.path("spans").path(1).path("start").asInt());
        ((ObjectNode)selected.path("native")).put("sourceSha256",DraftTemplateRouting.sha(bytes));
        ((ObjectNode)first.path("native")).remove("sourceSha256");((ObjectNode)second.path("native")).remove("sourceSha256");
        assertEquals("bound",sources.bind(source,selected).path("status").asText());
        ((com.fasterxml.jackson.databind.node.ArrayNode)selected.path("native").path("spans")).add(first.path("native"));
        assertEquals("OVERLAPPING_NATIVE_SPANS",sources.bind(source,selected).path("reason").asText());
    }

    @Test void pdfKeepsPhysicalEvidenceAndCannotBorrowDocxEditingCoordinates() throws Exception {
        byte[] pdf="%PDF-1.4\nexisting original bytes".getBytes(StandardCharsets.UTF_8);SourceDocument source=source(2,"SCC",pdf);
        source.setParseStatus("PARTIAL");source.setParseCoverageJson("{\"totalPages\":2,\"parsedPages\":1,\"complete\":false,\"failedPages\":[2]}");
        source.setStructuredContentJson("[{\"id\":\"page-1\",\"pageNo\":1,\"bbox\":[0.1,0.2,0.3,0.4],\"pdfGlyphs\":[{\"unicode\":\"A\"}]}]");
        JsonNode reading=sources.reading(source);assertEquals("scc-pdf",reading.path("docId").asText());
        assertTrue(reading.path("paragraphs").isEmpty());assertFalse(reading.path("canEdit").asBoolean());
        assertFalse(reading.path("coverage").path("complete").asBoolean());
        assertEquals("A",reading.path("blocks").path(0).path("pdfGlyphs").path(0).path("unicode").asText());
        assertEquals("EDITABLE_DOCX_REQUIRED",sources.bind(source,JsonUtils.mapper().createObjectNode()).path("reason").asText());
    }

    @Test void multipleNativeSpansMustMatchTheDisplayedSourceTextWhenSupplied() throws Exception {
        byte[] bytes=docx("one GAP two", "third source phrase");SourceDocument source=source(1,"SCT",bytes);
        ObjectNode selected=JsonUtils.mapper().createObjectNode().put("docId","sct").put("text","one\nthird");
        selected.putObject("native").putArray("spans").add(passage(bytes,0,0,3).path("native")).add(passage(bytes,1,0,5).path("native"));
        assertEquals("bound",sources.bind(source,selected).path("status").asText());
        selected.put("text","one third");
        assertEquals("SELECTED_TEXT_AND_NATIVE_SPAN_DIFFER",sources.bind(source,selected).path("reason").asText());
        selected.put("text","An unrelated passage displayed for review");
        assertEquals("unsafe",sources.bind(source,selected).path("status").asText());
        selected.remove("text");
        assertEquals("bound",sources.bind(source,selected).path("status").asText());
    }

    @Test void storedEvidenceMustStillMatchSourceFamilyInputsAndPayload() throws Exception {
        byte[] pdf="%PDF-1.4\nexisting original bytes".getBytes(StandardCharsets.UTF_8);SourceDocument source=source(2,"NTT",pdf);
        DocumentBlock block=new DocumentBlock();block.setId("page-1");block.setPageNo(1);block.setSource("native_pdf");
        block.setText("(Guidance Notes: Select the applicable option.)");
        ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(1);coverage.setParsedPages(1);coverage.setComplete(true);
        DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument(block.getText(),Collections.emptyList(),1,false,null,
                Collections.singletonList(block),coverage,DraftTemplateRouting.sha(pdf),Collections.emptyList());
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",parsed);coverage.setTemplateEvidence(evidence);
        source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(coverage));
        JsonNode bound=sources.reading(source);assertEquals("bound",bound.path("evidenceStatus").asText());
        assertEquals(1,bound.path("sourceMembers").size());assertEquals(1,bound.path("guidanceNotes").size());
        ((ObjectNode)evidence.path("guidanceNotes").path(0)).put("text","Tampered instruction");
        source.setParseCoverageJson(JsonUtils.write(coverage));JsonNode tampered=sources.reading(source);
        assertEquals("invalid",tampered.path("evidenceStatus").asText());assertTrue(tampered.path("guidanceNotes").isEmpty());
        coverage.setTemplateEvidence(OerTemplateEvidence.analyse("NTT",parsed));source.setParseCoverageJson(JsonUtils.write(coverage));
        source.setFileKey("SCT");assertEquals("invalid",sources.reading(source).path("evidenceStatus").asText());
    }

    @Test void correspondenceIdentityDoesNotPretendToBeTemplateAndUnrelatedCategoriesAreExcluded() throws Exception {
        SourceDocument correspondence=source(3,null,docx("A project fact"));correspondence.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);
        JsonNode item=sources.sources(Collections.singletonList(correspondence)).path(0);
        assertEquals("3",item.path("docId").asText());assertEquals(DraftAdoption.sourceHash(correspondence),item.path("sourceHash").asText());
        assertNotEquals(item.path("sourceHash"),item.path("sourceSha256"));
        correspondence.setFileKey("NTT");assertEquals("3",sources.reading(correspondence).path("docId").asText());
        correspondence.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);assertTrue(sources.sources(Collections.singletonList(correspondence)).isEmpty());
        assertThrows(BizException.class,()->sources.reading(correspondence));
    }

    @Test void completeSavedObservationSupportsReviewButNeverEditableAuthorityAndRejectsChangedProof() throws Exception {
        byte[] bytes="%PDF-1.4\nsource-observation-test".getBytes(StandardCharsets.UTF_8);SourceDocument source=source(1,"NTT",bytes);
        DocumentBlock block=new DocumentBlock();block.setId("page-1");block.setPageNo(1);block.setSource("native_pdf");block.setText("(Guidance Notes: Select the applicable option.)");
        block.setReadingContext(JsonUtils.mapper().createObjectNode().put("sourceSha256",DraftTemplateRouting.sha(bytes)).put("occurrenceId","page-1"));
        ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(1);coverage.setParsedPages(1);coverage.setComplete(true);
        DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument(block.getText(),Collections.emptyList(),1,false,null,Collections.singletonList(block),coverage,DraftTemplateRouting.sha(bytes),Collections.emptyList());
        coverage.setTemplateEvidence(OerTemplateEvidence.analyse("NTT",parsed));source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(coverage));
        ObjectNode anchor=observationPassage(source);
        ObjectNode bound=sources.bindObservation(source,anchor);assertEquals("bound",bound.path("status").asText());assertFalse(bound.path("editableAuthority").asBoolean());assertFalse(bound.has("spans"));
        ObjectNode wrong=anchor.deepCopy();wrong.put("text","Invented instruction");assertEquals("unsafe",sources.bindObservation(source,wrong).path("status").asText());
        wrong=anchor.deepCopy();((ObjectNode)wrong.path("observedRanges").path(0)).put("end",1);assertEquals("SOURCE_OBSERVATION_SCOPE_CHANGED",sources.bindObservation(source,wrong).path("reason").asText());
        wrong=anchor.deepCopy();((ObjectNode)wrong.path("sourceObservation")).put("captureComplete",false);assertEquals("SOURCE_OBSERVATION_OBJECT_CHANGED",sources.bindObservation(source,wrong).path("reason").asText());
        for(String key:new String[]{"analysisRevision","analysisInputSha256","analysisSha256"}){wrong=anchor.deepCopy();wrong.put(key,"old-or-forged");assertEquals("SOURCE_OBSERVATION_ANALYSIS_CHANGED",sources.bindObservation(source,wrong).path("reason").asText());}
        SourceDocument foreign=source(2,"NTT",bytes);foreign.setStructuredContentJson(source.getStructuredContentJson());foreign.setParseCoverageJson(source.getParseCoverageJson());assertEquals("SOURCE_DOCUMENT_MISMATCH",sources.bindObservation(foreign,anchor).path("reason").asText());
        ObjectNode old=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());((ObjectNode)old.path("templateEvidence").path("sourceBinding")).put("analysisRevision","historical-rule-revision");source.setParseCoverageJson(JsonUtils.write(old));
        assertEquals("CURRENT_SOURCE_OBSERVATIONS_REQUIRED",sources.bindObservation(source,anchor).path("reason").asText());
    }

    @Test void pdfObservationRequiresSameSourcePhysicalPageAndOccurrenceIdentity() throws Exception {
        byte[] bytes="%PDF-1.4\nPDF identity checks".getBytes(StandardCharsets.UTF_8);String sha=DraftTemplateRouting.sha(bytes);SourceDocument source=source(1,"NTT",bytes);
        for(String mismatch:new String[]{"source","page-zero","page-outside","occurrence"}) {
            DocumentBlock block=new DocumentBlock();block.setId("line-1");block.setSource("native");block.setPageNo("page-zero".equals(mismatch)?0:"page-outside".equals(mismatch)?2:1);block.setText("(Guidance Notes: Select the actual source option.)");
            block.setReadingContext(JsonUtils.mapper().createObjectNode().put("sourceSha256","source".equals(mismatch)?zeros():sha).put("occurrenceId","occurrence".equals(mismatch)?"foreign-line":"line-1"));
            ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(1);coverage.setParsedPages(1);coverage.setComplete(true);
            DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument(block.getText(),Collections.emptyList(),1,false,null,Collections.singletonList(block),coverage,sha,Collections.emptyList());coverage.setTemplateEvidence(OerTemplateEvidence.analyse("NTT",parsed));
            source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(coverage));
            assertNotEquals("bound",sources.bindObservation(source,observationPassage(source)).path("status").asText(),mismatch);
        }
    }

    private ObjectNode observationPassage(SourceDocument source) {
        JsonNode reading=sources.reading(source),note=reading.path("guidanceNotes").path(0);ObjectNode anchor=JsonUtils.mapper().createObjectNode().put("sourceDocumentId",source.getId()).put("sourceSha256",reading.path("sourceSha256").asText()).put("text",note.path("text").asText()).put("sourceObservationId",note.path("id").asText());
        anchor.set("sourceObservation",note.deepCopy());anchor.set("observedRanges",note.path("memberRanges").deepCopy());for(String key:new String[]{"analysisRevision","analysisInputSha256","analysisSha256"})anchor.set(key,reading.path(key).deepCopy());return anchor;
    }

    @Test void immutableArchiveSurvivesSourceIdReplacementAndRejectsTampering() throws Exception {
        byte[] original=docx("Old original");SourceDocument source=source(1,"NTT",original);
        ObjectNode archive=sources.archive(source);ObjectNode second=sources.archive(source);
        assertEquals(archive.path("archiveKey"),second.path("archiveKey"));assertFalse(archive.has("storagePath"));
        Path replacement=directory.resolve("replacement.docx");byte[] newBytes=docx("New original");Files.write(replacement,newBytes);
        source.setStoragePath(replacement.toString());source.setSourceSha256(DraftTemplateRouting.sha(newBytes));
        assertArrayEquals(original,sources.archivedBytes(archive));assertNotEquals(archive.path("sourceSha256"),sources.reading(source).path("sourceSha256"));
        Files.write(directory.resolve(".draft-review-originals").resolve(archive.path("archiveKey").asText()),new byte[]{1});
        assertThrows(BizException.class,()->sources.archivedBytes(archive));
        archive.put("archiveKey","../outside");assertThrows(BizException.class,()->sources.archivedBytes(archive));
    }

    @Test void changedBytesAndOutsidePathsNeverBecomeBoundSources() throws Exception {
        SourceDocument source=source(1,"NTT",docx("Source"));source.setSourceSha256(zeros());
        assertThrows(BizException.class,()->sources.reading(source));
        assertEquals("unavailable",sources.sources(Collections.singletonList(source)).path(0).path("evidenceStatus").asText());
        source.setStoragePath(directory.resolve("../absent.docx").toString());assertThrows(BizException.class,()->sources.archive(source));
    }

    @Test void exactTextInsideAFieldStillRequiresAnUnsafeBinding() throws Exception {
        byte[] bytes;
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            document.createParagraph().getCTP().addNewFldSimple().addNewR().addNewT().setStringValue("42");
            document.write(out);bytes=out.toByteArray();
        }
        SourceDocument source=source(1,"NTT",bytes);
        assertEquals("unsafe",sources.bind(source,passage(bytes,0,0,2)).path("status").asText());
    }

    private SourceDocument source(long id,String key,byte[] bytes) throws Exception {
        Path path=directory.resolve(id+".source");Files.write(path,bytes);SourceDocument source=new SourceDocument();
        source.setId(id);source.setProjectId("project");source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);source.setFileKey(key);
        source.setFileName("source.docx");source.setStoragePath(path.toString());source.setSourceSha256(DraftTemplateRouting.sha(bytes));
        source.setParseStatus("PARSED");source.setTextContent("Saved parsed observation");source.setStructuredContentJson("[]");source.setParseCoverageJson("{}");return source;
    }
    private static ObjectNode passage(byte[] bytes,int paragraphIndex,int start,int end) {
        DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(bytes);DocxTemplateEditor.Paragraph paragraph=index.getMainParagraphs().get(paragraphIndex);
        String expected=paragraph.getText().substring(start,end);ObjectNode passage=JsonUtils.mapper().createObjectNode().put("text",expected);
        passage.putObject("native").put("paragraphId",paragraph.getId()).put("paragraphSha256",paragraph.getSha256()).put("start",start).put("end",end).put("expectedText",expected).put("sourceSha256",index.getSourceSha256());return passage;
    }
    private static byte[] docx(String... paragraphs) throws Exception {
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(String text:paragraphs)document.createParagraph().createRun().setText(text);document.write(out);return out.toByteArray();
        }
    }
    private static String zeros(){return String.join("",Collections.nCopies(64,"0"));}
}
