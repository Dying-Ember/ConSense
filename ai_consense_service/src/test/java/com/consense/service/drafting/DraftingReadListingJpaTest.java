package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual narrow JPA reads with authored sources; no running database, parser, renderer or model. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class DraftingReadListingJpaTest {
    private static final String PROJECT="authored-draft-list",URL="jdbc:h2:mem:draft_read_list_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    static class CountingJdbc extends org.springframework.jdbc.core.JdbcTemplate {
        int structureReads;Runnable afterStructureRead;
        CountingJdbc(javax.sql.DataSource source){super(source);}
        @Override public <T> T query(String sql,org.springframework.jdbc.core.ResultSetExtractor<T> extractor,Object... args) {
            boolean structure=sql.startsWith("select structured_content_json,source_sha256 from source_document");
            if(structure)structureReads++;
            T result=super.query(sql,extractor,args);
            if(structure&&afterStructureRead!=null){Runnable callback=afterStructureRead;afterStructureRead=null;callback.run();}
            return result;
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->URL);registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired SourceDocumentRepository documents;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired javax.sql.DataSource dataSource;
    @TempDir Path directory;

    @Test void uploadCardsKeepStatusesAndTheSameBoundedPreviewWithoutReadingSourceArchives()throws Exception {
        source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","old.docx","PARSED","old source");
        SourceDocument current=source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","current.docx","PARTIAL","current source");
        source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCT","pending.docx","IDENTITY_PENDING","pending source");
        source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","failed.docx","FAILED","failed source");
        String body="Unicode preview 😀\r\n"+String.join("",Collections.nCopies(500,"正文😀"));
        SourceDocument input=source(PROJECT,SourceDocument.CATEGORY_PROJECT_INPUT,null,"minutes.pdf","PARTIAL",body);input.setContentType("application/pdf");documents.saveAndFlush(input);
        source("another-project",SourceDocument.CATEGORY_PROJECT_INPUT,null,"foreign.txt","PARSED","foreign source");
        source(PROJECT,SourceDocument.CATEGORY_VETTING_PACKAGE,"NTT","vetting-only.docx","PARSED","vetting source");
        ProjectService projects=projects();DraftingService service=new DraftingService(projects,null,null,null,documents,null,null,null,null,null,null,null,null,null,null,null);
        clearFeedback();List<TemplateVO> templates=service.listTemplates(PROJECT);List<EvidenceVO> inputs=service.listInputs(PROJECT);
        assertEquals(3,templates.size());assertEquals(current.getFileName(),templates.get(0).getFileName());assertEquals("partial",templates.get(0).getTag());
        assertEquals("identity_pending",templates.get(1).getTag());assertEquals("failed",templates.get(2).getTag());
        assertEquals(1,inputs.size());EvidenceVO row=inputs.get(0);assertEquals(input.getId(),row.getId());assertEquals("PDF",row.getType().getEn());
        assertEquals(body.substring(0,400)+"\n...[已截断]",row.getBody().getEn());assertEquals(input.getParseMessage(),row.getMessage().getEn());
        assertEquals(input.getPageCount(),row.getPageCount());assertEquals(input.getOcrUsed(),row.getOcrUsed());assertEquals(input.getParseStatus(),row.getStatus());
        List<String> selects=sourceSelects();assertEquals(2,selects.size(),selects.toString());
        for(String sql:selects){assertArchiveFree(sql);assertTrue(sql.contains("substring("),sql);assertFalse(sql.contains("structured_content_json"),sql);assertFalse(sql.contains("parse_coverage_json"),sql);assertFalse(sql.contains("oer_identity_json"),sql);assertTrue(sql.contains("category="),sql);}
        assertNoSourceEntityLoads();
    }

    @Test void graphSourceListingFiltersCategoriesBeforeLoadingAndPreservesExactSourceChecks()throws Exception {
        SourceDocument template=source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","template.txt","PARSED","Template source 😀");
        SourceDocument input=source(PROJECT,SourceDocument.CATEGORY_PROJECT_INPUT,null,"minutes.txt","PARTIAL","Correspondence source 😀");
        source(PROJECT,SourceDocument.CATEGORY_VETTING_PACKAGE,"SCC","vetting-only.txt","PARSING","Vetting data must stay outside this query");
        source(PROJECT,SourceDocument.CATEGORY_VETTING_SUPPLEMENT,"GCT","supplement-only.txt","PARSED","Other module data");
        source("another-project",SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","foreign.txt","PARSED","foreign source");
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());
        DraftReviewSources reader=new DraftReviewSources(new StorageService(props),props,new org.springframework.jdbc.core.JdbcTemplate(dataSource));
        JsonNode expected=reader.sources(Arrays.asList(template,input));
        DraftTemplateGraphService service=new DraftTemplateGraphService(projects(),documents,null,mock(com.consense.repository.DraftTemplateGraphHeadRepository.class),null,null,null,reader,null,null,null,null);
        clearFeedback();JsonNode actual=JsonUtils.mapper().valueToTree(service.sources(PROJECT));assertEquals(expected,actual);
        List<String> selects=sourceSelects();assertEquals(1,selects.size(),selects.toString());String sql=selects.get(0);assertArchiveFree(sql);
        assertTrue(sql.contains("category in ('standard_template'")&&sql.contains("'project_input'"),sql);
        assertTrue(sql.contains("text_content")&&sql.contains("parse_coverage_json")&&sql.contains("storage_path"),"Exact source validation inputs remain: "+sql);assertFalse(sql.contains("structured_content_json"),sql);assertNoSourceEntityLoads();
        assertEquals(DraftAdoption.sourceHash(template),actual.get(0).path("sourceHash").asText());
        Files.write(Paths.get(template.getStoragePath()),"Changed original bytes".getBytes(StandardCharsets.UTF_8));
        clearFeedback();JsonNode changed=JsonUtils.mapper().valueToTree(service.sources(PROJECT));assertEquals("unavailable",changed.get(0).path("evidenceStatus").asText());
        assertEquals("REVIEW_SOURCE_BYTES_CHANGED",changed.get(0).path("issue").asText());assertArchiveFree(sourceSelects().get(0));assertNoSourceEntityLoads();
    }

    @Test void graphReviewReadsOnlyTheRequestedSourceWithoutArchives() throws Exception {
        SourceDocument template=source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","template.pdf","PARTIAL","Saved template text");
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());
        DraftReviewSources reader=new DraftReviewSources(new StorageService(props),props,new org.springframework.jdbc.core.JdbcTemplate(dataSource));
        DraftTemplateGraphService service=new DraftTemplateGraphService(projects(),documents,null,null,null,null,null,reader,null,null,null,null);
        clearFeedback();JsonNode actual=service.reading(PROJECT,template.getId(),true);
        assertEquals(template.getTextContent(),actual.path("text").asText());
        List<String> selects=sourceSelects();assertEquals(1,selects.size(),selects.toString());assertArchiveFree(selects.get(0));assertNoSourceEntityLoads();
    }

    @Test void graphBootstrapReadsOnlyTemplatesWithoutArchives() throws Exception {
        SourceDocument template=source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","template.pdf","PARTIAL","Saved template text");
        source(PROJECT,SourceDocument.CATEGORY_PROJECT_INPUT,null,"minutes.txt","PARSED","Project input must stay outside template query");
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());
        DraftReviewSources reader=new DraftReviewSources(new StorageService(props),props,new org.springframework.jdbc.core.JdbcTemplate(dataSource));
        DraftTemplateGraphService service=new DraftTemplateGraphService(projects(),documents,null,mock(com.consense.repository.DraftTemplateGraphHeadRepository.class),null,null,null,reader,null,null,null,null);
        clearFeedback();Map<String,Object> result=service.bootstrap(PROJECT);
        List<?> sources=(List<?>)result.get("sources");assertEquals(1,sources.size());
        assertEquals(String.valueOf(template.getId()),((Map<?,?>)sources.get(0)).get("id"));
        List<String> selects=sourceSelects();assertEquals(2,selects.size(),selects.toString());for(String sql:selects)assertArchiveFree(sql);assertNoSourceEntityLoads();
    }

    @Test void compactDatabaseReadingChecksTheCompleteBoundStructure() throws Exception {
        SourceDocument template=source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","template.pdf","PARTIAL","Special Conditions of Contract");
        com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("page-1");block.setSource("native_pdf");block.setPageNo(1);block.setText(template.getTextContent());
        com.consense.document.PdfPageEvidence.PdfGlyph glyph=new com.consense.document.PdfPageEvidence.PdfGlyph();glyph.setId("glyph-1");glyph.setUnicode("S");block.setPdfGlyphs(Collections.singletonList(glyph));
        com.consense.document.ParseCoverage coverage=new com.consense.document.ParseCoverage();coverage.setTotalPages(1);
        com.consense.document.DocumentParser.ParsedDocument parsed=new com.consense.document.DocumentParser.ParsedDocument(template.getTextContent(),Collections.emptyList(),1,false,"fixture",Collections.singletonList(block),coverage,template.getSourceSha256(),Collections.emptyList());
        JsonNode evidence=com.consense.document.OerTemplateEvidence.analyse("SCC",parsed);com.fasterxml.jackson.databind.node.ObjectNode saved=JsonUtils.mapper().valueToTree(coverage);saved.set("templateEvidence",evidence);
        template.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));template.setParseCoverageJson(JsonUtils.write(saved));documents.saveAndFlush(template);
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());
        DraftReviewSources reader=new DraftReviewSources(new StorageService(props),props,new org.springframework.jdbc.core.JdbcTemplate(dataSource));
        DraftTemplateGraphService service=new DraftTemplateGraphService(projects(),documents,null,null,null,null,null,reader,null,null,null,null);
        clearFeedback();JsonNode result=service.reading(PROJECT,template.getId(),true);assertEquals("bound",result.path("evidenceStatus").asText());
        assertEquals(template.getTextContent(),result.path("blocks").get(0).path("text").asText());assertFalse(result.path("blocks").get(0).has("pdfGlyphs"));
        assertArchiveFree(sourceSelects().get(0));assertFalse(sourceSelects().get(0).contains("structured_content_json"));assertNoSourceEntityLoads();
        template=documents.findById(template.getId()).orElseThrow();template.setStructuredContentJson(template.getStructuredContentJson().replace("\"unicode\":\"S\"","\"unicode\":\"X\""));documents.saveAndFlush(template);
        clearFeedback();assertEquals("invalid",service.reading(PROJECT,template.getId(),true).path("evidenceStatus").asText());
    }

    @Test void graphCurrentValidatesEachUnchangedStructureOnceAndDetectsChanges() throws Exception {
        SourceDocument template=source(PROJECT,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","template.pdf","PARTIAL","Special Conditions of Contract");
        com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("page-1");block.setSource("native_pdf");block.setPageNo(1);block.setText(template.getTextContent());
        com.consense.document.ParseCoverage coverage=new com.consense.document.ParseCoverage();coverage.setTotalPages(1);
        com.consense.document.DocumentParser.ParsedDocument parsed=new com.consense.document.DocumentParser.ParsedDocument(template.getTextContent(),Collections.emptyList(),1,false,"fixture",Collections.singletonList(block),coverage,template.getSourceSha256(),Collections.emptyList());
        JsonNode evidence=com.consense.document.OerTemplateEvidence.analyse("SCC",parsed);com.fasterxml.jackson.databind.node.ObjectNode saved=JsonUtils.mapper().valueToTree(coverage);saved.set("templateEvidence",evidence);
        template.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));template.setParseCoverageJson(JsonUtils.write(saved));documents.saveAndFlush(template);
        CountingJdbc jdbc=new CountingJdbc(dataSource);writeStamps(jdbc,template);entityManager.clear();
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(directory.toString());
        DraftReviewSources reader=new DraftReviewSources(new StorageService(props),props,jdbc);
        SourceDocument snapshot=documents.findDraftReviewSourceByProjectIdAndId(PROJECT,template.getId()).orElseThrow();
        JsonNode manifest=reader.archive(snapshot);assertEquals("bound",manifest.path("evidenceStatus").asText());
        com.consense.domain.DraftTemplateGraphHead head=new com.consense.domain.DraftTemplateGraphHead();head.setProjectId(PROJECT);head.setCurrentRevisionId("revision");
        com.consense.domain.DraftTemplateGraphRevision revision=new com.consense.domain.DraftTemplateGraphRevision("revision",PROJECT,null,"CONFIRMED","fixture","{}",JsonUtils.write(Collections.singletonList(manifest)),java.time.Instant.now());
        com.consense.repository.DraftTemplateGraphHeadRepository heads=mock(com.consense.repository.DraftTemplateGraphHeadRepository.class);
        com.consense.repository.DraftTemplateGraphRevisionRepository revisions=mock(com.consense.repository.DraftTemplateGraphRevisionRepository.class);
        when(heads.findById(PROJECT)).thenReturn(Optional.of(head));when(revisions.findByIdAndProjectId("revision",PROJECT)).thenReturn(Optional.of(revision));
        DraftTemplateGraphService service=new DraftTemplateGraphService(projects(),documents,revisions,heads,null,null,null,reader,null,null,null,null);
        jdbc.structureReads=0;Map<String,Object> current=service.current(PROJECT);
        assertEquals(1,jdbc.structureReads,"One unchanged source must be read and verified once per request");
        assertEquals(false,((Map<?,?>)current.get("current")).get("sourceStale"));
        assertEquals("bound",((Map<?,?>)((List<?>)current.get("sources")).get(0)).get("evidenceStatus"));
        assertFalse(((Map<?,?>)((List<?>)current.get("sources")).get(0)).containsKey("archiveKey"));
        jdbc.update("update source_document set text_content_sha256=null,structured_content_sha256=null,parse_coverage_sha256=null,normal_candidate_sha256=null,business_content_sha256=null where id=?",template.getId());entityManager.clear();
        jdbc.structureReads=0;current=service.current(PROJECT);assertEquals(2,jdbc.structureReads,"Unstamped sources retain the full checks");
        assertEquals("bound",((Map<?,?>)((List<?>)current.get("sources")).get(0)).get("evidenceStatus"));
        writeStamps(jdbc,template);entityManager.clear();
        String changedStructure=template.getStructuredContentJson().replace("Special Conditions of Contract","Changed source content");
        jdbc.afterStructureRead=()->{template.setStructuredContentJson(changedStructure);jdbc.update("update source_document set structured_content_json=? where id=?",changedStructure,template.getId());writeStamps(jdbc,template);entityManager.clear();};
        jdbc.structureReads=0;current=service.current(PROJECT);assertEquals(2,jdbc.structureReads,"A source changed between reads must be checked again");
        assertEquals("invalid",((Map<?,?>)((List<?>)current.get("sources")).get(0)).get("evidenceStatus"));
        jdbc.structureReads=0;Map<String,Object> changed=service.current(PROJECT);
        assertEquals(true,((Map<?,?>)changed.get("current")).get("sourceStale"));
        assertEquals("invalid",((Map<?,?>)((List<?>)changed.get("sources")).get(0)).get("evidenceStatus"));
    }

    private void writeStamps(CountingJdbc jdbc,SourceDocument source) {
        String text=com.consense.service.sources.SourceContentDigests.field(source.getTextContent()),structure=com.consense.service.sources.SourceContentDigests.field(source.getStructuredContentJson());
        String coverage=com.consense.service.sources.SourceContentDigests.field(source.getParseCoverageJson()),candidate=com.consense.service.sources.SourceContentDigests.field(source.getNormalCandidateJson());
        jdbc.update("update source_document set text_content_sha256=?,structured_content_sha256=?,parse_coverage_sha256=?,normal_candidate_sha256=?,business_content_sha256=? where id=?",text,structure,coverage,candidate,com.consense.service.sources.SourceContentDigests.combined(text,structure,coverage,candidate),source.getId());
    }

    private ProjectService projects(){ProjectService service=mock(ProjectService.class);Project project=new Project();project.setId(PROJECT);when(service.require(PROJECT)).thenReturn(project);return service;}
    private SourceDocument source(String project,String category,String key,String name,String status,String text)throws Exception {
        SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory(category);source.setFileKey(key);source.setFileName(name);source.setParseStatus(status);
        source.setParseMessage("Authored saved processing record");source.setPageCount(7);source.setOcrUsed(true);source.setTextContent(text);source.setContentType("text/plain");
        byte[] original=("Authored original: "+name+" "+project).getBytes(StandardCharsets.UTF_8);Path path=directory.resolve(UUID.randomUUID()+"-"+name);Files.write(path,original);
        source.setSourceSha256(DraftTemplateRouting.sha(original));source.setStoragePath(path.toString());source.setStructuredContentJson("[{\"text\":\"Authored source structure\"}]");source.setParseCoverageJson("{\"complete\":false,\"totalPages\":7}");
        String retained=String.join("",Collections.nCopies(2048,"UNUSED RETAINED GRAPH 😀"));source.setNormalCandidateJson(retained);source.setNativePdfEvidenceJson(retained);return documents.saveAndFlush(source);
    }
    private void clearFeedback(){entityManager.flush();entityManager.clear();SqlCapture.statements.clear();entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();}
    private List<String> sourceSelects(){List<String> found=new ArrayList<>();for(String raw:SqlCapture.statements){String sql=raw.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();if(sql.startsWith("select ")&&sql.contains(" from source_document "))found.add(sql);}return found;}
    private void assertArchiveFree(String sql){assertFalse(sql.contains("normal_candidate_json"),sql);assertFalse(sql.contains("native_pdf_evidence_json"),sql);assertFalse(sql.contains("canonical_source_binding"),sql);}
    private void assertNoSourceEntityLoads(){assertEquals(0,entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount(),"Listing must use detached exact projections, never complete entities");}
}
