package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.service.sources.OerSourceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NormalDocxReadingProjectionTest {
    @Test void ordinaryWordKeepsTextClausesAndTablesWhileDroppingRepeatedLayout() throws Exception {
        byte[] original;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : Arrays.asList("Special Conditions of Contract", "SCC11.302 Schedule of rates",
                    "[Guidance Note: Select either Option A or Option B as appropriate.]",
                    "Option A", "(1) The first obligation applies.", "Option B", "(1) The second obligation applies."))
                word.createParagraph().createRun().setText(text);
            for (int i = 0; i < 48; i++) word.createParagraph().createRun().setText("Paragraph " + i + " retains its original reading order and text.");
            word.createTable(2, 2).getRow(0).getCell(0).setText("Item");
            word.getTables().get(0).getRow(0).getCell(1).setText("Requirement");
            word.getTables().get(0).getRow(1).getCell(0).setText("A");
            word.getTables().get(0).getRow(1).getCell(1).setText("Complete native row text");
            word.createParagraph().createRun().setText("SCC11.303 Next clause");
            word.write(out); original = out.toByteArray();
        }
        DocumentParser.ParsedDocument parsed = new DocumentParser(new ConsenseProperties(),
                org.mockito.Mockito.mock(OcrClient.class)).parse("authored.docx", original);
        SourceDocument rich = source(parsed), compact = source(parsed);
        rich.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));
        rich.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));
        NormalSourceCandidateGraph.capture(rich);
        OerSourceMetadata.captureOrdinary(compact, parsed, original);
        NormalSourceCandidateGraph.capture(compact);

        List<DocumentBlock> saved = VettingCorpus.blocks(compact);
        assertEquals(parsed.getText(), compact.getTextContent());
        assertEquals(parsed.getBlocks().size(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            DocumentBlock before = parsed.getBlocks().get(i), after = saved.get(i);
            assertEquals(before.getId(), after.getId());
            assertEquals(before.getText(), after.getText());
            assertEquals(before.getOriginalText(), after.getOriginalText());
            assertEquals(before.getDeletedText(), after.getDeletedText());
            assertEquals(before.getStrikeText(), after.getStrikeText());
            assertEquals(before.getSourcePosition(), after.getSourcePosition());
            assertEquals(before.getCells(), after.getCells());
            assertEquals(JsonUtils.mapper().valueToTree(before.getWordCells()), JsonUtils.mapper().valueToTree(after.getWordCells()));
            assertFalse(after.getReadingContext().has("nativeDocxParagraph"));
            assertFalse(after.getReadingContext().has("nativeDocxParagraphs"));
        }
        JsonNode oldCoverage = JsonUtils.parse(rich.getParseCoverageJson()), newCoverage = JsonUtils.parse(compact.getParseCoverageJson());
        assertEquals(oldCoverage.path("nativeDocxTables"), newCoverage.path("nativeDocxTables"));
        assertEquals(oldCoverage.path("nativeDocxSections"), newCoverage.path("nativeDocxSections"));
        assertEquals(oldCoverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges"),
                newCoverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges"));
        assertEquals(oldCoverage.path("nativeDocxOutline").path("sourceClauseScopes").path("members").size(),
                newCoverage.path("nativeDocxOutline").path("sourceClauseScopes").path("members").size());
        assertFalse(newCoverage.path("nativeDocxOutline").has("paragraphs"));
        assertEquals(parsed.getSourceSha256(), compact.getSourceSha256());
        assertEquals("actual_source_candidate_generation", NormalSourceCandidateGraph.requireBound(compact).path("status").asText());
        assertTrue(VettingCorpus.chunks(Arrays.asList(compact)).stream().anyMatch(chunk -> chunk.getContent().contains("Complete native row text")));
        JsonNode richGraph = NormalSourceCandidateGraph.requireBound(rich);
        JsonNode compactGraph = NormalSourceCandidateGraph.requireBound(compact);
        VettingCorpus.Chunk richClause = VettingCorpus.chunks(Arrays.asList(rich)).stream()
                .filter(chunk -> chunk.getContent().contains("The first obligation applies")).findFirst().orElseThrow(AssertionError::new);
        VettingCorpus.Chunk compactClause = VettingCorpus.chunks(Arrays.asList(compact)).stream()
                .filter(chunk -> chunk.getContent().contains("The first obligation applies")).findFirst().orElseThrow(AssertionError::new);
        JsonNode richScope = NormalSourceCandidateGraph.wordSourceScopeContext(richGraph, JsonUtils.mapper().valueToTree(richClause.getParts()));
        JsonNode compactScope = NormalSourceCandidateGraph.wordSourceScopeContext(compactGraph, JsonUtils.mapper().valueToTree(compactClause.getParts()));
        assertFalse(compactScope.path("ranges").isEmpty());
        assertTrue(compactScope.path("issues").isEmpty(), "Selected clause issues: " + compactScope.path("issues").size());
        assertEquals(richScope.path("ranges"), compactScope.path("ranges"));
        VettingCorpus.Chunk richTable = VettingCorpus.chunks(Arrays.asList(rich)).stream()
                .filter(chunk -> chunk.getContent().contains("Complete native row text")).findFirst().orElseThrow(AssertionError::new);
        VettingCorpus.Chunk compactTable = VettingCorpus.chunks(Arrays.asList(compact)).stream()
                .filter(chunk -> chunk.getContent().contains("Complete native row text")).findFirst().orElseThrow(AssertionError::new);
        JsonNode richModel = NormalSourceCandidateGraph.selectedForModel(richGraph, richTable,
                new NormalSourceCandidateGraph.NativeDocxTableIndex(rich, richGraph));
        JsonNode compactModel = NormalSourceCandidateGraph.selectedForModel(compactGraph, compactTable,
                new NormalSourceCandidateGraph.NativeDocxTableIndex(compact, compactGraph));
        assertNotNull(compactModel.findValue("tables"));
        assertEquals(richModel.findValue("tables"), compactModel.findValue("tables"));

        assertTrue(compact.getStructuredContentJson().length() < rich.getStructuredContentJson().length() / 2,
                compact.getStructuredContentJson().length() + " / " + rich.getStructuredContentJson().length());
        assertTrue(compact.getParseCoverageJson().length() < rich.getParseCoverageJson().length() * 85 / 100,
                compact.getParseCoverageJson().length() + " / " + rich.getParseCoverageJson().length());
        assertTrue(compact.getNormalCandidateJson().length() < rich.getNormalCandidateJson().length(),
                compact.getNormalCandidateJson().length() + " / " + rich.getNormalCandidateJson().length());
    }

    private static SourceDocument source(DocumentParser.ParsedDocument parsed) {
        SourceDocument source = new SourceDocument();
        source.setId(19L); source.setProjectId("ordinary-word-reading");
        source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        source.setReviewRole("tender"); source.setDocumentScope("fullset");
        source.setFileName("authored.docx"); source.setFileKey("SCC");
        source.setParseStatus(parsed.getParseStatus());
        source.setSourceSha256(parsed.getSourceSha256());
        source.setTextContent(parsed.getText());
        return source;
    }
}
