package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WordNativeObjectsTest {
    @Test void normalDocxPositionsNameTheExactOriginalPartAndRepeatedNativeOccurrences() throws Exception {
        ByteArrayOutputStream source=new ByteArrayOutputStream();
        try(XWPFDocument doc=new XWPFDocument()){
            doc.createParagraph().createRun().setText("Repeated source clause");doc.createParagraph().createRun().setText("Repeated source clause");
            doc.createTable(1,2).getRow(0).getCell(0).setText("Repeated source clause");
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Repeated source clause");
            doc.write(source);
        }
        byte[] original=source.toByteArray();java.util.Map<String,byte[]> parts=new java.util.HashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(original))){ZipEntry entry;byte[] buffer=new byte[8192];while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream raw=new ByteArrayOutputStream();int n;while((n=zip.read(buffer))!=-1)raw.write(buffer,0,n);parts.put(entry.getName(),raw.toByteArray());}}
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),mock(OcrClient.class,CALLS_REAL_METHODS)).parse("authored.docx",original);
        String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";
        java.util.List<com.fasterxml.jackson.databind.JsonNode> positions=new java.util.ArrayList<>();
        for(DocumentBlock block:parsed.getBlocks()){
            com.fasterxml.jackson.databind.JsonNode position=JsonUtils.mapper().valueToTree(block).path("sourcePosition");positions.add(position);
            assertEquals("docx_native",position.path("kind").asText());assertEquals(DocumentParseProbe.sha256(original),position.path("sourceSha256").asText());
            assertEquals(DocumentParseProbe.sha256(parts.get(position.path("partName").asText())),position.path("partSha256").asText());assertNull(block.getPageNo());
            DocumentBlock stored=JsonUtils.read(JsonUtils.write(block),DocumentBlock.class);assertEquals(position,JsonUtils.mapper().valueToTree(stored).path("sourcePosition"));
        }
        assertEquals("/"+ns+"document[1]/"+ns+"body[1]/"+ns+"p[1]",positions.get(0).path("nodePath").asText());
        assertEquals("/"+ns+"document[1]/"+ns+"body[1]/"+ns+"p[2]",positions.get(1).path("nodePath").asText());
        assertEquals("/"+ns+"document[1]/"+ns+"body[1]/"+ns+"tbl[1]/"+ns+"tr[1]",positions.get(2).path("nodePath").asText());
        assertEquals("word/document.xml",positions.get(0).path("partName").asText());assertEquals("word/header1.xml",positions.get(3).path("partName").asText());
        assertEquals("/"+ns+"hdr[1]",positions.get(3).path("nodePath").asText());
    }
    @Test void imageOnlyOccurrenceAndPackageAssetAreRetainedAsUnrendered() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",png);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        try(XWPFDocument doc=new XWPFDocument()) {
            doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(png.toByteArray()),
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,"authored.png",152400,152400);
            doc.write(source);
        }
        OcrClient ocr=mock(OcrClient.class,CALLS_REAL_METHODS);
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parse("authored.docx",source.toByteArray());
        assertEquals(1,parsed.getBlocks().size());
        assertFalse(parsed.getCoverage().isComplete());
        String retained=JsonUtils.write(parsed.getBlocks().get(0));
        assertTrue(retained.contains("wordNativeObjects")); assertTrue(retained.contains("native_ooxml_unrendered"));
        assertTrue(retained.contains("/word/media/image1.png"));
        assertTrue(retained.contains(DocumentParseProbe.sha256(png.toByteArray())));
        assertEquals(0,parsed.getPageCount()); verifyNoInteractions(ocr);
    }

    @Test void ommlFractionIsNotFlattenedIntoAnInventedLiteralNumber() throws Exception {
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        try(XWPFDocument doc=new XWPFDocument()){doc.createParagraph().createRun().setText("Authored formula follows.");doc.write(source);}
        String formula="<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                +"xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><m:oMath><m:f>"
                +"<m:num><m:r><m:t>1</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den>"
                +"</m:f></m:oMath></w:p>";
        ByteArrayOutputStream updated=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(source.toByteArray()));ZipOutputStream out=new ZipOutputStream(updated)){
            ZipEntry entry;byte[] buffer=new byte[8192];while((entry=in.getNextEntry())!=null){
                ByteArrayOutputStream data=new ByteArrayOutputStream();int n;while((n=in.read(buffer))!=-1)data.write(buffer,0,n);
                byte[] bytes=data.toByteArray();if("word/document.xml".equals(entry.getName()))
                    bytes=new String(bytes,StandardCharsets.UTF_8).replace("</w:body>",formula+"</w:body>").getBytes(StandardCharsets.UTF_8);
                out.putNextEntry(new ZipEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),mock(OcrClient.class,CALLS_REAL_METHODS))
                .parse("authored-formula.docx",updated.toByteArray());
        assertEquals(2,parsed.getBlocks().size());assertFalse(parsed.getCoverage().isComplete());
        assertFalse(parsed.getText().contains("12"));
        String retained=JsonUtils.write(parsed.getBlocks().get(1));
        assertTrue(retained.contains("wordNativeObjects"));assertTrue(retained.contains("oMath"));
        assertTrue(retained.contains("native_ooxml_unrendered"));
    }
}
