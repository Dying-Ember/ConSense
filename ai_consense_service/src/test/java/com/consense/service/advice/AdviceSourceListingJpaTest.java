package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.sources.ProjectDocumentScope;
import com.consense.service.sources.SourceContentDigestMigration;
import com.consense.service.sources.SourceContentDigests;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.dto.AdviceDtos.AdviceSourceVO;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real listing SQL and revision-change guards; authored inputs only, no live DB, model or engine. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false","spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("h2")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
public class AdviceSourceListingJpaTest {
    private static final String PROJECT="authored-source-list",URL="jdbc:h2:mem:advice_source_list_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final Set<String> LIST_COLUMNS=new LinkedHashSet<>(Arrays.asList("id","project_id","category","file_key","file_name","review_role","document_scope","parse_status","oer_identity_json","source_sha256","storage_path","text_content_sha256","structured_content_sha256","parse_coverage_sha256","normal_candidate_sha256","business_content_sha256"));
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=new CopyOnWriteArrayList<>();
        public String inspect(String sql){statements.add(sql);return sql;}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->URL);r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",()->SqlCapture.class.getName());}
    @Autowired SourceDocumentRepository documents;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void installDigestTrigger() {
        jdbc.execute((ConnectionCallback<Void>)connection->{try{SourceContentDigestMigration.install(connection);return null;}catch(Exception error){throw new java.sql.SQLException(error);}});
    }

    @Test void sourcesUseOnlyStampedScopeMetadataEvenForLargeCurrentAndExcludedSources() {
        List<SourceDocument> authored=new ArrayList<>();
        authored.add(source(PROJECT,"PRE"," Authored Same.docx ","package","PARSED","AUTHORED superseded"));
        SourceDocument current=source(PROJECT,"PRE","authored same.docx","package","PARTIAL","AUTHORED current 😀\r\n"+repeated('T',1_000_000));authored.add(current);
        current.setStructuredContentJson("[{\"paragraphStyleName\":\"Heading 1\",\"text\":\"AUTHORED native 😀"+repeated('S',1_000_000)+"\"}]");current.setParseCoverageJson("{\"evidence\":\""+repeated('C',1_000_000)+"\"}");documents.saveAndFlush(current);
        authored.add(source(PROJECT,"NTT","authored tender.docx","package","PARSED","AUTHORED tender"));
        authored.add(source(PROJECT,"BQ","authored pricing.docx","pricing","PARTIAL","AUTHORED excluded"));
        authored.add(source(PROJECT,"UNKNOWN","authored pending.pdf","package","PARSING","AUTHORED observation"));
        authored.add(source(PROJECT,"PRE","authored older-good.docx","package","PARSED","AUTHORED old good"));
        authored.add(source(PROJECT,"PRE","AUTHORED OLDER-GOOD.DOCX","package","FAILED","AUTHORED new failed"));
        authored.add(source("another-authored-project","PRE","authored foreign.docx","package","PARSED","AUTHORED foreign"));
        entityManager.flush();entityManager.clear();
        AdviceService service=new AdviceService(mock(ProjectService.class),null,null,null,null,documents,null,null,null,null,null);

        for(String scope:Arrays.asList("fullset","tender","contract")) {
            List<AdviceSourceVO> actual=listing(service,scope);List<SourceDocument> expected=ProjectDocumentScope.currentCatalog(PROJECT,authored);
            assertEquals(expected.size(),actual.size());
            for(int n=0;n<expected.size();n++) {
                SourceDocument d=expected.get(n);AdviceSourceVO row=actual.get(n);String exclusion=ProjectDocumentScope.exclusionReason(d,scope,"advisory");
                assertEquals(String.valueOf(d.getId()),row.getDocumentId());assertEquals(d.getFileKey(),row.getFileKey());assertEquals(d.getFileName(),row.getFileName());
                assertEquals(VettingCorpus.sourceRole(d),row.getSourceRole());assertEquals(d.getDocumentScope(),row.getDocumentScope());assertEquals(d.getParseStatus(),row.getParseStatus());
                assertNull(row.getSourceHash(),"A scalar catalog cannot claim to carry the legacy parsed hash");
                assertEquals(d.getSourceSha256(),row.getOriginalSourceSha256(),"The original revision remains independently comparable to historical evidence");
                assertTrue(SourceContentDigests.valid(row.getBusinessContentSha256()),"Included and excluded sources expose the database-owned content revision");
                assertEquals(jdbc.queryForObject("select business_content_sha256 from source_document where id=?",String.class,d.getId()),row.getBusinessContentSha256());
                assertEquals(exclusion,row.getExclusionReason());assertEquals(exclusion==null,row.isIncluded());
            }
        }

        String originalHash=row(listing(service,"fullset"),current.getId()).getBusinessContentSha256();
        current.setTextContent(current.getTextContent()+"AUTHORED changed text");
        jdbc.update("UPDATE source_document SET text_content=? WHERE id=?",current.getTextContent(),current.getId());
        String textChanged=row(listing(service,"fullset"),current.getId()).getBusinessContentSha256();
        assertNotEquals(originalHash,textChanged);
        current.setStructuredContentJson(current.getStructuredContentJson().replace("Heading 1","Heading 2"));
        jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=?",current.getStructuredContentJson(),current.getId());
        String styleChanged=row(listing(service,"fullset"),current.getId()).getBusinessContentSha256();
        assertNotEquals(textChanged,styleChanged,"The raw content revision must include style-only edits even when the legacy parsed hash ignores them");
        current.setStructuredContentJson(current.getStructuredContentJson().replace("AUTHORED native 😀","AUTHORED changed native 😀"));
        jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=?",current.getStructuredContentJson(),current.getId());
        String structureChanged=row(listing(service,"fullset"),current.getId()).getBusinessContentSha256();
        assertNotEquals(styleChanged,structureChanged);
    }

    private List<AdviceSourceVO> listing(AdviceService service,String scope) {
        SqlCapture.statements.clear();entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();
        List<AdviceSourceVO> rows=service.sources(PROJECT,scope);List<String> selects=new ArrayList<>();
        for(String sql:SqlCapture.statements){String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();if(normalized.startsWith("select ")&&normalized.contains(" from source_document "))selects.add(normalized);}
        assertEquals(1,selects.size(),"One scalar query captures listing identity, eligibility and stamped revision inputs together");
        String sql=selects.get(0);Set<String> columns=new LinkedHashSet<>();
        for(String item:sql.substring(sql.indexOf("select ")+7,sql.indexOf(" from ")).split(",")){String selected=item.trim().split("\\s+")[0];columns.add(selected.substring(selected.lastIndexOf('.')+1));}
        System.out.println("ADVICE_SOURCE_LIST_SQL_FEEDBACK "+sql);
        assertEquals(LIST_COLUMNS,columns,"Listing must not project text, structure, coverage, candidate or native CLOBs");
        assertEquals(0,entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityStatistics(SourceDocument.class.getName()).getLoadCount(),"Listing must not hydrate complete source entities");
        return rows;
    }
    private SourceDocument source(String project,String key,String name,String scope,String status,String text) {
        SourceDocument d=new SourceDocument();d.setProjectId(project);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey(key);d.setFileName(name);
        d.setReviewRole("tender");d.setDocumentScope(scope);d.setParseStatus(status);d.setSourceSha256(String.join("",Collections.nCopies(64,"a")));d.setTextContent(text);
        d.setStructuredContentJson("[{\"paragraphStyleName\":\"Heading 1\",\"text\":\"AUTHORED native 😀\",\"unknown\":{\"retained\":true}}]");
        d.setParseCoverageJson("{\"complete\":false}");d.setNormalCandidateJson("AUTHORED UNUSED CANDIDATE "+String.join("",Collections.nCopies(2048,"GRAPH")));
        d.setNativePdfEvidenceJson("AUTHORED UNUSED NATIVE "+String.join("",Collections.nCopies(2048,"ARCHIVE")));
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(d.getSourceSha256());identity.setKind(key);identity.setRole("tender");identity.setSourcePurpose("project_document");
        identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(identity));
        return documents.saveAndFlush(d);
    }
    private AdviceSourceVO row(List<AdviceSourceVO> rows,Long id){return rows.stream().filter(d->String.valueOf(id).equals(d.getDocumentId())).findFirst().orElseThrow(()->new AssertionError("Missing current source "+id));}
    private static String repeated(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
}
