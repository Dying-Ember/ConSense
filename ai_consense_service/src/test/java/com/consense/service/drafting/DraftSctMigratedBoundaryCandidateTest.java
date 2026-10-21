package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** Real source controls and optional-title failure remain authoritative after title migration. */
class DraftSctMigratedBoundaryCandidateTest {
    private static final String RELEASED="RELEASED_VERIFIED_SCT_TITLE_MIGRATION";

    @Test void successfulMigrationReleasesOnlyDerivedBreakAndRetainsMarkerSpacing()throws Exception {
        BoundaryCase c=new BoundaryCase(false,true);DocxEditResult result=c.apply(true,false);NativeLayoutChange change=boundary(result);
        assertEquals(RELEASED,change.getStatus(),"MIGRATED_TITLE_DERIVED_BREAK_MUST_RELEASE");
        Element paragraph=markerParagraph(result,change);assertEquals(0,inside(paragraph,W,"pageBreakBefore").size());
        Element spacing=inside(paragraph,W,"spacing").get(0);assertEquals("0",spacing.getAttributeNS(W,"before"));assertEquals("0",spacing.getAttributeNS(W,"after"));assertEquals("1",spacing.getAttributeNS(W,"line"));assertEquals("exact",spacing.getAttributeNS(W,"lineRule"));
        assertEquals(1,inside(paragraph,W,"bookmarkStart").size());assertEquals(1,inside(paragraph,W,"bookmarkEnd").size());
        assertEquals(2,DraftSctTrueHeaderCandidateTest.actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());
        assertEquals(2,DraftSctTrueHeaderCandidateTest.actions(result,"INSTALL_SCT_FIRST_TITLE_HEADER").size()+DraftSctTrueHeaderCandidateTest.actions(result,"INSTALL_SCT_DEFAULT_TITLE_HEADER").size());
        Map<String,Object> info=DraftNativeLayout.info("SCT","libreoffice",result);String saved=JsonUtils.mapper().writeValueAsString(DraftNativeLayout.ledger(result,info));
        List<Object> retained=DraftNativeLayout.bodyLedger(saved,result);assertFalse(JsonUtils.mapper().writeValueAsString(retained).contains("CHANGED_LAYOUT_CONTROL"));
    }

    @Test void failedMigrationRetainsUsableBaseAndDerivedBreak()throws Exception {
        BoundaryCase c=new BoundaryCase(false,true);DocxEditResult failed=c.apply(true,true),base=c.apply(false,false);
        DraftSctTrueHeaderCandidateTest.assertSkipped(failed,"SCT_TITLE_STYLE_NODE_MISMATCH");DraftSctTrueHeaderCandidateTest.assertSameEntries(base,failed);
        assertEquals("APPLIED",boundary(failed).getStatus());assertEquals(1,inside(markerParagraph(failed,boundary(failed)),W,"pageBreakBefore").size());
    }

    @Test void sourceHardPageBreakKeepsDerivedBoundaryAfterSuccessfulMigration()throws Exception {
        BoundaryCase c=new BoundaryCase(true,true);DocxEditResult result=c.apply(true,false);
        assertEquals(2,DraftSctTrueHeaderCandidateTest.actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());assertEquals("APPLIED",boundary(result).getStatus());
        assertEquals(1,inside(markerParagraph(result,boundary(result)),W,"pageBreakBefore").size());
        assertEquals(1,elements(parse(unzip(result.getDocxBytes()).get(MAIN)),W,"br").stream().filter(e->"page".equals(e.getAttributeNS(W,"type"))).count());
    }

    @Test void unmigratedHeadingKeepsDerivedBoundaryAfterSuccessfulMigration()throws Exception {
        BoundaryCase c=new BoundaryCase(false,false);DocxEditResult result=c.apply(true,false);
        assertEquals(2,DraftSctTrueHeaderCandidateTest.actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());assertEquals("APPLIED",boundary(result).getStatus());
        assertEquals(1,inside(markerParagraph(result,boundary(result)),W,"pageBreakBefore").size());assertTrue(text(parse(unzip(result.getDocxBytes()).get(MAIN))).contains("Another source heading"));
    }

