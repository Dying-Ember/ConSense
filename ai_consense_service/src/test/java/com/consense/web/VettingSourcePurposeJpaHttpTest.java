package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.common.BizException;
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
    @Autowired javax.sql.DataSource parsedReaderDataSource;
    @Autowired VettingService service;
    @Autowired com.consense.service.sources.CanonicalCurrentSourceReader canonicalCurrent;
    @Autowired com.consense.service.sources.NormalObservedPreparationService normalPreparation;
    @Autowired com.consense.service.sources.OerSourceRoutingRefreshService routingRefresh;
    @Autowired PlatformTransactionManager transactions;
    @MockBean AiGateway externalLlm;
    @SpyBean DocumentParser parser;
    @SpyBean OerBusinessEvidence oer;
    @SpyBean com.consense.service.vetting.VettingSemanticReview semantic;
    String project;

    @Test void progressReadsOnlyMetadataAndRestartMarksOnlyUnfinishedVettingFiles() throws Exception {
        SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        source.setFileName("inflight.pdf");source.setParseStatus("PARSING");source.setSourceSha256(String.join("",Collections.nCopies(64,"a")));
        source.setParseMessage("initial");source.setCreatedAt(Instant.now());source.setTextContent("body must not be polled");sources.saveAndFlush(source);
        assertEquals(0,sources.updateParseProgress(source.getId(),String.join("",Collections.nCopies(64,"b")),"wrong source"));
        assertEquals(1,sources.updateParseProgress(source.getId(),source.getSourceSha256(),"第 2 / 47 页：等待视觉识别结果"));
        SqlCapture.statements.clear();
        JsonNode response=JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/sources/progress")).andReturn().getResponse().getContentAsString());
        assertEquals(0,response.path("code").asInt(),response.toString());
        assertTrue(response.path("data").get(0).path("parseMessage").asText().contains("2 / 47"));
        assertFalse(SqlCapture.statements.stream().anyMatch(sql->sql.contains("text_content")||sql.contains("structured_content")),SqlCapture.statements.toString());
        service.markInterruptedParses();
        assertEquals("FAILED",sources.findById(source.getId()).get().getParseStatus());
        assertEquals("body must not be polled",sources.findById(source.getId()).get().getTextContent());
        assertEquals(0,sources.updateParseProgress(source.getId(),source.getSourceSha256(),"late result"));
    }

    @Test void fileCardsKeepLargeCoverageOutOfTheListQueryAndRetainItsWarningSummary() throws Exception {
        SourceDocument d=source("large-coverage-pre.txt");
        assertEquals(1,new TransactionTemplate(transactions).execute(tx->cas(d,"tender")).intValue());
        SourceDocument retained=fresh(d);String body="Authored complete-source text. "+"x".repeat(1_000_000);
        List<String> limits=new ArrayList<>();limits.add("Saved reading order needs review");
        for(int i=1;i<=36;i++)limits.add("Saved structure warning "+i);
        String coverage="{\"complete\":false,\"failedPages\":[7],\"limitations\":"+JsonUtils.write(limits)+",\"visualCoverage\":[{\"retained\":\""
                +"y".repeat(4_000_000)+"\"}]}";
        retained.setTextContent(body);retained.setParseCoverageJson(coverage);sources.saveAndFlush(retained);
        SqlCapture.statements.clear();
        JsonNode rows=listed();assertEquals(1,rows.size());JsonNode row=rows.get(0);
        assertEquals(body.length(),row.path("textChars").asInt());assertTrue(row.path("parsed").asBoolean());
        assertEquals("tender",row.path("sourceRole").asText());
        assertTrue(row.path("warnings").toString().contains("Saved reading order needs review"),row.toString());
        assertEquals(36,java.util.stream.StreamSupport.stream(row.path("warnings").spliterator(),false)
                .filter(w->w.asText().startsWith("Saved structure warning ")).count());
        assertTrue(row.path("warnings").toString().contains("[7]"),row.toString());
        String listSql=SqlCapture.statements.stream().map(s->s.toLowerCase(Locale.ROOT))
                .filter(s->s.contains("from source_document")&&s.contains("text_content_sha256")&&s.contains("parse_coverage_sha256"))
                .findFirst().orElse("");
        assertTrue(listSql.contains("length(")&&listSql.contains("parse_coverage_json"),SqlCapture.statements.toString());
        assertFalse(listSql.contains("substring("),listSql);
        assertFalse(listSql.matches("(?s).*,\\s*\\w+\\.parse_coverage_json\\s+(?:as\\s+)?\\w+.*"),listSql);
        assertFalse(listSql.matches("(?s).*,\\s*\\w+\\.structured_content_json\\s+(?:as\\s+)?\\w+.*")
                ||listSql.contains("native_pdf_evidence_json"),listSql);
        assertEquals(coverage,sources.findById(retained.getId()).orElseThrow(AssertionError::new).getParseCoverageJson());
        verifyNoInteractions(externalLlm,parser,oer);
    }

    @Test void fileCardsReadLargeClobTextPresenceWithoutTransformingTheWholeBody() throws Exception {
        installReaderStamps();
        SourceDocument blank=source("large-blank-body.txt");
        blank.setTextContent("\u2003\t\r\n\u00a0".repeat(400_000));sources.saveAndFlush(blank);
        SourceDocument later=source("long-blank-prefix.txt");
        later.setTextContent("\u2003\t\r\n\u00a0".repeat(400_000)+"Last readable clause");sources.saveAndFlush(later);

        SqlCapture.statements.clear();JsonNode rows=listed();assertEquals(2,rows.size());
        JsonNode blankRow=rows.get(0),laterRow=rows.get(1);
        assertEquals(blank.getTextContent().length(),blankRow.path("textChars").asInt());
        assertFalse(blankRow.path("parsed").asBoolean());assertFalse(blankRow.path("roleConfirmationAvailable").asBoolean());
        assertEquals(later.getTextContent().length(),laterRow.path("textChars").asInt());
        assertTrue(laterRow.path("roleConfirmationAvailable").asBoolean(),laterRow.toString());
        String listSql=SqlCapture.statements.stream().map(s->s.toLowerCase(Locale.ROOT))
                .filter(s->s.contains("from source_document")&&s.contains("text_content_sha256")&&s.contains("parse_coverage_sha256"))
                .findFirst().orElse("");
        assertFalse(listSql.contains("replace(")||listSql.contains("trim(text_content")||listSql.contains("substring(text_content"),listSql);
        verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @BeforeEach void setup(){
        project="purpose-"+UUID.randomUUID();Project p=new Project();p.setId(project);p.setNameEn("Authored purpose confirmation");projects.saveAndFlush(p);
        SqlCapture.statements.clear();clearInvocations(externalLlm,parser,oer,semantic);
    }
    @AfterEach void clearReaderStamps()throws Exception {
        // Reader tests install write triggers in this class's shared H2 database. Legacy fixtures need an unstamped start.
        try(java.sql.Connection connection=parsedReaderDataSource.getConnection();
            java.sql.Statement statement=connection.createStatement()) {
            statement.execute("drop trigger if exists canonical_source_binding_content_digests");
            statement.execute("drop trigger if exists source_document_content_digests");
        }
    }
    @Test void automaticListUploadsPersistRoutedPurposeAndEnterTheBusinessSourceSet()throws Exception {
        int index=0;
        for(String title:Arrays.asList("LIST OF CONTRACT DRAWINGS","LIST OF REFERENCE DRAWINGS/\nDOCUMENTS")) {
            byte[] original=("APPENDIX B\n"+title+"\n20250101").getBytes(StandardCharsets.UTF_8);
            JsonNode response=JsonUtils.parse(mvc.perform(multipart("/api/vetting/"+project+"/package/upload")
                    .file(new MockMultipartFile("files","anonymous-"+(index++)+".txt","text/plain",original)))
                    .andReturn().getResponse().getContentAsString());
            assertEquals(0,response.path("code").asInt(),response.toString());
        }
        List<SourceDocument> retained=sources.findBusinessSourcesByProjectId(project);
        assertEquals(2,retained.size());
        for(SourceDocument d:retained) {
            assertNull(d.getReviewRole());assertNull(d.getDocumentScope());assertEquals("tender",VettingCorpus.sourceRole(d));
            assertTrue(ProjectDocumentScope.parseUsable(d));assertEquals("content_supported",OerSourceMetadata.identity(d).getIdentityStatus());
        }
        assertEquals(2,ProjectDocumentScope.selected(project,retained,"fullset").size());
        assertEquals(2,service.listFiles(project).stream().filter(f->"tender".equals(f.getSourceRole())&&f.isParsed()).count());
        verifyNoInteractions(externalLlm);
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
    @Test void catalogueUsesStableIdsAndOnlyUnknownUsableRowsGetConfirmationControls()throws Exception{
        SourceDocument first=source("same-key-first.txt"),second=source("same-key-second.txt"),excluded=source("excluded.txt");excluded.setDocumentScope("drawing");sources.saveAndFlush(excluded);
        SourceDocument explicit=source("already-role.txt");new TransactionTemplate(transactions).execute(tx->cas(explicit,"project_fact"));
        JsonNode rows=listed();assertEquals(4,rows.size());Set<String> ids=new HashSet<>();for(JsonNode row:rows){assertTrue(ids.add(row.path("documentId").asText()));boolean allowed=row.path("fileName").asText().startsWith("same-key-");assertEquals(allowed,row.path("roleConfirmationAvailable").asBoolean());assertEquals(allowed||"already-role.txt".equals(row.path("fileName").asText())?64:0,row.path("sourceHash").asText("").length());}
        assertTrue(ids.contains(String.valueOf(first.getId()))&&ids.contains(String.valueOf(second.getId())));verifyNoInteractions(externalLlm,parser,oer);
    }
    @Test void unstampedAssignedSourceRetainsItsOriginalDownloadPin()throws Exception{
        SourceDocument source=source("legacy-assigned-original.txt");
        assertEquals(1,new TransactionTemplate(transactions).execute(tx->cas(source,"tender")).intValue());
        SourceDocument assigned=fresh(source);
        JsonNode file=listed().get(0);
        assertFalse(file.path("roleConfirmationAvailable").asBoolean());
        assertEquals(VettingCorpus.sourceHash(assigned),file.path("sourceHash").asText());
        assertEquals(assigned.getSourceSha256(),file.path("originalSourceSha256").asText());
        byte[] downloaded=mvc.perform(get("/api/vetting/"+project+"/sources/"+assigned.getId()+"/original")
                .param("sourceHash",file.path("sourceHash").asText())
                .param("originalSourceSha256",file.path("originalSourceSha256").asText())
                .param("sourceRole",file.path("sourceRole").asText()))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(Files.readAllBytes(Paths.get(assigned.getStoragePath())),downloaded);
        verifyNoInteractions(externalLlm,parser,oer);
    }
    @Test void largeUnstampedSourceOpensOnlyItsCurrentRegisteredOriginalWithoutHydratingSavedText()throws Exception{
        SourceDocument source=source("legacy-large-original.txt");
        source.setReviewRole("tender");source.setTextContent("AUTHORED saved content. "+"x".repeat(1_100_000));
        sources.saveAndFlush(source);
        JsonNode file=listed().get(0);
        assertEquals("",file.path("sourceHash").asText(""));
        assertEquals("",file.path("businessContentSha256").asText(""));
        assertEquals(source.getSourceSha256(),file.path("originalSourceSha256").asText());
        assertEquals(CanonicalEvidenceService.routingHash(fresh(source)),file.path("routingSha256").asText());
        SqlCapture.statements.clear();
        org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(get("/api/vetting/"+project+"/sources/"+source.getId()+"/registered-original")
                .param("originalSourceSha256",file.path("originalSourceSha256").asText())
                .param("routingSha256",file.path("routingSha256").asText())).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        assertEquals("routing_sha256",response.getHeader("X-Source-Revision-Kind"));
        assertArrayEquals(Files.readAllBytes(Paths.get(source.getStoragePath())),response.getContentAsByteArray());
        assertFalse(SqlCapture.statements.stream().anyMatch(sql->java.util.regex.Pattern.compile("\\b(?:text_content|structured_content_json)\\s+as\\s+\\w+",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql).find()),SqlCapture.statements.toString());
        JsonNode wrongRouting=JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/sources/"+source.getId()+"/registered-original")
                .param("originalSourceSha256",file.path("originalSourceSha256").asText())
                .param("routingSha256","0".repeat(64))).andReturn().getResponse().getContentAsString());
        assertEquals(4104,wrongRouting.path("code").asInt());
        JsonNode wrongOriginal=JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/sources/"+source.getId()+"/registered-original")
                .param("originalSourceSha256","0".repeat(64))
                .param("routingSha256",file.path("routingSha256").asText())).andReturn().getResponse().getContentAsString());
        assertEquals(4104,wrongOriginal.path("code").asInt());
        SourceDocument newer=source("legacy-large-original.txt");newer.setReviewRole("tender");sources.saveAndFlush(newer);
        JsonNode replaced=JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/sources/"+source.getId()+"/registered-original")
                .param("originalSourceSha256",file.path("originalSourceSha256").asText())
                .param("routingSha256",file.path("routingSha256").asText())).andReturn().getResponse().getContentAsString());
        assertEquals(4104,replaced.path("code").asInt());
        verifyNoInteractions(externalLlm,parser,oer);
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
    @Test void unconfirmedOriginalIsReadableByExactProjectPinsWithoutAdoptionOrParsing()throws Exception {
        for(boolean pending:new boolean[]{true,false}) {
            SourceDocument d=pending?pendingSource("pending-original-readable.txt"):source("unknown-original-readable.txt");
            if(pending){d.setTextContent("");sources.saveAndFlush(d);d=fresh(d);}
            byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));String before=JsonUtils.write(fresh(d));
            org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/original")
                    .param("sourceHash",VettingCorpus.sourceHash(d)).param("originalSourceSha256",d.getSourceSha256()).param("sourceRole","unknown"))
                    .andReturn().getResponse();
            assertEquals(200,response.getStatus());assertArrayEquals(original,response.getContentAsByteArray());
            assertEquals(d.getSourceSha256(),response.getHeader("X-Source-SHA256"));assertEquals(VettingCorpus.sourceHash(d),response.getHeader("X-Source-Revision"));
            for(String module:new String[]{"vetting","advisory"})assertTrue(ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset",module).isEmpty());
            unchanged(d,before);verifyNoInteractions(semantic);
        }
    }
    @Test void unconfirmedOriginalRetainsProjectRevisionBytesRoleStorageAndSupersededGuards()throws Exception {
        SourceDocument d=pendingSource("pending-original-guards.txt");String revision=VettingCorpus.sourceHash(d),raw=d.getSourceSha256();
        Project other=new Project();other.setId("original-other-"+UUID.randomUUID());projects.saveAndFlush(other);
        assertThrows(BizException.class,()->service.originalSource(other.getId(),d.getId(),revision,raw,"unknown"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),"a".repeat(64),raw,"unknown"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,"b".repeat(64),"unknown"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"tender"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,null));
        byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));Files.write(Paths.get(d.getStoragePath()),"changed original".getBytes(StandardCharsets.UTF_8));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));Files.write(Paths.get(d.getStoragePath()),original);
        Path outside=Files.createTempFile("consense-pending-outside-",".txt");String stored=d.getStoragePath();
        try {Files.write(outside,original);d.setStoragePath(outside.toString());sources.saveAndFlush(d);
            assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));
        } finally {Files.deleteIfExists(outside);d.setStoragePath(stored);sources.saveAndFlush(d);}
        d.setCategory("advice");sources.saveAndFlush(d);assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));
        d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);sources.saveAndFlush(d);
        pendingSource(d.getFileName());assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));
        verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @Test void unconfirmedOriginalCannotBypassExcludedKindsPurposesModulesCandidatesOrNativeDrawings()throws Exception {
        for(String scope:new String[]{"drawing","pricing","works_pss","example"}) {
            SourceDocument d=pendingSource("original-scope-"+scope+".txt");d.setDocumentScope(scope);sources.saveAndFlush(d);
            assertThrows(BizException.class,()->service.originalSource(project,d.getId(),VettingCorpus.sourceHash(d),d.getSourceSha256(),"unknown"));
        }
        for(String state:new String[]{"IDENTITY_PENDING","PARTIAL"})for(String excluded:new String[]{"DRAWING","BQ","SOR","GENERAL_SUMMARY","WORKS_PSS","SL_NON_TECHNICAL","standard_template","evaluation_answer","drawing_candidate","module_excluded"}) {
            SourceDocument d=source("original-"+state+"-"+excluded+".txt");d.setParseStatus(state);OerDocumentIdentity identity=OerSourceMetadata.identity(d);
            if(Arrays.asList("standard_template","evaluation_answer").contains(excluded))identity.setSourcePurpose(excluded);
            else if("drawing_candidate".equals(excluded))identity.getCandidates().add(new OerDocumentIdentity.Candidate("DRAWING","unknown","Authored retained drawing candidate"));
            else if("module_excluded".equals(excluded))identity.setEligibleModules(Collections.singletonList("advisory"));else identity.setKind(excluded);
            d.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(d);String before=JsonUtils.write(fresh(d));
            assertThrows(BizException.class,()->service.originalSource(project,d.getId(),VettingCorpus.sourceHash(d),d.getSourceSha256(),"unknown"));unchanged(d,before);
        }
        SourceDocument drawing=pendingSource("original-native-drawing.txt");byte[] bytes="AC1032 authored native DWG signature".getBytes(StandardCharsets.US_ASCII);Files.write(Paths.get(drawing.getStoragePath()),bytes);
        drawing.setSourceSha256(OerSourceMetadata.originalSha256(bytes));OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(drawing.getSourceSha256());drawing.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(drawing);
        assertThrows(BizException.class,()->service.originalSource(project,drawing.getId(),VettingCorpus.sourceHash(drawing),drawing.getSourceSha256(),"unknown"));verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    SourceDocument excludedDrawing(String name,boolean explicit)throws Exception {
        SourceDocument d=source(name);byte[] original=(explicit?"%PDF-1.4 AUTHORED retained drawing original":"AC1032 AUTHORED retained native drawing original").getBytes(StandardCharsets.US_ASCII);
        Files.write(Paths.get(d.getStoragePath()),original);d.setSourceSha256(OerSourceMetadata.originalSha256(original));d.setDocumentScope(explicit?"drawing":null);
        d.setReviewRole("unknown");d.setParseStatus("EXCLUDED");d.setParseMessage("Identity only; original retained");d.setPageCount(0);d.setOcrUsed(false);
        d.setTextContent("");d.setStructuredContentJson("[]");d.setNativePdfEvidenceJson(null);d.setNormalCandidateJson(null);d.setSizeBytes((long)original.length);
        OerSourceMetadata.captureDrawing(d,original);sources.saveAndFlush(d);return fresh(d);
    }
    @Test void excludedDrawingOriginalDownloadsExactBytesWithoutEnteringAnyReaderOrBusinessScope()throws Exception {
        for(boolean explicit:new boolean[]{true,false}) {
            SourceDocument d=excludedDrawing(explicit?"excluded-explicit.pdf":"excluded-native.dwg",explicit);String before=JsonUtils.write(fresh(d));
            byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));String revision=VettingCorpus.sourceHash(d);
            org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/original")
                    .param("sourceHash",revision).param("originalSourceSha256",d.getSourceSha256()).param("sourceRole","unknown")).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertArrayEquals(original,response.getContentAsByteArray());
            assertEquals(d.getSourceSha256(),response.getHeader("X-Source-SHA256"));assertEquals(revision,response.getHeader("X-Source-Revision"));
            assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
            for(String module:new String[]{"vetting","advisory"})assertTrue(ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset",module).isEmpty());
            String routing=CanonicalEvidenceService.routingHash(d);
            assertThrows(BizException.class,()->service.readerOriginal(project,d.getId(),d.getSourceSha256(),routing,"c".repeat(64)));
            assertThrows(BizException.class,()->service.parsedText(project,d.getId(),d.getSourceSha256(),routing,"c".repeat(64),1));
            assertThrows(BizException.class,()->service.parsedTables(project,d.getId(),d.getSourceSha256(),routing,"c".repeat(64),1));
            unchanged(d,before);verifyNoInteractions(semantic);
        }
    }
    @Test void excludedDrawingDownloadRetainsProjectRevisionRoleOriginalPathBytesAndLatestUploadGuards()throws Exception {
        SourceDocument d=excludedDrawing("excluded-original-guards.dwg",false);String revision=VettingCorpus.sourceHash(d),raw=d.getSourceSha256();
        Project other=new Project();other.setId("drawing-other-"+UUID.randomUUID());projects.saveAndFlush(other);
        assertThrows(BizException.class,()->service.originalSource(other.getId(),d.getId(),revision,raw,"unknown"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),"a".repeat(64),raw,"unknown"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,"b".repeat(64),"unknown"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"tender"));
        assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,null));
        byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));
        try {Files.write(Paths.get(d.getStoragePath()),"changed original".getBytes(StandardCharsets.UTF_8));assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));}
        finally {Files.write(Paths.get(d.getStoragePath()),original);}
        Path outside=Files.createTempFile("consense-drawing-outside-",".dwg");String stored=d.getStoragePath();
        try {Files.write(outside,original);d.setStoragePath(outside.toString());sources.saveAndFlush(d);assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));}
        finally {Files.deleteIfExists(outside);d.setStoragePath(stored);sources.saveAndFlush(d);}
        d.setCategory("advice");sources.saveAndFlush(d);assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));
        d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);sources.saveAndFlush(d);
        source(d.getFileName());assertThrows(BizException.class,()->service.originalSource(project,d.getId(),revision,raw,"unknown"));
        verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @Test void excludedDrawingDownloadRequiresTheCompletePersistedDrawingExclusionIdentity()throws Exception {
        for(int mode=0;mode<12;mode++) {
            SourceDocument d=excludedDrawing("excluded-identity-"+mode+".dwg",false);OerDocumentIdentity identity=OerSourceMetadata.identity(d);
            if(mode==0)d.setParseStatus("PARTIAL");if(mode==1)d.setFileKey("SCT");if(mode==2)identity.setKind("UNKNOWN");
            if(mode==3)identity.setSourcePurpose("evaluation_answer");if(mode==4)identity.setIdentityStatus("unknown");if(mode==5)identity.setIdentityStatus("explicit_scope");
            if(mode==6)identity.setEligibleModules(null);if(mode==7)identity.setEligibleModules(Collections.singletonList("vetting"));
            if(mode==8)identity.getIdentityEvidence().clear();if(mode==9)identity.getIdentityEvidence().get(0).setBasis("human_text_contract_material");
            if(mode==10)d.setDocumentScope("drawing");if(mode==11)identity.setSourceSha256("f".repeat(64));
            d.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(d);String before=JsonUtils.write(fresh(d));
            assertThrows(RuntimeException.class,()->service.originalSource(project,d.getId(),VettingCorpus.sourceHash(d),d.getSourceSha256(),"unknown"),"mode "+mode);
            unchanged(d,before);
        }
        verifyNoInteractions(semantic);
    }
    void installReaderStamps()throws Exception {
        try(java.sql.Connection c=parsedReaderDataSource.getConnection()){com.consense.service.sources.SourceContentDigestMigration.install(c);}
    }
    SourceDocument readerStamp(SourceDocument d) {
        return SourceDocumentRepository.scopeSnapshot(sources.findScopeMetadataById(d.getId()).orElseThrow(AssertionError::new));
    }
    JsonNode savedTextPage(SourceDocument d,int page)throws Exception {
        SourceDocument stamp=readerStamp(d);
        return JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/parsed-text")
                .param("originalSourceSha256",d.getSourceSha256()).param("routingSha256",CanonicalEvidenceService.routingHash(stamp))
                .param("businessContentSha256",stamp.getBusinessContentSha256()==null?"a".repeat(64):stamp.getBusinessContentSha256())
                .param("page",String.valueOf(page))).andReturn().getResponse().getContentAsString());
    }
    JsonNode savedTablePage(SourceDocument d,int page)throws Exception {
        SourceDocument stamp=readerStamp(d);
        return JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/parsed-tables")
                .param("originalSourceSha256",d.getSourceSha256()).param("routingSha256",CanonicalEvidenceService.routingHash(stamp))
                .param("businessContentSha256",stamp.getBusinessContentSha256()).param("page",String.valueOf(page))).andReturn().getResponse().getContentAsString());
    }
    @Test void savedTableReaderUsesPinnedBoundedCompactFieldsWithoutNativeArchiveOrWrites()throws Exception {
        installReaderStamps();SourceDocument d=source("compact-table-reader.pdf");d.setReviewRole("standard");
        com.consense.document.ParseCoverage coverage=new com.consense.document.ParseCoverage();coverage.setTotalPages(1);coverage.getFailedPages().add(1);
        DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument("",Collections.emptyList(),1,false,"Authored empty table observation",Collections.emptyList(),coverage,d.getSourceSha256(),Collections.singletonList(new com.consense.document.PdfPageEvidence()));
        assertTrue(com.consense.service.vetting.NormalPdfReadingProjection.capture(d,parsed,d.getSourceSha256()));d.setPageCount(1);sources.saveAndFlush(d);
        String before=JsonUtils.write(fresh(d));SqlCapture.statements.clear();JsonNode response=savedTablePage(d,1);assertEquals(0,response.path("code").asInt(),response.toString());JsonNode data=response.path("data");assertEquals("no_saved_tables",data.path("tableStatus").asText());assertTrue(data.path("tables").isEmpty());assertEquals("unknown",data.path("semanticQuality").asText());
        String queries=String.join("\n",SqlCapture.statements);assertTrue(queries.contains("length("),queries);assertFalse(queries.matches("(?s).*\\b(native_pdf_evidence_json|normal_candidate_json|text_content)\\b.*"),queries);assertFalse(queries.matches("(?s).*\\b(update|insert)\\s.*"),queries);assertEquals(before,JsonUtils.write(fresh(d)));
        assertEquals(4101,savedTablePage(d,2).path("code").asInt());
        com.fasterxml.jackson.databind.node.ObjectNode changed=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(d.getParseCoverageJson());changed.putObject(com.consense.service.vetting.NormalPdfReadingProjection.FIELD).put("schema","changed");d.setParseCoverageJson(JsonUtils.write(changed));sources.saveAndFlush(d);assertEquals("unavailable",savedTablePage(d,1).path("data").path("tableStatus").asText());verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @Test void savedTableReaderKeepsLegacyUnavailableAndByteChangesOutsideTheReadingContract()throws Exception {
        installReaderStamps();SourceDocument d=source("legacy-table-reader.pdf");d.setReviewRole("standard");sources.saveAndFlush(d);assertEquals("not_supported",savedTablePage(d,1).path("data").path("tableStatus").asText());
        SourceDocument stamp=readerStamp(d);assertTrue(sources.findBoundedParsedReading(d.getId(),project,d.getSourceSha256(),stamp.getBusinessContentSha256(),2).isEmpty());
        Files.write(Paths.get(d.getStoragePath()),"Changed original".getBytes(StandardCharsets.UTF_8));assertEquals(4104,savedTablePage(d,1).path("code").asInt());verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @Test void savedTextReaderReturnsOnlyPinnedPageAndMatchingModernOriginalWithoutHeavyPayloadQueries()throws Exception {
        installReaderStamps();SourceDocument d=source("reader-APH.pdf");d.setReviewRole("standard");d.setParseStatus("PARSED");d.setTextContent("--- P1 ---\nΦ ≤ >\n\n--- P3 ---\nCondition and exception");d.setPageCount(3);
        d.setNativePdfEvidenceJson("AUTHORED heavy native archive".repeat(1024));d.setStructuredContentJson("AUTHORED heavy structure".repeat(1024));sources.saveAndFlush(d);
        SourceDocument stamp=readerStamp(d);String before=JsonUtils.write(fresh(d));SqlCapture.statements.clear();
        JsonNode response=savedTextPage(d,3);assertEquals(0,response.path("code").asInt(),response.toString());JsonNode result=response.path("data");
        assertEquals("Condition and exception",result.path("text").asText());assertEquals(3,result.path("physicalPage").asInt());assertEquals("PARSED",result.path("parseStatus").asText());
        assertEquals("unknown",result.path("semanticQuality").asText());assertEquals("not_performed",result.path("canonicalAdoption").asText());assertEquals(stamp.getBusinessContentSha256(),result.path("businessContentSha256").asText());
        assertFalse(String.join("\n",SqlCapture.statements).matches("(?s).*\\b(structured_content_json|native_pdf_evidence_json|normal_candidate_json|parse_coverage_json)\\b.*"),SqlCapture.statements.toString());
        SqlCapture.statements.clear();
        org.springframework.mock.web.MockHttpServletResponse original=mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/reader-original")
                .param("originalSourceSha256",d.getSourceSha256()).param("routingSha256",CanonicalEvidenceService.routingHash(stamp))
                .param("businessContentSha256",stamp.getBusinessContentSha256())).andReturn().getResponse();
        assertEquals(200,original.getStatus());assertArrayEquals(Files.readAllBytes(Paths.get(d.getStoragePath())),original.getContentAsByteArray());
        assertEquals("business_content_sha256",original.getHeader("X-Source-Revision-Kind"));assertEquals(stamp.getBusinessContentSha256(),original.getHeader("X-Source-Revision"));
        assertFalse(String.join("\n",SqlCapture.statements).matches("(?s).*\\b(text_content|structured_content_json|native_pdf_evidence_json|normal_candidate_json|parse_coverage_json)\\b.*"),SqlCapture.statements.toString());
        unchanged(d,before);verifyNoInteractions(semantic);
    }
    @Test void savedTextReaderExplainsPendingMissingPagesAndAmbiguousMarkersWithoutBlankClaims()throws Exception {
        installReaderStamps();SourceDocument pending=pendingSource("reader-pending.pdf");pending.setTextContent("");sources.saveAndFlush(pending);
        assertEquals("detailed_parsing_not_started",savedTextPage(pending,1).path("data").path("status").asText());
        pending.setTextContent(null);sources.saveAndFlush(pending);assertEquals("detailed_parsing_not_started",savedTextPage(pending,1).path("data").path("status").asText());
        SourceDocument d=source("reader-missing.pdf");d.setTextContent("--- P1 ---\nFirst\n\n--- P3 ---\nThird");d.setPageCount(3);sources.saveAndFlush(d);
        JsonNode missing=savedTextPage(d,2);assertEquals(0,missing.path("code").asInt(),missing.toString());assertEquals("no_saved_text",missing.path("data").path("status").asText());assertEquals("",missing.path("data").path("text").asText());
        for(String markers:new String[]{"--- P1 ---\nOne\n--- P1 ---\nDuplicate","--- P2 ---\nTwo\n--- P1 ---\nOut of order"}) {
            d.setTextContent(markers);sources.saveAndFlush(d);assertEquals(4104,savedTextPage(d,1).path("code").asInt());
        }
        verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @Test void unpaginatedDocxReaderReturnsEveryCharacterInBoundedOrderedParts()throws Exception {
        installReaderStamps();SourceDocument d=source("reader-full.docx");d.setReviewRole("standard");d.setPageCount(0);
        for(String expected:new String[]{"A".repeat(65_520)+"\n"+"B".repeat(65_520)+"\uD83D\uDE00 tail", "x".repeat(65_535)+"\uD83D\uDE00 last"}) {
            d.setTextContent(expected);sources.saveAndFlush(d);String before=JsonUtils.write(fresh(d));
            JsonNode first=savedTextPage(d,1);assertEquals(0,first.path("code").asInt(),first.toString());
            int count=first.path("data").path("textPartCount").asInt();assertTrue(count>1);
            StringBuilder joined=new StringBuilder();
            for(int part=1;part<=count;part++) {
                JsonNode response=savedTextPage(d,part);assertEquals(0,response.path("code").asInt(),response.toString());JsonNode data=response.path("data");
                assertEquals("unpaginated_saved_text",data.path("pageNumberAuthority").asText());assertTrue(data.path("physicalPage").isNull());
                assertEquals(0,data.path("pageCount").asInt());assertEquals(part,data.path("textPart").asInt());assertEquals(count,data.path("textPartCount").asInt());
                assertTrue(data.path("text").asText().length()<=65_536);joined.append(data.path("text").asText());
            }
            assertEquals(expected,joined.toString());assertEquals(4101,savedTextPage(d,count+1).path("code").asInt());unchanged(d,before);
        }
        verifyNoInteractions(semantic);
    }
    @Test void savedTextReaderHasAtomicLengthAndRevisionBoundsAndDoesNotHealMissingStamps()throws Exception {
        installReaderStamps();SourceDocument d=source("reader-large.pdf");d.setTextContent("x".repeat(2_000_001));sources.saveAndFlush(d);
        SourceDocument stamp=readerStamp(d);assertTrue(sources.findBoundedParsedText(d.getId(),project,d.getSourceSha256(),stamp.getBusinessContentSha256(),2_000_000).isEmpty());
        assertTrue(sources.findBoundedParsedText(d.getId(),project,d.getSourceSha256(),"a".repeat(64),3_000_000).isEmpty());assertEquals(4104,savedTextPage(d,1).path("code").asInt());
        d.setTextContent("--- P1 ---\nSmall saved text");sources.saveAndFlush(d);
        try(java.sql.Connection c=parsedReaderDataSource.getConnection();java.sql.Statement statement=c.createStatement()) {
            statement.execute("drop trigger if exists source_document_content_digests");
            statement.executeUpdate("update source_document set text_content_sha256=null,structured_content_sha256=null,parse_coverage_sha256=null,normal_candidate_sha256=null,business_content_sha256=null where id="+d.getId());
        }
        try {SqlCapture.statements.clear();assertEquals(4104,savedTextPage(d,1).path("code").asInt());assertNull(readerStamp(d).getBusinessContentSha256());
            assertFalse(SqlCapture.statements.stream().anyMatch(sql->sql.toLowerCase(Locale.ROOT).startsWith("update ")),SqlCapture.statements.toString());
        } finally {installReaderStamps();}
        verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @Test void modernReadersRetainOriginalRoutingScopeAndLatestUploadGuards()throws Exception {
        installReaderStamps();SourceDocument d=source("reader-guard.pdf");d.setTextContent("--- P1 ---\nSaved first page");sources.saveAndFlush(d);SourceDocument stamp=readerStamp(d);
        String raw=d.getSourceSha256(),routing=CanonicalEvidenceService.routingHash(stamp),business=stamp.getBusinessContentSha256();
        assertThrows(BizException.class,()->service.parsedText(project,d.getId(),raw,"a".repeat(64),business,1));
        assertThrows(BizException.class,()->service.readerOriginal(project,d.getId(),raw,routing,"a".repeat(64)));
        byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));Files.write(Paths.get(d.getStoragePath()),"changed original".getBytes(StandardCharsets.UTF_8));
        assertThrows(BizException.class,()->service.parsedText(project,d.getId(),raw,routing,business,1));assertThrows(BizException.class,()->service.readerOriginal(project,d.getId(),raw,routing,business));Files.write(Paths.get(d.getStoragePath()),original);
        source(d.getFileName());assertThrows(BizException.class,()->service.parsedText(project,d.getId(),raw,routing,business,1));assertThrows(BizException.class,()->service.readerOriginal(project,d.getId(),raw,routing,business));
        SourceDocument excluded=source("reader-excluded.pdf");excluded.setDocumentScope("pricing");sources.saveAndFlush(excluded);SourceDocument blocked=readerStamp(excluded);
        assertThrows(BizException.class,()->service.parsedText(project,excluded.getId(),excluded.getSourceSha256(),CanonicalEvidenceService.routingHash(blocked),blocked.getBusinessContentSha256(),1));
        assertThrows(BizException.class,()->service.readerOriginal(project,excluded.getId(),excluded.getSourceSha256(),CanonicalEvidenceService.routingHash(blocked),blocked.getBusinessContentSha256()));
        verifyNoInteractions(externalLlm,parser,oer,semantic);
    }
    @Test void explicitPendingConfirmationPublishesParsingBeforeCallingTheUsualParserOutsideATransaction()throws Exception {
        SourceDocument d=pendingSource("pending-success.txt");byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));String oldHash=VettingCorpus.sourceHash(d);
        doAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            SourceDocument parsing=fresh(d);assertEquals("PARSING",parsing.getParseStatus());assertEquals("tender",parsing.getReviewRole());
            OerDocumentIdentity reading=invocation.getArgument(3);assertEquals(d.getSourceSha256(),reading.getSourceSha256());assertEquals("human_confirmed_textual_material",reading.getIdentityStatus());assertEquals("UNKNOWN",reading.getKind());assertEquals("tender",reading.getRole());assertNull(reading.getVersion());assertNull(reading.getEligibleModules());assertTrue(reading.getIdentityEvidence().stream().anyMatch(e->"human_text_contract_material".equals(e.getBasis())&&("original:"+d.getSourceSha256()).equals(e.getLocation())));
            JsonNode visible=listed().get(0);assertFalse(visible.path("parsed").asBoolean());assertFalse(visible.path("identityConfirmationAvailable").asBoolean());
            assertUnavailableSourceStaysInCoverage(d);
            return invocation.callRealMethod();
        }).when(parser).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());JsonNode row=response.path("data");
        assertEquals(String.valueOf(d.getId()),row.path("documentId").asText());assertEquals("tender",row.path("sourceRole").asText());assertEquals("PARSED",row.path("parseStatus").asText());assertTrue(row.path("parsed").asBoolean());
        assertEquals(d.getSourceSha256(),row.path("originalSourceSha256").asText());assertNotEquals(oldHash,row.path("sourceHash").asText());assertEquals(64,row.path("routingSha256").asText().length());
        assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));verifyNoInteractions(externalLlm,oer);
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
        doThrow(new IllegalStateException("Authored detail parser failed")).when(parser).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());JsonNode row=response.path("data");assertEquals("FAILED",row.path("parseStatus").asText());assertFalse(row.path("parsed").asBoolean());assertEquals("tender",row.path("sourceRole").asText());
        SourceDocument after=fresh(d);assertEquals(text,after.getTextContent());assertEquals(structure,after.getStructuredContentJson());assertEquals(coverage,after.getParseCoverageJson());assertEquals(nativeArchive,after.getNativePdfEvidenceJson());assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
        assertUnavailableSourceStaysInCoverage(d);
        JsonNode listed=listed().get(0);assertEquals(d.getSourceSha256(),listed.path("originalSourceSha256").asText());assertEquals(64,listed.path("sourceHash").asText().length());
        assertTrue(listed.path("identityConfirmationAvailable").asBoolean(),listed.toString());
        assertEquals(4104,putPending(d,pendingRequest(d)).path("code").asInt());verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));verifyNoInteractions(externalLlm,oer);
    }
    @Test void historicalFailedTextSourcesCanExplicitlyRetryTheSameOriginalWithoutReuploadOrAStoredHumanMarker()throws Exception {
        for(boolean known:new boolean[]{false,true}) {
            SourceDocument d=pendingSource(known?"failed-known-gct.txt":"failed-historical-list.txt");d.setParseStatus("FAILED");
            if(known) {OerDocumentIdentity identity=OerSourceMetadata.identity(d);identity.setKind("GCT");identity.setRole("tender");d.setOerIdentityJson(JsonUtils.write(identity));d.setFileKey("GCT");}
            sources.saveAndFlush(d);
            if(!known)assertEquals(1,new org.springframework.jdbc.core.JdbcTemplate(parsedReaderDataSource)
                    .update("update source_document set review_role=? where id=?","tender",d.getId()));
            d=fresh(d);byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));
            final SourceDocument failed=d;
            JsonNode visible=listed();JsonNode row=null;for(JsonNode item:visible)if(String.valueOf(d.getId()).equals(item.path("documentId").asText()))row=item;
            assertNotNull(row);assertTrue(row.path("identityConfirmationAvailable").asBoolean(),row.toString());assertFalse(row.path("parsed").asBoolean());
            org.springframework.mock.web.MockHttpServletResponse retained=mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/original")
                    .param("sourceHash",VettingCorpus.sourceHash(d)).param("originalSourceSha256",d.getSourceSha256()).param("sourceRole","tender")).andReturn().getResponse();
            assertEquals(200,retained.getStatus());assertArrayEquals(original,retained.getContentAsByteArray());
            rejectPending(d,new LinkedHashMap<>(request(d,"tender")));
            Map<String,Object> changedRole=pendingRequest(d);changedRole.put("sourceRole","standard");rejectPending(d,changedRole);
            for(String key:new String[]{"expectedOriginalSourceSha256","expectedSourceHash","expectedRoutingSha256"}) {Map<String,Object> stale=pendingRequest(d);stale.put(key,"0".repeat(64));rejectPending(d,stale);}
            VettingRun active=run("RUNNING");rejectPending(d,pendingRequest(d));runs.delete(active);
            doAnswer(invocation->{assertEquals("PARSING",fresh(failed).getParseStatus());return invocation.callRealMethod();})
                    .when(parser).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
            JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());assertEquals("PARSED",response.path("data").path("parseStatus").asText());
            assertEquals(d.getId(),fresh(d).getId());assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
            verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));clearInvocations(parser);
        }
        verifyNoInteractions(externalLlm,oer);
    }
    @Test void failedUnknownPurposeNeedsExplicitValidPurposeAndTextConfirmationBeforeSameOriginalRetry()throws Exception {
        for(String role:new String[]{"tender","standard","project_fact","package_manifest"}) {
            SourceDocument d=pendingSource("failed-unknown-"+role+".txt");d.setParseStatus("FAILED");
            String oldFailure="Preserved failed attempt: NORMAL_PDF_COMPACT_REFERENCE_LITERAL_TARGET";d.setParseMessage(oldFailure);sources.saveAndFlush(d);d=fresh(d);
            assertEquals("unknown",VettingCorpus.sourceRole(d));String before=JsonUtils.write(d);byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));
            JsonNode visible=null;for(JsonNode item:listed())if(String.valueOf(d.getId()).equals(item.path("documentId").asText()))visible=item;
            assertNotNull(visible);assertTrue(visible.path("identityConfirmationAvailable").asBoolean(),visible.toString());assertFalse(visible.path("parsed").asBoolean());
            org.springframework.mock.web.MockHttpServletResponse retained=mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/original")
                    .param("sourceHash",VettingCorpus.sourceHash(d)).param("originalSourceSha256",d.getSourceSha256()).param("sourceRole","unknown")).andReturn().getResponse();
            assertEquals(200,retained.getStatus());assertArrayEquals(original,retained.getContentAsByteArray());unchanged(d,before);
            Map<String,Object> body=pendingRequest(d);body.put("sourceRole",role);
            Map<String,Object> noTextCheck=new LinkedHashMap<>(body);noTextCheck.remove("confirmTextContractMaterial");rejectPending(d,noTextCheck);
            for(String invalid:new String[]{"unknown","sample_answer","drawing",""}) {
                Map<String,Object> invalidPurpose=new LinkedHashMap<>(body);invalidPurpose.put("sourceRole",invalid);
                assertNotEquals(0,putPending(d,invalidPurpose).path("code").asInt());unchanged(d,before);
            }
            Map<String,Object> missingPurpose=new LinkedHashMap<>(body);missingPurpose.remove("sourceRole");assertNotEquals(0,putPending(d,missingPurpose).path("code").asInt());unchanged(d,before);
            for(String key:new String[]{"expectedOriginalSourceSha256","expectedSourceHash","expectedRoutingSha256"}) {Map<String,Object> stale=new LinkedHashMap<>(body);stale.put(key,"0".repeat(64));rejectPending(d,stale);}
            VettingRun active=run("RUNNING");rejectPending(d,body);runs.delete(active);
            assertEquals("FAILED",fresh(d).getParseStatus());assertEquals(oldFailure,fresh(d).getParseMessage());verifyNoInteractions(parser,externalLlm,oer,semantic);
            final SourceDocument failed=d;
            doAnswer(invocation->{assertEquals("PARSING",fresh(failed).getParseStatus());return invocation.callRealMethod();})
                    .when(parser).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
            JsonNode response=putPending(d,body);assertEquals(0,response.path("code").asInt(),response.toString());assertEquals(role,response.path("data").path("sourceRole").asText());
            assertEquals(d.getId(),fresh(d).getId());assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
            verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));clearInvocations(parser);
        }
        verifyNoInteractions(externalLlm,oer,semantic);
    }

    @Test void failedRetryStillRejectsDrawingAndExcludedSources()throws Exception {
        for(String scope:new String[]{"drawing","pricing","works_pss","example"}) {
            SourceDocument d=pendingSource("failed-excluded-"+scope+".txt");d.setParseStatus("FAILED");d.setReviewRole("tender");d.setDocumentScope(scope);sources.saveAndFlush(d);
            assertFalse(service.listFiles(project).stream().filter(row->String.valueOf(d.getId()).equals(row.getDocumentId())).findFirst().orElseThrow(AssertionError::new).isIdentityConfirmationAvailable());
            rejectPending(d,pendingRequest(d));
        }
        SourceDocument d=pendingSource("failed-drawing-candidate.txt");d.setParseStatus("FAILED");d.setReviewRole("tender");OerDocumentIdentity identity=OerSourceMetadata.identity(d);
        identity.getCandidates().add(new OerDocumentIdentity.Candidate("DRAWING","unknown","Authored drawing candidate"));d.setOerIdentityJson(JsonUtils.write(identity));sources.saveAndFlush(d);rejectPending(d,pendingRequest(d));
    }
    @Test void concurrentPendingRequestsRunTheDetailParserOnceAndRejectTheStaleSecondClaim()throws Exception {
        SourceDocument originalPending=pendingSource("pending-concurrent.txt");new TransactionTemplate(transactions).execute(tx->cas(originalPending,"tender"));
        SourceDocument d=fresh(originalPending);Map<String,Object> body=pendingRequest(d);java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return invocation.callRealMethod();}).when(parser).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<JsonNode> first=pool.submit(()->putPending(d,body));assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<JsonNode> second=pool.submit(()->putPending(d,body));release.countDown();assertEquals(0,first.get(20,java.util.concurrent.TimeUnit.SECONDS).path("code").asInt());assertEquals(4104,second.get(20,java.util.concurrent.TimeUnit.SECONDS).path("code").asInt());
            verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));assertEquals("PARSED",fresh(d).getParseStatus());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void originalChangedDuringDetailParsingDiscardsTheResultAndKeepsTheObservation()throws Exception {
        SourceDocument d=pendingSource("pending-change-during-parse.txt");String text=d.getTextContent(),structure=d.getStructuredContentJson();
        doAnswer(invocation->{Files.write(Paths.get(d.getStoragePath()),"concurrently changed bytes".getBytes(StandardCharsets.UTF_8));return invocation.callRealMethod();}).when(parser).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());assertEquals("FAILED",response.path("data").path("parseStatus").asText());assertFalse(response.path("data").path("parsed").asBoolean());
        SourceDocument after=fresh(d);assertEquals(text,after.getTextContent());assertEquals(structure,after.getStructuredContentJson());assertEquals(d.getSourceSha256(),after.getSourceSha256());
        assertUnavailableSourceStaysInCoverage(d);verifyNoInteractions(externalLlm,oer);
    }

    void assertUnavailableSourceStaysInCoverage(SourceDocument source) {
        for(String module:new String[]{"vetting","advisory"}) {
            List<SourceDocument> selected=ProjectDocumentScope.selected(project,sources.findBusinessSourcesByProjectId(project),"fullset",module);
            assertEquals(Collections.singletonList(source.getId()),selected.stream().map(SourceDocument::getId).collect(java.util.stream.Collectors.toList()));
            assertFalse(ProjectDocumentScope.parseUsable(selected.get(0)),"Coverage membership must not authorize unavailable parser evidence");
        }
    }

    @Test void anExplicitUploadRoleDoesNotReplaceTheSeparateTextIdentityConfirmation()throws Exception {
        doAnswer(invocation->{DocumentParser.ParsedDocument observed=(DocumentParser.ParsedDocument)invocation.callRealMethod();
            com.fasterxml.jackson.databind.node.ObjectNode routing=(com.fasterxml.jackson.databind.node.ObjectNode)observed.getRoutingObservation();
            routing.put("nonDrawingFormatVerified",false);
            org.springframework.test.util.ReflectionTestUtils.setField(observed,"routingObservation",routing);return observed;
        }).when(parser).observeOrdinary(anyString(),any(byte[].class));
        byte[] original="Authored unidentified original awaiting human text identity".getBytes(StandardCharsets.UTF_8);
        JsonNode uploaded=JsonUtils.parse(mvc.perform(multipart("/api/vetting/"+project+"/package/upload").file(new MockMultipartFile("files","explicit-pending.txt","text/plain",original))
                .param("sourceRole","tender").param("documentScope","package")).andReturn().getResponse().getContentAsString());assertEquals(0,uploaded.path("code").asInt());
        SourceDocument d=sources.findByProjectIdOrderByIdAsc(project).get(0);assertEquals("IDENTITY_PENDING",d.getParseStatus());assertEquals("tender",d.getReviewRole());
        JsonNode row=listed().get(0);assertTrue(row.path("identityConfirmationAvailable").asBoolean(),row.toString());assertFalse(row.path("parsed").asBoolean());
        clearInvocations(parser);assertEquals(4104,putPending(d,new LinkedHashMap<>(request(d,"tender"))).path("code").asInt());verifyNoInteractions(parser);
        JsonNode response=putPending(d,pendingRequest(d));assertEquals(0,response.path("code").asInt(),response.toString());assertEquals("PARSED",response.path("data").path("parseStatus").asText());assertEquals("tender",fresh(d).getReviewRole());
        assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));verifyNoInteractions(externalLlm,oer);
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

    @Test void heldDetailParserDoesNotBlockRunOrDuplicateConfirmationAndStillPublishesItsTerminalResult()throws Exception {
        SourceDocument d=pendingSource("pending-held-parse-barrier.txt");Map<String,Object> body=pendingRequest(d);
        SourceDocument other=pendingSource("pending-other-file-barrier.txt");Map<String,Object> otherBody=pendingRequest(other);String otherBefore=JsonUtils.write(fresh(other));
        byte[] original=Files.readAllBytes(Paths.get(d.getStoragePath()));String text=d.getTextContent(),structure=d.getStructuredContentJson(),archive=d.getNativePdfEvidenceJson();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return invocation.callRealMethod();}).when(parser).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
        doThrow(new AssertionError("An in-flight source must be rejected before semantic capture")).when(semantic).captureOperation(any());
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);java.util.concurrent.Future<JsonNode> first=null;
        try {
            first=pool.submit(()->putPending(d,body));assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<JsonNode> started=pool.submit(()->JsonUtils.parse(mvc.perform(post("/api/vetting/"+project+"/runs").param("lang","en")).andReturn().getResponse().getContentAsString()));
            java.util.concurrent.Future<JsonNode> duplicate=pool.submit(()->putPending(d,body));
            java.util.concurrent.Future<JsonNode> otherConfirmation=pool.submit(()->putPending(other,otherBody));
            JsonNode refused=started.get(2,java.util.concurrent.TimeUnit.SECONDS),second=duplicate.get(2,java.util.concurrent.TimeUnit.SECONDS),otherRefused=otherConfirmation.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,release.getCount(),"All three requests must finish while the original parser remains held");assertEquals(4104,refused.path("code").asInt(),refused.toString());assertTrue(refused.path("message").asText().contains("解析"));assertEquals(4104,second.path("code").asInt(),second.toString());assertEquals(4104,otherRefused.path("code").asInt(),otherRefused.toString());assertTrue(otherRefused.path("message").asText().contains("解析"));assertEquals(otherBefore,JsonUtils.write(fresh(other)));verify(parser,never()).parseOrdinary(eq(other.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
            assertFalse(runs.findFirstByProjectIdOrderByStartedAtDesc(project).isPresent());verifyNoInteractions(externalLlm,oer,semantic);verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));
            SourceDocument parsing=fresh(d);assertEquals("PARSING",parsing.getParseStatus());assertEquals(text,parsing.getTextContent());assertEquals(structure,parsing.getStructuredContentJson());assertEquals(archive,parsing.getNativePdfEvidenceJson());assertArrayEquals(original,Files.readAllBytes(Paths.get(d.getStoragePath())));
            release.countDown();assertEquals(0,first.get(20,java.util.concurrent.TimeUnit.SECONDS).path("code").asInt());assertEquals("PARSED",fresh(d).getParseStatus());assertEquals(otherBefore,JsonUtils.write(fresh(other)));verify(parser,times(1)).parseOrdinary(eq(d.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));verify(parser,never()).parseOrdinary(eq(other.getFileName()),any(byte[].class),isNull(),any(OerDocumentIdentity.class));assertFalse(runs.findFirstByProjectIdOrderByStartedAtDesc(project).isPresent());verifyNoInteractions(externalLlm,oer,semantic);
        } finally {
            release.countDown();if(first!=null)try{first.get(20,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}
            pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void onlyCurrentPackageParsingClaimsBlockANewRunWhileAnExistingRunStillResumes()throws Exception {
        doThrow(new BizException(4991,"Authored semantic boundary reached")).when(semantic).captureOperation(any());
        SourceDocument old=source("parse-state-barrier.txt");
        for(String state:new String[]{"PARSING","IDENTITY_OBSERVED"}){
            old.setParseStatus(state);sources.saveAndFlush(old);BizException blocked=assertThrows(BizException.class,()->service.startRun(project,"en"));assertEquals(4104,blocked.getCode());assertTrue(blocked.getMessage().contains("解析"));verifyNoInteractions(semantic,externalLlm,oer);assertFalse(runs.findFirstByProjectIdOrderByStartedAtDesc(project).isPresent());
        }
        SourceDocument newer=source(" PARSE-STATE-BARRIER.TXT ");
        SourceDocument template=source("non-review-template.txt");template.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);template.setParseStatus("PARSING");sources.saveAndFlush(template);
        Project other=new Project();other.setId("other-barrier-"+UUID.randomUUID());projects.saveAndFlush(other);SourceDocument outside=source("other-project-parsing.txt");outside.setProjectId(other.getId());outside.setParseStatus("PARSING");sources.saveAndFlush(outside);
        BizException reached=assertThrows(BizException.class,()->service.startRun(project,"en"));assertEquals(4991,reached.getCode(),"Superseded, other-category and other-project parsing rows must not block this current package");verify(semantic,times(1)).captureOperation(any());assertFalse(runs.findFirstByProjectIdOrderByStartedAtDesc(project).isPresent());clearInvocations(semantic);
        newer.setParseStatus("PARSING");sources.saveAndFlush(newer);
        for(String state:new String[]{"QUEUED","RUNNING"}){VettingRun active=run(state);assertEquals(active.getId(),service.startRun(project,"en").getId());runs.delete(active);}
        verifyNoInteractions(semantic,externalLlm,parser);
    }
}
