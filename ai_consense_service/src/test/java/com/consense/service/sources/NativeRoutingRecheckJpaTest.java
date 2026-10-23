package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real authored ZIP bytes plus committed JPA rows exercise the existing routing service boundary. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({OerSourceRoutingRefreshService.class,NativeRoutingRecheckJpaTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class NativeRoutingRecheckJpaTest {
    static final String DB=UUID.randomUUID().toString();
    static final Path ROOT=createRoot();
    static Path createRoot(){try{Path folder=Paths.get("target","native-recheck-uploads",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(folder);return folder;}catch(IOException failure){throw new IllegalStateException(failure);}}
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();public String inspect(String sql){statements.add(sql.toLowerCase(Locale.ROOT));return sql;}
    }
    @TestConfiguration static class Config {@Bean ConsenseProperties properties(){ConsenseProperties p=new ConsenseProperties();p.setStorageRoot(ROOT.toString());return p;}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:native_recheck_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->ROOT.toString());r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired SourceDocumentRepository documents;
    @Autowired OerSourceRoutingRefreshService routing;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void unknown_docx_native_pre_becomes_eligible_without_loading_or_rewriting_parse_lobs()throws Exception {
        SourceDocument d=fixture(standard());String oldText=d.getTextContent(),oldStructure=d.getStructuredContentJson();SqlCapture.statements.clear();
        OerSourceRoutingRefreshService.Result result=recheck(d);List<String> sql=new ArrayList<>(SqlCapture.statements);
        assertTrue(result.isChanged());assertEquals("PRE",result.getIdentity().getKind());assertEquals("project_document",result.getIdentity().getSourcePurpose());
        assertTrue(result.getIdentity().getIdentityEvidence().stream().anyMatch(e->"native_preliminaries_heading".equals(e.getBasis())));
        for(String statement:sql)for(String column:Arrays.asList("text_content","structured_content_json","parse_coverage_json","normal_candidate_json","native_pdf_evidence_json"))
            assertFalse(statement.matches("(?s).*\\."+column+"\\b.*"),"Identity operation loaded "+column);
        SourceDocument after=documents.findById(d.getId()).orElseThrow(AssertionError::new);
        assertEquals(oldText,after.getTextContent());assertEquals(oldStructure,after.getStructuredContentJson());assertEquals(d.getParseCoverageJson(),after.getParseCoverageJson());
        assertEquals(d.getNormalCandidateJson(),after.getNormalCandidateJson());assertEquals(d.getNativePdfEvidenceJson(),after.getNativePdfEvidenceJson());assertEquals(d.getParseMessage(),after.getParseMessage());
        assertEquals("PRE",after.getFileKey());assertEquals("PARTIAL",after.getParseStatus());assertEquals(d.getSourceSha256(),after.getSourceSha256());
        assertNull(ProjectDocumentScope.exclusionReason(after,"fullset","vetting"));
    }
    @Test void rejects_stale_original_or_routing_manual_roles_known_identity_and_superseded_source()throws Exception {
        SourceDocument d=fixture(standard());String original=d.getOerIdentityJson();
        SourceDocument initial=d;CanonicalEvidenceException stale=assertThrows(CanonicalEvidenceException.class,()->routing.recheckNativeIdentity(initial.getProjectId(),initial.getId(),String.join("",Collections.nCopies(64,"a")),CanonicalEvidenceService.routingHash(initial)));
        assertEquals("SOURCE_REFRESH_SNAPSHOT_STALE",stale.getMessage());
        assertEquals("SOURCE_REFRESH_SNAPSHOT_STALE",assertThrows(CanonicalEvidenceException.class,()->routing.recheckNativeIdentity(initial.getProjectId(),initial.getId(),initial.getSourceSha256(),String.join("",Collections.nCopies(64,"b")))).getMessage());
        jdbc.update("update source_document set review_role='project_fact' where id=?",d.getId());d.setReviewRole("project_fact");reject(d,"SOURCE_ROUTING_PURPOSE_CONFLICT");
        jdbc.update("update source_document set review_role=null where id=?",d.getId());d.setReviewRole(null);
        jdbc.update("update source_document set oer_identity_json=? where id=?",original.replace("unknown\"}","unknown\",\"identityStatus\":\"human_confirmed_source_use\"}"),d.getId());
        d=documents.findById(d.getId()).orElseThrow(AssertionError::new);reject(d,"SOURCE_NATIVE_RECHECK_UNKNOWN_ONLY");
        jdbc.update("update source_document set oer_identity_json=? where id=?",original,d.getId());d.setOerIdentityJson(original);
        SourceDocument newer=new SourceDocument();newer.setProjectId(d.getProjectId());newer.setCategory(d.getCategory());newer.setFileName(d.getFileName());newer.setParseStatus("FAILED");documents.saveAndFlush(newer);
        reject(d,"SOURCE_DOCUMENT_SUPERSEDED");
    }
    @Test void actual_original_changes_and_explicit_exclusions_are_rejected_without_identity_write()throws Exception {
        SourceDocument changed=fixture(standard());Files.write(Paths.get(changed.getStoragePath()),"different actual bytes".getBytes(StandardCharsets.UTF_8));reject(changed,"SOURCE_ORIGINAL_BYTES_CHANGED");
        for(String scope:Arrays.asList("drawing","pricing","example","works_pss")){
            SourceDocument d=fixture(standard());jdbc.update("update source_document set document_scope=? where id=?",scope,d.getId());d.setDocumentScope(scope);reject(d,"SOURCE_MODULE_EXCLUDED");
        }
        SourceDocument known=fixture(standard());known.setOerIdentityJson(known.getOerIdentityJson().replace("\"UNKNOWN\"","\"SL\""));jdbc.update("update source_document set oer_identity_json=? where id=?",known.getOerIdentityJson(),known.getId());reject(known,"SOURCE_NATIVE_RECHECK_UNKNOWN_ONLY");
    }
    @Test void filename_and_late_or_library_evidence_cannot_qualify_source()throws Exception {
        List<String> late=new ArrayList<>();for(int n=0;n<1024;n++)late.add("filler");late.addAll(standard());reject(fixture(late),"SOURCE_ROUTING_IDENTITY_UNRESOLVED");
        reject(fixture(Arrays.asList("Specification Library",standard().get(0),standard().get(1),standard().get(2),standard().get(3))),"SOURCE_ROUTING_IDENTITY_UNRESOLVED");
        reject(fixture(Collections.singletonList("a document only mentioning PRE.B1 PROJECT PARTICULARS")),"SOURCE_ROUTING_IDENTITY_UNRESOLVED");
        List<String> overText=new ArrayList<>(standard().subList(0,3));overText.add(String.join("",Collections.nCopies(131073,"x")));overText.add(standard().get(3));reject(fixture(overText),"SOURCE_ROUTING_IDENTITY_UNRESOLVED");
    }
    private List<String> standard(){return Arrays.asList("PRE.B1 PROJECT PARTICULARS 17","PRE.B1.010.P THE PROJECT 17","PRE.B2 THE WORKS 18","Contract no. AUTHORED123 (the \"Contract\")");}
    private SourceDocument fixture(List<String> lines)throws Exception {
        StringBuilder xml=new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for(String text:lines)xml.append("<w:p><w:r><w:t>").append(text.replace("&","&amp;").replace("<","&lt;")).append("</w:t></w:r></w:p>");xml.append("</w:body></w:document>");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(xml.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        Path file=ROOT.resolve(UUID.randomUUID()+".docx");Files.write(file,bytes.toByteArray());
        SourceDocument d=new SourceDocument();d.setProjectId("authored-"+UUID.randomUUID());d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("PRE");
        d.setFileName("anonymous.docx");d.setParseStatus("PARTIAL");d.setStoragePath(file.toString());d.setSourceSha256(OerSourceMetadata.originalSha256(bytes.toByteArray()));
        d.setTextContent("untouched authored parsed text");d.setStructuredContentJson("untouched authored structure");d.setParseCoverageJson("{\"complete\":false}");d.setNormalCandidateJson("untouched normal candidates");
        d.setNativePdfEvidenceJson("INTENTIONALLY UNPARSEABLE untouched native archive");d.setParseMessage("retained partial coverage");
        d.setOerIdentityJson("{\"sourceSha256\":\""+d.getSourceSha256()+"\",\"kind\":\"UNKNOWN\",\"sourcePurpose\":\"unknown\"}");return documents.saveAndFlush(d);
    }
    private OerSourceRoutingRefreshService.Result recheck(SourceDocument d){return routing.recheckNativeIdentity(d.getProjectId(),d.getId(),d.getSourceSha256(),CanonicalEvidenceService.routingHash(d));}
    private void reject(SourceDocument d,String reason){String identity=d.getOerIdentityJson();CanonicalEvidenceException failure=assertThrows(CanonicalEvidenceException.class,()->recheck(d));assertEquals(reason,failure.getMessage());assertEquals(identity,jdbc.queryForObject("select oer_identity_json from source_document where id=?",String.class,d.getId()));}
}
