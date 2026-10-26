package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.ParseCoverage;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Ordinary Word storage retains reading text and source clauses without repeating native layout in every block. */
public final class NormalDocxReadingProjection {
    public static final String FIELD = "normalDocxReadingObservation";
    private static final String VERSION = "ordinary-docx-reading-compact-v1";
    private NormalDocxReadingProjection() {}

    public static boolean capture(SourceDocument source, DocumentParser.ParsedDocument parsed, String sha) {
        if (parsed == null || parsed.getCoverage() == null || source.getFileName() == null
                || !source.getFileName().toLowerCase(Locale.ROOT).endsWith(".docx")) return false;
        ParseCoverage richCoverage = parsed.getCoverage();
        JsonNode outline = richCoverage.getNativeDocxOutline();
        if (outline == null || !outline.path("headings").isArray() || !outline.path("paragraphs").isArray()
                || !outline.path("sourceClauseScopes").path("members").isArray()
                || !outline.path("sourceClauseScopes").path("ranges").isArray()
                || richCoverage.getTemplateEvidence() != null || richCoverage.getNativeDocxParagraphs() != null) return false;
        if (sha == null || !sha.equals(parsed.getSourceSha256()))
            throw new IllegalArgumentException("Ordinary DOCX source identity differs from parsed bytes");

        List<DocumentBlock> blocks = new ArrayList<>(parsed.getBlocks().size());
        for (DocumentBlock rich : parsed.getBlocks()) {
            DocumentBlock compact = new DocumentBlock();
            BeanUtils.copyProperties(rich, compact);
            compact.setReadingContext(compactReadingContext(rich.getReadingContext()));
            blocks.add(compact);
        }
        ParseCoverage coverage = new ParseCoverage();
        BeanUtils.copyProperties(richCoverage, coverage);
        coverage.setNativeDocxOutline(compactOutline(outline));
        coverage.setLimitations(new ArrayList<>(richCoverage.getLimitations()));
        coverage.getLimitations().add("Repeated Word paragraph and section layout declarations are omitted from ordinary storage; the original file remains available for reparse. Reading text, native locations, table cells and observed clause ranges are retained.");
        ObjectNode marker = JsonUtils.mapper().createObjectNode();
        marker.put("version", VERSION).put("sourceSha256", sha);
        marker.put("blockCount", blocks.size()).put("sourceQualityAccepted", false);
        ((ObjectNode) coverage.getNativeDocxOutline()).set(FIELD, marker);
        source.setStructuredContentJson(JsonUtils.write(blocks));
        source.setParseCoverageJson(JsonUtils.write(coverage));
        return true;
    }

    private static JsonNode compactReadingContext(JsonNode rich) {
        if (rich == null || !rich.isObject()) return rich;
        ObjectNode compact = copyExcept(rich, "nativeDocxParagraph", "nativeDocxParagraphs", "nativeDocxSection");
        JsonNode section = rich.path("nativeDocxSection");
        if (section.isObject()) compact.set("nativeDocxSection", copyOnly(section,
                "sectionId", "ordinalZeroBased", "inventoryPointer", "membershipStatus", "ownerStatus",
                "applicabilityStatus", "effectiveSectionState"));
        return compact;
    }

    private static JsonNode compactOutline(JsonNode rich) {
        ObjectNode compact = copyExcept(rich, "headings", "paragraphs", "sourceClauseScopes");
        ArrayNode headings = compact.putArray("headings");
        for (JsonNode heading : rich.path("headings")) {
            ObjectNode value = copyExcept(heading, "nativeMemberPaths", "properties");
            if (heading.has("properties")) value.set("properties", compactProperties(heading.path("properties")));
            headings.add(value);
        }
        JsonNode scopes = rich.path("sourceClauseScopes");
        ObjectNode selected = copyExcept(scopes, "members");
        ArrayNode members = selected.putArray("members");
        for (JsonNode member : scopes.path("members")) {
            ObjectNode value = copyExcept(member, "nativeFieldRoles", "directParagraphLayout", "cellNodePath",
                    "rowNodePath", "tableNodePath", "paragraphProperties");
            if (member.has("paragraphProperties")) value.set("paragraphProperties", compactProperties(member.path("paragraphProperties")));
            members.add(value);
        }
        compact.set("sourceClauseScopes", selected);
        return compact;
    }

    private static JsonNode compactProperties(JsonNode rich) {
        if (!rich.isObject()) return rich;
        ObjectNode compact = copyExcept(rich, "layoutEvidence");
        JsonNode layout = rich.path("layoutEvidence");
        if (layout.isObject()) compact.set("layoutEvidence", copyOnly(layout, "status", "numbering"));
        return compact;
    }

    private static ObjectNode copyOnly(JsonNode source, String... fields) {
        ObjectNode out = JsonUtils.mapper().createObjectNode();
        for (String field : fields) if (source.has(field)) out.set(field, source.get(field));
        return out;
    }

    private static ObjectNode copyExcept(JsonNode source, String... excluded) {
        ObjectNode out = JsonUtils.mapper().createObjectNode();
        List<String> skip = Arrays.asList(excluded);
        source.fields().forEachRemaining(field -> { if (!skip.contains(field.getKey())) out.set(field.getKey(), field.getValue()); });
        return out;
    }
}
