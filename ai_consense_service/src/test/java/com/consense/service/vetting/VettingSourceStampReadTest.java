package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.domain.VettingRun;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.StorageService;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.SourceContentDigestMigration;
import com.consense.service.sources.SourceContentDigests;
import com.consense.web.dto.VettingDtos.CoverageVO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=com.consense.ConSenseApplication.class,properties={"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "consense.llm.enabled=false", "consense.ocr.enabled=false", "consense.vector.provider=memory",
        "consense.oer-evidence.base-url="})
@ActiveProfiles("h2")
class VettingSourceStampReadTest {
    private static final String DB=UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties){
        properties.add("spring.datasource.url",()->"jdbc:h2:mem:source_stamp_"+DB
                +";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
                +";OLD_INFORMATION_SCHEMA=TRUE;NON_KEYWORDS=VALUE");
        properties.add("consense.storage-root",()->Paths.get("target","source-stamp-read",DB).toAbsolutePath().toString());
    }
    @Autowired VettingService service;
    @Autowired ProjectRepository projects;
    @Autowired VettingRunRepository runs;
    @Autowired StorageService storage;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CanonicalCurrentSourceReader currentReader;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean SourceDocumentRepository sources;

    @Test void preparedCandidateRebindsOnlyVerifiedCurrentScalarStamps() throws Exception {
        SourceDocument saved=source();String project=saved.getProjectId();
        SourceDocument captured=sources.findSelectedBusinessSourcesByProjectId(project,"fullset","vetting").get(0);
        String candidate="{\"status\":\"prepared\"}";
        int updated=new TransactionTemplate(transactions).execute(status->sources.compareAndSetNormalCandidate(saved.getId(),project,captured.getSourceSha256(),
                captured.getOerIdentityJson(),captured.getTextContent(),captured.getStructuredContentJson(),
                captured.getParseCoverageJson(),captured.getCategory(),captured.getFileKey(),captured.getReviewRole(),
                captured.getDocumentScope(),captured.getParseStatus(),candidate));
        assertEquals(1,updated);
        captured.setNormalCandidateJson(candidate);
        assertThrows(IllegalArgumentException.class,()->SourceContentDigests.verifyLoaded(captured));

        SourceDocument stamp=currentReader.currentStamp(project,saved.getId(),"vetting");
        clearInvocations(sources);
        SourceContentDigests.rebindVerifiedCurrent(captured,stamp);
        assertDoesNotThrow(()->SourceContentDigests.verifyLoaded(captured));
        assertEquals(stamp.getBusinessContentSha256(),captured.getBusinessContentSha256());
        verify(sources,never()).findSelectedBusinessSourcesByProjectId(anyString(),anyString(),anyString());
        verify(sources,never()).findBusinessSourceMetadataByProjectIdAndIds(anyString(),anyList());
        verify(sources,never()).findNormalObservationMetadataById(anyLong());

        jdbc.update("update source_document set review_role='standard' where id=?",saved.getId());
        SourceDocument changedRole=currentReader.currentStamp(project,saved.getId(),"vetting");
        assertThrows(IllegalArgumentException.class,()->SourceContentDigests.rebindVerifiedCurrent(captured,changedRole));
        jdbc.update("update source_document set review_role='tender' where id=?",saved.getId());
        jdbc.update("update source_document set text_content='Concurrent replacement' where id=?",saved.getId());
        SourceDocument changedContent=currentReader.currentStamp(project,saved.getId(),"vetting");
        assertThrows(IllegalArgumentException.class,()->SourceContentDigests.rebindVerifiedCurrent(captured,changedContent));
    }

    @Test void progressReadChecksStampsAndOriginalBytesWithoutHydratingBodies() throws Exception {
        SourceDocument source=source();String project=source.getProjectId();
        String stamp=VettingService.sourceStampHash(sources.findScopeSourcesByProjectId(project));
        assertNotNull(stamp);
        CoverageVO coverage=new CoverageVO();coverage.setSourceStampSha256(stamp);
        VettingRun run=new VettingRun();run.setId(UUID.randomUUID().toString());run.setProjectId(project);
        run.setStatus("RUNNING");run.setPhase("semantic");run.setStartedAt(Instant.now());
        run.setCoverageJson(JsonUtils.write(coverage));run.setSourceSnapshotSha256(compactHash(sources.findScopeSourcesByProjectId(project)));
        runs.saveAndFlush(run);
        clearInvocations(sources);
        for(int i=0;i<3;i++)assertFalse(service.getRun(project,run.getId()).isSourceSetStale());
        verify(sources,never()).findSelectedBusinessSourcesByProjectId(anyString(),anyString(),anyString());
        verify(sources,never()).findBusinessSourceMetadataByProjectIdAndIds(anyString(),anyList());
        Files.write(Paths.get(source.getStoragePath()),"changed original bytes".getBytes(StandardCharsets.US_ASCII));
        assertTrue(service.getRun(project,run.getId()).isSourceSetStale());
    }

    @Test void workerGuardPreparesMissingDigestWithoutReloadingFullSources() throws Exception {
        SourceDocument source=source();String project=source.getProjectId();
        List<SourceDocument> captured=sources.findSelectedBusinessSourcesByProjectId(project,"fullset","vetting");
        assertEquals(1,captured.size());
        try(Connection connection=dataSource.getConnection();Statement statement=connection.createStatement()){
            statement.execute("drop trigger source_document_content_digests");
            statement.execute("update source_document set text_content_sha256=null,structured_content_sha256=null,"
                    +"parse_coverage_sha256=null,normal_candidate_sha256=null,business_content_sha256=null where id="+source.getId());
            SourceContentDigestMigration.install(connection);
        }
        assertFalse(SourceContentDigests.stamped(sources.findScopeSourcesByProjectId(project).get(0)));
        clearInvocations(sources);
        VettingService target=AopTestUtils.getUltimateTargetObject(service);
        ReflectionTestUtils.invokeMethod(target,"assertMaterialSourcesCurrent",project,captured);
        assertTrue(SourceContentDigests.stamped(sources.findScopeSourcesByProjectId(project).get(0)));
        verify(sources,never()).findSelectedBusinessSourcesByProjectId(anyString(),anyString(),anyString());
        verify(sources,never()).findBusinessSourceMetadataByProjectIdAndIds(anyString(),anyList());
    }

    @Test void complete33SourceCompactGuardNeverHydratesBusinessBodiesAndRejectsDirectCandidateDrift() throws Exception {
        SourceDocument source=source();String project=source.getProjectId();
        for(int i=1;i<33;i++)jdbc.update("insert into source_document(project_id,category,file_name,file_key,review_role,document_scope,source_sha256,storage_path,oer_identity_json,parse_status,ocr_used,text_content,structured_content_json,parse_coverage_json,normal_candidate_json,created_at) "
                +"select project_id,category,?,file_key,review_role,document_scope,source_sha256,storage_path,oer_identity_json,parse_status,ocr_used,text_content,structured_content_json,parse_coverage_json,normal_candidate_json,created_at from source_document where id=?",
                "PRE-authored-complete-"+i+".docx",source.getId());
        List<SourceDocument> captured=sources.findScopeSourcesByProjectId(project);assertEquals(33,captured.size());
        for(SourceDocument metadata:captured){assertNull(metadata.getTextContent());assertNull(metadata.getStructuredContentJson());assertNull(metadata.getParseCoverageJson());assertNull(metadata.getNormalCandidateJson());assertTrue(SourceContentDigests.stamped(metadata));}
        CoverageVO coverage=new CoverageVO();coverage.setSourceStampSha256(VettingService.sourceStampHash(captured));
        VettingRun run=new VettingRun();run.setId(UUID.randomUUID().toString());run.setProjectId(project);run.setStatus("RUNNING");run.setPhase("preparing");run.setStartedAt(Instant.now());
        run.setCoverageJson(JsonUtils.write(coverage));run.setSourceSnapshotSha256(compactHash(captured));runs.saveAndFlush(run);
        clearInvocations(sources);VettingService target=AopTestUtils.getUltimateTargetObject(service);
        for(int i=0;i<3;i++){assertFalse(service.getRun(project,run.getId()).isSourceSetStale());ReflectionTestUtils.invokeMethod(target,"assertMaterialSourcesCurrent",project,captured);}
        jdbc.update("update source_document set normal_candidate_json='DIRECT CONCURRENT CANDIDATE' where id=?",source.getId());
        assertTrue(service.getRun(project,run.getId()).isSourceSetStale());assertThrows(RuntimeException.class,()->ReflectionTestUtils.invokeMethod(target,"assertMaterialSourcesCurrent",project,captured));
        verify(sources,never()).findSelectedBusinessSourcesByProjectId(anyString(),anyString(),anyString());verify(sources,never()).findBusinessSourceMetadataByProjectIdAndIds(anyString(),anyList());verify(sources,never()).findNormalObservationMetadataById(anyLong());
    }

    @Test void sourceStampCasAcceptsOnlyOneExactOwnedCandidateAndRejectsConcurrentRole() throws Exception {
        SourceDocument saved=source();String project=saved.getProjectId();SourceDocument before=sources.findScopeSourcesByProjectId(project).get(0);
        String candidate="{\"authoredOwnedCandidate\":true}";
        assertEquals(1,stampCas(before,candidate));assertEquals(0,stampCas(before,"stale overwrite"));
        SourceDocument after=sources.findScopeSourcesByProjectId(project).get(0);assertEquals(SourceContentDigests.field(candidate),after.getNormalCandidateSha256());
        assertEquals(before.getTextContentSha256(),after.getTextContentSha256());assertEquals(before.getStructuredContentSha256(),after.getStructuredContentSha256());assertEquals(before.getParseCoverageSha256(),after.getParseCoverageSha256());
        jdbc.update("update source_document set review_role='standard' where id=?",saved.getId());assertEquals(0,stampCas(after,"unauthorised role overwrite"));
    }

    @Test void compactSourcePinsRetainOnlyHashesProvenFromActualContent() throws Exception {
        SourceDocument saved=source();SourceDocument full=currentReader.normalDocument(saved.getProjectId(),saved.getId(),"vetting");
        SourceDocument compact=CanonicalCurrentSourceReader.compactVerifiedSource(full);
        assertNull(compact.getTextContent());assertNull(compact.getStructuredContentJson());assertNull(compact.getParseCoverageJson());assertNull(compact.getNormalCandidateJson());
        assertEquals(VettingCorpus.sourceHash(full),VettingCorpus.sourceHash(compact));assertEquals(SourceContentDigests.actual(full),SourceContentDigests.actual(compact));
        SourceDocument current=currentReader.currentStamp(saved.getProjectId(),saved.getId(),"vetting");assertDoesNotThrow(()->SourceContentDigests.requireCurrent(compact,current));
        compact.setTextContent("Changed after the actual proof");assertThrows(IllegalArgumentException.class,()->SourceContentDigests.requireCurrent(compact,current));
    }
    private int stampCas(SourceDocument before,String candidate){return new TransactionTemplate(transactions).execute(status->sources.compareAndSetNormalCandidateWithStamp(before.getId(),before.getProjectId(),before.getBusinessContentSha256(),before.getSourceSha256(),before.getOerIdentityJson(),before.getCategory(),before.getFileKey(),before.getReviewRole(),before.getDocumentScope(),before.getParseStatus(),candidate));}
    private static String compactHash(List<SourceDocument> sources)throws Exception{java.lang.reflect.Method hash=VettingService.class.getDeclaredMethod("compactSourceSetHash",List.class);hash.setAccessible(true);return(String)hash.invoke(null,sources);}

    private SourceDocument source() throws Exception {
        String project="stamp-"+UUID.randomUUID();Project p=new Project();p.setId(project);p.setNameEn("Source stamp review");projects.saveAndFlush(p);
        byte[] original="ORIGINAL FULL PRE".getBytes(StandardCharsets.US_ASCII);
        StorageService.StoredFile stored=storage.store(project,SourceDocument.CATEGORY_VETTING_PACKAGE,
                new MockMultipartFile("files","PRE.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",original));
        SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileName("PRE.docx");d.setFileKey("PRE");d.setReviewRole("tender");d.setDocumentScope("package");d.setParseStatus("PARTIAL");
        d.setStoragePath(stored.getPath());d.setSourceSha256(OerSourceMetadata.originalSha256(original));
        d.setTextContent("Technical clause text. ".repeat(100000));d.setStructuredContentJson("{\"body\":\""+"A".repeat(1000000)+"\"}");
        d.setParseCoverageJson("{\"status\":\"partial\"}");d.setNormalCandidateJson("{\"status\":\"candidate_unverified\"}");
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());
        identity.setIdentityStatus("format_supported_non_drawing");identity.setEligibleModules(List.of("vetting","advisory"));
        d.setOerIdentityJson(JsonUtils.write(identity));
        return sources.saveAndFlush(d);
    }
}
