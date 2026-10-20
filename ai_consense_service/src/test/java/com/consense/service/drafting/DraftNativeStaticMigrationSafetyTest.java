package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** NSR-M1/M2 regressions use the exact OER/Editor source seam, no private inputs or services. */
class DraftNativeStaticMigrationSafetyTest {
    @Test void staticPairInsideCrossParagraphFieldIsRejectedAndOrdinaryStaticContentStillMigrates() throws Exception {
        String before="<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>Keep</w:t></w:r></w:p>";
        String after="<w:p><w:r><w:t>Tail</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>";
        Fixture protectedCache=staticSource(before,after,false);byte[] original=protectedCache.bytes.clone();NativeStoryPlan plan=protectedCache.plan();
        assertEquals(Arrays.asList("HD(QS)- SCT/1 -","March 2020"),Arrays.asList(plan.staticFooterGroups.get(0).exactParagraphs.get(0).exactText,plan.staticFooterGroups.get(0).exactParagraphs.get(1).exactText));
        assertEquals(2,plan.staticFooterGroups.get(0).exactParagraphs.get(0).source.getOrdinal());assertEquals(3,plan.staticFooterGroups.get(0).exactParagraphs.get(1).source.getOrdinal());
        assertCode("FIELD_TEXT_EDIT_UNSUPPORTED",()->protectedCache.apply(plan,Collections.emptyList()));assertArrayEquals(original,protectedCache.bytes);
        Fixture ordinary=staticSource(p("Keep"),p("Tail"),false);assertMigrated(ordinary,ordinary.apply());
    }

    @Test void staticClearAndStartRequireActiveWrittenFooterRatherThanPreserveFlagsOrInactivePage() throws Exception {
        Fixture source=staticSource(p("Keep"),p("Tail"),false);NativeStoryPlan valid=source.plan();
        assertTrue(valid.sections.get(0).stories.stream().filter(s->s.expectedBinding.kind==Kind.FOOTER).allMatch(s->s.expectedBinding.state==BindingState.NO_TARGET));
        assertMigrated(source,source.apply(valid,Collections.emptyList()));byte[] original=source.bytes.clone();
        // The only changed intent is the active default footer action; all observed source pins stay valid.
        assertCode("STORY_STATIC_CARRIER_REQUIRED",()->source.apply(preserveDefaultFooter(valid,true),Collections.emptyList()));
        assertCode("STORY_STATIC_CARRIER_REQUIRED",()->source.apply(preserveDefaultFooter(valid,false),Collections.emptyList()));assertArrayEquals(original,source.bytes);
        Fixture inactive=staticSource(p("Keep"),p("Tail"),true);NativeStoryPlan inactivePlan=preserveDefaultFooter(inactive.plan(),true);
        assertTrue(inactivePlan.sections.get(0).stories.stream().anyMatch(s->s.expectedBinding.kind==Kind.FOOTER&&s.expectedBinding.variant==Variant.FIRST&&s.action==StoryAction.CREATE_NATIVE_FOOTER));
        assertFalse(inactivePlan.sections.get(0).titlePg.present);assertCode("STORY_STATIC_CARRIER_REQUIRED",()->inactive.apply(inactivePlan,Collections.emptyList()));
    }

    private static Fixture staticSource(String before,String after,boolean inactiveFirst) throws Exception {
        Map<String,String> stories=new LinkedHashMap<>();String reference="",relations="";
        if(inactiveFirst){stories.put("word/footer1.xml",story("ftr","<w:p/>"));reference="<w:footerReference w:type=\"first\" r:id=\"rId8\"/>";relations=relation("rId8","footer","footer1.xml");}
        Fixture f=new Fixture(before+p("HD(QS)- SCT/1 -")+p("March 2020")+after+"<w:sectPr>"+reference+"<w:pgNumType w:start=\"17\"/></w:sectPr>",relations,stories,"");
        f.policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,"SCT/"," -","1",Arrays.asList("HD(QS)","March 2020")));f.groups.add(new DraftNativeIdentity.StaticGroupSpec(1,2,"HD(QS)- SCT/1 -",3,"March 2020"));return f;
    }
    private static NativeStoryPlan preserveDefaultFooter(NativeStoryPlan original,boolean keepNumbering) {
        List<Section> sections=new ArrayList<>();for(Section section:original.sections){List<Story> stories=new ArrayList<>();for(Story story:section.stories)stories.add(story.expectedBinding.kind==Kind.FOOTER&&story.expectedBinding.variant==Variant.DEFAULT?new Story(story.expectedBinding,StoryAction.PRESERVE,story.pageAction,story.contractLabels,story.retainedSourceControlLines,story.pagePrefix,story.pageSuffix):story);sections.add(new Section(section.observedSectionId,section.sourceSection,section.sourcePosition,section.titlePg,section.pgNumType,section.pgMar,section.pgSz,section.type,stories,keepNumbering?section.numberingChange:null,section.marginChange));}
        return new NativeStoryPlan(original.sourceSha256,original.profile,original.identity,original.evenAndOddHeaders,sections,original.staticFooterGroups);
    }
    private static void assertMigrated(Fixture source,DocxEditResult result) throws Exception {
        NativeStoryChange carrier=result.getNativeStoryLedger().stream().filter(c->c.getOriginalBinding()!=null&&c.getOriginalBinding().kind==Kind.FOOTER&&c.getOriginalBinding().variant==Variant.DEFAULT&&"APPLIED".equals(c.getStatus())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(BindingState.NO_TARGET,carrier.getOriginalBinding().state);Document footer=parse(unzip(result.getDocxBytes()).get(carrier.getGeneratedPartName()));assertEquals(" PAGE ",elements(footer,W,"fldSimple").get(0).getAttributeNS(W,"instr"));assertTrue(text(footer).contains("HD(QS)"));assertTrue(text(footer).contains("March 2020"));
        assertEquals("",result.getIndex().mainParagraph(2).getCatalogText());assertEquals("",result.getIndex().mainParagraph(3).getCatalogText());assertEquals("1",elements(parse(unzip(result.getDocxBytes()).get(MAIN)),W,"pgNumType").get(0).getAttributeNS(W,"start"));
        assertEquals("HD(QS)- SCT/1 -",source.index.mainParagraph(2).getCatalogText());assertEquals("March 2020",source.index.mainParagraph(3).getCatalogText());
    }
}
