package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser;
import com.consense.document.OerDocumentIdentity;
import com.consense.document.OerSourceRouting;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/** Persist original identity and native candidates without accepting OCR or changing explicit user roles. */
public final class OerSourceMetadata {
    private OerSourceMetadata() {}

    private static class FixedRegistry {
        private static final OerSourceRouting ROUTER = load();
        private static OerSourceRouting load() {
            try (InputStream stream = OerSourceMetadata.class.getResourceAsStream("/oer-fixed-sources.json")) {
                if (stream == null) throw new IllegalStateException("Missing frozen OER source registry");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int size;
                while((size=stream.read(buffer))!=-1)bytes.write(buffer,0,size);
                JsonNode registry = JsonUtils.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (!"consense.fixed-source-routing.v1".equals(registry.path("schema").asText())
                        || !"exact_original_file_bytes_sha256".equals(registry.path("identityMeaning").asText())
                        || !registry.path("sources").isArray()) throw new IllegalStateException("Invalid frozen OER source registry");
                List<OerSourceRouting.FixedSource> sources = new ArrayList<>();
                for (JsonNode source : registry.get("sources")) sources.add(new OerSourceRouting.FixedSource(
                        source.path("sourceSha256").asText(), source.path("kind").asText(),
                        source.path("sourcePurpose").asText(), source.path("role").isNull() ? null : source.path("role").asText(),
                        source.path("version").asText()));
                return new OerSourceRouting(sources);
            } catch (Exception failure) { throw new IllegalStateException("Cannot load frozen OER source registry", failure); }
        }
    }

    public static void capture(SourceDocument document, DocumentParser.ParsedDocument parsed, byte[] originalBytes) {
        capture(document, parsed, originalBytes, FixedRegistry.ROUTER);
    }

    /** Retain the uploaded original and its exclusion identity without producing parser evidence. */
    public static void captureDrawing(SourceDocument document, byte[] originalBytes) {
        String digest = originalSha256(originalBytes);
        if (document.getSourceSha256() != null && !digest.equals(document.getSourceSha256()))
            throw new IllegalArgumentException("Drawing source bytes changed before identity was persisted");
        OerDocumentIdentity identity = new OerDocumentIdentity();
        identity.setSourceSha256(digest); identity.setKind("DRAWING"); identity.setSourcePurpose("drawing");
        identity.setIdentityStatus("drawing".equals(document.getDocumentScope()) ? "explicit_scope" : "format_supported");
        identity.setEligibleModules(java.util.Collections.emptyList());
        identity.getIdentityEvidence().add(new OerDocumentIdentity.Evidence(
                "drawing".equals(document.getDocumentScope()) ? "upload_document_scope" : "native_drawing_format",
                null, "Drawing file is outside parsing and business evidence scope; no detail parser was called"));
        document.setSourceSha256(digest); document.setFileKey("DRAWING");
        document.setOerIdentityJson(JsonUtils.write(identity));
    }

    /** A trusted exact-byte exclusion must be known before any producer sees evaluation/template content. */
    public static boolean captureKnownIdentity(SourceDocument document, byte[] originalBytes) {
        return captureKnownIdentity(document, originalBytes, FixedRegistry.ROUTER);
    }
    static boolean captureKnownIdentity(SourceDocument document, byte[] originalBytes, OerSourceRouting router) {
        capture(document, null, originalBytes, router);
        return "sha_verified".equals(identity(document).getIdentityStatus());
    }

    /** Persist first-pass identity separately from full parser completion. */
    public static boolean captureObservation(SourceDocument document, DocumentParser.ParsedDocument observation, byte[] originalBytes) {
        capture(document, observation, originalBytes);
        OerDocumentIdentity identity = identity(document);
        List<OerDocumentIdentity.Evidence> hints = OerSourceRouting.drawingMetadata(originalBytes);
        for (OerDocumentIdentity.Evidence hint : hints) {
            identity.getIdentityEvidence().add(hint);
            identity.getCandidates().add(new OerDocumentIdentity.Candidate("DRAWING", "unknown", hint.getLocation() + ": " + hint.getReason()));
        }
        document.setOerIdentityJson(JsonUtils.write(identity));
        boolean readable = java.util.Arrays.asList("sha_verified", "content_supported", "content_supported_non_drawing", "format_supported_non_drawing")
                .contains(identity.getIdentityStatus()) && !"DRAWING".equals(identity.getKind());
        if (!readable) document.setParseStatus("IDENTITY_PENDING");
        return readable;
    }

    /** The parser binds its own input bytes; legacy mock results cannot inherit old upload identities. */
    public static void captureParsed(SourceDocument document, DocumentParser.ParsedDocument parsed) {
        String digest=parsed==null?null:parsed.getSourceSha256();
        if(digest==null) {
            document.setSourceSha256(null); document.setOerIdentityJson(null);
            document.setNativePdfEvidenceJson(parsed==null?null:JsonUtils.write(parsed.getNativePdfEvidence()));
            return;
        }
        persist(document,parsed,digest,FixedRegistry.ROUTER);
    }

