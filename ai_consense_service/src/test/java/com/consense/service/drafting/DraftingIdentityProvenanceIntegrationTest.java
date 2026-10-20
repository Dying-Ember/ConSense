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
import java.util.stream.Stream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real local MVC/JPA identity eligibility; no shared HTTP/database, model, renderer or service. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class DraftingIdentityProvenanceIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    private static final String VALUE="{\"number\":\"STRICT-123\",\"title\":\"Preserved identity title\"}";
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:strict_identity_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target","strict-identity-uploads",DB).toAbsolutePath().toString());}

    @Autowired MockMvc mvc;
    @Autowired DraftTemplateGraphService graphs;
    @Autowired SourceDocumentRepository sources;
    @Autowired DraftVariableRepository variables;
    @Autowired DraftDocumentRepository documents;
    @Autowired StorageService storage;
    @MockBean AiGateway ai;

    static Map<String,Object> map(Object... entries){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}
    JsonNode response(String method,String path,Object body)throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request="GET".equals(method)?get(path):"PUT".equals(method)?put(path):post(path);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body));
        return JsonUtils.parse(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode ok(String method,String path,Object body)throws Exception {JsonNode result=response(method,path,body);assertEquals(0,result.path("code").asInt(),result.toString());return result.path("data");}
    JsonNode getDraft(String id,String path)throws Exception{return ok("GET","/api/drafting/"+id+path,null);}
    JsonNode identity(String id)throws Exception {for(JsonNode row:getDraft(id,"/variables"))if("contractTitle".equals(row.path("key").asText()))return row;throw new AssertionError("Fixed identity missing");}
    JsonNode project(String id)throws Exception{return ok("GET","/api/projects/"+id,null);}
    DraftVariable stored(String id){return variables.findByProjectIdAndVarKey(id,"contractTitle").get();}
    void adopt(String id,String key,String value)throws Exception {
        Map<String,Object> patch=map("value",value,"note","Explicit isolated human adoption");
        if("duration".equals(key))for(JsonNode row:getDraft(id,"/variables"))if(key.equals(row.path("key").asText())){patch.put("graphRevisionId",row.path("graphRevisionId").asText());patch.put("fieldDefinitionHash",row.path("fieldDefinitionHash").asText());}
        ok("PUT","/api/drafting/"+id+"/variables/"+key,patch);
    }
    Map<String,Object> confirmation(String id)throws Exception {JsonNode p=project(id),name=p.path("name");return map("expectedProjectIdentity",map("nameZhHans",name.path("zhHans").asText(),"nameZhHant",name.path("zhHant").asText(),"nameEn",name.path("en").asText(),"contractNo",p.path("contractNo").isNull()?null:p.path("contractNo").asText()),"expectedAdoptedIdentity",map("value",stored(id).getValueText()));}
    String fresh(boolean reviewed)throws Exception {
        String id="strict-identity-"+(reviewed?"graph-":"legacy-")+UUID.randomUUID();
        ok("POST","/api/projects",map("id",id,"nameZhHans","Temporary identity","nameZhHant","Temporary identity","nameEn","Temporary identity","contractNo","TEMP-STRICT","packageRef","KEEP-PACKAGE","specification","KEEP-SPEC"));
        if(reviewed)reviewedGraph(id);
        return id;
    }
    void reviewedGraph(String id)throws Exception {
        byte[] bytes;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("Duration: ____ months.");doc.write(out);bytes=out.toByteArray();}
        Map<String,Object> selected=new LinkedHashMap<>();String first=null;
        for(String key:Arrays.asList("NTT","SCT","SCC")) {
            SourceDocument source=source(id,SourceDocument.CATEGORY_STANDARD_TEMPLATE,key+"-strict.docx","Duration: ____ months.",bytes);source.setFileKey(key);sources.saveAndFlush(source);selected.put(key,source.getId().toString());if(first==null)first=source.getId().toString();
        }
        Map<String,Object> node=map("id","duration","nameZh","工期","nameEn","Duration","type",map("kind","number"),"role",map("kind","input"),"status","confirmed","docIds",Collections.singletonList(first),"sourceAnchors",Collections.singletonList(map("docId",first,"text","Duration: ____ months.")),"editTargets",new ArrayList<>(),"options",new ArrayList<>());
        Map<String,Object> graph=map("kind","consense-template-graph-v1","graph",map("variables",Collections.singletonList(node),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",2,"blank",false,"guidanceMode","recognized"),"selectedDocuments",selected);
        String revision=String.valueOf(graphs.save(id,graph,null).get("id"));graphs.confirm(id,revision,null);
    }
    SourceDocument source(String id,String category,String name,String text,byte[] bytes){SourceDocument source=new SourceDocument();source.setProjectId(id);source.setCategory(category);source.setFileName(name);source.setTextContent(text);source.setParseStatus("PARSED");source.setSourceSha256(DraftTemplateRouting.sha(bytes));source.setStoragePath(storage.store(id,category,new MockMultipartFile("file",name,"application/octet-stream",bytes)).getPath());return sources.saveAndFlush(source);}
    String validSources(String id){String quote="The contract number is STRICT-123 and the title is Preserved identity title.";SourceDocument source=source(id,SourceDocument.CATEGORY_PROJECT_INPUT,"strict-minutes.txt",quote,quote.getBytes(StandardCharsets.UTF_8));return JsonUtils.write(Collections.singletonList(map("value",VALUE,"sourceDocumentId",source.getId(),"fileName",source.getFileName(),"sourceHash",DraftAdoption.sourceHash(source),"sourceQuote",quote,"originalSha256",source.getSourceSha256())));}
    void saveProvenance(String id,String raw,boolean manual){DraftVariable row=stored(id);row.setAdoptedSourcesJson(raw);row.setManuallyEdited(manual);row.setConfirmed(true);row.setReviewRequired(false);row.setConfirmedFrom("INPUT");row.setNoteText("Historical adoption note remains intact");variables.saveAndFlush(row);}
    void rejectionPreservesOtherContent(String id,boolean reviewed,String raw,boolean manual)throws Exception {
        String other=reviewed?"duration":"contractPeriodMonths";adopt(id,other,"42");adopt(id,"contractTitle",VALUE);saveProvenance(id,raw,manual);
        DraftDocument historical=new DraftDocument();historical.setProjectId(id);historical.setFileKey("NTT");historical.setTitle("Preserved historical draft");historical.setContent("Historical content must remain byte-for-byte");historical.setSnapshotId(String.join("",Collections.nCopies(64,"a")));historical.setInputSnapshotJson("{\"historical\":true}");historical.setUnresolvedJson("[{\"id\":\"historical-unresolved\"}]");historical=documents.saveAndFlush(historical);
        Long historicalId=historical.getId();String historyBefore=JsonUtils.write(documents.findById(historicalId).get()),valueBefore=stored(id).getValueText(),noteBefore=stored(id).getNoteText();JsonNode projectBefore=project(id),visible=identity(id),plan=getDraft(id,"/plan");
        JsonNode confirmation=response("POST","/api/drafting/"+id+"/identity/confirm",confirmation(id));
        assertAll("Strict identity provenance; retain unrelated inputs and historical records",
            ()->assertFalse(plan.path("effectiveValues").has("contractTitle"),"Corrupted or source-empty identity leaked into effective plan"),
            ()->assertTrue(visible.path("reviewRequired").asBoolean(),"Identity must require review"),
            ()->assertEquals("needs_review",visible.path("adoptionState").asText()),
            ()->assertEquals(4007,confirmation.path("code").asInt(),"Controlled provenance rejection must precede Project update"),
            ()->assertEquals(42,plan.path("effectiveValues").path(other).asInt(),"Unrelated adopted input remains effective"),
            ()->assertEquals(projectBefore,project(id),"Rejected confirmation must retain Project metadata"),
            ()->assertEquals(valueBefore,stored(id).getValueText(),"Historical adopted identity must stay stored"),
            ()->assertEquals(raw,stored(id).getAdoptedSourcesJson(),"Do not rewrite or repair corrupt historical provenance"),
            ()->assertEquals(noteBefore,stored(id).getNoteText()),
            ()->assertEquals(historyBefore,JsonUtils.write(documents.findById(historicalId).get()),"Historical draft content/snapshot/unresolved must stay untouched"));
        verifyNoInteractions(ai);
    }

    static Stream<Arguments> malformed(){List<Arguments> out=new ArrayList<>();for(boolean reviewed:Arrays.asList(false,true))for(Object[] raw:new Object[][]{{"truncated","[",true},{"object","{}",true},{"json-null","null",true},{"null-member","[null]",true},{"trailing-json","[] {}",true},{"blank","",true},{"missing",null,true},{"source-backed-empty","[]",false},{"scalar-member","[42]",true},{"object-without-source","[{}]",true}})out.add(Arguments.of(reviewed,raw[0],raw[1],raw[2]));return out.stream();}
    @ParameterizedTest(name="reviewed={0} rejects {1}") @MethodSource("malformed")
    void malformedAndSourceBackedEmptyIdentitiesStayHistoricalAndCannotRenameProject(boolean reviewed,String kind,String raw,boolean manual)throws Exception {String id=fresh(reviewed);rejectionPreservesOtherContent(id,reviewed,raw,manual);}
    static Stream<Arguments> modes(){return Stream.of(Arguments.of(false),Arguments.of(true));}
    @ParameterizedTest(name="reviewed={0} fresh missing identity") @MethodSource("modes")
    void freshUnadoptedIdentityRemainsMissing(boolean reviewed)throws Exception {String id=fresh(reviewed);JsonNode value=identity(id);assertFalse(value.path("reviewRequired").asBoolean());assertEquals("missing",value.path("adoptionState").asText());assertFalse(getDraft(id,"/plan").path("effectiveValues").has("contractTitle"));verifyNoInteractions(ai);}
    @ParameterizedTest(name="reviewed={0} manual INPUT empty array") @MethodSource("modes")
    void explicitManualEmptyArrayRemainsEffectiveAndCanConfirmFromInput(boolean reviewed)throws Exception {String id=fresh(reviewed);adopt(id,"contractTitle",VALUE);assertEquals("INPUT",stored(id).getConfirmedFrom());assertEquals("[]",stored(id).getAdoptedSourcesJson());assertTrue(stored(id).getManuallyEdited());assertTrue(getDraft(id,"/plan").path("effectiveValues").has("contractTitle"));assertFalse(identity(id).path("reviewRequired").asBoolean());ok("POST","/api/drafting/"+id+"/identity/confirm",confirmation(id));assertEquals("STRICT-123",project(id).path("contractNo").asText());assertEquals("PROJECT_IDENTITY",stored(id).getConfirmedFrom());verifyNoInteractions(ai);}
    @ParameterizedTest(name="reviewed={0} valid source INPUT identity") @MethodSource("modes")
    void completeSourceBindingRemainsEffectiveWithoutAnEarlierMetadataConfirmation(boolean reviewed)throws Exception {String id=fresh(reviewed);adopt(id,"contractTitle",VALUE);saveProvenance(id,validSources(id),false);assertTrue(getDraft(id,"/plan").path("effectiveValues").has("contractTitle"));assertFalse(identity(id).path("reviewRequired").asBoolean());ok("POST","/api/drafting/"+id+"/identity/confirm",confirmation(id));assertEquals("STRICT-123",project(id).path("contractNo").asText());verifyNoInteractions(ai);}
    static Stream<Arguments> changedSources(){List<Arguments> out=new ArrayList<>();for(boolean reviewed:Arrays.asList(false,true))for(String kind:Arrays.asList("hash","quote","project","category","parse","mixed-null"))out.add(Arguments.of(reviewed,kind));return out.stream();}
    @ParameterizedTest(name="reviewed={0} invalid nonempty source {1}") @MethodSource("changedSources")
    void nonemptySourceArraysMustKeepEveryExistingSourceGuard(boolean reviewed,String kind)throws Exception {String id=fresh(reviewed);String raw=validSources(id);List<Object> rows=JsonUtils.readList(raw,Object.class);Map<String,Object> candidate=(Map<String,Object>)rows.get(0);SourceDocument source=sources.findById(((Number)candidate.get("sourceDocumentId")).longValue()).get();
        if("hash".equals(kind))candidate.put("sourceHash",String.join("",Collections.nCopies(64,"0")));
        if("quote".equals(kind))candidate.put("sourceQuote","Quote absent from the original source");
        if("project".equals(kind))source.setProjectId(fresh(false));
        if("category".equals(kind))source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        if("parse".equals(kind))source.setParseStatus("FAILED");
        if("mixed-null".equals(kind))rows.add(null);
        sources.saveAndFlush(source);rejectionPreservesOtherContent(id,reviewed,JsonUtils.write(rows),false);
    }
    @Test void ordinaryPermissiveUiHelperRemainsCompatible(){DraftVariable value=new DraftVariable();value.setAdoptedSourcesJson("{broken");assertTrue(DraftAdoption.adoptedSources(value).isEmpty());}
}
