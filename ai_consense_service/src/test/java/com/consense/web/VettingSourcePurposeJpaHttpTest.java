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
    @Autowired com.consense.service.sources.CanonicalCurrentSourceReader canonicalCurrent;
    @Autowired com.consense.service.sources.NormalObservedPreparationService normalPreparation;
    @Autowired com.consense.service.sources.OerSourceRoutingRefreshService routingRefresh;
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
            SourceDocument after=fresh(d);assertEquals(role,after.getReviewRole());assertEquals(Arrays.asList("vetting","advisory"),OerSourceMetadata.identity(after).getEligibleModules());assertEquals("human_confirmed_source_use",OerSourceMetadata.identity(after).getIdentityStatus());
            for(String module:new String[]{"vetting","advisory"})assertEquals(d.getId(),canonicalCurrent.currentDocument(project,d.getId(),module).getId());
            after.setReviewRole(null);after.setOerIdentityJson(d.getOerIdentityJson());assertEquals(before,JsonUtils.write(after));assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
            List<String> writes=new ArrayList<>();for(String sql:SqlCapture.statements)if(sql.toLowerCase(Locale.ROOT).startsWith("update source_document"))writes.add(sql.toLowerCase(Locale.ROOT));
            assertEquals(2,writes.size());for(String write:writes){String assignments=write.split(" where ")[0];assertTrue(assignments.matches("update source_document(?: \\w+)? set (?:review_role|oer_identity_json)\\s*=\\s*\\?"),assignments);}
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

    SourceDocument pendingSource(String name)throws Exception {
        SourceDocument d=source(name);d.setParseStatus("IDENTITY_PENDING");d.setParseMessage("Authored initial identity observation; full parsing not started");sources.saveAndFlush(d);return fresh(d);
    }
    Map<String,Object> pendingRequest(SourceDocument d) {
        Map<String,Object> body=new LinkedHashMap<>(request(d,"tender"));body.put("confirmTextContractMaterial",true);return body;
    }
    JsonNode putPending(SourceDocument d,Map<String,Object> body)throws Exception {
        return JsonUtils.parse(mvc.perform(put("/api/vetting/"+project+"/sources/"+d.getId()+"/role")
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body))).andReturn().getResponse().getContentAsString());
    }
    void rejectPending(SourceDocument d,Map<String,Object> body)throws Exception {
        String before=JsonUtils.write(fresh(d));assertEquals(4104,putPending(d,body).path("code").asInt());unchanged(d,before);
    }
    @Test void identityPendingOriginalOffersSeparateExplicitConfirmationAndPinsWithoutBecomingUsable()throws Exception {
        SourceDocument d=pendingSource("pending-list.txt");d.setTextContent("");sources.saveAndFlush(d);JsonNode row=listed().get(0);
        assertTrue(row.path("identityConfirmationAvailable").asBoolean(),row.toString());assertFalse(row.path("roleConfirmationAvailable").asBoolean());assertFalse(row.path("parsed").asBoolean());
        assertEquals(d.getSourceSha256(),row.path("originalSourceSha256").asText());assertEquals(VettingCorpus.sourceHash(d),row.path("sourceHash").asText());assertEquals(64,row.path("routingSha256").asText().length());
        for(String module:new String[]{"vetting","advisory"})assertTrue(ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset",module).isEmpty());
        verifyNoInteractions(externalLlm,parser,oer);
    }
    @Test void explicitPendingConfirmationPublishesParsingBeforeCallingTheUsualParserOutsideATransaction()throws Exception {
        SourceDocument d=pendingSource("pending-success.txt");byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));String oldHash=VettingCorpus.sourceHash(d);
        doAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            SourceDocument parsing=fresh(d);assertEquals("PARSING",parsing.getParseStatus());assertEquals("tender",parsing.getReviewRole());
            JsonNode visible=listed().get(0);assertFalse(visible.path("parsed").asBoolean());assertFalse(visible.path("identityConfirmationAvailable").asBoolean());
            for(String module:new String[]{"vetting","advisory"})assertTrue(ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset",module).isEmpty());
            return invocation.callRealMethod();
        }).when(parser).parse(eq(d.getFileName()),any(byte[].class));
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());JsonNode row=response.path("data");
        assertEquals(String.valueOf(d.getId()),row.path("documentId").asText());assertEquals("tender",row.path("sourceRole").asText());assertEquals("PARSED",row.path("parseStatus").asText());assertTrue(row.path("parsed").asBoolean());
        assertEquals(d.getSourceSha256(),row.path("originalSourceSha256").asText());assertNotEquals(oldHash,row.path("sourceHash").asText());assertEquals(64,row.path("routingSha256").asText().length());
        assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));verify(parser,times(1)).parse(eq(d.getFileName()),any(byte[].class));verifyNoInteractions(externalLlm,oer);
    }
    @Test void pendingConfirmationRequiresTheExplicitCheckCurrentPinsOriginalAndNoActiveReview()throws Exception {
        SourceDocument d=pendingSource("pending-guards.txt");rejectPending(d,new LinkedHashMap<>(request(d,"tender")));
        for(String key:new String[]{"expectedOriginalSourceSha256","expectedSourceHash","expectedRoutingSha256"}){Map<String,Object> body=pendingRequest(d);body.put(key,"0".repeat(64));rejectPending(d,body);}
        for(String state:new String[]{"QUEUED","RUNNING"}){VettingRun active=run(state);rejectPending(d,pendingRequest(d));runs.delete(active);}
        byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));Files.write(Paths.get(d.getStoragePath()),"changed original".getBytes(StandardCharsets.UTF_8));rejectPending(d,pendingRequest(d));Files.write(Paths.get(d.getStoragePath()),original);
        Project other=new Project();other.setId("pending-other-"+UUID.randomUUID());projects.saveAndFlush(other);
        String before=JsonUtils.write(fresh(d));JsonNode foreign=JsonUtils.parse(mvc.perform(put("/api/vetting/"+other.getId()+"/sources/"+d.getId()+"/role").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(pendingRequest(d)))).andReturn().getResponse().getContentAsString());
        assertEquals(4104,foreign.path("code").asInt());unchanged(d,before);
    }
    @Test void pendingExplicitConfirmationCannotOverrideKnownExclusionsOrDrawingCandidates()throws Exception {
        for(String scope:new String[]{"drawing","pricing","works_pss","example"}){SourceDocument d=pendingSource("pending-scope-"+scope+".txt");d.setDocumentScope(scope);sources.saveAndFlush(d);rejectPending(d,pendingRequest(d));}
        for(String excluded:new String[]{"DRAWING","BQ","SOR","GENERAL_SUMMARY","WORKS_PSS","standard_template","evaluation_answer","drawing_candidate","module_excluded"}){
            SourceDocument d=pendingSource("pending-identity-"+excluded+".txt");OerDocumentIdentity identity=OerSourceMetadata.identity(d);
            if(Arrays.asList("standard_template","evaluation_answer").contains(excluded))identity.setSourcePurpose(excluded);
            else if("drawing_candidate".equals(excluded))identity.getCandidates().add(new OerDocumentIdentity.Candidate("DRAWING","unknown","Authored native drawing candidate"));
            else if("module_excluded".equals(excluded))identity.setEligibleModules(Collections.emptyList());else identity.setKind(excluded);
            d.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(d);rejectPending(d,pendingRequest(d));
        }
        SourceDocument drawing=pendingSource("pending-native-drawing.txt");byte[] bytes="AC1032 authored native DWG signature".getBytes(StandardCharsets.US_ASCII);Files.write(Paths.get(drawing.getStoragePath()),bytes);
        drawing.setSourceSha256(OerSourceMetadata.originalSha256(bytes));OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(drawing.getSourceSha256());drawing.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(drawing);rejectPending(drawing,pendingRequest(drawing));
    }
    @Test void pendingParseFailureRetainsTheOriginalObservationAndCannotBeReusedAsContractEvidence()throws Exception {
        SourceDocument d=pendingSource("pending-failure.txt");String text=d.getTextContent(),structure=d.getStructuredContentJson(),coverage=d.getParseCoverageJson(),nativeArchive=d.getNativePdfEvidenceJson();byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));
        doThrow(new IllegalStateException("Authored detail parser failed")).when(parser).parse(eq(d.getFileName()),any(byte[].class));
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());JsonNode row=response.path("data");assertEquals("FAILED",row.path("parseStatus").asText());assertFalse(row.path("parsed").asBoolean());assertEquals("tender",row.path("sourceRole").asText());
        SourceDocument after=fresh(d);assertEquals(text,after.getTextContent());assertEquals(structure,after.getStructuredContentJson());assertEquals(coverage,after.getParseCoverageJson());assertEquals(nativeArchive,after.getNativePdfEvidenceJson());assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
        for(String module:new String[]{"vetting","advisory"})assertTrue(ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset",module).isEmpty());
        JsonNode listed=listed().get(0);assertEquals(d.getSourceSha256(),listed.path("originalSourceSha256").asText());assertEquals(64,listed.path("sourceHash").asText().length());
        assertEquals(4104,putPending(d,pendingRequest(d)).path("code").asInt());verify(parser,times(1)).parse(eq(d.getFileName()),any(byte[].class));verifyNoInteractions(externalLlm,oer);
    }
    @Test void concurrentPendingRequestsRunTheDetailParserOnceAndRejectTheStaleSecondClaim()throws Exception {
        SourceDocument originalPending=pendingSource("pending-concurrent.txt");new TransactionTemplate(transactions).execute(tx->cas(originalPending,"tender"));
        SourceDocument d=fresh(originalPending);Map<String,Object> body=pendingRequest(d);java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return invocation.callRealMethod();}).when(parser).parse(eq(d.getFileName()),any(byte[].class));
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<JsonNode> first=pool.submit(()->putPending(d,body));assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<JsonNode> second=pool.submit(()->putPending(d,body));release.countDown();assertEquals(0,first.get(20,java.util.concurrent.TimeUnit.SECONDS).path("code").asInt());assertEquals(4104,second.get(20,java.util.concurrent.TimeUnit.SECONDS).path("code").asInt());
            verify(parser,times(1)).parse(eq(d.getFileName()),any(byte[].class));assertEquals("PARSED",fresh(d).getParseStatus());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void originalChangedDuringDetailParsingDiscardsTheResultAndKeepsTheObservation()throws Exception {
        SourceDocument d=pendingSource("pending-change-during-parse.txt");String text=d.getTextContent(),structure=d.getStructuredContentJson();
        doAnswer(invocation->{Files.write(Paths.get(d.getStoragePath()),"concurrently changed bytes".getBytes(StandardCharsets.UTF_8));return invocation.callRealMethod();}).when(parser).parse(eq(d.getFileName()),any(byte[].class));
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());assertEquals("FAILED",response.path("data").path("parseStatus").asText());assertFalse(response.path("data").path("parsed").asBoolean());
        SourceDocument after=fresh(d);assertEquals(text,after.getTextContent());assertEquals(structure,after.getStructuredContentJson());assertEquals(d.getSourceSha256(),after.getSourceSha256());
        for(String module:new String[]{"vetting","advisory"})assertTrue(ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset",module).isEmpty());verifyNoInteractions(externalLlm,oer);
    }

    @Test void anExplicitUploadRoleDoesNotReplaceTheSeparateTextIdentityConfirmation()throws Exception {
        doAnswer(invocation->{DocumentParser.ParsedDocument observed=(DocumentParser.ParsedDocument)invocation.callRealMethod();
            com.fasterxml.jackson.databind.node.ObjectNode routing=(com.fasterxml.jackson.databind.node.ObjectNode)observed.getRoutingObservation();
            routing.put("nonDrawingFormatVerified",false);
            org.springframework.test.util.ReflectionTestUtils.setField(observed,"routingObservation",routing);return observed;
        }).when(parser).observe(anyString(),any(byte[].class));
        byte[] original="Authored unidentified original awaiting human text identity".getBytes(StandardCharsets.UTF_8);
        JsonNode uploaded=JsonUtils.parse(mvc.perform(multipart("/api/vetting/"+project+"/package/upload").file(new MockMultipartFile("files","explicit-pending.txt","text/plain",original))
                .param("sourceRole","tender").param("documentScope","package")).andReturn().getResponse().getContentAsString());assertEquals(0,uploaded.path("code").asInt());
        SourceDocument d=sources.findByProjectIdOrderByIdAsc(project).get(0);assertEquals("IDENTITY_PENDING",d.getParseStatus());assertEquals("tender",d.getReviewRole());
        JsonNode row=listed().get(0);assertTrue(row.path("identityConfirmationAvailable").asBoolean(),row.toString());assertFalse(row.path("parsed").asBoolean());
        clearInvocations(parser);assertEquals(4104,putPending(d,new LinkedHashMap<>(request(d,"tender"))).path("code").asInt());verifyNoInteractions(parser);
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());assertEquals("PARSED",response.path("data").path("parseStatus").asText());assertEquals("tender",fresh(d).getReviewRole());
        assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));verify(parser,times(1)).parse(eq(d.getFileName()),any(byte[].class));verifyNoInteractions(externalLlm,oer);
    }

    @Test void humanTextConfirmationReachesBothRealCanonicalReadersAndSurvivesRoutingRefreshWithoutGuessingFamily()throws Exception {
        SourceDocument d=pendingSource("pending-canonical.txt");JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());
        SourceDocument after=fresh(d);OerDocumentIdentity identity=OerSourceMetadata.identity(after);assertEquals("UNKNOWN",identity.getKind());assertEquals("unknown",identity.getSourcePurpose());assertEquals("human_confirmed_textual_material",identity.getIdentityStatus());
        assertEquals(Arrays.asList("vetting","advisory"),identity.getEligibleModules());assertTrue(identity.getIdentityEvidence().stream().anyMatch(e->"human_text_contract_material".equals(e.getBasis())&&("original:"+d.getSourceSha256()).equals(e.getLocation())));
        for(String module:new String[]{"vetting","advisory"}) {
            assertEquals(d.getId(),canonicalCurrent.currentDocument(project,d.getId(),module).getId());
            SourceDocument normal=canonicalCurrent.normalDocument(project,d.getId(),module);assertNotNull(com.consense.service.vetting.NormalSourceCandidateGraph.requireBound(normal));
            JsonNode graph=normalPreparation.prepare(project,d.getId(),module);assertEquals("actual_source_candidate_generation",graph.path("status").asText());assertEquals(graph,com.consense.service.vetting.NormalSourceCandidateGraph.requireBound(canonicalCurrent.normalDocument(project,d.getId(),module)));
        }
        String text=after.getTextContent(),structure=after.getStructuredContentJson(),raw=after.getSourceSha256(),fileKey=after.getFileKey();
        routingRefresh.refresh(project,d.getId(),raw,VettingCorpus.sourceHash(after),CanonicalEvidenceService.routingHash(after));
        SourceDocument refreshed=fresh(d);assertEquals(fileKey,refreshed.getFileKey());assertEquals(text,refreshed.getTextContent());assertEquals(structure,refreshed.getStructuredContentJson());assertEquals(raw,refreshed.getSourceSha256());
        assertEquals("human_confirmed_textual_material",OerSourceMetadata.identity(refreshed).getIdentityStatus());assertEquals("UNKNOWN",OerSourceMetadata.identity(refreshed).getKind());
        for(String module:new String[]{"vetting","advisory"}){assertEquals(d.getId(),canonicalCurrent.currentDocument(project,d.getId(),module).getId());assertEquals("actual_source_candidate_generation",normalPreparation.prepare(project,d.getId(),module).path("status").asText());}verifyNoInteractions(externalLlm,oer);
    }

    @Test void legacyExplicitRolesCanRefreshModulePermissionWithoutReparseOrChangingSourceText()throws Exception {
        for(String role:new String[]{"tender","standard","project_fact","package_manifest"}) {
            SourceDocument d=source("legacy-confirmed-"+role+".txt");new TransactionTemplate(transactions).execute(tx->cas(d,role));SourceDocument before=fresh(d);
            assertNull(OerSourceMetadata.identity(before).getEligibleModules());String raw=before.getSourceSha256(),parsed=VettingCorpus.sourceHash(before),routing=CanonicalEvidenceService.routingHash(before),text=before.getTextContent(),structure=before.getStructuredContentJson(),archive=before.getNativePdfEvidenceJson();
            routingRefresh.refresh(project,d.getId(),raw,parsed,routing);SourceDocument after=fresh(d);OerDocumentIdentity identity=OerSourceMetadata.identity(after);
            assertEquals(role,after.getReviewRole());assertEquals(raw,after.getSourceSha256());assertEquals(parsed,VettingCorpus.sourceHash(after));assertNotEquals(routing,CanonicalEvidenceService.routingHash(after));assertEquals(text,after.getTextContent());assertEquals(structure,after.getStructuredContentJson());assertEquals(archive,after.getNativePdfEvidenceJson());
            assertEquals("UNKNOWN",identity.getKind());assertEquals("unknown",identity.getSourcePurpose());assertEquals("human_confirmed_source_use",identity.getIdentityStatus());assertTrue(identity.getIdentityEvidence().stream().anyMatch(e->"human_source_role".equals(e.getBasis())&&("original:"+raw).equals(e.getLocation())));
            for(String module:new String[]{"vetting","advisory"}){assertEquals(d.getId(),canonicalCurrent.currentDocument(project,d.getId(),module).getId());assertEquals("actual_source_candidate_generation",normalPreparation.prepare(project,d.getId(),module).path("status").asText());}
            routingRefresh.refresh(project,d.getId(),raw,parsed,CanonicalEvidenceService.routingHash(fresh(d)));assertEquals("human_confirmed_source_use",OerSourceMetadata.identity(fresh(d)).getIdentityStatus());
        }
        verifyNoInteractions(externalLlm,parser,oer);
    }

    @Test void legacyExplicitRoleRefreshCannotInventTextIdentityOrOverrideDrawingCandidatesAndExclusions()throws Exception {
        for(String defect:new String[]{"unverified","drawing_candidate","evaluation_answer","standard_template","excluded_modules"}) {
            SourceDocument d=source("legacy-rejected-"+defect+".txt");OerDocumentIdentity identity=OerSourceMetadata.identity(d);
            if("unverified".equals(defect))identity.setIdentityStatus("unknown");else if("drawing_candidate".equals(defect))identity.getCandidates().add(new OerDocumentIdentity.Candidate("DRAWING","unknown","Authored retained drawing candidate"));
            else if("excluded_modules".equals(defect))identity.setEligibleModules(Collections.emptyList());else identity.setSourcePurpose(defect);
            d.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(d);SourceDocument old=fresh(d);new TransactionTemplate(transactions).execute(tx->cas(old,"tender"));SourceDocument before=fresh(d);String retained=JsonUtils.write(before);
            assertThrows(com.consense.service.sources.CanonicalEvidenceException.class,()->routingRefresh.refresh(project,d.getId(),before.getSourceSha256(),VettingCorpus.sourceHash(before),CanonicalEvidenceService.routingHash(before)));
            assertEquals(retained,JsonUtils.write(fresh(d)));assertThrows(com.consense.service.sources.CanonicalEvidenceException.class,()->canonicalCurrent.currentDocument(project,d.getId(),"vetting"));
        }
        verifyNoInteractions(externalLlm,parser,oer);
    }
}
