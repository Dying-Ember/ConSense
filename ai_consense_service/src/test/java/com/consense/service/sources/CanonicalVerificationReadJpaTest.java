package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.*;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual SQL/JPA/fresh reader and authored local HTTP registration; no live DB/model/source-quality claim. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({CanonicalEvidenceService.class,CanonicalCurrentSourceReader.class,NormalObservedPreparationService.class,SourceContentDigestPreparation.class,CanonicalVerificationReadJpaTest.Config.class})
public class CanonicalVerificationReadJpaTest {
    static final String PROJECT="project-a",URL="jdbc:h2:mem:verify_read_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    static final Set<String> VERIFY_COLUMNS=new LinkedHashSet<>(Arrays.asList("id","project_id","category","file_key","file_name","review_role","document_scope","parse_status","oer_identity_json","source_sha256","storage_path","text_content","structured_content_json"));
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @TestConfiguration static class Config {
        @Bean CanonicalEvidenceProperties canonicalTestProperties(){return new CanonicalEvidenceProperties();}
        @Bean CanonicalEvidenceBackend canonicalTestBackend(CanonicalEvidenceProperties p){return new CanonicalEvidenceBackend(p);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->URL);r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired CanonicalCurrentSourceReader reader;
    @Autowired CanonicalEvidenceService service;
    @Autowired SourceDocumentRepository documents;
    @Autowired CanonicalSourceBindingRepository bindings;
    @Autowired ConsenseProperties properties;
    @Autowired CanonicalEvidenceProperties canonicalProperties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired SourceContentDigestPreparation digestPreparation;
    @TempDir Path directory;
    private HttpServer server;
    private CanonicalSourceBinding binding;
    private SourceDocument originalDocument;
    private String nativeJson,raw;
    private JsonNode fixture;

    @BeforeEach void prepare() throws Exception {
        jdbc.execute("drop trigger if exists source_document_content_digests");
        jdbc.execute("drop trigger if exists canonical_source_binding_content_digests");
        raw=new String(resource("authored-three-page-projection.json"),StandardCharsets.UTF_8);fixture=JsonUtils.parse(raw);
        Path original=directory.resolve("original.pdf");Files.write(original,resource("authored-source.pdf"));properties.setStorageRoot(directory.toString());
        nativeJson="{\"authoredUnusedNativePayload\":\""+repeatText("NATIVE 😀",65536)+"\"}";
        committed(()->{
            jdbc.update("DELETE FROM canonical_source_binding");jdbc.update("DELETE FROM source_document");entityManager.clear();jdbc.execute("ALTER TABLE source_document ALTER COLUMN id RESTART WITH 11");
            SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("PRE");d.setFileName("authored-project-PRE.pdf");
            d.setReviewRole("tender");d.setDocumentScope("package");d.setParseStatus("PARTIAL");d.setStoragePath(original.toString());d.setContentType("application/pdf");d.setSizeBytes(999L);
            d.setSourceSha256(CanonicalJson.sha(read(original)));d.setTextContent("AUTHORED current text 😀\r\n");
            d.setStructuredContentJson("[{\"paragraphStyleName\":\"Heading 1\",\"text\":\"AUTHORED 😀\",\"unknown\":null}]");d.setNativePdfEvidenceJson(nativeJson);d.setParseCoverageJson("{\"partial\":true}");
            OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());identity.setKind("PRE");identity.setSourcePurpose("project_document");identity.setRole("tender");
            identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("drafting","vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(identity));originalDocument=documents.saveAndFlush(d);
            binding=new CanonicalSourceBinding();binding.setProjectId(PROJECT);binding.setDocumentId(11L);binding.setOriginalSourceSha256(d.getSourceSha256());binding.setJavaParsedSourceHash(VettingCorpus.sourceHash(d));binding.setRoutingSha256(CanonicalEvidenceService.routingHash(d));
            binding.setProjectionSha256(repeat('a'));binding.setSourceRevisionSha256(repeat('a'));binding.setProjectionRevisionSha256(repeat('a'));binding.setCanonicalSourceHash(repeat('a'));binding.setScopePolicySha256(repeat('a'));
            binding.setAdoptionManifestSha256(repeat('a'));binding.setReviewReferenceSha256(repeat('a'));binding.setSnapshotJson("{\"authored\":true}");binding.setSnapshotBytesSha256(CanonicalJson.sha(binding.getSnapshotJson().getBytes(StandardCharsets.UTF_8)));
            binding.setDeclaredModule("advisory");binding.setEligibleModulesJson("[\"drafting\",\"vetting\",\"advisory\"]");bindings.saveAndFlush(binding);
        });
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();String result=path.equals("/health")?"{\"oer\":{\"inputContract\":\"canonical-source-chunks-upstream-scope-and-parser-projection-v1\",\"assetReturnContract\":\"source-and-projection-bound-content-addressed-assets-v1\",\"canonicalProjectionEndpoint\":\"/project\",\"scopePolicy\":{\"manifestSha256\":\""+repeat('d')+"\"}}}":raw;
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}});
        server.start();canonicalProperties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());canonicalProperties.setTimeoutMs(30000);clearFeedback();
    }
    @AfterEach void stop(){
        jdbc.execute("SET QUERY_STATISTICS FALSE");
        jdbc.execute("drop trigger if exists source_document_content_digests");
        jdbc.execute("drop trigger if exists canonical_source_binding_content_digests");
        if(server!=null)server.stop(0);
    }

    @Test void assert_source_current_sql_reads_exact_verification_fields_without_native_payload_or_source_entity(){
        reader.assertSourceCurrent(binding,"advisory");assertVerificationSql("assertSourceCurrent");
    }
    @Test void assert_current_sql_keeps_adoption_check_without_loading_native_payload(){
        reader.assertCurrent(binding,"advisory");assertVerificationSql("assertCurrent");assertTrue(SqlCapture.statements.stream().anyMatch(sql->sql.contains("canonical_source_binding")));
    }
    @Test void frame_rechecks_binding_without_hydrating_snapshot_and_rejects_unstamped_direct_sql_change()throws Exception {
        binding=bindings.findById(binding.getId()).orElseThrow();
        CanonicalEvidenceService target=org.springframework.test.util.AopTestUtils.getTargetObject(service);
        Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");
        java.lang.reflect.Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
        java.lang.reflect.Constructor<CanonicalEvidenceService.VerifiedBindingFrame> constructor=CanonicalEvidenceService.VerifiedBindingFrame.class
                .getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);constructor.setAccessible(true);
        CanonicalEvidenceService.VerifiedBindingFrame frame=constructor.newInstance(target,service,PROJECT,"advisory",
                Collections.singletonList(view.newInstance(binding,JsonUtils.parse(binding.getSnapshotJson()),false)));
        jdbc.execute("SET QUERY_STATISTICS TRUE");long beforeClobReads=snapshotClobReads();
        clearFeedback();frame.beforeWait();frame.revalidate();
        assertTrue(snapshotClobReads()>beforeClobReads,"An unstamped legacy binding still requires the actual CLOB read");
        List<String> bindingReads=new ArrayList<>();for(String sql:SqlCapture.statements)if(sql.toLowerCase(Locale.ROOT).contains(" from canonical_source_binding "))bindingReads.add(sql.toLowerCase(Locale.ROOT));
        assertFalse(bindingReads.isEmpty());for(String sql:bindingReads)assertFalse(sql.substring(0,sql.indexOf(" from ")).contains("snapshot_json"),sql);
        committed(()->jdbc.update("update canonical_source_binding set snapshot_json=? where id=?","{\"authored\":\"changed outside JPA\"}",binding.getId()));
        assertNull(jdbc.queryForObject("select snapshot_content_sha256 from canonical_source_binding where id=?",String.class,binding.getId()));
        assertEquals("CANONICAL_FRAME_BINDING_CHANGED",assertThrows(CanonicalEvidenceException.class,frame::beforeWait).getMessage());
        assertEquals("CANONICAL_FRAME_BINDING_CHANGED",assertThrows(CanonicalEvidenceException.class,frame::revalidate).getMessage());
    }
    @Test void stampedFrameAvoidsActualSnapshotClobReadsAndRejectsDirectSqlMutation()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        service.prepareCompactIdentities(frame,documents.findBusinessSourcesByProjectId(PROJECT));
        String originalDigest=binding.getSnapshotBytesSha256();
        assertEquals(originalDigest,jdbc.queryForObject("select snapshot_content_sha256 from canonical_source_binding where id=?",String.class,binding.getId()));
        jdbc.execute("SET QUERY_STATISTICS TRUE");long beforeClobReads=snapshotClobReads();
        for(int i=0;i<3;i++){frame.beforeWait();frame.revalidate();}
        assertEquals(beforeClobReads,snapshotClobReads(),"Stamped frame checks must execute no snapshot_json CLOB SELECT");
        String replacement="{\"authored\":\"changed directly after preparation\"}";
        committed(()->jdbc.update("update canonical_source_binding set snapshot_json=?,snapshot_content_sha256=? where id=?",replacement,originalDigest,binding.getId()));
        assertEquals(com.consense.common.Utf8.sha256(replacement),jdbc.queryForObject("select snapshot_content_sha256 from canonical_source_binding where id=?",String.class,binding.getId()),"The actual write trigger replaces a caller-supplied stale digest");
        assertEquals("CANONICAL_FRAME_BINDING_CHANGED",assertThrows(CanonicalEvidenceException.class,frame::beforeWait).getMessage());
        assertEquals("CANONICAL_FRAME_BINDING_CHANGED",assertThrows(CanonicalEvidenceException.class,frame::close).getMessage());
        assertThrows(CanonicalEvidenceException.class,frame::bindings);
        assertEquals(beforeClobReads,snapshotClobReads(),"Changed stamp rejects from fresh scalar state without rereading the CLOB");
        System.out.println("STAMPED_FRAME_CLOB_READS repeatedGuards=6 snapshotClobReads=0 directSqlChange=rejected");
    }
    @Test void currentBindingGuardRejectsScalarRevisionChangesAcrossAWait(){
        CanonicalEvidenceService target=org.springframework.test.util.AopTestUtils.getTargetObject(service);
        TransactionTemplate tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.execute(status->{
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"assertCurrent",binding,"advisory",null);
            for(String column:Arrays.asList("source_revision_sha256","projection_revision_sha256","canonical_source_hash")){
                committed(()->jdbc.update("update canonical_source_binding set "+column+"=? where id=?",repeat('b'),binding.getId()));
                CanonicalEvidenceException changed=assertThrows(CanonicalEvidenceException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"assertCurrent",binding,"advisory",null),column);
                assertEquals("CANONICAL_ADOPTION_SUPERSEDED",changed.getMessage(),column);
                committed(()->jdbc.update("update canonical_source_binding set "+column+"=? where id=?",repeat('a'),binding.getId()));
            }
            return null;
        });
    }
    @Test void compactPreparationDoesNotRehydrateTheAlreadyOwnedStampedBinding()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        List<SourceDocument> captured=documents.findBusinessSourcesByProjectId(PROJECT);
        service.prepareCompactIdentities(frame,captured);
        clearFeedback();
        service.prepareCompactIdentities(frame,captured);
        assertNoPaginatedSnapshotHydration();
        assertEquals(0,bindingLoads(),"The owned selected snapshot must not be hydrated into another entity");
        frame.close();
    }
    @Test void compactPreparationSkipsStampedForeignHistoryWithoutDuplicatePaginatedHydration()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        List<SourceDocument> captured=documents.findBusinessSourcesByProjectId(PROJECT);
        service.prepareCompactIdentities(frame,captured);
        CanonicalSourceBinding foreign=addForeignNormalHistory();
        clearFeedback();
        service.prepareCompactIdentities(frame,captured);
        assertNoPaginatedSnapshotHydration();
        assertEquals(2,bindingLoads(),"The two unchanged final authority checks each validate and detach the foreign revision");
        assertEquals(foreign.getSnapshotBytesSha256(),jdbc.queryForObject(
                "select snapshot_content_sha256 from canonical_source_binding where id=?",String.class,foreign.getId()));
        frame.close();
    }
    @Test void compactPreparationRetainsTheMissingHistoricalDigestFallback()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        List<SourceDocument> captured=documents.findBusinessSourcesByProjectId(PROJECT);
        service.prepareCompactIdentities(frame,captured);
        CanonicalSourceBinding foreign=addForeignNormalHistory();
        replaceHistoryStamp(foreign.getId(),null);
        clearFeedback();
        service.prepareCompactIdentities(frame,captured);
        assertNoPaginatedSnapshotHydration();
        assertTrue(bindingLoads()>0,"An unstamped historical row still requires full byte and typed-pin validation");
        assertEquals(foreign.getSnapshotBytesSha256(),jdbc.queryForObject(
                "select snapshot_content_sha256 from canonical_source_binding where id=?",String.class,foreign.getId()));
        assertEquals(foreign.getSnapshotJson(),jdbc.queryForObject(
                "select snapshot_json from canonical_source_binding where id=?",String.class,foreign.getId()));
        frame.close();
    }
    @Test void compactPreparationRejectsAnInvalidStampedHistoryWithoutRepairingIt()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        List<SourceDocument> captured=documents.findBusinessSourcesByProjectId(PROJECT);
        service.prepareCompactIdentities(frame,captured);
        CanonicalSourceBinding foreign=addForeignNormalHistory();
        replaceHistoryStamp(foreign.getId(),repeat('0'));
        clearFeedback();
        assertThrows(CanonicalEvidenceException.class,()->service.prepareCompactIdentities(frame,captured));
        assertEquals(repeat('0'),jdbc.queryForObject(
                "select snapshot_content_sha256 from canonical_source_binding where id=?",String.class,foreign.getId()));
    }
    private CanonicalSourceBinding addForeignNormalHistory(){
        CanonicalSourceBinding foreign=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(binding,foreign);
        foreign.setId(null);foreign.setProjectionSha256(repeat('b'));foreign.setDeclaredModule("vetting");
        foreign.setAdoptionManifestSha256(null);foreign.setReviewReferenceSha256(null);
        com.fasterxml.jackson.databind.node.ObjectNode normal=JsonUtils.mapper().createObjectNode();
        normal.put("schema",NormalObservedContract.SCHEMA);normal.put("producerKind",NormalObservedContract.PRODUCER);
        normal.put("sourceHash",foreign.getCanonicalSourceHash());normal.put("projectionRevisionSha256",foreign.getProjectionRevisionSha256());
        com.fasterxml.jackson.databind.node.ObjectNode identity=normal.putObject("projectionIdentity");
        identity.put("routingSha256",foreign.getRoutingSha256());identity.put("scopePolicySha256",foreign.getScopePolicySha256());
        identity.put("originalSourceSha256",foreign.getOriginalSourceSha256());identity.put("javaParsedSourceHash",foreign.getJavaParsedSourceHash());
        identity.put("sourceRevisionSha256",foreign.getSourceRevisionSha256());identity.put("module","vetting");
        identity.set("eligibleModules",JsonUtils.parse(foreign.getEligibleModulesJson()));
        foreign.setSnapshotJson(JsonUtils.write(normal));foreign.setSnapshotBytesSha256(com.consense.common.Utf8.sha256(foreign.getSnapshotJson()));
        committed(()->bindings.saveAndFlush(foreign));return foreign;
    }
    private void replaceHistoryStamp(Long id,String stamp)throws Exception {
        jdbc.execute("drop trigger if exists canonical_source_binding_content_digests");
        committed(()->jdbc.update("update canonical_source_binding set snapshot_content_sha256=? where id=?",stamp,id));
        try(java.sql.Connection connection=dataSource.getConnection()){SourceContentDigestMigration.install(connection);}
    }
    private void assertNoPaginatedSnapshotHydration(){
        List<String> found=new ArrayList<>();for(String rawSql:SqlCapture.statements){String sql=rawSql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
            if(sql.startsWith("select ")&&sql.contains(" from canonical_source_binding ")&&sql.contains(" order by ")
                    &&sql.substring(0,sql.indexOf(" from ")).contains("snapshot_json"))found.add(sql);
        }
        assertTrue(found.isEmpty(),"Compact preparation must page scalar metadata, not complete snapshot entities: "+found);
    }
    private long bindingLoads(){return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount();}
    @Test void captured_semantic_frame_rechecks_scalar_source_revision_without_reloading_source_content()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        clearFeedback();
        frame.beforeWait();
        frame.revalidate();
        frame.close();
        List<String> sourceReads=byIdSelects();assertFalse(sourceReads.isEmpty());
        for(String sql:sourceReads){Set<String> selected=columns(sql);
            assertFalse(selected.contains("text_content")||selected.contains("structured_content_json")
                    ||selected.contains("parse_coverage_json")||selected.contains("normal_candidate_json"),sql);
            assertTrue(selected.contains("business_content_sha256"),sql);
        }
        assertEquals(0,sourceLoads());
    }
    @Test void saved_run_display_reads_only_current_scalar_binding_and_rejects_changed_bytes()throws Exception {
        committed(()->jdbc.update("update canonical_source_binding set declared_module='vetting' where id=?",binding.getId()));
        binding.setDeclaredModule("vetting");
        SourceDocument captured=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
        String text=SourceContentDigests.field(captured.getTextContent()),structure=SourceContentDigests.field(captured.getStructuredContentJson());
        String coverage=SourceContentDigests.field(captured.getParseCoverageJson()),candidate=SourceContentDigests.field(captured.getNormalCandidateJson());
        committed(()->jdbc.update("update source_document set text_content_sha256=?,structured_content_sha256=?,parse_coverage_sha256=?,"
                +"normal_candidate_sha256=?,business_content_sha256=? where id=11",
                text,structure,coverage,candidate,SourceContentDigests.combined(text,structure,coverage,candidate)));
        committed(()->jdbc.update("update canonical_source_binding set snapshot_content_sha256=? where id=?",binding.getSnapshotBytesSha256(),binding.getId()));
        SourceDocument stamp=reader.currentStamp(PROJECT,11L,"vetting");
        clearFeedback();
        List<CanonicalSourceBinding> selected=service.currentDisplayBindings(PROJECT,"vetting",Collections.singletonList(stamp));
        assertEquals(1,selected.size());assertNull(selected.get(0).getSnapshotJson());
        assertEquals(binding.getSnapshotBytesSha256(),selected.get(0).getSnapshotContentSha256());
        List<String> bindingReads=new ArrayList<>();for(String rawSql:SqlCapture.statements){String sql=rawSql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
            if(sql.contains(" from canonical_source_binding "))bindingReads.add(sql);
        }
        assertFalse(bindingReads.isEmpty());for(String sql:bindingReads)
            assertFalse(sql.substring(0,sql.indexOf(" from ")).contains("snapshot_json"),sql);
        assertEquals(0,entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount());
        String replaced="{\"authored\":\"replaced\"}";
        committed(()->jdbc.update("update canonical_source_binding set snapshot_json=?,snapshot_content_sha256=? where id=?",
                replaced,com.consense.common.Utf8.sha256(replaced),binding.getId()));
        assertEquals("CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE",
                assertThrows(CanonicalEvidenceException.class,()->service.currentDisplayBindings(PROJECT,"vetting",Collections.singletonList(stamp))).getMessage());
        committed(()->jdbc.update("update canonical_source_binding set snapshot_json=?,snapshot_content_sha256=? where id=?",
                binding.getSnapshotJson(),binding.getSnapshotBytesSha256(),binding.getId()));
        Files.write(Paths.get(originalDocument.getStoragePath()),"replaced original".getBytes(StandardCharsets.US_ASCII));
        assertEquals("SOURCE_ORIGINAL_BYTES_CHANGED",
                assertThrows(CanonicalEvidenceException.class,()->service.currentDisplayBindings(PROJECT,"vetting",Collections.singletonList(stamp))).getMessage());
    }
    @Test void mixedModuleDisplaySkipsForeignNormalHeaderWithoutHydratingItsLargeSnapshot()throws Exception {
        SourceDocument stamp=displayStamp();
        CanonicalSourceBinding foreign=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(binding,foreign);foreign.setId(null);
        foreign.setProjectionSha256(repeat('b'));foreign.setDeclaredModule("vetting");foreign.setAdoptionManifestSha256(null);foreign.setReviewReferenceSha256(null);
        String header="{\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"normal_observed\",\"authoredBody\":\""+repeatText("AUTHORED large normal source ",65536)+"\"}";
        foreign.setSnapshotJson(header);foreign.setSnapshotBytesSha256(com.consense.common.Utf8.sha256(header));
        committed(()->{bindings.saveAndFlush(foreign);jdbc.update("update canonical_source_binding set snapshot_content_sha256=? where id=?",foreign.getSnapshotBytesSha256(),foreign.getId());});
        clearFeedback();List<CanonicalSourceBinding> selected=service.currentDisplayBindings(PROJECT,"advisory",Collections.singletonList(stamp));
        assertEquals(binding.getId(),selected.get(0).getId());assertNull(selected.get(0).getSnapshotJson());
        assertEquals(0,entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount());
        assertEquals(0,sourceLoads());
        for(String sql:SqlCapture.statements)if(sql.toLowerCase(Locale.ROOT).contains(" from canonical_source_binding "))
            assertFalse(sql.substring(0,sql.toLowerCase(Locale.ROOT).indexOf(" from ")).contains("snapshot_json"),sql);
        String changed=header.replace("normal_observed","normal_OBSERVED");
        committed(()->jdbc.update("update canonical_source_binding set snapshot_json=?,snapshot_content_sha256=? where id=?",changed,com.consense.common.Utf8.sha256(changed),foreign.getId()));
        assertEquals("CANONICAL_AUTHORITY_INSPECTION_REQUIRED",assertThrows(CanonicalEvidenceException.class,
            ()->service.currentDisplayBindings(PROJECT,"advisory",Collections.singletonList(stamp))).getMessage());
    }
    @Test void foreignReviewedDisplayUsesValidatedScalarPinsDespiteLateHeaderWithoutSourceEntityLoad()throws Exception {
        SourceDocument stamp=displayStamp();String value="{\"authoredBody\":\""+repeatText("AUTHORED reviewed source ",65536)+"\",\"schema\":\"consense.canonical-projection.v1\"}";
        committed(()->jdbc.update("update canonical_source_binding set declared_module='vetting',snapshot_json=?,snapshot_bytes_sha256=?,snapshot_content_sha256=? where id=?",
            value,com.consense.common.Utf8.sha256(value),com.consense.common.Utf8.sha256(value),binding.getId()));
        clearFeedback();List<CanonicalSourceBinding> selected=service.currentDisplayBindings(PROJECT,"advisory",Collections.singletonList(stamp));
        assertEquals(binding.getId(),selected.get(0).getId());assertNull(selected.get(0).getSnapshotJson());
        assertEquals(0,entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount());assertEquals(0,sourceLoads());
        committed(()->jdbc.update("update canonical_source_binding set review_reference_sha256=null where id=?",binding.getId()));
        assertEquals("CANONICAL_AUTHORITY_INSPECTION_REQUIRED",assertThrows(CanonicalEvidenceException.class,
            ()->service.currentDisplayBindings(PROJECT,"advisory",Collections.singletonList(stamp))).getMessage());
    }
    private SourceDocument displayStamp(){
        SourceDocument captured=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
        String text=SourceContentDigests.field(captured.getTextContent()),structure=SourceContentDigests.field(captured.getStructuredContentJson());
        String coverage=SourceContentDigests.field(captured.getParseCoverageJson()),candidate=SourceContentDigests.field(captured.getNormalCandidateJson());
        committed(()->jdbc.update("update source_document set text_content_sha256=?,structured_content_sha256=?,parse_coverage_sha256=?,normal_candidate_sha256=?,business_content_sha256=? where id=11",
            text,structure,coverage,candidate,SourceContentDigests.combined(text,structure,coverage,candidate)));
        committed(()->jdbc.update("update canonical_source_binding set snapshot_content_sha256=? where id=?",binding.getSnapshotBytesSha256(),binding.getId()));
        return reader.currentStamp(PROJECT,11L,"advisory");
    }
    @ParameterizedTest @CsvSource({"text_content,Changed complete text","structured_content_json,Changed complete structure",
            "parse_coverage_json,Changed coverage","normal_candidate_json,Changed candidate graph",
            "review_role,project_fact","file_key,GCC"})
    void captured_semantic_frame_rejects_committed_source_or_identity_changes(String column,String value)throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        committed(()->jdbc.update("update source_document set "+column+"=? where id=11",value));
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,frame::beforeWait).getMessage());
    }
    @Test void captured_semantic_frame_still_checks_original_bytes()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        Path original=Paths.get(originalDocument.getStoragePath());Files.write(original,"Changed original bytes".getBytes(StandardCharsets.UTF_8));
        assertEquals("SOURCE_ORIGINAL_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,frame::beforeWait).getMessage());
    }
    @Test void captured_semantic_frame_rejects_module_eligibility_change()throws Exception {
        CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame();
        OerDocumentIdentity identity=JsonUtils.read(originalDocument.getOerIdentityJson(),OerDocumentIdentity.class);
        identity.setEligibleModules(Collections.singletonList("drafting"));
        committed(()->jdbc.update("update source_document set oer_identity_json=? where id=11",JsonUtils.write(identity)));
        assertEquals("SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED",assertThrows(CanonicalEvidenceException.class,frame::beforeWait).getMessage());
    }
    @Test void captured_semantic_stamp_checks_the_full_parsed_source_hash_once()throws Exception {
        capturedFrame();SourceDocument captured=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
        binding.setJavaParsedSourceHash(repeat('b'));
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,
                ()->reader.captureSourceStamp(binding,"advisory",captured)).getMessage());
    }
    @Test void checked_semantic_stamp_does_not_parse_the_already_checked_snapshot_again()throws Exception {
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame()){
            SourceDocument captured=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
            JsonNode checked=JsonUtils.parse(binding.getSnapshotJson()),before=checked.deepCopy();
            try(JsonParseCounter reads=new JsonParseCounter(JsonUtils.mapper().getFactory(),binding.getSnapshotJson())){
                assertNotNull(reader.captureSourceStamp(binding,"advisory",captured,checked));
                assertEquals(0,reads.sourceReads,"The current source gate must reuse the caller's checked snapshot");
            }
            assertEquals(before,checked);
        }
    }
    @Test void checked_semantic_stamp_still_rejects_the_wrong_parsed_source_hash()throws Exception {
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=capturedFrame()){
            SourceDocument captured=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
            JsonNode checked=JsonUtils.parse(binding.getSnapshotJson());binding.setJavaParsedSourceHash(repeat('b'));
            assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,
                    ()->reader.captureSourceStamp(binding,"advisory",captured,checked)).getMessage());
        }
    }
    private CanonicalEvidenceService.VerifiedBindingFrame capturedFrame()throws Exception {
        try(java.sql.Connection connection=dataSource.getConnection()){SourceContentDigestMigration.install(connection);}
        SourceDocument captured=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
        digestPreparation.prepareSourceStamp(captured,"advisory");
        captured=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
        binding=bindings.findById(binding.getId()).orElseThrow(()->new AssertionError("binding missing"));
        SourceDocument stamp=reader.captureSourceStamp(binding,"advisory",captured);assertNotNull(stamp);
        CanonicalEvidenceService target=org.springframework.test.util.AopTestUtils.getTargetObject(service);
        Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");
        java.lang.reflect.Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class,SourceDocument.class);view.setAccessible(true);
        java.lang.reflect.Constructor<CanonicalEvidenceService.VerifiedBindingFrame> constructor=CanonicalEvidenceService.VerifiedBindingFrame.class
                .getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);constructor.setAccessible(true);
        return constructor.newInstance(target,service,PROJECT,"advisory",
                Collections.singletonList(view.newInstance(binding,JsonUtils.parse(binding.getSnapshotJson()),false,stamp)));
    }
    @Test void current_document_reads_complete_verification_fields_and_preserves_retained_archive(){
        SourceDocument d=reader.currentDocument(PROJECT,11L,"advisory");assertNull(d.getNativePdfEvidenceJson());assertNull(d.getParseCoverageJson());
        assertEquals(originalDocument.getStructuredContentJson(),d.getStructuredContentJson());assertEquals(originalDocument.getTextContent(),d.getTextContent());
        assertEquals(originalDocument.getSourceSha256(),d.getSourceSha256());assertEquals(originalDocument.getStoragePath(),d.getStoragePath());assertVerificationSql("currentDocument");
        committed(()->{SourceDocument retained=documents.findById(11L).orElseThrow();assertEquals(nativeJson,retained.getNativePdfEvidenceJson());assertEquals(originalDocument.getParseCoverageJson(),retained.getParseCoverageJson());assertEquals("application/pdf",retained.getContentType());assertEquals(999L,retained.getSizeBytes());});
    }
    @Test void vetting_original_after_read_rejects_committed_role_change_despite_a_managed_old_entity(){
        SourceDocumentRepository boundary=mock(SourceDocumentRepository.class,org.mockito.AdditionalAnswers.delegatesTo(documents));
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            Optional<SourceDocumentRepository.VerificationMetadata> captured=documents.findVerificationMetadataById(11L);
            if(reads.incrementAndGet()==1)committed(()->jdbc.update("UPDATE source_document SET review_role=? WHERE id=11","project_fact"));
            return captured;
        }).when(boundary).findVerificationMetadataById(11L);
        VettingService vetting=new VettingService(mock(com.consense.service.ProjectService.class),mock(com.consense.service.StorageService.class),
                mock(com.consense.document.DocumentParser.class),properties,boundary,mock(VettingFindingRepository.class),mock(VettingRunRepository.class),
                mock(com.consense.service.vetting.VettingSemanticReview.class),mock(com.consense.service.vetting.VettingReportWriter.class),transactions,mock(com.consense.ai.LlmProfiles.class));
        TransactionTemplate outer=new TransactionTemplate(transactions);outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.execute(status->{
            SourceDocument managed=documents.findById(11L).orElseThrow();assertEquals("tender",managed.getReviewRole());
            com.consense.common.BizException rejected=assertThrows(com.consense.common.BizException.class,()->vetting.originalSource(PROJECT,11L,
                    VettingCorpus.sourceHash(originalDocument),originalDocument.getSourceSha256(),"tender"));
            assertEquals(4104,rejected.getCode());assertEquals(2,reads.get());
            assertSame(managed,documents.findById(11L).orElseThrow());assertEquals("tender",managed.getReviewRole(),"The first-level cache really retains the superseded role");
            return null;
        });
    }
    @Test void actual_registration_keeps_full_initial_source_read_and_verification_only_after_remote_response(){
        CanonicalSourceBinding registered=service.registerReviewed(PROJECT,11L,reviewedRegistration());
        assertEquals(raw,registered.getSnapshotJson());List<String> queries=byIdSelects();assertEquals(2,queries.size());
        System.out.println("REGISTER_FULL_SQL_FEEDBACK "+queries.get(0));System.out.println("REGISTER_POST_HTTP_SQL_FEEDBACK "+queries.get(1));
        assertEquals(VERIFY_COLUMNS,columns(queries.get(0)));assertEquals(VERIFY_COLUMNS,columns(queries.get(1)));assertEquals(0,sourceLoads());
    }
    @Test void semantic_frame_creation_uses_the_selected_complete_source_without_a_second_lob_read()throws Exception {
        try(java.sql.Connection connection=dataSource.getConnection()){SourceContentDigestMigration.install(connection);}
        SourceDocument selected=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
        digestPreparation.prepareSourceStamp(selected,"vetting");
        selected=documents.findBusinessSourcesByProjectId(PROJECT).get(0);
        service.registerReviewed(PROJECT,11L,reviewedRegistration());
        clearFeedback();
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=service.verifiedSemanticBindings(PROJECT,"vetting",Collections.singletonList(selected))){
            frame.beforeWait();
        }
        List<String> sourceReads=byIdSelects();assertFalse(sourceReads.isEmpty());
        for(String sql:sourceReads){Set<String> selectedColumns=columns(sql);
            assertFalse(selectedColumns.contains("text_content")||selectedColumns.contains("structured_content_json")
                    ||selectedColumns.contains("parse_coverage_json")||selectedColumns.contains("normal_candidate_json"),sql);
        }
        assertEquals(0,sourceLoads());
    }
    private CanonicalEvidenceService.ReviewedRegistration reviewedRegistration(){
        return new CanonicalEvidenceService.ReviewedRegistration(fixture.path("projectionSha256").asText(),originalDocument.getSourceSha256(),
                VettingCorpus.sourceHash(originalDocument),fixture.path("source").path("sourceRevisionSha256").asText(),repeat('d'),
                fixture.path("projectionIdentity").path("manifestSha256").asText(),fixture.path("manifest").path("reference").path("evidenceSha256").asText(),"vetting");
    }
    @ParameterizedTest @CsvSource({"text_content,CANONICAL_SOURCE_STALE","structured_content_json,CANONICAL_SOURCE_STALE","parse_status,CANONICAL_SOURCE_STALE","review_role,CANONICAL_SOURCE_STALE","file_key,CANONICAL_SOURCE_STALE","document_scope,SOURCE_MODULE_EXCLUDED","category,SOURCE_TEMPLATE_DRAFTING_ONLY","project_id,SOURCE_DOCUMENT_UNAVAILABLE","storage_path,SOURCE_ORIGINAL_UNAVAILABLE"})
    void required_field_mutations_still_reject(String column,String reason){
        Map<String,String> changes=new HashMap<>();changes.put("text_content","AUTHORED changed 😀");changes.put("structured_content_json","[{\"text\":\"AUTHORED changed\"}]");changes.put("parse_status","FAILED");changes.put("review_role","standard");changes.put("file_key","GCC");changes.put("document_scope","pricing");changes.put("category",SourceDocument.CATEGORY_STANDARD_TEMPLATE);changes.put("file_name","authored answer sheet.pdf");changes.put("project_id","other-authored-project");changes.put("storage_path",directory.resolve("missing.pdf").toString());
        committed(()->jdbc.update("UPDATE source_document SET "+column+"=? WHERE id=11",changes.get(column)));clearFeedback();
        assertEquals(reason,assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void changingOnlyDisplayFilenameCannotChangeConfirmedSourceEligibility() {
        committed(() -> jdbc.update("UPDATE source_document SET file_name=? WHERE id=11", "authored answer sheet.pdf"));
        assertDoesNotThrow(() -> reader.assertSourceCurrent(binding, "advisory"));
        assertEquals(originalDocument.getSourceSha256(), reader.currentDocument(PROJECT, 11L, "advisory").getSourceSha256());
    }
    @Test void paragraph_style_name_normalization_remains_the_same_and_other_structure_bytes_still_matter(){
        committed(()->jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=11",originalDocument.getStructuredContentJson().replace("Heading 1","Heading 2")));assertDoesNotThrow(()->reader.assertSourceCurrent(binding,"advisory"));
        committed(()->jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=11",originalDocument.getStructuredContentJson().replace("AUTHORED 😀","AUTHORED changed 😀")));
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void original_bytes_identity_and_module_eligibility_remain_independent_gates() throws Exception {
        byte[] bytes=Files.readAllBytes(Paths.get(originalDocument.getStoragePath()));bytes[bytes.length/2]^=1;Files.write(Paths.get(originalDocument.getStoragePath()),bytes);
        assertEquals("SOURCE_ORIGINAL_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void changed_identity_or_declared_binding_eligibility_does_not_inherit_the_projection(){
        OerDocumentIdentity identity=JsonUtils.read(originalDocument.getOerIdentityJson(),OerDocumentIdentity.class);identity.setEligibleModules(Collections.singletonList("drafting"));committed(()->jdbc.update("UPDATE source_document SET oer_identity_json=? WHERE id=11",JsonUtils.write(identity)));
        assertEquals("SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void source_sha_mismatch_is_still_rejected_by_identity_before_original_hash(){
        committed(()->jdbc.update("UPDATE source_document SET source_sha256=? WHERE id=11",repeat('b')));
        assertEquals("SOURCE_ROUTING_STALE",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
    }
    @Test void null_source_id_keeps_the_repository_nonnull_contract(){
        binding.setDocumentId(null);assertThrows(IllegalArgumentException.class,()->reader.assertSourceCurrent(binding,"advisory"));
    }
    @Test void real_deleted_source_is_unavailable_for_both_verification_and_full_document_reads(){
        committed(()->jdbc.update("DELETE FROM source_document WHERE id=11"));clearFeedback();
        assertEquals("SOURCE_DOCUMENT_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->reader.assertSourceCurrent(binding,"advisory")).getMessage());
        assertEquals("SOURCE_DOCUMENT_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->reader.currentDocument(PROJECT,11L,"advisory")).getMessage());
    }
    private void assertVerificationSql(String label){List<String> selects=byIdSelects();assertEquals(1,selects.size());String sql=selects.get(0);System.out.println("VERIFY_SQL_FEEDBACK "+label+" "+sql);System.out.println("VERIFY_SOURCE_ENTITY_LOAD_COUNT "+sourceLoads());assertEquals(VERIFY_COLUMNS,columns(sql));assertEquals(0,sourceLoads());}
    private List<String> byIdSelects(){List<String> found=new ArrayList<>();for(String sql:SqlCapture.statements){String s=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();if(s.startsWith("select ")&&s.contains(" from source_document ")&&s.matches("(?s).*where\\s+[^ ]*\\.id\\s*=.*"))found.add(s);}return found;}
    private Set<String> columns(String sql){Set<String> result=new LinkedHashSet<>();String selected=sql.substring(sql.indexOf("select ")+7,sql.indexOf(" from "));for(String col:selected.split(",")){String field=col.trim().split("\\s+")[0];result.add(field.substring(field.lastIndexOf('.')+1));}return result;}
    private long sourceLoads(){return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount();}
    private long snapshotClobReads(){return jdbc.queryForObject("select coalesce(sum(execution_count),0) from information_schema.query_statistics where lower(sql_statement) like 'select snapshot_json from canonical_source_binding where id=%'",Long.class);}
    private void clearFeedback(){SqlCapture.statements.clear();entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();}
    private void committed(Runnable work){TransactionTemplate tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.execute(status->{work.run();return null;});}
    private static byte[] read(Path path){try{return Files.readAllBytes(path);}catch(IOException e){throw new IllegalStateException(e);}}
    private static byte[] resource(String name)throws IOException{try(InputStream in=CanonicalVerificationReadJpaTest.class.getResourceAsStream("/oer/canonical/"+name)){if(in==null)throw new IOException(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n);return out.toByteArray();}}
    private static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
    private static String repeatText(String value,int count){StringBuilder out=new StringBuilder();for(int n=0;n<count;n++)out.append(value);return out.toString();}
}
