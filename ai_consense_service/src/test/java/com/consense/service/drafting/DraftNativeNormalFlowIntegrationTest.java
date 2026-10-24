package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.domain.*;
import com.consense.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fresh actual 2a compiler and isolated MVC/JPA; real finalization, synthetic project identity, no visual acceptance. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftNativeNormalFlowIntegrationTest {
    @org.springframework.boot.test.mock.mockito.MockBean com.consense.service.export.EnglishFileOutput englishFileOutput;
    @org.junit.jupiter.api.BeforeEach void englishDeliveryBoundary(){EnglishFileOutputNoopFixture.configure(englishFileOutput);}
    private static final String DB=UUID.randomUUID().toString();
    private static final String NUMBER="E2E-F17-20261020",TITLE="E2E FORMAL17 PARTIAL PACKAGE - SYNTHETIC ACCEPTANCE PROJECT";
    private static final String[] KEYS={"NTT","SCT","SCC"};
    private static final String[] NAMES={"01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"};
    private static final String[] HASHES={"60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"};
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:native_normal_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","native-normal-uploads",DB).toAbsolutePath().toString());
        r.add("consense.drafting.renderer-kind",()->"libreoffice");
        r.add("consense.drafting.renderer-executable",()->System.getProperty("consense.acceptance.rendererExecutable",""));
        r.add("consense.drafting.renderer-work-root",()->System.getProperty("consense.acceptance.pdfWorkRoot","target/unconfigured-native-normal-renderer"));
        r.add("consense.drafting.renderer-timeout-ms",()->120000);
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired DraftDocumentRepository documents;
    @Autowired DraftArtifactRepository artifacts;
    @Autowired DraftPdfArtifactRepository pdfArtifacts;
    @Autowired SourceDocumentRepository sources;
    @MockBean AiGateway ai;
    @SpyBean(proxyTargetAware=true) DraftingService drafting;
    private DraftTocFinalizer.Result observedToc;
    private int requestNumber;
    private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private Path evidence()throws IOException {String dir=System.getProperty("consense.acceptance.artifactEvidence");assertNotNull(dir,"Explicit evidence namespace required");Path out=Paths.get(dir);Files.createDirectories(out);return out;}
    private void bytes(String name,byte[] bytes)throws IOException {Files.write(evidence().resolve(name),bytes,StandardOpenOption.CREATE_NEW);}
    private void text(String name,String value)throws IOException {bytes(name,(value==null?"null":value).getBytes(StandardCharsets.UTF_8));}
    private void json(String name,Object value)throws IOException {text(name,JsonUtils.write(value));}
    private MockHttpServletResponse perform(String method,String path,Object body,MockMultipartFile file)throws Exception {
        int number=++requestNumber;String prefix=String.format(Locale.ROOT,"request-%03d",number);
        MockHttpServletRequestBuilder request=file!=null?multipart(path).file(file):"GET".equals(method)?get(path):"PUT".equals(method)?put(path):post(path);
        String payload=body==null?null:JsonUtils.write(body);if(payload!=null)request.contentType(MediaType.APPLICATION_JSON).content(payload);
        if(file!=null)bytes(prefix+"-file.bin",file.getBytes());
        json(prefix+".json",map("transport","in_process_MockMvc_no_network_socket","method",method,"path",path,"body",payload,"multipart",file==null?null:map("field",file.getName(),"filename",file.getOriginalFilename(),"contentType",file.getContentType(),"bytes",file.getSize(),"sha256",DraftPdfConverter.sha256(file.getBytes()),"binaryFile",prefix+"-file.bin")));
        MockHttpServletResponse response=mvc.perform(request).andReturn().getResponse();bytes(prefix+"-response.bin",response.getContentAsByteArray());Map<String,Object> headers=new LinkedHashMap<>();for(String header:response.getHeaderNames())headers.put(header,new ArrayList<>(response.getHeaders(header)));
        json(prefix+"-response.json",map("status",response.getStatus(),"headers",headers,"contentType",response.getContentType(),"bytes",response.getContentAsByteArray().length,"sha256",DraftPdfConverter.sha256(response.getContentAsByteArray()),"binaryFile",prefix+"-response.bin"));
        assertEquals(200,response.getStatus());return response;
    }
    private JsonNode call(String method,String path,Object body,int code)throws Exception {JsonNode response=JsonUtils.parse(new String(perform(method,path,body,null).getContentAsByteArray(),StandardCharsets.UTF_8));assertEquals(code,response.path("code").asInt(),response.toString());return response.path("data");}
    private byte[] word(String id,String key,String name)throws Exception {MockHttpServletResponse response=perform("GET","/api/drafting/"+id+"/documents/"+key+"/export.docx",null,null);assertTrue(response.getContentType().contains("wordprocessingml"));byte[] result=response.getContentAsByteArray();bytes(name,result);return result;}
    private static Map<String,byte[]> parts(byte[] docx)throws IOException {Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(docx))){ZipEntry e;byte[] buffer=new byte[8192];while((e=in.getNextEntry())!=null){assertFalse(result.containsKey(e.getName()),"Duplicate package entry");ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);result.put(e.getName(),out.toByteArray());}}return result;}
    private static Map<String,String> hashes(Map<String,byte[]> parts){Map<String,String> out=new LinkedHashMap<>();for(Map.Entry<String,byte[]> e:parts.entrySet())out.put(e.getKey(),DraftPdfConverter.sha256(e.getValue()));return out;}
    private static Map<String,Object> layout(DraftArtifact artifact){for(Object raw:JsonUtils.readList(artifact.getLedgerJson(),Object.class)){Map<String,Object> row=DraftBusinessRules.asMap(raw);if("native_layout".equals(row.get("kind")))return row;}throw new AssertionError("Native layout ledger absent");}
    private static Map<String,Object> story(DraftArtifact artifact)throws IOException {Map<String,Object> story=DraftBusinessRules.asMap(layout(artifact).get("nativeStories"));assertEquals("applied",story.get("status"));assertEquals(artifact.getSourceSha256(),story.get("sourceSha256"));assertEquals(artifact.getDocxSha256(),story.get("docxSha256"));Map<String,String> current=hashes(parts(artifact.getDocxBytes()));assertFalse(DraftBusinessRules.asMap(story.get("entryHashes")).isEmpty());for(Map.Entry<String,Object> e:DraftBusinessRules.asMap(story.get("entryHashes")).entrySet())assertEquals(e.getValue(),current.get(e.getKey()),e.getKey());return story;}
    private void recordRealFinalization()throws Exception {
        doAnswer(invocation->{
            byte[] input=((byte[])invocation.getArgument(0)).clone();String source=invocation.getArgument(1);
            bytes("SCC-compiled.docx",input);json("SCC-finalizer-request.json",map("originalSourceSha256",source,"compiledDocxSha256",DraftPdfConverter.sha256(input),"delegation","spy_callRealMethod_only"));
            DraftTocFinalizer.Result actual=(DraftTocFinalizer.Result)invocation.callRealMethod();observedToc=actual;
            if(actual!=null){
                bytes("SCC-finalizer-output.docx",actual.docx);json("SCC-toc-finalization-proof.json",actual.proof);
                if(actual.first!=null){bytes("SCC-finalizer-first.pdf",actual.first.getPdfBytes());json("SCC-finalizer-first-manifest.json",actual.first.getManifest());}
                if(actual.pdf!=null){bytes("SCC-finalizer-final.pdf",actual.pdf.getPdfBytes());json("SCC-finalizer-final-manifest.json",actual.pdf.getManifest());}
                assertArrayEquals(input,actual.compiled);assertEquals(HASHES[2],source);
            }
            return actual;
        }).when(drafting).finalizeCompiledToc(any(byte[].class),anyString(),anyList(),anyList());
    }
    private static Set<String> compilerPoints(byte[] docx) {
        DocxTemplateEditor editor=new DocxTemplateEditor();Set<String> points=new LinkedHashSet<>();int cs=0,cl=0;
        List<DocxTemplateEditor.TocPageReference> references=editor.tocPageReferences(docx);assertEquals(92,references.size());
        for(DocxTemplateEditor.TocPageReference reference:references)assertTrue(points.add(reference.getTargetBookmark()));
        for(DocxTemplateEditor.Feature feature:editor.inspect(docx).getFeatures())if("BOOKMARK".equals(feature.getKind())&&feature.getName()!=null&&feature.getName().matches("(?:CS|CL)[A-Za-z0-9]{1,38}")){
            assertTrue(feature.isComplete());assertTrue(points.add(feature.getName()));if(feature.getName().startsWith("CS"))cs++;else cl++;
        }
        assertEquals(1097,cs);assertEquals(1329,cl);assertEquals(2518,points.size());return points;
    }
    private void assertSccFinalization(DraftArtifact saved)throws Exception {
        assertNotNull(observedToc,"Normal generation must call the real native finalizer");Map<String,Object> proof=observedToc.proof;
        String compiledHash=DraftPdfConverter.sha256(observedToc.compiled),finalHash=DraftPdfConverter.sha256(observedToc.docx);
        assertEquals(compiledHash,proof.get("compiledDocxSha256"));assertEquals(compiledHash,layout(saved).get("compiledDocxSha256"));assertEquals(compiledHash,story(saved).get("compiledDocxSha256"));
        assertEquals(finalHash,saved.getDocxSha256());assertArrayEquals(observedToc.docx,saved.getDocxBytes());assertEquals(finalHash,proof.get("finalDocxSha256"));
        assertEquals("PASS",proof.get("status"),JsonUtils.write(proof));assertEquals("native-heading-flow-then-toc-bounded-v2",proof.get("policy"));assertEquals("PASS",proof.get("headingFlowStatus"));
        assertEquals(HASHES[2],proof.get("headingFlowSourceSha256"));assertEquals(244,proof.get("headingFlowDenominator"));assertEquals(92,proof.get("targetDenominator"));assertEquals(2518,proof.get("preservedPointCount"));
        DocxTemplateEditor bodyEditor=new DocxTemplateEditor();DocxTemplateEditor.TemplateIndex compiledIndex=bodyEditor.inspect(observedToc.compiled);
        for(int ordinal:new int[]{1340,1395}){String sourceId="word/document.xml#/w:document[1]/w:body[1]/w:p["+ordinal+"]";String cs="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|"+sourceId).substring(0,28);List<DocxTemplateEditor.Feature> matches=new ArrayList<>();for(DocxTemplateEditor.Feature f:compiledIndex.getFeatures())if(cs.equals(f.getName()))matches.add(f);assertEquals(1,matches.size());String start=matches.get(0).getStartId();assertTrue(compiledIndex.paragraph(start.substring(0,start.lastIndexOf("/w:bookmarkStart"))).hasNativeWidowControl());}
        int bodyWidows=0;for(Object raw:DraftBusinessRules.list(layout(saved).get("actions"))){Map<String,Object> action=DraftBusinessRules.asMap(raw);if("keep_verified_body_paragraph_widows".equals(action.get("action"))){assertEquals("APPLIED",action.get("status"));bodyWidows++;}}assertEquals(2,bodyWidows);

        Map<String,Object> measuredPoints=DraftBusinessRules.asMap(proof.get("finalPoints"));int tableRelationCount=0,wholeTableCellChecks=0;
        try(PDDocument pdf=PDDocument.load(observedToc.pdf.getPdfBytes())){for(int[] relation:new int[][]{{714,1},{1439,2},{1539,3},{1637,4}}){int leadOrdinal=relation[0],tableNumber=relation[1];String lead="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|word/document.xml#/w:document[1]/w:body[1]/w:p["+leadOrdinal+"]").substring(0,28);List<Object> leadPoint=DraftBusinessRules.list(measuredPoints.get(lead));assertFalse(leadPoint.isEmpty());String prefix="word/document.xml#/w:document[1]/w:body[1]/w:tbl["+tableNumber+"]/";int count=0;PDFTextStripper stripper=new PDFTextStripper();stripper.setStartPage(Integer.parseInt(String.valueOf(leadPoint.get(0))));stripper.setEndPage(Integer.parseInt(String.valueOf(leadPoint.get(0))));String fullPage=stripper.getText(pdf).replaceAll("(?U)\\s+","");
            for(DocxTemplateEditor.Paragraph sourceParagraph:compiledIndex.getParagraphs()){if(!sourceParagraph.getId().startsWith(prefix)||sourceParagraph.getText().trim().isEmpty())continue;String cellName=null;for(DocxTemplateEditor.Feature feature:compiledIndex.getFeatures())if("BOOKMARK".equals(feature.getKind())&&feature.getName()!=null&&feature.getName().startsWith("CS")&&feature.getStartId().startsWith(sourceParagraph.getId()+"/w:bookmarkStart[")){assertNull(cellName,"One source CS per table paragraph");cellName=feature.getName();}assertNotNull(cellName,sourceParagraph.getId());List<Object> point=DraftBusinessRules.list(measuredPoints.get(cellName));assertFalse(point.isEmpty(),cellName);assertEquals(leadPoint.get(0),point.get(0),"Exact source lead and complete native table cell must share one physical page: "+sourceParagraph.getId());String cell=sourceParagraph.getText().replaceAll("(?U)\\s+","");assertTrue(fullPage.contains(cell),"Complete source table-cell text on the lead page: "+sourceParagraph.getId());count++;wholeTableCellChecks++;}
            assertEquals(8,count);tableRelationCount++;}}assertEquals(4,tableRelationCount);assertEquals(32,wholeTableCellChecks);json("SCC-source-table-introduction-checks.json",map("sourceSha256",HASHES[2],"exactNativeTables",tableRelationCount,"completeNonemptyCellChecks",wholeTableCellChecks,"sourceLeadAndAllNonemptyCellsSamePage",true,"nativeOMathWholeChainAuthority","UNKNOWN_UNCHANGED","visualAcceptance",false));
        for(int[] pair:new int[][]{{1349,1351},{1369,1371},{1466,1468},{1486,1488},{1566,1568},{1586,1588},{1664,1666},{1683,1685}}){List<Object> previous=Collections.emptyList();for(int ordinal:pair){String name="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|word/document.xml#/w:document[1]/w:body[1]/w:p["+ordinal+"]").substring(0,28);List<Object> point=DraftBusinessRules.list(measuredPoints.get(name));assertFalse(point.isEmpty());if(!previous.isEmpty())assertEquals(previous.get(0),point.get(0),"Final list item and earliest qualifier must share one physical page: "+pair[0]);previous=point;}}
        for(int[] pair:new int[][]{{1343,1345},{1363,1365},{1460,1462},{1480,1482},{1560,1562},{1580,1582},{1658,1660},{1678,1680}}){List<Object> previous=Collections.emptyList();for(int ordinal:pair){String name="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|word/document.xml#/w:document[1]/w:body[1]/w:p["+ordinal+"]").substring(0,28);List<Object> point=DraftBusinessRules.list(measuredPoints.get(name));assertFalse(point.isEmpty());if(!previous.isEmpty())assertEquals(previous.get(0),point.get(0),"Branch introduction and first list item must share one physical page: "+pair[0]);previous=point;}}
        for(int[] pair:new int[][]{{1342,1343},{1458,1460},{1558,1560},{1656,1658}}){List<Object> previous=Collections.emptyList();for(int ordinal:pair){String name="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|word/document.xml#/w:document[1]/w:body[1]/w:p["+ordinal+"]").substring(0,28);List<Object> point=DraftBusinessRules.list(measuredPoints.get(name));assertFalse(point.isEmpty());if(!previous.isEmpty())assertEquals(previous.get(0),point.get(0),"Index definition and first branch must share one physical page: "+pair[0]);previous=point;}}
        Set<String> expectedWholeBodies=new LinkedHashSet<>(),appliedWholeBodies=new LinkedHashSet<>();for(int[] pair:DraftNativeHeadingBoundariesTest.allIntroductionPairs())assertTrue(expectedWholeBodies.add("word/document.xml#/w:document[1]/w:body[1]/w:p["+pair[1]+"]"));
        for(Object raw:DraftBusinessRules.list(layout(saved).get("actions"))){Map<String,Object> action=DraftBusinessRules.asMap(raw);if("keep_verified_boundary_body_lines".equals(action.get("action"))){assertEquals("APPLIED",action.get("status"));String sourceId=String.valueOf(action.get("sourceId"));assertTrue(expectedWholeBodies.contains(sourceId));assertTrue(appliedWholeBodies.add(sourceId));String cs="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|"+sourceId).substring(0,28);DocxTemplateEditor.Feature f=compiledIndex.getFeatures().stream().filter(value->cs.equals(value.getName())).findFirst().orElseThrow(AssertionError::new);String start=f.getStartId();assertTrue(compiledIndex.paragraph(start.substring(0,start.lastIndexOf("/w:bookmarkStart"))).hasNativeBodyLines(),"Exact first body keeps its own native lines: "+sourceId);}}
        assertEquals(139,expectedWholeBodies.size());assertEquals(expectedWholeBodies,appliedWholeBodies);
        int introductionRelations=0,completeIntroductionParagraphs=0;Set<Integer> uniqueCompleteIntroductionParagraphs=new LinkedHashSet<>();
        try(PDDocument pdf=PDDocument.load(observedToc.pdf.getPdfBytes())){for(int[] pair:DraftNativeHeadingBoundariesTest.allIntroductionPairs()){List<Object> previous=Collections.emptyList();for(int ordinal:pair){String name="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|word/document.xml#/w:document[1]/w:body[1]/w:p["+ordinal+"]").substring(0,28);List<Object> point=DraftBusinessRules.list(measuredPoints.get(name));assertFalse(point.isEmpty(),"Complete source introduction endpoint: "+ordinal);if(!previous.isEmpty())assertEquals(previous.get(0),point.get(0),"Source introduction and first controlled item must share one physical page: "+pair[0]);previous=point;int page=Integer.parseInt(String.valueOf(point.get(0)));PDFTextStripper stripper=new PDFTextStripper();stripper.setStartPage(page);stripper.setEndPage(page);String fullPage=stripper.getText(pdf).replaceAll("(?U)\\s+","");DocxTemplateEditor.Feature feature=compiledIndex.getFeatures().stream().filter(f->name.equals(f.getName())).findFirst().orElseThrow(AssertionError::new);String start=feature.getStartId(),paragraph=compiledIndex.paragraph(start.substring(0,start.lastIndexOf("/w:bookmarkStart"))).getText().replaceAll("(?U)\\s+","");assertTrue(fullPage.contains(paragraph),"Complete source introduction / first item must fit one physical page: "+ordinal);uniqueCompleteIntroductionParagraphs.add(ordinal);completeIntroductionParagraphs++;}introductionRelations++;}}
        assertEquals(139,introductionRelations);assertEquals(278,completeIntroductionParagraphs);assertEquals(261,uniqueCompleteIntroductionParagraphs.size());json("SCC-source-introduction-checks.json",map("sourceSha256",HASHES[2],"localSourceRelations",introductionRelations,"completeEndpointChecks",completeIntroductionParagraphs,"uniqueCompleteEndpointParagraphs",uniqueCompleteIntroductionParagraphs.size(),"wholeUpstreamTableAuthority","ALL_FOUR_EXPLICIT_SOURCE_TABLES_MEASURED","nativeOMathWholeChainAuthority","UNKNOWN_UNCHANGED","semanticAuthority","exact_native_source_control_relationships_no_fixed_page_targets","visualAcceptance",false));
        try(PDDocument pdf=PDDocument.load(observedToc.pdf.getPdfBytes())){for(int ordinal:new int[]{1340,1395,1349,1351,1369,1371,1466,1468,1486,1488,1566,1568,1586,1588,1664,1666,1683,1685,1343,1345,1363,1365,1460,1462,1480,1482,1560,1562,1580,1582,1658,1660,1678,1680,1342,1458,1558,1656}){String name="CS"+DraftAdoption.hash("SCC|"+HASHES[2]+"|word/document.xml#/w:document[1]/w:body[1]/w:p["+ordinal+"]").substring(0,28);List<Object> point=DraftBusinessRules.list(measuredPoints.get(name));assertFalse(point.isEmpty());int page=Integer.parseInt(String.valueOf(point.get(0)));PDFTextStripper stripper=new PDFTextStripper();stripper.setStartPage(page);stripper.setEndPage(page);String fullPage=stripper.getText(pdf).replaceAll("(?U)\\s+","");DocxTemplateEditor.Feature feature=compiledIndex.getFeatures().stream().filter(f->name.equals(f.getName())).findFirst().orElseThrow(AssertionError::new);String start=feature.getStartId(),paragraph=compiledIndex.paragraph(start.substring(0,start.lastIndexOf("/w:bookmarkStart"))).getText().replaceAll("(?U)\\s+","");assertTrue(fullPage.contains(paragraph),"Complete registered paragraph must fit on one physical page: "+ordinal);}}
        Set<String> points=compilerPoints(observedToc.compiled);assertEquals(points,compilerPoints(saved.getDocxBytes()));assertEquals(points,DraftBusinessRules.asMap(proof.get("finalPoints")).keySet());
        assertEquals(proof.get("measurementPoints"),proof.get("finalPoints"));
        List<Object> pairs=DraftBusinessRules.list(proof.get("finalHeadingPairs"));assertEquals(244,pairs.size());for(Object pair:pairs)assertEquals(true,DraftBusinessRules.asMap(pair).get("samePhysicalPage"));
        Map<String,Object> pages=DraftBusinessRules.asMap(proof.get("finalPages")),displayed=DraftBusinessRules.asMap(proof.get("finalActualTocPages"));
        for(DocxTemplateEditor.TocPageReference reference:new DocxTemplateEditor().tocPageReferences(saved.getDocxBytes())){assertEquals(reference.getCachedPage(),pages.get(reference.getTargetBookmark()));assertEquals(reference.getCachedPage(),displayed.get(reference.getTargetBookmark()));}
        List<Map<String,Object>> persisted=new ArrayList<>();for(Object row:JsonUtils.readList(saved.getLedgerJson(),Object.class)){Map<String,Object> item=DraftBusinessRules.asMap(row);if("toc_finalization".equals(item.get("kind")))persisted.add(item);}
        assertEquals(1,persisted.size());assertEquals(JsonUtils.parse(JsonUtils.write(proof)),JsonUtils.parse(JsonUtils.write(persisted.get(0))));
        assertNotNull(observedToc.pdf);assertEquals(finalHash,observedToc.pdf.getDocxSha256());
        DraftPdfArtifact eager=pdfArtifacts.findById(DraftAdoption.hash(finalHash+"|"+observedToc.pdf.getRenderProfileHash())).orElseThrow(AssertionError::new);
        assertArrayEquals(observedToc.pdf.getPdfBytes(),eager.getPdfBytes());assertEquals(observedToc.pdf.getPdfSha256(),eager.getPdfSha256());
        if(!compiledHash.equals(finalHash))assertFalse(pdfArtifacts.findFirstByDocxSha256OrderByCreatedAtDesc(compiledHash).isPresent(),"Only the finalized pair is published to the cache");
        assertEquals(JsonUtils.parse(JsonUtils.write(proof)),JsonUtils.parse(eager.getManifestJson()).path("tocFinalization"));
        DocxTemplateEditor.TemplateIndex finalIndex=new DocxTemplateEditor().inspect(saved.getDocxBytes());
        for(Object raw:JsonUtils.readList(saved.getBindingsJson(),Object.class)){Map<String,Object> row=DraftBusinessRules.asMap(raw);if("exact".equals(row.get("locationStatus"))){DocxTemplateEditor.Paragraph paragraph=finalIndex.paragraph(String.valueOf(row.get("resultParagraphId")));assertEquals(row.get("text"),paragraph.getText());}}
        List<Object> blocks=JsonUtils.readList(saved.getBlocksJson(),Object.class);assertEquals(finalIndex.getMainParagraphs().size(),blocks.size());Set<String> blockIds=new HashSet<>();
        for(Object raw:blocks){Map<String,Object> block=DraftBusinessRules.asMap(raw);String id=String.valueOf(block.get("id"));assertTrue(blockIds.add(id));assertEquals(block.get("text"),finalIndex.paragraph(id).getText());}
        json("SCC-normal-flow-identity-checks.json",map("compiledDocxSha256",compiledHash,"finalDocxSha256",finalHash,"revisionId",saved.getId(),"eagerPdfCacheId",eager.getId(),"sourceSha256",saved.getSourceSha256(),"headingDenominator",244,"legacyRegisteredFlowDenominator",239,"runtime33AdditionalFlowDenominator",5,"legacyHeadingDenominator",70,"measuredContinuationDenominator",34,"sourceIntroductionDenominator",139,"priorSourceIntroductionDenominator",135,"tocDenominator",92,"csCount",1097,"clCount",1329,"pointDenominator",2518,"fixtureAuthority","actual_2a_templates_with_explicit_synthetic_project_adoption","visualAcceptance",false,"bodyIntroBoundaryAccepted",false,"actual2bAccepted",false));
    }
    private void saveRevision(String label,DraftDocument document,DraftArtifact artifact)throws Exception {
        assertEquals(document.getRevisionId(),artifact.getId());assertEquals(artifact.getDocxSha256(),DraftPdfConverter.sha256(artifact.getDocxBytes()));
        text(label+"-inputSnapshot.json",document.getInputSnapshotJson());text(label+"-unresolved.json",document.getUnresolvedJson());text(label+"-ledger.json",artifact.getLedgerJson());text(label+"-blocks.json",artifact.getBlocksJson());text(label+"-bindings.json",artifact.getBindingsJson());text(label+"-fieldImpacts.json",artifact.getFieldImpactsJson());
        json(label+"-revision.json",map("revisionId",artifact.getId(),"parentRevisionId",artifact.getParentRevisionId(),"projectId",artifact.getProjectId(),"fileKey",artifact.getFileKey(),"snapshotId",artifact.getSnapshotId(),"sourceSha256",artifact.getSourceSha256(),"docxSha256",artifact.getDocxSha256(),"nativeStories",story(artifact),"entryHashes",hashes(parts(artifact.getDocxBytes()))));
    }
    private void sourcePartChecks(String key,byte[] original,byte[] output)throws Exception {
        Map<String,byte[]> before=parts(original),after=parts(output);Set<String> expected=new TreeSet<>(before.keySet()),mutable=new TreeSet<>(Arrays.asList("word/document.xml","word/_rels/document.xml.rels","[Content_Types].xml"));
        if("NTT".equals(key))expected.addAll(Arrays.asList("word/header1.xml","word/footer1.xml"));
        else if("SCT".equals(key)){expected.addAll(Arrays.asList("word/header1.xml","word/header2.xml"));mutable.add("word/footer1.xml");}
        else{assertEquals("SCC",key);mutable.addAll(Arrays.asList("word/header1.xml","word/header2.xml","word/header3.xml","word/header4.xml","word/footer1.xml","word/footer2.xml"));}
        assertEquals(expected,after.keySet());for(String part:before.keySet())if(!mutable.contains(part))assertArrayEquals(before.get(part),after.get(part),key+":"+part);
        com.consense.document.DocxTemplateEditor.TemplateIndex index=new com.consense.document.DocxTemplateEditor().inspect(output);
        assertTrue(index.getParagraphs().stream().anyMatch(p->p.getPart().matches("word/header[0-9]+\\.xml")&&p.getCatalogText().contains(TITLE)));assertTrue(index.getParagraphs().stream().anyMatch(p->p.getPart().matches("word/footer[0-9]+\\.xml")&&p.getCatalogText().contains(NUMBER)));
        json(key+"-source-output-part-checks.json",map("sourceEntryHashes",hashes(before),"outputEntryHashes",hashes(after),"exactOutputParts",expected,"mutableExistingSourceParts",mutable,"headerTitle",TITLE,"footerNumber",NUMBER));
    }
    private final class Fixture {String id;JsonNode generated;Map<String,DraftArtifact> revisions=new LinkedHashMap<>();Map<String,byte[]> exports=new LinkedHashMap<>();}
    private Fixture qualifiedPackage()throws Exception {
        Fixture f=new Fixture();f.id="native-normal-"+UUID.randomUUID();call("POST","/api/projects",map("id",f.id,"nameZhHans","Temporary metadata","nameZhHant","Temporary metadata","nameEn","Temporary metadata","contractNo","UNADOPTED-PROJECT-METADATA"),0);
        String sourceDir=System.getProperty("consense.acceptance.sourceDir");assertNotNull(sourceDir,"Explicit actual original sourceDir required");
        for(int i=0;i<KEYS.length;i++){byte[] original=Files.readAllBytes(Paths.get(sourceDir,NAMES[i]));assertEquals(HASHES[i],DraftPdfConverter.sha256(original));bytes("source-"+KEYS[i]+".docx",original);MockHttpServletResponse response=perform("POST","/api/drafting/"+f.id+"/templates/"+KEYS[i]+"/replace",null,new MockMultipartFile("file",NAMES[i],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",original));assertEquals(1,JsonUtils.parse(new String(response.getContentAsByteArray(),StandardCharsets.UTF_8)).path("data").path("parsed").asInt());}
        call("PUT","/api/drafting/"+f.id+"/variables/contractTitle",map("value",JsonUtils.write(map("number",NUMBER,"title",TITLE)),"note","Explicit isolated test adoption before package generation"),0);
        JsonNode plan=call("GET","/api/drafting/"+f.id+"/plan",null,0);assertEquals(NUMBER,plan.path("effectiveValues").path("contractTitle").path("number").asText());assertEquals(TITLE,plan.path("effectiveValues").path("contractTitle").path("title").asText());json("effective-plan.json",plan);
        f.generated=call("POST","/api/drafting/"+f.id+"/generate",null,0);assertEquals(3,f.generated.size());json("generated-package.json",f.generated);Set<String> snapshots=new HashSet<>();Set<String> inputSnapshots=new HashSet<>();
        for(int i=0;i<KEYS.length;i++){String key=KEYS[i];DraftDocument document=documents.findByProjectIdAndFileKey(f.id,key).orElseThrow(AssertionError::new);DraftArtifact artifact=artifacts.findById(document.getRevisionId()).orElseThrow(AssertionError::new);snapshots.add(document.getSnapshotId());inputSnapshots.add(document.getInputSnapshotJson());JsonNode saved=JsonUtils.parse(document.getInputSnapshotJson());assertEquals(plan.path("effectiveValues").path("contractTitle"),saved.path("effectiveValues").path("contractTitle"));assertEquals(HASHES[i],artifact.getSourceSha256());saveRevision(key,document,artifact);if("SCC".equals(key))assertSccFinalization(artifact);else assertEquals(artifact.getDocxSha256(),story(artifact).get("compiledDocxSha256"));byte[] exported=word(f.id,key,key+"-generated.docx");assertArrayEquals(artifact.getDocxBytes(),exported);sourcePartChecks(key,Files.readAllBytes(Paths.get(sourceDir,NAMES[i])),exported);SourceDocument source=sources.findFirstByProjectIdAndFileKeyAndCategory(f.id,key,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow(AssertionError::new);text(key+"-source-structured.json",source.getStructuredContentJson());f.revisions.put(key,artifact);f.exports.put(key,exported);}
        assertEquals(1,snapshots.size());assertEquals(1,inputSnapshots.size());assertEquals("UNADOPTED-PROJECT-METADATA",projects.findById(f.id).orElseThrow(AssertionError::new).getContractNo());assertEquals("Temporary metadata",projects.findById(f.id).orElseThrow(AssertionError::new).getNameEn());verifyNoInteractions(ai);return f;
    }

    private static long renderCount(Path work)throws IOException {try(java.util.stream.Stream<Path> dirs=Files.list(work)){return dirs.filter(p->p.getFileName().toString().startsWith("render-")).count();}}

    @Test void qualifiedStandardPackageThreePreviewsRetainFullNativeGenerationEvidence()throws Exception {
        assertNotNull(System.getProperty("consense.acceptance.rendererExecutable"),"Explicit finite renderer window required");
        Path work=Paths.get(System.getProperty("consense.acceptance.pdfWorkRoot"));assertFalse(Files.exists(work),"Unique short renderer work root required");
        recordRealFinalization();Fixture f=qualifiedPackage();assertEquals(1,pdfArtifacts.count(),"SCC final PDF is cached during generation");Map<String,Object> files=new LinkedHashMap<>();int rendered=1;
        int sccConversions=((Number)observedToc.proof.get("finalConversionPass")).intValue();assertTrue(sccConversions==2||sccConversions==3);assertEquals(sccConversions,observedToc.proof.get("nativeConversionsAttempted"));
        for(String key:KEYS){
            DraftArtifact saved=f.revisions.get(key);Map<String,Object> nativeLayout=layout(saved);long beforePreview=renderCount(work);
            if("SCT".equals(key)){
                assertEquals("existing_source_bound_policy",nativeLayout.get("bodyLayoutStatus"),"Full installed body-layout policy must participate");
                assertFalse(DraftBusinessRules.list(nativeLayout.get("actions")).isEmpty(),"SCT must use the installed nonempty body-layout policy");
            }
            if(!"SCC".equals(key))bytes(key+"-compiled.docx",saved.getDocxBytes());bytes(key+"-saved.docx",saved.getDocxBytes());json(key+"-complete-native-layout.json",nativeLayout);
            MockHttpServletResponse previewResponse=perform("GET","/api/drafting/"+f.id+"/documents/"+key+"/preview.pdf",null,null);assertTrue(previewResponse.getContentType().contains("pdf"));byte[] pdf=previewResponse.getContentAsByteArray();bytes(key+"-preview.pdf",pdf);
            DraftPdfArtifact cache=pdfArtifacts.findFirstByDocxSha256OrderByCreatedAtDesc(saved.getDocxSha256()).orElseThrow(AssertionError::new);
            assertEquals(saved.getDocxSha256(),cache.getDocxSha256());assertEquals(DraftPdfConverter.sha256(pdf),cache.getPdfSha256());assertArrayEquals(pdf,cache.getPdfBytes());text(key+"-render-manifest.json",cache.getManifestJson());
            assertEquals(DraftAdoption.hash(saved.getDocxSha256()+"|"+cache.getRenderProfileHash()),cache.getId());JsonNode manifest=JsonUtils.parse(cache.getManifestJson());
            assertEquals(saved.getDocxSha256(),manifest.path("docxSha256").asText());assertEquals(cache.getPdfSha256(),manifest.path("pdfSha256").asText());assertEquals(cache.getRenderProfileHash(),manifest.path("renderProfileHash").asText());
            if("SCC".equals(key))assertArrayEquals(observedToc.pdf.getPdfBytes(),pdf);
            byte[] exported=perform("GET","/api/drafting/"+f.id+"/documents/"+key+"/export.pdf",null,null).getContentAsByteArray();bytes(key+"-export.pdf",exported);assertArrayEquals(pdf,exported);
            byte[] current=word(f.id,key,key+"-current.docx");assertArrayEquals(f.exports.get(key),current);assertArrayEquals(saved.getDocxBytes(),current);if(!"SCC".equals(key))rendered++;assertEquals(rendered,pdfArtifacts.count());assertEquals(beforePreview+("SCC".equals(key)?0:1),renderCount(work),"Preview/export share the same exact-DOCX cache");
            List<Object> pages=new ArrayList<>();try(PDDocument parsed=PDDocument.load(pdf)){
                assertTrue(parsed.getNumberOfPages()>0);
                if("SCC".equals(key)){
                    String completeText=new PDFTextStripper().getText(parsed).replaceAll("\\s+"," ");
                    assertTrue(completeText.contains("contrary, the Bills"),"SCC11.301 must retain the complete word at the justified line boundary");
                    assertTrue(completeText.contains("In relation to any application for an Excavation Permit under Clause SCC22.303(1)(b) of the Special Conditions of Contract"),"SCC22.303(5) must retain the complete source wording");
                    Set<String> slotSources=new HashSet<>();for(Object rawAction:DraftBusinessRules.list(nativeLayout.get("actions"))){Map<String,Object> action=DraftBusinessRules.asMap(rawAction);if("normalize_verified_latin_font_slot".equals(action.get("action"))){assertEquals("APPLIED",action.get("status"));assertTrue(slotSources.add(String.valueOf(action.get("sourceId"))));}}
                    assertEquals(Set.of("word/document.xml#/w:document[1]/w:body[1]/w:p[977]","word/document.xml#/w:document[1]/w:body[1]/w:p[1807]"),slotSources);
                }
                for(int page=0;page<parsed.getNumberOfPages();page++){
                    PDFTextStripper stripper=new PDFTextStripper();stripper.setStartPage(page+1);stripper.setEndPage(page+1);String raw=stripper.getText(parsed);String name=String.format(Locale.ROOT,key+"-page-%03d.txt",page+1);text(name,raw);
                    pages.add(map("page",page+1,"textFile",name,"textSha256",DraftPdfConverter.sha256(raw.getBytes(StandardCharsets.UTF_8)),"utf16Length",raw.length(),"widthPoints",parsed.getPage(page).getMediaBox().getWidth(),"heightPoints",parsed.getPage(page).getMediaBox().getHeight()));
                }
            }
            files.put(key,map("sourceSha256",saved.getSourceSha256(),"savedRevisionId",saved.getId(),"compiledDocxSha256",nativeLayout.get("compiledDocxSha256"),"finalDocxSha256",saved.getDocxSha256(),"currentDocxSha256",DraftPdfConverter.sha256(current),"compiledStoryDocxSha256",story(saved).get("compiledDocxSha256"),"pdfSha256",cache.getPdfSha256(),"previewEqualsExport",true,"currentEqualsSaved",true,"currentEqualsCompiled",Arrays.equals("SCC".equals(key)?observedToc.compiled:saved.getDocxBytes(),current),"allPages",pages,"bodyLayoutStatus",nativeLayout.get("bodyLayoutStatus"),"bodyLayoutActionCount",DraftBusinessRules.list(nativeLayout.get("actions")).size()));
        }
        long conversions,probes;try(java.util.stream.Stream<Path> dirs=Files.list(work)){conversions=dirs.filter(p->p.getFileName().toString().startsWith("render-")).count();}
        try(java.util.stream.Stream<Path> dirs=Files.list(work)){probes=dirs.filter(p->p.getFileName().toString().startsWith("profile-probe-")).count();}
        assertEquals(sccConversions+2,conversions);assertTrue(conversions<=5);assertTrue(probes<=12,"Bounded profile/version checks for generation plus preview/export of three saved files");assertEquals(3,pdfArtifacts.count());
        verify(drafting,times(1)).finalizeCompiledToc(any(byte[].class),eq(HASHES[2]),anyList(),anyList());
        for(String key:KEYS){DraftDocument currentDocument=documents.findByProjectIdAndFileKey(f.id,key).orElseThrow(AssertionError::new);assertEquals(f.revisions.get(key).getId(),currentDocument.getRevisionId());assertArrayEquals(f.exports.get(key),artifacts.findById(currentDocument.getRevisionId()).orElseThrow(AssertionError::new).getDocxBytes());}
        json("full-generation-render-result.json",map("projectId",f.id,"sourceEdition","2a_Standard documents","generatedFiles",Arrays.asList(KEYS),"effectiveIdentity",map("number",NUMBER,"title",TITLE),"files",files,"rendererConversions",conversions,"profileProbeDirectories",probes,"maxRendererVersionProbes",17,"sccFinalizationConversions",sccConversions,"savedInputReplayInjected",false,"bodyIntroBoundaryAccepted",false,"actual2bAccepted",false,"visualAcceptance",false,"rasterExecuted",false,"targetPageCount",null,"sharedHttp",0,"sharedSql",0,"modelRequests",0));verifyNoInteractions(ai);
    }

}
