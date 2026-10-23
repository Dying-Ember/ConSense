package com.consense.document;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocxLibreOfficeRenderingCopyTest {
    @Test void onlyEligibleLatinHintsChangeAndSavedBytesTextAndLayoutStayUntouched() throws Exception {
        byte[] original=source(),before=original.clone();DocxTemplateEditor editor=new DocxTemplateEditor();
        DocxTemplateEditor.DocxEditResult result=editor.libreOfficeRenderingCopy(original);
        assertArrayEquals(before,original);assertNotEquals(editor.inspect(original).getSourceSha256(),result.getDocxSha256());
        assertEquals(editor.inspect(original).getSourceSha256(),result.getOriginalSourceSha256());
        assertEquals(1,result.getNativeLayoutLedger().size());
        List<DocxTemplateEditor.Paragraph> old=editor.inspect(original).getParagraphs(),now=result.getIndex().getParagraphs();
        assertEquals(old.size(),now.size());for(int i=0;i<old.size();i++)assertEquals(old.get(i).getText(),now.get(i).getText());
        Map<String,byte[]> entries=entries(result.getDocxBytes());String xml=new String(entries.get("word/document.xml"),StandardCharsets.UTF_8);
        assertEquals(2,xml.split("hint=",-1).length-1,"Chinese and hyperlink runs remain unchanged");
        assertTrue(xml.contains("as detailed"));assertTrue(xml.contains("Times New Roman"));assertTrue(xml.contains("720"));
        assertArrayEquals(entries(original).get("word/styles.xml"),entries.get("word/styles.xml"));
        assertArrayEquals(result.getDocxBytes(),editor.libreOfficeRenderingCopy(result.getDocxBytes()).getDocxBytes(),"No-op rendering preparation preserves exact bytes");
    }
    @Test void invalidPackagesAreRejected() {assertThrows(DocxTemplateEditor.EditException.class,()->new DocxTemplateEditor().libreOfficeRenderingCopy(new byte[]{1,2,3}));}
    private byte[] source()throws Exception {
        String font="<w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:hint=\"eastAsia\"/><w:sz w:val=\"24\"/></w:rPr>";
        Map<String,String> parts=new LinkedHashMap<>();
        parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
        parts.put("word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:pPr><w:ind w:left=\"720\"/></w:pPr><w:r>"+font+"<w:t>English as detailed</w:t><w:tab/></w:r></w:p><w:p><w:r>"+font+"<w:t>中文 English</w:t></w:r></w:p><w:p><w:hyperlink><w:r>"+font+"<w:t>Linked English</w:t></w:r></w:hyperlink></w:p></w:body></w:document>");
        parts.put("word/styles.xml","<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>");
        try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,String> p:parts.entrySet()){zip.putNextEntry(new ZipEntry(p.getKey()));zip.write(p.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}zip.finish();return out.toByteArray();}
    }
    private Map<String,byte[]> entries(byte[] bytes)throws Exception {Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null)result.put(e.getName(),zip.readAllBytes());}return result;}
}
