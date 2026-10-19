package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser;
import com.consense.document.ParseCoverage;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** On-demand guidance selection for ordinary Word sources; the persisted parse is never rewritten. */
class WordGuidanceScopesTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String OTHER=String.join("",Collections.nCopies(64,"b"));
    private static final String BODY="/{w}document[1]/{w}body[1]";

    @Test void aSelectedProjectGuidanceNoteIsProjectedWithItsConditionAndCaptureGroup() {
        DocumentParser.ParsedDocument parsed=word(p("title","Special Conditions of Contract"),
                p("g1","[Guidance Note: Insert the amount for SCC7.6.]"),p("body","SCC1.301 Definitions of terms"));
        SourceDocument source=source(parsed,"SCC",true);String before=source.getParseCoverageJson();
        JsonNode guidance=new OerModelMaterial.Request().guidanceScopes(source,chunk("g1"));
        assertNotNull(guidance);
        assertEquals("observed",guidance.path("status").asText());
        assertEquals("SCC",guidance.path("family").asText());
        assertEquals("native-template-rules-20261019-r21",guidance.path("analysisRevision").asText());
        assertEquals(1,guidance.path("guidanceNotes").size());
        assertEquals(1,guidance.path("sourceConditions").size());
        assertEquals(1,guidance.path("guidanceGroups").size());
        assertEquals("docx-p:g1",guidance.path("guidanceNotes").get(0).path("orderedMemberIds").get(0).asText());
        assertEquals(before,source.getParseCoverageJson());
    }

    @Test void unrelatedMembersSelectNothingAndUnsupportedFamiliesStayAbsent() {
        DocumentParser.ParsedDocument parsed=word(p("title","Special Conditions of Contract"),
                p("g1","[Guidance Note: Insert the amount.]"));
        SourceDocument source=source(parsed,"SCC",true);
        JsonNode none=new OerModelMaterial.Request().guidanceScopes(source,chunk("zzz"));
        assertNull(none);
        DocumentParser.ParsedDocument plain=word(p("title","Special Conditions of Contract"));
        assertNull(new OerModelMaterial.Request().guidanceScopes(source(plain,"SCC",true),chunk("title")));
        assertNull(new OerModelMaterial.Request().guidanceScopes(source(parsed,"PRE",true),chunk("g1")));
        assertNull(new OerModelMaterial.Request().guidanceScopes(source(parsed,"SCC",false),chunk("g1")));
    }

    @Test void canonicalPointerGuidanceRequiresMatchingSourceIdentity() {
        DocumentParser.ParsedDocument parsed=word(p("title","Special Conditions of Contract"),
                p("g1","[Guidance Note: Insert the amount.]"));
        SourceDocument source=source(parsed,"SCC",true);
        ObjectNode member=object();member.put("schema",com.consense.service.sources.NormalObservedContract.SCHEMA);
        JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).project(pointer(source),member,"tender");
        assertEquals(1,model.path("observedGuidanceScopes").path("guidanceNotes").size());
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer forged=pointer(source);forged.setJavaParsedSourceHash(OTHER);
        JsonNode absent=new OerModelMaterial.Request(Collections.singletonList(source)).project(forged,member,"tender");
        assertTrue(absent.path("observedGuidanceScopes").isMissingNode());
        JsonNode unmatched=new OerModelMaterial.Request(Collections.singletonList(source)).project(pointer(source,"zzz"),member,"tender");
        assertEquals("unavailable",unmatched.path("observedGuidanceScopes").path("status").asText());
        assertEquals("no_guidance_in_selected_scope",unmatched.path("observedGuidanceScopes").path("reason").asText());
    }

    @Test void pdfObservedGuidanceIsProjectedForOrdinaryAndCanonicalConsumers() {
        SourceDocument source=pdfSource();
        JsonNode ordinary=new OerModelMaterial.Request().guidanceScopes(source,chunk("b1"));
        assertNotNull(ordinary);
        assertEquals("SCC",ordinary.path("family").asText());
        assertEquals(1,ordinary.path("guidanceNotes").size());
        ObjectNode member=object();member.put("schema",com.consense.service.sources.NormalObservedContract.SCHEMA);
        JsonNode canonical=new OerModelMaterial.Request(Collections.singletonList(source)).project(pointer(source,"b1"),member,"tender");
        assertEquals(1,canonical.path("observedGuidanceScopes").path("guidanceNotes").size());
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer forged=pointer(source,"b1");
        forged.setOriginalSourceSha256(OTHER);
        JsonNode absent=new OerModelMaterial.Request(Collections.singletonList(source)).project(forged,member,"tender");
        assertTrue(absent.path("observedGuidanceScopes").isMissingNode());
    }

    private static SourceDocument pdfSource() {
        ArrayNode blocks=JsonUtils.mapper().createArrayNode();
        ObjectNode block=blocks.addObject();
        block.put("id","b1");block.put("kind","pdf_line");block.put("source","native");block.put("pageNo",23);
        block.put("text","[Guidance Note: Insert the amount for SCC7.6.]");
        block.put("originalText","[Guidance Note: Insert the amount for SCC7.6.]");
        block.putArray("bbox").add(0.1).add(0.2).add(0.5).add(0.01);
        ObjectNode context=block.putObject("readingContext");context.put("sourceSha256",SHA);
        SourceDocument source=new SourceDocument();
        source.setId(98L);source.setFileKey("SCC");source.setSourceSha256(SHA);
        source.setTextContent("[Guidance Note: Insert the amount for SCC7.6.]");source.setPageCount(66);
        source.setStructuredContentJson(blocks.toString());
        source.setParseCoverageJson("{\"complete\":false,\"totalPages\":66,\"parsedPages\":66,\"ocrPages\":0,\"failedPages\":[]}");
        return source;
    }

    private static com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer(SourceDocument source) {
        return pointer(source,"g1");
    }

    private static com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer(SourceDocument source,String blockId) {
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer();
        pointer.setProjectId("authored");pointer.setDocumentId(source.getId());
        pointer.setProducerKind(com.consense.service.sources.NormalObservedContract.PRODUCER);
        pointer.setOriginalSourceSha256(SHA);pointer.setJavaParsedSourceHash(VettingCorpus.sourceHash(source));
        pointer.setSourceRevisionSha256(OTHER);pointer.setProjectionSha256(OTHER);pointer.setProjectionRevisionSha256(OTHER);
        pointer.setCanonicalSourceHash(OTHER);
        ArrayNode parts=JsonUtils.mapper().createArrayNode();
        parts.addObject().put("blockId",blockId).put("text","fixture").put("startOffset",0).put("endOffset",7).put("offsetUnit","utf16").put("sourceSha256",SHA);
        pointer.setParts(parts);
        pointer.setSourceSpans(JsonUtils.mapper().createArrayNode());
        pointer.setRelations(JsonUtils.mapper().createArrayNode());
        pointer.setUnknowns(JsonUtils.mapper().createArrayNode());
        return pointer;
    }

    private static SourceDocument source(DocumentParser.ParsedDocument parsed,String fileKey,boolean word) {
        SourceDocument source=new SourceDocument();
        source.setId(97L);source.setFileKey(fileKey);source.setSourceSha256(SHA);
        source.setTextContent("fixture");source.setPageCount(0);
        source.setStructuredContentJson(tree(parsed.getBlocks()).toString());
        source.setParseCoverageJson(word?tree(parsed.getCoverage()).toString():"{}");
        return source;
    }
    private static Chunk chunk(String... blockIds) {
        Chunk chunk=new Chunk();chunk.setId("chunk-1");chunk.setContent("fixture");
        List<Part> parts=new ArrayList<>();
        for(String id:blockIds){Part part=new Part();part.setBlockId(id);part.setText("fixture");part.setStartOffset(0);part.setEndOffset(7);parts.add(part);}
        chunk.setParts(parts);
        return chunk;
    }
    private static DocumentParser.ParsedDocument word(ObjectNode... paragraphs) {
        ParseCoverage coverage=new ParseCoverage();ObjectNode inventory=object();
        inventory.put("version","docx-native-paragraphs-v1");inventory.put("sourceSha256",SHA);
        ArrayNode list=inventory.putArray("paragraphs");for(ObjectNode paragraph:paragraphs)list.add(paragraph);
        coverage.setNativeDocxParagraphs(inventory);
        return new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),0,false,"fixture",Collections.emptyList(),coverage,SHA,Collections.emptyList());
    }
    private static ObjectNode p(String id,String text) {
        ObjectNode p=object();p.put("id","docx-p:"+id);p.put("storyKind","body");p.put("storyRootNodePath",BODY);
        p.putArray("retainedBlockIds").add(id);
        ObjectNode position=p.putObject("sourcePosition");position.put("kind","docx_native");position.put("sourceSha256",SHA);
        position.put("partName","word/document.xml");position.put("partSha256",OTHER);position.put("nodePath",BODY+"/{w}p["+id+"]");
        ObjectNode channels=p.putObject("textObservation");channels.put("status","observed_parser_paragraph_channels");
        channels.put("text",text);channels.put("originalText",text);channels.put("deletedText","");channels.put("strikeText","");
        return p;
    }
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}
}
