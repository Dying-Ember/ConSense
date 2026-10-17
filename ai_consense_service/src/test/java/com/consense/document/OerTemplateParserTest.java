package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OerTemplateParserTest {
    @Test void nativeFillSpacesKeepTheSameParserProjectionBeforeNormalization() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(XWPFDocument document=new XWPFDocument()){
            document.createParagraph().createRun().setText("Special Conditions of Tender");
            XWPFRun run=document.createParagraph().createRun();run.setText("A/     (*Mr/Ms                     ), telephone          .");
            document.write(out);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);
        DocumentParser.ParsedDocument parsed=parser.parseTemplate("SCT","authored.docx",out.toByteArray());
        JsonNode member=parsed.getCoverage().getNativeDocxParagraphs().path("paragraphs").get(1),raw=member.path("preNormalizedTextObservation");
        assertEquals("A/     (*Mr/Ms                     ), telephone          .\n",raw.path("text").asText());
        assertEquals(raw.path("text"),raw.path("originalText"));assertEquals("",raw.path("deletedText").asText());assertEquals("",raw.path("strikeText").asText());
        assertEquals("A/ (*Mr/Ms ), telephone .",member.path("textObservation").path("text").asText());
        assertTrue(raw.path("meaning").asText().contains("not raw XML"));
        ObjectNode actual=JsonUtils.mapper().valueToTree(parsed);ObjectNode coverage=(ObjectNode)actual.path("coverage");coverage.remove("nativeDocxParagraphs");coverage.remove("templateEvidence");
        assertEquals(JsonUtils.mapper().valueToTree(parser.parse("authored.docx",out.toByteArray())),actual);verifyNoInteractions(ocr);
    }
    @Test void optInAddsEvidenceWithoutChangingAnyExistingNormalParseField() throws Exception {
        byte[] bytes=word();OcrClient ocr=mock(OcrClient.class);DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);
        DocumentParser.ParsedDocument ordinary=parser.parse("authored.docx",bytes),template=parser.parseTemplate(null,"authored.docx",bytes);
        ObjectNode actual=JsonUtils.mapper().valueToTree(template);
        ObjectNode coverage=(ObjectNode)actual.path("coverage");
        assertNotNull(coverage.remove("nativeDocxParagraphs"));assertNotNull(coverage.remove("templateEvidence"));
        assertEquals(JsonUtils.mapper().valueToTree(ordinary),actual);
        assertNull(ordinary.getCoverage().getNativeDocxParagraphs());assertNull(ordinary.getCoverage().getTemplateEvidence());
        assertEquals("SCC",template.getCoverage().getTemplateEvidence().path("sourceBinding").path("fileKey").asText());
        verifyNoInteractions(ocr);
    }

    @Test void byteFormatWinsOverMisleadingOrUnknownUploadNames() throws Exception {
        byte[] bytes=word();OcrClient ocr=mock(OcrClient.class);DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);
        JsonNode expected=JsonUtils.mapper().valueToTree(parser.parseTemplate(null,"authored.docx",bytes));
        assertEquals(expected,JsonUtils.mapper().valueToTree(parser.parseTemplate(null,"misnamed.pdf",bytes)));
        assertEquals(expected,JsonUtils.mapper().valueToTree(parser.parseTemplate(null,"unknown.bin",bytes)));
        verifyNoInteractions(ocr);
    }

    @Test void nativeParagraphsKeepEmptyMembersStoriesRawFormattingAndSourceAnchors() throws Exception {
        byte[] bytes=word();OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parseTemplate("SCC","authored.docx",bytes);
        JsonNode inventory=parsed.getCoverage().getNativeDocxParagraphs();Set<String> parts=new HashSet<>(),ids=new HashSet<>();boolean empty=false,format=false,header=false;
        for(JsonNode member:inventory.path("paragraphs")) {
            assertTrue(ids.add(member.path("id").asText()));
            JsonNode position=member.path("sourcePosition");assertEquals(parsed.getSourceSha256(),position.path("sourceSha256").asText());
            assertEquals(64,position.path("partSha256").asText().length());assertFalse(position.path("nodePath").asText().isEmpty());
            parts.add(position.path("partName").asText());
            assertFalse(member.has("physicalPage"));
            if(member.path("textObservation").path("text").asText().isEmpty())empty=true;
            if(member.path("nativeXml").asText().contains("Preserve")) {
                format=true;assertTrue(member.path("nativeXml").asText().contains("tab"));assertTrue(member.path("nativeXml").asText().contains("i"));
                assertEquals("1000",member.path("directParagraphLayout").path("ind").get(0).path("attributes").path("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}left").asText());
                assertEquals(parsed.getSourceSha256(),member.path("directParagraphLayout").path("ind").get(0).path("sourcePosition").path("sourceSha256").asText());
                assertEquals(DocumentParseProbe.sha256(member.path("nativeXml").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)),member.path("nativeXmlSha256").asText());
            }
            if("header".equals(member.path("storyKind").asText())&&member.path("textObservation").path("text").asText().contains("Guidance"))header=true;
        }
        assertEquals(5,parts.size());assertTrue(empty);assertTrue(format);assertTrue(header);
        assertEquals(inventory,JsonUtils.read(JsonUtils.write(parsed.getCoverage()),ParseCoverage.class).getNativeDocxParagraphs());
        boolean headerNote=false;for(JsonNode note:parsed.getCoverage().getTemplateEvidence().path("guidanceNotes"))if(note.path("text").asText().contains("header-specific"))headerNote=true;
        assertTrue(headerNote);verifyNoInteractions(ocr);
    }

    @Test void pdfUsesExistingNativePhysicalEvidenceWithoutInventingWordAnchors() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(PDDocument pdf=new PDDocument()){
            PDPage page=new PDPage();pdf.addPage(page);try(PDPageContentStream content=new PDPageContentStream(pdf,page)){
                content.beginText();content.setFont(PDType1Font.HELVETICA,12);content.newLineAtOffset(50,700);
                content.showText("Special Conditions of Tender");content.newLineAtOffset(0,-30);content.showText("[Guidance Note: Retain this source condition.]");content.endText();
            }pdf.save(out);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parseTemplate(null,"misnamed.docx",out.toByteArray());
        assertEquals(1,parsed.getPageCount());assertNull(parsed.getCoverage().getNativeDocxParagraphs());assertFalse(parsed.getNativePdfEvidence().isEmpty());
        JsonNode evidence=parsed.getCoverage().getTemplateEvidence();assertEquals("SCT",evidence.path("sourceBinding").path("fileKey").asText());
        assertFalse(evidence.path("sourceMembers").isEmpty());for(JsonNode member:evidence.path("sourceMembers"))assertFalse(member.has("nativeXml"));
        verify(ocr,never()).recognize(any(byte[].class));verify(ocr,never()).recognize(any(byte[].class),anyMap());
    }

    @Test void corruptAndUnsupportedBytesAndDisagreeingFamiliesAreRejected() throws Exception {
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),mock(OcrClient.class));
        assertThrows(RuntimeException.class,()->parser.parseTemplate("SCC","named.docx",new byte[]{1,2,3}));
        assertThrows(RuntimeException.class,()->parser.parseTemplate("SCC","named.docx",new byte[]{'P','K',3,4}));
        assertThrows(IllegalArgumentException.class,()->parser.parseTemplate("NTT","SCC.docx",word()));
    }

    @Test void explicitIdentityAndPriorObservationMustBelongToTheCurrentBytesAndConfiguration() throws Exception {
        byte[] bytes=word();OcrClient ocr=mock(OcrClient.class);ConsenseProperties properties=new ConsenseProperties();DocumentParser parser=new DocumentParser(properties,ocr);
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(DocumentParseProbe.sha256(bytes));identity.setKind("SCC");identity.setSourcePurpose("standard_template");identity.setIdentityStatus("sha_verified");
        DocumentParser.ParsedDocument observed=parser.observe("template.docx",bytes);
        DocumentParser.ParsedDocument parsed=parser.parseTemplate("SCC","misnamed.pdf",bytes,observed,identity);
        assertEquals("SCC",parsed.getCoverage().getReadingPlan().path("documentFamily").asText());
        byte[] changed=bytes.clone();changed[changed.length-1]^=1;
        assertThrows(IllegalArgumentException.class,()->parser.parseTemplate("SCC","same.docx",changed,observed,identity));
        identity.setSourceSha256(String.join("",Collections.nCopies(64,"a")));
        assertThrows(IllegalArgumentException.class,()->parser.parseTemplate("SCC","same.docx",bytes,null,identity));
        identity.setSourceSha256(DocumentParseProbe.sha256(bytes));identity.setKind("DRAWING");
        assertThrows(IllegalArgumentException.class,()->parser.parseTemplate("SCC","same.docx",bytes,null,identity));
        verifyNoInteractions(ocr);
    }

    private static byte[] word() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(XWPFDocument document=new XWPFDocument()){
            document.createParagraph().createRun().setText("Special Conditions of Contract");document.createParagraph();
            XWPFParagraph p=document.createParagraph();XWPFRun run=p.createRun();run.setItalic(true);run.setText("Preserve");run.addTab();run.setText("raw formatting *");
            p.getCTP().addNewPPr().addNewInd().setLeft(java.math.BigInteger.valueOf(1000));
            document.createTable(1,2).getRow(0).getCell(0).setText("[Guidance Note: Check this condition.]");
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("[Guidance Note: header-specific instruction.]");
            document.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer context");
            document.createFootnote().createParagraph().createRun().setText("Footnote context");document.createEndnote().createParagraph().createRun().setText("Endnote context");
            document.write(out);
        }return out.toByteArray();
    }
}
