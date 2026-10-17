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

    @Test void sameTextAndBlankCellsKeepActualHeaderFooterPartIdentityAfterSaveRead() throws Exception {
        ByteArrayOutputStream source=new ByteArrayOutputStream();
        try(XWPFDocument doc=new XWPFDocument()){
            doc.createTable(1,2).getRow(0).getCell(0).setText("Repeated cell text");
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createTable(1,2).getRow(0).getCell(0).setText("Repeated cell text");
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.FIRST).createTable(1,2).getRow(0).getCell(0).setText("Repeated cell text");
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createTable(1,2).getRow(0).getCell(0).setText("Repeated cell text");
            doc.write(source);
        }
        byte[] original=source.toByteArray();java.util.Map<String,String> hashes=new java.util.HashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(original))){
            ZipEntry entry;byte[] buffer=new byte[8192];while((entry=zip.getNextEntry())!=null){
                ByteArrayOutputStream raw=new ByteArrayOutputStream();int n;while((n=zip.read(buffer))!=-1)raw.write(buffer,0,n);
                hashes.put(entry.getName(),DocumentParseProbe.sha256(raw.toByteArray()));
            }
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);
        DocumentParser.ParsedDocument parsed=parser.parse("authored-stories.docx",original);
        java.util.Set<String> parts=new java.util.HashSet<>(),identities=new java.util.HashSet<>();int cells=0,empty=0;
        for(DocumentBlock block:parsed.getBlocks())for(DocumentBlock.WordCell cell:block.getWordCells()){
            com.fasterxml.jackson.databind.JsonNode position=cell.getSourcePosition();assertNotNull(position);
            assertEquals("docx_native",position.path("kind").asText());assertEquals(parsed.getSourceSha256(),position.path("sourceSha256").asText());
            assertEquals(block.getSourcePosition().path("partName"),position.path("partName"));
            assertEquals(hashes.get(position.path("partName").asText()),position.path("partSha256").asText());
            assertTrue(position.path("nodePath").asText().endsWith("}tc["+(cell.getCellIndex()+1)+"]"));
            assertEquals(position.path("tableNodePath").asText()+"/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}tr[1]",position.path("rowNodePath").asText());
            assertFalse(position.has("parentCellNodePath"));assertNull(block.getPageNo());
            assertEquals(position,JsonUtils.read(JsonUtils.write(cell),DocumentBlock.WordCell.class).getSourcePosition());
            assertTrue(identities.add(position.path("partName").asText()+"\n"+position.path("nodePath").asText()));
            parts.add(position.path("partName").asText());cells++;if(cell.getText().isEmpty())empty++;
        }
        assertEquals(4,parts.size());assertEquals(8,cells);assertEquals(4,empty);
        assertEquals(JsonUtils.write(parsed),JsonUtils.write(parser.parse("authored-stories.docx",original)));verifyNoInteractions(ocr);
    }
    @Test void legacyWordCellWithoutSourcePositionKeepsTheOptionalFieldAbsent() {
        String legacy="{\"location\":\"legacy/cell/0\",\"tableLocation\":\"legacy/table\",\"text\":\"unchanged\"}";
        DocumentBlock.WordCell cell=JsonUtils.read(legacy,DocumentBlock.WordCell.class);assertNull(cell.getSourcePosition());
        com.fasterxml.jackson.databind.JsonNode stored=JsonUtils.mapper().valueToTree(cell);assertFalse(stored.has("sourcePosition"));
        assertEquals(stored,JsonUtils.mapper().valueToTree(JsonUtils.read(JsonUtils.write(cell),DocumentBlock.WordCell.class)));
    }
    @Test void emptyLoadedStoriesAndPhysicalSeparatorTablesSurviveCoverageSaveReadWithoutBodyAuthority() throws Exception {
        ByteArrayOutputStream source=new ByteArrayOutputStream();
        try(XWPFDocument doc=new XWPFDocument()){
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph();
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph();
            org.apache.poi.xwpf.usermodel.XWPFFootnote first=doc.createFootnote();first.getCTFtnEdn().setId(java.math.BigInteger.valueOf(9));first.createParagraph();
            org.apache.poi.xwpf.usermodel.XWPFFootnote separator=doc.createFootnote();separator.getCTFtnEdn().setId(java.math.BigInteger.ZERO);
            separator.getCTFtnEdn().addNewTbl().addNewTr().addNewTc().addNewP().addNewR().addNewT().setStringValue("Physical separator is not body authority");
            org.apache.poi.xwpf.usermodel.XWPFFootnote last=doc.createFootnote();last.getCTFtnEdn().setId(java.math.BigInteger.valueOf(2));last.createParagraph();
            org.apache.poi.xwpf.usermodel.XWPFEndnote end=doc.createEndnote();end.getCTFtnEdn().setId(java.math.BigInteger.valueOf(5));end.createParagraph();
            org.apache.poi.xwpf.usermodel.XWPFEndnote endSeparator=doc.createEndnote();endSeparator.getCTFtnEdn().setId(java.math.BigInteger.valueOf(-1));endSeparator.createParagraph();
            doc.write(source);
        }
        byte[] bytes=source.toByteArray();OcrClient ocr=mock(OcrClient.class);DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);
        DocumentParser.ParsedDocument parsed=parser.parse("empty-stories.docx",bytes);assertTrue(parsed.getBlocks().isEmpty());assertEquals("",parsed.getText());
        com.fasterxml.jackson.databind.JsonNode inventory=parsed.getCoverage().getNativeDocxTables();assertEquals(5,inventory.path("parts").size());
        java.util.Map<String,byte[]> raw=new java.util.HashMap<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=in.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);raw.put(entry.getName(),out.toByteArray());}}
        int roots=0,physicalMembers=0;
        for(com.fasterxml.jackson.databind.JsonNode part:inventory.path("parts")){
            assertEquals(DocumentParseProbe.sha256(raw.get(part.path("partName").asText())),part.path("partSha256").asText());
            assertFalse(part.path("loadedRoots").isEmpty());roots+=part.path("loadedRoots").size();physicalMembers+=part.path("members").size();
            if(part.path("partName").asText().equals("word/footnotes.xml")){
                assertEquals(java.util.Arrays.asList("9","0","2"),java.util.Arrays.asList(part.path("loadedRoots").get(0).path("noteId").asText(),part.path("loadedRoots").get(1).path("noteId").asText(),part.path("loadedRoots").get(2).path("noteId").asText()));
                assertEquals(3,part.path("members").size());for(com.fasterxml.jackson.databind.JsonNode member:part.path("members"))assertTrue(member.path("retained").isEmpty());
            }else assertTrue(part.path("members").isEmpty());
        }
        assertEquals(8,roots);assertEquals(3,physicalMembers);
        ParseCoverage stored=JsonUtils.read(JsonUtils.write(parsed.getCoverage()),ParseCoverage.class);assertEquals(inventory,stored.getNativeDocxTables());
        assertEquals(JsonUtils.write(parsed),JsonUtils.write(parser.parse("empty-stories.docx",bytes)));verifyNoInteractions(ocr);
    }
    @Test void optionalInventoryDoesNotAlterLegacyCoverageSerializationWhenAbsent() {
        ParseCoverage legacy=new ParseCoverage();legacy.setComplete(false);legacy.getLimitations().add("Original legacy declaration");
        String saved=JsonUtils.write(legacy);assertFalse(JsonUtils.parse(saved).has("nativeDocxTables"));
        ParseCoverage replay=JsonUtils.read(saved,ParseCoverage.class);assertNull(replay.getNativeDocxTables());assertEquals(saved,JsonUtils.write(replay));
        com.fasterxml.jackson.databind.node.ObjectNode inventory=JsonUtils.mapper().createObjectNode().put("version","docx-native-tables-v1");inventory.putArray("parts");
        replay.setNativeDocxTables(inventory);com.fasterxml.jackson.databind.node.ObjectNode newCoverage=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(JsonUtils.write(replay));
        assertEquals(inventory,newCoverage.remove("nativeDocxTables"));assertEquals(JsonUtils.parse(saved),newCoverage);
        replay.setNativeDocxTables(null);assertEquals(saved,JsonUtils.write(replay));
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
        assertEquals(2,parsed.getBlocks().size());assertTrue(parsed.getCoverage().isComplete());
        assertFalse(parsed.getText().contains("12"));
        String retained=JsonUtils.write(parsed.getBlocks().get(1));
        assertTrue(retained.contains("wordNativeObjects"));assertTrue(retained.contains("oMath"));
        assertTrue(parsed.getText().contains("(1)/(2)"));assertTrue(retained.contains("native_ooxml_projected"));
    }
}
