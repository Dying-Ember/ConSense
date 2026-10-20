package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP, graph and isolated JPA boundaries; the external model is a deterministic fixture. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingProjectIdentityIntegrationTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:identity_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target","identity-uploads",DB).toAbsolutePath().toString());}
    @Autowired MockMvc mvc;
    @Autowired DraftTemplateGraphService graphs;
    @Autowired SourceDocumentRepository sources;
    @Autowired StorageService storage;
    @MockBean AiGateway ai;
    static Map<String,Object> map(Object... entries){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)out.put((String)entries[i],entries[i+1]);return out;}
    JsonNode call(String project,String path)throws Exception{return JsonUtils.parse(mvc.perform(get("/api/drafting/{id}"+path,project)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode identity(String project)throws Exception{for(JsonNode value:call(project,"/variables"))if("contractTitle".equals(value.path("key").asText()))return value;throw new AssertionError("Fixed project identity is absent");}
    void adopt(String project,Object body)throws Exception{mvc.perform(put("/api/drafting/{id}/variables/contractTitle",project).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body))).andExpect(jsonPath("$.code").value(0));}
    JsonNode project(String id)throws Exception{return JsonUtils.parse(mvc.perform(get("/api/projects/{id}",id)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    Map<String,Object> confirmation(JsonNode project,String value){return map("expectedProjectIdentity",map("nameZhHans",project.path("name").path("zhHans").asText(),"nameZhHant",project.path("name").path("zhHant").asText(),"nameEn",project.path("name").path("en").asText(),"contractNo",project.path("contractNo").isNull()?null:project.path("contractNo").asText()),"expectedAdoptedIdentity",map("value",value));}
    void confirm(String id,Object body,int code)throws Exception{mvc.perform(post("/api/drafting/{id}/identity/confirm",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body))).andExpect(jsonPath("$.code").value(code));}
    SourceDocument source(String id,String category,String name,String text,byte[] bytes){SourceDocument s=new SourceDocument();s.setProjectId(id);s.setCategory(category);s.setFileKey(SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(category)?"NTT":null);s.setFileName(name);s.setTextContent(text);s.setParseStatus("PARSED");s.setSourceSha256(DraftTemplateRouting.sha(bytes));s.setStoragePath(storage.store(id,category,new MockMultipartFile("file",name,"application/octet-stream",bytes)).getPath());return sources.save(s);}

    @Test void fixedIdentitySurvivesTheGraphAndOnlyAnExplicitCurrentConfirmationRenamesTheProject()throws Exception {
        String id="identity-"+UUID.randomUUID();
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(map("id",id,"nameZhHans","Temporary title","nameZhHant","Temporary title","nameEn","Temporary title","contractNo","TEMP-1","packageRef","KEEP-PACKAGE","pages","99","nttRange","1-10","sctRange","11-20","sccRange","21-99","specification","KEEP-SPEC","outputReferenceFile","KEEP-REFERENCE")))).andExpect(jsonPath("$.code").value(0));
        assertFalse(identity(id).path("confirmed").asBoolean());assertFalse(call(id,"/plan").path("effectiveValues").has("contractTitle"));
        byte[] bytes;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("Duration: ____ months.");doc.write(out);bytes=out.toByteArray();}
        SourceDocument template=source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"identity-template.docx","Duration: ____ months.",bytes);String docId=template.getId().toString();
        Map<String,Object> selected=map("NTT",docId);for(String key:Arrays.asList("SCT","SCC")){SourceDocument sibling=source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key+"-identity-template.docx","Duration: ____ months.",bytes);sibling.setFileKey(key);sources.saveAndFlush(sibling);selected.put(key,sibling.getId().toString());}
        Map<String,Object> node=map("id","duration","nameZh","工期","nameEn","Duration","type",map("kind","number"),"role",map("kind","input"),"status","confirmed","docIds",Collections.singletonList(docId),"sourceAnchors",Collections.singletonList(map("docId",docId,"text","Duration: ____ months.")),"editTargets",new ArrayList<>(),"options",new ArrayList<>());
        Map<String,Object> graph=map("kind","consense-template-graph-v1","graph",map("variables",Collections.singletonList(node),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",2,"blank",false,"guidanceMode","recognized"),"selectedDocuments",selected);
        String draft=String.valueOf(graphs.save(id,graph,null).get("id"));graphs.confirm(id,draft,null);
        JsonNode catalog=call(id,"/catalog");assertEquals("identity",catalog.path("groups").get(0).path("id").asText());assertEquals("contractTitle",catalog.path("groups").get(0).path("fields").get(0).path("key").asText());assertFalse(identity(id).path("confirmed").asBoolean());
        String initial=call(id,"/plan").path("snapshotId").asText(),manual="{\"number\":\"CN-123\",\"title\":\"Confirmed project title\"}";
        adopt(id,map("value",manual,"note","User checked this project identity"));String adopted=identity(id).path("value").asText();
        mvc.perform(put("/api/drafting/{id}/variables/contractTitle",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(map("value","{\"number\":true,\"title\":\"Wrong type\"}")))).andExpect(jsonPath("$.code").value(4007));assertEquals(adopted,identity(id).path("value").asText());
        assertEquals("CN-123",call(id,"/plan").path("effectiveValues").path("contractTitle").path("number").asText());assertNotEquals(initial,call(id,"/plan").path("snapshotId").asText());assertEquals("TEMP-1",project(id).path("contractNo").asText());
        Map<String,Object> staleMetadata=confirmation(project(id),adopted);((Map<String,Object>)staleMetadata.get("expectedProjectIdentity")).put("contractNo","OLD-DISPLAY");confirm(id,staleMetadata,4090);assertEquals("TEMP-1",project(id).path("contractNo").asText());
        JsonNode duration=null;for(JsonNode value:call(id,"/variables"))if("duration".equals(value.path("key").asText()))duration=value;assertNotNull(duration);
        mvc.perform(put("/api/drafting/{id}/variables/duration",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(map("value","42","note","Checked duration for generating the fixture package","graphRevisionId",duration.path("graphRevisionId").asText(),"fieldDefinitionHash",duration.path("fieldDefinitionHash").asText())))).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/drafting/{id}/generate",id)).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(3));JsonNode oldDocuments=call(id,"/documents");for(JsonNode doc:oldDocuments)assertFalse(doc.path("stale").asBoolean());
        String beforeConfirmation=call(id,"/plan").path("snapshotId").asText();confirm(id,confirmation(project(id),adopted),0);JsonNode confirmed=project(id);
        assertEquals("Confirmed project title",confirmed.path("name").path("zhHans").asText());assertEquals("CN-123",confirmed.path("contractNo").asText());assertEquals("KEEP-PACKAGE",confirmed.path("packageRef").asText());assertEquals("KEEP-SPEC",confirmed.path("specification").asText());assertEquals("KEEP-REFERENCE",confirmed.path("outputReferenceFile").asText());assertEquals("99",confirmed.path("pages").asText());assertEquals("21-99",confirmed.path("sccRange").asText());assertEquals("CN-123",confirmed.path("titleLines").get(1).path("en").asText());assertNotEquals(beforeConfirmation,call(id,"/plan").path("snapshotId").asText());
        JsonNode staleDocuments=call(id,"/documents");assertEquals(3,staleDocuments.size());for(int n=0;n<3;n++){assertTrue(staleDocuments.get(n).path("stale").asBoolean());assertEquals(oldDocuments.get(n).path("revisionId"),staleDocuments.get(n).path("revisionId"));assertEquals(oldDocuments.get(n).path("snapshotId"),staleDocuments.get(n).path("snapshotId"));}
        mvc.perform(post("/api/drafting/{id}/plan",id).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(map("values",map("contractTitle","{\"number\":\"PREVIEW\",\"title\":\"Preview only\"}"))))).andExpect(jsonPath("$.data.effectiveValues.contractTitle.number").value("PREVIEW"));assertEquals(adopted,identity(id).path("value").asText());
        String quote="The contract number is SRC-456 and the project title is Source title.";SourceDocument evidence=source(id,SourceDocument.CATEGORY_PROJECT_INPUT,"identity-minutes.txt",quote,quote.getBytes(StandardCharsets.UTF_8));
        when(ai.chatModel()).thenReturn("offline-identity-fixture");when(ai.complete(anyString(),anyString())).thenAnswer(invocation->{assertTrue(invocation.getArgument(0,String.class).contains("contractTitle"));return JsonUtils.write(Collections.singletonList(map("key","contractTitle","value",map("number","SRC-456","title","Source title"),"sourceQuote",quote,"reason","Explicit source identity","confidence",0.99)));});
        mvc.perform(post("/api/drafting/{id}/variables/extract",id)).andExpect(jsonPath("$.code").value(0));JsonNode suggested=identity(id);assertEquals(1,suggested.path("candidates").size());assertEquals("CN-123",project(id).path("contractNo").asText());
        confirm(id,confirmation(project(id),adopted),4007);
        adopt(id,map("candidateIndex",0,"candidateSnapshot",suggested.path("candidates").get(0)));String sourceAdopted=identity(id).path("value").asText();confirm(id,confirmation(project(id),adopted),4090);
        evidence.setTextContent("Source identity superseded.");sources.saveAndFlush(evidence);confirm(id,confirmation(project(id),sourceAdopted),4007);assertEquals("CN-123",project(id).path("contractNo").asText());assertFalse(call(id,"/plan").path("effectiveValues").has("contractTitle"));
    }
}
