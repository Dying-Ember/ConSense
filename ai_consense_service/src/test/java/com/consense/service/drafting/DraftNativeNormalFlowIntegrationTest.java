package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Same explicitly adopted identity fixture for both independent, finite local MVC/JPA windows. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftNativeNormalFlowIntegrationTest {
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
        for(int i=0;i<KEYS.length;i++){String key=KEYS[i];DraftDocument document=documents.findByProjectIdAndFileKey(f.id,key).orElseThrow(AssertionError::new);DraftArtifact artifact=artifacts.findById(document.getRevisionId()).orElseThrow(AssertionError::new);snapshots.add(document.getSnapshotId());inputSnapshots.add(document.getInputSnapshotJson());JsonNode saved=JsonUtils.parse(document.getInputSnapshotJson());assertEquals(plan.path("effectiveValues").path("contractTitle"),saved.path("effectiveValues").path("contractTitle"));assertEquals(HASHES[i],artifact.getSourceSha256());assertEquals(artifact.getDocxSha256(),story(artifact).get("compiledDocxSha256"));saveRevision(key,document,artifact);byte[] exported=word(f.id,key,key+"-generated.docx");assertArrayEquals(artifact.getDocxBytes(),exported);sourcePartChecks(key,Files.readAllBytes(Paths.get(sourceDir,NAMES[i])),exported);SourceDocument source=sources.findFirstByProjectIdAndFileKeyAndCategory(f.id,key,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow(AssertionError::new);text(key+"-source-structured.json",source.getStructuredContentJson());f.revisions.put(key,artifact);f.exports.put(key,exported);}
        assertEquals(1,snapshots.size());assertEquals(1,inputSnapshots.size());assertEquals("UNADOPTED-PROJECT-METADATA",projects.findById(f.id).orElseThrow(AssertionError::new).getContractNo());assertEquals("Temporary metadata",projects.findById(f.id).orElseThrow(AssertionError::new).getNameEn());verifyNoInteractions(ai);return f;
    }

    @Test void qualifiedPackageSctPreviewExportRetainFullNativeGenerationEvidence()throws Exception {
        assertNotNull(System.getProperty("consense.acceptance.rendererExecutable"),"Explicit finite renderer window required");
        Path work=Paths.get(System.getProperty("consense.acceptance.pdfWorkRoot"));assertFalse(Files.exists(work),"Unique short renderer work root required");
        Fixture f=qualifiedPackage();DraftArtifact compiled=f.revisions.get("SCT");assertEquals(0,pdfArtifacts.count());
        Map<String,Object> nativeLayout=layout(compiled);assertEquals("existing_source_bound_policy",nativeLayout.get("bodyLayoutStatus"),"Full installed body-layout policy must participate");
        assertFalse(DraftBusinessRules.list(nativeLayout.get("actions")).isEmpty(),"SCT must use the installed nonempty body-layout policy");
        bytes("SCT-compiled.docx",compiled.getDocxBytes());json("SCT-complete-native-layout.json",nativeLayout);
        MockHttpServletResponse previewResponse=perform("GET","/api/drafting/"+f.id+"/documents/SCT/preview.pdf",null,null);assertTrue(previewResponse.getContentType().contains("pdf"));byte[] pdf=previewResponse.getContentAsByteArray();bytes("SCT-preview.pdf",pdf);
        DraftPdfArtifact cache=pdfArtifacts.findFirstByDocxSha256OrderByCreatedAtDesc(compiled.getDocxSha256()).orElseThrow(AssertionError::new);
        assertEquals(compiled.getDocxSha256(),cache.getDocxSha256());assertEquals(DraftPdfConverter.sha256(pdf),cache.getPdfSha256());assertArrayEquals(pdf,cache.getPdfBytes());text("SCT-render-manifest.json",cache.getManifestJson());
        byte[] exported=perform("GET","/api/drafting/"+f.id+"/documents/SCT/export.pdf",null,null).getContentAsByteArray();bytes("SCT-export.pdf",exported);assertArrayEquals(pdf,exported);
        byte[] current=word(f.id,"SCT","SCT-current.docx");assertArrayEquals(f.exports.get("SCT"),current);assertArrayEquals(compiled.getDocxBytes(),current);assertEquals(1,pdfArtifacts.count());
        List<Object> pages=new ArrayList<>();try(PDDocument parsed=PDDocument.load(pdf)){
            assertTrue(parsed.getNumberOfPages()>0);for(int page=0;page<parsed.getNumberOfPages();page++){
                PDFTextStripper stripper=new PDFTextStripper();stripper.setStartPage(page+1);stripper.setEndPage(page+1);String raw=stripper.getText(parsed);String name=String.format(Locale.ROOT,"SCT-page-%03d.txt",page+1);text(name,raw);
                pages.add(map("page",page+1,"textFile",name,"textSha256",DraftPdfConverter.sha256(raw.getBytes(StandardCharsets.UTF_8)),"utf16Length",raw.length(),"widthPoints",parsed.getPage(page).getMediaBox().getWidth(),"heightPoints",parsed.getPage(page).getMediaBox().getHeight()));
            }
        }
        long conversions,probes;try(java.util.stream.Stream<Path> dirs=Files.list(work)){conversions=dirs.filter(p->p.getFileName().toString().startsWith("render-")).count();}
        try(java.util.stream.Stream<Path> dirs=Files.list(work)){probes=dirs.filter(p->p.getFileName().toString().startsWith("profile-probe-")).count();}
        assertEquals(1,conversions);assertTrue(probes<=3,"Only the accepted renderer version/profile probes;one conversion");assertEquals(1,pdfArtifacts.count());
        for(String key:KEYS){DraftDocument currentDocument=documents.findByProjectIdAndFileKey(f.id,key).orElseThrow(AssertionError::new);assertEquals(f.revisions.get(key).getId(),currentDocument.getRevisionId());assertArrayEquals(f.exports.get(key),artifacts.findById(currentDocument.getRevisionId()).orElseThrow(AssertionError::new).getDocxBytes());}
        json("full-generation-render-result.json",map("projectId",f.id,"generatedFiles",Arrays.asList(KEYS),"effectiveIdentity",map("number",NUMBER,"title",TITLE),"sourceSctSha256",HASHES[1],
            "compiledRevisionId",compiled.getId(),"compiledDocxSha256",compiled.getDocxSha256(),"currentDocxSha256",DraftPdfConverter.sha256(current),"compiledStoryDocxSha256",story(compiled).get("compiledDocxSha256"),
            "pdfSha256",cache.getPdfSha256(),"previewEqualsExport",true,"currentEqualsCompiled",true,"rendererConversions",conversions,"profileProbeDirectories",probes,"maxRendererVersionProbes",4,
            "allPages",pages,"bodyLayoutPolicyApplied",true,"bodyLayoutStatus",nativeLayout.get("bodyLayoutStatus"),"visualAcceptance",false,"rasterExecuted",false,"targetPageCount",null,"sharedHttp",0,"sharedSql",0,"modelRequests",0));verifyNoInteractions(ai);
    }
}
