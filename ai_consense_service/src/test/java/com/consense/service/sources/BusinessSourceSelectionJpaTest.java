package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual scope-before-payload SQL; authored local rows, no runtime/source/model access. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class BusinessSourceSelectionJpaTest {
    static final String PROJECT="authored-selection-before-payload";
    static final String URL="jdbc:h2:mem:business_selection_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql.toLowerCase(Locale.ROOT));return sql;}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->URL);
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());
    }
    @Autowired SourceDocumentRepository sources;

    @Test void excluded_unknown_and_pricing_sources_never_enter_payload_query() {
        SourceDocument selected=save("current.docx","PRE","tender",null,"PARTIAL");
        SourceDocument unknown=save("25 - PRE.docx","PRE",null,null,"PARTIAL");
        SourceDocument pricing=save("bill.docx","BQ","tender","pricing","PARSED");
        sources.flush();SqlCapture.statements.clear();
        List<SourceDocument> loaded=sources.findSelectedBusinessSourcesByProjectId(PROJECT,"fullset","vetting");
        assertEquals(Collections.singletonList(selected.getId()),ids(loaded));
        assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(unknown,"fullset"));
        assertEquals("pricing_document",ProjectDocumentScope.exclusionReason(pricing,"fullset"));
        List<String> sql=selects();assertEquals(2,sql.size(),sql.toString());
        for(String column:Arrays.asList("text_content","structured_content_json","parse_coverage_json","normal_candidate_json","native_pdf_evidence_json"))
            assertFalse(sql.get(0).matches("(?s).*\\."+column+"\\b.*"),"Scope query loaded "+column);
        assertTrue(sql.get(1).contains("text_content")&&sql.get(1).matches("(?s).*\\.id in \\(.*"),sql.get(1));
        assertFalse(sql.get(1).contains("native_pdf_evidence_json"));
    }
    @Test void no_selected_source_performs_only_the_metadata_query() {
        save("unresolved-PRE.docx","PRE",null,null,"PARTIAL");sources.flush();SqlCapture.statements.clear();
        assertTrue(sources.findSelectedBusinessSourcesByProjectId(PROJECT,"fullset","vetting").isEmpty());
        assertEquals(1,selects().size());
        assertFalse(selects().get(0).matches("(?s).*\\.text_content\\b.*"));
    }
    @Test void latest_failed_revision_is_selected_instead_of_falling_back_to_old_complete_text() {
        SourceDocument old=save("current.docx","PRE","tender",null,"PARSED");
        SourceDocument failed=save(" CURRENT.DOCX ","PRE","tender",null,"FAILED");sources.flush();SqlCapture.statements.clear();
        List<SourceDocument> selected=sources.findSelectedBusinessSourcesByProjectId(PROJECT,"fullset","vetting");
        assertEquals(Collections.singletonList(failed.getId()),ids(selected));
        assertEquals("FAILED",selected.get(0).getParseStatus());assertNotEquals(old.getId(),selected.get(0).getId());
    }
    private SourceDocument save(String name,String kind,String role,String scope,String status){
        SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileName(name);d.setFileKey(kind);d.setReviewRole(role);d.setDocumentScope(scope);d.setParseStatus(status);
        String sha=String.join("",Collections.nCopies(64,"a"));d.setSourceSha256(sha);
        d.setTextContent("AUTHORED body "+name);d.setStructuredContentJson("[{\"text\":\"AUTHORED\"}]");
        d.setParseCoverageJson("{\"unknown\":true}");d.setNormalCandidateJson("{\"adoptionStatus\":\"candidate_unverified\"}");
        if(role!=null){OerDocumentIdentity i=new OerDocumentIdentity();i.setSourceSha256(sha);i.setKind(kind);i.setRole(role);i.setSourcePurpose("project_document");i.setIdentityStatus("content_supported");i.setEligibleModules(Arrays.asList("vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(i));}
        return sources.saveAndFlush(d);
    }
    private List<Long> ids(List<SourceDocument> rows){List<Long> out=new ArrayList<>();rows.forEach(d->out.add(d.getId()));return out;}
    private List<String> selects(){List<String> out=new ArrayList<>();for(String sql:SqlCapture.statements)if(sql.startsWith("select ")&&sql.contains(" from source_document "))out.add(sql);return out;}
}
