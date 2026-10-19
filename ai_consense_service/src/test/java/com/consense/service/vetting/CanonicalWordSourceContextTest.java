package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import com.consense.document.DocumentParser;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.NormalObservedContract;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CanonicalWordSourceContextTest {
    @Test void enumeratedPointersDoNotExpandWholeChapterScopesBeforeAnyModelSelection()throws Exception{
        SourceDocument source=source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source);CanonicalSourceBinding binding=binding(source);
        for(JsonNode unit:graph.path("units")) {
            EvidencePointer pointer=pointer(binding,graph,canonical(graph,unit));
            assertFalse(JsonUtils.mapper().valueToTree(pointer).has("nativeWordSourceScopesContext"),
                    "All-pointer enumeration must stay linear in source members; full chapter context belongs to an actual selected-model request");
        }
    }
    @Test void actualWordGraphAndNormalMapperBindCompleteClauseContextAtPointerRequestThenReachModelAndCompact()throws Exception{
        SourceDocument source=source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source);String before=JsonUtils.write(graph);
        JsonNode unit=unit(graph,"1. Body first clause"),canonical=canonical(graph,unit);CanonicalSourceBinding binding=binding(source);
        EvidencePointer pointer=selectedPointer(binding,graph,canonical);JsonNode context=JsonUtils.mapper().valueToTree(pointer).path("nativeWordSourceScopesContext");
        assertTrue(context.isObject(),"Word source intervals must reach a real normal canonical pointer, not only the ordinary view");
        assertEquals("docx-source-clause-scopes-v1",context.path("version").asText());assertFalse(context.path("ranges").isEmpty());assertFalse(context.path("members").isEmpty());
        assertEquals(graph.path("generationSha256"),context.path("generationSha256"));assertEquals(binding.getProjectionSha256(),context.path("projectionSha256").asText());assertEquals(binding.getSourceRevisionSha256(),context.path("sourceRevisionSha256").asText());
        assertTrue(context.toString().contains("First clause continuation"));assertTrue(context.toString().contains("2. Body second clause"));assertFalse(context.toString().contains("OUTSIDE_NEXT_CHAPTER"));
        JsonNode model=new OerModelMaterial.Request().project(pointer,canonical,"tender"),observed=model.path("observedWordSourceScopes");
        assertTrue(observed.isObject());assertEquals(context.path("ranges"),observed.path("ranges"));assertEquals(context.path("members"),observed.path("members"));
        SourceJsonWireAssertions.assertRoundTrip(model);assertEquals(before,JsonUtils.write(graph));
        assertEquals("1. Body first clause",model.path("parts").get(0).path("text").asText(),"Only actual canonical member text retains quotation authority");
        assertFalse(pointer.isHumanConfirmed());assertTrue(model.path("adoption").isNull());
        ((ObjectNode)context.path("ranges").get(0)).put("aliasProbe",true);assertEquals(before,JsonUtils.write(graph));
    }

    @Test void wordScopeContextRejectsForeignBindingAndModelSourceOrProjectionMutations()throws Exception{
        SourceDocument source=source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source),canonical=canonical(graph,unit(graph,"1. Body first clause"));CanonicalSourceBinding foreign=binding(source);foreign.setOriginalSourceSha256(VettingCorpus.hash("foreign"));
        InvocationTargetException rejected=assertThrows(InvocationTargetException.class,()->selectedPointer(foreign,graph,canonical));assertEquals("NORMAL_WORD_SOURCE_SCOPE_BINDING_MISMATCH",rejected.getCause().getMessage());
        EvidencePointer pointer=selectedPointer(binding(source),graph,canonical);JsonNode raw=JsonUtils.mapper().valueToTree(pointer);assertTrue(raw.path("nativeWordSourceScopesContext").isObject());
        for(String key:Arrays.asList("sourceSha256","originalSourceSha256","sourceRevisionSha256","projectionSha256","projectId","documentId","parsedSourceHash","canonicalChunkId","selectedPartsSha256")){
            ObjectNode changed=(ObjectNode)raw.deepCopy(),context=(ObjectNode)changed.path("nativeWordSourceScopesContext");
            if(Arrays.asList("projectId","documentId","parsedSourceHash").contains(key))((ObjectNode)context.path("source")).put(key,"foreign");else context.put(key,VettingCorpus.hash("foreign"));
            EvidencePointer value=JsonUtils.read(JsonUtils.write(changed),EvidencePointer.class);assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request().project(value,canonical,"tender"),key);
        }
    }

    @Test void oldPointersKeepTheOptionalContextAbsentAndDoNotGainInventedWordScope()throws Exception{
        assertFalse(JsonUtils.mapper().valueToTree(new EvidencePointer()).has("nativeWordSourceScopesContext"));SourceDocument source=source();ObjectNode graph=(ObjectNode)NormalSourceCandidateGraph.requireBound(source);JsonNode canonical=canonical(graph,unit(graph,"1. Body first clause"));graph.remove("nativeDocxOutline");
        assertFalse(JsonUtils.mapper().valueToTree(selectedPointer(binding(source),graph,canonical)).has("nativeWordSourceScopesContext"));
    }

    @Test void contextFromAnotherChunkInTheSameDocumentCannotBorrowSelectedModelAuthority()throws Exception{
        SourceDocument source=source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source),first=canonical(graph,unit(graph,"1. Body first clause")),second=canonical(graph,unit(graph,"2. Body second clause"));
        EvidencePointer target=pointer(binding(source),graph,second),origin=selectedPointer(binding(source),graph,first);
        Map<String,JsonNode> borrowed=Collections.singletonMap(target.getCanonicalChunkId(),origin.getNativeWordSourceScopesContext());
        assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(null,borrowed).project(target,second,"tender"));
    }

    static SourceDocument source()throws Exception{
        OcrClient ocr=mock(OcrClient.class);byte[] bytes;
        try(XWPFDocument document=new XWPFDocument()){
            document.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:style w:type='paragraph' w:styleId='heading'><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='clause'/></w:styles>").getStyles());
            paragraph(document,"Policy","heading");paragraph(document,"1. Body first clause","clause");paragraph(document,"First clause continuation",null);document.createParagraph();paragraph(document,"2. Body second clause","clause");paragraph(document,"Next chapter","heading");paragraph(document,"OUTSIDE_NEXT_CHAPTER",null);
            ByteArrayOutputStream out=new ByteArrayOutputStream();document.write(out);bytes=out.toByteArray();
        }
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parse("authored-word-scopes.docx",bytes);verifyNoInteractions(ocr);
        SourceDocument source=new SourceDocument();source.setId(97L);source.setProjectId("authored");source.setFileName("authored-word-scopes.docx");source.setFileKey("PRE");source.setReviewRole("tender");source.setDocumentScope("fullset");source.setParseStatus(parsed.getParseStatus());source.setSourceSha256(DocumentParseProbe.sha256(bytes));source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));NormalSourceCandidateGraph.capture(source);return source;
    }
    private static void paragraph(XWPFDocument document,String text,String style){XWPFParagraph paragraph=document.createParagraph();if(style!=null)paragraph.setStyle(style);paragraph.createRun().setText(text);}
    private static JsonNode unit(JsonNode graph,String text){for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))if(text.equals(part.path("text").asText()))return unit;throw new AssertionError("Actual producer unit missing");}
    static JsonNode canonical(JsonNode graph,JsonNode unit)throws Exception{
        ObjectNode identity=(ObjectNode)graph.path("source").deepCopy();identity.put("module","vetting").put("sourcePurpose","project_document").put("sourceClass","project_pre");identity.putArray("eligibleModules").add("vetting").add("advisory");Method mapper=NormalObservedContract.class.getDeclaredMethod("chunk",JsonNode.class,JsonNode.class,JsonNode.class,String.class,String.class,String.class,String.class);mapper.setAccessible(true);
        JsonNode chunk=(JsonNode)mapper.invoke(null,unit,graph,identity,VettingCorpus.hash("canonical"),VettingCorpus.hash("revision"),VettingCorpus.hash("projection-revision"),VettingCorpus.hash("manifest"));NormalObservedContract.validateChunk(chunk);return chunk;
    }
    static CanonicalSourceBinding binding(SourceDocument source){CanonicalSourceBinding out=new CanonicalSourceBinding();out.setProjectId(source.getProjectId());out.setDocumentId(source.getId());out.setOriginalSourceSha256(source.getSourceSha256());out.setJavaParsedSourceHash(VettingCorpus.sourceHash(source));out.setCanonicalSourceHash(VettingCorpus.hash("canonical"));out.setSourceRevisionSha256(VettingCorpus.hash("revision"));out.setProjectionSha256(VettingCorpus.hash("projection"));out.setProjectionRevisionSha256(VettingCorpus.hash("projection-revision"));return out;}
    static EvidencePointer pointer(CanonicalSourceBinding binding,JsonNode graph,JsonNode canonical)throws Exception{
        ObjectNode snapshot=JsonUtils.mapper().createObjectNode();snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.putObject("manifest").set("candidateGraph",graph);snapshot.putObject("source").put("sourceFilename","authored-word-scopes.docx");snapshot.putArray("unknowns");
        Method method=CanonicalEvidenceService.class.getDeclaredMethod("pointer",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class,String.class);method.setAccessible(true);return (EvidencePointer)method.invoke(null,binding,snapshot,canonical,"vetting");
    }
    private static EvidencePointer selectedPointer(CanonicalSourceBinding binding,JsonNode graph,JsonNode canonical)throws Exception{
        EvidencePointer out=pointer(binding,graph,canonical);ObjectNode snapshot=JsonUtils.mapper().createObjectNode();snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.putObject("manifest").set("candidateGraph",graph);
        Method method=CanonicalEvidenceService.class.getDeclaredMethod("wordSourceScopeContext",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class);method.setAccessible(true);
        out.setNativeWordSourceScopesContext((JsonNode)method.invoke(null,binding,snapshot,canonical));return out;
    }
}
