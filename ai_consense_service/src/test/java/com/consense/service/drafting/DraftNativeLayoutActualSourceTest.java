package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.TemplateIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual pinned source registry construction only; no renderer, service or business acceptance. */
class DraftNativeLayoutActualSourceTest {
    @Test void actualSccSourceBuildsEveryRegisteredNativeLayoutAnchorIncludingTabs()throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(sourceDir!=null&&!sourceDir.trim().isEmpty(),"Explicit actual sourceDir is required for this source contract");
        Path path=Paths.get(sourceDir,"06_Special Conditions of Contract (SCC).docx");
        assertTrue(Files.isRegularFile(path),"Explicit sourceDir must contain the actual SCC original");
        byte[] original=Files.readAllBytes(path);
        String expected="9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208";
        assertEquals(expected,DraftPdfConverter.sha256(original));
        TemplateIndex index=new DocxTemplateEditor().inspect(original);
        assertEquals(expected,index.getSourceSha256());
        String table="word/document.xml#/w:document[1]/w:body[1]/w:tbl[1]";
        assertEquals("a.\tStructural integrity of the Play Equipment",index.paragraph(table+"/w:tr[2]/w:tc[1]/w:p[1]").getText());
        assertEquals("b.\tTimber parts of the Play Equipment",index.paragraph(table+"/w:tr[3]/w:tc[1]/w:p[1]").getText());
        assertNotNull(assertDoesNotThrow(()->DraftNativeLayout.plan(index,"SCC","libreoffice")));
    }
}
