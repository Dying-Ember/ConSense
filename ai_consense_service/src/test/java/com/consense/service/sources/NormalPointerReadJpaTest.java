package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.config.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.*;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

/** Public pointer, actual repositories/JPA/H2 and loopback HTTP; no live service or database. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({CanonicalEvidenceService.class,CanonicalCurrentSourceReader.class,NormalObservedPreparationService.class,SourceContentDigestPreparation.class,NormalPointerReadJpaTest.Config.class})
class NormalPointerReadJpaTest {
    static final String URL="jdbc:h2:mem:pointer_read_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @TestConfiguration static class Config {
        @Bean CanonicalEvidenceProperties canonicalPointerProperties(){return new CanonicalEvidenceProperties();}
        @Bean CanonicalEvidenceBackend canonicalPointerBackend(CanonicalEvidenceProperties properties){return new CanonicalEvidenceBackend(properties);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->URL);registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired CanonicalEvidenceService service;
    @Autowired SourceDocumentRepository documents;
    @Autowired CanonicalSourceBindingRepository bindings;
    @Autowired ConsenseProperties properties;
    @Autowired CanonicalEvidenceProperties canonicalProperties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory factory;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @TempDir Path directory;

    @Test void publicNormalPointerStreamsOneSelectedUnitWithoutHydratingEitherEntity()throws Exception {
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)){
            try(java.sql.Connection connection=dataSource.getConnection()){SourceContentDigestMigration.install(connection);}
            CanonicalSourceBinding row=wire.rows.get(1L);String raw=NormalPointerStreamingTest.produce(wire,row,directory);
            JsonNode projection=JsonUtils.parse(raw),identity=projection.path("projectionIdentity");
            row.setProjectionSha256(projection.path("projectionSha256").asText());row.setCanonicalSourceHash(projection.path("sourceHash").asText());
            row.setProjectionRevisionSha256(projection.path("projectionRevisionSha256").asText());row.setSourceRevisionSha256(identity.path("sourceRevisionSha256").asText());
            row.setSnapshotJson(raw);row.setSnapshotBytesSha256(Utf8.sha256(raw));row.setSnapshotContentSha256(row.getSnapshotBytesSha256());
            SourceDocument source=wire.documents.get(1L);Path original=directory.resolve("original.docx");Files.writeString(original,"original-1",StandardCharsets.UTF_8);
            source.setStoragePath(original.toString());source.setNativePdfEvidenceJson("{\"unusedNativeBody\":\""+"AUTHORED unused ".repeat(65536)+"\"}");properties.setStorageRoot(directory.toString());
            committed(()->{
                source.setId(null);SourceDocument saved=documents.saveAndFlush(source);assertEquals(1L,saved.getId());
                row.setId(null);bindings.saveAndFlush(row);
            });
            String nativeRaw=JsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(projection);
            assertNotEquals(row.getSnapshotBytesSha256(),Utf8.sha256(nativeRaw));
            HttpServer nativeServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            nativeServer.createContext("/canonical/"+row.getProjectionSha256(),exchange->{
                exchange.getResponseHeaders().set("ETag","\""+Utf8.sha256(nativeRaw)+"\"");
                // Match the existing Native normal canonical protocol: identity is in the body, not a source header.
                exchange.getResponseHeaders().set("X-Projection-Sha256",row.getProjectionSha256());
                byte[] body=Utf8.bytes(nativeRaw);exchange.sendResponseHeaders(200,body.length);try(java.io.OutputStream output=exchange.getResponseBody()){output.write(body);}
            });nativeServer.start();
            try{
                canonicalProperties.setBaseUrl("http://127.0.0.1:"+nativeServer.getAddress().getPort());canonicalProperties.setNormalPublicationTimeoutMs(5000);
                SqlCapture.statements.clear();factory.unwrap(SessionFactory.class).getStatistics().clear();jdbc.execute("SET QUERY_STATISTICS TRUE");
                JsonNode selected=projection.path("chunks").get(0);
                com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=service.pointer("authored-compact",1L,row.getProjectionSha256(),selected.path("unitId").asText(),"vetting");
                assertEquals(selected.path("parts"),pointer.getParts());assertEquals(selected.path("id").asText(),pointer.getCanonicalChunkId());assertNull(pointer.getNativeWordSourceScopesContext());
                assertEquals(0,factory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount());
                assertEquals(0,factory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(CanonicalSourceBinding.class.getName()).getLoadCount());
                for(String statement:SqlCapture.statements){
                    String sql=statement.toLowerCase(Locale.ROOT);if(!sql.startsWith("select "))continue;
                    String selectedColumns=sql.substring(0,sql.indexOf(" from "));
                    assertFalse(selectedColumns.contains("snapshot_json")||selectedColumns.contains("text_content,")||selectedColumns.contains("structured_content_json")
                        ||selectedColumns.contains("parse_coverage_json")||selectedColumns.contains("normal_candidate_json")||selectedColumns.contains("native_pdf_evidence_json"),sql);
                }
                assertEquals(1L,jdbc.queryForObject("select coalesce(sum(execution_count),0) from information_schema.query_statistics where lower(sql_statement) like 'select text_content,structured_content_json,parse_coverage_json,normal_candidate_json from source_document where id=%'",Long.class));
                assertEquals(1L,jdbc.queryForObject("select coalesce(sum(execution_count),0) from information_schema.query_statistics where lower(sql_statement) like 'select snapshot_json from canonical_source_binding where id=%'",Long.class));
            }finally{jdbc.execute("SET QUERY_STATISTICS FALSE");nativeServer.stop(0);}
        }
    }
    private void committed(Runnable work){TransactionTemplate transaction=new TransactionTemplate(transactions);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.execute(status->{work.run();return null;});}
}
