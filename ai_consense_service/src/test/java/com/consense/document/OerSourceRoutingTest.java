package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OerSourceRoutingTest {
    @Test void drawingListsAreReviewableTextAppendicesRatherThanDrawingFiles() {
        for(String title:Arrays.asList("LIST OF CONTRACT DRAWINGS", "LIST OF REFERENCE DRAWINGS AND DOCUMENTS", "LIST OF REFERENCE DRAWINGS/\nDOCUMENTS")) {
            DocumentParser.ParsedDocument source=parse("APPENDIX B\n"+title+"\n20250101");
            OerDocumentIdentity identity=new OerSourceRouting().route("anonymous.txt",source.getSourceSha256(),source);
            assertEquals("project_document",identity.getSourcePurpose(),title);
            assertEquals("tender",identity.getRole());
            assertTrue(identity.getEligibleModules().contains("vetting"));
        }
        for(String text:Arrays.asList("DRAWINGS", "This letter refers to LIST OF CONTRACT DRAWINGS", "Sample answer\nLIST OF CONTRACT DRAWINGS")) {
            DocumentParser.ParsedDocument source=parse(text);
            assertEquals("unknown",new OerSourceRouting().route("APB_List of Contract Dwg.pdf",source.getSourceSha256(),source).getSourcePurpose());
        }
    }
    private DocumentParser.ParsedDocument parse(String text) {
        return new DocumentParser(new ConsenseProperties(), mock(OcrClient.class)).parse("source.txt", text.getBytes(StandardCharsets.UTF_8));
    }
    private DocumentParser.ParsedDocument appendix(String title,String header,String body) throws Exception {
        try(org.apache.poi.xwpf.usermodel.XWPFDocument word=new org.apache.poi.xwpf.usermodel.XWPFDocument();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText(title);word.createParagraph().createRun().setText(body);
            if(header!=null) {org.apache.poi.xwpf.usermodel.XWPFHeader running=word.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
                for(String line:header.split("\n"))running.createParagraph().createRun().setText(line);}
            word.write(out);return new DocumentParser(new ConsenseProperties(),mock(OcrClient.class)).parse("unrelated.docx",out.toByteArray());
        }
    }
    @Test void nativeAppendixTitleOrHeaderWithAssessmentSubmissionRulesSupportsTenderUseWithoutAProjectNumber() throws Exception {
        String body="The tenderer shall submit technical proposals. Assessment standards and submission requirements determine the score.";
        for(DocumentParser.ParsedDocument source:Arrays.asList(
                appendix("Appendix A to Special Conditions of Tender Marking Scheme",null,body),
                appendix("Assessment Standards and Submission Requirements for Technical Proposals","Appendix A to\nSpecial Conditions of Tender",body))) {
            OerDocumentIdentity identity=new OerSourceRouting().route("anonymous-reference.docx",source.getSourceSha256(),source);
            assertEquals("SCT",identity.getKind());assertEquals("project_document",identity.getSourcePurpose());assertEquals("tender",identity.getRole());
            assertEquals("content_supported",identity.getIdentityStatus());assertTrue(identity.getEligibleModules().containsAll(Arrays.asList("vetting","advisory")));
            assertTrue(identity.getIdentityEvidence().stream().anyMatch(e->"native_tender_appendix".equals(e.getBasis())&&e.getLocation()!=null));
        }
    }
    @Test void nativeAppendixCuesDoNotOverrideExplicitPricingOrReferenceStandardTitles() throws Exception {
        String header="Appendix A to\nSpecial Conditions of Tender";
        String body="The tenderer shall submit technical proposals. Assessment standards and submission requirements determine the score. The bill amount, quantity and rate shall be stated.";
        DocumentParser.ParsedDocument pricing=appendix("BILLS OF QUANTITIES",header,body);
        OerDocumentIdentity bill=new OerSourceRouting().route("anonymous.docx",pricing.getSourceSha256(),pricing);
        assertEquals("BQ",bill.getKind());assertEquals("project_document",bill.getSourcePurpose());assertEquals(Collections.emptyList(),bill.getEligibleModules());
        assertTrue(bill.getIdentityEvidence().stream().noneMatch(e->"native_tender_appendix".equals(e.getBasis())));
        DocumentParser.ParsedDocument reference=appendix("GENERAL CONDITIONS OF CONTRACT",header,body);
        OerDocumentIdentity gcc=new OerSourceRouting().route("anonymous.docx",reference.getSourceSha256(),reference);
        assertEquals("GCC",gcc.getKind());assertEquals("reference_standard",gcc.getSourcePurpose());assertEquals("standard",gcc.getRole());
        assertTrue(gcc.getEligibleModules().containsAll(Arrays.asList("vetting","advisory")));
        assertTrue(gcc.getIdentityEvidence().stream().noneMatch(e->"native_tender_appendix".equals(e.getBasis())));
    }
    @Test void filenameReferencesUnboundPositionsAndNonOperativeHeadingsDoNotCreateTenderAppendixUse() throws Exception {
        String body="The tenderer shall submit technical proposals. Assessment standards and submission requirements determine the score.";
        for(DocumentParser.ParsedDocument source:Arrays.asList(
                appendix("Assessment Standards and Submission Requirements for Technical Proposals",null,body),
                appendix("This letter refers to Appendix A to Special Conditions of Tender",null,body),
                appendix("Appendix A to Special Conditions of Tender Marking Scheme",null,"Background information only."),
                appendix("Sample answer","Appendix A to\nSpecial Conditions of Tender",body))) {
            OerDocumentIdentity identity=new OerSourceRouting().route("SCT_A_Marking_Scheme_assessment_std.docx",source.getSourceSha256(),source);
            assertEquals("unknown",identity.getSourcePurpose());assertNull(identity.getEligibleModules());
        }
        DocumentParser.ParsedDocument missingBinding=appendix("Appendix A to Special Conditions of Tender Marking Scheme",null,body);
        missingBinding.getBlocks().forEach(b->b.setSourcePosition(null));
        assertEquals("unknown",new OerSourceRouting().route("SCT.docx",missingBinding.getSourceSha256(),missingBinding).getSourcePurpose());
        DocumentParser.ParsedDocument wrongBinding=appendix("Appendix A to Special Conditions of Tender Marking Scheme",null,body);
        wrongBinding.getBlocks().forEach(b->{if(b.getSourcePosition()!=null)((com.fasterxml.jackson.databind.node.ObjectNode)b.getSourcePosition()).put("sourceSha256",String.join("",Collections.nCopies(64,"a")));});
        assertEquals("unknown",new OerSourceRouting().route("SCT.docx",wrongBinding.getSourceSha256(),wrongBinding).getSourcePurpose());
    }
    @Test void exactEvaluationAnswerIdentityOverridesNativeAppendixLikeText() throws Exception {
        DocumentParser.ParsedDocument source=appendix("Appendix A to Special Conditions of Tender Marking Scheme",null,"The tenderer shall submit technical proposals. Assessment standards and submission requirements determine the score.");
        OerSourceRouting router=new OerSourceRouting(Collections.singletonList(new OerSourceRouting.FixedSource(source.getSourceSha256(),"EVALUATION_ANSWER","evaluation_answer",null,"authored-isolated-answer")));
        OerDocumentIdentity identity=router.route("SCT.docx",source.getSourceSha256(),source);
        assertEquals("evaluation_answer",identity.getSourcePurpose());assertEquals(Collections.emptyList(),identity.getEligibleModules());assertNull(identity.getRole());
    }

    private DocumentParser.ParsedDocument nativeProjectDeclaration(String title, String declaration, String footer) throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument word = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText(title);
            if (declaration != null) word.createParagraph().createRun().setText(declaration);
            if (footer != null) {
                org.apache.poi.xwpf.usermodel.XWPFFooter running = word.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
                org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = running.createParagraph();
                paragraph.createRun().setText(footer); paragraph.getCTP().addNewFldSimple().setInstr("PAGE");
            }
            word.write(out);
            return new DocumentParser(new ConsenseProperties(), mock(OcrClient.class)).parse("unrelated.docx", out.toByteArray());
        }
    }
    @Test void sourceBoundNativeContractDefinitionAcceptsOnlyTheBoundedDefinitionSuffix() throws Exception {
        for (String declaration : Arrays.asList("Contract No. 20250101 (the “Contract”)", "Contract Number: TEST/2046/02 (the \"Contract\")")) {
            DocumentParser.ParsedDocument source = nativeProjectDeclaration("SPECIAL CONDITIONS OF TENDER", declaration, null);
            OerDocumentIdentity identity = new OerSourceRouting().route("unrelated.docx", source.getSourceSha256(), source);
            assertEquals("SCT", identity.getKind()); assertEquals("project_document", identity.getSourcePurpose());
            assertTrue(identity.getEligibleModules().containsAll(Arrays.asList("vetting", "advisory")));
            OerDocumentIdentity retained = new OerSourceRouting().routeRetained("unrelated.docx", source.getSourceSha256(), source.getText(), source.getBlocks());
            assertEquals(identity.getKind(), retained.getKind()); assertEquals(identity.getSourcePurpose(), retained.getSourcePurpose());
            assertEquals(identity.getEligibleModules(), retained.getEligibleModules());
            assertTrue(identity.getIdentityEvidence().stream().anyMatch(e -> "native_contract_definition".equals(e.getBasis()) && e.getLocation() != null));
            assertTrue(retained.getIdentityEvidence().stream().anyMatch(e -> "native_contract_definition".equals(e.getBasis()) && e.getLocation() != null));
        }
    }
    @Test void nativeContractDefinitionRequiresOriginalPartAndNodeBinding() throws Exception {
        for (String defect : Arrays.asList("missing", "sourceSha256", "partSha256", "nodePath", "partName", "location")) {
            DocumentParser.ParsedDocument source = nativeProjectDeclaration("SPECIAL CONDITIONS OF TENDER", "Contract No. 20250101 (the “Contract”)", null);
            for (DocumentBlock block : source.getBlocks()) {
                if ("missing".equals(defect)) block.setSourcePosition(null);
                else if ("location".equals(defect)) block.setLocation(null);
                else if (block.getSourcePosition() != null) {
                    com.fasterxml.jackson.databind.node.ObjectNode position = (com.fasterxml.jackson.databind.node.ObjectNode) block.getSourcePosition();
                    position.put(defect, "sourceSha256".equals(defect) ? String.join("", Collections.nCopies(64, "a"))
                            : "partName".equals(defect) ? "word/footer1.xml" : "");
                }
            }
            assertEquals("unknown", new OerSourceRouting().route("SCT.docx", source.getSourceSha256(), source).getSourcePurpose(), defect);
        }
    }
    @Test void nativeExternalMentionsPlaceholdersAndBareFooterFurnitureDoNotSupplyProjectIdentity() throws Exception {
        for (String declaration : Arrays.asList("For preparation of a tender for Contract No. 20250101 (the “Contract”)",
                "(Contract No. 20250101)", "Contract No. [Contract Number] (the “Contract”)", "Contract No. unknown (the “Contract”)",
                "Contract No. 20250101 (the “Contract”) is only an external example.")) {
            DocumentParser.ParsedDocument source = nativeProjectDeclaration("SPECIAL CONDITIONS OF TENDER", declaration, null);
            assertEquals("unknown", new OerSourceRouting().route("SCT.docx", source.getSourceSha256(), source).getSourcePurpose(), declaration);
        }
        DocumentParser.ParsedDocument ntt = nativeProjectDeclaration("NOTES TO TENDERERS", "Tender submission requirements", "20250101- NTT/10 -");
        assertEquals("unknown", new OerSourceRouting().route("NTT.docx", ntt.getSourceSha256(), ntt).getSourcePurpose());
        DocumentParser.ParsedDocument footerOnly = nativeProjectDeclaration("SPECIAL CONDITIONS OF TENDER", "Tender submission requirements", "Contract No. 20250101 (the “Contract”)");
        assertEquals("unknown", new OerSourceRouting().route("SCT.docx", footerOnly.getSourceSha256(), footerOnly).getSourcePurpose());
    }
    @Test void answerHeadingsAndExactTemplatePurposeCannotBecomeProjectsFromContractDeclarations() throws Exception {
        for (String heading : Arrays.asList("Sample answer", "Model report", "Comments on Tender Documentation", "Checklist for review", "Contractual advice")) {
            for (String number : Arrays.asList("Contract No. 20250101", "Contract No. 20250101 (the “Contract”)")) {
                DocumentParser.ParsedDocument source = nativeProjectDeclaration(heading + "\nSPECIAL CONDITIONS OF TENDER", number, null);
                assertEquals("unknown", new OerSourceRouting().route("SCT.docx", source.getSourceSha256(), source).getSourcePurpose(), heading + ": " + number);
            }
        }
        DocumentParser.ParsedDocument template = nativeProjectDeclaration("SPECIAL CONDITIONS OF TENDER", "Contract No. 20250101 (the “Contract”)", null);
        OerSourceRouting router = new OerSourceRouting(Collections.singletonList(new OerSourceRouting.FixedSource(template.getSourceSha256(), "SCT", "standard_template", "standard", "authored-fixture")));
        OerDocumentIdentity identity = router.route("SCT.docx", template.getSourceSha256(), template);
        assertEquals("standard_template", identity.getSourcePurpose()); assertEquals(Collections.singletonList("drafting"), identity.getEligibleModules());
    }

    @Test void exactTrustedTemplateIdentityOverridesMisleadingFilenameAndRetainsDraftingOnly() {
        DocumentParser.ParsedDocument source = parse("Notice to Tenderers\nUnfilled reference template");
        OerSourceRouting router = new OerSourceRouting(Collections.singletonList(new OerSourceRouting.FixedSource(source.getSourceSha256(), "NTT", "standard_template", "standard", "v1")));
        OerDocumentIdentity identity = router.route("project-GCC.pdf", source.getSourceSha256(), source);
        assertEquals("NTT", identity.getKind()); assertEquals("sha_verified", identity.getIdentityStatus());
        assertEquals("standard_template", identity.getSourcePurpose()); assertEquals(Collections.singletonList("drafting"), identity.getEligibleModules());
    }
    @Test void actualProjectNttAllowsReviewAndAdvice() {
        DocumentParser.ParsedDocument source = parse("NOTICE TO TENDERERS\nContract No: TEST/2046/02\nTender submission requirements");
        OerDocumentIdentity identity = new OerSourceRouting().route("NTT.docx", source.getSourceSha256(), source);
        assertEquals("project_document", identity.getSourcePurpose()); assertTrue(identity.getEligibleModules().containsAll(Arrays.asList("vetting", "advisory")));
    }
    @Test void nativeContractNumberWithoutColonIdentifiesTheProjectButNotAnUnfilledOrCrossLineValue() {
        for(String label:Arrays.asList("CONTRACT NO. 20250101","Contract No: 20250101","Contract Number # 20250101")) {
            String text="APPENDIX G305\nDeclaration of Compliance with Confidentiality\n"+label+"\nConstruction of Public Housing Development";
            DocumentParser.ParsedDocument observed=new DocumentParser(new ConsenseProperties(),mock(OcrClient.class)).observe("source.txt",text.getBytes(StandardCharsets.UTF_8));
            OerDocumentIdentity identity=new OerSourceRouting().route("source.txt",observed.getSourceSha256(),observed);
            assertEquals("project_document",identity.getSourcePurpose(),label);assertEquals("content_supported",identity.getIdentityStatus());
            assertTrue(identity.getEligibleModules().containsAll(Arrays.asList("vetting","advisory")));
        }
        for(String value:Arrays.asList("not provided","to be completed","insert number","unknown","n/a","________","[Contract Number]","\nConstruction of Public Housing Development")) {
            DocumentParser.ParsedDocument source=parse("NOTICE TO TENDERERS\nContract No. "+value+"\nTender submission requirements");
            OerDocumentIdentity identity=new OerSourceRouting().route("source.docx",source.getSourceSha256(),source);
            assertEquals("unknown",identity.getSourcePurpose(),value);assertNull(identity.getEligibleModules(),value);
        }
    }
    @Test void noteNotesAndNoticeTitlesShareTheNttFamilyWithoutPromotingBodyReferences() {
        for(String title:Arrays.asList("Notes to Tenderers","Note to Tenderer","Notice to Tenderers")) {
            DocumentParser.ParsedDocument source=parse(title+"\nContract No: TEST/2046/02\nTender requirements");
            OerDocumentIdentity identity=new OerSourceRouting().route("anonymous.pdf",source.getSourceSha256(),source);
            assertEquals("NTT",identity.getKind());assertEquals("project_document",identity.getSourcePurpose());
        }
        DocumentParser.ParsedDocument reference=parse("This source refers to Notes to Tenderers for background.\nUnrelated material.");
        OerDocumentIdentity unknown=new OerSourceRouting().route("NTT.pdf",reference.getSourceSha256(),reference);
        assertEquals("UNKNOWN",unknown.getKind());assertEquals("unknown",unknown.getIdentityStatus());assertNull(unknown.getEligibleModules());
    }
    @Test void splitParticularSpecificationCoverDoesNotExcludeProjectPre() {
        DocumentParser.ParsedDocument source = parse("PARTICULAR SPECIFICATION\nPRELIMINARIES\nContract No: TEST/2046/02\nMaterials and workmanship");
        OerDocumentIdentity identity = new OerSourceRouting().route("PRE.docx", source.getSourceSha256(), source);
        assertEquals("PRE", identity.getKind()); assertEquals("project_document", identity.getSourcePurpose()); assertTrue(identity.getEligibleModules().contains("vetting"));
    }
    @Test void pricingAndTechnicalPssRequireTitleEvidenceBeforeExclusion() {
        for (String title : Arrays.asList("BILLS OF QUANTITIES", "GENERAL SUMMARY", "PARTICULAR SPECIFICATION - STRUCTURAL WORKS")) {
            DocumentParser.ParsedDocument source = parse(title + "\nContract No: TEST/2046/02\nMaterials construction price quantity amount total");
            assertEquals(Collections.emptyList(), new OerSourceRouting().route("source.pdf", source.getSourceSha256(), source).getEligibleModules());
        }
        DocumentParser.ParsedDocument body = parse("NOTICE TO TENDERERS\nContract No: TEST/2046/02\nThe General Summary and Bills of Quantities shall be submitted separately.");
        assertEquals("NTT", new OerSourceRouting().route("source.pdf", body.getSourceSha256(), body).getKind());
    }
    @Test void filenameAndBodyKeywordAloneStayUnresolvedAndRetainCandidates() {
        DocumentParser.ParsedDocument source = parse("The notice to tenderers refers to the general summary.\nUnrelated descriptive material.");
        OerDocumentIdentity identity = new OerSourceRouting().route("NTT.docx", source.getSourceSha256(), source);
        assertEquals("UNKNOWN", identity.getKind()); assertNull(identity.getEligibleModules()); assertFalse(identity.getCandidates().isEmpty());
    }
    @Test void unfilledTitleAndPlaceholderProjectNumberDoNotProveTemplateOrProject() {
        DocumentParser.ParsedDocument source = parse("NOTICE TO TENDERERS\nContract No: not provided\nTender submission requirements");
        OerDocumentIdentity identity = new OerSourceRouting().route("NTT.docx", source.getSourceSha256(), source);
        assertEquals("unknown", identity.getSourcePurpose()); assertNull(identity.getEligibleModules());
        assertTrue(identity.getCandidates().stream().anyMatch(c -> "standard_template".equals(c.getSourcePurpose())));
    }
    @Test void wrongOriginalByteIdentityFailsClosed() {
        DocumentParser.ParsedDocument source = parse("GENERAL CONDITIONS OF CONTRACT\nConditions");
        OerDocumentIdentity identity = new OerSourceRouting().route("GCC.pdf", String.join("", Collections.nCopies(64, "a")), source);
        assertEquals("source_identity_mismatch", identity.getIdentityStatus()); assertNull(identity.getEligibleModules());
    }
    @Test void gccReferenceRetainedAndInvalidTemplateRegistryRejected() {
        DocumentParser.ParsedDocument source = parse("GENERAL CONDITIONS OF CONTRACT\nStandard edition 2026");
        OerDocumentIdentity identity = new OerSourceRouting().route("GCC.pdf", source.getSourceSha256(), source);
        assertEquals("reference_standard", identity.getSourcePurpose()); assertEquals("standard", identity.getRole());
        assertTrue(identity.getEligibleModules().contains("advisory"));
        assertThrows(IllegalArgumentException.class, () -> new OerSourceRouting(Collections.singletonList(new OerSourceRouting.FixedSource(source.getSourceSha256(), "GCC", "standard_template", "standard", "v1"))));
    }
}
