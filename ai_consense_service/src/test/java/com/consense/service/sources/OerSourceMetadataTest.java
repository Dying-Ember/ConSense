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
    @Test void pdfListObservationSurvivesDetailedParsingAndEntersReviewWithoutAnExplicitRole() throws Exception {
        for(String title:java.util.Arrays.asList("LIST OF CONTRACT DRAWINGS","LIST OF REFERENCE DRAWINGS AND DOCUMENTS")) {
            byte[] bytes;
            try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                org.apache.pdfbox.pdmodel.PDPage page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
                try(org.apache.pdfbox.pdmodel.PDPageContentStream content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                    content.beginText();content.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12);content.newLineAtOffset(40,700);content.showText(title);content.endText();
                }
                pdf.save(out);bytes=out.toByteArray();
            }
            com.consense.config.ConsenseProperties props=new com.consense.config.ConsenseProperties();props.getOcr().setTextLayerMinChars(1);
            DocumentParser parser=new DocumentParser(props,org.mockito.Mockito.mock(com.consense.ocr.OcrClient.class));
            SourceDocument d=document();d.setReviewRole(null);d.setDocumentScope(null);d.setParseStatus("PARSING");
            DocumentParser.ParsedDocument observation=parser.observe("anonymous.pdf",bytes);
            assertTrue(OerSourceMetadata.captureObservation(d,observation,bytes),title);
            assertEquals("tender",VettingCorpus.sourceRole(d));assertNull(d.getReviewRole());
            DocumentParser.ParsedDocument parsed=parser.parse("anonymous.pdf",bytes,observation,OerSourceMetadata.identity(d));
            OerSourceMetadata.capture(d,parsed,bytes);d.setTextContent(parsed.getText());d.setParseStatus(parsed.getParseStatus());
            assertNull(ProjectDocumentScope.exclusionReason(d,"fullset"));
            assertEquals(java.util.Collections.singletonList(d),ProjectDocumentScope.selected(d.getProjectId(),java.util.Collections.singletonList(d),"fullset"));
        }
    }
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

    @Test void reviewed_actual_2b_ntt_and_sct_refresh_source_purpose_without_recapturing_or_adopting_content() {
        // The reviewed original-byte evidence is private; these are metadata contracts, not content-quality claims.
        for(String[] source:new String[][]{
                {"NTT","0cae753f94bac27bfaa992b55f9ba60d8d541d407313825fd3375d1ff41c3356"},
                {"SCT","6d7802ea7b094124c0ddebb0b448c7e6bfe72828ffbc91ad1c30133902a6f64c"}}) {
            SourceDocument d=document();d.setFileName("renamed-unrelated-reference.pdf");d.setFileKey(source[0]);d.setSourceSha256(source[1]);
            d.setDocumentScope("package");d.setReviewRole("project_fact");d.setNativePdfEvidenceJson("AUTHORED retained evidence, not recaptured");
            d.setOerIdentityJson("AUTHORED old identity");String before=JsonUtils.write(d),parsedHash=VettingCorpus.sourceHash(d);
            OerDocumentIdentity next=OerSourceMetadata.refreshedFixedIdentity(d,source[1]);
            assertEquals(source[0],next.getKind());assertEquals(source[1],next.getSourceSha256());assertEquals("sha_verified",next.getIdentityStatus());
            assertEquals("project_document",next.getSourcePurpose());assertEquals("tender",next.getRole());
            assertTrue(next.getEligibleModules().containsAll(java.util.Arrays.asList("vetting","advisory")));
            assertEquals("fixed_source_sha256",next.getIdentityEvidence().get(0).getBasis());
            assertEquals(before,JsonUtils.write(d));assertEquals(parsedHash,VettingCorpus.sourceHash(d));
            assertEquals("project_fact",d.getReviewRole(),"Source identity refresh cannot replace an explicit human role");
            assertThrows(IllegalArgumentException.class,()->OerSourceMetadata.refreshedFixedIdentity(d,SHA));
        }
    }

    @Test void reviewed_2b_entries_do_not_promote_2a_templates_unregistered_bytes_or_body_references() {
        for(String[] source:new String[][]{
                {"NTT","60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c"},
                {"SCT","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4"},
                {"SCC","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"}}) {
            SourceDocument d=document();d.setFileName("actual-project-"+source[0]+".docx");d.setSourceSha256(source[1]);d.setReviewRole("tender");
            OerDocumentIdentity next=OerSourceMetadata.refreshedFixedIdentity(d,source[1]);
            assertEquals("standard_template",next.getSourcePurpose());assertEquals(java.util.Collections.singletonList("drafting"),next.getEligibleModules());
            d.setOerIdentityJson(JsonUtils.write(next));assertEquals("drafting_standard_template",OerSourceMetadata.moduleExclusion(d,"vetting"));assertEquals("drafting_standard_template",OerSourceMetadata.moduleExclusion(d,"advisory"));
        }
        SourceDocument unregistered=document();unregistered.setFileName("03 - NTT.docx");unregistered.setSourceSha256(SHA);
        OerDocumentIdentity unknown=OerSourceMetadata.refreshedFixedIdentity(unregistered,SHA);assertEquals("unknown",unknown.getSourcePurpose());assertNull(unknown.getEligibleModules());
        for(String text:java.util.Arrays.asList("NOTES TO TENDERERS\nContract No. __________", "NOTES TO TENDERERS\nReference to another project: \"Contract No. OTHER/2026\"")) {
            DocumentParser.ParsedDocument authored=new DocumentParser.ParsedDocument(text,java.util.Collections.emptyList(),0,false,"AUTHORED; no parser execution");
            OerDocumentIdentity candidate=new OerSourceRouting().route("03 - NTT.docx",OerSourceMetadata.originalSha256(text.getBytes(StandardCharsets.UTF_8)),authored);
            assertEquals("unknown",candidate.getSourcePurpose(),text);assertNull(candidate.getEligibleModules(),text);
        }
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

    @Test void failed_and_parsing_latest_sources_keep_scope_membership_without_becoming_parse_usable() {
        for(String status:java.util.Arrays.asList("FAILED","PARSING")) {
            SourceDocument old=document(),latest=document();latest.setId(18L);latest.setFileName(" RENAMED-DOCUMENT.PDF ");
            for(SourceDocument source:java.util.Arrays.asList(old,latest))
                OerSourceMetadata.capture(source,parsed(SHA),BYTES,routing("project_document","SCC","tender"));
            latest.setParseStatus(status);latest.setTextContent(null);
            for(String module:java.util.Arrays.asList("vetting","advisory")) {
                assertNull(ProjectDocumentScope.exclusionReason(latest,"contract",module),status);
                assertEquals(java.util.Collections.singletonList(latest),ProjectDocumentScope.selected(old.getProjectId(),java.util.Arrays.asList(old,latest),"contract",module),status);
            }
            assertFalse(ProjectDocumentScope.parseUsable(latest),status);
            for(String scope:java.util.Arrays.asList("drawing","pricing","example","works_pss")) {
                latest.setDocumentScope(scope);
                for(String module:java.util.Arrays.asList("vetting","advisory"))
                    assertTrue(ProjectDocumentScope.selected(old.getProjectId(),java.util.Arrays.asList(old,latest),"fullset",module).isEmpty(),status+" "+scope);
            }
            SourceDocument unknown=document();unknown.setId(19L);
            OerSourceMetadata.capture(unknown,parsed(SHA),BYTES,new OerSourceRouting());unknown.setParseStatus(status);
            assertEquals("source_identity_unresolved",ProjectDocumentScope.exclusionReason(unknown,"fullset"));
            for(String module:java.util.Arrays.asList("vetting","advisory"))
                assertTrue(ProjectDocumentScope.selected(old.getProjectId(),java.util.Arrays.asList(old,unknown),"fullset",module).isEmpty(),status);
            for(String[] excluded:new String[][]{{"standard_template","SCT","standard"},{"evaluation_answer","EVALUATION_ANSWER",null}}) {
                SourceDocument blocked=document();blocked.setId(20L);
                OerSourceMetadata.capture(blocked,parsed(SHA),BYTES,routing(excluded[0],excluded[1],excluded[2]));blocked.setParseStatus(status);
                for(String module:java.util.Arrays.asList("vetting","advisory"))
                    assertTrue(ProjectDocumentScope.selected(old.getProjectId(),java.util.Arrays.asList(old,blocked),"fullset",module).isEmpty(),status+" "+excluded[0]);
            }
        }
    }
}
