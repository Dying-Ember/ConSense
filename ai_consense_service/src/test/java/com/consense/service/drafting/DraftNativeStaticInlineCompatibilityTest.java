package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** Native 2a NTT inline pattern: tab between label runs, split date and saved render marker. */
class DraftNativeStaticInlineCompatibilityTest {
    @Test void staticMigrationPreservesNativeTabRunPropertiesAndRenderMarkerWithoutLooseningLocalLabelEdits() throws Exception {
        String label="HD(QS) 3C/121/2/10- NTT/1 -";
        String labelXml="<w:p><w:pPr><w:tabs><w:tab w:val=\"center\" w:pos=\"4500\"/></w:tabs></w:pPr>"
                +"<w:r><w:t>HD(QS) 3C/121/2/10</w:t></w:r><w:r><w:rPr><w:rFonts w:hint=\"eastAsia\"/></w:rPr><w:tab/></w:r>"
                +"<w:r><w:rPr><w:rFonts w:hint=\"eastAsia\"/></w:rPr><w:t>- NTT/1</w:t></w:r><w:r><w:t xml:space=\"preserve\"> -</w:t></w:r></w:p>";
        String dateXml="<w:p><w:pPr><w:jc w:val=\"both\"/></w:pPr><w:r><w:rPr><w:bCs/></w:rPr><w:lastRenderedPageBreak/><w:t>March 20</w:t></w:r><w:r><w:rPr><w:bCs/></w:rPr><w:t>20</w:t></w:r></w:p>";
        Fixture source=new Fixture(p("Keep")+labelXml+dateXml+p("Tail")+"<w:sectPr/>","",Collections.emptyMap(),"");
        source.policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,"NTT/",null,null,Arrays.asList("HD(QS) 3C/121/2/10","March 2020")));
        source.groups.add(new DraftNativeIdentity.StaticGroupSpec(1,2,label,3,"March 2020"));
        NativeStoryPlan plan=source.plan();byte[] original=source.bytes.clone();
        assertEquals(label,source.index.mainParagraph(2).getCatalogText());
        assertTrue(source.index.mainParagraph(2).getText().contains("\t"));
        assertCode("UNSUPPORTED_INLINE_FEATURE",()->Edit.replaceCatalogLabelOutsideFields("local-label-stays-guarded",source.index.mainParagraph(2),0,label.length(),label,""));

        DocxEditResult result=assertDoesNotThrow(()->source.apply(plan,Collections.emptyList()),"Source-pinned full static clear must not invoke local-label replacement validation");
        assertArrayEquals(original,source.bytes);
        assertEquals("",result.getIndex().mainParagraph(2).getCatalogText());assertEquals("",result.getIndex().mainParagraph(3).getCatalogText());
        assertEquals("Keep",result.getIndex().mainParagraph(1).getCatalogText());assertEquals("Tail",result.getIndex().mainParagraph(4).getCatalogText());
        Document before=parse(unzip(source.bytes).get(MAIN)),after=parse(unzip(result.getDocxBytes()).get(MAIN));
        assertNativeNodesUnchanged(before,after,"pPr");assertNativeNodesUnchanged(before,after,"rPr");
        assertNativeNodesUnchanged(before,after,"tab");assertNativeNodesUnchanged(before,after,"lastRenderedPageBreak");
        NativeStoryChange carrier=result.getNativeStoryLedger().stream().filter(c->c.getOriginalBinding()!=null&&c.getOriginalBinding().kind==Kind.FOOTER&&c.getOriginalBinding().variant==Variant.DEFAULT&&"APPLIED".equals(c.getStatus())).findFirst().orElseThrow(AssertionError::new);
        Document footer=parse(unzip(result.getDocxBytes()).get(carrier.getGeneratedPartName()));
        assertEquals(" PAGE ",elements(footer,W,"fldSimple").get(0).getAttributeNS(W,"instr"));
        assertTrue(text(footer).contains(ID.number));assertTrue(text(footer).contains("HD(QS) 3C/121/2/10"));assertTrue(text(footer).contains("March 2020"));
        unchanged(unzip(source.bytes),unzip(result.getDocxBytes()),new HashSet<>(Arrays.asList(MAIN,RELS,TYPES)));
    }

    private static void assertNativeNodesUnchanged(Document before,Document after,String local) {
        List<Element> originals=elements(before,W,local),current=elements(after,W,local);assertEquals(originals.size(),current.size(),local);
        for(int i=0;i<originals.size();i++)assertTrue(originals.get(i).isEqualNode(current.get(i)),local+" at "+i);
    }
}
