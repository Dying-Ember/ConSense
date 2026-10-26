package com.consense.web;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.repository.ProjectRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.StorageService;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Purpose confirmation against the real Flyway H2 schema, including both source-row writes. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "consense.llm.enabled=false", "consense.ocr.enabled=false", "consense.vector.provider=memory",
        "consense.oer-evidence.base-url="})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingSourcePurposeFlywayH2Test {
    private static final String DB = UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:purpose_flyway_" + DB
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
                + ";OLD_INFORMATION_SCHEMA=TRUE;NON_KEYWORDS=VALUE");
        properties.add("consense.storage-root", () -> Paths.get("target", "source-purpose-flyway", DB).toAbsolutePath().toString());
    }

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @Autowired StorageService storage;
    @MockBean AiGateway externalLlm;

    @Test void completePurposeConfirmationKeepsLargeSourceFieldsAndOriginalIdentity() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            for (String column : new String[]{"oer_identity_json", "native_pdf_evidence_json", "text_content",
                    "structured_content_json", "parse_coverage_json", "normal_candidate_json"}) {
                try (ResultSet columns = connection.getMetaData().getColumns(null, connection.getSchema(), "source_document", column)) {
                    assertTrue(columns.next(), column);
                    assertEquals(Types.CLOB, columns.getInt("DATA_TYPE"), column);
                }
            }
        }
        String project = "flyway-purpose-" + UUID.randomUUID();
        Project p = new Project(); p.setId(project); p.setNameEn("Authored Flyway source purpose"); projects.saveAndFlush(p);
        byte[] original = "AUTHORED ORIGINAL PRE BYTES".getBytes(StandardCharsets.US_ASCII);
        StorageService.StoredFile stored = storage.store(project, SourceDocument.CATEGORY_VETTING_PACKAGE,
                new MockMultipartFile("files", "authored-pre.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", original));
        String body = "Original clause and table content. ".repeat(32768);
        DocumentBlock block = new DocumentBlock(); block.setId("authored:1"); block.setKind("paragraph");
        block.setLocation("body/authored"); block.setText(body); block.setSource("authored_saved_text");
        SourceDocument d = new SourceDocument(); d.setProjectId(project); d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setFileName("authored-pre.docx"); d.setFileKey("PRE"); d.setDocumentScope("package"); d.setParseStatus("PARTIAL");
        d.setStoragePath(stored.getPath()); d.setSourceSha256(OerSourceMetadata.originalSha256(original));
        d.setTextContent(body); d.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));
        d.setParseCoverageJson("{\"complete\":false}");
        d.setNormalCandidateJson(JsonUtils.write(Collections.singletonMap("retained", body)));
        d.setNativePdfEvidenceJson(JsonUtils.write(Collections.singletonMap("retained", body)));
        OerDocumentIdentity identity = new OerDocumentIdentity(); identity.setSourceSha256(d.getSourceSha256());
        identity.setIdentityStatus("format_supported_non_drawing"); d.setOerIdentityJson(JsonUtils.write(identity));
        d = sources.saveAndFlush(d);
        String structure = d.getStructuredContentJson(), candidate = d.getNormalCandidateJson(), archive = d.getNativePdfEvidenceJson();
        String sourceHash = VettingCorpus.sourceHash(d), routing = CanonicalEvidenceService.routingHash(d), originalSha = d.getSourceSha256();
        JsonNode offered = JsonUtils.parse(mvc.perform(get("/api/vetting/" + project + "/files"))
                .andReturn().getResponse().getContentAsString()).path("data").get(0);
        assertTrue(offered.path("roleConfirmationAvailable").asBoolean(), offered.toString());
        assertEquals("", offered.path("sourceHash").asText(""), "A large source must not be hydrated to compute the legacy hash in the list");
        String businessSha = offered.path("businessContentSha256").asText();
        assertEquals(64, businessSha.length());
        Map<String,String> request = new LinkedHashMap<>(); request.put("sourceRole", "tender");
        request.put("expectedOriginalSourceSha256", originalSha); request.put("expectedBusinessContentSha256", businessSha);
        request.put("expectedRoutingSha256", routing);
        Map<String,String> staleRequest = new LinkedHashMap<>(request);
        staleRequest.put("expectedBusinessContentSha256", "0".repeat(64));
        JsonNode rejected = JsonUtils.parse(mvc.perform(put("/api/vetting/" + project + "/sources/" + d.getId() + "/role")
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(staleRequest)))
                .andReturn().getResponse().getContentAsString());
        assertEquals(4104, rejected.path("code").asInt(), rejected.toString());
        JsonNode response = JsonUtils.parse(mvc.perform(put("/api/vetting/" + project + "/sources/" + d.getId() + "/role")
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(request)))
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, response.path("code").asInt(), response.toString());
        assertEquals(businessSha, response.path("data").path("businessContentSha256").asText());
        SourceDocument after = sources.findById(d.getId()).orElseThrow(AssertionError::new);
        assertEquals("tender", after.getReviewRole()); assertEquals(originalSha, after.getSourceSha256());
        assertEquals(sourceHash, VettingCorpus.sourceHash(after)); assertEquals(body, after.getTextContent());
        assertEquals(structure, after.getStructuredContentJson()); assertEquals(candidate, after.getNormalCandidateJson());
        assertEquals(archive, after.getNativePdfEvidenceJson());
        assertEquals("human_confirmed_source_use", OerSourceMetadata.identity(after).getIdentityStatus());
        assertArrayEquals(original, Files.readAllBytes(Paths.get(after.getStoragePath())));
        JsonNode listed=JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/files"))
                .andReturn().getResponse().getContentAsString()).path("data").get(0);
        assertEquals(body.length(),listed.path("textChars").asInt());
        assertEquals(originalSha,listed.path("originalSourceSha256").asText());
        assertEquals(CanonicalEvidenceService.routingHash(after),listed.path("routingSha256").asText());
        assertEquals(64,listed.path("businessContentSha256").asText().length());
        JsonNode stale = JsonUtils.parse(mvc.perform(put("/api/vetting/" + project + "/sources/" + d.getId() + "/role")
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(request)))
                .andReturn().getResponse().getContentAsString());
        assertEquals(4104, stale.path("code").asInt(), stale.toString());
        after.setParseStatus("FAILED");sources.saveAndFlush(after);
        JsonNode failed=JsonUtils.parse(mvc.perform(get("/api/vetting/"+project+"/files"))
                .andReturn().getResponse().getContentAsString()).path("data").get(0);
        assertEquals("",failed.path("sourceHash").asText(""));
        assertEquals(64,failed.path("businessContentSha256").asText().length());
        byte[] downloaded=mvc.perform(get("/api/vetting/"+project+"/sources/"+d.getId()+"/reader-original")
                .param("originalSourceSha256",failed.path("originalSourceSha256").asText())
                .param("routingSha256",failed.path("routingSha256").asText())
                .param("businessContentSha256",failed.path("businessContentSha256").asText()))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(original,downloaded);
        verifyNoInteractions(externalLlm);
    }
}
