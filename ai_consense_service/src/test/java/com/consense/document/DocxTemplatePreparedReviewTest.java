package com.consense.document;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Review uses the same edit guards without repeatedly rendering a no-op package. */
class DocxTemplatePreparedReviewTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();

    @Test void repeatedTextReviewsDoNotMutateThePreparedOriginalOrAcquireStructuralAuthority() throws Exception {
        byte[] original=DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>A😀 42 months</w:t></w:r></w:p>");
        byte[] unchanged=original.clone();DocxTemplateEditor.PreparedTemplate prepared=editor.prepare(original);
        DocxTemplateEditor.TemplateIndex index=prepared.getIndex();DocxTemplateEditor.Paragraph paragraph=index.mainParagraph(1);
        DocxTemplateEditor.SourceEditBatch good=batch(index,DocxTemplateEditor.Edit.replaceSpan("duration",paragraph,4,6,"42","42"));
        for(int n=0;n<100;n++)prepared.validateTextEdits(good);
        assertEquals("A😀 42 months",prepared.getIndex().mainParagraph(1).getText());assertArrayEquals(unchanged,original);
        DocxTemplateEditor.EditException structural=assertThrows(DocxTemplateEditor.EditException.class,()->prepared.validateTextEdits(batch(index,DocxTemplateEditor.Edit.deleteParagraph("delete",paragraph))));
        assertEquals("TEXT_REVIEW_OPERATIONS_REQUIRED",structural.getCode());
        assertDoesNotThrow(()->prepared.validateTextEdits(good));
    }

    @Test void preparedReviewAndApplyRejectTheSameSourceSpanAndOperationFailures() throws Exception {
        byte[] original=DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>A😀 42 months</w:t></w:r></w:p>");
        DocxTemplateEditor.PreparedTemplate prepared=editor.prepare(original);DocxTemplateEditor.TemplateIndex index=prepared.getIndex();DocxTemplateEditor.Paragraph paragraph=index.mainParagraph(1);
        DocxTemplateEditor.Paragraph foreign=editor.inspect(DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>A😀 43 months</w:t></w:r></w:p>")).mainParagraph(1);
        List<DocxTemplateEditor.SourceEditBatch> invalid=Arrays.asList(
            new DocxTemplateEditor.SourceEditBatch(String.join("",Collections.nCopies(64,"0")),Collections.emptyList()),
            batch(index,DocxTemplateEditor.Edit.replaceSpan("wrong",paragraph,4,6,"43","43")),
            batch(index,DocxTemplateEditor.Edit.replaceSpan("stale-paragraph",foreign,4,6,"43","43")),
            batch(index,DocxTemplateEditor.Edit.replaceSpan("surrogate",paragraph,1,2,paragraph.getText().substring(1,2),"x")),
            batch(index,DocxTemplateEditor.Edit.insertText("point",paragraph,5,""),DocxTemplateEditor.Edit.replaceSpan("overlap",paragraph,4,6,"42","42")),
            batch(index,DocxTemplateEditor.Edit.insertText("same",paragraph,4,""),DocxTemplateEditor.Edit.insertText("same",paragraph,7,"")));
        for(DocxTemplateEditor.SourceEditBatch test:invalid) {
            String expected=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,test)).getCode();
            assertEquals(expected,assertThrows(DocxTemplateEditor.EditException.class,()->prepared.validateTextEdits(test)).getCode());
        }
    }

    @Test void preparedReviewCannotAcquireHeaderStoryEditAuthority() throws Exception {
        Map<String,byte[]> parts=DocxTemplateEditorTest.entries(DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>Body</w:t></w:r></w:p>"));
        parts.put("word/header1.xml","<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>Header</w:t></w:r></w:p></w:hdr>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] original=DocxTemplateEditorSafetyTest.zip(parts);DocxTemplateEditor.PreparedTemplate prepared=editor.prepare(original);DocxTemplateEditor.TemplateIndex index=prepared.getIndex();
        DocxTemplateEditor.Paragraph header=index.getParagraphs().stream().filter(p->p.getPart().equals("word/header1.xml")).findFirst().orElseThrow();
        DocxTemplateEditor.SourceEditBatch test=batch(index,DocxTemplateEditor.Edit.replaceSpan("header",header,0,6,"Header","Header"));
        assertEquals("STORY_EDIT_UNSUPPORTED",assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,test)).getCode());
        assertEquals("STORY_EDIT_UNSUPPORTED",assertThrows(DocxTemplateEditor.EditException.class,()->prepared.validateTextEdits(test)).getCode());
    }

    @Test void fieldsAndOpaqueInlineFeaturesRemainUnsafeInPreparedReview() throws Exception {
        for(String feature:Arrays.asList("<w:fldSimple w:instr=\"PAGE\"><w:r><w:t>42</w:t></w:r></w:fldSimple>","<w:r><w:drawing/></w:r>","<m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>")) {
            byte[] original=DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>Left</w:t></w:r>"+feature+"<w:r><w:t>Right</w:t></w:r></w:p>");
            DocxTemplateEditor.PreparedTemplate prepared=editor.prepare(original);DocxTemplateEditor.TemplateIndex index=prepared.getIndex();DocxTemplateEditor.Paragraph paragraph=index.mainParagraph(1);
            DocxTemplateEditor.SourceEditBatch test=batch(index,DocxTemplateEditor.Edit.replaceSpan("source-check",paragraph,0,paragraph.getText().length(),paragraph.getText(),paragraph.getText()));
            String expected=assertThrows(DocxTemplateEditor.EditException.class,()->editor.apply(original,test)).getCode();
            assertEquals(expected,assertThrows(DocxTemplateEditor.EditException.class,()->prepared.validateTextEdits(test)).getCode());
        }
    }

    private static DocxTemplateEditor.SourceEditBatch batch(DocxTemplateEditor.TemplateIndex index,DocxTemplateEditor.Edit... edits){return new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Arrays.asList(edits));}
}
