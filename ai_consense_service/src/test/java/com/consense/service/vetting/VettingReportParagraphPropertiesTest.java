package com.consense.service.vetting;

import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import com.consense.web.dto.VettingDtos.FindingVO;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;

/** Inspect the emitted package XML so duplicate singleton properties cannot hide behind POI getters. */
class VettingReportParagraphPropertiesTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void headingsAndRepeatedTableHeadersKeepOneParagraphPropertyContainer() throws Exception {
        Project project=new Project();project.setId("authored-paragraph-properties");
        FindingVO finding=new FindingVO();finding.setCode("VT-PPR");finding.setFileKey("SCT");
        finding.setTitle(LocalizedText.same("Retain the original source condition."));
        finding.setBody(LocalizedText.same("The recorded source requires a project-team review."));
        finding.setEvidence(Collections.singletonList(new FindingEvidence("source","authored-source","SCT",
                "authored-source.docx",null,"body/4/paragraph","The authored source condition remains exact.",true,"authored-source-hash",null)));
        byte[] bytes=new VettingReportWriter().docx(project,Collections.singletonList(finding),null,"en");
        Document xml=mainDocument(bytes);
        Element appendix=null;
        NodeList paragraphs=xml.getElementsByTagNameNS(W,"p");
        for(int at=0;at<paragraphs.getLength();at++) {
            Element paragraph=(Element)paragraphs.item(at);
            if("APPENDIX I".equals(text(paragraph)))appendix=paragraph;
        }
        assertNotNull(appendix,"The real report's appendix heading must be emitted.");
        Element properties=singleProperties(appendix,"APPENDIX I heading");
        assertEquals(1,directCount(properties,"pStyle"));
        assertEquals(1,directCount(properties,"spacing"));
        assertEquals(1,directCount(properties,"pageBreakBefore"));
        assertEquals(1,directCount(properties,"keepNext"),"Heading keep must share its style/spacing container.");

        int checkedParagraphs=0;boolean commentsHeaderChecked=false;
        NodeList rows=xml.getElementsByTagNameNS(W,"tr");
        for(int at=0;at<rows.getLength();at++) {
            Element row=(Element)rows.item(at);
            // Single-column owner records have a separate continuation policy.
            if(row.getElementsByTagNameNS(W,"tblHeader").getLength()==0||directCount(row,"tc")==1)continue;
            commentsHeaderChecked|=text(row).contains("Comments");
            NodeList headers=row.getElementsByTagNameNS(W,"p");
            for(int p=0;p<headers.getLength();p++) {
                Element header=(Element)headers.item(p);
                Element headerProperties=singleProperties(header,"Repeated table header: "+text(header));
                assertEquals(1,directCount(headerProperties,"spacing"));
                assertEquals(1,directCount(headerProperties,"keepNext"),"Table-header keep must share its spacing container: "+text(header));
                checkedParagraphs++;
            }
        }
        assertTrue(commentsHeaderChecked,"The five-column comments table's repeated header must be checked.");
        assertTrue(checkedParagraphs>=2,"Both comments/checklist headers must remain covered.");
    }

    private Element singleProperties(Element paragraph,String context) {
        assertEquals(1,directCount(paragraph,"pPr"),context+": w:pPr is a singleton; duplicates can hide keep rules.");
        return (Element)paragraph.getElementsByTagNameNS(W,"pPr").item(0);
    }
    private int directCount(Element parent,String local) {
        int count=0;
        for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling())
            if(child instanceof Element&&W.equals(child.getNamespaceURI())&&local.equals(child.getLocalName()))count++;
        return count;
    }
    private String text(Element element) {
        StringBuilder value=new StringBuilder();NodeList texts=element.getElementsByTagNameNS(W,"t");
        for(int at=0;at<texts.getLength();at++)value.append(texts.item(at).getTextContent());
        return value.toString();
    }
    private Document mainDocument(byte[] docx) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(docx))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;)if("word/document.xml".equals(entry.getName())) {
                DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                return factory.newDocumentBuilder().parse(zip);
            }
        }
        throw new AssertionError("The generated DOCX must contain word/document.xml.");
    }
}
