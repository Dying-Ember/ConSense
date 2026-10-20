package com.consense.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.consense.document.DocxTemplateEditor.*;

class DocxTemplateHeaderFooterEditTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private byte[] source() throws Exception {
        Map<String,byte[]> parts=DocxTemplateEditorTest.entries(DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>Original body</w:t></w:r></w:p>"));
        parts.put("word/header1.xml",("<w:hdr xmlns:w=\""+W+"\"><w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"22\"/></w:rPr><w:t>Old title</w:t></w:r></w:p></w:hdr>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/footer1.xml",("<w:ftr xmlns:w=\""+W+"\"><w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r><w:t>20250101</w:t><w:tab/><w:t>- SCC/</w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>12</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/footer2.xml",("<w:ftr xmlns:w=\""+W+"\"><w:p><w:r><w:t>Unselected footer</w:t></w:r></w:p></w:ftr>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/footnotes.xml",("<w:footnotes xmlns:w=\""+W+"\"><w:footnote w:id=\"1\"><w:p><w:r><w:t>Original note</w:t></w:r></w:p></w:footnote></w:footnotes>").getBytes(StandardCharsets.UTF_8));
        return DocxTemplateEditorSafetyTest.zip(parts);
    }
    private Paragraph paragraph(TemplateIndex index,String part) {return index.getParagraphs().stream().filter(p->part.equals(p.getPart())).findFirst().orElseThrow(AssertionError::new);}
    private Edit label(String id,Paragraph p,String old,String next) {int at=p.getCatalogText().indexOf(old);assertTrue(at>=0);return Edit.replaceCatalogLabelOutsideFields(id,p,at,at+old.length(),old,next);}

    @Test void explicitHeaderFooterLabelsPreserveBodyOtherPartsFieldsAndFormatting() throws Exception {
        byte[] original=source(),unchanged=original.clone();TemplateIndex index=editor.inspect(original);
        List<Edit> edits=Arrays.asList(label("title",paragraph(index,"word/header1.xml"),"Old title","New project title"),label("number",paragraph(index,"word/footer1.xml"),"20250101","TEST-42"));
        SourceEditBatch batch=new SourceEditBatch(index.getSourceSha256(),edits);editor.prepare(original).validateTextEdits(batch);
        DocxEditResult result=editor.apply(original,batch);
        Map<String,byte[]> before=DocxTemplateEditorTest.entries(original),after=DocxTemplateEditorTest.entries(result.getDocxBytes());
        assertEquals(before.keySet(),after.keySet());
        for(String part:before.keySet())if(!part.equals("word/header1.xml")&&!part.equals("word/footer1.xml"))assertArrayEquals(before.get(part),after.get(part),part);
        assertEquals("New project title",paragraph(result.getIndex(),"word/header1.xml").getCatalogText());
        assertEquals("TEST-42- SCC/12",paragraph(result.getIndex(),"word/footer1.xml").getCatalogText());
        String footer=new String(after.get("word/footer1.xml"),StandardCharsets.UTF_8),header=new String(after.get("word/header1.xml"),StandardCharsets.UTF_8);
        assertTrue(footer.contains("> PAGE <"));assertTrue(footer.contains("fldCharType=\"begin\""));assertTrue(footer.contains("fldCharType=\"end\""));
        assertTrue(footer.contains("after=\"0\""));assertTrue(header.contains("val=\"22\""));assertTrue(header.contains("val=\"right\""));
        assertEquals(2,result.getLedger().size());assertArrayEquals(unchanged,original);
    }
    @Test void nonExplicitStoryEditsStructuralChangesAndFootnotesRemainRejected() throws Exception {
        byte[] original=source();TemplateIndex index=editor.inspect(original);Paragraph header=paragraph(index,"word/header1.xml"),note=paragraph(index,"word/footnotes.xml");
        for(Edit edit:Arrays.asList(Edit.replaceSpan("ordinary",header,0,3,"Old","New"),Edit.clearParagraph("clear",header),label("note",note,"Original","Changed"))) {
            EditException error=assertThrows(EditException.class,()->editor.apply(original,new SourceEditBatch(index.getSourceSha256(),Collections.singletonList(edit))));
            assertEquals("STORY_EDIT_UNSUPPORTED",error.getCode());
        }
    }
    @Test void fieldResultsAndWrongSourceHashesCannotBeEditedThroughTheLabelPath() throws Exception {
        byte[] original=source();TemplateIndex index=editor.inspect(original);Paragraph footer=paragraph(index,"word/footer1.xml");
        assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",assertThrows(EditException.class,()->editor.apply(original,new SourceEditBatch(index.getSourceSha256(),Collections.singletonList(label("page-cache",footer,"12","999"))))).getCode());
        Edit number=label("number",footer,"20250101","OTHER");
        assertEquals("SOURCE_HASH_MISMATCH",assertThrows(EditException.class,()->editor.apply(original,new SourceEditBatch(String.join("",Collections.nCopies(64,"0")),Collections.singletonList(number)))).getCode());
    }
    @ParameterizedTest @ValueSource(strings={"03 - NTT.docx","05 - SCT.docx","10 - SCC.docx"})
    void actualProjectFooterNumbersChangeOnlyTheirSelectedNativeParts(String name) throws Exception {
        String folder=System.getProperty("consense.acceptance.project2bDir");assumeTrue(folder!=null,"Actual2b project folder required");
        byte[] original=Files.readAllBytes(Paths.get(folder,name));TemplateIndex index=editor.inspect(original);List<Edit> edits=new ArrayList<>();Set<String> changed=new HashSet<>();
        for(Paragraph p:index.getParagraphs())if(p.getPart().matches("word/footer[0-9]+\\.xml")&&p.getCatalogText().contains("20250101")){edits.add(label("contract-"+edits.size(),p,"20250101","TEST-CONTRACT-42"));changed.add(p.getPart());}
        assertFalse(edits.isEmpty());DocxEditResult result=editor.apply(original,new SourceEditBatch(index.getSourceSha256(),edits));
        Map<String,byte[]> before=DocxTemplateEditorTest.entries(original),after=DocxTemplateEditorTest.entries(result.getDocxBytes());
        for(String part:before.keySet())if(!changed.contains(part))assertArrayEquals(before.get(part),after.get(part),part);
        for(Paragraph p:result.getIndex().getParagraphs())if(changed.contains(p.getPart()))assertFalse(p.getCatalogText().contains("20250101"));
        assertEquals(edits.size(),result.getLedger().size());
    }
}
