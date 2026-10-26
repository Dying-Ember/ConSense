package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser;
import com.consense.document.OerDocumentIdentity;
import com.consense.document.ParseCoverage;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.ProjectDocumentScope;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Actual file-list MVC/JPA seam: archive hydration must fail the SQL check, even on a small fixture. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
    "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
    "consense.oer-evidence.base-url=","spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingFileCatalogJpaHttpTest {
    static final String DB=UUID.randomUUID().toString().replace("-","");
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:file_catalog_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","file-catalog-uploads",DB).toAbsolutePath().toString());
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired EntityManagerFactory emf;
    @Autowired org.springframework.jdbc.core.JdbcTemplate fixtureSql;
    @MockBean AiGateway externalLlm;
    @SpyBean DocumentParser actualParser;
    @SpyBean OerBusinessEvidence actualOer;
    String project;
    @BeforeEach void setup(){
        project="files-"+UUID.randomUUID();Project p=new Project();p.setId(project);p.setNameEn("Authored source file catalogue");projects.saveAndFlush(p);
    }
    SourceDocument source(String name,String key,String text){return source(name,key,"tender","package",text);}
    SourceDocument source(String name,String key,String role,String documentScope,String text){
        SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileName(name);d.setFileKey(key);d.setReviewRole(role);d.setDocumentScope(documentScope);d.setParseStatus("PARSED");d.setTextContent(text);
        String digest=OerSourceMetadata.originalSha256(text==null?new byte[0]:text.getBytes(java.nio.charset.StandardCharsets.UTF_8));d.setSourceSha256(digest);
        if(role!=null){OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(digest);identity.setKind(key);
            identity.setRole(role);identity.setSourcePurpose("standard".equals(role)?"reference_standard":"project_document");identity.setIdentityStatus("authored_test_fixture");
            identity.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(identity));}
        d.setNativePdfEvidenceJson("{BROKEN "+String.join("",Collections.nCopies(32768,"unused-native-archive")));
        d.setStructuredContentJson("{BROKEN STRUCTURE IS NOT A FILE LIST INPUT}");
        documents.saveAndFlush(d);return d;
    }
    void clear(){SqlCapture.statements.clear();emf.unwrap(SessionFactory.class).getStatistics().clear();org.mockito.Mockito.clearInvocations(externalLlm,actualParser,actualOer);}
    JsonNode response()throws Exception{org.springframework.mock.web.MockHttpServletResponse r=mvc.perform(get("/api/vetting/"+project+"/files")).andReturn().getResponse();assertEquals(200,r.getStatus());return JsonUtils.parse(r.getContentAsString());}
    JsonNode data()throws Exception{JsonNode r=response();assertEquals(0,r.path("code").asInt(),r.toString());return r.path("data");}
    void assertNoArchiveHydration(){
        List<String> sql=new ArrayList<>();for(String raw:SqlCapture.statements){String s=raw.toLowerCase(Locale.ROOT);if(s.startsWith("select ")&&s.contains(" from source_document "))sql.add(s);}
        assertFalse(sql.isEmpty());for(String statement:sql)assertFalse(statement.contains("native_pdf_evidence_json"),"file list hydrated native archive: "+statement);
        String metadata=sql.get(0);
        assertFalse(java.util.regex.Pattern.compile("\\.(?:text_content|structured_content_json|parse_coverage_json|normal_candidate_json)\\s+as\\s")
                .matcher(metadata).find(),"file list projected a complete source payload: "+metadata);
        for(int i=1;i<sql.size();i++)assertTrue(sql.get(i).matches("(?s).*\\bwhere\\s+\\w+\\.id=\\?.*"),"legacy verification must read one bounded source: "+sql.get(i));
        assertEquals(0,emf.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount());
        org.mockito.Mockito.verifyNoInteractions(externalLlm,actualParser,actualOer);
        System.out.println("FILE_LIST_EXACT_SQL "+sql.get(0));
    }
    @Test void realFileListingDoesNotHydrateUnusedNativeArchiveOrFullStructure()throws Exception{
        source("10 - SCC.docx","SCC","tender","package","Contractual clause with \uD83D\uDE00 and an exception.");clear();JsonNode d=data();
        assertEquals(1,d.size());assertEquals("Contractual clause with \uD83D\uDE00 and an exception.".length(),d.get(0).path("textChars").asInt());
        assertTrue(d.get(0).path("parsed").asBoolean());assertNoArchiveHydration();
    }
    @Test void latestFailedUploadStillSupersedesOldSuccessfulParseAndPreservesFileOrder()throws Exception{
        source("10 - SCC.docx","SCC","tender","package","Old source must not become current again.");
        SourceDocument failed=source(" 10 - scc.DOCX ","SCC","tender","package",null);failed.setParseStatus("FAILED");failed.setParseMessage("Authored failed parse");documents.saveAndFlush(failed);
        source("03 - NTT.docx","NTT","tender","package","Current tender text.");clear();JsonNode d=data();assertEquals(2,d.size());
        assertEquals("NTT",d.get(0).path("key").asText());assertEquals("FAILED",d.get(1).path("parseStatus").asText());
        assertFalse(d.get(1).path("parsed").asBoolean());assertEquals(0,d.get(1).path("textChars").asInt());assertEquals("Authored failed parse",d.get(1).path("basis").asText());
        assertEquals(String.valueOf(failed.getId()),d.get(1).path("documentId").asText());
        assertEquals(failed.getSourceSha256(),d.get(1).path("originalSourceSha256").asText());
        assertEquals(VettingCorpus.sourceHash(failed),d.get(1).path("sourceHash").asText());
        assertEquals(CanonicalEvidenceService.routingHash(failed),d.get(1).path("routingSha256").asText());
        assertNoArchiveHydration();
    }
    @Test void fileListKeepsExistingRoutingExclusionsAndExplicitRoles()throws Exception{
        source("10 - SCC.docx","SCC","tender","package","Current project SCC.");SourceDocument g=source("GCC.pdf","GCC","standard","package","Current GCC standard.");fixtureSql.update("update source_document set review_role=? where id=?","project_fact",g.getId());
        source("Bills of Quantities.pdf","BQ","tender","pricing","Excluded pricing.");source("Project PSS - Concrete works.pdf","WORKS_PSS","tender","works_pss","Excluded technical PSS.");source("Q&A.docx","OTHER","tender","example","Excluded answer.");
        SourceDocument t=source("NTT template.docx","NTT","standard","package","Excluded standard template.");t.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);documents.saveAndFlush(t);
        SourceDocument a=source("advisory-only.docx","OTHER","tender","package","Outside vetting module.");OerDocumentIdentity onlyAdvice=OerSourceMetadata.identity(a);onlyAdvice.setEligibleModules(Collections.singletonList("advisory"));a.setOerIdentityJson(JsonUtils.write(onlyAdvice));documents.saveAndFlush(a);
        clear();JsonNode d=data();assertEquals(6,d.size());
        Map<String,JsonNode> rows=new HashMap<>();for(JsonNode row:d)rows.put(row.path("fileName").asText(),row);
        assertTrue(rows.get("10 - SCC.docx").path("parsed").asBoolean());assertTrue(rows.get("GCC.pdf").path("parsed").asBoolean());
        assertEquals("project_fact",rows.get("GCC.pdf").path("sourceRole").asText());
        for(String name:new String[]{"Bills of Quantities.pdf","Project PSS - Concrete works.pdf","Q&A.docx","advisory-only.docx"}){
            assertNotNull(rows.get(name));assertFalse(rows.get(name).path("parsed").asBoolean(),name);
        }
        assertNoArchiveHydration();
    }
    @Test void confirmedPendingAndExcludedOriginalsStayVisibleButOnlyConfirmedIsSelected()throws Exception{
        String text="AUTHORED TEST ONLY same source text.";
        SourceDocument confirmed=source("confirmed.txt","SCC","tender","package",text);
        SourceDocument pending=source("SCC checklist PSS.txt","SCC",null,"package",text);
        SourceDocument excluded=source("reference-answer.txt","OTHER","tender","example",text);
        clear();JsonNode listed=data();assertEquals(3,listed.size());
        Map<String,JsonNode> rows=new HashMap<>();for(JsonNode row:listed)rows.put(row.path("fileName").asText(),row);
        assertTrue(rows.get(confirmed.getFileName()).path("parsed").asBoolean());
        JsonNode waiting=rows.get(pending.getFileName());assertNotNull(waiting);assertEquals("unknown",waiting.path("sourceRole").asText());
        assertEquals("PARSED",waiting.path("parseStatus").asText());assertFalse(waiting.path("parsed").asBoolean());assertEquals(text.length(),waiting.path("textChars").asInt());
        assertEquals("Identity pending confirmation",waiting.path("role").path("en").asText());
        assertTrue(waiting.path("warnings").toString().contains("身份待确认"));assertFalse(rows.get(excluded.getFileName()).path("parsed").asBoolean());
        assertEquals(String.valueOf(pending.getId()),waiting.path("documentId").asText());assertTrue(waiting.path("roleConfirmationAvailable").asBoolean());
        assertEquals(64,waiting.path("sourceHash").asText().length());assertNoArchiveHydration();
        List<SourceDocument> selected=ProjectDocumentScope.selected(project,documents.findBusinessSourcesByProjectId(project),"fullset");
        assertEquals(1,selected.size());assertEquals(confirmed.getId(),selected.get(0).getId());
        assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(pending,"fullset"));
        assertEquals("example_answer",ProjectDocumentScope.exclusionReason(excluded,"fullset"));
        assertEquals(text,documents.findById(pending.getId()).orElseThrow(AssertionError::new).getTextContent());
        assertEquals(OerSourceMetadata.originalSha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),pending.getSourceSha256());
    }
    @Test void parseWarningsOcrAndExactWhitespaceSemanticsRemainVisible()throws Exception{
        SourceDocument d=source("10 - SCC.docx","SCC","tender","package","\t\r\n  ");d.setParseStatus("PARTIAL");d.setPageCount(9);d.setOcrUsed(true);
        ParseCoverage c=new ParseCoverage();c.setComplete(false);c.setFailedPages(Arrays.asList(2,7));c.setLimitations(Collections.singletonList("Authored limitation"));d.setParseCoverageJson(JsonUtils.write(c));documents.saveAndFlush(d);
        clear();JsonNode row=data().get(0);assertEquals(5,row.path("textChars").asInt());assertFalse(row.path("parsed").asBoolean());assertEquals(9,row.path("pageCount").asInt());assertTrue(row.path("ocrUsed").asBoolean());
        assertTrue(row.path("warnings").toString().contains("Authored limitation"));assertTrue(row.path("warnings").toString().contains("[2, 7]"));assertTrue(row.path("warnings").toString().contains("解析不完整"));assertTrue(row.path("warnings").toString().contains("OCR"));assertNoArchiveHydration();
    }
    @Test void invalidIdentityStillFailsClosedInsteadOfListingAsEligible()throws Exception{
        SourceDocument d=source("10 - SCC.docx","SCC","tender","package","Current text.");d.setSourceSha256("actual-source");d.setOerIdentityJson("{\"sourceSha256\":\"different-source\",\"eligibleModules\":[\"vetting\"]}");documents.saveAndFlush(d);
        clear();org.springframework.test.web.servlet.MvcResult result=mvc.perform(get("/api/vetting/"+project+"/files")).andReturn();
        assertEquals(200,result.getResponse().getStatus());assertNotNull(result.getResolvedException());
        assertEquals(IllegalArgumentException.class,result.getResolvedException().getClass());
        assertEquals("Persisted OER identity does not match its uploaded source",result.getResolvedException().getMessage());
        JsonNode r=JsonUtils.parse(result.getResponse().getContentAsString());assertEquals(5000,r.path("code").asInt());
        assertEquals("服务内部错误，请稍后重试；若仍失败，请联系维护人员。",r.path("message").asText());
        assertTrue(r.path("data").isNull()||r.path("data").isMissingNode(),"Invalid identity must never publish an eligible file list");assertNoArchiveHydration();
    }
    @Test void foreignProjectAndAbsentProjectCannotReadThisSourceCatalogue()throws Exception{
        source("10 - SCC.docx","SCC","tender","package","Project isolated source.");String original=project;project="files-"+UUID.randomUUID();Project p=new Project();p.setId(project);projects.saveAndFlush(p);clear();assertEquals(0,data().size());assertNoArchiveHydration();
        project="absent-"+original;clear();assertEquals(4004,response().path("code").asInt());assertTrue(SqlCapture.statements.stream().noneMatch(s->s.contains(" from source_document ")));
    }
}
