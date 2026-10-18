package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalEvidenceException;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.sources.NativePdfCanonicalContract;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalPdfSourceContextTest {
    @Test void verifiedNormalPointerKeepsExactMethodContextAndRevisionPinsWithoutAlias() throws Exception {
        SourceDocument source=PdfSourceGroupConsumptionTest.source(false);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);String before=JsonUtils.write(graph);
        assertFalse(NativePdfCanonicalContract.isNativePdf(graph),"Normal source-group metadata must not impersonate the reviewed native-PDF authority");
        CanonicalSourceBinding binding=binding(source);EvidencePointer pointer=pointer(binding,graph,"row");JsonNode context=JsonUtils.mapper().valueToTree(pointer).path("nativePdfSourceGroupsContext");
        assertTrue(context.isObject(),"The normal canonical pointer must carry its exact manifest source-group closure");assertEquals(source.getSourceSha256(),context.path("originalSourceSha256").asText());assertEquals(binding.getProjectionSha256(),context.path("projectionSha256").asText());assertEquals(binding.getSourceRevisionSha256(),context.path("sourceRevisionSha256").asText());assertEquals(graph.path("generationSha256"),context.path("generationSha256"));
        assertEquals(1,context.path("groups").size());assertEquals("camelot_stream",context.path("groups").get(0).path("methodBinding").path("method").asText());assertTrue(JsonUtils.write(context).contains("(1) EXACT_COMPLETE_NOTE_DEFINITION;"));assertFalse(JsonUtils.write(context).contains("UNRELATED SAME PAGE NOTE (1)"));
        JsonNode retained=(JsonNode)pointer.getClass().getMethod("getNativePdfSourceGroupsContext").invoke(pointer);((ObjectNode)retained.path("groups").get(0)).put("aliasProbe",true);assertEquals(before,JsonUtils.write(graph));assertFalse(pointer.isHumanConfirmed());
    }
    @Test void unrelatedTextGetsNoMethodGroupAndLegacyPointersKeepTheOptionalFieldAbsent() throws Exception {
        SourceDocument source=PdfSourceGroupConsumptionTest.source(false);ObjectNode graph=(ObjectNode)NormalSourceCandidateGraph.requireBound(source);CanonicalSourceBinding binding=binding(source);
        JsonNode unrelated=JsonUtils.mapper().valueToTree(pointer(binding,graph,"outside")).path("nativePdfSourceGroupsContext");assertTrue(unrelated.path("groups").isEmpty());assertFalse(JsonUtils.write(unrelated).contains("EXACT_COMPLETE_NOTE_DEFINITION"));graph.remove("nativePdfSourceGroups");assertFalse(JsonUtils.mapper().valueToTree(pointer(binding,graph,"row")).has("nativePdfSourceGroupsContext"));
        assertFalse(JsonUtils.mapper().valueToTree(new EvidencePointer()).has("nativePdfSourceGroupsContext"));
    }
    @Test void aForeignBindingCannotReceiveAnotherOriginalsPdfGroupContext() throws Exception {
        SourceDocument source=PdfSourceGroupConsumptionTest.source(false);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);CanonicalSourceBinding binding=binding(source);binding.setOriginalSourceSha256(VettingCorpus.hash("different-original"));
        InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->pointer(binding,graph,"row"));assertInstanceOf(CanonicalEvidenceException.class,failure.getCause());assertEquals("NORMAL_PDF_SOURCE_GROUP_BINDING_MISMATCH",failure.getCause().getMessage());
    }
    private static CanonicalSourceBinding binding(SourceDocument source){CanonicalSourceBinding b=new CanonicalSourceBinding();b.setProjectId(source.getProjectId());b.setDocumentId(source.getId());b.setOriginalSourceSha256(source.getSourceSha256());b.setJavaParsedSourceHash(VettingCorpus.sourceHash(source));b.setCanonicalSourceHash(VettingCorpus.hash("canonical-source"));b.setSourceRevisionSha256(VettingCorpus.hash("source-revision"));b.setProjectionSha256(VettingCorpus.hash("projection"));b.setProjectionRevisionSha256(VettingCorpus.hash("projection-revision"));return b;}
    private static EvidencePointer pointer(CanonicalSourceBinding binding,JsonNode graph,String block) throws Exception {
        ObjectNode snapshot=JsonUtils.mapper().createObjectNode();snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.putObject("manifest").set("candidateGraph",graph);snapshot.putObject("source").put("sourceFilename","authored.pdf");snapshot.putArray("unknowns");ObjectNode chunk=null;
        for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))if(block.equals(part.path("blockId").asText()))chunk=unit.deepCopy();assertNotNull(chunk);chunk.put("id","canonical:authored-pointer");
        Method method=CanonicalEvidenceService.class.getDeclaredMethod("pointer",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class,String.class);method.setAccessible(true);return (EvidencePointer)method.invoke(null,binding,snapshot,chunk,"advisory");
    }
}
