package com.consense.service.vetting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/** Explicit software renderer replacement for workflow tests; no native layout acceptance. */
public final class VettingDocxPdfFixture {
    private VettingDocxPdfFixture() { }
    public static byte[] render(byte[] exactDocx) throws IOException {
        List<String> lines=new ArrayList<>();
        try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(exactDocx));XWPFWordExtractor text=new XWPFWordExtractor(document)) {
            for(String paragraph:text.getText().split("\\R",-1)) {
                StringBuilder line=new StringBuilder();
                for(String word:paragraph.trim().split("\\s+")) {
                    if(line.length()>0&&line.length()+word.length()+1>95){lines.add(line.toString());line.setLength(0);}
                    if(line.length()>0)line.append(' ');line.append(word);
                }
                lines.add(line.toString());
            }
        }
        try(PDDocument pdf=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            pdf.getDocumentInformation().setTitle("Authored software renderer fixture");
            pdf.getDocumentInformation().setCustomMetadataValue("AuthoredUnrelatedMetadata","retain this value");
            for(int start=0;start<lines.size();start+=55) {
                PDPage page=new PDPage();pdf.addPage(page);
                try(PDPageContentStream content=new PDPageContentStream(pdf,page)) {
                    content.beginText();content.newLineAtOffset(36,760);
                    for(String line:lines.subList(start,Math.min(start+55,lines.size()))) {
                        // Match the existing writer's explicit standard-font handling of checklist marks.
                        for(String run:line.split("(?=[\u2713\u2717])|(?<=[\u2713\u2717])"))if(!run.isEmpty()) {
                            content.setFont("\u2713".equals(run)||"\u2717".equals(run)?PDType1Font.ZAPF_DINGBATS:PDType1Font.TIMES_ROMAN,9);content.showText(run);
                        }
                        content.newLineAtOffset(0,-12);
                    }
                    content.endText();
                }
            }
            pdf.save(out);return out.toByteArray();
        }
    }
}
