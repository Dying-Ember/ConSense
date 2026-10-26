package com.consense.service.sources;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Public SL declarations only. Python must cold-validate the referenced scope,
 * native revision and complete glyph ranges; this class never reads CAS or
 * certifies source quality. Table/asset/geometry admission remains separate. */
final class NativePdfSlScopeContract {
    static final String PROSE = "source-reviewed-native-pdf-sl-whole-unit-v1";
    static final String TABLES = "source-reviewed-native-pdf-sl-whole-unit-tables-and-assets-v1";
    static final String FONT_PROSE = "source-reviewed-native-pdf-sl-whole-unit-font-text-v1";
    static final String FONT_TABLES = "source-reviewed-native-pdf-sl-whole-unit-tables-and-assets-font-text-v1";
    static final Set<String> CLASSES = set("sl_library", "sl_technical_library", "specification_library");
    static final Set<String> IDENTITY_FIELDS = set("sourceEditionId", "sourceUnitId", "slUnitScopeManifestSha256");

    private NativePdfSlScopeContract() {}

    static boolean isSl(JsonNode identity) {
        String version = identity.path("internalProjectionVersion").asText();
        return PROSE.equals(version) || TABLES.equals(version) || FONT_PROSE.equals(version) || FONT_TABLES.equals(version);
    }

    static Set<String> chunkFields(Set<String> ordinary, JsonNode chunk) {
        if (!isSl(chunk)) return ordinary;
        need("SL".equals(chunk.path("fileKey").asText()));
        need(chunk.path("unitId").equals(chunk.path("sourceUnitId")));
        Set<String> fields = new HashSet<>(ordinary);
        fields.addAll(IDENTITY_FIELDS); fields.add("fileKey");
        return fields;
    }

    static void identity(JsonNode value) {
        if (!isSl(value)) {
            for (String field : IDENTITY_FIELDS) need(!value.has(field));
            return;
        }
        need(CLASSES.contains(value.path("sourceClass").asText()));
        label(value.path("sourceEditionId")); label(value.path("sourceUnitId"));
        sha(value.path("slUnitScopeManifestSha256"));
        need(set("vetting", "advisory").contains(value.path("module").asText()));
        need("standard".equals(value.path("role").asText())
                && "reference_standard".equals(value.path("sourcePurpose").asText()));
        JsonNode eligible = value.path("eligibleModules"); need(eligible.isArray() && !eligible.isEmpty());
        Set<String> seen = new HashSet<>();
        for (JsonNode module : eligible) need(module.isTextual()
                && set("vetting", "advisory").contains(module.asText()) && seen.add(module.asText()));
        need(seen.contains(value.path("module").asText()));
    }

    static void scope(JsonNode scope, JsonNode identity) {
        Set<String> allowed = set("requiredPhysicalPages", "scopePolicySha256");
        if (!isSl(identity)) { fields(scope, allowed); need(!scope.has("slTechnicalUnit")); return; }
        identity(identity); allowed.add("slTechnicalUnit"); fields(scope, allowed);
        JsonNode binding = scope.path("slTechnicalUnit");
        fields(binding, set("scopeManifestRef", "editionId", "unitId"));
        label(binding.path("editionId")); label(binding.path("unitId"));
        need(binding.path("editionId").equals(identity.path("sourceEditionId"))
                && binding.path("unitId").equals(identity.path("sourceUnitId")));
        String policy = sha(scope.path("scopePolicySha256"));
        need(policy.equals(sha(identity.path("slUnitScopeManifestSha256"))));
        JsonNode ref = binding.path("scopeManifestRef");
        fields(ref, set("schema", "storage", "sha256", "byteCount", "relativePath", "identityScope"));
        need("consense.artifact-descriptor.v1".equals(ref.path("schema").asText())
                && "content_addressed_disk".equals(ref.path("storage").asText())
                && policy.equals(sha(ref.path("sha256")))
                && ref.path("byteCount").isIntegralNumber() && ref.path("byteCount").canConvertToLong()
                && ref.path("byteCount").asLong() > 0
                && ("sha256/" + policy.substring(0, 2) + "/" + policy + ".blob").equals(ref.path("relativePath").asText())
                && set("exact_supplied_bytes", "canonical_json_object").contains(ref.path("identityScope").asText()));
    }

    static void sameIdentity(JsonNode chunk, JsonNode envelopeIdentity) {
        need(isSl(chunk) == isSl(envelopeIdentity));
        if (!isSl(chunk)) return;
        for (String field : IDENTITY_FIELDS) need(chunk.path(field).equals(envelopeIdentity.path(field)));
        need("SL".equals(chunk.path("fileKey").asText()));
    }

    private static void label(JsonNode value) {
        need(value.isTextual() && !value.asText().trim().isEmpty()
                && value.asText().equals(value.asText().trim())
                && !set("unknown", "latest").contains(value.asText().toLowerCase(java.util.Locale.ROOT)));
    }
    private static String sha(JsonNode value) {
        need(value.isTextual() && value.asText().matches("[a-f0-9]{64}")); return value.asText();
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        need(value.isObject()); value.fieldNames().forEachRemaining(name -> need(allowed.contains(name)));
    }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void need(boolean value) {
        if (!value) throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY, "CANONICAL_NATIVE_PDF_SL_SCOPE_INVALID");
    }
}
