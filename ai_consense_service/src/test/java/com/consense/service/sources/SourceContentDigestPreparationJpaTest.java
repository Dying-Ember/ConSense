package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.*;
import com.consense.service.vetting.VettingCorpus;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cold preparation and later guards run against committed authored rows and actual temporary bytes. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({CanonicalCurrentSourceReader.class,SourceContentDigestPreparation.class,SourceContentDigestPreparationJpaTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class SourceContentDigestPreparationJpaTest {
    static final String DB=UUID.randomUUID().toString();static final Path ROOT=root();
    static Path root(){try{Path folder=Paths.get("target","digest-prepare-uploads",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(folder);return folder;}catch(IOException e){throw new IllegalStateException(e);}}
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();public String inspect(String sql){statements.add(sql.toLowerCase(Locale.ROOT));return sql;}
    }
    static final class PreparedCall {
        final String sql;final Map<Integer,Object> parameters=new LinkedHashMap<>();PreparedCall(String sql){this.sql=sql.toLowerCase(Locale.ROOT);}
    }
    /** JDBC callbacks bypass Hibernate's StatementInspector, so observe their real templates and bound values too. */
    static final class RecordingJdbcTemplate extends JdbcTemplate {
        static final List<PreparedCall> calls=new CopyOnWriteArrayList<>();
        RecordingJdbcTemplate(javax.sql.DataSource source){super(source);}
        @Override public <T>T execute(ConnectionCallback<T> action){
            return super.execute((ConnectionCallback<T>)connection->action.doInConnection((java.sql.Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                Object result=invoke(connection,method,args);
                if("prepareStatement".equals(method.getName())&&args!=null&&args[0] instanceof String){
                    PreparedCall call=new PreparedCall((String)args[0]);calls.add(call);java.sql.PreparedStatement statement=(java.sql.PreparedStatement)result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(ignored,operation,values)->{
                        if(operation.getName().startsWith("set")&&values!=null&&values.length>1&&values[0] instanceof Integer)call.parameters.put((Integer)values[0],"setNull".equals(operation.getName())?null:values[1]);
                        return invoke(statement,operation,values);
                    });
                }
                return result;
            })));
        }
        private static Object invoke(Object target,Method method,Object[] arguments)throws Throwable {try{return method.invoke(target,arguments);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}}
    }
    @TestConfiguration static class Config {
        @Bean ConsenseProperties properties(){ConsenseProperties p=new ConsenseProperties();p.setStorageRoot(ROOT.toString());return p;}
        @Bean @Primary JdbcTemplate jdbcTemplate(javax.sql.DataSource source){return new RecordingJdbcTemplate(source);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:digest_prepare_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->ROOT.toString());r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired SourceDocumentRepository documents;
    @Autowired CanonicalSourceBindingRepository bindings;
    @Autowired SourceContentDigestPreparation preparation;
    @Autowired CanonicalCurrentSourceReader current;
    @Autowired JdbcTemplate jdbc;

    @Test void selected_legacy_source_is_stamped_without_loading_body_again_and_warm_guard_is_metadata_only()throws Exception {
        SourceDocument captured=legacySource();install();SqlCapture.statements.clear();RecordingJdbcTemplate.calls.clear();
        SourceDocument prepared=preparation.prepareSourceStamp(captured,"vetting");assertEquals(SourceContentDigests.actual(captured),prepared.getBusinessContentSha256());
        current.requireCurrentStamp(captured,current.currentStamp(captured.getProjectId(),captured.getId(),"vetting"));
        assertNoBodyQueries();assertNoBodyParameters(captured,null);assertEquals(captured.getTextContent(),jdbc.queryForObject("select text_content from source_document where id=?",String.class,captured.getId()));
    }
    @Test void missing_write_trigger_is_explicit_recoverable_and_does_not_change_content()throws Exception {
        SourceDocument captured=legacySource();SqlCapture.statements.clear();
        CanonicalEvidenceException unavailable=assertThrows(CanonicalEvidenceException.class,()->preparation.prepareSourceStamp(captured,"vetting"));
        assertEquals("SOURCE_CONTENT_REVISION_UNAVAILABLE",unavailable.getMessage());assertNoBodyQueries();
        assertNull(jdbc.queryForObject("select business_content_sha256 from source_document where id=?",String.class,captured.getId()));
        install();assertNotNull(preparation.prepareSourceStamp(captured,"vetting").getBusinessContentSha256());
    }
    @Test void changed_source_or_routing_or_actual_original_is_never_given_the_captured_stamp()throws Exception {
        SourceDocument body=legacySource();install();jdbc.update("update source_document set text_content=? where id=?",body.getTextContent()+" changed",body.getId());
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,()->preparation.prepareSourceStamp(body,"vetting")).getMessage());
        SourceDocument routing=legacySource();install();jdbc.update("update source_document set review_role='standard' where id=?",routing.getId());
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,()->preparation.prepareSourceStamp(routing,"vetting")).getMessage());
        SourceDocument original=legacySource();install();Files.write(Paths.get(original.getStoragePath()),"altered original".getBytes(StandardCharsets.UTF_8));
        assertEquals("SOURCE_ORIGINAL_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,()->preparation.prepareSourceStamp(original,"vetting")).getMessage());
    }
    @Test void legacy_binding_gets_raw_snapshot_stamp_and_direct_sql_snapshot_mutation_is_rejected()throws Exception {
        SourceDocument captured=legacySource();CanonicalSourceBinding binding=binding(captured);install();SqlCapture.statements.clear();RecordingJdbcTemplate.calls.clear();
        assertEquals(binding.getSnapshotBytesSha256(),preparation.prepareBindingStamp(binding,captured,"vetting"));assertNoBodyQueries();assertNoBodyParameters(captured,binding.getSnapshotJson());
        jdbc.update("update canonical_source_binding set snapshot_json=? where id=?","{\"schema\":\"authored-mutated\"}",binding.getId());
        assertEquals("CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->preparation.prepareBindingStamp(binding,captured,"vetting")).getMessage());
    }
    @Test void cold_source_changed_before_stamp_preparation_is_rejected_and_the_new_stamp_is_rolled_back()throws Exception {
        SourceDocument captured=legacySource();String changed="AUTHORED actual current body, newer than the captured text";
        jdbc.update("update source_document set text_content=? where id=?",changed,captured.getId());install();RecordingJdbcTemplate.calls.clear();
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(CanonicalEvidenceException.class,()->preparation.prepareSourceStamp(captured,"vetting")).getMessage());
        assertNoBodyParameters(captured,null);assertSourceUnstamped(captured.getId());
        assertEquals(changed,jdbc.queryForObject("select text_content from source_document where id=?",String.class,captured.getId()));
        assertEquals("SOURCE_CONTENT_REVISION_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->current.currentStamp(captured.getProjectId(),captured.getId(),"vetting")).getMessage());
    }
    @Test void cold_binding_changed_before_stamp_preparation_rolls_back_both_binding_and_source_stamps()throws Exception {
        SourceDocument captured=legacySource();CanonicalSourceBinding binding=binding(captured);String changed="{\"schema\":\"authored-current-snapshot-differs\"}";
        jdbc.update("update canonical_source_binding set snapshot_json=? where id=?",changed,binding.getId());install();RecordingJdbcTemplate.calls.clear();
        assertEquals("CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->preparation.prepareBindingStamp(binding,captured,"vetting")).getMessage());
        assertNoBodyParameters(captured,binding.getSnapshotJson());assertSourceUnstamped(captured.getId());
        assertNull(jdbc.queryForObject("select snapshot_content_sha256 from canonical_source_binding where id=?",String.class,binding.getId()));
        assertEquals(changed,jdbc.queryForObject("select snapshot_json from canonical_source_binding where id=?",String.class,binding.getId()));
    }
    private SourceDocument legacySource()throws Exception {
        jdbc.execute("drop trigger if exists source_document_content_digests");jdbc.execute("drop trigger if exists canonical_source_binding_content_digests");
        byte[] bytes="AUTHORED source bytes".getBytes(StandardCharsets.UTF_8);Path file=ROOT.resolve(UUID.randomUUID()+".docx");Files.write(file,bytes);
        SourceDocument d=new SourceDocument();d.setProjectId("authored-digest-"+UUID.randomUUID());d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileName("source.docx");d.setFileKey("PRE");d.setReviewRole("tender");d.setParseStatus("PARTIAL");d.setSourceSha256(OerSourceMetadata.originalSha256(bytes));d.setStoragePath(file.toString());
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());identity.setKind("PRE");identity.setRole("tender");identity.setSourcePurpose("project_document");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(identity));
        d.setTextContent("AUTHORED selected body 😀");d.setStructuredContentJson("[]");d.setParseCoverageJson("{\"complete\":false}");d.setNormalCandidateJson(null);return documents.saveAndFlush(d);
    }
    private CanonicalSourceBinding binding(SourceDocument d){
        String sha=String.join("",Collections.nCopies(64,"c"));CanonicalSourceBinding b=new CanonicalSourceBinding();b.setProjectId(d.getProjectId());b.setDocumentId(d.getId());b.setProjectionSha256(sha);b.setOriginalSourceSha256(d.getSourceSha256());b.setJavaParsedSourceHash(VettingCorpus.sourceHash(d));b.setRoutingSha256(CanonicalEvidenceService.routingHash(d));
        b.setSourceRevisionSha256(sha);b.setProjectionRevisionSha256(sha);b.setCanonicalSourceHash(sha);b.setScopePolicySha256(sha);b.setDeclaredModule("vetting");b.setEligibleModulesJson("[\"vetting\",\"advisory\"]");b.setSnapshotJson("{\"schema\":\"authored-cold-validated-by-caller\"}");b.setSnapshotBytesSha256(Utf8.sha256(b.getSnapshotJson()));
        b=bindings.saveAndFlush(b);return bindings.findById(b.getId()).orElseThrow(AssertionError::new);
    }
    private void install(){jdbc.execute((ConnectionCallback<Void>)connection->{try{SourceContentDigestMigration.install(connection);return null;}catch(Exception error){throw new java.sql.SQLException(error);}});}
    private void assertSourceUnstamped(Long id){for(String column:Arrays.asList("text_content_sha256","structured_content_sha256","parse_coverage_sha256","normal_candidate_sha256","business_content_sha256"))assertNull(jdbc.queryForObject("select "+column+" from source_document where id=?",String.class,id),column);}
    private void assertNoBodyParameters(SourceDocument captured,String snapshot){
        assertFalse(RecordingJdbcTemplate.calls.isEmpty(),"Must observe the real cold JDBC operation");
        List<String> bodies=Arrays.asList(captured.getTextContent(),captured.getStructuredContentJson(),captured.getParseCoverageJson(),captured.getNormalCandidateJson(),snapshot);
        for(PreparedCall call:RecordingJdbcTemplate.calls){
            for(String field:Arrays.asList("text_content","structured_content_json","parse_coverage_json","normal_candidate_json","snapshot_json","native_pdf_evidence_json"))assertFalse(call.sql.matches("(?s).*\\b"+field+"\\b.*"),call.sql);
            for(Object value:call.parameters.values()){
                assertFalse(value instanceof java.io.Reader||value instanceof java.io.InputStream||value instanceof java.sql.Clob||value instanceof java.sql.Blob,"Cold JDBC must not upload a content stream");
                if(value instanceof String)for(String body:bodies)if(body!=null&&!body.isEmpty())assertNotEquals(body,value,"Cold JDBC must not re-upload an already captured body");
            }
        }
    }
    private void assertNoBodyQueries(){for(String sql:SqlCapture.statements)for(String field:Arrays.asList("text_content","structured_content_json","parse_coverage_json","normal_candidate_json","snapshot_json","native_pdf_evidence_json"))assertFalse(sql.matches("(?s).*\\."+field+"\\b.*"),sql);}
}