    @Test void actual2a22ReleasesAnd2bNoDerivedBreaksPreserveOriginalControls()throws Exception {
        String profiles=System.getProperty("consense.acceptance.nativeStoryProfiles");assumeTrue(profiles!=null,"Private real-source replay must be explicitly enabled");
        JsonNode sources=JsonUtils.mapper().readTree(Paths.get(profiles).toFile()).path("sources");
        for(String edition:List.of("2a","2b")) {
            JsonNode row=null;for(JsonNode value:sources)if(edition.equals(value.path("edition").asText())&&"SCT".equals(value.path("key").asText())){assertNull(row);row=value;}assertNotNull(row);
            Path input=Paths.get(row.path("sourcePath").asText());byte[] bytes=Files.readAllBytes(input);TemplateIndex index=EDITOR.inspect(bytes);assertEquals(row.path("sourceSha256").asText(),index.getSourceSha256());
            NativeLayoutPlan body=DraftNativeLayout.plan(index,"SCT","libreoffice");if(body==null)body=new NativeLayoutPlan(index.getSourceSha256(),"actual-sct-boundary-story-only",Collections.emptyList(),Collections.emptyList());
            Map<String,Object> prepared=new LinkedHashMap<>();prepared.put("effectiveValues",Map.of("contractTitle",Map.of("number",ID.number,"title",ID.title)));
            NativeLayoutPlan layout=DraftNativeIdentity.attach(index,observe(bytes,"SCT",input.getFileName().toString()),"SCT",prepared,"libreoffice",body);
            DocxEditResult result=EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,layout);
            List<NativeLayoutChange> breaks=new ArrayList<>();for(NativeLayoutChange change:result.getNativeLayoutLedger())if("page_break_before".equals(change.getAction()))breaks.add(change);
            assertEquals(edition.equals("2a")?22:0,breaks.size());assertEquals(edition.equals("2a")?22:0,breaks.stream().filter(c->RELEASED.equals(c.getStatus())).count(),"ACTUAL_MIGRATED_TITLE_BOUNDARIES_REQUIRE_RELEASE");
            for(NativeLayoutChange change:breaks){Element paragraph=markerParagraph(result,change);assertEquals(0,inside(paragraph,W,"pageBreakBefore").size());assertEquals(1,inside(paragraph,W,"spacing").size());}
            assertEquals(edition.equals("2a")?27:21,DraftSctTrueHeaderCandidateTest.actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());
            Document before=parse(unzip(bytes).get(MAIN)),after=parse(unzip(result.getDocxBytes()).get(MAIN));assertEquals(elements(before,W,"br").stream().filter(e->"page".equals(e.getAttributeNS(W,"type"))).count(),elements(after,W,"br").stream().filter(e->"page".equals(e.getAttributeNS(W,"type"))).count());
            assertEquals(elements(before,W,"sectPr").size(),elements(after,W,"sectPr").size());assertArrayEquals(bytes,Files.readAllBytes(input));
        }
    }

    private static List<Element> inside(Element parent,String namespace,String local){List<Element> out=new ArrayList<>();NodeList nodes=parent.getElementsByTagNameNS(namespace,local);for(int i=0;i<nodes.getLength();i++)out.add((Element)nodes.item(i));return out;}
    private static NativeLayoutChange boundary(DocxEditResult result){return result.getNativeLayoutLedger().stream().filter(c->"page_break_before".equals(c.getAction())).findFirst().orElseThrow(AssertionError::new);}
    private static Element markerParagraph(DocxEditResult result,NativeLayoutChange change)throws Exception {
        Element marker=elements(parse(unzip(result.getDocxBytes()).get(MAIN)),W,"bookmarkStart").stream().filter(e->change.getLayoutId().equals(e.getAttributeNS(W,"name"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("p",marker.getParentNode().getLocalName());return(Element)marker.getParentNode();
    }
    private static final class BoundaryCase {
        final byte[] bytes;final TemplateIndex index;final NativeStoryPlan base;final NativeLayoutPlan layout;final String styleHash;
        BoundaryCase(boolean hardBreak,boolean titleBoundary)throws Exception {
            String margins=DraftSctTrueHeaderCandidateTest.MARGINS,plain=DraftSctTrueHeaderCandidateTest.PLAIN,continued=DraftSctTrueHeaderCandidateTest.CONTINUED;
            String first="<w:p><w:pPr><w:sectPr>"+reference("footer","rId8")+margins+"</w:sectPr></w:pPr><w:r><w:t>Cover title</w:t></w:r></w:p>";
            String titles=table(p(plain))+"<w:p><w:r><w:t>Source boundary guard</w:t>"+(hardBreak?"<w:br w:type=\"page\"/>":"")+"</w:r></w:p>"+table("<w:p>"+DraftSctTrueHeaderCandidateTest.RUNS+"</w:p>")+table(p("Another source heading"));
            Fixture fixture=new Fixture(first+titles+"<w:sectPr>"+reference("footer","rId8")+margins+"</w:sectPr>",relation("rId8","footer","footer1.xml"),Map.of("word/footer1.xml",story("ftr","<w:p><w:r><w:t>OLDNUM</w:t></w:r>"+PAGE+"</w:p>")),"","SCT");
            Map<String,byte[]> parts=new LinkedHashMap<>(fixture.parts);parts.put("word/styles.xml",DraftSctTrueHeaderCandidateTest.STYLES.getBytes(StandardCharsets.UTF_8));
            parts.put(TYPES,new String(parts.get(TYPES),StandardCharsets.UTF_8).replace("</Types>","<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
            parts.put(RELS,new String(parts.get(RELS),StandardCharsets.UTF_8).replace("</Relationships>",relation("rIdStyle","styles","styles.xml")+"</Relationships>").getBytes(StandardCharsets.UTF_8));validateFixturePackage(parts);
            bytes=zip(parts);index=EDITOR.inspect(bytes);Paragraph number=index.getParagraphs().stream().filter(p->"word/footer1.xml".equals(p.getPart())).findFirst().orElseThrow(AssertionError::new);
            base=DraftNativeIdentity.plan(index,observe(bytes,"SCT","synthetic-sct-boundary.docx"),ID,new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(),"synthetic-exact-sct-boundary",index.getEntryHashes(),List.of(policy(PageAction.PRESERVE_EXISTING_PAGE),policy(PageAction.PRESERVE_EXISTING_PAGE)),List.of(new DraftNativeIdentity.LabelSpec(number.getPart(),index.getEntryHashes().get(number.getPart()),number.getOrdinal(),number.getCatalogText(),0,6,"OLDNUM")),Collections.emptyList()));
            Paragraph heading=index.mainParagraph(titleBoundary?4:5);String tableId=heading.getId().substring(0,heading.getId().indexOf("/w:tr["));
            layout=new NativeLayoutPlan(index.getSourceSha256(),"synthetic-sct-boundary",List.of(new NativeLayoutBoundary(index.anchor(tableId),index.anchor(heading.getId()),index.anchor(index.mainParagraph(3).getId()))),Collections.emptyList());
            styleHash=DraftSctTrueHeaderCandidateTest.xmlHash(elements(parse(parts.get("word/styles.xml")),W,"style").get(0));
        }
        DocxEditResult apply(boolean migrate,boolean badStyle){NativeStoryPlan plan=base;if(migrate)plan=base.withSctTitleMigration(new SctTitleMigration(base.sections.get(1),index.getEntryHashes().get("word/styles.xml"),"a3",badStyle?"0".repeat(64):styleHash,List.of(new SctBodyTitle(index.mainParagraph(2),DraftSctTrueHeaderCandidateTest.PLAIN,false),new SctBodyTitle(index.mainParagraph(4),DraftSctTrueHeaderCandidateTest.CONTINUED,false)),2,4));return EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,layout.withStories(plan));}
        private static String table(String paragraph){return "<w:tbl><w:tr><w:tc>"+paragraph+"</w:tc></w:tr></w:tbl>";}
    }
}
