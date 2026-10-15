package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalSourceCandidateGraphTest {
    @Test void persistedWordNumberingBindsToTheSameCompleteJsonGeneration(){
        SourceDocument source=numberedSource();NormalSourceCandidateGraph.capture(source);String raw=source.getNormalCandidateJson();JsonNode expected=JsonUtils.parse(raw);
        JsonNode current=assertDoesNotThrow(()->NormalSourceCandidateGraph.requireBound(source));
        assertEquals(expected,current);assertEquals(raw,source.getNormalCandidateJson());
        assertEquals(1,current.path("units").get(0).path("parts").get(0).path("wordNumbering").get(0).path("value").asLong());
        assertFalse(current.path("humanConfirmed").asBoolean());assertFalse(current.path("sourceQualityAccepted").asBoolean());
    }
    @Test void numberStorageNormalizationStillRejectsBooleanStringFloatingAndChangedIntegerValues(){
        SourceDocument source=numberedSource();NormalSourceCandidateGraph.capture(source);String raw=source.getNormalCandidateJson();
        for(JsonNode replacement:Arrays.asList(com.fasterxml.jackson.databind.node.BooleanNode.TRUE,com.fasterxml.jackson.databind.node.TextNode.valueOf("1"),com.fasterxml.jackson.databind.node.DoubleNode.valueOf(1.0),com.fasterxml.jackson.databind.node.IntNode.valueOf(2))){
            com.fasterxml.jackson.databind.node.ObjectNode changed=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(raw);
            ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("units").get(0).path("parts").get(0).path("wordNumbering").get(0)).set("value",replacement);
            source.setNormalCandidateJson(JsonUtils.write(changed));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source),replacement.getClass().getSimpleName());
        }
    }
    private SourceDocument numberedSource(){
        SourceDocument source=source();DocumentBlock block=new DocumentBlock();block.setId("body/0/paragraph");block.setLocation("body/0/paragraph");block.setSource("native");block.setText("1. AUTHORED numbered obligation");
        DocumentBlock.WordNumbering numbering=new DocumentBlock.WordNumbering();numbering.setValue(1L);numbering.setLabel("1.");numbering.setAppliedToEffectiveText(true);numbering.setResolutionStatus("observed");block.setWordNumbering(Collections.singletonList(numbering));
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));return source;
    }
    @Test void zeroTextWordMathAndImageRemainWholeUnknownObjectsWithoutRawXmlInModelInput() {
        SourceDocument source=source();DocumentBlock object=new DocumentBlock();object.setId("body:7:paragraph");
        object.setLocation("body/7/paragraph");object.setKind("paragraph");object.setSource("docx");object.setWordStructureVersion("docx-numbering-symbol-v1");
        object.setWordWarnings(Collections.singletonList("Unrendered native Word math; source semantics unknown"));
        object.setWordNativeObjects(Collections.singletonList(JsonUtils.parse("{\"kind\":\"math\",\"location\":\"body/7/paragraph/node/0\",\"status\":\"native_ooxml_unrendered\",\"semanticStatus\":\"unknown\",\"nativeXml\":\"RAW_OMML_DO_NOT_SEND_IN_PROMPT\",\"sourcePart\":\"/word/document.xml\",\"relationshipRefs\":[{\"relationshipId\":\"rId4\",\"targetPart\":\"/word/media/image1.png\",\"sha256\":\"bound-original-asset-sha\",\"status\":\"original_package_asset_bytes_bound\"}]}")));
        source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(object)));source.setTextContent("");NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertEquals(1,chunks.size());assertEquals("",chunks.get(0).getContent());
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks)).get(0).path("candidateStructure");
        JsonNode unit=material.path("units").get(0);assertEquals("word_native_object",unit.path("kind").asText());
        assertEquals("complete_actual_structural_unit",unit.path("closureStatus").asText());
        String wire=material.toString();assertTrue(wire.contains("native_ooxml_unrendered"));assertTrue(wire.contains("bound-original-asset-sha"));
        assertTrue(wire.contains("semantics_unknown"));assertFalse(wire.contains("RAW_OMML_DO_NOT_SEND_IN_PROMPT"));
        assertTrue(source.getStructuredContentJson().contains("RAW_OMML_DO_NOT_SEND_IN_PROMPT"));
    }
    @Test void completeAlternativeParserPagesRemainOneWholeCandidateWithoutMixingOrRawAudit() {
        SourceDocument source=source();
        com.fasterxml.jackson.databind.node.ObjectNode pageGroup=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse("{\"physicalPageNo\":1,\"scope\":\"whole_page\",\"selectionStatus\":\"candidate_unverified\",\"selectedParser\":null,\"policySha256\":null,\"sourceQualityAccepted\":false,\"adoptionStatus\":\"candidate_unverified\",\"reason\":\"no approved generic policy\",\"candidates\":[{\"parser\":\"paddleocr-vl\",\"runId\":\"paddle-run\",\"pageId\":\"paddle-page\",\"features\":{\"hasTables\":true,\"allTableStructuresObserved\":false,\"allGeometryBound\":false,\"completeRawCapture\":true},\"blocks\":[{\"id\":\"paddle-table\",\"kind\":\"table\",\"text\":\"PADDLE_WHOLE_BODY\",\"table\":{\"cells\":[{\"text\":\"\",\"rowSpan\":2}]},\"relations\":[{\"type\":\"note\",\"qualifiersComplete\":\"unknown\"}],\"unknowns\":[\"blank meaning\"]}],\"page\":{\"frame\":{\"sourceSha256\":\"bound\",\"status\":\"unknown\"},\"unknowns\":[\"control state\"],\"rawPayload\":\"DO_NOT_SEND_PAGE_RAW_AUDIT\"}},{\"parser\":\"mineru\",\"runId\":\"mineru-run\",\"pageId\":\"mineru-page\",\"features\":{\"hasTables\":false,\"allTableStructuresObserved\":false,\"allGeometryBound\":true,\"completeRawCapture\":true},\"blocks\":[{\"id\":\"mineru-control\",\"kind\":\"checkbox\",\"text\":\"MINERU_WHOLE_BODY\",\"fields\":[{\"state\":\"unknown\"}],\"unknowns\":[\"whole page scope\"]}],\"page\":{\"frame\":{\"status\":\"bound\"},\"unknowns\":[\"whole page scope\"],\"softwareProof\":\"DO_NOT_SEND_PAGE_SOFTWARE_AUDIT\"}}],\"dualEvidenceRef\":{\"relativePath\":\"DO_NOT_SEND_PAGE_CAS_AUDIT\"}}");
        pageGroup.set("source",JsonUtils.mapper().createObjectNode().put("sourceSha256",source.getSourceSha256()).put("sourceFilename","authored.pdf").put("pageCount",1));
        ((com.fasterxml.jackson.databind.node.ObjectNode)pageGroup.path("candidates").get(0).path("page")).set("mapping",JsonUtils.parse("{\"physicalPageNo\":1,\"providerInputPageNo\":1,\"providerBboxFrame\":\"normalized_1000\",\"originalPageTransform\":{\"kind\":\"affine\",\"matrix\":[0.5,0,0,0,0.75,0]}}"));
        DocumentBlock group=new DocumentBlock();group.setId("whole-page-1");group.setLocation("pdf-page/1/visual-parser-group");group.setPageNo(1);group.setKind("visual_parser_group");
        group.setSource("parser_page_group_candidate");group.setText("DO_NOT_EMBED_GROUP_CANDIDATE");group.setParserPageGroup(pageGroup);
        source.setTextContent("");source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(group)));NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertEquals(1,chunks.size(),"A zero-text whole parser page must remain selectable source structure");
        assertEquals("",chunks.get(0).getContent());assertTrue(JsonUtils.write(chunks).contains("DO_NOT_SEND_PAGE_RAW_AUDIT"),"Original full alternatives and audits remain in saved source Parts");
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));JsonNode unit=material.get(0).path("candidateStructure").path("units").get(0);
        assertEquals("whole_parser_page",unit.path("kind").asText());assertEquals("complete_actual_structural_unit",unit.path("closureStatus").asText());
        JsonNode projected=unit.path("parts").get(0).path("parserPageGroup");assertEquals(2,projected.path("candidates").size());assertTrue(projected.path("selectedParser").isNull());assertFalse(projected.path("sourceQualityAccepted").asBoolean());
        for(int i=0;i<2;i++)assertEquals(pageGroup.path("candidates").get(i).path("blocks"),projected.path("candidates").get(i).path("blocks"),"Both complete alternatives keep their own blocks, blanks, relationships and unknowns");
        assertEquals(pageGroup.path("candidates").get(1).path("page").path("unknowns"),projected.path("candidates").get(1).path("page").path("unknowns"));
        assertEquals(pageGroup.path("candidates").get(0).path("page").path("mapping"),projected.path("candidates").get(0).path("page").path("mapping"),"Whole alternatives retain their own provider-to-original page frame mapping");
        assertTrue(material.toString().contains("PADDLE_WHOLE_BODY"));assertTrue(material.toString().contains("MINERU_WHOLE_BODY"));
        for(String audit:Arrays.asList("DO_NOT_SEND_PAGE_RAW_AUDIT","DO_NOT_SEND_PAGE_SOFTWARE_AUDIT","DO_NOT_SEND_PAGE_CAS_AUDIT","DO_NOT_EMBED_GROUP_CANDIDATE"))assertFalse(material.toString().contains(audit));
        JsonNode method=material.get(0).path("selectedPartExtractionObservations").get(0);assertEquals("whole_independent_parser_page_candidates",method.path("parserPageGroupStatus").asText());
        assertEquals(projected,method.path("parserPageGroup"));
    }
    @Test void nativeWholeMethodCandidatesRemainIndependentZeroTextStructureWithoutRawGlyphAudit() {
        SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);
        for(String method:Arrays.asList("native-regions","docling")){
            DocumentBlock candidate=new DocumentBlock();candidate.setId("native-method-"+method);candidate.setLocation("pdf-page/1/native-method/"+method);candidate.setPageNo(1);
            candidate.setSource("native_table_candidate");candidate.setText("DO_NOT_EMBED_METHOD_CANDIDATE");
            candidate.setNativeTableCandidate(JsonUtils.parse("{\"method\":\""+method+"\",\"provider\":\"authored-cpu\",\"status\":\"candidate\",\"physicalPageNo\":1,\"frame\":{\"coordinateUnit\":\"pt\"},\"candidate\":{\"schema\":\"authored-independent-table\",\"tables\":[{\"cells\":[{\"text\":\"\",\"rowSpan\":2}],\"glyphs\":[{\"text\":\"DO_NOT_SEND_RAW_GLYPH_AUDIT\"}]}],\"logicalTables\":[],\"conditionCandidates\":[{\"status\":\"unknown\"}],\"unassignedSourceGlyphIds\":[\"actual-unassigned\"],\"completeSourceGroupAccepted\":false,\"quality\":{\"status\":\"unverified\"}},\"bundleRef\":{\"rawPayload\":\"DO_NOT_SEND_CAS_AUDIT\"}}"));
            blocks.add(candidate);
        }
        source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        assertTrue(chunks.stream().noneMatch(chunk->chunk.getContent().contains("DO_NOT_EMBED_METHOD_CANDIDATE")));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));assertFalse(material.toString().contains("DO_NOT_SEND_RAW_GLYPH_AUDIT"));assertFalse(material.toString().contains("DO_NOT_SEND_CAS_AUDIT"));
        JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());assertEquals(3,graph.path("units").size());
        assertEquals("native_table_method",graph.path("units").get(1).path("kind").asText());assertEquals("",graph.path("units").get(1).path("text").asText());
        assertEquals("native-regions",graph.path("units").get(1).path("parts").get(0).path("nativeTableCandidate").path("method").asText());
        assertEquals("docling",graph.path("units").get(2).path("parts").get(0).path("nativeTableCandidate").path("method").asText());
        assertTrue(JsonUtils.write(chunks).contains("DO_NOT_SEND_RAW_GLYPH_AUDIT"),"Original method audit is retained outside compact model material");
    }
    @Test void ordinaryCandidateRetainsWholeNativeTableAndUnreadScopeWithoutAcceptedClaims() {
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);
        JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());
        assertEquals("consense.canonical-candidate.v1",graph.path("schema").asText());
        assertEquals("candidate_unverified",graph.path("adoptionStatus").asText());
        assertFalse(graph.path("humanConfirmed").asBoolean());assertFalse(graph.path("sourceQualityAccepted").asBoolean());
        JsonNode table=graph.path("units").get(0);assertEquals(2,table.path("parts").size());
        assertEquals("native_table",table.path("kind").asText());assertEquals(2,table.path("tableGroup").path("rows").size());
        assertEquals("",table.path("tableGroup").path("rows").get(0).path("cells").get(1).asText());
        assertEquals("unknown",table.path("tableGroup").path("headerMeaning").asText());
        assertTrue(graph.path("unknowns").toString().contains("failed_page"));assertTrue(graph.path("unknowns").toString().contains("needs_review_page"));
        assertFalse(graph.toString().contains("FULL_SOURCE_AUDIT_GLYPH"));
        assertEquals(2,table.path("requiredMemberBlockIds").size());
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));
        assertEquals("candidate_unverified",material.get(0).path("candidateStructure").path("adoptionStatus").asText());
        assertEquals(2,material.get(0).path("candidateStructure").path("units").get(0).path("parts").size());
        assertTrue(material.get(0).path("candidateStructure").path("unknowns").toString().contains("failed_page"));
        assertFalse(material.toString().contains("FULL_SOURCE_AUDIT_GLYPH"));assertFalse(material.get(0).path("candidateStructure").toString().contains("DELETED_UNSELECTED_TEXT"));
        assertNull(chunks.get(0).getCanonicalPayload());assertNull(chunks.get(0).getCanonicalEvidence());
    }

    @Test void candidateGenerationCannotAttachAfterOriginalOrCoverageChanges() {
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);
        String generation=source.getNormalCandidateJson();
        source.setParseCoverageJson("{\"failedPages\":[2,3]}");
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(VettingCorpus.chunks(Collections.singletonList(source))));
        assertEquals("generation_binding_unknown",material.get(0).path("candidateStructure").path("status").asText());
        assertEquals(0,material.get(0).path("candidateStructure").path("units").size());
        assertEquals(generation,source.getNormalCandidateJson(),"Consumer never rewrites a prior generation or grants acceptance");
    }

    @Test void legacySourceRemainsUsableWithoutInventingCandidateAcceptance() {
        SourceDocument source=source();List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));
        assertTrue(material.get(0).path("content").asText().contains("Actual obligation"));
        assertTrue(material.get(0).path("candidateStructure").isNull());
    }

    @Test void partialParagraphCandidateKeepsSelectedEffectiveRangeWithoutFullParentText() {
        SourceDocument source=source();DocumentBlock block=new DocumentBlock();block.setId("long:paragraph");block.setLocation("body/0/paragraph");block.setSource("native");
        block.setText(String.join("",Collections.nCopies(1600,"x"))+"UNSELECTED_PARENT_TAIL");block.setOriginalText("UNSELECTED_REVISION");block.setDeletedText("UNSELECTED_DELETION");block.setWordStructureVersion("docx-numbering-symbol-v1");
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertEquals(2,chunks.size());
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(chunks.get(0))));
        assertFalse(material.toString().contains("UNSELECTED_PARENT_TAIL"));assertFalse(material.toString().contains("UNSELECTED_REVISION"));assertFalse(material.toString().contains("UNSELECTED_DELETION"));
        assertEquals("selected_source_range_only",material.get(0).path("candidateStructure").path("units").get(0).path("closureStatus").asText());
    }

    @Test void conflictedVisualTextRetainsUnknownStructureWithoutEnteringEmbeddingBody() {
        SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);DocumentBlock blocked=new DocumentBlock();blocked.setId("blocked-region");blocked.setLocation("pdf-page/1/visual-region/blocked");
        blocked.setPageNo(1);blocked.setSource("ocr_candidate");blocked.setText("CONFLICTED_VISUAL_LITERAL");blocked.setVisualRegion(JsonUtils.parse("{\"id\":\"blocked\",\"text\":\"CONFLICTED_VISUAL_LITERAL\",\"kind\":\"table\",\"table\":{\"cells\":[{\"text\":\"CONFLICTED_VISUAL_LITERAL\"}]}}"));
        blocked.setReadingContext(JsonUtils.parse("{\"adoptionStatus\":\"blocked_native_visual_conflict\",\"nativeIds\":[\"native-observation\"]}"));blocks.add(blocked);source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertTrue(chunks.stream().noneMatch(c->c.getContent().contains("CONFLICTED_VISUAL_LITERAL")));
        JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());JsonNode visual=graph.path("units").get(1);
        assertEquals("",visual.path("text").asText());assertTrue(visual.path("parts").get(0).path("visualRegion").toString().contains("CONFLICTED_VISUAL_LITERAL"));
        assertEquals("blocked_native_visual_conflict",visual.path("parts").get(0).path("readingContext").path("adoptionStatus").asText());
        assertTrue(visual.path("unknowns").toString().contains("native_visual_conflict"));
    }

    @Test void oneSplitTableSeedActuallyExpandsCompleteCurrentTableMembers() {
        SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);
        DocumentBlock row=blocks.get(0);row.setCells(Arrays.asList(String.join("",Collections.nCopies(1800,"x")),""));row.setText(String.join(" | ",row.getCells()));
        row.getWordCells().get(0).setText(row.getCells().get(0));source.setStructuredContentJson(JsonUtils.write(blocks));source.setTextContent(row.getText()+"\n"+blocks.get(1).getText());
        NormalSourceCandidateGraph.capture(source);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertTrue(chunks.size()>1);
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(chunks.get(0)))).get(0).path("candidateStructure");
        JsonNode unit=material.path("units").get(0);assertEquals("complete_actual_structural_unit",unit.path("closureStatus").asText());assertEquals(2,unit.path("parts").size());
        assertTrue(unit.path("text").asText().contains("Second member"));assertEquals(1800,unit.path("tableGroup").path("rows").get(0).path("cells").get(0).asText().length());
        assertEquals("candidate_unverified",unit.path("adoptionStatus").asText());
    }

    @Test void mutatedCandidateClaimsAndOversizedWholeUnitsCannotBecomeSubmittedPartialUnits() {
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);
        com.fasterxml.jackson.databind.node.ObjectNode saved=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(source.getNormalCandidateJson());saved.put("humanConfirmed",true);source.setNormalCandidateJson(JsonUtils.write(saved));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(VettingCorpus.chunks(Collections.singletonList(source)))).get(0).path("candidateStructure");
        assertEquals("generation_binding_unknown",material.path("status").asText());assertFalse(material.path("humanConfirmed").asBoolean());assertEquals(0,material.path("units").size());
        List<DocumentBlock> blocks=VettingCorpus.blocks(source);DocumentBlock row=blocks.get(0);row.setCells(Arrays.asList(String.join("",Collections.nCopies(70000,"x")),""));row.setText(String.join(" | ",row.getCells()));
        source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);
        IllegalArgumentException blocked=assertThrows(IllegalArgumentException.class,()->VettingCorpus.chunks(Collections.singletonList(source)));
        assertTrue(blocked.getMessage().contains("no truncated unit"));
    }

    private static SourceDocument source() {
        SourceDocument source=new SourceDocument();source.setId(71L);source.setProjectId("authored-candidate");source.setFileName("authored.docx");
        source.setReviewRole("tender");source.setDocumentScope("package");source.setFileKey("SCC");source.setSourceSha256(VettingCorpus.hash("authored original bytes"));
        source.setParseStatus("PARTIAL");source.setParseCoverageJson("{\"complete\":false,\"totalPages\":2,\"failedPages\":[2],\"needsReviewPages\":[1],\"visualCoverage\":[{\"rawPayload\":\"FULL_SOURCE_AUDIT_GLYPH\"}]}");
        List<DocumentBlock> blocks=new ArrayList<>();
        for(int i=0;i<2;i++) {
            DocumentBlock row=new DocumentBlock();row.setId("body:0:table-row:"+i);row.setLocation("body/0/table-row/"+i);row.setSource("native");row.setKind("table_row");
            row.setCells(Arrays.asList(i==0?"Actual obligation":"Second member", ""));row.setText(String.join(" | ",row.getCells()));
            row.setDeletedText("DELETED_UNSELECTED_TEXT");row.setWordStructureVersion("docx-numbering-symbol-v1");
            DocumentBlock.WordCell cell=new DocumentBlock.WordCell();cell.setTableLocation("body/0");cell.setRowIndex(i);cell.setCellIndex(0);cell.setLocation(row.getLocation()+"/cell/0");
            cell.setGridColumn(0);cell.setColumnSpan(1);cell.setRowSpan(1);cell.setTopologyStatus("observed_native");cell.setText(row.getCells().get(0));row.setWordCells(Collections.singletonList(cell));blocks.add(row);
        }
        source.setTextContent("Actual obligation | \nSecond member | ");source.setStructuredContentJson(JsonUtils.write(blocks));return source;
    }
}
