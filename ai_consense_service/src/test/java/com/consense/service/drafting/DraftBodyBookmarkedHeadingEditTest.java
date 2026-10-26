package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.web.dto.DraftingDtos.BlockPatch;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual body compiler/editor seam; native separators and bookmark-external tail are layout. */
class DraftBodyBookmarkedHeadingEditTest {
    private static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    private static final String BEFORE="SCC5.308\tDesign responsibility (adjustment) ";
    private static final String AFTER="SCC5.308\tNot used";
    private static final String HEADING="<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/><w:tabs><w:tab w:val=\"left\" w:pos=\"1106\"/></w:tabs></w:pPr><w:bookmarkStart w:id=\"1\" w:name=\"heading\"/><w:r><w:rPr><w:b/></w:rPr><w:t>SCC5.</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>308</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:tab/><w:t>Design responsibility (adjustment)</w:t></w:r><w:bookmarkEnd w:id=\"1\"/><w:r><w:t xml:space=\"preserve\"> </w:t></w:r></w:p>";

    private static Paragraph heading(TemplateIndex index){return index.getMainParagraphs().stream().filter(p->BEFORE.equals(p.getText())).findFirst().orElseThrow(AssertionError::new);}
    private static DocxEditResult edit(byte[] source)throws Exception {
        TemplateIndex index=EDITOR.inspect(source);Paragraph p=heading(index);
        return EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),DraftSourceEditCompiler.editableSpans("body-468",p,AFTER)));
    }
    private static void opaqueEntriesUnchanged(byte[] source,byte[] result)throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new java.io.ByteArrayInputStream(source))){ZipEntry item;while((item=zip.getNextEntry())!=null)if(!"word/document.xml".equals(item.getName()))assertArrayEquals(zip.readAllBytes(),DraftBodyParagraphRemovalTest.entry(result,item.getName()),item.getName());}
    }

    @Test void sameNumberNativeTabHeadingEditsOnlyTitleBeforeBookmarkEnd()throws Exception {
        byte[] source=DraftBodyParagraphRemovalTest.fixture(HEADING);TemplateIndex before=EDITOR.inspect(source);Paragraph p=heading(before);
        DocxEditResult result=edit(source);Paragraph changed=result.getIndex().paragraph(p.getId());
        assertEquals(AFTER+" ",changed.getText(),"Keep the original tab separator and bookmark-external trailing space");
        assertEquals(p.getRuns().get(0).getPropertiesSha256(),changed.getRuns().get(0).getPropertiesSha256());
        assertTrue(result.getIndex().getFeatures().stream().anyMatch(f->"BOOKMARK".equals(f.getKind())&&"heading".equals(f.getName())));
        assertEquals(before.mainParagraph(1).getSha256(),result.getIndex().mainParagraph(1).getSha256());opaqueEntriesUnchanged(source,result.getDocxBytes());
    }

    @Test void actualI170SavedExportHasTheSameBookmarkedHeadingFailurePattern()throws Exception {
        String path=System.getProperty("consense.acceptance.bookmarkedHeadingSource");assumeTrue(path!=null&&!path.isBlank(),"Explicit preserved I170 frontend export required");
        byte[] source=Files.readAllBytes(Paths.get(path));TemplateIndex before=EDITOR.inspect(source);
        assertEquals("3a38e5b86bb3052097f8af8c3d7493693563b392f818e926cacc5b6ef55a6a0d",before.getSourceSha256(),"I170 is a prior actual frontend export, not the entire current I186 package");
        Paragraph p=heading(before);assertTrue(before.getFeatures().stream().anyMatch(f->"CSe6977088e8807fbf21e7696ee1bd".equals(f.getName())&&f.getStartId().startsWith(p.getId()+"/")));
        DocxEditResult result=edit(source);assertEquals(AFTER+" ",result.getIndex().paragraph(p.getId()).getText());
        assertEquals(before.getMainParagraphs().size(),result.getIndex().getMainParagraphs().size());
        assertEquals(before.getFeatures().size(),result.getIndex().getFeatures().size());opaqueEntriesUnchanged(source,result.getDocxBytes());
    }

    @Test void publicBodySaveUsesTheSameSafeHeadingCompilerPath()throws Exception {
        DraftBodyParagraphRemovalTest.Saved saved=new DraftBodyParagraphRemovalTest.Saved(HEADING);
        BlockPatch patch=saved.block(BEFORE,null);patch.setText(AFTER);saved.save(saved.patch(patch));
        assertEquals(AFTER+" ",EDITOR.inspect(saved.current().getDocxBytes()).getMainParagraphs().get(1).getText());
    }

    @Test void aFieldInTheHeadingStillRejectsAnAttemptToChangeItsText()throws Exception {
        byte[] source=DraftBodyParagraphRemovalTest.fixture("<w:p><w:r><w:t>SCC5.308</w:t><w:tab/></w:r><w:fldSimple w:instr=\"DOCPROPERTY Title\"><w:r><w:t>Design responsibility (adjustment)</w:t></w:r></w:fldSimple><w:r><w:t xml:space=\"preserve\"> </w:t></w:r></w:p>");
        EditException failure=assertThrows(EditException.class,()->edit(source));assertEquals("UNSUPPORTED_INLINE_FEATURE",failure.getCode());
    }

    @Test void theSameNativeTabAndRunsWithoutTheBookmarkBoundaryRemainEditable()throws Exception {
        String ordinary=HEADING.replace("<w:bookmarkStart w:id=\"1\" w:name=\"heading\"/>","").replace("<w:bookmarkEnd w:id=\"1\"/>","");
        byte[] source=DraftBodyParagraphRemovalTest.fixture(ordinary);TemplateIndex index=EDITOR.inspect(source);Paragraph p=heading(index);
        DocxEditResult result=EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),DraftSourceEditCompiler.editableSpans("no-bookmark",p,AFTER+" ")));
        assertEquals(AFTER+" ",result.getIndex().getMainParagraphs().get(1).getText());
    }

    @Test void aBookmarkEndingAfterTheLastTextDoesNotRequireTheExternalTailException()throws Exception {
        String noTail=HEADING.replace("<w:r><w:t xml:space=\"preserve\"> </w:t></w:r>","");byte[] source=DraftBodyParagraphRemovalTest.fixture(noTail);TemplateIndex index=EDITOR.inspect(source);
        Paragraph p=index.getMainParagraphs().get(1);assertEquals(BEFORE.substring(0,BEFORE.length()-1),p.getText());
        DocxEditResult result=EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),DraftSourceEditCompiler.editableSpans("no-external-tail",p,AFTER)));
        assertEquals(AFTER,result.getIndex().paragraph(p.getId()).getText());
    }

    @Test void anotherClauseNumberDoesNotReceiveTheSameHeadingLayoutException()throws Exception {
        byte[] source=DraftBodyParagraphRemovalTest.fixture(HEADING);TemplateIndex index=EDITOR.inspect(source);Paragraph p=heading(index);
        for(String replacement:Arrays.asList("SCC5.309\tNot used","SCC5.3089\tNot used","SCC5.308 Not used"))
            assertThrows(EditException.class,()->EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),DraftSourceEditCompiler.editableSpans("different-number-or-separator",p,replacement))));
    }

    @Test void aLiteralTextTabDoesNotReceiveTheNativeLayoutException()throws Exception {
        String literal=HEADING.replace("<w:tab/><w:t>Design","<w:t xml:space=\"preserve\">\tDesign");byte[] source=DraftBodyParagraphRemovalTest.fixture(literal);
        assertEquals(BEFORE,heading(EDITOR.inspect(source)).getCatalogText());assertThrows(EditException.class,()->edit(source));
    }

    @Test void aPreparerMarkerDoesNotImplicitlyAuthorizeTheCatalogMarkerRemovalPath()throws Exception {
        byte[] source=DraftBodyParagraphRemovalTest.fixture(HEADING.replace("<w:t>SCC5.</w:t>","<w:t>*SCC5.</w:t>"));TemplateIndex index=EDITOR.inspect(source);Paragraph p=index.getMainParagraphs().get(1);
        assertEquals("*"+BEFORE,p.getText());assertThrows(EditException.class,()->EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),DraftSourceEditCompiler.editableSpans("marked-heading",p,"*"+AFTER))));
    }
}