    public static void capture(SourceDocument document, DocumentParser.ParsedDocument parsed,
                               byte[] originalBytes, OerSourceRouting router) {
        String digest = originalSha256(originalBytes);
        if (document.getSourceSha256() != null && !digest.equals(document.getSourceSha256()))
            throw new IllegalArgumentException("Uploaded source identity changed before parsed metadata was persisted");
        persist(document,parsed,digest,router);
    }

    private static void persist(SourceDocument document, DocumentParser.ParsedDocument parsed,
                                String digest, OerSourceRouting router) {
        OerDocumentIdentity identity = router.route(document.getFileName(), digest, parsed);
        OerDocumentIdentity previous = document.getOerIdentityJson() == null ? null : identity(document);
        if (previous != null && digest.equals(previous.getSourceSha256())) {
            for (OerDocumentIdentity.Evidence evidence : previous.getIdentityEvidence())
                if ("pdf_metadata_candidate".equals(evidence.getBasis())) identity.getIdentityEvidence().add(evidence);
            for (OerDocumentIdentity.Candidate candidate : previous.getCandidates())
                if ("DRAWING".equals(candidate.getKind())) identity.getCandidates().add(candidate);
            if ("unknown".equals(identity.getIdentityStatus()) && java.util.Arrays.asList("content_supported_non_drawing", "format_supported_non_drawing").contains(previous.getIdentityStatus()))
                identity.setIdentityStatus(previous.getIdentityStatus());
        }
        if ("source_identity_mismatch".equals(identity.getIdentityStatus()))
            throw new IllegalArgumentException("Parsed metadata belongs to different source bytes");
        identity=retainTextContractConfirmation(document,identity);
        document.setSourceSha256(digest);
        document.setOerIdentityJson(JsonUtils.write(identity));
        document.setNativePdfEvidenceJson(parsed == null || parsed.getNativePdfEvidence() == null ? null
                : JsonUtils.write(parsed.getNativePdfEvidence()));
        if (!SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(document.getCategory())
                && !"UNKNOWN".equals(identity.getKind()) && !"EVALUATION_ANSWER".equals(identity.getKind()))
            document.setFileKey(identity.getKind());
        // reviewRole is the user's explicit input; the routed role is retained
        // separately and used only when there is no valid explicit role.
    }

    /** Explicit role plus verified textual identity grants module use, never family, edition or clause ownership. */
    public static void confirmSourceUse(SourceDocument document,boolean textMaterialConfirmed) {
        OerDocumentIdentity identity=identity(document);
        if(!sourceUseAllowed(document,identity)||!textMaterialConfirmed&&!observedNonDrawing(identity)&&!hasSourceUseConfirmation(identity,document.getSourceSha256()))
            throw new IllegalArgumentException("Source-use confirmation cannot override missing textual identity, exclusions or incomplete parsing");
        grantSourceUse(document,identity,textMaterialConfirmed?"human_text_contract_material":"human_source_role",null);
        document.setOerIdentityJson(JsonUtils.write(identity));
    }
    private static boolean sourceUseAllowed(SourceDocument document,OerDocumentIdentity identity) {
        return identity!=null&&document.getSourceSha256()!=null&&document.getSourceSha256().matches("[a-f0-9]{64}")&&document.getSourceSha256().equals(identity.getSourceSha256())
                &&ProjectDocumentScope.reviewCategory(document)&&ProjectDocumentScope.parseUsable(document)
                &&Arrays.asList("tender","standard","project_fact","package_manifest").contains(document.getReviewRole())
                &&!Arrays.asList("drawing","example","pricing","works_pss").contains(document.getDocumentScope())
                &&!Arrays.asList("DRAWING","BQ","SOR","GENERAL_SUMMARY","WORKS_PSS","SL_NON_TECHNICAL").contains(identity.getKind())
                &&!Arrays.asList("standard_template","evaluation_answer","drawing").contains(identity.getSourcePurpose())
                &&!"source_identity_mismatch".equals(identity.getIdentityStatus())
                &&(identity.getEligibleModules()==null||identity.getEligibleModules().containsAll(Arrays.asList("vetting","advisory")))
                &&identity.getCandidates().stream().noneMatch(c->"DRAWING".equals(c.getKind()));
    }
    private static boolean observedNonDrawing(OerDocumentIdentity identity) {
        return identity!=null&&Arrays.asList("sha_verified","content_supported","content_supported_non_drawing","format_supported_non_drawing").contains(identity.getIdentityStatus());
    }
    private static boolean hasSourceUseConfirmation(OerDocumentIdentity identity,String originalSha) {
        return identity!=null&&originalSha!=null&&originalSha.equals(identity.getSourceSha256())
                &&identity.getIdentityEvidence().stream().anyMatch(e->Arrays.asList("human_text_contract_material","human_source_role").contains(e.getBasis())&&("original:"+originalSha).equals(e.getLocation()));
    }
    private static void grantSourceUse(SourceDocument document,OerDocumentIdentity identity,String basis,OerDocumentIdentity.Evidence retained) {
        if(identity.getEligibleModules()==null) {
            identity.setEligibleModules(Arrays.asList("vetting","advisory"));identity.setIdentityStatus("human_text_contract_material".equals(basis)?"human_confirmed_textual_material":"human_confirmed_source_use");
        }
        if(!hasSourceUseConfirmation(identity,document.getSourceSha256()))identity.getIdentityEvidence().add(retained!=null?retained:new OerDocumentIdentity.Evidence(
                basis,"original:"+document.getSourceSha256(),"Explicit "+document.getReviewRole()+" use with confirmed textual identity; extraction quality, family, clause ownership and project adoption remain separate"));
    }
    private static OerDocumentIdentity retainTextContractConfirmation(SourceDocument document,OerDocumentIdentity next) {
        OerDocumentIdentity previous=identity(document);
        if(sourceUseAllowed(document,next)&&(previous==null||sourceUseAllowed(document,previous))) {
            if(hasSourceUseConfirmation(previous,document.getSourceSha256())) {
                for(OerDocumentIdentity.Evidence evidence:previous.getIdentityEvidence())if(Arrays.asList("human_text_contract_material","human_source_role").contains(evidence.getBasis())&&("original:"+document.getSourceSha256()).equals(evidence.getLocation()))
                    {grantSourceUse(document,next,evidence.getBasis(),evidence);break;}
            } else if(next.getEligibleModules()==null&&(observedNonDrawing(previous)||observedNonDrawing(next)))grantSourceUse(document,next,"human_source_role",null);
        }
        return next;
    }

