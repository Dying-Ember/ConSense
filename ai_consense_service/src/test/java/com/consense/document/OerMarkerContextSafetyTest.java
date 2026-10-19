package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic native observations only; source-context links must never become edit authority. */
class OerMarkerContextSafetyTest {
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));
    private static final String PART = String.join("", Collections.nCopies(64, "b"));
    private static final String BODY = "/{w}document[1]/{w}body[1]";

    @Test void originalPureSpacerParagraphsInsideACompleteNoteDoNotHideItsContext() {
        JsonNode evidence = OerTemplateEvidence.analyse("SCC", word(
            p("h", "SCC63.501 Foundation warranty"),
            p("open", "[Guidance Notes:"),
            p("spacer", "").put("nativeContentEmpty", true),
            p("adopt", "1. Only adopt this SCC Clause if the Contractor designs foundations."),
            p("spacer2", "").put("nativeContentEmpty", true),
            p("action", "2. *Delete/amend by the PQS as appropriate.]"),
            p("body", "The piles and pads* shall support the structure."),
            p("next", "SCC63.502 Other warranty")));
        JsonNode note = onlyNote(evidence), marker = unknown(evidence, "body");
        assertTrue(note.path("captureComplete").asBoolean());
        assertTrue(note.path("sourceSyntaxComplete").asBoolean());
        assertEquals(Collections.singletonList(note.path("id").asText()), strings(marker.path("guidanceNoteIds")));
        assertEquals(1, marker.path("conditionIds").size());
        assertFalse(marker.path("sourceInstructionRelationships").isEmpty());
        assertEquals("negative_instruction_symbol", marker(evidence, "action").path("status").asText());
    }

    @Test void anUnprovedOrPayloadBearingEmptyParagraphCannotBeIgnoredAsAPureSpacer() {
        for (int variant = 0; variant < 4; variant++) {
            ObjectNode blank = p("spacer", "");
            if (variant == 1) blank.put("nativeContentEmpty", false);
            if (variant == 2) blank.put("nativeContentEmpty", "true");
            if (variant == 3) {
                blank.put("nativeContentEmpty", true);
                ((ObjectNode) blank.path("textObservation")).put("deletedText", "unreviewed deleted payload");
            }
            JsonNode evidence = OerTemplateEvidence.analyse("SCC", word(
                p("h", "SCC63.501 Foundation warranty"), p("open", "[Guidance Notes:"), blank,
                p("action", "*Delete/amend by the PQS as appropriate.]"),
                p("body", "The footings* remain protected."), p("next", "SCC63.502 Other warranty")));
            JsonNode marker = unknown(evidence, "body");
            assertTrue(marker.path("guidanceNoteIds").isEmpty(), "unproved spacer " + variant);
            assertTrue(marker.path("conditionIds").isEmpty(), "unproved spacer " + variant);
        }
    }

    @Test void anExactQuotedTargetElsewhereInTheOwnerDoesNotExplainAnotherUnknownMarker() {
        JsonNode evidence = OerTemplateEvidence.analyse("SCC", word(
            p("h", "SCC64.501 Component warranty"),
            p("note", "[Guidance Note: *Delete \u201cRoofing Panel\u201d if roofing is excluded.]"),
            p("exact", "The Roofing Panel* remains until acceptance."),
            p("unrelated", "The footings* remain protected."), p("next", "SCC64.502 Other warranty")));
        assertEquals("source_scope_resolved_project_condition_unknown", marker(evidence, "exact").path("status").asText());
        JsonNode unrelated = unknown(evidence, "unrelated");
        assertTrue(unrelated.path("guidanceNoteIds").isEmpty());
        assertTrue(unrelated.path("conditionIds").isEmpty());
    }

    @Test void aUniqueSameRowQuotedLiteralConflictIsContextOnly() {
        JsonNode evidence = quotationConflict("r1", false, false);
        JsonNode note = onlyNote(evidence), marker = unknown(evidence, "body");
        assertEquals(Collections.singletonList(note.path("id").asText()), strings(marker.path("guidanceNoteIds")));
        assertEquals(1, marker.path("conditionIds").size());
        assertFalse(marker.path("sourceInstructionRelationships").isEmpty());
        assertFalse(marker.path("unknownReasonCode").asText().isEmpty());
    }

    @Test void quotedMismatchContextCannotCrossRowsOrChooseAmongTwoUnknownMarkers() {
        for (int variant = 0; variant < 2; variant++) {
            JsonNode evidence = quotationConflict(variant == 0 ? "r2" : "r1", variant == 1, false);
            JsonNode marker = unknown(evidence, "body");
            assertTrue(marker.path("guidanceNoteIds").isEmpty(), "row or candidate ambiguity " + variant);
            assertTrue(marker.path("conditionIds").isEmpty(), "row or candidate ambiguity " + variant);
        }
    }

    @Test void aQuotedInstructionWithAnotherSymbolDoesNotExplainTheUnknownMarker() {
        JsonNode evidence = quotationConflict("r1", false, true);
        JsonNode marker = unknown(evidence, "body");
        assertTrue(marker.path("guidanceNoteIds").isEmpty());
        assertTrue(marker.path("conditionIds").isEmpty());
    }

    @Test void contextNotesCannotCrossOriginalPartsStoriesOrSourceIdentities() {
        for (int variant = 0; variant < 4; variant++) {
            ObjectNode note = p("note", "[Guidance Note: *Delete/amend by the PQS as appropriate.]");
            ObjectNode position = (ObjectNode) note.path("sourcePosition");
            if (variant == 0) position.put("partName", "word/other-story.xml");
            if (variant == 1) {
                String story = BODY + "/{w}txbxContent[1]";
                note.put("storyRootNodePath", story);
                position.put("nodePath", story + "/{w}p[note]");
            }
            if (variant == 2) {
                note.put("storyKind", "header").put("storyRootNodePath", "/{w}hdr[1]");
                position.put("partName", "word/header1.xml").put("nodePath", "/{w}hdr[1]/{w}p[note]");
            }
            if (variant == 3) position.put("sourceSha256", PART);
            JsonNode evidence = OerTemplateEvidence.analyse("SCC", word(
                p("h", "SCC65.501 Foundation warranty"), p("body", "The footings* remain protected."),
                note, p("next", "SCC65.502 Other warranty")));
            JsonNode marker = unknown(evidence, "body");
            assertTrue(marker.path("guidanceNoteIds").isEmpty(), "foreign source boundary " + variant);
            assertTrue(marker.path("conditionIds").isEmpty(), "foreign source boundary " + variant);
        }
    }

    @Test void anUnclosedSameOwnerNoteAddsNoProvedContext() {
        JsonNode evidence = OerTemplateEvidence.analyse("SCC", word(
            p("h", "SCC66.501 Foundation warranty"), p("body", "The footings* remain protected."),
            p("note", "[Guidance Note: *Delete/amend by the PQS as appropriate."),
            p("next", "SCC66.502 Other warranty")));
        JsonNode note = onlyNote(evidence), marker = unknown(evidence, "body");
        assertFalse(note.path("sourceSyntaxComplete").asBoolean());
        assertTrue(marker.path("guidanceNoteIds").isEmpty());
        assertTrue(marker.path("conditionIds").isEmpty());
    }

    @Test void aLiteralTitleChoiceLinksOnlyItsExistingFieldAndKeepsTheProjectChoiceUnknown() {
        JsonNode evidence = OerTemplateEvidence.analyse("SCT", word(
            cell("h", "t", "r0", "1", "SCT67 Officer contacts"),
            cell("body", "t", "r1", "1", "(1) The officer is A/ (*Mr/Ms Officer), telephone 1234."),
            cell("note", "t", "r1", "2", "(Project Architect)"),
            cell("other", "t", "r2", "1", "(2) The specimen is Alpha/Beta* and the design is raft/pad."),
            cell("other-note", "t", "r2", "2", "(Project Architect)"),
            cell("next", "t", "r3", "1", "SCT68 Other contacts")));
        JsonNode marker = unknown(evidence, "body"), choice = null;
        for (JsonNode group : evidence.path("groups")) if ("person_title_choice".equals(group.path("fieldLabel").asText())) choice = group;
        assertNotNull(choice);
        assertTrue(choice.path("sourceChoiceComplete").asBoolean());
        assertTrue(choice.path("sourceFieldRangeComplete").asBoolean());
        assertFalse(choice.path("scopeComplete").asBoolean());
        assertEquals(2, choice.path("optionRanges").size());
        assertTrue(strings(marker.path("contextGroupIds")).contains(choice.path("id").asText()));
        assertEquals(strings(choice.path("guidanceNoteIds")), strings(marker.path("guidanceNoteIds")));
        assertFalse(marker.path("sourceInstructionRelationships").isEmpty());
        JsonNode other = unknown(evidence, "other");
        for (JsonNode group : evidence.path("groups")) if ("native_source_field_candidate".equals(group.path("kind").asText()))
            assertFalse(strings(other.path("contextGroupIds")).contains(group.path("id").asText()));
        assertTrue(other.path("guidanceNoteIds").isEmpty());
    }

    private static JsonNode quotationConflict(String noteRow, boolean twoUnknowns, boolean anotherSymbol) {
        String body = "(1) The record includes *and the Panel Schedule" + (twoUnknowns ? " and *an appendix." : ".");
        String symbol = anotherSymbol ? "#" : "*";
        return OerTemplateEvidence.analyse("SCT", word(
            cell("h", "t", "r0", "1", "SCT62 Submission documents"),
            cell("body", "t", "r1", "1", body),
            cell("note", "t", noteRow, "2", "(" + symbol + "For electronic procurement, delete \u201c" + symbol + "and the Panels Schedule\u201d)"),
            cell("next", "t", "r3", "1", "SCT63 Other documents")));
    }

    private static JsonNode unknown(JsonNode evidence, String member) {
        JsonNode marker = marker(evidence, member);
        assertEquals("unknown_marker_scope", marker.path("status").asText());
        assertEquals("unknown", marker.path("disposition").asText());
        assertFalse(marker.path("scopeComplete").asBoolean());
        assertTrue(marker.path("targetGroupIds").isEmpty());
        assertFalse(marker.path("wholeParagraphTargetAssumed").asBoolean());
        return marker;
    }

    private static JsonNode marker(JsonNode evidence, String member) {
        for (JsonNode marker : evidence.path("markerCandidates")) if (member.equals(marker.path("memberId").asText())) return marker;
        throw new AssertionError("Missing marker for " + member);
    }

    private static JsonNode onlyNote(JsonNode evidence) {
        assertEquals(1, evidence.path("guidanceNotes").size());
        return evidence.path("guidanceNotes").get(0);
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) result.add(value.asText());
        return result;
    }

    private static DocumentParser.ParsedDocument word(ObjectNode... paragraphs) {
        ParseCoverage coverage = new ParseCoverage();
        ObjectNode inventory = JsonUtils.mapper().createObjectNode().put("version", "docx-native-paragraphs-v1").put("sourceSha256", SHA);
        ArrayNode values = inventory.putArray("paragraphs");
        for (ObjectNode paragraph : paragraphs) values.add(paragraph);
        coverage.setNativeDocxParagraphs(inventory);
        return new DocumentParser.ParsedDocument("fixture", Collections.emptyList(), 0, false, "fixture", Collections.emptyList(), coverage, SHA, Collections.emptyList());
    }

    private static ObjectNode p(String id, String text) {
        ObjectNode paragraph = JsonUtils.mapper().createObjectNode().put("id", id).put("storyKind", "body").put("storyRootNodePath", BODY);
        paragraph.putObject("sourcePosition").put("kind", "docx_native").put("sourceSha256", SHA).put("partName", "word/document.xml").put("partSha256", PART).put("nodePath", BODY + "/{w}p[" + id + "]");
        paragraph.putObject("textObservation").put("status", "observed_parser_paragraph_channels").put("text", text).put("originalText", text).put("deletedText", "").put("strikeText", "");
        return paragraph;
    }

    private static ObjectNode cell(String id, String table, String row, String cell, String text) {
        ObjectNode paragraph = p(id, text);
        String tablePath = BODY + "/{w}tbl[" + table + "]", rowPath = tablePath + "/{w}tr[" + row + "]", cellPath = rowPath + "/{w}tc[" + cell + "]";
        paragraph.put("tableNodePath", tablePath).put("rowNodePath", rowPath).put("cellNodePath", cellPath);
        ((ObjectNode) paragraph.path("sourcePosition")).put("nodePath", cellPath + "/{w}p[" + id + "]");
        return paragraph;
    }
}
