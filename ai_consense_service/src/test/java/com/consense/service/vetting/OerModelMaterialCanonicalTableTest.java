package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.advice.AdviceService;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A ranked normal-canonical row must carry its existing complete physical table to both consumers. */
class OerModelMaterialCanonicalTableTest {
    @Test void advisorySuppliesEachOtherTableRowAsItsOwnRealCitationWithoutBorrowingAnUnrelatedSource() throws Exception {
        SourceDocument source=source();Map<String,VettingCorpus.Chunk> corpus=new LinkedHashMap<>();Map<String,VectorStore.SearchHit> hits=new LinkedHashMap<>();
        for(JsonNode raw:NormalSourceCandidateGraph.requireBound(source).path("units")){
            ObjectNode member=raw.deepCopy();member.put("producerKind","normal_observed");EvidencePointer pointer=pointer(source,member);
            VettingCorpus.Chunk chunk=new VettingCorpus.Chunk();chunk.setId(pointer.getCanonicalChunkId());chunk.setDocumentId(String.valueOf(source.getId()));chunk.setRole("tender");chunk.setCanonicalEvidence(pointer);
            chunk.setParts(JsonUtils.readList(JsonUtils.write(pointer.getParts()),VettingCorpus.Part.class));corpus.put(chunk.getId(),chunk);
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("documentId",String.valueOf(source.getId()));payload.put("projectId",source.getProjectId());payload.put("sourceHash",VettingCorpus.sourceHash(source));payload.put("sourceRole","tender");
            payload.put("anchor",pointer.getParts().get(0).path("anchor").asText());payload.put("content",raw.path("text").asText());payload.put("canonicalEvidence",Collections.singletonList(pointer));payload.put("canonicalMembers",Collections.singletonList(member));
            payload.put("canonicalSourceUnit",JsonUtils.mapper().createObjectNode().put("unitId",pointer.getUnitId()).put("status","complete_observed_unit"));payload.put("scoreKind","context_only");
            hits.put(chunk.getId(),new VectorStore.SearchHit(chunk.getId(),0d,payload));
        }
        VettingCorpus.Chunk selectedRow=corpus.values().stream().filter(chunk->"Excluded library C".equals(chunk.getParts().get(0).getText())).findFirst().get();
        VettingCorpus.Chunk foreign=JsonUtils.read(JsonUtils.write(selectedRow),VettingCorpus.Chunk.class);foreign.setId("foreign-source");foreign.getCanonicalEvidence().setDocumentId(source.getId()+1);corpus.put(foreign.getId(),foreign);
        java.lang.reflect.Constructor<OerBusinessEvidence.Session> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);constructor.setAccessible(true);
        OerBusinessEvidence.Session session=constructor.newInstance(source.getProjectId(),"advisory",Collections.singletonList(source.getId()),Collections.emptyList(),corpus,Collections.emptyMap());
        OerBusinessEvidence canonical=mock(OerBusinessEvidence.class);when(canonical.sourceHits(eq(session),anyList())).thenAnswer(call->{List<VectorStore.SearchHit> result=new ArrayList<>();for(String id:call.<List<String>>getArgument(1))result.add(hits.get(id));return result;});
        SourceDocumentRepository repository=mock(SourceDocumentRepository.class);when(repository.findById(source.getId())).thenReturn(Optional.of(source));
        AdviceService service=new AdviceService(null,null,null,null,null,repository,null,null,null,canonical,null);
        VectorStore.SearchHit seed=hits.values().stream().filter(hit->"Excluded library C".equals(hit.text())).findFirst().get();String unchanged=JsonUtils.write(seed);
        List<VectorStore.SearchHit> selected=ReflectionTestUtils.invokeMethod(service,"withCanonicalTableContext",session,Collections.singletonList(seed));
        assertEquals(4,selected.size());assertSame(seed,selected.get(0));assertEquals(unchanged,JsonUtils.write(seed));
        List<String> texts=new ArrayList<>(),locations=new ArrayList<>();
        for(VectorStore.SearchHit hit:selected){CitationVO citation=ReflectionTestUtils.invokeMethod(service,"toCitation",hit);texts.add(citation.getContent());locations.add(citation.getAnchor());
            assertEquals(hit.text(),citation.getCanonicalEvidence().get(0).getParts().get(0).path("text").asText());assertEquals(source.getId(),citation.getCanonicalEvidence().get(0).getDocumentId());}
        assertEquals(Arrays.asList("Excluded library C","Excluded library A","Excluded library B","Excluded library D"),texts);assertEquals(4,new HashSet<>(locations).size());
        assertEquals(4,selected.stream().map(hit->hit.getPayload().get("canonicalSourceUnit")).distinct().count());
        verify(canonical).sourceHits(eq(session),argThat(ids->ids.size()==4&&!ids.contains("foreign-source")));
    }
    @Test void oneCanonicalRowKeepsAllFourObservedRowsAndLeavesTheOtherTableUnselected() throws Exception {
        SourceDocument source=source();EvidencePointer pointer=pointer(source);String before=JsonUtils.write(source),savedPointer=JsonUtils.write(pointer);
        JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).project(pointer,member(source),"tender");
        JsonNode context=model.path("observedSourceUnits"),units=context.path("units");
        assertEquals(1,units.size(),"Canonical normal consumers must receive the same complete table context as ordinary consumers");
        JsonNode table=units.get(0).path("tableGroup").path("tables");assertEquals(1,table.size());assertEquals(4,table.get(0).path("rows").size());
        for(String text:Arrays.asList("Excluded library A","Excluded library B","Excluded library C","Excluded library D"))assertTrue(units.toString().contains(text),text);
        assertFalse(units.toString().contains("Unrelated table value"));
        assertEquals("Excluded library C",model.path("parts").get(0).path("text").asText(),"Unranked table context must not rewrite the quoted seed");
        assertFalse(context.path("sourceDeclaration").path("sourceQualityAccepted").asBoolean());assertEquals("unknown",context.path("sourceDeclaration").path("legalClosure").asText());
        assertEquals(before,JsonUtils.write(source));assertEquals(savedPointer,JsonUtils.write(pointer));
        SourceJsonWireAssertions.assertRoundTrip(model);
        OerModelMaterial.Request request=new OerModelMaterial.Request(Collections.singletonList(source));
        JsonNode first=request.project(pointer,member(source),"tender"),again=request.project(pointer,member(source),"tender");
        assertEquals(first.path("modelUnitAddress"),again.path("modelUnitRef"));
    }

    @Test void completeContextRequiresTheCurrentProjectDocumentAndOriginalAndParsedIdentities() throws Exception {
        SourceDocument source=source();
        for(String changed:Arrays.asList("project","document","original","parsed")){
            EvidencePointer pointer=pointer(source);
            if(changed.equals("project"))pointer.setProjectId("another-project");
            if(changed.equals("document"))pointer.setDocumentId(source.getId()+1);
            if(changed.equals("original"))pointer.setOriginalSourceSha256(VettingCorpus.hash("another original"));
            if(changed.equals("parsed"))pointer.setJavaParsedSourceHash(VettingCorpus.hash("another parse"));
            JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).project(pointer,member(source),"tender");
            assertFalse(model.has("observedSourceUnits"),changed+" must not borrow the current source table");
        }
    }

    @Test void changedSelectedTextIsRejectedInsteadOfExpandingAnUnboundCanonicalRow() throws Exception {
        SourceDocument source=source();EvidencePointer pointer=pointer(source);
        ((ObjectNode)pointer.getParts().get(0)).put("text","Unbound replacement text");
        assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(Collections.singletonList(source)).project(pointer,member(source),"tender"));
    }

    private SourceDocument source() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(XWPFDocument document=new XWPFDocument()){
            document.createParagraph().createRun().setText("The following libraries are excluded:");
            XWPFTable table=document.createTable(4,1);for(int row=0;row<4;row++)table.getRow(row).getCell(0).setText("Excluded library "+(char)('A'+row));
            document.createParagraph().createRun().setText("A separate provision begins here.");
            document.createTable(1,1).getRow(0).getCell(0).setText("Unrelated table value");document.write(bytes);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parse("table-context.docx",bytes.toByteArray());verifyNoInteractions(ocr);
        SourceDocument source=NormalSourceCandidateGraphTest.ordinaryWordSource();source.setFileKey("SP");source.setFileName("table-context.docx");source.setSourceSha256(parsed.getSourceSha256());source.setTextContent(parsed.getText());
        source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));NormalSourceCandidateGraph.capture(source);return source;
    }
    private JsonNode member(SourceDocument source){
        for(JsonNode unit:NormalSourceCandidateGraph.requireBound(source).path("units"))if(unit.path("text").asText().equals("Excluded library C")){
            ObjectNode member=unit.deepCopy();member.put("producerKind","normal_observed");return member;
        }throw new AssertionError("Actual parsed row missing");
    }
    private EvidencePointer pointer(SourceDocument source){return pointer(source,member(source));}
    private EvidencePointer pointer(SourceDocument source,JsonNode unit){
        EvidencePointer pointer=new EvidencePointer();pointer.setProjectId(source.getProjectId());pointer.setDocumentId(source.getId());pointer.setModule("advisory");
        pointer.setProducerKind("normal_observed");pointer.setProjectionSchema("consense.normal-observed.v1");pointer.setOriginalSourceSha256(source.getSourceSha256());pointer.setJavaParsedSourceHash(VettingCorpus.sourceHash(source));
        pointer.setUnitId(unit.path("unitId").asText());pointer.setCanonicalChunkId("canonical:"+unit.path("unitId").asText());pointer.setParts(unit.path("parts").deepCopy());pointer.setSourceSpans(unit.path("sourceSpans").deepCopy());
        pointer.setTableGroup(unit.path("tableGroup").deepCopy());pointer.setRelations(JsonUtils.mapper().createArrayNode());pointer.setUnknowns(JsonUtils.mapper().createArrayNode());return pointer;
    }
}