    public static OerDocumentIdentity identity(SourceDocument document) {
        if (JsonUtils.isBlankText(document.getOerIdentityJson())) return null;
        OerDocumentIdentity identity = JsonUtils.read(document.getOerIdentityJson(), OerDocumentIdentity.class);
        if (document.getSourceSha256() == null || !document.getSourceSha256().equals(identity.getSourceSha256()))
            throw new IllegalArgumentException("Persisted OER identity does not match its uploaded source");
        return identity;
    }

    /** Reclassify exact retained original bytes without recapturing or clearing any parser evidence. */
    public static OerDocumentIdentity refreshedFixedIdentity(SourceDocument document, String verifiedOriginalSha256) {
        if (verifiedOriginalSha256 == null || !verifiedOriginalSha256.equals(document.getSourceSha256()))
            throw new IllegalArgumentException("Retained original identity changed before routing refresh");
        // No fabricated ParsedDocument: only a trusted exact-byte registry entry can resolve this refresh.
        return FixedRegistry.ROUTER.route(document.getFileName(), verifiedOriginalSha256, null);
    }

    /** Existing fixed identities keep priority; otherwise reuse only retained source-bound native parser fields. */
    public static OerDocumentIdentity refreshedIdentity(SourceDocument document,String verifiedOriginalSha256) {
        OerDocumentIdentity fixed=retainTextContractConfirmation(document,refreshedFixedIdentity(document,verifiedOriginalSha256));
        if ("sha_verified".equals(fixed.getIdentityStatus()) || JsonUtils.isBlankText(document.getStructuredContentJson())) return fixed;
        List<com.consense.document.DocumentBlock> blocks;
        try {blocks=JsonUtils.readList(document.getStructuredContentJson(),com.consense.document.DocumentBlock.class);}
        catch(RuntimeException invalid){return fixed;}
        if(blocks==null||blocks.isEmpty())return fixed;
        for(com.consense.document.DocumentBlock block:blocks) {
            if(block==null)return fixed;
            JsonNode position=block.getSourcePosition();
            if(position!=null && "docx_native".equals(position.path("kind").asText())
                    && !verifiedOriginalSha256.equals(position.path("sourceSha256").asText())) return fixed;
        }
        return retainTextContractConfirmation(document,FixedRegistry.ROUTER.routeRetained(document.getFileName(),verifiedOriginalSha256,document.getTextContent(),blocks));
    }

    /** null means unresolved; a non-null reason excludes the source from this module. */
    public static String moduleExclusion(SourceDocument document, String module) {
        if (!Arrays.asList("drafting", "vetting", "advisory").contains(module))
            throw new IllegalArgumentException("Unknown OER business module");
        OerDocumentIdentity identity = identity(document);
        if (identity == null) return null;
        if ("evaluation_answer".equals(identity.getSourcePurpose())) return "evaluation_answer_isolated";
        if ("standard_template".equals(identity.getSourcePurpose()) && !"drafting".equals(module))
            return "drafting_standard_template";
        if (identity.getEligibleModules() != null && !identity.getEligibleModules().contains(module))
            return "source_not_eligible_for_" + module;
        return null;
    }

    public static String originalSha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Original source bytes are required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest) value.append(String.format("%02x", b & 255));
            return value.toString();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
