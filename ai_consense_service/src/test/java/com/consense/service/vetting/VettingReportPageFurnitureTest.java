package com.consense.service.vetting;

import com.consense.domain.Project;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Word-field definitions and native glyph sizes; controlled renderer acceptance remains separate. */
class VettingReportPageFurnitureTest {
    private Project project(){Project project=new Project();project.setId("authored-furniture");project.setContractNo("DISPLAY-METADATA-MUST-NOT-BECOME-ADOPTED");return project;}
    private XWPFDocument word()throws Exception{return new XWPFDocument(new ByteArrayInputStream(new VettingReportWriter().docx(project(),Collections.emptyList(),null,"en")));}

    @Test void wordPageFieldsHaveExplicit11ptTimesResultsAndRemainDynamic()throws Exception{
        try(XWPFDocument word=word()){
            XWPFParagraph page=word.getFooterList().get(0).getParagraphs().get(0);
            List<CTSimpleField> fields=page.getCTP().getFldSimpleList();assertEquals(2,fields.size());assertEquals("PAGE",fields.get(0).getInstr());assertEquals("NUMPAGES",fields.get(1).getInstr());
            for(CTSimpleField field:fields){
                assertTrue(field.sizeOfRArray()>0,"WORD_PAGE_FIELD_EXPLICIT_FONT: dynamic field must have an explicitly formatted result run");
                for(CTR result:field.getRList()){
                    XWPFRun run=new XWPFRun(result,page);assertEquals(11,run.getFontSize());assertEquals("Times New Roman",run.getFontFamily());
                    assertEquals("",run.text(),"Do not invent a cached page or total to obtain a styled field");
                }
            }
            for(XWPFRun run:page.getRuns()){assertEquals(11,run.getFontSize());assertEquals("Times New Roman",run.getFontFamily());}
            assertEquals(ParagraphAlignment.CENTER,page.getAlignment());
        }
    }

    @Test void wordHeaderUses11ptAndKeepsUnconfirmedIdentitySeparateFromProjectDisplayMetadata()throws Exception{
        try(XWPFDocument word=word()){
            assertEquals(1,word.getHeaderList().size());XWPFHeader header=word.getHeaderList().get(0);
            assertTrue(header.getText().contains("Contract No. Not confirmed (identity check not recorded)"));assertFalse(header.getText().contains("DISPLAY-METADATA"));
            for(XWPFParagraph paragraph:header.getParagraphs())for(XWPFRun run:paragraph.getRuns()){
                assertEquals(11,run.getFontSize());assertEquals("Times New Roman",run.getFontFamily());
            }
        }
    }

    @Test void nativeHeaderAndCompletePageFooterUseTheSame11ptOnEveryActualPage()throws Exception{
        byte[] bytes=new VettingReportWriter().pdf(project(),Collections.emptyList(),null,"en");
        try(PDDocument pdf=PDDocument.load(bytes)){
            int total=pdf.getNumberOfPages();assertTrue(total>1);int[] counted={0};
            PDFTextStripper stripper=new PDFTextStripper(){
                @Override protected void processTextPosition(TextPosition text){
                    if(!text.getUnicode().trim().isEmpty()&&(text.getYDirAdj()<40||text.getYDirAdj()>pdf.getPage(getCurrentPageNo()-1).getMediaBox().getHeight()-40)){
                        assertEquals(11.0f,text.getFontSizeInPt(),0.01f,"NATIVE_PAGE_FURNITURE_FONT: every header/footer glyph must have the common size");counted[0]++;
                    }
                    super.processTextPosition(text);
                }
            };
            for(int page=1;page<=total;page++){
                stripper.setStartPage(page);stripper.setEndPage(page);String text=stripper.getText(pdf).replaceAll("\\s+","");
                assertTrue(text.contains("Page"+page+"of"+total));assertTrue(text.contains("ContractNo.Notconfirmed(identitychecknotrecorded)"));assertFalse(text.contains("DISPLAY-METADATA"));
            }
            assertTrue(counted[0]>0);
        }
    }
}
