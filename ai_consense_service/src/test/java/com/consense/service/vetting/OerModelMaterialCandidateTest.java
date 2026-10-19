package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.SourceDocument;
import com.consense.document.DocumentBlock;
import com.consense.service.advice.AdviceService;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual frozen source literals; no HTTP, DB, model or answer files. */
class OerModelMaterialCandidateTest {
    @Test void anAbsentRealWordSectionPositionRemainsNullInSelectedCanonicalModelAndCompact()throws Exception{
        SourceDocument source=CanonicalWordSourceContextTest.source();JsonNode graph=NormalSourceCandidateGraph.requireBound(source),canonical=CanonicalWordSourceContextTest.canonical(graph,graph.path("units").get(0));
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=CanonicalWordSourceContextTest.pointer(CanonicalWordSourceContextTest.binding(source),graph,canonical);
        JsonNode original=canonical.path("parts").get(0).path("readingContext").path("nativeDocxSection").path("titlePg");assertTrue(original.has("sourcePosition"));assertTrue(original.path("sourcePosition").isNull());assertEquals("absent",original.path("status").asText());String before=JsonUtils.write(pointer),memberBefore=JsonUtils.write(canonical);
        JsonNode model=new OerModelMaterial.Request().project(pointer,canonical,"tender"),part=model.path("parts").get(0);assertEquals(JsonUtils.write(original),JsonUtils.write(part.path("readingContext").path("nativeDocxSection").path("titlePg")),"Absent original Word section positions must stay JSON null instead of becoming empty positions");
        assertFalse(part.has("extractionSource"),"The existing selected-part whitelist remains deliberate");assertEquals(before,JsonUtils.write(pointer));assertEquals(memberBefore,JsonUtils.write(canonical));SourceJsonWireAssertions.assertRoundTrip(model);
    }

    @Test void explicitNullPositionsKeepTheirTypeAtPartSpanAndNestedTableEntrypoints(){
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=positionTypePointer();String before=JsonUtils.write(pointer);JsonNode model=new OerModelMaterial.Request().project(pointer);
        assertAll(()->assertTrue(model.path("parts").get(0).path("sourcePosition").isNull()),()->assertTrue(model.path("sourceSpans").get(0).path("sourcePosition").isNull()),()->assertTrue(model.path("parts").get(0).path("readingContext").path("nativeDocxSection").path("titlePg").path("sourcePosition").isNull()),()->assertTrue(model.path("tableGroup").path("sourcePosition").isNull()));
        assertEquals(before,JsonUtils.write(pointer));SourceJsonWireAssertions.assertRoundTrip(model);
    }

