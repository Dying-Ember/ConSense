package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.StorageService;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.ProjectDocumentScope;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Authored isolated MVC/JPA contracts, not real-source quality or provider acceptance. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
    "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
    "consense.oer-evidence.base-url="})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingSourcePurposeJpaHttpTest {
    static final String DB=UUID.randomUUID().toString().replace("-","");
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:source_purpose_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","source-purpose-uploads",DB).toAbsolutePath().toString());
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @Autowired VettingRunRepository runs;
    @Autowired VettingFindingRepository findings;
    @Autowired VettingChecklistReviewRepository checks;
    @Autowired StorageService storage;
    @Autowired VettingService service;
    @Autowired PlatformTransactionManager transactions;
    @MockBean AiGateway externalLlm;
    @SpyBean DocumentParser parser;
    @SpyBean OerBusinessEvidence oer;
    String project;

    @BeforeEach void setup(){
        project="purpose-"+UUID.randomUUID();Project p=new Project();p.setId(project);p.setNameEn("Authored purpose confirmation");projects.saveAndFlush(p);
        SqlCapture.statements.clear();clearInvocations(externalLlm,parser,oer);
    }
    SourceDocument source(String name)throws Exception{
        String text="AUTHORED source retained exactly \uD83D\uDE00, including its condition and exception.";
        byte[] original=("AUTHORED ORIGINAL BYTES "+name).getBytes(StandardCharsets.UTF_8);
        StorageService.StoredFile stored=storage.store(project,SourceDocument.CATEGORY_VETTING_PACKAGE,new MockMultipartFile("files",UUID.randomUUID()+".txt","text/plain",original));
        SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("SCT");d.setFileName(name);
        d.setDocumentScope("package");d.setParseStatus("PARTIAL");d.setParseMessage("Authored partial parse remains partial");d.setTextContent(text);d.setStoragePath(stored.getPath());
        d.setSourceSha256(OerSourceMetadata.originalSha256(original));d.setPageCount(7);d.setOcrUsed(false);d.setContentType("text/plain");d.setSizeBytes((long)original.length);
        DocumentBlock block=new DocumentBlock();block.setId("authored:1");block.setLocation("body/authored");block.setText(text);block.setKind("paragraph");block.setSource("authored_saved_text");
        d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));d.setParseCoverageJson("{\"complete\":false,\"limitations\":[\"Authored retained limitation\"]}");
        d.setNativePdfEvidenceJson("AUTHORED_NATIVE_ARCHIVE_RETAINED");d.setNormalCandidateJson("{\"authored\":\"retained candidate\"}");
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());identity.setIdentityStatus("format_supported_non_drawing");d.setOerIdentityJson(JsonUtils.write(identity));
        sources.saveAndFlush(d);return sources.findById(d.getId()).orElseThrow(AssertionError::new);
    }
    Map<String,String> request(SourceDocument d,String role){
        Map<String,String> body=new LinkedHashMap<>();body.put("sourceRole",role);body.put("expectedOriginalSourceSha256",d.getSourceSha256());
        body.put("expectedSourceHash",VettingCorpus.sourceHash(d));body.put("expectedRoutingSha256",CanonicalEvidenceService.routingHash(d));return body;
    }
    JsonNode putRole(String projectId,SourceDocument d,Map<String,String> body)throws Exception{
        return JsonUtils.parse(mvc.perform(put("/api/vetting/"+projectId+"/sources/"+d.getId()+"/role")
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body))).andReturn().getResponse().getContentAsString());
    }
    SourceDocument fresh(SourceDocument d){return sources.findById(d.getId()).orElseThrow(AssertionError::new);}
    void unchanged(SourceDocument d,String expected){assertEquals(expected,JsonUtils.write(fresh(d)));verifyNoInteractions(externalLlm,parser,oer);}
    void reject(SourceDocument d,Map<String,String> body)throws Exception{
        String before=JsonUtils.write(fresh(d));assertEquals(4104,putRole(project,d,body).path("code").asInt());unchanged(d,before);
    }
    int cas(SourceDocument before,String next){return sources.compareAndSetReviewRole(before.getId(),before.getProjectId(),before.getSourceSha256(),before.getCategory(),before.getFileName(),before.getOerIdentityJson(),
            before.getTextContent(),before.getStructuredContentJson(),before.getFileKey(),before.getReviewRole(),before.getDocumentScope(),before.getParseStatus(),before.getStoragePath(),next);}
    VettingRun run(String state){VettingRun r=new VettingRun();r.setId(UUID.randomUUID().toString());r.setProjectId(project);r.setStatus(state);r.setPhase("authored");r.setStartedAt(Instant.now());return runs.saveAndFlush(r);}
    JsonNode listed()throws Exception{return JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/files")).andReturn().getResponse().getContentAsString()).path("data");}

    @Test void fourExplicitChoicesChangeOnlyRoleAndReturnBoundPins()throws Exception{
        VettingFinding human=new VettingFinding();human.setProjectId(project);human.setCode("VT-HUMAN");human.setGroupKey("reference");human.setStatus("Handled");human.setReviewRemarks("Keep exact human remarks");human.setActionTaken("Original action");human.setAddendumRequired(false);findings.saveAndFlush(human);
        VettingChecklistReview check=new VettingChecklistReview();check.setId(UUID.randomUUID().toString());check.setProjectId(project);check.setRunId("authored-prior-run");check.setItemId("APP-I-001");check.setRunSnapshotSha256("a".repeat(64));check.setStatus("fulfilled");check.setRemarks("Original human checklist");check.setUpdatedAt(Instant.now());checks.saveAndFlush(check);
        String humanBefore=JsonUtils.write(findings.findById(human.getId()).get()),checkBefore=JsonUtils.write(checks.findById(check.getId()).get());
        for(String role:new String[]{"tender","standard","project_fact","package_manifest"}){
            SourceDocument d=source(role+".txt");String before=JsonUtils.write(d);byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));Map<String,String> body=request(d,role);
            SqlCapture.statements.clear();JsonNode response=putRole(project,d,body);assertEquals(0,response.path("code").asInt(),response.toString());JsonNode row=response.path("data");
            assertEquals(String.valueOf(d.getId()),row.path("documentId").asText());assertEquals(role,row.path("sourceRole").asText());assertTrue(row.path("parsed").asBoolean());assertFalse(row.path("roleConfirmationAvailable").asBoolean());
            assertEquals(body.get("expectedOriginalSourceSha256"),row.path("originalSourceSha256").asText());assertEquals(body.get("expectedSourceHash"),row.path("sourceHash").asText());assertEquals(64,row.path("routingSha256").asText().length());assertNotEquals(body.get("expectedRoutingSha256"),row.path("routingSha256").asText());
            SourceDocument after=fresh(d);assertEquals(role,after.getReviewRole());after.setReviewRole(null);assertEquals(before,JsonUtils.write(after));assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
            List<String> writes=new ArrayList<>();for(String sql:SqlCapture.statements)if(sql.toLowerCase(Locale.ROOT).startsWith("update source_document"))writes.add(sql.toLowerCase(Locale.ROOT));
            assertEquals(1,writes.size());String assignments=writes.get(0).split(" where ")[0];assertTrue(assignments.matches("update source_document(?: \\w+)? set review_role\\s*=\\s*\\?"),assignments);
        }
        assertEquals(humanBefore,JsonUtils.write(findings.findById(human.getId()).get()));assertEquals(checkBefore,JsonUtils.write(checks.findById(check.getId()).get()));verifyNoInteractions(externalLlm,parser,oer);
    }
    @Test void catalogueUsesStableIdsAndOnlyUnknownUsableRowsGetConfirmationPins()throws Exception{
        SourceDocument first=source("same-key-first.txt"),second=source("same-key-second.txt"),excluded=source("excluded.txt");excluded.setDocumentScope("drawing");sources.saveAndFlush(excluded);
        SourceDocument explicit=source("already-role.txt");new TransactionTemplate(transactions).execute(tx->cas(explicit,"project_fact"));
        JsonNode rows=listed();assertEquals(4,rows.size());Set<String> ids=new HashSet<>();for(JsonNode row:rows){assertTrue(ids.add(row.path("documentId").asText()));boolean allowed=row.path("fileName").asText().startsWith("same-key-");assertEquals(allowed,row.path("roleConfirmationAvailable").asBoolean());assertEquals(allowed?64:0,row.path("sourceHash").asText("").length());}
        assertTrue(ids.contains(String.valueOf(first.getId()))&&ids.contains(String.valueOf(second.getId())));verifyNoInteractions(externalLlm,parser,oer);
    }
    @Test void crossProjectRequestCannotConfirmOrLeakAnotherProjectsSource()throws Exception{
        SourceDocument d=source("private.txt");String before=JsonUtils.write(d);Project other=new Project();other.setId("other-"+UUID.randomUUID());projects.saveAndFlush(other);
        assertEquals(4104,putRole(other.getId(),d,request(d,"tender")).path("code").asInt());unchanged(d,before);
    }
    @Test void eachOfTheThreeStaleHashesRejectsWithoutWrites()throws Exception{
        SourceDocument d=source("stale.txt");for(String field:new String[]{"expectedOriginalSourceSha256","expectedSourceHash","expectedRoutingSha256"}){Map<String,String> body=request(d,"tender");body.put(field,"0".repeat(64));reject(d,body);}
    }
    @Test void invalidOrMissingRoleAndHashesFailValidationBeforeWriting()throws Exception{
        SourceDocument d=source("validation.txt");String before=JsonUtils.write(d);
        for(String field:new String[]{"sourceRole","expectedOriginalSourceSha256","expectedSourceHash","expectedRoutingSha256"}){Map<String,String> body=request(d,"tender");body.remove(field);assertNotEquals(0,putRole(project,d,body).path("code").asInt());unchanged(d,before);}
        Map<String,String> body=request(d,"auto");assertNotEquals(0,putRole(project,d,body).path("code").asInt());unchanged(d,before);
    }
    @Test void failedCaseInsensitiveSameNameReplacementRejectsBothServiceAndCas()throws Exception{
        SourceDocument d=source("same-name.txt");SourceDocument newer=source(" SAME-NAME.TXT ");newer.setParseStatus("FAILED");sources.saveAndFlush(newer);
        reject(d,request(d,"tender"));assertEquals(0,new TransactionTemplate(transactions).execute(tx->cas(d,"tender")).intValue());assertNull(fresh(d).getReviewRole());
    }
    @Test void explicitAndIdentityExclusionsCannotBeOverriddenByChoosingARole()throws Exception{
        for(String scope:new String[]{"drawing","pricing","works_pss","example"}){SourceDocument d=source(scope+".txt");d.setDocumentScope(scope);sources.saveAndFlush(d);reject(d,request(d,"tender"));}
        for(String purpose:new String[]{"standard_template","evaluation_answer","module_excluded","drawing_kind","pricing_kind","identity_role"}){
            SourceDocument d=source(purpose+".txt");OerDocumentIdentity identity=OerSourceMetadata.identity(d);
            if("module_excluded".equals(purpose))identity.setEligibleModules(Collections.singletonList("advisory"));
            else if("drawing_kind".equals(purpose))identity.setKind("DRAWING");else if("pricing_kind".equals(purpose))identity.setKind("BQ");else if("identity_role".equals(purpose))identity.setRole("tender");else identity.setSourcePurpose(purpose);
            d.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(d);reject(d,request(d,"tender"));
        }
        SourceDocument template=source("category-template.txt");template.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);sources.saveAndFlush(template);reject(template,request(template,"tender"));
        SourceDocument explicit=source("already-explicit.txt");new TransactionTemplate(transactions).execute(tx->cas(explicit,"project_fact"));reject(explicit,request(fresh(explicit),"tender"));
    }
    @Test void inProgressFailedPendingAndWhitespaceOnlySourcesCannotBeConfirmed()throws Exception{
        for(String state:new String[]{"PENDING","PARSING","IDENTITY_OBSERVED","IDENTITY_PENDING","EXCLUDED","FAILED"}){SourceDocument d=source(state+".txt");d.setParseStatus(state);sources.saveAndFlush(d);reject(d,request(d,"tender"));}
        for(String blankText:new String[]{"\u2003\t\r\n","\u00a0\u202f"," \t\r\n"}){SourceDocument blank=source("blank-"+UUID.randomUUID()+".txt");blank.setTextContent(blankText);sources.saveAndFlush(blank);reject(blank,request(blank,"tender"));}
    }
    @Test void queuedAndRunningReviewBlockTheEndpointAndTheDatabaseCas()throws Exception{
        SourceDocument d=source("active.txt");for(String state:new String[]{"QUEUED","RUNNING"}){VettingRun active=run(state);reject(d,request(d,"tender"));assertEquals(0,new TransactionTemplate(transactions).execute(tx->cas(d,"tender")).intValue());for(JsonNode row:listed())assertFalse(row.path("roleConfirmationAvailable").asBoolean());runs.delete(active);}
        for(JsonNode row:listed())assertTrue(row.path("roleConfirmationAvailable").asBoolean());
    }
    @Test void changedOriginalBytesOrMissingOriginalCannotBeConfirmed()throws Exception{
        SourceDocument d=source("bytes.txt");Path original=Paths.get(d.getStoragePath());byte[] bytes=Files.readAllBytes(original);java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(original);bytes[0]^=1;Files.write(original,bytes);Files.setLastModifiedTime(original,time);reject(d,request(d,"tender"));Files.delete(original);reject(d,request(d,"tender"));
    }
    @Test void casRejectsAConcurrentParsedOrRoutingSnapshotChange()throws Exception{
        for(String change:new String[]{"text","structure","scope","identity","name"}){
            SourceDocument before=source(change+".txt"),changed=fresh(before);
            if("text".equals(change))changed.setTextContent(changed.getTextContent()+" amended");else if("structure".equals(change))changed.setStructuredContentJson("[]");else if("scope".equals(change))changed.setDocumentScope("drawing");else if("identity".equals(change))changed.setOerIdentityJson(null);else changed.setFileName("renamed-"+changed.getFileName());
            sources.saveAndFlush(changed);assertEquals(0,new TransactionTemplate(transactions).execute(tx->cas(before,"tender")).intValue());assertNull(fresh(before).getReviewRole());
        }
    }
    @Test void detachedParsedAndPartialSavesCannotUndoHumanRoleWhileExplicitInsertsStillWork()throws Exception{
        for(String state:new String[]{"PARSED","PARTIAL"}){
            SourceDocument old=source(state+"-detached.txt");old.setParseStatus(state);sources.saveAndFlush(old);SourceDocument retained=fresh(old);String originalHash=VettingCorpus.sourceHash(retained),identity=retained.getOerIdentityJson();
            assertEquals(0,putRole(project,retained,request(retained,"tender")).path("code").asInt());sources.saveAndFlush(retained);
            SourceDocument after=fresh(old);assertEquals("tender",after.getReviewRole());assertEquals(originalHash,VettingCorpus.sourceHash(after));assertEquals(identity,after.getOerIdentityJson());assertEquals(state,after.getParseStatus());
        }
        verifyNoInteractions(externalLlm,parser,oer);
    }
    @Test void anExistingCompletedReportBecomesStaleWithoutChangingTheReportOrHumanRecords()throws Exception{
        SourceDocument known=source("known.txt");new TransactionTemplate(transactions).execute(tx->cas(known,"tender"));SourceDocument unresolved=source("newly-confirmed.txt");
        java.lang.reflect.Method hash=VettingService.class.getDeclaredMethod("sourceSetHash",List.class);hash.setAccessible(true);
        String oldSourceHash=(String)hash.invoke(null,ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset"));
        VettingRun old=run("COMPLETED");old.setSourceSnapshotSha256(oldSourceHash);old.setCoverageJson("{\"documents\":[],\"warnings\":[]}");old.setResultJson("{\"total\":0,\"messages\":[]}");old.setReportSnapshotSha256("b".repeat(64));runs.saveAndFlush(old);String before=JsonUtils.write(runs.findById(old.getId()).get());
        assertFalse(service.getRun(project,old.getId()).isSourceSetStale());assertEquals(0,putRole(project,unresolved,request(unresolved,"tender")).path("code").asInt());assertTrue(service.getRun(project,old.getId()).isSourceSetStale());assertEquals(before,JsonUtils.write(runs.findById(old.getId()).get()));
        verifyNoInteractions(externalLlm,parser);
    }
    @Test void uploadStillInsertsEachOfTheFourExplicitRoles()throws Exception{
        for(String role:new String[]{"tender","standard","project_fact","package_manifest"}){
            MockMultipartFile file=new MockMultipartFile("files",role+"-upload.txt","text/plain","Contract No: AUTHORED-101\nTextual tender provisions remain readable.".getBytes(StandardCharsets.UTF_8));
            JsonNode response=JsonUtils.parse(mvc.perform(multipart("/api/vetting/"+project+"/package/upload").file(file).param("sourceRole",role).param("documentScope","package")).andReturn().getResponse().getContentAsString());assertEquals(0,response.path("code").asInt());
            SourceDocument saved=sources.findByProjectIdOrderByIdAsc(project).stream().filter(d->d.getFileName().equals(role+"-upload.txt")).findFirst().get();assertEquals(role,saved.getReviewRole());
        }
        verifyNoInteractions(externalLlm,oer);
    }
}
