package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** Structural business removal and the separately pinned static-footer migration share one source. */
class DraftStaticFooterRemovalCompositionTest {
    private static Fixture source() throws Exception {return source(false);}
    private static Fixture source(boolean tableFooters) throws Exception {
        StringBuilder body=new StringBuilder();for(int ordinal=1;ordinal<=254;ordinal++){if(tableFooters&&ordinal==253)body.append("<w:tbl><w:tr><w:tc>");body.append(p(DraftBusinessRules.standardParagraph("SCT",ordinal).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")));}if(tableFooters)body.append("</w:tc></w:tr></w:tbl>");
        Fixture source=new Fixture(body+p("Tail")+"<w:sectPr><w:pgNumType w:start=\"17\"/></w:sectPr>","",Map.of(),"","SCT");
        source.policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,"SCT/"," -","1",List.of("HD(QS)","March 2020")));
        source.groups.add(new DraftNativeIdentity.StaticGroupSpec(1,253,DraftBusinessRules.standardParagraph("SCT",253),254,DraftBusinessRules.standardParagraph("SCT",254)));return source;
    }
    private static NativeLayoutPlan layout(Fixture source,NativeStoryPlan stories){return new NativeLayoutPlan(source.index.getSourceSha256(),"static-removal-composition",List.of(),List.of()).withStories(stories);}
    private static List<Edit> remove(Fixture source,NativeLayoutPlan plan){
        DraftSourceEditCompiler compiler=new DraftSourceEditCompiler(source.index,"SCT",plan);
        compiler.adopted(DraftBusinessRules.map("id","business-branch"),List.of(new DraftClauseRules.SourceChange(248,254)),true);
        return compiler.edits();
    }

    @Test void removalKeepsExactMigrationNodesUntilTheFooterCarrierIsWritten() throws Exception {
        Fixture source=source();NativeStoryPlan stories=source.plan();byte[] original=source.bytes.clone();
        DocxEditResult result=source.apply(stories,remove(source,layout(source,stories)));
        assertArrayEquals(original,source.bytes);
        assertFalse(result.getIndex().getMainParagraphs().stream().anyMatch(p->DraftBusinessRules.standardParagraph("SCT",248).equals(p.getCatalogText())));
        assertTrue(result.getIndex().getMainParagraphs().stream().anyMatch(p->"Tail".equals(p.getCatalogText())));
        assertEquals(2,result.getNativeStoryLedger().stream().filter(row->"CLEAR_EXACT_STATIC_FOOTER".equals(row.getAction())&&"APPLIED".equals(row.getStatus())).count());
        NativeStoryChange carrier=result.getNativeStoryLedger().stream().filter(row->row.getOriginalBinding()!=null&&row.getOriginalBinding().kind==Kind.FOOTER&&"APPLIED".equals(row.getStatus())).findFirst().orElseThrow();
        String footer=text(parse(unzip(result.getDocxBytes()).get(carrier.getGeneratedPartName())));
        assertTrue(footer.contains("HD(QS)"));assertTrue(footer.contains("March 2020"));
        assertEquals(" PAGE ",elements(parse(unzip(result.getDocxBytes()).get(carrier.getGeneratedPartName())),W,"fldSimple").get(0).getAttributeNS(W,"instr"));
    }

    @Test void noMigrationPlanDoesNotSilentlySkipTheStaticParagraphs() throws Exception {
        Fixture source=source();
        assertCode("STORY_STATIC_BUSINESS_OVERLAP",()->source.apply(source.plan(),remove(source,null)));
    }

    @Test void directStaticEditsStillFailTheUnchangedEditorOverlapGuard() throws Exception {
        Fixture source=source();NativeStoryPlan stories=source.plan();List<Edit> edits=new ArrayList<>(remove(source,layout(source,stories)));
        edits.add(Edit.clearParagraph("unrelated-static-edit",source.index.mainParagraph(253)));
        assertCode("STORY_STATIC_BUSINESS_OVERLAP",()->source.apply(stories,edits));
    }

    @Test void observedStaticPairWithoutAMigratingFooterCarrierCannotProduceAResult() throws Exception {
        Fixture source=source();NativeStoryPlan actual=source.plan();List<Section> sections=new ArrayList<>();
        for(Section section:actual.sections){List<Story> stories=new ArrayList<>();for(Story story:section.stories)stories.add(new Story(story.expectedBinding,StoryAction.PRESERVE,story.pageAction,story.contractLabels,story.retainedSourceControlLines,story.pagePrefix,story.pageSuffix));sections.add(new Section(section.observedSectionId,section.sourceSection,section.sourcePosition,section.titlePg,section.pgNumType,section.pgMar,section.pgSz,section.type,stories,null,section.marginChange));}
        NativeStoryPlan preserve=new NativeStoryPlan(actual.sourceSha256,actual.profile,actual.identity,actual.evenAndOddHeaders,sections,actual.staticFooterGroups);
        assertCode("STORY_STATIC_CARRIER_REQUIRED",()->source.apply(preserve,remove(source,layout(source,preserve))));
    }

    @Test void staticParagraphsInsideATableCannotMakeAncestorRemovalAdmissible() throws Exception {
        Fixture source=source(true);NativeStoryPlan stories=source.plan();List<Edit> edits=new ArrayList<>(remove(source,layout(source,stories)));
        edits.add(Edit.removeNodes("unrelated-table-removal",List.of(source.index.anchor(source.index.mainParagraph(253).getTableId()))));
        assertCode("STORY_STATIC_PARAGRAPH_MISMATCH",()->source.apply(stories,edits));
    }

    @Test void sourceHashAndExactFooterTextCannotBeBorrowedOrInvented() throws Exception {
        Fixture source=source();NativeStoryPlan actual=source.plan();
        NativeStoryPlan wrongSource=new NativeStoryPlan("0".repeat(64),actual.profile,actual.identity,actual.evenAndOddHeaders,actual.sections,actual.staticFooterGroups);
        assertCode("STORY_SOURCE_HASH_MISMATCH",()->layout(source,wrongSource));
        NativeLayoutPlan borrowed=new NativeLayoutPlan(wrongSource.sourceSha256,"wrong-source",List.of(),List.of()).withStories(wrongSource);
        assertTrue(assertThrows(BizException.class,()->remove(source,borrowed)).getMessage().contains("SOURCE_STATIC_MIGRATION_HASH_MISMATCH"));
        List<StaticFooterParagraph> forged=new ArrayList<>(actual.staticFooterGroups.get(0).exactParagraphs);
        forged.set(0,new StaticFooterParagraph(source.index.mainParagraph(253),"Invented footer text"));
        NativeStoryPlan wrongText=new NativeStoryPlan(actual.sourceSha256,actual.profile,actual.identity,actual.evenAndOddHeaders,actual.sections,List.of(new StaticFooterGroup(1,forged)));
        assertTrue(assertThrows(BizException.class,()->remove(source,layout(source,wrongText))).getMessage().contains("SOURCE_STATIC_MIGRATION_ANCHOR_MISMATCH"));
    }
}
