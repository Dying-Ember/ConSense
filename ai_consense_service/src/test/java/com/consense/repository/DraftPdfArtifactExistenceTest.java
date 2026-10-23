package com.consense.repository;

import com.consense.domain.DraftPdfArtifact;
import java.util.*;
import javax.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.consense.repository.DraftPdfArtifactExistenceTest$SqlCapture"})
@ActiveProfiles("h2") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
public class DraftPdfArtifactExistenceTest {
    private static final String URL="jdbc:h2:mem:pdf_exists_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->URL);}
    @Autowired DraftPdfArtifactRepository repository;
    @Autowired EntityManager entityManager;
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements=Collections.synchronizedList(new ArrayList<>());
        @Override public String inspect(String sql){statements.add(sql);return sql;}
    }
    @Test void existenceUsesRealJpaWithoutSelectingPdfOrManifestLobs() {
        DraftPdfArtifact artifact=new DraftPdfArtifact();artifact.setId(repeat('a'));artifact.setDocxSha256(repeat('b'));
        artifact.setPdfSha256(repeat('c'));artifact.setRenderProfileHash(repeat('d'));artifact.setPdfBytes(new byte[1024]);artifact.setManifestJson("{\"authored\":true}");
        repository.saveAndFlush(artifact);entityManager.clear();SqlCapture.statements.clear();
        assertTrue(repository.existsByDocxSha256(repeat('b')));assertFalse(repository.existsByDocxSha256(repeat('e')));
        assertEquals(2,SqlCapture.statements.size());
        for(String raw:SqlCapture.statements){String sql=raw.toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("draft_pdf_artifact"));assertTrue(sql.contains("docx_sha256"));
            assertFalse(sql.contains("pdf_bytes"),sql);assertFalse(sql.contains("manifest_json"),sql);
        }
    }
    @Test void exactProfileMetadataUsesRealJpaWithoutSelectingPdfOrManifestLobs() {
        DraftPdfArtifact first=artifact('a','b','c','d'),otherProfile=artifact('e','b','f','g');
        repository.saveAndFlush(first);repository.saveAndFlush(otherProfile);entityManager.clear();SqlCapture.statements.clear();
        DraftPdfArtifactRepository.Metadata found=repository.findMetadataById(first.getId()).orElseThrow(AssertionError::new);
        assertEquals(first.getDocxSha256(),found.getDocxSha256());assertEquals(first.getPdfSha256(),found.getPdfSha256());assertEquals(first.getRenderProfileHash(),found.getRenderProfileHash());
        DraftPdfArtifactRepository.Metadata other=repository.findMetadataById(otherProfile.getId()).orElseThrow(AssertionError::new);
        assertEquals(otherProfile.getPdfSha256(),other.getPdfSha256());assertEquals(otherProfile.getRenderProfileHash(),other.getRenderProfileHash());
        assertFalse(repository.findMetadataById(repeat('h')).isPresent());assertEquals(3,SqlCapture.statements.size());
        for(String raw:SqlCapture.statements){String sql=raw.toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("draft_pdf_artifact"),sql);assertTrue(sql.contains("docx_sha256"),sql);assertTrue(sql.contains("pdf_sha256"),sql);assertTrue(sql.contains("render_profile_hash"),sql);
            assertFalse(sql.contains("pdf_bytes"),sql);assertFalse(sql.contains("manifest_json"),sql);
        }
        DraftPdfArtifact retained=repository.findById(first.getId()).orElseThrow(AssertionError::new);
        assertArrayEquals(first.getPdfBytes(),retained.getPdfBytes());assertEquals(first.getManifestJson(),retained.getManifestJson());
    }
    private static DraftPdfArtifact artifact(char id,char docx,char pdf,char profile){
        DraftPdfArtifact artifact=new DraftPdfArtifact();artifact.setId(repeat(id));artifact.setDocxSha256(repeat(docx));artifact.setPdfSha256(repeat(pdf));artifact.setRenderProfileHash(repeat(profile));artifact.setPdfBytes(new byte[]{1,2,3,4});artifact.setManifestJson("{\"retained\":true}");return artifact;
    }
    private static String repeat(char value){char[] chars=new char[64];Arrays.fill(chars,value);return new String(chars);}
}
