package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.TemplateIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.w3c.dom.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual pinned source registry construction only; no renderer, service or business acceptance. */
class DraftNativeLayoutActualSourceTest {
    @Test void nttPreviewPreservesGuidanceAndInlineFooterSpacerGeometry()throws Exception {
        String sourceDir=System.getProperty("consense.acceptance.sourceDir");
        assumeTrue(sourceDir!=null&&!sourceDir.trim().isEmpty(),"Explicit actual sourceDir is required for this source contract");
        Path path=Paths.get(sourceDir,"01_Notes to Tenderers (NTT).docx");
        byte[] original=Files.readAllBytes(path),before=original.clone();DocxTemplateEditor editor=new DocxTemplateEditor();
        assertEquals("60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c",DraftPdfConverter.sha256(original));
        TemplateIndex index=editor.inspect(original);
        DocxTemplateEditor.NativeLayoutPlan layout=DraftNativeLayout.originalPreviewPlan(index,"libreoffice");assertNotNull(layout);
        DocxTemplateEditor.DocxEditResult preview=DraftDocumentBindings.sourceWorkingCopyResult(original,DraftDocumentBindings.source("64",index),layout);
        List<Map<String,String>> sourceSpacing=paragraphSpacing(original),previewSpacing=paragraphSpacing(preview.getDocxBytes());
        assertEquals("280",sourceSpacing.get(78).get("line"),"P79 is an intentional 14-point guidance alignment spacer");
        assertEquals("280",previewSpacing.get(preview.getIndex().paragraph(index.mainParagraph(79).getId()).getOrdinal()-1).get("line"),"Do not flatten the guidance spacer to one twip");
        Set<String> removed=new HashSet<>(),inserted=new HashSet<>();
        for(DocxTemplateEditor.NativeLayoutChange change:preview.getNativeLayoutLedger()) {
            assertEquals("APPLIED",change.getStatus());
            if("remove_verified_empty".equals(change.getAction()))removed.add(change.getSource().getId());
            else if("page_break_before".equals(change.getAction()))inserted.add(change.getGeneratedId());
            else fail("Unexpected NTT preview layout change: "+change.getAction());
        }
        assertEquals(2,removed.size());assertEquals(9,inserted.size());
        List<DocxTemplateEditor.Paragraph> old=new ArrayList<>(),now=new ArrayList<>();
        for(DocxTemplateEditor.Paragraph paragraph:index.getMainParagraphs())if(!removed.contains(paragraph.getId()))old.add(paragraph);
        for(DocxTemplateEditor.Paragraph paragraph:preview.getIndex().getMainParagraphs())if(!inserted.contains(paragraph.getId()))now.add(paragraph);
        assertEquals(old.size(),now.size());assertEquals(index.getMainParagraphs().size(),sourceSpacing.size());
        for(int i=0;i<old.size();i++) {
            assertEquals(old.get(i).getText(),now.get(i).getText(),old.get(i).getId());
            assertEquals(sourceSpacing.get(old.get(i).getOrdinal()-1),previewSpacing.get(now.get(i).getOrdinal()-1),old.get(i).getId());
        }
        byte[] changed=original.clone();changed[changed.length-1]^=1;
        assertNull(DraftNativeLayout.plan(changed,"NTT","libreoffice"),"An unknown byte revision must not inherit the registered layout");
        assertArrayEquals(before,original);assertArrayEquals(before,Files.readAllBytes(path));
    }
    private static List<Map<String,String>> paragraphSpacing(byte[] bytes)throws Exception {
        List<Map<String,String>> result=new ArrayList<>();
        try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            NodeList paragraphs=((Element)document.getDocument().getDomNode()).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","p");
            for(int i=0;i<paragraphs.getLength();i++) {
                Element properties=child((Element)paragraphs.item(i),"pPr"),spacing=properties==null?null:child(properties,"spacing");Map<String,String> attributes=new TreeMap<>();
                if(spacing!=null)for(int j=0;j<spacing.getAttributes().getLength();j++){Node attribute=spacing.getAttributes().item(j);if("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(attribute.getNamespaceURI()))attributes.put(attribute.getLocalName(),attribute.getNodeValue());}
                result.add(attributes);
            }
        }
        return result;
    }
    private static Element child(Element parent,String name) {
        for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling())if(node instanceof Element&&name.equals(node.getLocalName())&&"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(node.getNamespaceURI()))return (Element)node;
        return null;
    }
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
