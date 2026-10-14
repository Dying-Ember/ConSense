package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.advice.AdviceOriginalSourceService;
import com.consense.service.vetting.VettingCorpus;
import com.consense.web.AdviceOriginalSourceController;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdviceOriginalSourceBoundaryTest {
    @TempDir Path root;
    SourceDocument document; SourceDocumentRepository documents; CanonicalEvidenceService canonical;
    AdviceOriginalSourceService service; List<SourceDocument> catalog; byte[] bytes; Path file;
    @BeforeEach void prepare() throws Exception {
        bytes="%PDF-1.7\nAUTHORED source only\n".getBytes(StandardCharsets.UTF_8);file=root.resolve("source.pdf");Files.write(file,bytes);
        document=new SourceDocument();document.setId(11L);document.setProjectId("project-a");document.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        document.setFileKey("SCC");document.setFileName("project-SCC.pdf");document.setReviewRole("tender");document.setParseStatus("PARSED");
        document.setTextContent("AUTHORED parsed text");document.setStructuredContentJson("[]");document.setStoragePath(file.toString());document.setSourceSha256(OerSourceMetadata.originalSha256(bytes));
        identity(Arrays.asList("vetting","advisory"));catalog=new ArrayList<>(Collections.singletonList(document));
        documents=mock(SourceDocumentRepository.class);
        when(documents.findVerificationMetadataById(11L)).thenAnswer(call->Optional.of(CatalogMetadataFixtures.verification(document)));
        when(documents.findCatalogMetadataByProjectId("project-a")).thenAnswer(call->CatalogMetadataFixtures.from(catalog));
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());
        canonical=mock(CanonicalEvidenceService.class);
        service=new AdviceOriginalSourceService(mock(ProjectService.class),new CanonicalCurrentSourceReader(documents,mock(CanonicalSourceBindingRepository.class),properties),canonical,new StorageService(properties));
    }
    void identity(List<String> modules){
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(document.getSourceSha256());identity.setKind("SCC");
        identity.setSourcePurpose("project_document");identity.setRole("tender");identity.setIdentityStatus("content_supported");identity.setEligibleModules(modules);document.setOerIdentityJson(JsonUtils.write(identity));
    }
    AdviceOriginalSourceService.OriginalSource read(String scope){return service.original("project-a",11L,VettingCorpus.sourceHash(document),document.getSourceSha256(),scope,null,null);}
    @Test void actual_original_bytes_and_revision_are_returned_with_private_headers() throws Exception {
        assertArrayEquals(bytes,read("contract").getBytes());
        MockMvcBuilders.standaloneSetup(new AdviceOriginalSourceController(service)).build().perform(get("/api/advice/project-a/sources/11/original")
                .param("sourceHash",VettingCorpus.sourceHash(document)).param("originalSourceSha256",document.getSourceSha256()).param("scope","contract"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(header().string("Content-Type","application/pdf"))
                .andExpect(header().string("X-Source-SHA256",document.getSourceSha256())).andExpect(header().string("X-Source-Revision",VettingCorpus.sourceHash(document)))
                .andExpect(header().string("Cache-Control","private, no-store"));
    }
    @Test void advisory_scope_is_enforced_even_when_vetting_allows_document(){
        assertEquals("ADVICE_SOURCE_OUTSIDE_SCOPE",assertThrows(CanonicalEvidenceException.class,()->read("tender")).getMessage());
        identity(Collections.singletonList("vetting"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(CanonicalEvidenceException.class,()->read("fullset")).getStatus());
    }
    @Test void stale_revision_missing_raw_identity_and_changed_bytes_fail_closed() throws Exception {
        assertEquals("ADVICE_SOURCE_REVISION_MISMATCH",assertThrows(CanonicalEvidenceException.class,()->service.original("project-a",11L,"a".repeat(64),document.getSourceSha256(),"fullset",null,null)).getMessage());
        assertThrows(CanonicalEvidenceException.class,()->service.original("project-a",11L,VettingCorpus.sourceHash(document),null,"fullset",null,null));
        java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(file);byte[] replacement=bytes.clone();replacement[15]^=1;Files.write(file,replacement);Files.setLastModifiedTime(file,time);
        assertEquals("SOURCE_ORIGINAL_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,()->read("fullset")).getMessage());
    }
    @Test void old_same_name_upload_and_cross_project_id_never_alias(){
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(CanonicalEvidenceException.class,()->service.original("project-b",11L,VettingCorpus.sourceHash(document),document.getSourceSha256(),"fullset",null,null)).getStatus());
        SourceDocument newer=new SourceDocument();newer.setId(12L);newer.setProjectId("project-a");newer.setCategory(document.getCategory());newer.setFileName(document.getFileName());catalog.add(newer);
        assertEquals("SOURCE_DOCUMENT_SUPERSEDED",assertThrows(CanonicalEvidenceException.class,()->read("fullset")).getMessage());
    }
    @Test void drawing_pricing_and_evaluation_sources_cannot_use_original_reader(){
        for(String scope:Arrays.asList("drawing","pricing","example")){document.setDocumentScope(scope);assertEquals(HttpStatus.FORBIDDEN,assertThrows(CanonicalEvidenceException.class,()->read("fullset")).getStatus());}
    }
    @Test void template_category_remains_drafting_only(){
        document.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        assertEquals("SOURCE_TEMPLATE_DRAFTING_ONLY",assertThrows(CanonicalEvidenceException.class,()->read("fullset")).getMessage());
    }
    @Test void canonical_addresses_recheck_advisory_binding_and_do_not_downgrade(){
        String projection="a".repeat(64);EvidencePointer pointer=new EvidencePointer();pointer.setOriginalSourceSha256(document.getSourceSha256());pointer.setJavaParsedSourceHash(VettingCorpus.sourceHash(document));
        when(canonical.pointer("project-a",11L,projection,"unit-a","advisory")).thenReturn(pointer);
        service.original("project-a",11L,VettingCorpus.sourceHash(document),document.getSourceSha256(),"fullset",projection,"unit-a");
        verify(canonical,times(2)).pointer("project-a",11L,projection,"unit-a","advisory");
        when(canonical.pointer("project-a",11L,projection,"unit-a","advisory")).thenThrow(new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_ADOPTION_SUPERSEDED"));
        assertEquals("CANONICAL_ADOPTION_SUPERSEDED",assertThrows(CanonicalEvidenceException.class,()->service.original("project-a",11L,VettingCorpus.sourceHash(document),document.getSourceSha256(),"fullset",projection,"unit-a")).getMessage());
        assertEquals("ADVICE_SOURCE_BINDING_INCOMPLETE",assertThrows(CanonicalEvidenceException.class,()->service.original("project-a",11L,VettingCorpus.sourceHash(document),document.getSourceSha256(),"fullset",projection,null)).getMessage());
    }
    @Test void actual_http_boundary_reports_staleness_without_original_bytes() throws Exception {
        MockMvcBuilders.standaloneSetup(new AdviceOriginalSourceController(service)).build().perform(get("/api/advice/project-a/sources/11/original")
                .param("sourceHash","b".repeat(64)).param("originalSourceSha256",document.getSourceSha256()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("ADVICE_SOURCE_REVISION_MISMATCH"));
    }
}
