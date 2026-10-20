package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prepared only. No JVM run or DOCX generation has been authorized for this candidate phase. */
class DraftNativeIdentityCandidateTest {
    private static final String NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String W = "{" + NS + "}";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final String MAIN = "word/document.xml", RELS = "word/_rels/document.xml.rels";
    private static final Identity ID = new Identity("CONTRACT-42", "Project title");

    @Test void longIdentityIsPlainTextAndBlankInvalidXmlValuesAreRejected() {
        String title = String.join("", Collections.nCopies(100, "工程 <>& title \uD83D\uDE80\n"));
        assertEquals(title, new Identity("C42", title).title);
        for (String bad : Arrays.asList("", "\u00A0\u3000", "bad\u0001", "bad\uD800"))
            assertThrows(IllegalArgumentException.class, () -> new Identity("C42", bad));
        assertThrows(IllegalArgumentException.class, () -> new Identity(null, "title"));
    }
    @Test void coverNoPageRomanAndAppendixResetPoliciesRemainDistinct() throws Exception {
        Fixture f = fixture(); NativeStoryPlan plan = f.plan();
        assertEquals(2, plan.sections.size());
        assertEquals(PageAction.PRESERVE_NO_PAGE, footer(plan.sections.get(0)).pageAction);
        assertEquals(PageAction.PRESERVE_EXISTING_PAGE, footer(plan.sections.get(1)).pageAction);
        assertEquals("SCT.C/", footer(plan.sections.get(1)).pagePrefix);
        assertEquals("lowerRoman", plan.sections.get(0).pgNumType.observations.get(0).rawAttributes.get(W + "fmt"));
        assertEquals("1", plan.sections.get(1).pgNumType.observations.get(0).rawAttributes.get(W + "start"));
        assertNull(plan.sections.get(0).numberingChange); assertNull(plan.sections.get(1).numberingChange);
        assertEquals(StoryAction.APPEND_PROJECT_TITLE, plan.sections.get(0).stories.get(0).action);
        assertEquals(6, plan.sections.get(0).stories.size()); assertEquals(6, plan.sections.get(1).stories.size());
    }
    @Test void oldSavedNativeSectionsCannotMasqueradeAsNewLayoutObservation() throws Exception {
        Fixture f = fixture(); section(f, 0).remove("layoutProperties");
        rejected("NATIVE_SECTION_PROPERTIES_NOT_OBSERVED", f);
    }
    @Test void wrongSourcePartOrSectionPathCannotBind() throws Exception {
        Fixture a = fixture(); ((ObjectNode) a.evidence.path("sourcePosition")).put("sourceSha256", "wrong");
        rejected("NATIVE_SOURCE_POSITION_MISMATCH", a);
        Fixture b = fixture(); ((ObjectNode) section(b, 0).path("endBoundary").path("sourcePosition")).put("partSha256", "wrong");
        rejected("NATIVE_SOURCE_POSITION_MISMATCH", b);
        Fixture c = fixture(); ((ObjectNode) section(c, 0).path("endBoundary").path("sourcePosition")).put("nodePath", "/wrong");
        rejected("SECTION_ANCHOR_NOT_FOUND", c);
    }
    @Test void mismatchedRelationshipIdTargetHashExternalAndUnknownBindingAreRejected() throws Exception {
        Fixture a = fixture(); ((ObjectNode) slot(a, 0, 3).path("relationship")).put("id", "wrong");
        rejected("RELATIONSHIP_ID_MISMATCH", a);
        Fixture b = fixture(); ((ObjectNode) slot(b, 0, 3).path("targetPart")).put("partSha256", "wrong");
        rejected("STORY_PART_HASH_MISMATCH", b);
        Fixture c = fixture(); ((ObjectNode) slot(c, 0, 3).path("relationship")).put("rawTargetMode", "External");
        rejected("EXTERNAL_STORY_UNSUPPORTED", c);
        Fixture d = fixture(); slot(d, 0, 3).put("status", "unknown");
        rejected("NATIVE_BINDING_UNSUPPORTED", d);
    }
    @Test void duplicatePropertiesAndIncompleteOrDuplicateSlotsAreRejected() throws Exception {
        Fixture a = fixture(); ((ObjectNode) section(a, 0).path("layoutProperties").path("pgMar")).put("status", "duplicate_property");
        rejected("NATIVE_PROPERTY_UNSUPPORTED", a);
        Fixture b = fixture(); ((ArrayNode) section(b, 0).path("headerFooterBindings")).remove(5);
        rejected("SECTION_SIX_SLOTS_REQUIRED", b);
        Fixture c = fixture(); ArrayNode slots = (ArrayNode) section(c, 0).path("headerFooterBindings"); slots.set(5, slots.get(4).deepCopy());
        rejected("DUPLICATE_STORY_SLOT", c);
    }
    @Test void staticFooterMigrationClearsOnlyTwoExactParagraphsAndBindsVisibleStart() throws Exception {
        Fixture f = fixture(true); f.staticMigration(); NativeStoryPlan plan = f.plan();
        assertEquals(1, plan.staticFooterGroups.size());
        assertEquals(Arrays.asList(3, 4), Arrays.asList(plan.staticFooterGroups.get(0).exactParagraphs.get(0).source.getOrdinal(), plan.staticFooterGroups.get(0).exactParagraphs.get(1).source.getOrdinal()));
        assertFalse(plan.staticFooterGroups.get(0).exactParagraphs.stream().anyMatch(p -> p.source.getId().equals(f.index.mainParagraph(5).getId())));
        assertEquals("17", plan.sections.get(1).numberingChange.expected.observations.get(0).rawAttributes.get(W + "start"));
        assertEquals("1", plan.sections.get(1).numberingChange.newStart);
        assertEquals(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE, footer(plan.sections.get(1)).pageAction);
    }
    @Test void staleStaticTextAndNumberingWithoutAStaticSequenceAreRejected() throws Exception {
        Fixture a = fixture(true); a.staticMigration();
        a.groups.set(0, new DraftNativeIdentity.StaticGroupSpec(1, 3, "wrong", 4, "March 2020- SCT/1 -"));
        rejected("STATIC_FOOTER_TEXT_MISMATCH", a);
        Fixture b = fixture(true); b.staticMigration(); b.groups.clear();
        rejected("NUMBER_START_REQUIRES_STATIC_MIGRATION", b);
    }
    @Test void sharedNativePartWithDifferentPageIntentIsRejectedBeforeEditorApply() throws Exception {
        Fixture f = fixture(); ObjectNode original = slot(f, 0, 3); ObjectNode second = slot(f, 1, 3);
        second.set("targetPart", original.path("targetPart").deepCopy()); second.set("relationship", original.path("relationship").deepCopy());
        second.set("originReference", original.path("originReference").deepCopy());
        rejected("SHARED_STORY_INTENT_CONFLICT", f);
    }

