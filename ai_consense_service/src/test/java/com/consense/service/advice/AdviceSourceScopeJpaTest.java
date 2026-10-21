package com.consense.service.advice;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.sources.CanonicalEvidenceException;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.vetting.OerBusinessEvidence;
import com.consense.web.dto.AdviceDtos.IndexStatusVO;
import com.consense.web.dto.AdviceDtos.SourcePreparationResult;
import com.consense.web.dto.AdviceDtos.SourcePreparationVO;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real catalog SQL with mocked preparation boundaries; authored inputs, no network, engine or model. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("h2")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AdviceSourceScopeJpaTest {
    private static final String URL="jdbc:h2:mem:advice_source_scope_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final Set<String> COLUMNS=new LinkedHashSet<>(Arrays.asList("id","project_id","category","file_key","file_name","review_role","document_scope","parse_status","parse_message","oer_identity_json","source_sha256","page_count","ocr_used","text_content","parse_coverage_json","structured_content_json"));
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->URL);r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired SourceDocumentRepository documents;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test void unavailableStatusUsesOnlyScopeProjectionAndRetainsTheSelectedIds() {
        String project="authored-status-scope-"+UUID.randomUUID();
        SourceDocument a=source(project,"SCT","first.docx","package"),b=source(project,"PRE","second.docx","package");
        source(project,"BQ","excluded-pricing.docx","pricing");
        CanonicalEvidenceService preparation=mock(CanonicalEvidenceService.class);OerBusinessEvidence canonical=mock(OerBusinessEvidence.class);
        when(canonical.enabled()).thenReturn(true);
        when(canonical.currentIndexStatus(eq(project),eq("advisory"),any())).thenThrow(new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENT_UNREGISTERED"));
        when(preparation.normalPreparable(project,Arrays.asList(a.getId(),b.getId()),"advisory")).thenReturn(true);
        AdviceService service=service(canonical,preparation);clearSql();
        IndexStatusVO status=service.status(project,"fullset");
        assertEquals(2,status.getEligibleDocuments());assertEquals(Boolean.TRUE,status.getPreparable());assertEquals("source_unregistered",status.getSourceReason());
        assertProjectionSelects(1);verify(preparation).normalPreparable(project,Arrays.asList(a.getId(),b.getId()),"advisory");
        verify(preparation,never()).prepareNormalObserved(anyString(),anyLong(),anyString());
    }

    @Test void preparationUsesFreshProjectionAtBothCatalogGatesWithoutTouchingStoredPayloads() {
        String project="authored-preparation-scope-"+UUID.randomUUID();
        SourceDocument a=source(project,"SCT","first.docx","package"),b=source(project,"PRE","second.docx","package");
        source(project,"BQ","excluded-pricing.docx","pricing");
        CanonicalEvidenceService preparation=mock(CanonicalEvidenceService.class);
        when(preparation.prepareNormalObserved(eq(project),anyLong(),eq("advisory"))).thenReturn(true);
        when(preparation.verifiedBindings(project,"advisory",Arrays.asList(a.getId(),b.getId())))
                .thenThrow(new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE"));
        AdviceService service=service(null,preparation);clearSql();SourcePreparationVO result=service.prepareSources(project,"fullset");
        assertEquals(2,result.getSelectedDocuments());assertEquals(2,result.getPreparedDocuments());assertFalse(result.isComplete(),"The mocked final canonical guard rejects; catalog optimization cannot bypass it");
        assertFalse(result.isHumanConfirmed());assertFalse(result.isSourceQualityAccepted());assertEquals(2,result.getResults().size());
        assertEquals(String.valueOf(a.getId()),result.getResults().get(0).getDocumentId());assertEquals(String.valueOf(b.getId()),result.getResults().get(1).getDocumentId());
        for(SourcePreparationResult row:result.getResults())assertEquals("prepared",row.getStatus());assertProjectionSelects(2);
        verify(preparation).prepareNormalObserved(project,a.getId(),"advisory");verify(preparation).prepareNormalObserved(project,b.getId(),"advisory");
        verify(preparation).verifiedBindings(project,"advisory",Arrays.asList(a.getId(),b.getId()));
        for(SourceDocument d:Arrays.asList(a,b)) {
            assertEquals(d.getTextContent(),jdbc.queryForObject("select text_content from source_document where id=?",String.class,d.getId()));
            assertEquals(d.getStructuredContentJson(),jdbc.queryForObject("select structured_content_json from source_document where id=?",String.class,d.getId()));
            assertEquals(d.getNormalCandidateJson(),jdbc.queryForObject("select normal_candidate_json from source_document where id=?",String.class,d.getId()));
            assertEquals(d.getNativePdfEvidenceJson(),jdbc.queryForObject("select native_pdf_evidence_json from source_document where id=?",String.class,d.getId()));
        }
    }

    @Test void finalProjectionStillDetectsCommittedTextCoverageRoutingAndMembershipChanges() {
        for(String change:Arrays.asList("text","coverage","routing","member")) {
            String project="scope-change-"+change+"-"+UUID.randomUUID();SourceDocument d=source(project,"PRE","source.docx","package");
            CanonicalEvidenceService preparation=mock(CanonicalEvidenceService.class);
            when(preparation.prepareNormalObserved(project,d.getId(),"advisory")).thenAnswer(call->{
                switch(change) {
                    case "text":jdbc.update("update source_document set text_content=? where id=?","AUTHORED committed text change",d.getId());break;
                    case "coverage":jdbc.update("update source_document set parse_coverage_json=? where id=?","{\"complete\":true}",d.getId());break;
                    case "routing":jdbc.update("update source_document set review_role=? where id=?","standard",d.getId());break;
                    default:source(project,"PRE","new-member.docx","package");
                }
                return true;
            });
            AdviceService service=service(null,preparation);clearSql();SourcePreparationVO result=service.prepareSources(project,"fullset");
            assertEquals(1,result.getSelectedDocuments(),change);assertEquals(1,result.getPreparedDocuments(),change);assertFalse(result.isComplete(),change);
            assertProjectionSelects(2);verify(preparation,never()).verifiedBindings(anyString(),anyString(),anyList());
        }
    }

    private AdviceService service(OerBusinessEvidence canonical,CanonicalEvidenceService preparation) {
        AiGateway ai=mock(AiGateway.class);when(ai.chatModel()).thenReturn("AUTHORED model identity only");
        AdviceService service=new AdviceService(mock(ProjectService.class),ai,null,null,null,documents,null,null,null,canonical,transactions);
        ReflectionTestUtils.setField(service,"entityManager",entityManager);ReflectionTestUtils.setField(service,"evidencePreparation",preparation);return service;
    }
    private void clearSql(){SqlCapture.statements.clear();entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();}
    private void assertProjectionSelects(int expected) {
        List<String> selects=new ArrayList<>();
        for(String sql:SqlCapture.statements){String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();if(normalized.startsWith("select ")&&normalized.contains(" from source_document "))selects.add(normalized);}
        assertEquals(expected,selects.size(),String.valueOf(selects));
        for(String sql:selects) {
            Set<String> columns=new LinkedHashSet<>();for(String item:sql.substring(sql.indexOf("select ")+7,sql.indexOf(" from ")).split(",")){String selected=item.trim().split("\\s+")[0];columns.add(selected.substring(selected.lastIndexOf('.')+1));}
            assertEquals(COLUMNS,columns);assertFalse(sql.contains("normal_candidate_json"));assertFalse(sql.contains("native_pdf_evidence_json"));
        }
        assertEquals(0,entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount());
    }
    private SourceDocument source(String project,String key,String name,String scope) {
        SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey(key);d.setFileName(name);
        d.setReviewRole("tender");d.setDocumentScope(scope);d.setParseStatus("PARTIAL");d.setSourceSha256(String.join("",Collections.nCopies(64,"a")));
        d.setTextContent("AUTHORED exact parser text 😀\r\n");d.setStructuredContentJson("[{\"paragraphStyleName\":\"Heading 1\",\"text\":\"AUTHORED complete native structure 😀\",\"retainedUnknown\":true}]");
        d.setParseCoverageJson("{\"complete\":false}");d.setNormalCandidateJson("AUTHORED UNUSED WHOLE CANDIDATE "+String.join("",Collections.nCopies(2048,"GRAPH")));
        d.setNativePdfEvidenceJson("AUTHORED UNUSED NATIVE ARCHIVE "+String.join("",Collections.nCopies(2048,"ARCHIVE")));
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());identity.setKind(key);identity.setRole("tender");identity.setSourcePurpose("project_document");
        identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(identity));return documents.saveAndFlush(d);
    }
}
