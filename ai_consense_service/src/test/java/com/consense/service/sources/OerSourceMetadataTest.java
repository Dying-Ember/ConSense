package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser;
import com.consense.document.DocumentBlock;
import com.consense.document.OerDocumentIdentity;
import com.consense.document.OerSourceRouting;
import com.consense.document.PdfPageEvidence;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Authored metadata contracts; no real corpus parse or human/source-quality acceptance. */
class OerSourceMetadataTest {
    private static final byte[] BYTES="AUTHORED-original-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String SHA=OerSourceMetadata.originalSha256(BYTES);
    private SourceDocument document() {
        SourceDocument d=new SourceDocument(); d.setId(17L); d.setProjectId("AUTHORED-project");
        d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE); d.setFileName("renamed-document.pdf");
        d.setTextContent("A native paragraph"); d.setParseStatus("PARSED"); return d;
    }
    private OerSourceRouting routing(String purpose,String kind,String role) {
        return new OerSourceRouting(java.util.Arrays.asList(new OerSourceRouting.FixedSource(SHA,kind,purpose,role,"AUTHORED-version")));
    }
    private DocumentParser.ParsedDocument parsed(String sha) {
        DocumentParser.ParsedDocument old=new DocumentParser.ParsedDocument("A native paragraph",java.util.Arrays.asList(),1,false,"AUTHORED");
        DocumentBlock block=new DocumentBlock(); block.setId("body:1"); block.setLocation("page/1");
        block.setKind("paragraph"); block.setSource("pdf-native"); block.setText("A native paragraph"); block.setPageNo(1);
        PdfPageEvidence evidence=new PdfPageEvidence(); evidence.setPageNo(1); evidence.setNativeBlocks(java.util.Arrays.asList(block));
        return new DocumentParser.ParsedDocument(old.getText(),old.getPages(),1,false,old.getMessage(),
                java.util.Arrays.asList(block),old.getCoverage(),sha,java.util.Arrays.asList(evidence));
    }

    @Test void stores_original_sha_and_native_candidates_without_replacing_canonical_hash_or_explicit_role() {
        SourceDocument d=document(); d.setReviewRole("project_fact");
        DocumentParser.ParsedDocument p=parsed(SHA); d.setStructuredContentJson(JsonUtils.write(p.getBlocks()));
        String textHash=VettingCorpus.sourceHash(d);
        OerSourceMetadata.capture(d,p,BYTES,routing("reference_standard","GCC","standard"));
        assertEquals(SHA,d.getSourceSha256()); assertNotEquals(textHash,d.getSourceSha256());
        assertEquals(textHash,VettingCorpus.sourceHash(d)); assertEquals("GCC",d.getFileKey());
        assertEquals("project_fact",VettingCorpus.sourceRole(d));
        assertEquals(1,JsonUtils.readList(d.getNativePdfEvidenceJson(),PdfPageEvidence.class).get(0).getNativeBlocks().size());
        VettingCorpus.Chunk chunk=VettingCorpus.chunks(java.util.Arrays.asList(d)).get(0);
        assertEquals(SHA,chunk.getOriginalSourceSha256()); assertEquals(SHA,chunk.getSourceSha256());
        assertEquals("reference_standard",chunk.getSourcePurpose()); assertEquals(textHash,chunk.getSourceHash());
    }

    @Test void exact_template_identity_excludes_renamed_review_upload_but_remains_available_to_drafting() {
        SourceDocument d=document();
        OerSourceMetadata.capture(d,parsed(SHA),BYTES,routing("standard_template","SCT","standard"));
        assertEquals("drafting_standard_template",ProjectDocumentScope.exclusionReason(d,"fullset"));
        assertEquals("drafting_standard_template",ProjectDocumentScope.exclusionReason(d,"fullset","advisory"));
        assertNull(OerSourceMetadata.moduleExclusion(d,"drafting"));
        assertTrue(ProjectDocumentScope.selected(d.getProjectId(),java.util.Arrays.asList(d),"fullset").isEmpty());
    }

    @Test void project_sct_and_pre_remain_eligible_and_known_answers_are_isolated_despite_renaming() {
        for(String kind:java.util.Arrays.asList("SCT","PRE")) {
            SourceDocument d=document(); OerSourceMetadata.capture(d,parsed(SHA),BYTES,routing("project_document",kind,"tender"));
            assertNull(ProjectDocumentScope.exclusionReason(d,"fullset"));
            assertEquals(1,ProjectDocumentScope.selected(d.getProjectId(),java.util.Arrays.asList(d),"fullset","advisory").size());
        }
        SourceDocument answer=document();answer.setReviewRole("tender");answer.setDocumentScope("package");
        OerSourceMetadata.capture(answer,parsed(SHA),BYTES,routing("evaluation_answer","EVALUATION_ANSWER",null));
        assertEquals("evaluation_answer_isolated",ProjectDocumentScope.exclusionReason(answer,"fullset"));
        assertEquals(java.util.Arrays.asList(),OerSourceMetadata.identity(answer).getEligibleModules());
    }

    @Test void trustedKnownAnswerExclusionIsPersistedBeforeAnyParserObservation() {
        SourceDocument answer=document();answer.setReviewRole("tender");answer.setDocumentScope("package");
        assertTrue(OerSourceMetadata.captureKnownIdentity(answer,BYTES,routing("evaluation_answer","EVALUATION_ANSWER",null)));
        assertEquals("sha_verified",OerSourceMetadata.identity(answer).getIdentityStatus());
        assertEquals("evaluation_answer_isolated",ProjectDocumentScope.exclusionReason(answer,"fullset"));
        assertNull(answer.getNativePdfEvidenceJson());
        assertEquals(SHA,answer.getSourceSha256());
    }
    @Test void unidentified_source_keeps_unknown_eligibility_and_original_identity() {
        SourceDocument d=document(); OerSourceMetadata.capture(d,parsed(SHA),BYTES,new OerSourceRouting());
        assertEquals("unknown",OerSourceMetadata.identity(d).getSourcePurpose());
        assertNull(OerSourceMetadata.identity(d).getEligibleModules());
        assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(d,"fullset"));
        assertEquals(1,ProjectDocumentScope.currentCatalog(d.getProjectId(),java.util.Arrays.asList(d)).size());
        assertTrue(ProjectDocumentScope.selected(d.getProjectId(),java.util.Arrays.asList(d),"fullset").isEmpty());
        assertEquals("A native paragraph",d.getTextContent());
        assertEquals(SHA,d.getSourceSha256());
    }

    @Test void incorrect_parser_original_identity_and_persisted_identity_fail_closed() {
        SourceDocument d=document();
        assertThrows(IllegalArgumentException.class,()->OerSourceMetadata.capture(d,parsed(String.join("",java.util.Collections.nCopies(64,"a"))),BYTES,new OerSourceRouting()));
        OerSourceMetadata.capture(d,parsed(SHA),BYTES,new OerSourceRouting()); d.setSourceSha256(String.join("",java.util.Collections.nCopies(64,"b")));
        assertThrows(IllegalArgumentException.class,()->ProjectDocumentScope.exclusionReason(d,"fullset"));
    }

    @Test void module_selection_uses_each_modules_explicit_eligibility() {
        SourceDocument d=document(); OerSourceMetadata.capture(d,parsed(SHA),BYTES,new OerSourceRouting());
        OerDocumentIdentity identity=OerSourceMetadata.identity(d); identity.setEligibleModules(java.util.Arrays.asList("advisory"));d.setReviewRole("tender");
        d.setOerIdentityJson(JsonUtils.write(identity));
        assertEquals(0,ProjectDocumentScope.selected(d.getProjectId(),java.util.Arrays.asList(d),"fullset").size());
        assertEquals(1,ProjectDocumentScope.selected(d.getProjectId(),java.util.Arrays.asList(d),"fullset","advisory").size());
    }

    @Test void legacy_parser_result_does_not_inherit_previous_upload_identity() {
        SourceDocument d=document(); OerSourceMetadata.capture(d,parsed(SHA),BYTES,new OerSourceRouting());
        OerSourceMetadata.captureParsed(d,new DocumentParser.ParsedDocument("legacy",java.util.Arrays.asList(),1,false,"AUTHORED"));
        assertNull(d.getSourceSha256()); assertNull(d.getOerIdentityJson());
    }

    @Test void routing_refresh_returns_exact_project_scc_identity_without_mutating_any_retained_field() {
        SourceDocument d=document();String sha="8ba80d183f6adf7a11eeb08f75e32c2bffdc3960f969202fcbed357e477f7af3";
        d.setSourceSha256(sha);d.setFileKey("SCC");d.setNativePdfEvidenceJson("retained archive, not recaptured");
        d.setOerIdentityJson("retained old routing");d.setReviewRole("project_fact");
        String before=JsonUtils.write(d);
        OerDocumentIdentity next=OerSourceMetadata.refreshedFixedIdentity(d,sha);
        assertEquals("SCC",next.getKind());assertEquals("project_document",next.getSourcePurpose());
        assertEquals("sha_verified",next.getIdentityStatus());assertTrue(next.getEligibleModules().containsAll(java.util.Arrays.asList("vetting","advisory")));
        assertEquals(before,JsonUtils.write(d));
        assertThrows(IllegalArgumentException.class,()->OerSourceMetadata.refreshedFixedIdentity(d,String.join("",java.util.Collections.nCopies(64,"a"))));
    }

    @Test void filename_and_display_keys_cannot_exclude_unknown_source_or_confirm_its_role() {
        for(String name:java.util.Arrays.asList("checklist.pdf","General Summary.pdf","PSS Concrete.pdf","meeting minutes report.pdf","GCC discussion.eml","APA.pdf","APC.pdf")) {
            SourceDocument d=document();d.setDocumentScope("package");d.setFileName(name);d.setFileKey("BQ");
            OerSourceMetadata.capture(d,parsed(SHA),BYTES,new OerSourceRouting());
            assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(d,"fullset"),name);
            assertEquals("unknown",VettingCorpus.sourceRole(d),name);
            assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(d,"tender"),name);
            assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(d,"contract"),name);
            d.setReviewRole("tender");
            assertNull(ProjectDocumentScope.exclusionReason(d,"fullset"),name);
        }
    }

    @Test void confirmed_identity_and_explicit_scope_survive_misleading_filename_and_display_key() {
        SourceDocument d=document();d.setFileName("PSS checklist General Summary.pdf");
        OerSourceMetadata.capture(d,parsed(SHA),BYTES,routing("project_document","NTT","tender"));
        d.setFileKey("BQ");
        assertNull(ProjectDocumentScope.exclusionReason(d,"fullset"));
        assertNull(ProjectDocumentScope.exclusionReason(d,"tender"));
        assertEquals("outside_contract_scope",ProjectDocumentScope.exclusionReason(d,"contract"));
        for(String[] boundary:new String[][]{{"example","example_answer"},{"pricing","pricing_document"},{"works_pss","project_works_specification"}}) {
            d.setDocumentScope(boundary[0]);
            assertEquals(boundary[1],ProjectDocumentScope.exclusionReason(d,"fullset"));
        }
    }

    @Test void identity_pending_remains_outside_business_selection_even_with_explicit_tender_role() {
        SourceDocument d=document();d.setReviewRole("tender");d.setDocumentScope("package");
        OerSourceMetadata.capture(d,parsed(SHA),BYTES,new OerSourceRouting());
        assertNull(ProjectDocumentScope.exclusionReason(d,"fullset"));
        d.setParseStatus("IDENTITY_PENDING");
        assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(d,"fullset"));
        assertTrue(ProjectDocumentScope.selected(d.getProjectId(),java.util.Arrays.asList(d),"fullset").isEmpty());
        assertEquals(1,ProjectDocumentScope.currentCatalog(d.getProjectId(),java.util.Arrays.asList(d)).size());
        assertFalse(ProjectDocumentScope.parseUsable(d));
        assertEquals("A native paragraph",d.getTextContent());
    }
}