    private static Story footer(Section section) { return section.stories.stream().filter(s -> s.expectedBinding.kind == Kind.FOOTER && s.expectedBinding.variant == Variant.DEFAULT).findFirst().orElseThrow(AssertionError::new); }
    private static ObjectNode section(Fixture f, int i) { return (ObjectNode) f.evidence.path("sections").get(i); }
    private static ObjectNode slot(Fixture f, int i, int slot) { return (ObjectNode) section(f, i).path("headerFooterBindings").get(slot); }
    private static void rejected(String code, Fixture f) { assertEquals(code, assertThrows(IllegalStateException.class, f::plan).getMessage()); }

    private static final class Fixture {
        final TemplateIndex index; final ObjectNode evidence;
        final List<DraftNativeIdentity.SectionPolicy> policies = new ArrayList<>();
        final List<DraftNativeIdentity.StaticGroupSpec> groups = new ArrayList<>();
        final List<DraftNativeIdentity.LabelSpec> labels = new ArrayList<>();
        Fixture(TemplateIndex index, ObjectNode evidence) {
            this.index = index; this.evidence = evidence;
            policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.PRESERVE_NO_PAGE, null, null, null, Collections.emptyList()));
            policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.PRESERVE_EXISTING_PAGE, "SCT.C/", " -", null, Collections.emptyList()));
            for (String part : Arrays.asList("word/footer1.xml", "word/footer2.xml")) {
                Paragraph p = index.getParagraphs().stream().filter(q -> part.equals(q.getPart())).findFirst().orElseThrow(AssertionError::new);
                labels.add(new DraftNativeIdentity.LabelSpec(part, index.getEntryHashes().get(part), 1, p.getCatalogText(), 0, 8, "20250101"));
            }
        }
        void staticMigration() {
            policies.set(1, new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE, "SCT/", " -", "1", Arrays.asList("HD(QS) source control", "March 2020")));
            groups.add(new DraftNativeIdentity.StaticGroupSpec(1, 3, "HD(QS) source control", 4, "March 2020- SCT/1 -"));
        }
        NativeStoryPlan plan() {
            return DraftNativeIdentity.plan(index, evidence, ID, new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(), "synthetic-edition", index.getEntryHashes(), policies, labels, groups));
        }
    }
    private static Fixture fixture() throws Exception { return fixture(false); }
    private static Fixture fixture(boolean migrationSource) throws Exception {
        String a = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rH\"/><w:footerReference w:type=\"default\" r:id=\"rF1\"/><w:pgNumType w:fmt=\"lowerRoman\" w:start=\"1\"/><w:pgMar w:top=\"720\"/></w:sectPr>";
        String b = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rF2\"/><w:pgNumType w:start=\"" + (migrationSource ? "17" : "1") + "\"/><w:pgMar w:top=\"720\"/></w:sectPr>";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        put(entries, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/><Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/><Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/><Override PartName=\"/word/footer2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/></Types>");
        put(entries, "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"root\" Type=\"" + REL + "officeDocument\" Target=\"word/document.xml\"/></Relationships>");
        put(entries, MAIN, "<w:document xmlns:w=\"" + NS + "\" xmlns:r=\"" + REL.substring(0, REL.length()-1) + "\"><w:body><w:p><w:pPr>" + a + "</w:pPr><w:r><w:t>Cover body</w:t></w:r></w:p><w:p><w:r><w:t>Body</w:t></w:r></w:p><w:p><w:r><w:t>HD(QS) source control</w:t></w:r></w:p><w:p><w:r><w:t>March 2020- SCT/1 -</w:t></w:r></w:p><w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + b + "</w:body></w:document>");
        put(entries, "word/header1.xml", "<w:hdr xmlns:w=\"" + NS + "\"><w:p><w:r><w:t>Appendix C source heading</w:t></w:r></w:p></w:hdr>");
        put(entries, "word/footer1.xml", "<w:ftr xmlns:w=\"" + NS + "\"><w:p><w:r><w:t>20250101</w:t></w:r></w:p></w:ftr>");
        put(entries, "word/footer2.xml", "<w:ftr xmlns:w=\"" + NS + "\"><w:p><w:r><w:t>20250101- SCT.C/</w:t></w:r><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>");
        put(entries, RELS, "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rH\" Type=\"" + REL + "header\" Target=\"header1.xml\"/><Relationship Id=\"rF1\" Type=\"" + REL + "footer\" Target=\"footer1.xml\"/><Relationship Id=\"rF2\" Type=\"" + REL + "footer\" Target=\"footer2.xml\"/><Relationship Id=\"rIdSettings\" Type=\"" + REL + "settings\" Target=\"settings.xml\"/></Relationships>");
        put(entries, "word/settings.xml", "<w:settings xmlns:w=\"" + NS + "\"/>");
        DraftNativeStoryEditorCandidateTest.validateFixturePackage(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) { for (Map.Entry<String, byte[]> e : entries.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry(); } }
        TemplateIndex index = new DocxTemplateEditor().inspect(out.toByteArray());
        List<NodeAnchor> anchors = new ArrayList<>();
        for (NodeAnchor n : index.getAnchors()) if ("sectPr".equals(n.getKind())) anchors.add(n);
        ObjectNode root = object(); root.put("version", "docx-native-sections-v1"); root.set("sourcePosition", pos(index, MAIN, "/" + W + "document[1]/" + W + "body[1]"));
        root.putObject("headerFooterSettings").set("evenAndOddHeaders", absent());
        ArrayNode sections = root.putArray("sections");
        for (int i = 0; i < 2; i++) {
            ObjectNode s = sections.addObject(); s.put("sectionId", "source-section-" + i);
            s.putObject("endBoundary").set("sourcePosition", pos(index, MAIN, anchors.get(i).getNativeNodePath()));
            s.set("titlePg", absent());
            ObjectNode layout = s.putObject("layoutProperties"); layout.put("version", "docx-native-section-layout-properties-v1"); layout.put("effectiveState", "unknown");
            ObjectNode number = property(index, anchors.get(i), "pgNumType", "start", i == 0 || !migrationSource ? "1" : "17");
            if (i == 0) ((ObjectNode) number.path("observations").get(0).path("rawAttributes")).put(W + "fmt", "lowerRoman");
            layout.set("pgNumType", number); layout.set("pgMar", property(index, anchors.get(i), "pgMar", "top", "720")); layout.set("pgSz", absent()); layout.set("type", absent());
            ArrayNode slots = s.putArray("headerFooterBindings");
            for (Kind kind : Kind.values()) for (Variant variant : Variant.values()) {
                ObjectNode slot = slots.addObject(); slot.put("kind", kind.name().toLowerCase(Locale.ROOT)); slot.put("variant", variant.name().toLowerCase(Locale.ROOT)); slot.put("status", "no_target"); slot.putNull("targetPart");
                if (variant == Variant.DEFAULT) {
                    String id = kind == Kind.HEADER ? "rH" : i == 0 ? "rF1" : "rF2";
                    String part = kind == Kind.HEADER ? "word/header1.xml" : "word/footer" + (i + 1) + ".xml";
                    slot.put("status", i == 1 && kind == Kind.HEADER ? "inherited_target_candidate" : "explicit_target_loaded");
                    slot.put("originSectionId", "source-section-" + (kind == Kind.HEADER ? 0 : i));
                    if (i == 1 && kind == Kind.HEADER) slot.put("inheritedFromSectionId", "source-section-0"); else slot.putNull("inheritedFromSectionId");
                    ObjectNode target = slot.putObject("targetPart"); target.put("partName", part); target.put("partSha256", index.getEntryHashes().get(part)); target.put("rootNodePath", "/" + W + (kind == Kind.HEADER ? "hdr" : "ftr") + "[1]");
                    ObjectNode relation = slot.putObject("relationship"); relation.put("id", id); relation.put("type", REL + kind.name().toLowerCase(Locale.ROOT)); relation.put("rawTarget", part.substring(5)); relation.putNull("rawTargetMode"); relation.set("sourcePosition", pos(index, RELS, "/relationship-" + id));
                    ObjectNode origin = slot.putObject("originReference"); origin.put("rawRelationshipId", id); origin.set("sourcePosition", pos(index, MAIN, anchors.get(kind == Kind.HEADER ? 0 : i).getNativeNodePath() + "/" + W + kind.name().toLowerCase(Locale.ROOT) + "Reference[1]"));
                }
            }
        }
        return new Fixture(index, root);
    }
    private static ObjectNode absent() { ObjectNode n = object(); n.put("present", false); n.put("status", "absent"); n.putArray("observations"); return n; }
    private static ObjectNode property(TemplateIndex index, NodeAnchor section, String name, String attribute, String value) {
        ObjectNode n = object(); n.put("present", true); n.put("status", "observed_raw_declaration");
        ObjectNode occurrence = n.putArray("observations").addObject(); occurrence.putObject("rawAttributes").put(W + attribute, value);
        occurrence.set("sourcePosition", pos(index, MAIN, section.getNativeNodePath() + "/" + W + name + "[1]")); return n;
    }
    private static ObjectNode pos(TemplateIndex index, String part, String path) { ObjectNode n = object(); n.put("sourceSha256", index.getSourceSha256()); n.put("partName", part); n.put("partSha256", index.getEntryHashes().get(part)); n.put("nodePath", path); return n; }
    private static ObjectNode object() { return JsonUtils.mapper().createObjectNode(); }
    private static void put(Map<String, byte[]> parts, String name, String xml) { parts.put(name, xml.getBytes(StandardCharsets.UTF_8)); }
}