    @Test void malformedNonObjectPositionsStillRejectAtEverySelectedMaterialEntrypoint(){
        for(String location:Arrays.asList("part","span","nested","table"))for(String value:Arrays.asList("[]","[null]","false","0","\"unavailable\"")){
            com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=positionTypePointer();com.fasterxml.jackson.databind.node.ObjectNode owner=(com.fasterxml.jackson.databind.node.ObjectNode)(location.equals("part")?pointer.getParts().get(0):location.equals("span")?pointer.getSourceSpans().get(0):location.equals("table")?pointer.getTableGroup():pointer.getParts().get(0).path("readingContext").path("nativeDocxSection").path("titlePg"));owner.set("sourcePosition",JsonUtils.parse(value));
            IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request().project(pointer),location+" "+value);assertTrue(rejected.getMessage().contains("model_structure_object_required"),location+" "+value);
        }
    }

    @Test void anAlreadyProjectedFootnoteXmlIdentitySurvivesModelReprojectionAndRawXmlStillWins(){
        String xml="<w:footnoteReference xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' w:id='2'/>",hash=VettingCorpus.hash(xml);com.fasterxml.jackson.databind.node.ArrayNode raw=JsonUtils.mapper().createArrayNode();com.fasterxml.jackson.databind.node.ObjectNode object=raw.addObject();object.put("kind","footnoteReference");object.put("status","native_note_reference_resolved");object.put("semanticStatus","unknown");object.put("location","body/0/paragraph/node/4/node/1");object.put("sourcePart","/word/document.xml");object.put("nativeXml",xml);object.put("nativeXmlSha256",VettingCorpus.hash("incorrect old identity"));object.putObject("noteReference").put("rawId","2").put("bindingStatus","resolved");String before=JsonUtils.write(raw);
        JsonNode first=OerModelMaterial.wordNativeObjects(raw),again=OerModelMaterial.wordNativeObjects(first);assertEquals(hash,first.get(0).path("nativeXmlSha256").asText());assertFalse(first.get(0).has("nativeXml"));
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=positionTypePointer();((com.fasterxml.jackson.databind.node.ObjectNode)pointer.getParts().get(0)).set("wordNativeObjects",first.deepCopy());JsonNode model=new OerModelMaterial.Request().project(pointer);
        assertAll(()->assertEquals(JsonUtils.write(first),JsonUtils.write(again),"The graph's already-projected XML identity must not disappear on the selected model projection"),()->assertEquals(hash,model.path("parts").get(0).path("wordNativeObjects").get(0).path("nativeXmlSha256").asText()));
        assertFalse(JsonUtils.write(model).contains("<w:footnoteReference"));assertEquals(before,JsonUtils.write(raw));SourceJsonWireAssertions.assertRoundTrip(model);
    }

    private com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer positionTypePointer(){
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer();pointer.setProducerKind("native_docx");pointer.setUnitId("position-type-unit");pointer.setCanonicalChunkId("position-type-chunk");pointer.setParts(JsonUtils.parse("[{\"text\":\"Original source observation\",\"sourcePosition\":null,\"readingContext\":{\"nativeDocxSection\":{\"titlePg\":{\"present\":false,\"status\":\"absent\",\"sourcePosition\":null}}}}]"));pointer.setSourceSpans(JsonUtils.parse("[{\"blockId\":\"source-block\",\"sourcePosition\":null}]"));pointer.setTableGroup(JsonUtils.parse("{\"sourcePosition\":null}"));pointer.setRelations(JsonUtils.mapper().createArrayNode());pointer.setUnknowns(JsonUtils.mapper().createArrayNode());return pointer;
    }

    @Test void wordNativeMathProjectionRetainsReadableStateWithoutExpandingRawXml() {
        String xml="<m:oMath xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math'>"
                +String.join("",Collections.nCopies(1000,"source-only-proof"))+"</m:oMath>";
        for(String state:new String[]{"projected","unresolved"}) {
            com.fasterxml.jackson.databind.node.ObjectNode nativeObject=JsonUtils.mapper().createObjectNode();
            nativeObject.put("kind","math");nativeObject.put("location","body/0/paragraph/node/1");
            nativeObject.put("status","native_ooxml_unrendered");nativeObject.put("semanticStatus","unknown");nativeObject.put("nativeXml",xml);
            com.fasterxml.jackson.databind.node.ObjectNode projection=nativeObject.putObject("mathProjection");
            projection.put("encoding","omml_linear_v1");projection.put("status",state);projection.put("text","projected".equals(state)?"frac(a,b)":"");
            if("unresolved".equals(state))projection.put("reason","unsupported_omml_construct:phant");
            com.fasterxml.jackson.databind.node.ArrayNode raw=JsonUtils.mapper().createArrayNode();raw.add(nativeObject);
            String immutable=JsonUtils.write(raw);JsonNode selected=OerModelMaterial.wordNativeObjects(raw).get(0);
            assertEquals(projection,selected.path("mathProjection"),"Readable native math state and unknown reason must reach usable material unchanged");
            assertEquals("omml_linear_v1",selected.path("mathProjection").path("encoding").asText());
            assertEquals(state,selected.path("mathProjection").path("status").asText());
            assertEquals(projection.path("text"),selected.path("mathProjection").path("text"));
            assertEquals(projection.get("reason"),selected.path("mathProjection").get("reason"));
            assertEquals(VettingCorpus.hash(xml),selected.path("nativeXmlSha256").asText());
            assertFalse(selected.has("nativeXml"));assertFalse(JsonUtils.write(selected).contains("source-only-proof"));
            assertEquals(immutable,JsonUtils.write(raw),"Projection must not mutate original native math evidence");
        }
        com.fasterxml.jackson.databind.node.ArrayNode legacy=JsonUtils.mapper().createArrayNode();legacy.addObject().put("kind","drawing").put("nativeXml",xml);
        assertFalse(OerModelMaterial.wordNativeObjects(legacy).get(0).has("mathProjection"),"Legacy native objects must not gain invented math metadata");
    }
    @Test void inventoryTreesAreSharedOncePerRequestAndLegacyCompleteJsonStaysExact()throws Exception{
        SourceDocument source=NormalSourceCandidateGraphTest.nativeInventorySource(89);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertTrue(chunks.size()>1);
        for(VettingCorpus.Chunk chunk:chunks)for(VettingCorpus.Part part:chunk.getParts()){
            assertTrue(part.getWordCells()==null||part.getWordCells().isEmpty(),"Real split carriers omit whole WordCells");
            assertFalse(part.getSourcePosition().path("nodePath").asText().matches(".*\\}tr\\[[1-9][0-9]*\\]$"),"This mixed carrier must exercise the non-row prefilter gap");
        }
        Method fingerprint=VettingRetrievalClient.class.getDeclaredMethod("corpusFingerprint",List.class);fingerprint.setAccessible(true);Object strict=fingerprint.invoke(null,chunks);
        String frozen=JsonUtils.write(source),before=JsonUtils.write(chunks);OerModelMaterial.Request request=new OerModelMaterial.Request(Collections.singletonList(source));
        JsonNode first=request.projectOrdinary(chunks.get(0)),again=request.projectOrdinary(chunks.get(1));JsonNode unit=first.path("units").get(0);
        assertEquals("ordinary-native-docx-table-tree-model-v1",first.path("projectionVersion").asText());assertEquals(unit.path("modelUnitAddress"),again.path("units").get(0).path("modelUnitRef"));
        assertFalse(again.has("sourceDeclaration"));assertFalse(again.path("units").get(0).has("tableGroup"));assertEquals(90,unit.path("tableGroup").path("tables").get(0).path("rows").size());
        assertEquals(first,new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(chunks.get(0)));assertEquals(frozen,JsonUtils.write(source));assertEquals(before,JsonUtils.write(chunks));assertEquals(strict,fingerprint.invoke(null,chunks));
        VettingCorpus.Chunk broken=JsonUtils.read(JsonUtils.write(chunks.get(0)),VettingCorpus.Chunk.class);broken.setContent("different selected body");
        assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(broken));
        assertNull(new OerModelMaterial.Request().projectOrdinary(chunks.get(0)));assertNull(new OerModelMaterial.Request(Collections.emptyList()).projectOrdinary(chunks.get(0)));
        SourceDocument absent=JsonUtils.read(frozen,SourceDocument.class);com.fasterxml.jackson.databind.node.ObjectNode coverage=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(absent.getParseCoverageJson());coverage.remove("nativeDocxTables");absent.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(absent);
        List<VettingCorpus.Chunk> absentChunks=VettingCorpus.chunks(Collections.singletonList(absent));
        assertEquals(JsonUtils.write(VettingSourceMaterial.project(absentChunks)),JsonUtils.write(VettingSourceMaterial.project(absentChunks,Collections.singletonList(absent))),"No-inventory split/non-row complete ordinary JSON must remain the old no-context result");
        absent.setNormalCandidateJson("deliberately invalid saved graph");assertNull(new OerModelMaterial.Request(Collections.singletonList(absent)).projectOrdinary(absentChunks.get(0)),"Old word=false and no inventory must return before graph validation");
        SourceDocument missingGraph=JsonUtils.read(frozen,SourceDocument.class);missingGraph.setNormalCandidateJson(null);assertNull(new OerModelMaterial.Request(Collections.singletonList(missingGraph)).projectOrdinary(chunks.get(0)));
        SourceDocument legacy=NormalSourceCandidateGraphTest.ordinaryWordSource();VettingCorpus.Chunk oldChunk=VettingCorpus.chunks(Collections.singletonList(legacy)).get(0);
        JsonNode graph=NormalSourceCandidateGraph.requireBound(legacy),raw=NormalSourceCandidateGraph.selectedForModel(graph,oldChunk),actual=new OerModelMaterial.Request(Collections.singletonList(legacy)).projectOrdinary(oldChunk);
        com.fasterxml.jackson.databind.node.ObjectNode identity=JsonUtils.mapper().createObjectNode();identity.set("source",graph.path("source").deepCopy());identity.set("generationSha256",graph.path("generationSha256").deepCopy());identity.set("coverageSha256",graph.path("coverageSha256").deepCopy());identity.put("projectionVersion","ordinary-physical-word-table-model-v1");
        String ref="ordinary-model-source:"+VettingCorpus.hash(JsonUtils.write(identity));
        assertEquals(ref,actual.path("sourceDeclarationRef").asText());assertEquals("ordinary-physical-word-table-model-v1",actual.path("projectionVersion").asText());
        for(int i=0;i<raw.path("units").size();i++){
            JsonNode u=raw.path("units").get(i);assertEquals("ordinary-model-unit:"+VettingCorpus.hash(JsonUtils.write(Arrays.asList(identity,u))),actual.path("units").get(i).path("modelUnitAddress").asText());
            assertEquals(u.path("tableGroup"),actual.path("units").get(i).path("tableGroup"));assertLiteralParts(u.path("parts"),actual.path("units").get(i).path("parts"));
        }
        assertEquals(actual,new OerModelMaterial.Request(Collections.singletonList(JsonUtils.read(JsonUtils.write(legacy),SourceDocument.class))).projectOrdinary(oldChunk));
    }
    @Test void actualHeaderFooterNotesKeepPartAndPhysicalOrderWithoutInventingUnselectedTables()throws Exception{
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(org.apache.poi.xwpf.usermodel.XWPFDocument doc=new org.apache.poi.xwpf.usermodel.XWPFDocument()){
            doc.createParagraph().createRun().setText(String.join(" ",Collections.nCopies(150,"Plain body without table")));
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createTable(1,2).getRow(0).getCell(0).setText(String.join(" ",Collections.nCopies(300,"Repeated literal")));
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.FIRST).createTable(1,2).getRow(0).getCell(0).setText("Repeated literal");
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createTable(1,2).getRow(0).getCell(0).setText("Repeated literal");
            for(int id:new int[]{9,2}){org.apache.poi.xwpf.usermodel.XWPFFootnote note=doc.createFootnote();note.getCTFtnEdn().setId(java.math.BigInteger.valueOf(id));noteTable(note.getCTFtnEdn(),"Footnote "+id);}
            org.apache.poi.xwpf.usermodel.XWPFFootnote separator=doc.createFootnote();separator.getCTFtnEdn().setId(java.math.BigInteger.ZERO);noteTable(separator.getCTFtnEdn(),"Unselected separator literal");
            org.apache.poi.xwpf.usermodel.XWPFEndnote end=doc.createEndnote();end.getCTFtnEdn().setId(java.math.BigInteger.valueOf(5));noteTable(end.getCTFtnEdn(),"Endnote 5");doc.write(out);
        }
        com.consense.ocr.OcrClient ocr=mock(com.consense.ocr.OcrClient.class);com.consense.document.DocumentParser.ParsedDocument parsed=new com.consense.document.DocumentParser(new com.consense.config.ConsenseProperties(),ocr).parse("authored-stories.docx",out.toByteArray());
        SourceDocument source=NormalSourceCandidateGraphTest.ordinaryWordSource();source.setSourceSha256(parsed.getSourceSha256());source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));OerModelMaterial.Request request=new OerModelMaterial.Request(Collections.singletonList(source));Set<String> addresses=new HashSet<>(),identities=new HashSet<>(),paths=new HashSet<>(),carriers=new HashSet<>();int cells=0,empty=0,body=0;boolean splitStory=false;
        for(DocumentBlock block:VettingCorpus.blocks(source))if(!block.getWordCells().isEmpty())carriers.add(block.getId());
        for(VettingCorpus.Chunk chunk:chunks){JsonNode ordinary=request.projectOrdinary(chunk);boolean noCells=chunk.getParts().stream().noneMatch(p->carriers.contains(p.getBlockId()));if(noCells){body++;assertNull(ordinary);continue;}
            if(chunk.getParts().stream().anyMatch(p->carriers.contains(p.getBlockId())&&(p.getWordCells()==null||p.getWordCells().isEmpty())&&!p.getSourcePosition().path("nodePath").asText().matches(".*\\}tr\\[[1-9][0-9]*\\]$"))){splitStory=true;assertNotNull(ordinary);assertFalse(ordinary.path("units").isEmpty());}
            for(JsonNode unit:ordinary.path("units"))if(unit.has("tableGroup")){
                assertTrue(addresses.add(unit.path("modelUnitAddress").asText()));JsonNode position=unit.path("tableGroup").path("sourcePosition");paths.add(position.path("nodePath").asText());assertTrue(identities.add(position.path("partName").asText()+":"+position.path("nodePath").asText()));
                JsonNode loaded=unit.path("tableGroup").path("loadedRoot");assertTrue(position.path("nodePath").asText().startsWith(loaded.path("nodePath").asText()+"/"));
                assertEquals("unknown",unit.path("tableGroup").path("effectiveContentStatus").asText());assertFalse(unit.path("tableGroup").path("semanticScopeVerified").asBoolean());
                for(JsonNode cell:unit.path("tableGroup").findValues("wordCell")){cells++;if(cell.path("text").asText().isEmpty())empty++;}
                assertTrue(unit.path("parts").isEmpty());assertTrue(unit.path("sourceSpans").isEmpty());assertEquals("unknown",unit.path("legalClosure").asText());assertFalse(JsonUtils.write(unit).contains("Unselected separator literal"));
            }
        }
        assertTrue(body>0);assertTrue(splitStory,"Actual long header story must split without whole WordCells and still select its table");assertEquals(6,addresses.size());assertEquals(6,identities.size());assertTrue(paths.size()<identities.size(),"Same native path in distinct header parts must not be merged");assertEquals(12,cells);assertEquals(6,empty);verifyNoInteractions(ocr);
        for(int kind=0;kind<2;kind++){
            SourceDocument invalid=JsonUtils.read(JsonUtils.write(source),SourceDocument.class);
            if(kind==0){com.fasterxml.jackson.databind.node.ObjectNode coverage=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(invalid.getParseCoverageJson());
                for(JsonNode part:coverage.path("nativeDocxTables").path("parts"))if("word/footnotes.xml".equals(part.path("partName").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)part.path("loadedRoots").get(0)).put("noteId","999");invalid.setParseCoverageJson(JsonUtils.write(coverage));
            }else{
                List<DocumentBlock> blocks=VettingCorpus.blocks(invalid);List<DocumentBlock> notes=new ArrayList<>();for(DocumentBlock block:blocks)if("footnote".equals(block.getKind()))notes.add(block);
                ((com.fasterxml.jackson.databind.node.ObjectNode)notes.get(0).getSourcePosition()).set("nodePath",notes.get(1).getSourcePosition().path("nodePath"));invalid.setStructuredContentJson(JsonUtils.write(blocks));
            }
            NormalSourceCandidateGraph.capture(invalid);VettingCorpus.Chunk selected=VettingCorpus.chunks(Collections.singletonList(invalid)).stream().filter(c->c.getParts().stream().anyMatch(p->p.getWordCells()!=null&&!p.getWordCells().isEmpty())).findFirst().get();
            assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(Collections.singletonList(invalid)).projectOrdinary(selected));
        }
        // The native namespace is part of each identity; a strict-namespace observation uses the same new branch.
        SourceDocument strict=NormalSourceCandidateGraphTest.nativeInventorySource(2);strict.setStructuredContentJson(strict.getStructuredContentJson().replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main","http://purl.oclc.org/ooxml/wordprocessingml/main"));strict.setParseCoverageJson(strict.getParseCoverageJson().replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main","http://purl.oclc.org/ooxml/wordprocessingml/main"));NormalSourceCandidateGraph.capture(strict);
        assertEquals(3,new OerModelMaterial.Request(Collections.singletonList(strict)).projectOrdinary(VettingCorpus.chunks(Collections.singletonList(strict)).get(0)).path("units").get(0).path("tableGroup").path("tables").size());
    }
    private static void noteTable(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFtnEdn note,String value){
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow row=note.addNewTbl().addNewTr();row.addNewTc().addNewP().addNewR().addNewT().setStringValue(value);row.addNewTc().addNewP();
    }
    @Test void dictionarySharingRequiresExactCompletePointerCanonicalPayloadAndRole()throws Exception{
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();VettingCorpus.Chunk c=w.load("vetting").chunks().get(3);
        OerModelMaterial.Request r=new OerModelMaterial.Request();JsonNode first=r.project(c.getCanonicalEvidence(),c.getCanonicalPayload(),"standard");
        JsonNode repeated=r.project(c.getCanonicalEvidence(),c.getCanonicalPayload(),"standard");assertEquals(first.path("modelUnitAddress"),repeated.path("modelUnitRef"));assertFalse(repeated.has("parts"));
        JsonNode otherRole=r.project(c.getCanonicalEvidence(),c.getCanonicalPayload(),"project_fact");assertTrue(otherRole.has("parts"));assertNotEquals(first.path("modelUnitAddress"),otherRole.path("modelUnitAddress"));
        com.fasterxml.jackson.databind.node.ObjectNode authoredVariant=c.getCanonicalPayload().deepCopy();authoredVariant.put("authoredIdentityVariant","negative sharing control only");
        JsonNode otherPayload=r.project(c.getCanonicalEvidence(),authoredVariant,"standard");assertTrue(otherPayload.has("parts"));assertNotEquals(first.path("modelUnitAddress"),otherPayload.path("modelUnitAddress"));
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer authoredPointer=JsonUtils.read(JsonUtils.write(c.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)authoredPointer.getAdoption()).put("authoredAdoptionVariant","negative sharing control only");
        JsonNode otherAdoption=r.project(authoredPointer,c.getCanonicalPayload(),"standard");assertTrue(otherAdoption.has("parts"));assertNotEquals(first.path("modelUnitAddress"),otherAdoption.path("modelUnitAddress"));
    }
    static void assertLiteralParts(JsonNode original,JsonNode projected){
        assertEquals(original.size(),projected.size(),"Whole-unit part inventory must remain");
        for(int i=0;i<original.size();i++){
            JsonNode raw=original.get(i),model=projected.get(i);
            for(String key:Arrays.asList("text","anchor","blockId","startOffset","endOffset","offsetUnit","physicalPageNo","bbox","bboxConvention","referenceRegionId","textChannel","nativeEditingState","nativeSemanticQuality","nativeLimitations","nativeEffectiveVisibility","geometryStatus","qualityAttributes"))
                if(raw.has(key))assertEquals(JsonUtils.write(raw.get(key)),JsonUtils.write(model.get(key)),"Original business field lost: "+key);
            for(String key:Arrays.asList("kind","physicalPageNo","partName","nodePath","partSha256","sourceSha256"))if(raw.path("sourcePosition").has(key))
                assertEquals(raw.path("sourcePosition").get(key),model.path("sourcePosition").get(key),"Original source position lost: "+key);
            if(raw.path("textTransform").has("textChannel"))assertEquals(raw.path("textTransform").get("textChannel"),model.path("textTransform").get("textChannel"));
            if(raw.has("cells"))assertEquals(raw.get("cells"),model.get("cells"),"Whole original table cells must remain");
        }
    }
    static void assertRelations(JsonNode original,JsonNode projected){
        assertEquals(original.size(),projected.size());
        for(int i=0;i<original.size();i++)for(String key:Arrays.asList("type","status","scope","basis","humanConfirmed","fromUnitId","toUnitId","fromRegionId","toRegionId","fromBlockId","toBlockId","fromCellId","toCellId","condition","qualifiers","exceptions"))
            if(original.get(i).has(key))assertEquals(original.get(i).get(key),projected.get(i).get(key),"Relation semantic endpoint or declaration lost: "+key);
    }
    @Test void docxUnknownsKeepExactSelectedUnitAttributesAndUnsupportedSchemasReject()throws Exception{
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);List<VettingCorpus.Chunk> chunks=w.load().chunks();
        OerModelMaterial.Request request=new OerModelMaterial.Request();
        for(VettingCorpus.Chunk c:chunks){JsonNode projected=request.project(c.getCanonicalEvidence());List<JsonNode> expected=new ArrayList<>();
            for(JsonNode u:c.getCanonicalEvidence().getUnknowns())if(c.getCanonicalEvidence().getUnitId().equals(u.path("unitId").asText()))expected.add(u);
            assertEquals(JsonUtils.mapper().valueToTree(expected),projected.path("unknowns"));assertLiteralParts(c.getCanonicalPayload().path("parts"),projected.path("parts"));
        }
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer p=JsonUtils.read(JsonUtils.write(chunks.get(0).getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)p.getUnknowns().get(0)).put("unrecognizedMeaning","Keep me unknown");
        assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request().project(p));
    }
    @Test void exactPdfRegionUnknownsAreLocalWhileSourceUnknownSummariesNeverUpgradeState()throws Exception{
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();OerModelMaterial.Request request=new OerModelMaterial.Request();
        int declarations=0,local=0;for(VettingCorpus.Chunk c:w.load("vetting").chunks()){
            JsonNode p=request.project(c.getCanonicalEvidence());if(p.has("sourceDeclaration")){declarations++;assertFalse(p.path("sourceDeclaration").path("limits").path("semanticScopeVerified").asBoolean());assertEquals("unknown",p.path("sourceDeclaration").path("limits").path("wholeOriginalSourceCoverage").asText());}
            Set<String> regions=new HashSet<>();for(JsonNode part:c.getCanonicalEvidence().getParts())regions.add(part.path("referenceRegionId").asText());
            for(JsonNode u:p.path("unknowns")){local++;assertEquals("unknown",u.path("status").asText());assertTrue(regions.stream().anyMatch(r->u.path("path").asText().startsWith("regions."+r+".")));assertTrue(c.getCanonicalEvidence().getUnknowns().findValues("reason").contains(u.get("reason")));}
        }assertEquals(1,declarations);assertTrue(local>0);
    }
    @Test void nativeWholeTableRetainsBlanksMergeValuesNestingAndOriginalCellPositions()throws Exception{
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire(true);VettingCorpus.Chunk table=w.load().chunks().get(1);
        JsonNode raw=table.getCanonicalPayload(),projected=new OerModelMaterial.Request().project(table.getCanonicalEvidence());
        assertLiteralParts(raw.path("parts"),projected.path("parts"));assertEquals(5,projected.path("parts").size());
        assertEquals(3,projected.path("parts").findValues("text").stream().filter(n->n.asText().isEmpty()).count());
        for(String key:Arrays.asList("nativeParagraphIds","nativeTableIds"))assertEquals(raw.path("tableGroup").get(key),projected.path("tableGroup").get(key));
        for(String key:Arrays.asList("cellId","cellIndex","rowIndex","childParagraphIds","nestedTableIds","val","present","gridInterpretationStatus","rowSpanStatus","nodePath","partName"))
            assertEquals(raw.path("tableGroup").findValues(key),projected.path("tableGroup").findValues(key),"Whole-table semantic structure lost: "+key);
        assertFalse(JsonUtils.write(projected).contains("observerIdentitySha256"));assertFalse(JsonUtils.write(projected).contains("relativePath"));
    }
    @Test void actualAdviceWriterKeepsCompleteBodyWithoutSerializingWholeSourceCaptureForEachMember() throws Exception {
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();
        List<VectorStore.SearchHit> hits=w.service.adviceHits(w.load("advisory"),"source-only replay");
        String immutable=JsonUtils.write(hits);AdviceService advice=mock(AdviceService.class,CALLS_REAL_METHODS);
        Method render=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);render.setAccessible(true);
        String prompt=(String)render.invoke(advice,hits);
        Path out=Paths.get(System.getenv("CONSENSE_OER_REPLAY_OUTPUT"));
        Files.write(out.resolve("actual-advice-model-material.txt"),prompt.getBytes(StandardCharsets.UTF_8));
        OerAdviceDisplayDedupTest.assertCompleteBodies(hits,prompt);
        assertTrue(prompt.contains("Canonical body: hitMembership[0]"),"Complete literal body must be directly navigable in the same request dictionary");
        assertTrue(prompt.contains("legalClosureVerified=false"));assertTrue(prompt.contains("descendants are unranked source context"));
        assertEquals(immutable,JsonUtils.write(hits),"Projection must not mutate the public source evidence");
        assertFalse(prompt.contains("\"captureCompleteness\""),"Whole-source capture repeated in model input");
        assertFalse(prompt.contains("\"nativeGlyphMembershipRef\""),"CAS proof belongs to the source verifier");
        assertTrue(prompt.length()<250000,"Actual 31-member source context metadata dominates model request: "+prompt.length());
    }
    @Test void actualVettingModelMaterialKeepsBodiesButUsesASeparateBoundedProjection()throws Exception {
        OerTypedHierarchyBusinessTest.Wire w=new OerTypedHierarchyBusinessTest.Wire();
        List<VettingCorpus.Chunk> chunks=w.load("vetting").chunks();String immutable=JsonUtils.write(chunks);
        List<Map<String,Object>> projected=VettingSourceMaterial.project(chunks);String wire=VettingSourceMetadataWire.write(projected);
        JsonNode decoded=VettingSourceMetadataWire.decode(wire);
        for(int i=0;i<chunks.size();i++)assertEquals(chunks.get(i).getContent(),decoded.get(i).path("content").asText());
        assertEquals(immutable,JsonUtils.write(chunks));
        assertFalse(wire.contains("captureCompleteness"),"Request sharing still encodes whole-source proof tree");
        assertFalse(wire.contains("nativeGlyphMembershipRef"));
        assertTrue(wire.length()<400000,"Selected field projection must precede wire budgeting: "+wire.length());
    }
}
