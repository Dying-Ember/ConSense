package com.consense.repository;

import com.consense.domain.CanonicalSourceBinding;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the exact new SQL against populated H2 MySQL-mode state, then uses actual JPA. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=none","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.consense.repository.CanonicalSourceBindingPersistenceTest$SqlCapture"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class CanonicalSourceBindingPersistenceTest {
    private static final String URL="jdbc:h2:mem:canonical_migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        try(Connection c=DriverManager.getConnection(URL,"sa","");Statement sql=c.createStatement()){
            sql.execute("CREATE TABLE source_document(id BIGINT PRIMARY KEY,project_id VARCHAR(64),file_name VARCHAR(512))");
            sql.execute("INSERT INTO source_document VALUES(11,'project-a','legacy-preserved.pdf')");
            ResourceDatabasePopulator migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V14__canonical_source_bindings.sql"));
            migration.setSqlScriptEncoding("UTF-8");migration.populate(c);
            ResourceDatabasePopulator familyMigration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V15__canonical_source_family_pins.sql"));familyMigration.setSqlScriptEncoding("UTF-8");familyMigration.populate(c);
            // This old-DDL fixture keeps its null legacy state while exposing the current read-only ORM field.
            sql.execute("ALTER TABLE canonical_source_binding ADD COLUMN snapshot_content_sha256 VARCHAR(64) NULL");
        }catch(Exception error){throw new IllegalStateException("Cannot execute canonical migration",error);}
        registry.add("spring.datasource.url",()->URL);
    }
    @Autowired CanonicalSourceBindingRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.persistence.EntityManager entityManager;
    public static class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final java.util.List<String> statements=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        @Override public String inspect(String sql){statements.add(sql);return sql;}
    }
    @Test void latestSelectionLimitsActualJpaToOneBindingAndLeavesOlderSnapshotsUnloaded() {
        Long newestId=null;
        for(int i=0;i<4;i++){
            CanonicalSourceBinding row=new CanonicalSourceBinding();row.setProjectId("bounded-project");row.setDocumentId(22L);row.setProjectionSha256(String.format(java.util.Locale.ROOT,"%064d",i+1));
            row.setOriginalSourceSha256(sha());row.setJavaParsedSourceHash(sha());row.setRoutingSha256(sha());row.setSourceRevisionSha256(sha());row.setProjectionRevisionSha256(sha());row.setCanonicalSourceHash(sha());row.setScopePolicySha256(sha());
            row.setSnapshotBytesSha256(sha());row.setDeclaredModule("vetting");row.setEligibleModulesJson("[\"vetting\"]");row.setSnapshotJson(i==3?"{\"latestOwn\":true}":"{\"oldUnusedSnapshot\":\""+"x".repeat(16384)+"\"}");newestId=repository.saveAndFlush(row).getId();
        }
        entityManager.clear();org.hibernate.stat.Statistics statistics=entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();statistics.clear();SqlCapture.statements.clear();
        com.consense.service.sources.CanonicalEvidenceService service=new com.consense.service.sources.CanonicalEvidenceService(repository,org.mockito.Mockito.mock(com.consense.service.sources.CanonicalEvidenceBackend.class),org.mockito.Mockito.mock(com.consense.service.sources.CanonicalCurrentSourceReader.class));
        assertNull(service.prepareNormalCandidate("bounded-project",22L,"vetting"));
        java.util.List<String> selects=new java.util.ArrayList<>();for(String sql:SqlCapture.statements)if(sql.toLowerCase(java.util.Locale.ROOT).contains("from canonical_source_binding"))selects.add(sql.toLowerCase(java.util.Locale.ROOT));
        assertEquals(1,selects.size(),selects.toString());assertTrue(selects.get(0).contains(" limit ")||selects.get(0).contains(" fetch first "),"Latest selection must bound actual SQL: "+selects.get(0));
        assertEquals(1,statistics.getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount(),"Unselected historical snapshot LOBs must not be hydrated");
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM canonical_source_binding WHERE project_id='bounded-project' AND document_id=22",Integer.class));
        entityManager.clear();statistics.clear();SqlCapture.statements.clear();
        java.util.List<CanonicalSourceBinding> older=repository.findByProjectIdAndDocumentIdAndIdLessThanOrderByIdDesc("bounded-project",22L,newestId,org.springframework.data.domain.PageRequest.of(0,1));
        assertEquals(1,older.size());assertTrue(older.get(0).getId()<newestId);assertTrue(older.get(0).getSnapshotJson().contains("oldUnusedSnapshot"));
        assertEquals(1,SqlCapture.statements.size());String seek=SqlCapture.statements.get(0).toLowerCase(java.util.Locale.ROOT);
        assertTrue(seek.contains("<")&&(seek.contains(" limit ")||seek.contains(" fetch first ")),seek);assertEquals(1,statistics.getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount());
    }
    @Test void exact_sql_preserves_legacy_unknowns_and_jpa_roundtrips_complete_snapshot(){
        assertEquals("legacy-preserved.pdf",jdbc.queryForObject("SELECT file_name FROM source_document WHERE id=11",String.class));
        assertNull(jdbc.queryForObject("SELECT source_sha256 FROM source_document WHERE id=11",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM canonical_source_binding",Integer.class));
        CanonicalSourceBinding row=new CanonicalSourceBinding();row.setProjectId("project-a");row.setDocumentId(11L);row.setProjectionSha256(sha());
        row.setOriginalSourceSha256(sha());row.setJavaParsedSourceHash(sha());row.setRoutingSha256(sha());row.setSourceRevisionSha256(sha());
        row.setProjectionRevisionSha256(sha());row.setCanonicalSourceHash(sha());row.setScopePolicySha256(sha());row.setAdoptionManifestSha256(sha());
        row.setReviewReferenceSha256(sha());row.setSnapshotBytesSha256(sha());row.setDeclaredModule("vetting");row.setEligibleModulesJson("[\"vetting\",\"advisory\"]");
        String raw="{\"future\":{\"text\":\"中文 😀\",\"unknown\":null},\"float\":1e-07}";row.setSnapshotJson(raw);row.setCreatedAt(Instant.now());
        repository.saveAndFlush(row);entityManager.clear();
        CanonicalSourceBinding loaded=repository.findByProjectIdAndDocumentIdAndProjectionSha256("project-a",11L,sha()).get();
        assertEquals(raw,loaded.getSnapshotJson());assertEquals(row.getId(),loaded.getId());
        assertNull(loaded.getSnapshotContentSha256(),"Legacy fixture must not acquire an inferred trusted content stamp");
        assertEquals(1,repository.findByProjectIdAndDocumentIdOrderByIdDesc("project-a",11L).size());
    }
    @Test void fullScopeHistoriesUseOneBoundedScalarQueryAndRetainForeignRevisions() {
        java.util.List<Long> anchors=new java.util.ArrayList<>();
        for(long document=1;document<=33;document++)for(int revision=0;revision<3;revision++) {
            CanonicalSourceBinding row=new CanonicalSourceBinding();row.setProjectId("batch-history");row.setDocumentId(document);row.setProjectionSha256(String.format(java.util.Locale.ROOT,"%064d",document*10+revision));
            row.setOriginalSourceSha256(sha());row.setJavaParsedSourceHash(sha());row.setRoutingSha256(sha());row.setSourceRevisionSha256(sha());row.setProjectionRevisionSha256(sha());row.setCanonicalSourceHash(sha());row.setScopePolicySha256(sha());
            row.setSnapshotBytesSha256(sha());row.setDeclaredModule(revision==2?"advisory":"vetting");row.setEligibleModulesJson("[\"vetting\",\"advisory\"]");row.setSnapshotJson("{\"unusedBody\":\""+"x".repeat(16384)+"\"}");
            repository.saveAndFlush(row);if(revision==1)anchors.add(row.getId());
        }
        entityManager.clear();org.hibernate.stat.Statistics statistics=entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();statistics.clear();SqlCapture.statements.clear();
        java.util.List<CanonicalSourceBindingRepository.CompactMetadata> rows=repository.findCompactHistoriesForSelected("batch-history",anchors,org.springframework.data.domain.PageRequest.of(0,67));
        assertEquals(66,rows.size());assertEquals(1,SqlCapture.statements.size());String sql=SqlCapture.statements.get(0).toLowerCase(java.util.Locale.ROOT);
        assertFalse(sql.contains("snapshot_json"),sql);assertTrue(sql.contains(" limit ")||sql.contains(" fetch first "),sql);
        assertEquals(0,statistics.getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount());
        for(int i=0;i<33;i++){assertEquals(i+1L,rows.get(2*i).getDocumentId());assertEquals("advisory",rows.get(2*i).getDeclaredModule());assertEquals(anchors.get(i),rows.get(2*i+1).getId());}
        jdbc.update("DELETE FROM canonical_source_binding WHERE id=?",anchors.get(0));entityManager.clear();
        assertEquals(64,repository.findCompactHistoriesForSelected("batch-history",anchors,org.springframework.data.domain.PageRequest.of(0,67)).size(),"Deleting the retained anchor removes its complete history group so the guard can reject it");
    }
    @Test void familyPinsAndNullLegacyReviewPinsRoundTripInActualJpaAfterExactUpgrade()throws Exception{
        CanonicalSourceBinding row=com.consense.service.sources.NativePdfSourceFamilyFixture.binding("authored-family-v1.json");row.setId(null);
        repository.saveAndFlush(row);entityManager.clear();CanonicalSourceBinding loaded=repository.findById(row.getId()).get();
        assertNull(loaded.getAdoptionManifestSha256());assertNull(loaded.getReviewReferenceSha256());
        assertNull(loaded.getSnapshotContentSha256());
        assertEquals(row.getFamilyCompositionSha256(),loaded.getFamilyCompositionSha256());assertEquals(row.getFamilyComponentSetSha256(),loaded.getFamilyComponentSetSha256());
        assertEquals(row.getSnapshotJson(),loaded.getSnapshotJson());
        com.consense.service.sources.CanonicalEvidenceService.validateBindingPins(loaded,com.consense.common.JsonUtils.parse(loaded.getSnapshotJson()));
    }
    private static String sha(){return String.join("",java.util.Collections.nCopies(64,"a"));}
}
