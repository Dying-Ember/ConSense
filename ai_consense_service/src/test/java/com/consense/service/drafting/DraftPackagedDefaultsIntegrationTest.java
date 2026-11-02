package com.consense.service.drafting;
import com.consense.ai.AiGateway;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.web.dto.ProjectDtos.ProjectRequest;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.junit.jupiter.api.condition.EnabledIf("fixturesAvailable")
class DraftPackagedDefaultsIntegrationTest {
    static final Path ROOT;
    static boolean fixturesAvailable() {
        Path words=Paths.get("/mnt/d/R&D/ConSense/Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents");
        Path reference=Paths.get("/mnt/d/R&D/ConSense/.local/scc-pdf-recovery-20261027/reconstructed-workspace/.local/drafting-variable-graph-audit-20261015");
        return Arrays.asList("01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx").stream().allMatch(name->Files.isRegularFile(words.resolve(name)))
                && Files.isRegularFile(Paths.get("/mnt/c/Users/Administrator/Downloads/email from Smart QS Hackathon Team/07_Special Conditions of Contract (SCC).pdf"))
                && Files.isRegularFile(reference.resolve("pdf.json")) && Files.isRegularFile(reference.resolve("atomic-pdf.json"));
    }
    static {try {
        if(!fixturesAvailable()) ROOT=null;
        else {
            ROOT=Files.createTempDirectory("draft-packaged-integration-");Path words=Paths.get("/mnt/d/R&D/ConSense/Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents");
            for(String name:Arrays.asList("01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"))Files.copy(words.resolve(name),ROOT.resolve(name));
            Files.copy(Paths.get("/mnt/c/Users/Administrator/Downloads/email from Smart QS Hackathon Team/07_Special Conditions of Contract (SCC).pdf"),ROOT.resolve("07_Special Conditions of Contract (SCC).pdf"));
        }
    }catch(Exception ex){throw new RuntimeException(ex);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:packaged;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->ROOT.resolve("storage").toString());
        r.add("consense.drafting.packaged-template-dir",()->ROOT.toString());
        r.add("consense.drafting.pdf-reference-dir",()->"/mnt/d/R&D/ConSense/.local/scc-pdf-recovery-20261027/reconstructed-workspace/.local/drafting-variable-graph-audit-20261015");
    }
    @Autowired ProjectService projects; @Autowired DraftingService drafting; @Autowired DraftTemplateGraphService graphs;
    @Autowired com.consense.service.StorageService storage; @Autowired org.springframework.test.web.servlet.MockMvc mvc; @Autowired ProjectRepository projectRepo; @Autowired SourceDocumentRepository sources; @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @MockBean AiGateway ai; @MockBean com.consense.service.export.EnglishFileOutput english;
    @Test void newDefaultIsBoundAndReplacementIsIndependentWhileOldProjectStaysUntouched() throws Exception {
        Project old=new Project();old.setId("existing");old.setNameEn("existing");projectRepo.save(old);
        ProjectRequest request=new ProjectRequest();request.setId("existing");request.setNameZhHans("old");projects.save(request);
        assertTrue(sources.findByProjectIdAndCategoryOrderByIdAsc("existing",SourceDocument.CATEGORY_STANDARD_TEMPLATE).isEmpty());
        request.setId("new");request.setNameZhHans("new");projects.save(request);
        List<SourceDocument> rows=sources.findByProjectIdAndCategoryOrderByIdAsc("new",SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        assertEquals(4,rows.size());
        for(String key:Arrays.asList("NTT","SCT","SCC"))assertTrue(rows.stream().anyMatch(x->key.equals(x.getFileKey())&&"PARSED".equals(x.getParseStatus())),key);
        assertTrue(rows.stream().anyMatch(x->x.getFileKey()==null&&"UNSELECTED_AUXILIARY_CANDIDATE".equals(x.getParseStatus())&&"1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741".equals(x.getSourceSha256())));
        SourceDocument scc=sources.findFirstByProjectIdAndFileKeyAndCategory("new","SCC",SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow();
        assertEquals("9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208",scc.getSourceSha256());
        assertEquals("docx",drafting.templateReading("new","SCC").getFormat());
        assertEquals(scc.getSourceSha256(),drafting.templateReading("new","SCC").getSourceHash());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",drafting.templateSource("new","SCC").getContentType());
        Map<String,Object> initial=DraftReviewGraph.map(graphs.bootstrap("new").get("graph"));
        assertTrue(DraftFixedBaseline.installed(initial));
        assertTrue(DraftReviewGraph.nodes(initial).stream().allMatch(n->"pending".equals(n.get("status"))));
        assertEquals("partial_requires_review",DraftReviewGraph.map(DraftReviewGraph.state(initial).get("baselineRestoration")).get("status"));
        SourceDocument ntt=sources.findFirstByProjectIdAndFileKeyAndCategory("new","NTT",SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow();
        byte[] original=Files.readAllBytes(ROOT.resolve("01_Notes to Tenderers (NTT).docx")),changed=Arrays.copyOf(original,original.length+1);changed[changed.length-1]=1;
        com.consense.web.dto.DraftingDtos.UploadResultVO result=drafting.replaceTemplate("new","NTT",new MockMultipartFile("file","NTT-replacement.docx","application/octet-stream",changed));
        assertEquals(1,result.getParsed(),String.join(";",result.getMessages()));
        SourceDocument replacement=sources.findFirstByProjectIdAndFileKeyAndCategory("new","NTT",SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow();
        assertEquals(DraftTemplateRouting.sha(changed),replacement.getSourceSha256());assertNotEquals(ntt.getSourceSha256(),replacement.getSourceSha256());
        assertFalse(DraftTemplateReadingSources.catalogueSourceVerified("NTT",replacement.getSourceSha256()));
        assertFalse(DraftFixedBaseline.installed(DraftReviewGraph.map(graphs.bootstrap("new").get("graph"))));
        assertTrue(sources.findByProjectIdAndCategoryOrderByIdAsc("existing",SourceDocument.CATEGORY_STANDARD_TEMPLATE).isEmpty());
    }
    @Test void missingDefaultShowsMissingAndUserCanUploadIt() throws Exception {
        Path source=ROOT.resolve("06_Special Conditions of Contract (SCC).docx"),backup=ROOT.resolve("scc-test-backup.docx");
        Files.move(source,backup);
        try {
            ProjectRequest request=new ProjectRequest();request.setId("partial");request.setNameZhHans("partial");projects.save(request);
            com.consense.web.dto.DraftingDtos.TemplateVO before=drafting.listTemplates("partial").stream().filter(t->"SCC".equals(t.getKey())).findFirst().orElseThrow();
            assertEquals("missing",before.getTag());
            assertTrue(sources.findByProjectIdAndCategoryOrderByIdAsc("partial",SourceDocument.CATEGORY_STANDARD_TEMPLATE).stream().anyMatch(d->d.getFileKey()==null&&"UNSELECTED_AUXILIARY_CANDIDATE".equals(d.getParseStatus())));
            byte[] bytes=Files.readAllBytes(backup);
            com.consense.web.dto.DraftingDtos.UploadResultVO uploaded=drafting.replaceTemplate("partial","SCC",new MockMultipartFile("file","SCC.docx","application/octet-stream",bytes));
            assertEquals(1,uploaded.getParsed());
            com.consense.web.dto.DraftingDtos.TemplateVO after=drafting.listTemplates("partial").stream().filter(t->"SCC".equals(t.getKey())).findFirst().orElseThrow();
            assertEquals("ok",after.getTag());assertEquals("docx",drafting.templateReading("partial","SCC").getFormat());
        } finally { Files.move(backup,source); }
    }

    @Test void oneTransactionalSaveFailureDoesNotRollBackOtherTemplates() throws Exception {
        db.execute("ALTER TABLE source_document ADD CONSTRAINT test_reject_sct CHECK (project_id <> 'rollback-check' OR file_key <> 'SCT')");
        try {
            ProjectRequest request=new ProjectRequest();request.setId("rollback-check");request.setNameZhHans("rollback-check");projects.save(request);
            List<SourceDocument> saved=sources.findByProjectIdAndCategoryOrderByIdAsc("rollback-check",SourceDocument.CATEGORY_STANDARD_TEMPLATE);
            assertTrue(saved.stream().anyMatch(x->"NTT".equals(x.getFileKey())&&"PARSED".equals(x.getParseStatus())));
            assertFalse(saved.stream().anyMatch(x->"SCT".equals(x.getFileKey())));
            assertTrue(saved.stream().anyMatch(x->"SCC".equals(x.getFileKey())&&"PARSED".equals(x.getParseStatus())));
            assertTrue(saved.stream().anyMatch(x->x.getFileKey()==null&&"UNSELECTED_AUXILIARY_CANDIDATE".equals(x.getParseStatus())));
            assertEquals("missing",drafting.listTemplates("rollback-check").stream().filter(x->"SCT".equals(x.getKey())).findFirst().orElseThrow().getTag());
        } finally { db.execute("ALTER TABLE source_document DROP CONSTRAINT test_reject_sct"); }
    }

    @Test void unselectedPackagedPdfReferenceIsReadOnlyAndProjectBound() throws Exception {
        ProjectRequest request=new ProjectRequest();request.setId("pdf-reference");request.setNameZhHans("PDF reference");projects.save(request);
        String body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/drafting/pdf-reference/packaged-pdf-scc-reference"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode listing=com.consense.common.JsonUtils.parse(body).path("data");
        assertTrue(listing.path("available").asBoolean());assertFalse(listing.path("executable").asBoolean(true));assertEquals(74,listing.path("total").asInt());
        String point=null;for(com.fasterxml.jackson.databind.JsonNode row:listing.path("candidates"))if(row.path("ruleCount").asInt()>0){point=row.path("pointId").asText();break;}
        assertNotNull(point);
        String detail=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/drafting/pdf-reference/packaged-pdf-scc-reference/{point}",point))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode data=com.consense.common.JsonUtils.parse(detail).path("data");
        assertTrue(data.path("available").asBoolean());assertFalse(data.path("executable").asBoolean(true));assertTrue(data.path("rules").size()>0);
        assertEquals("9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208",sources.findFirstByProjectIdAndFileKeyAndCategory("pdf-reference","SCC",SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow().getSourceSha256());
        SourceDocument auxiliary=sources.findByProjectIdAndCategoryOrderByIdAsc("pdf-reference",SourceDocument.CATEGORY_STANDARD_TEMPLATE).stream().filter(x->x.getFileKey()==null&&"UNSELECTED_AUXILIARY_CANDIDATE".equals(x.getParseStatus())).findFirst().orElseThrow();
        Path stored=storage.resolveStoredPath(auxiliary.getStoragePath());byte[] original=Files.readAllBytes(stored);
        try {
            Files.write(stored,new byte[]{1,2,3});
            String changed=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/drafting/pdf-reference/packaged-pdf-scc-reference"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(com.consense.common.JsonUtils.parse(changed).path("data").path("available").asBoolean(true));
        } finally { Files.write(stored,original); }
        Project old=new Project();old.setId("pdf-unavailable");old.setNameEn("old");projectRepo.save(old);
        String absent=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/drafting/pdf-unavailable/packaged-pdf-scc-reference"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(com.consense.common.JsonUtils.parse(absent).path("data").path("available").asBoolean(true));
    }

}
