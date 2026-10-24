package com.consense.service.vetting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.consense.common.LocalizedText;
import com.consense.domain.Project;
import com.consense.web.dto.VettingDtos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/** Word and PDF exports share one report outline and the same source snapshot. */
@Component
public class VettingReportWriter {
    private static final int FURNITURE_SIZE = 11;
    private static final float MARGIN = 48;
    private static final float WIDTH = PDRectangle.A4.getWidth() - MARGIN * 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
            .withZone(ZoneId.of("Asia/Shanghai"));

    @Value("${consense.report.font-path:}")
    private String fontPath = "";

    public byte[] docx(Project project, List<FindingVO> findings, VettingJobVO job, String lang) {
        return docx(project,findings,job,lang,(VettingExportProbe)null);
    }
    public byte[] docx(Project project,List<FindingVO> findings,VettingJobVO job,String lang,List<VettingChecklistCatalog.Result> checklist) {
        return docx(project,findings,job,lang,null,checklist);
    }
    byte[] docx(Project project,List<FindingVO> findings,VettingJobVO job,String lang,VettingExportProbe probe) {
        return docx(project,findings,job,lang,probe,Collections.emptyList());
    }
    byte[] docx(Project project,List<FindingVO> findings,VettingJobVO job,String lang,VettingExportProbe probe,List<VettingChecklistCatalog.Result> checklist) {
        List<Block> outline;
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"report_outline_construction","Original shared report outline construction")){outline=outline(project,findings,job,lang);}
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"docx_ooxml_generation","Original DOCX structure generation/write; not Microsoft Word pagination or visual layout QA")) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            setupWord(document);
            writeBusinessWord(document,project,findings,job,checklist);
            XWPFParagraph appendix=document.createParagraph();appendix.setPageBreak(true);
            appendix.setStyle("Heading1");appendix.createRun().setText("APPENDIX II - Review scope and evidence records");
            wordTable(document,VettingReportPresentation.readerEvidenceIndex(findings,job==null?null:job.getCoverage()));
            XWPFTable record=null;
            for (Block block : outline) {
                XWPFParagraph paragraph;
                if(block.ownerHeading) {
                    record=wordRecord(document);
                    paragraph=record.getRow(0).getCell(0).getParagraphs().get(0);
                } else if(block.owner!=null) {
                    XWPFTableRow row=record.createRow();row.setRepeatHeader(false);row.setCantSplitRow(block.quote || !block.compact);
                    paragraph=row.getCell(0).getParagraphs().get(0);
                } else {record=null;paragraph=document.createParagraph();}
                if (block.pageBreak) paragraph.setPageBreak(true);
                paragraph.setStyle(block.kind == 3 ? "Title" : block.kind == 2 ? "Heading1" : block.kind == 1 ? "Heading2" : "Normal");
                paragraph.setSpacingAfter(block.compact ? block.kind > 0 ? 20 : 0 : block.kind > 0 ? 160 : 100);
                paragraph.setSpacingBefore(block.kind > 0 && block.kind < 3 ? block.compact ? 80 : 180 : 0);
                paragraph.setSpacingBetween(block.compact ? 1.05 : 1.15);
                paragraph.getCTP().getPPr().addNewWidowControl();
                if (block.kind > 0 || block.keepWithNextOpening) {
                    paragraph.getCTP().getPPr().addNewKeepNext();
                    paragraph.getCTP().getPPr().addNewKeepLines();
                }
                if (block.quote) {paragraph.setIndentationLeft(240);paragraph.getCTP().getPPr().addNewKeepLines();}
                XWPFRun run = paragraph.createRun();
                setWordFont(run);
                run.setFontSize(block.kind == 3 ? 22 : block.kind == 2 ? 14 : 11);
                run.setBold(block.kind > 0);
                run.setItalic(block.quote);
                run.setColor(block.kind > 0 ? "000000" : "222222");
                String[] lines = block.text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) run.addBreak();
                    run.setText(lines[i]);
                }
            }
            XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
            XWPFParagraph page = footer.createParagraph();
            page.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun label = page.createRun(); setWordFont(label); label.setFontSize(FURNITURE_SIZE);
            label.setText(tr(lang, "第 ", "第 ", "Page "));
            wordPageField(page,"PAGE");
            XWPFRun separator = page.createRun(); setWordFont(separator); separator.setText("en".equalsIgnoreCase(lang)?" of ":" / "); separator.setFontSize(FURNITURE_SIZE);
            wordPageField(page,"NUMPAGES");
            XWPFHeader runningHeader=document.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph contractHeader=runningHeader.createParagraph();
            XWPFRun contractLabel=contractHeader.createRun();setWordFont(contractLabel);contractLabel.setFontSize(FURNITURE_SIZE);
            contractLabel.setText("Contract No. "+reportIdentity(job,"number"));
            document.getProperties().getCoreProperties().setTitle(tr(lang, "招标文件审查报告", "招標文件審查報告", "Tender Document Vetting Report"));
            document.getProperties().getCoreProperties().setCreator("ConSense");
            if(job!=null&&!JsonUtils.isBlankText(job.getReportSnapshotId()))document.getProperties().getCoreProperties().setIdentifier(job.getReportSnapshotId());
            // Frozen report time and ZIP timestamps keep separate Word/PDF requests on the same DOCX bytes.
            Date frozenTime=Date.from(job!=null&&job.getFinishedAt()!=null?job.getFinishedAt():job!=null&&job.getStartedAt()!=null?job.getStartedAt():Instant.EPOCH);
            document.getProperties().getCoreProperties().setCreated(Optional.of(frozenTime));
            document.getProperties().getCoreProperties().setModified(Optional.of(frozenTime));
            document.write(out);
            return stableDocx(out.toByteArray());
        } catch (IOException e) {
            if(probe!=null)probe.originalFailure("docx_original_writer_failure",e);
            throw new BizException(4100, "生成审查报告 Word 失败: " + e.getMessage());
        }
        }
    }

    private static byte[] stableDocx(byte[] generated) throws IOException {
        try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(generated));ByteArrayOutputStream bytes=new ByteArrayOutputStream();ZipOutputStream output=new ZipOutputStream(bytes)) {
            ZipEntry source;byte[] buffer=new byte[8192];int count;
            while((source=input.getNextEntry())!=null) {
                ZipEntry entry=new ZipEntry(source.getName());entry.setTime(0);output.putNextEntry(entry);
                while((count=input.read(buffer))!=-1)output.write(buffer,0,count);output.closeEntry();
            }
            output.finish();return bytes.toByteArray();
        }
    }

    public byte[] pdf(Project project, List<FindingVO> findings, VettingJobVO job, String lang) {
        return pdf(project,findings,job,lang,(VettingExportProbe)null);
    }
    public byte[] pdf(Project project,List<FindingVO> findings,VettingJobVO job,String lang,List<VettingChecklistCatalog.Result> checklist) {
        return pdf(project,findings,job,lang,null,checklist);
    }
    byte[] pdf(Project project,List<FindingVO> findings,VettingJobVO job,String lang,VettingExportProbe probe) {
        return pdf(project,findings,job,lang,probe,Collections.emptyList());
    }
    byte[] pdf(Project project,List<FindingVO> findings,VettingJobVO job,String lang,VettingExportProbe probe,List<VettingChecklistCatalog.Result> checklist) {
        List<Block> outline;
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"report_outline_construction","Original shared report outline construction")){outline=outline(project,findings,job,lang);}
        try(VettingExportProbe.Timer timer=VettingExportProbe.measure(probe,"pdf_native_generation","Original native PDF font/layout/drawing/footer/save work; not independent visual verification")) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<Block> characters=new ArrayList<>(outline);
            for(VettingReportPresentation.Table table:businessTables(findings,job,checklist)) {
                characters.add(new Block(0,table.getTitle()));for(String h:table.getHeadings())characters.add(new Block(0,h));
                for(List<String> row:table.getRows())for(String cell:row)characters.add(new Block(0,cell));
            }
            PDFont font = loadFont(document, characters);
            try (PdfCursor cursor = new PdfCursor(document, font)) {
                writeBusinessPdf(cursor,project,findings,job,checklist);
                cursor.newPage();cursor.block(new Block(2,"APPENDIX II - Review scope and evidence records"),null);
                cursor.table(VettingReportPresentation.readerEvidenceIndex(findings,job==null?null:job.getCoverage()));
                for (int at = 0; at < outline.size(); at++)
                    cursor.block(outline.get(at), at + 1 < outline.size() ? outline.get(at + 1) : null,
                            at + 2 < outline.size() ? outline.get(at + 2) : null);
            }
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    stream.beginText(); stream.setFont(font, FURNITURE_SIZE); stream.setNonStrokingColor(100, 110, 120);
                    String footerLabel=tr(lang, "第 ", "第 ", "Page ") + (i + 1) + ("en".equalsIgnoreCase(lang)?" of ":" / ") + document.getNumberOfPages();
                    stream.newLineAtOffset((PDRectangle.A4.getWidth()-pdfWidth(font,footerLabel)/1000*FURNITURE_SIZE)/2, 27);
                    pdfShowText(stream,font,footerLabel,FURNITURE_SIZE);
                    stream.endText();
                    stream.beginText();stream.setFont(font,FURNITURE_SIZE);stream.newLineAtOffset(MARGIN,PDRectangle.A4.getHeight()-27);
                    pdfShowText(stream,font,"Contract No. "+reportIdentity(job,"number"),FURNITURE_SIZE);stream.endText();
                }
            }
            document.getDocumentInformation().setTitle(tr(lang, "招标文件审查报告", "招標文件審查報告", "Tender Document Vetting Report"));
            document.getDocumentInformation().setCreator("ConSense");
            if(job!=null&&!JsonUtils.isBlankText(job.getReportSnapshotId()))document.getDocumentInformation().setCustomMetadataValue("ConSenseReportSnapshotId",job.getReportSnapshotId());
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            if(probe!=null)probe.originalFailure("pdf_original_writer_failure",e);
            throw new BizException(4100, "生成审查报告 PDF 失败: " + e.getMessage());
        }
        }
    }

    private List<VettingReportPresentation.Table> businessTables(List<FindingVO> findings,VettingJobVO job,List<VettingChecklistCatalog.Result> checklist) {
        List<VettingReportPresentation.Table> tables=new ArrayList<>(VettingReportPresentation.readerComments(findings,job==null?null:job.getCoverage()));
        tables.addAll(VettingReportPresentation.readerChecklist(checklist,automaticChecklist(job)));return tables;
    }
    private List<com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO> automaticChecklist(VettingJobVO job) {
        return job==null?Collections.emptyList():VettingChecklistAutomaticResults.aggregate(job.getStatus(),
                com.consense.common.JsonUtils.mapper().valueToTree(job.getCoverage()),com.consense.common.JsonUtils.mapper().valueToTree(job.getResult()));
    }
    private void writeBusinessWord(XWPFDocument document,Project project,List<FindingVO> findings,VettingJobVO job,List<VettingChecklistCatalog.Result> checklist) {
        wordLine(document,"Comments on Tender Documentation","Title",false);
        XWPFTable metadata=document.createTable(4,3);metadata.setWidth("100%");
        String[][] fields={{"Contract Title",reportIdentity(job,"title")},{"Contract No.",reportIdentity(job,"number")},{"Contract Manager","Not provided"},{"Surveyor","Not provided"}};
        for(int i=0;i<fields.length;i++)setWordCells(metadata.getRow(i),Arrays.asList(fields[i][0],":",fields[i][1]),new int[]{25,3,72},false);
        CTTblBorders border=metadata.getCTTbl().getTblPr().addNewTblBorders();
        for(CTBorder b:Arrays.asList(border.addNewTop(),border.addNewBottom(),border.addNewLeft(),border.addNewRight(),border.addNewInsideH(),border.addNewInsideV()))b.setVal(STBorder.NONE);
        wordLine(document,"Comments below are review suggestions. Source quotations and the review scope are retained in Appendix II. Project team remarks are recorded independently.","Normal",false);
        for(VettingReportPresentation.Table table:VettingReportPresentation.readerComments(findings,job==null?null:job.getCoverage()))wordTable(document,table);
        wordLine(document,"The project team may record whether an amendment is required for incorporation into a tender addendum in the remarks column.","Normal",false);
        wordLine(document,"APPENDIX I","Heading1",true);
        wordLine(document,"Checklist for the Contract Team for Counterchecking Various PSS Clauses of PRE Worksection and Bills No. 1 and 2","Heading2",false);
        wordLine(document,"Automatic suggestions are separate from saved human counterchecks. A saved human decision may be fulfilled, not fulfilled with a reason, or not applicable. Unchecked items remain unknown; no findings or a handled comment does not confirm fulfilment.","Normal",false);
        wordLine(document,"Pricing-document counterchecks are retained from the sample for structure only and do not expand the competition review scope; automated review excludes pricing documents and works-related project-specific specifications.","Normal",false);
        for(VettingReportPresentation.Table table:VettingReportPresentation.readerChecklist(checklist,automaticChecklist(job)))wordTable(document,table);
    }
    private void wordLine(XWPFDocument document,String text,String style,boolean pageBreak) {
        XWPFParagraph p=document.createParagraph();p.setStyle(style);p.setPageBreak(pageBreak);
        p.setSpacingAfter("Title".equals(style)?180:100);p.setSpacingBetween(1.15);
        if(style.startsWith("Heading"))p.getCTP().getPPr().addNewKeepNext();
        XWPFRun r=p.createRun();setWordFont(r);r.setFontSize("Title".equals(style)?12:style.startsWith("Heading")?12:11);r.setBold(style.startsWith("Heading"));r.setText(text);
        if("Title".equals(style)){p.setAlignment(ParagraphAlignment.CENTER);r.setUnderline(UnderlinePatterns.SINGLE);}
    }
    private void wordTable(XWPFDocument document,VettingReportPresentation.Table model) {
        if(!model.commentsHeader())wordLine(document,model.getTitle(),"Heading2",false);
        XWPFTable table=document.createTable(model.commentsHeader()?2:1,model.getHeadings().size());table.setWidth("100%");
        table.getCTTbl().getTblPr().addNewTblLayout().setType(STTblLayoutType.FIXED);
        int[] widths=model.getWidths();
        if(model.commentsHeader()) {
            XWPFTableRow first=table.getRow(0),second=table.getRow(1);
            setWordCells(first,Arrays.asList(model.sectionNumber(),model.sectionLabel(),"","",model.getHeadings().get(4)),widths,true);
            setWordCells(second,Arrays.asList("",model.getHeadings().get(1),model.getHeadings().get(2),model.getHeadings().get(3),""),widths,true);
            first.getCell(1).getCTTc().getTcPr().addNewGridSpan().setVal(BigInteger.valueOf(3));
            first.getCell(1).setWidth(String.valueOf(9986*(widths[1]+widths[2]+widths[3])/100));
            first.getCell(0).getCTTc().getTcPr().addNewVMerge().setVal(STMerge.RESTART);
            first.getCell(4).getCTTc().getTcPr().addNewVMerge().setVal(STMerge.RESTART);
            second.getCell(0).getCTTc().getTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            second.getCell(4).getCTTc().getTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            first.removeCell(3);first.removeCell(2);first.setRepeatHeader(true);second.setRepeatHeader(true);
            // Repeated vertical merges need explicit minimum row heights in paginated Word renderers.
            for(XWPFTableRow header:Arrays.asList(first,second)){header.setHeight(480);header.setHeightRule(TableRowHeightRule.AT_LEAST);}
        } else {setWordCells(table.getRow(0),model.getHeadings(),widths,true);table.getRow(0).setRepeatHeader(true);}
        for(List<String> values:model.getRows())setWordCells(table.createRow(),values,widths,false);
    }
    private XWPFTable wordRecord(XWPFDocument document) {
        // Adjacent tables can share the first table's repeated header in Word renderers.
        List<IBodyElement> body=document.getBodyElements();
        if(!body.isEmpty()&&body.get(body.size()-1) instanceof XWPFTable) {
            XWPFParagraph boundary=document.createParagraph();boundary.setStyle("Normal");
            boundary.setSpacingBefore(0);boundary.setSpacingAfter(0);boundary.setSpacingBetween(1,LineSpacingRule.EXACT);
        }
        XWPFTable table=document.createTable(1,1);table.setWidth("100%");table.setCellMargins(0,0,0,0);
        table.getCTTbl().getTblPr().addNewTblLayout().setType(STTblLayoutType.FIXED);
        table.getRow(0).getCell(0).setWidth("9986");table.getRow(0).setRepeatHeader(true);
        CTTblBorders borders=table.getCTTbl().getTblPr().addNewTblBorders();
        for(CTBorder border:Arrays.asList(borders.addNewTop(),borders.addNewBottom(),borders.addNewLeft(),borders.addNewRight(),borders.addNewInsideH(),borders.addNewInsideV()))border.setVal(STBorder.NONE);
        return table;
    }
    private void setWordCells(XWPFTableRow row,List<String> values,int[] widths,boolean header) {
        row.getCtRow().addNewTrPr();row.setCantSplitRow(true);
        // POI seeds a new row from the merged first row's physical cell count.
        while(row.getTableCells().size()<values.size())row.addNewTableCell();
        for(int i=0;i<values.size();i++) {
            XWPFTableCell cell=row.getCell(i);cell.setWidth(String.valueOf(9986*widths[i]/100));
            cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.TOP);
            XWPFParagraph p=cell.getParagraphs().get(0);p.setSpacingAfter(40);p.setSpacingBefore(40);p.setSpacingBetween(1.1);
            if(header)p.getCTP().getPPr().addNewKeepNext();
            XWPFRun r=p.createRun();setWordFont(r);r.setFontSize(11);r.setBold(header);
            String[] lines=values.get(i).split("\n",-1);for(int n=0;n<lines.length;n++){if(n>0)r.addBreak();r.setText(lines[n]);}
        }
    }
    private void writeBusinessPdf(PdfCursor cursor,Project project,List<FindingVO> findings,VettingJobVO job,List<VettingChecklistCatalog.Result> checklist) throws IOException {
        cursor.centeredTitle("Comments on Tender Documentation");
        cursor.metadata("Contract Title",reportIdentity(job,"title"));
        cursor.metadata("Contract No.",reportIdentity(job,"number"));
        cursor.metadata("Contract Manager","Not provided");cursor.metadata("Surveyor","Not provided");
        cursor.block(new Block(0,"Comments are review suggestions. Project team remarks are recorded independently; review scope and source evidence are retained in Appendix II."),null);
        for(VettingReportPresentation.Table table:VettingReportPresentation.readerComments(findings,job==null?null:job.getCoverage()))cursor.table(table);
        cursor.block(new Block(0,"The project team may record whether an amendment is required for incorporation into a tender addendum in the remarks column."),null);
        cursor.newPage();cursor.block(new Block(2,"APPENDIX I"),null);
        cursor.block(new Block(1,"Checklist for the Contract Team for Counterchecking Various PSS Clauses of PRE Worksection and Bills No. 1 and 2"),null);
        cursor.block(new Block(0,"Automatic suggestions are separate from saved human counterchecks. A saved human decision may be fulfilled, not fulfilled with a reason, or not applicable. Unchecked items remain unknown; no findings or a handled comment does not confirm fulfilment."),null);
        cursor.block(new Block(0,"Pricing-document counterchecks are retained from the sample for structure only and do not expand the competition review scope; automated review excludes pricing documents and works-related project-specific specifications."),null);
        for(VettingReportPresentation.Table table:VettingReportPresentation.readerChecklist(checklist,automaticChecklist(job)))cursor.table(table);
    }

    static String reportIdentity(VettingJobVO job,String field) {
        JsonNode review=job==null||job.getCoverage()==null?null:job.getCoverage().getPackageIdentityReview();
        if(review==null)return "Not confirmed (identity check not recorded)";
        if(!VettingService.packageIdentityReviewValid(review,job.getSourceSnapshotSha256()))return "Not confirmed (identity snapshot invalid)";
        if(!"executed".equals(review.path("executionStatus").asText()))return "Not confirmed (identity check not executed)";
        JsonNode adopted=review.path("authority").path("core").path("fields").path(field);
        return adopted.path("eligible").asBoolean()&&adopted.path("value").isTextual()?adopted.path("value").asText():"Not confirmed (adopted "+field+" unavailable)";
    }
    private void identityOutline(List<Block> blocks,VettingJobVO job) {
        blocks.add(new Block(2,"Package identity coordination"));
        JsonNode review=job==null||job.getCoverage()==null?null:job.getCoverage().getPackageIdentityReview();
        if(review==null){blocks.add(new Block(0,"This historical run has no recorded package identity coordination check. It remains unexecuted; no agreement or PASS is inferred. Project display metadata is not an adopted package identity."));return;}
        if(!VettingService.packageIdentityReviewValid(review,job.getSourceSnapshotSha256())){blocks.add(new Block(0,"The frozen package identity record could not be verified. Its comparison and adopted identity remain unconfirmed."));return;}
        blocks.add(new Block(0,"Adopted contract number: "+reportIdentity(job,"number")+"\nAdopted project or contract title: "+reportIdentity(job,"title")));
        if(!"executed".equals(review.path("executionStatus").asText())){blocks.add(new Block(0,"Identity observations were not executed for this run. No agreement or PASS is inferred."));return;}
        for(String field:Arrays.asList("number","title")) {
            JsonNode result=review.path("fields").path(field);String state=result.path("status").asText();
            String observation="candidate_difference_requires_review".equals(state)?"Observed labelled candidates differ and require source-scope review."
                    :"candidate_agreement_scope_unknown".equals(state)?"Observed labelled candidates agree, but their own-package scope remains unknown.":"No comparable own-package declaration has been established.";
            blocks.add(new Block(0,("number".equals(field)?"Contract number":"Project or contract title")+": "+observation));
        }
        blocks.add(new Block(0,"Labels are source identity candidates, not proof of package ownership. Explicit examples and external references remain separate. Alias equivalence and physical header/footer page use may remain unknown; no identity PASS is established."));
        JsonNode mirror=job.getCoverage().getPackageIdentityMirrorAudit();
        blocks.add(new Block(0,mirror!=null&&mirror.path("changed").asBoolean()?"Project display metadata changed after capture. This audit difference does not change the adopted comparison identity.":"Project display metadata is recorded separately as audit information and is not the package baseline."));
    }

    private List<Block> outline(Project project,List<FindingVO> findings,VettingJobVO job,String lang) {
        List<Block> blocks=new ArrayList<>();CoverageVO coverage=job==null?null:job.getCoverage();
        identityOutline(blocks,job);
        blocks.add(new Block(2,tr(lang,"支持审查意见的原文摘录","支持審查意見的原文摘錄","Supporting source quotations")));
        blocks.add(new Block(0,tr(lang,"编号意见均为待复核的审查建议。已有来源条款归属保留；原文位置和已定位引文本身不确认正式条款归属或法律结论，候选意见是否等价尚未确认。","編號意見均為待覆核的審查建議。已有來源條款歸屬保留；原文位置和已定位引文本身不確認正式條款歸屬或法律結論，候選意見是否等價尚未確認。","The numbered comments are review suggestions. Existing recorded source clauses are retained. Recorded source positions and located quotations do not confirm formal clause ownership or the legal conclusion. Candidate equivalence remains unconfirmed.")));
        if(findings==null||findings.isEmpty())blocks.add(new Block(0,"No findings were recorded; this does not establish that the documents have no issues."));
        Map<String,DocumentFindings> byFile=new LinkedHashMap<>();
        for(FindingVO finding:safe(findings))if(finding!=null){ReportSource source=reportSource(finding,coverage);byFile.computeIfAbsent(source.identity,ignored->new DocumentFindings(source)).findings.add(finding);}
        int findingOrdinal=0;
        for(DocumentFindings group:byFile.values()) {
            blocks.add(new Block(2,tr(lang,"文件","文件","Document")+" "+fileLabel(group.source,lang)));
            for(FindingVO finding:group.findings) {
            int recordStart=blocks.size();findingOrdinal++;
            String code=JsonUtils.isBlankText(finding.getCode())?"Comment record "+findingOrdinal:finding.getCode();
            Block heading=compact(new Block(1,code+"  "+tr(lang,"原文摘录","原文摘錄","Source quotations")));
            heading.keepWithNextOpening=true;blocks.add(heading);
            if(safe(finding.getEvidence()).isEmpty())blocks.add(new Block(0,"No located source evidence is recorded. Source and formal clause attribution require review."));
            for(FindingEvidence evidence:safe(finding.getEvidence()))if(evidence!=null)readerEvidence(blocks,evidence,lang);
            ownRecord(blocks,recordStart);
            }
        }
        boolean assessmentHeading=false;int assessmentOrdinal=0;
        if(coverage!=null)for(SemanticTopicVO topic:safe(coverage.getSemanticTopics()))if(topic!=null)for(SemanticPacketVO packet:safe(topic.getPacketAudits()))if(packet!=null){
            for(VerifiedAssessmentVO assessment:safe(packet.getVerifiedAssessments()))if(assessment!=null){
                if(!assessmentHeading){blocks.add(new Block(2,tr(lang,"其他已记录的来源核对","其他已記錄的來源核對","Additional recorded source checks")));assessmentHeading=true;}
                FindingVO context=new FindingVO();context.setEvidence(assessment.getEvidence());
                int recordStart=blocks.size();
                blocks.add(compact(new Block(1,String.format(Locale.ROOT,"Source check A%03d  ",++assessmentOrdinal)+readerCheckState(assessment.getAssessment()))));
                blocks.add(new Block(0,"Original model assessment; "+VettingReportPresentation.readerClauseQualification(context)+". "+VettingReportPresentation.readerNarrative(nvl(assessment.getTitle())+"\n"+nvl(assessment.getComment()),context)));
                for(FindingEvidence evidence:safe(assessment.getEvidence()))if(evidence!=null)readerEvidence(blocks,evidence,lang);
                ownRecord(blocks,recordStart);
            }
            for(com.fasterxml.jackson.databind.JsonNode reference:safe(packet.getFixedBaselineEvidence()))if(reference!=null)readerReference(blocks,reference);
        }
        blocks.add(new Block(2,tr(lang,"审查限制及未审范围","審查限制及未審範圍","Review limitations and unreviewed scope")));
        if(coverage==null)blocks.add(new Block(0,"No verifiable review coverage is available. Completeness and unreviewed scope remain unknown."));
        else {
            for(VettingReportPresentation.SectionSummary summary:VettingReportPresentation.sectionSummaries(findings,coverage))
                blocks.add(new Block(0,available(summary.getFileName(),lang)+": "+VettingReportPresentation.readerSummary(summary)));
            for(DocumentCoverageVO document:safe(coverage.getDocuments()))if(document!=null)
                blocks.add(new Block(0,readerCoverage(document,lang)));
            blocks.add(new Block(0,"complete".equals(coverage.getReviewCompleteness())?"The saved review scope is marked complete. Completeness of the original and substantive correctness remain unconfirmed.":"The saved source review is incomplete or its completeness is unconfirmed."));
            Map<String,List<String>> states=new LinkedHashMap<>();
            boolean legacyRequestScope=false;
            for(SemanticTopicVO topic:safe(coverage.getSemanticTopics()))if(topic!=null){
                String scope="Recorded source check "+topic.getTopicIndex()+": "+readerScopeSubject(topic.getTopic());
                states.computeIfAbsent(readerCheckState(topic.getCheckState()),ignored->new ArrayList<>()).add(scope);
                if(topic.getPendingSourceRequestCount()>0||topic.getBudgetDroppedGroups()>0||topic.getPartialContextGroups()>0||topic.getUnresolvedSegments()>0||topic.getMissingLocatedReferenceTargets()>0||topic.getFailedPacketCount()>0||topic.getNotSubmittedPacketCount()>0)
                    blocks.add(new Block(0,scope+". Source support is incomplete: pending source requests "+topic.getPendingSourceRequestCount()+(topic.getSourceRequestCount()>0?" of "+topic.getSourceRequestCount():"")+"; source groups omitted "+topic.getBudgetDroppedGroups()+"; groups with partial context "+topic.getPartialContextGroups()+"; unresolved excerpts "+topic.getUnresolvedSegments()+"; unlocated reference targets "+topic.getMissingLocatedReferenceTargets()+"; source checks failed "+topic.getFailedPacketCount()+"; source checks not submitted "+topic.getNotSubmittedPacketCount()+"."));
                if(JsonUtils.isBlankText(topic.getAggregateReviewStatus()))legacyRequestScope=true;
                if("completed_empty".equals(topic.getGlobalCallStatus()))blocks.add(new Block(0,scope+". "+tr(lang,
                        "整体模型调用未返回意见；这不确认合同没有问题，也不证明各项来源请求已完成实质审查。",
                        "整體模型呼叫未返回意見；這不確認合同沒有問題，也不證明各項來源請求已完成實質審查。",
                        "The overall model call returned no comments; this does not confirm that the contract has no issues or establish substantive review of the source requests.")));
                for(SemanticPacketVO packet:safe(topic.getPacketAudits()))if(packet!=null){
                    String limitation=readerPacketLimitation(packet,lang);
                    if(limitation!=null)blocks.add(new Block(0,scope+". "+tr(lang,"来源核对批次 ","來源核對批次 ","Source check packet ")+packet.getPacketIndex()+": "+limitation));
                }
                for(SemanticSourceRequestVO request:safe(topic.getSourceRequests()))if(request!=null){
                    if(!safe(request.getMissingChunkIds()).isEmpty())blocks.add(new Block(0,scope+". Required source excerpts missing from a recorded request: "+request.getMissingChunkIds().size()+". Complete source support remains unconfirmed."));
                    if(!JsonUtils.isBlankText(request.getUnknownReason()))blocks.add(new Block(0,scope+". Recorded source limitation: "+request.getUnknownReason()));
                }
            }
            if(legacyRequestScope)blocks.add(new Block(0,tr(lang,
                    "部分历史来源核对未记录请求覆盖评估，其范围仍未知；模型调用完成不代表已完成实质审查。",
                    "部分歷史來源核對未記錄請求覆蓋評估，其範圍仍未知；模型呼叫完成不代表已完成實質審查。",
                    "Some historical source checks have no recorded request-coverage assessment. Their scope remains unknown; a completed model call does not establish substantive review.")));
            if(states.isEmpty())blocks.add(new Block(0,"No substantive source-check record is available. Review scope remains unknown."));
            for(Map.Entry<String,List<String>> state:states.entrySet()) {
                blocks.add(compact(new Block(1,state.getKey())));
                for(String scope:state.getValue())blocks.add(new Block(0,"- "+scope+"."));
            }
            blocks.add(new Block(0,"These states cover only the recorded source excerpts. Any unconfirmed applicability, incomplete qualifying conditions, omitted requests and unreviewed source portions still require confirmation."));
            LinkedHashSet<String> limitations=new LinkedHashSet<>();
            for(String value:safe(coverage.getWarnings()))limitations.add(readerWarning(value,lang));
            for(DocumentCoverageVO doc:safe(coverage.getDocuments()))for(String value:safe(doc.getWarnings()))limitations.add(available(doc.getFileName(),lang)+": "+readerWarning(value,lang));
            for(String value:limitations)blocks.add(new Block(0,value));
            blocks.add(new Block(0,"Only the available reviewed excerpts support these comments. An empty model response or a completed request does not establish a substantive countercheck or confirm that the documents have no issues."));
        }
        return blocks;
    }
    private String readerCoverage(DocumentCoverageVO document,String lang) {
        String unknown=tr(lang,"未记录","未記錄","not recorded");
        String excerpts=document.getTotalSegments()>0?document.getReviewedSegments()+"/"+document.getTotalSegments():unknown;
        String characters=document.getTextChars()>0?document.getReviewedChars()+"/"+document.getTextChars():unknown;
        return available(document.getFileName(),lang)+": "+tr(lang,
                "保存来源范围内的已记录覆盖：片段 "+excerpts+"；字符 "+characters+"。这些数字仅描述已记录的来源观察，不代表整份原件完整或实质核对通过。",
                "保存來源範圍內的已記錄覆蓋：片段 "+excerpts+"；字元 "+characters+"。這些數字僅描述已記錄的來源觀察，不代表整份原件完整或實質核對通過。",
                "Recorded coverage within the saved source scope: excerpts "+excerpts+"; characters "+characters+". These figures describe only recorded source observations, not completeness of the entire original or a substantive pass.");
    }
    private String readerPacketLimitation(SemanticPacketVO packet,String lang) {
        boolean notSubmitted=nvl(packet.getStatus()).startsWith("not_submitted");
        boolean failed="failed".equals(packet.getStatus());
        boolean overBudget="over_budget".equals(packet.getInputBudgetStatus());
        boolean estimated="provider_estimated_fit".equals(packet.getInputBudgetStatus());
        boolean budgetUnknown=!overBudget&&!estimated&&!"observed_tokens".equals(packet.getInputBudgetStatus());
        boolean unknownReason="input_budget_unknown".equals(packet.getFailureKind())||notSubmitted&&budgetUnknown;
        boolean overBudgetReason="input_context_over_budget".equals(packet.getFailureKind())||notSubmitted&&overBudget;
        boolean unknownExplained=false,overBudgetExplained=false;
        List<String> limitations=new ArrayList<>();
        if(notSubmitted||failed||!JsonUtils.isBlankText(packet.getFailureKind())) {
            String reason;
            if("output_budget_exhausted".equals(packet.getFailureKind()))reason=tr(lang,"模型输出预算已耗尽","模型輸出預算已耗盡","the model output budget was exhausted");
            else if(overBudgetReason){reason=tr(lang,"完整输入超出可用上下文预算","完整輸入超出可用上下文預算","the complete input exceeded the available context budget");overBudgetExplained=true;}
            else if(unknownReason){reason=tr(lang,"完整输入预算尚未确认","完整輸入預算尚未確認","the complete input budget was not established");unknownExplained=true;}
            else if("transport_failure".equals(packet.getFailureKind()))reason=tr(lang,"模型连接或传输失败","模型連接或傳輸失敗","the model connection or transfer failed");
            else if("structured_output_invalid".equals(packet.getFailureKind()))reason=tr(lang,"模型输出未通过所需结构校验","模型輸出未通過所需結構校驗","the model output did not pass the required structure checks");
            else reason=tr(lang,"须查阅已保存的审查记录确认具体原因","須查閱已保存的審查記錄確認具體原因","consult the saved review record for the specific reason");
            String execution=notSubmitted?tr(lang,"未提交：","未提交：","Not submitted: "):failed?tr(lang,"未完成：","未完成：","Not completed: "):tr(lang,"记录的审查限制：","記錄的審查限制：","Recorded review limitation: ");
            limitations.add(execution+reason+tr(lang,"。","。","."));
        }
        if(budgetUnknown&&!unknownExplained)limitations.add(tr(lang,"完整输入预算仍未确认。","完整輸入預算仍未確認。","The complete input budget remains unconfirmed."));
        if(overBudget&&!overBudgetExplained)limitations.add(tr(lang,"已记录的完整输入超出可用上下文预算。","已記錄的完整輸入超出可用上下文預算。","The recorded complete input exceeded the available context budget."));
        if(estimated)limitations.add(tr(lang,"完整输入预算仅依据提供者估计，尚无实际观测的词元数。","完整輸入預算僅依據提供者估計，尚無實際觀測的詞元數。","The complete input budget is based on a provider estimate; an observed token count is unavailable."));
        if(limitations.isEmpty())return null;
        limitations.add(tr(lang,"这些记录不证明来源审查完整或实质正确。","這些記錄不證明來源審查完整或實質正確。","These records do not establish completeness or substantive correctness of the source review."));
        return String.join(" ",limitations);
    }
    private void ownRecord(List<Block> blocks,int start) {
        Block heading=blocks.get(start);heading.ownerHeading=true;
        for(int at=start;at<blocks.size();at++)blocks.get(at).owner=heading.text;
    }
    private void readerEvidence(List<Block> blocks,FindingEvidence evidence,String lang) {
        String page=JsonUtils.isBlankText(evidence.getPageNo())?"":("; recorded page: "+evidence.getPageNo());
        Block label=compact(new Block(0,available(evidence.getFileName(),lang)+" — "+VettingReportPresentation.readerEvidenceLocation(evidence)+page+"; "+verification(evidence.isLocated()?"verified":"unverified",lang)));
        label.keepWithNextOpening=true;blocks.add(label);
        Block quote=new Block(0,JsonUtils.isBlankText(evidence.getQuote())?"Verbatim source quotation is unavailable; source review is required.":evidence.getQuote());quote.quote=true;blocks.add(quote);
        if(evidence.getFixedBaselineEvidence()!=null)readerReference(blocks,evidence.getFixedBaselineEvidence());
    }
    private void readerReference(List<Block> blocks,com.fasterxml.jackson.databind.JsonNode reference) {
        blocks.add(compact(new Block(0,"Reference applicability: "+reference.path("projectApplicability").asText("unconfirmed")+"; completeness of applicable conditions: "+reference.path("qualifiersComplete").asText("unknown"))));
        if(reference.path("sourceUnit").path("missingMemberIds").size()>0)blocks.add(new Block(0,"Required parts of the reference source are missing; complete source support and applicability remain unconfirmed."));
    }
    private String readerCheckState(String state) {
        if("issue".equals(state))return "Candidate issues recorded in reviewed excerpts";
        if("insufficient_evidence".equals(state)||"insufficient_context".equals(state))return "Source evidence or context is insufficient";
        if("checked_pass".equals(state)||"consistent".equals(state))return "No issue reported for reviewed excerpts; the full source is not cleared";
        if("not_applicable".equals(state))return "Non-applicability recorded for reviewed excerpts; project applicability requires confirmation";
        return "Substantive source review incomplete or unconfirmed";
    }
    private String readerScopeSubject(String raw) {
        String[][] known={
                {"payment interim certificates minimum amount general conditions deleted not used reference","Interim payment certificates, minimum amounts and deleted/unused General Conditions references"},
                {"quality control site staff QCM AQCC SQCC number minimum appointment full time","Quality control staff (QCM/AQCC/SQCC): minimum numbers, appointments and full-time requirements"},
                {"definitions Building Services Inspector Contract Manager Representative Site Staff","Definitions of the Building Services Inspector, Contract Manager, Representative and Site Staff"},
                {"specification library edition date general specification civil engineering off site order precedence","Specification editions/dates, civil engineering/off-site specifications and order of precedence"},
                {"subcontracts building services nominated specialist provisional prime cost sum installation","Building services, nominated/specialist subcontracts, provisional/prime-cost sums and installation"},
                {"tender submission electronic hardcopy copies original duplicates envelopes technical priced documents reference addendum drawings DVD HOMES notice","Tender submission: electronic/paper copies, originals/duplicates, envelopes, technical/priced documents, references, addenda, drawing references, DVD/HOMES and notices"},
                {"modular integrated construction transport plan submission days advance frequency quarterly","Modular integrated construction transport plans: advance submission periods and frequency"},
                {"abbreviation schedule short forms different expansions meanings definitions ambiguity","Abbreviations, conflicting expansions and defined meanings"},
                {"form of tender articles agreement contract number title amount execution signature liquidated damages payment completion period sectional completion","Form of Tender and Articles of Agreement: contract identity/title, amounts, execution/signatures, liquidated damages, payment and completion/sectional-completion periods"},
                {"cross reference clause section appendix not used missing applicability conditions","Clause/section/appendix cross-references, unused/missing provisions and applicability conditions"},
                {"works scope Site boundary possession access phasing sections responsibility design workmanship defect indemnity liability insurance risk allocation","Works/site scope: boundaries, possession/access, phases/sections, responsibilities, design/workmanship/defects, indemnity/liability, insurance and risk allocation"},
                {"programme commencement completion contract duration two envelope tender evaluation","Programme, commencement/completion, contract duration and two-envelope tender evaluation"},
                {"design submission review approval allowance first revised submission days minimum time periods","First/revised design submissions: review/approval allowances and minimum periods"},
                {"site personnel employment full time solely contract attendance exceptions safety staff","Site personnel: full-time dedicated employment, attendance exceptions and safety staff"},
                {"design stages temporary works scope submission overlapping works different deadlines","Design stages, temporary-works scope, overlapping submissions and different deadlines"},
                {"inclusive range numbered clauses definitions adopted amended not used provisions reference precision","Inclusive numbered-clause ranges, definitions, adoption/amendments, unused provisions and reference precision"},
                {"project added amended wording British English terminology grammar spelling ambiguity guidance notes optional clauses unfilled project fields","Project additions/amendments: British English terminology, grammar/spelling, ambiguity, guidance notes, optional clauses and unfilled project fields"}
        };
        for(String[] subject:known)if(subject[0].equals(raw))return subject[1];
        return JsonUtils.isBlankText(raw)?"Recorded subject unavailable":raw;
    }
    private String readerWarning(String raw,String lang) {
        String value=warning(raw,lang);
        String[][] known={
                {"规则与语义仅处理已采用的 canonical 来源观察；未采用区域、整份原件完整性、编辑状态及采用关系保持声明的未知。字符分母仅为已采用观察的 UTF16 范围并集，不代表原件全文覆盖。",
                 "规则与语义仅处理已采用的来源观察；未采用区域、整份原件完整性、编辑状态及采用关系仍按记录保持未知。字符统计仅采用已采用观察的字符范围并集，不代表原件全文覆盖。",
                 "規則與語義僅處理已採用的來源觀察；未採用區域、整份原件完整性、編輯狀態及採用關係仍按記錄保持未知。字元統計僅採用已採用觀察的字元範圍聯集，不代表原件全文覆蓋。",
                 "Rules and semantic review process only adopted source observations. Unadopted regions, completeness of the entire original, editing status and adoption relationships retain their recorded unknowns. Character counts use only the union of character ranges in adopted observations and do not establish full coverage of the original."},
                {"片段与字符统计以已采用 canonical 观察为分母；未采用区域及原件完整性未确认。",
                 "片段与字符统计仅以已采用的来源观察为分母；未采用区域及原件完整性未确认。",
                 "片段與字元統計僅以已採用的來源觀察為分母；未採用區域及原件完整性未確認。",
                 "Segment and character statistics use only adopted source observations as the denominator; unadopted regions and original-source completeness are unconfirmed."},
                {"规则检查仅对已采用的 canonical 招标合同观察运行。",
                 "规则检查仅对已采用的招标合同来源观察运行。","規則檢查僅對已採用的招標合同來源觀察執行。","Rule checks run only on adopted tender contract source observations."}
        };
        for(String[] phrase:known)value=value.replace(warning(phrase[0],lang),tr(lang,phrase[1],phrase[2],phrase[3]));
        java.util.regex.Matcher plan=java.util.regex.Pattern.compile("语义审查按 (\\d+) 个主题检索(?:，并计划 (\\d+) 组项目资料引用比较)?；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。").matcher(nvl(raw));
        while(plan.find()) {
            String scope="";
            if(plan.group(2)!=null)scope="0".equals(plan.group(2))
                    ?tr(lang,"本次记录的审查范围未计划项目资料引用比较；该计划记录不证明是否进行了其他比较。","本次記錄的審查範圍未計劃項目資料引用比較；該計劃記錄不證明是否進行了其他比較。","No project-reference comparisons were planned in this recorded review scope; this plan does not establish whether other comparisons occurred.")
                    :tr(lang,"本次记录的审查范围计划了项目资料引用比较；完成情况未确认。","本次記錄的審查範圍計劃了項目資料引用比較；完成情況未確認。","Project-reference comparisons were planned in this recorded review scope; completion is unconfirmed.");
            String limitation=tr(lang,"已提交内容为局部原文窗口；其他限定是否完整未知。未提交给模型的片段尚未完成语义审查。","已提交內容為局部原文窗口；其他限定是否完整未知。未提交給模型的片段尚未完成語義審查。","Submitted excerpts are local source windows; completeness of other qualifiers is unknown. Excerpts not submitted to the model have not completed semantic review.");
            value=value.replace(warning(plan.group(),lang),(scope.isEmpty()?"":scope+" ")+limitation);
        }
        if(value.startsWith("Explicit run strike/dstrike"))value=value.replace("Explicit run strike/dstrike and tracked deletions are retained; inherited style strike requires manual review.","Explicit strikethrough and tracked deletions are recorded; inherited formatting still requires source review.");
        return value.replace("review.Rule checks","review. Rule checks");
    }

    private void setupWord(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().addNewSectPr();
        CTPageSz size = section.addNewPgSz(); size.setW(BigInteger.valueOf(11906)); size.setH(BigInteger.valueOf(16838));
        CTPageMar margins = section.addNewPgMar();
        margins.setTop(BigInteger.valueOf(960)); margins.setBottom(BigInteger.valueOf(960));
        margins.setLeft(BigInteger.valueOf(960)); margins.setRight(BigInteger.valueOf(960));
        margins.setFooter(BigInteger.valueOf(400)); margins.setHeader(BigInteger.valueOf(400));
        XWPFStyles styles = document.createStyles();
        for (String name : Arrays.asList("Normal", "Title", "Heading1", "Heading2")) {
            CTStyle style = CTStyle.Factory.newInstance(); style.setStyleId(name); style.setType(STStyleType.PARAGRAPH);
            style.addNewName().setVal(name); if (!"Normal".equals(name)) style.addNewBasedOn().setVal("Normal");
            CTRPr properties = style.addNewRPr();
            CTFonts fonts = properties.addNewRFonts(); fonts.setAscii("Times New Roman"); fonts.setHAnsi("Times New Roman"); fonts.setEastAsia("Microsoft YaHei");
            properties.addNewColor().setVal("000000");
            if (name.startsWith("Heading")) style.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf("Heading1".equals(name) ? 0 : 1));
            styles.addStyle(new XWPFStyle(style));
        }
    }

    private void wordPageField(XWPFParagraph paragraph,String instruction) {
        CTSimpleField field=paragraph.getCTP().addNewFldSimple();field.setInstr(instruction);
        XWPFRun result=new XWPFRun(field.addNewR(),paragraph);setWordFont(result);result.setFontSize(FURNITURE_SIZE);
    }

    private void setWordFont(XWPFRun run) {
        run.setFontFamily("Times New Roman"); run.setFontFamily("Microsoft YaHei", XWPFRun.FontCharRange.eastAsia);
    }

    private PDFont loadFont(PDDocument document, List<Block> blocks) throws IOException {
        List<String> candidates = new ArrayList<>();
        if (!nvl(fontPath).trim().isEmpty()) candidates.add(fontPath.trim());
        if(candidates.isEmpty()&&canEncode(PDType1Font.TIMES_ROMAN,blocks))return PDType1Font.TIMES_ROMAN;
        candidates.addAll(Arrays.asList("C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/simkai.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf", "/usr/share/fonts/truetype/noto/NotoSansCJKsc-Regular.ttf",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf"));
        for (String path : candidates) {
            if (!new File(path).isFile()) continue;
            try {
                PDFont font = PDType0Font.load(document, new File(path));
                if (canEncode(font, blocks)) return font;
            } catch (IOException | IllegalArgumentException ignored) { /* Try the next font without losing source text. */ }
        }
        if (canEncode(PDType1Font.HELVETICA, blocks)) return PDType1Font.HELVETICA;
        throw new BizException(4100, "PDF字体不支持报告中的字符，请配置 consense.report.font-path / CONSENSE_REPORT_FONT_PATH 为支持中文的 Unicode TTF 字体文件。");
    }

    private boolean canEncode(PDFont font, List<Block> blocks) throws IOException {
        try {
            for (Block block : blocks) for (String line : block.text.split("\n", -1)) pdfWidth(font,line);
            return true;
        } catch (IllegalArgumentException e) { return false; }
    }

    // The standard Dingbats font contains both required marks; common CJK fonts do not.
    private static PDFont markFont(PDFont font,String run) {
        return "\u2713".equals(run)||"\u2717".equals(run)?PDType1Font.ZAPF_DINGBATS:font;
    }
    private static boolean hasMarks(String text) {return text.indexOf('\u2713')>=0||text.indexOf('\u2717')>=0;}
    private static float pdfWidth(PDFont font,String text) throws IOException {
        if(!hasMarks(text))return font.getStringWidth(text);
        float width=0;for(String run:text.split("(?=[\u2713\u2717])|(?<=[\u2713\u2717])"))width+=markFont(font,run).getStringWidth(run);
        return width;
    }
    private static void pdfShowText(PDPageContentStream stream,PDFont font,String text,float size) throws IOException {
        if(!hasMarks(text)){stream.setFont(font,size);stream.showText(text);return;}
        for(String run:text.split("(?=[\u2713\u2717])|(?<=[\u2713\u2717])"))if(!run.isEmpty()) {
            stream.setFont(markFont(font,run),size);stream.showText(run);
        }
    }

    private static final class Block {
        final int kind; final String text; String owner; boolean ownerHeading; boolean quote; boolean pageBreak; boolean compact; boolean keepFirstLines; boolean keepWithNextOpening;
        Block(int kind, String text) { this.kind = kind; this.text = nvl(text).replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").replaceAll("[\\p{Cntrl}&&[^\\n]]", ""); }
    }

    private static final class PdfCursor implements AutoCloseable {
        final PDDocument document; final PDFont font; PDPageContentStream stream; float y;String owner;boolean ownerHeading;
        PdfCursor(PDDocument document, PDFont font) throws IOException { this.document = document; this.font = font; newPage(); }
        void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = PDRectangle.A4.getHeight() - MARGIN;
            if(owner!=null&&!ownerHeading) {
                y-=4;
                for(String line:wrap(owner+" (Cont'd)",11,WIDTH)){textAt(MARGIN,y,11,line);y-=11*1.05f;}
                y-=1;
            }
        }
        void centeredTitle(String text) throws IOException {
            float w=pdfWidth(font,text)/1000*12,x=(PDRectangle.A4.getWidth()-w)/2;
            y-=12;stream.beginText();stream.setFont(font,12);stream.setNonStrokingColor(0,0,0);
            stream.newLineAtOffset(x,y);pdfShowText(stream,font,text,12);stream.endText();
            stream.setStrokingColor(0,0,0);stream.setLineWidth(0.5f);stream.moveTo(x,y-2);stream.lineTo(x+w,y-2);stream.stroke();y-=32;
        }
        void metadata(String label,String value) throws IOException {
            List<String> lines=wrap(value,11,WIDTH-142);
            for(int i=0;i<lines.size();i++) {
                if(i==0){textAt(MARGIN,y,11,label);textAt(MARGIN+130,y,11,":");}
                textAt(MARGIN+142,y,11,lines.get(i));y-=17;
            }
            y-=5;
        }
        void textAt(float x,float baseline,float size,String text) throws IOException {
            stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(0,0,0);
            stream.newLineAtOffset(x,baseline);pdfShowText(stream,font,text,size);stream.endText();
        }
        private static final float TABLE_SIZE=11, TABLE_LEADING=13.2f;
        void table(VettingReportPresentation.Table table) throws IOException {
            float headHeight=Math.max(headerHeight(table,false),headerHeight(table,true));
            float titleHeight=table.commentsHeader()?0:wrap(table.getTitle(),11,WIDTH).size()*11*1.48f+13;
            float firstHeight=table.getRows().isEmpty()?0:height(cellLines(table.getRows().get(0),table.getWidths()));
            float freshBody=PDRectangle.A4.getHeight()-MARGIN*2-titleHeight-headHeight;
            float opening=titleHeight+headHeight+(firstHeight<=freshBody?firstHeight:32);
            if(y-opening<MARGIN)newPage();
            tableHeader(table,false);
            for(List<String> row:table.getRows()) {
                List<List<String>> lines=cellLines(row,table.getWidths());int count=maxLines(lines),offset=0;
                while(offset<count) {
                    int fit=(int)((y-MARGIN-8)/TABLE_LEADING);
                    if(fit<1||(offset==0&&count>fit&&count*TABLE_LEADING+8<PDRectangle.A4.getHeight()-MARGIN*2-headHeight-35)) {
                        newPage();tableHeader(table,true);fit=(int)((y-MARGIN-8)/TABLE_LEADING);
                    }
                    int n=Math.min(count-offset,Math.max(1,fit));tableSlice(lines,table.getWidths(),offset,n);offset+=n;
                    if(offset<count){newPage();tableHeader(table,true);}
                }
            }
            y-=12;
        }
        float headerHeight(VettingReportPresentation.Table table,boolean continued) throws IOException {
            if(!table.commentsHeader())return height(cellLines(table.getHeadings(),table.getWidths()));
            int[] widths=table.getWidths();float middle=WIDTH*(widths[1]+widths[2]+widths[3])/100;
            float top=Math.max(wrap(table.sectionNumber(),TABLE_SIZE,WIDTH*widths[0]/100-8).size(),wrap(table.sectionLabel()+(continued?" (Cont'd)":""),TABLE_SIZE,middle-8).size())*TABLE_LEADING+8;
            float bottom=height(cellLines(Arrays.asList("",table.getHeadings().get(1),table.getHeadings().get(2),table.getHeadings().get(3),""),widths));
            float right=wrap(table.getHeadings().get(4),TABLE_SIZE,WIDTH*widths[4]/100-8).size()*TABLE_LEADING+8;
            return Math.max(top+bottom,right);
        }
        void tableHeader(VettingReportPresentation.Table table,boolean continued) throws IOException {
            if(!table.commentsHeader()) {
                block(new Block(1,table.getTitle()+(continued?" (Cont'd)":"")),null);
                List<List<String>> header=cellLines(table.getHeadings(),table.getWidths());tableSlice(header,table.getWidths(),0,maxLines(header));return;
            }
            int[] widths=table.getWidths();float h=headerHeight(table,continued),left=WIDTH*widths[0]/100,right=WIDTH*widths[4]/100,middle=WIDTH-left-right;
            List<List<String>> bottom=cellLines(Arrays.asList("",table.getHeadings().get(1),table.getHeadings().get(2),table.getHeadings().get(3),""),widths);
            float bottomHeight=height(bottom),topHeight=h-bottomHeight;
            headerCell(wrap(table.sectionNumber(),TABLE_SIZE,left-8),MARGIN,y,left,h);
            headerCell(wrap(table.sectionLabel()+(continued?" (Cont'd)":""),TABLE_SIZE,middle-8),MARGIN+left,y,middle,topHeight);
            float x=MARGIN+left;for(int i=1;i<=3;i++){float w=WIDTH*widths[i]/100;headerCell(bottom.get(i),x,y-topHeight,w,bottomHeight);x+=w;}
            headerCell(wrap(table.getHeadings().get(4),TABLE_SIZE,right-8),MARGIN+WIDTH-right,y,right,h);y-=h;
        }
        void headerCell(List<String> lines,float x,float top,float width,float height) throws IOException {
            stream.setStrokingColor(35,35,35);stream.setLineWidth(0.5f);stream.addRect(x,top-height,width,height);stream.stroke();
            for(int n=0;n<lines.size();n++)textAt(x+4,top-4-TABLE_SIZE-n*TABLE_LEADING,TABLE_SIZE,lines.get(n));
        }
        List<List<String>> cellLines(List<String> values,int[] widths) throws IOException {
            List<List<String>> result=new ArrayList<>();
            for(int i=0;i<values.size();i++) {
                List<String> lines=new ArrayList<>();for(String p:nvl(values.get(i)).split("\n",-1))lines.addAll(wrap(p,TABLE_SIZE,WIDTH*widths[i]/100-8));result.add(lines);
            }
            return result;
        }
        int maxLines(List<List<String>> cells) {return cells.stream().mapToInt(List::size).max().orElse(1);}
        float height(List<List<String>> cells){return maxLines(cells)*TABLE_LEADING+8;}
        void tableSlice(List<List<String>> cells,int[] widths,int offset,int count) throws IOException {
            float h=count*TABLE_LEADING+8,x=MARGIN;
            stream.setStrokingColor(35,35,35);stream.setLineWidth(0.5f);
            for(int i=0;i<cells.size();i++) {
                float w=WIDTH*widths[i]/100;stream.addRect(x,y-h,w,h);stream.stroke();
                for(int n=0;n<count;n++)if(offset+n<cells.get(i).size())textAt(x+4,y-4-TABLE_SIZE-n*TABLE_LEADING,TABLE_SIZE,cells.get(i).get(offset+n));
                x+=w;
            }
            y-=h;
        }
        void block(Block block, Block next) throws IOException { block(block,next,null); }
        void block(Block block, Block next, Block following) throws IOException {
            owner=block.owner;ownerHeading=block.ownerHeading;
            if (block.pageBreak && y < PDRectangle.A4.getHeight() - MARGIN) newPage();
            float size = block.kind == 3 ? 21 : block.kind == 2 ? 14 : 11;
            float lineHeight = size * (block.compact ? 1.05f : 1.48f), indent = block.quote ? 12 : 0;
            if (block.kind > 0 && y - lineHeight * 3 < MARGIN) newPage();
            List<String> lines = new ArrayList<>();
            for (String paragraph : block.text.split("\n", -1)) lines.addAll(wrap(paragraph, size, WIDTH - indent));
            if (block.keepWithNextOpening && next != null) {
                float nextSize = next.kind == 3 ? 21 : next.kind == 2 ? 14 : 11;
                float nextHeight = nextSize * (next.compact ? 1.05f : 1.48f);
                List<String> nextLines = new ArrayList<>();
                for (String paragraph : next.text.split("\n", -1))
                    nextLines.addAll(wrap(paragraph, nextSize, WIDTH - (next.quote ? 12 : 0)));
                float needed = before(block) + lineHeight * lines.size() + after(block);
                // A source heading keeps its label and first quotation paragraph, not only the label opening.
                if (next.keepWithNextOpening && following != null) {
                    needed += before(next) + nextHeight * nextLines.size() + after(next);
                    next = following;nextSize = next.kind == 3 ? 21 : next.kind == 2 ? 14 : 11;
                    nextHeight = nextSize * (next.compact ? 1.05f : 1.48f);nextLines.clear();
                    for (String paragraph : next.text.split("\n", -1))
                        nextLines.addAll(wrap(paragraph, nextSize, WIDTH - (next.quote ? 12 : 0)));
                }
                int opening = next.quote ? wrap(next.text.split("\n", -1)[0],nextSize,WIDTH - 12).size() : Math.min(2,nextLines.size());
                float available = PDRectangle.A4.getHeight() - MARGIN * 2;
                if (needed + before(next) + nextHeight * opening > available) opening = Math.min(2,nextLines.size());
                needed += before(next) + nextHeight * opening;
                // Long quotations can still paginate; an opening group that fits a fresh page cannot orphan its heading.
                if (needed <= available && y - needed < MARGIN) newPage();
            }
            // Keep a labeled detail with its opening lines without locking long source quotations to one page.
            int openingLines=Math.min(lines.size(),lines.size()==3?3:2);
            if ((block.keepFirstLines || lines.size()>1) && y - 3 - lineHeight * openingLines < MARGIN) newPage();
            y -= block.kind > 0 ? block.compact ? 4 : 9 : block.compact ? 0 : 3;
            for (int lineIndex=0;lineIndex<lines.size();lineIndex++) {
                String line=lines.get(lineIndex);
                if (y - lineHeight < MARGIN || (lines.size()-lineIndex==2 && y-lineHeight*2<MARGIN)) newPage();
                stream.beginText(); stream.setFont(font, size);
                stream.setNonStrokingColor(25, 25, 25);
                stream.newLineAtOffset(MARGIN + indent, y); pdfShowText(stream,font,line,size); stream.endText();
                y -= lineHeight;
            }
            y -= block.compact ? block.kind > 0 ? 1 : 0 : 4;
        }
        float before(Block block) {return block.kind > 0 ? block.compact ? 4 : 9 : block.compact ? 0 : 3;}
        float after(Block block) {return block.compact ? block.kind > 0 ? 1 : 0 : 4;}
        List<String> wrap(String text, float size, float width) throws IOException {
            if (text.isEmpty()) return Collections.singletonList("");
            List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder(); float used = 0;
            int lastSpace = -1;
            for (int at = 0; at < text.length();) {
                int cp = text.codePointAt(at); String ch = new String(Character.toChars(cp));
                float charWidth = pdfWidth(font,ch) / 1000 * size;
                if (used + charWidth > width && line.length() > 0) {
                    if (lastSpace > 0) {
                        lines.add(line.substring(0, lastSpace));
                        String remainder = line.substring(lastSpace + 1); line.setLength(0); line.append(remainder);
                        used = pdfWidth(font,remainder) / 1000 * size;
                    } else { lines.add(line.toString()); line.setLength(0); used = 0; }
                    lastSpace = line.lastIndexOf(" ");
                }
                if (cp != ' ' || line.length() > 0) { line.append(ch); used += charWidth; if (cp == ' ') lastSpace = line.length() - 1; }
                at += Character.charCount(cp);
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }

    private void detail(List<Block> blocks, String label, String value) {
        if (!nvl(value).trim().isEmpty()) {
            Block block = new Block(0, label + ": " + value);
            block.keepFirstLines = true;
            blocks.add(block);
        }
    }
    private static Block compact(Block block) { block.compact = true; return block; }
    private ReportSource reportSource(FindingVO finding, CoverageVO coverage) {
        FindingEvidence primary = safe(finding.getEvidence()).stream().filter(Objects::nonNull)
                .filter(e->"source".equalsIgnoreCase(e.getSide())).findFirst()
                .orElseGet(()->safe(finding.getEvidence()).stream().filter(Objects::nonNull).findFirst().orElse(null));
        String key = nvl(primary == null ? finding.getFileKey() : primary.getFileKey());
        String documentId = primary == null ? "" : nvl(primary.getDocumentId());
        String fileName = primary == null ? "" : nvl(primary.getFileName());
        if (!documentId.trim().isEmpty()) {
            // A document ID is authoritative; a repeated protocol key is not.
            DocumentCoverageVO document = uniqueCoverage(coverage, documentId, true);
            if (document != null) {
                if (fileName.trim().isEmpty()) fileName = nvl(document.getFileName());
                if (key.trim().isEmpty()) key = nvl(document.getFileKey());
            }
            if (key.trim().isEmpty()) key = nvl(finding.getFileKey());
            return new ReportSource("document:" + documentId, key, fileName);
        }
        if (key.trim().isEmpty()) key = nvl(finding.getFileKey());
        if (!fileName.trim().isEmpty()) return new ReportSource("filename:" + key.length() + ":" + key + fileName, key, fileName);
        if (primary == null) {
            // Legacy records have no source evidence. Only an unambiguous key
            // can supply a filename; never choose the first of multiple sources.
            DocumentCoverageVO document = uniqueCoverage(coverage, key, false);
            if (document != null) {
                documentId = nvl(document.getDocumentId());
                fileName = nvl(document.getFileName());
                if (!documentId.trim().isEmpty()) return new ReportSource("document:" + documentId, key, fileName);
                if (!fileName.trim().isEmpty()) return new ReportSource("filename:" + key.length() + ":" + key + fileName, key, fileName);
            }
        }
        return new ReportSource("unidentified-key:" + key, key, "");
    }
    private DocumentCoverageVO uniqueCoverage(CoverageVO coverage, String value, boolean byDocumentId) {
        if (coverage == null || value.trim().isEmpty()) return null;
        DocumentCoverageVO match = null;
        for (DocumentCoverageVO document : safe(coverage.getDocuments())) {
            if (document != null && value.equals(byDocumentId ? document.getDocumentId() : document.getFileKey())) {
                if (match != null) return null;
                match = document;
            }
        }
        return match;
    }
    private String fileLabel(ReportSource source, String lang) {
        return available(source.key, lang) + (source.fileName.trim().isEmpty() ? "" : "  " + source.fileName);
    }
    private static final class ReportSource {
        final String identity, key, fileName;
        ReportSource(String identity, String key, String fileName) { this.identity = identity; this.key = key; this.fileName = fileName; }
    }
    private static final class DocumentFindings {
        final ReportSource source;
        final List<FindingVO> findings = new ArrayList<>();
        DocumentFindings(ReportSource source) { this.source = source; }
    }
    private String projectName(Project project, String lang) { return LocalizedText.of(project.getNameZhHans(), project.getNameZhHant(), project.getNameEn()).pick(lang); }
    private String status(String value, String lang) {
        if ("HANDLED".equalsIgnoreCase(value)) return tr(lang, "已处理", "已處理", "Handled");
        if ("ASSIGNED".equalsIgnoreCase(value)) return tr(lang, "已分派", "已分派", "Assigned");
        if ("OPEN".equalsIgnoreCase(value)) return tr(lang, "待处理", "待處理", "Open");
        return available(value, lang);
    }
    private String scope(String value, String lang) {
        if ("inter".equals(value)) return tr(lang, "跨文件", "跨文件", "Across documents");
        if ("intra".equals(value)) return tr(lang, "文件内", "文件內", "Within document");
        return available(value, lang);
    }
    private String severity(String value, String lang) {
        if ("high".equalsIgnoreCase(value)) return tr(lang, "高", "高", "High");
        if ("medium".equalsIgnoreCase(value)) return tr(lang, "中", "中", "Medium");
        if ("low".equalsIgnoreCase(value)) return tr(lang, "低", "低", "Low");
        return available(value, lang);
    }
    private String checkSource(String value, String lang) {
        if ("rule".equals(value)) return tr(lang, "规则检查", "規則檢查", "Rule check");
        if ("llm".equals(value) || "model".equals(value)) return tr(lang, "模型审查", "模型審查", "Model review");
        return available(value, lang);
    }
    private String verification(String value, String lang) {
        if ("verified".equals(value)) return tr(lang, "证据已定位", "證據已定位", "Evidence located");
        if ("partial".equals(value)) return tr(lang, "部分证据已定位", "部分證據已定位", "Evidence partly located");
        return tr(lang, "证据待定位", "證據待定位", "Evidence awaiting location");
    }
    private String pick(LocalizedText value, String lang) { return value == null ? "" : value.pick(lang); }
    static String warning(String value, String lang) {
        if (!"en".equalsIgnoreCase(lang) && !"zh-Hant".equalsIgnoreCase(lang)) return nvl(value);
        String source = "OCR 不识别删除线、表格行列关系及图形含义；引文、删除状态和表格对应关系须对照原始物理页复核。";
        String result = nvl(value).replace(source, tr(lang, source,
                "OCR 不識別刪除線、表格行列關係及圖形含義；引文、刪除狀態和表格對應關係須對照原始物理頁覆核。",
                "OCR does not identify strikethrough, table row/column relationships or the meaning of graphics. Quotations, deletion status and table associations must be checked against the original physical pages."));
        // Only known system-warning phrases are localized here. Evidence,
        // comments, filenames and opaque diagnostic details are never translated.
        String[][] phrases = {
                {"规则与语义仅处理已采用的 canonical 来源观察；未采用区域、整份原件完整性、编辑状态及采用关系保持声明的未知。字符分母仅为已采用观察的 UTF16 范围并集，不代表原件全文覆盖。", "規則與語義僅處理已採用的 canonical 來源觀察；未採用區域、整份原件完整性、編輯狀態及採用關係保持聲明的未知。字元分母僅為已採用觀察的 UTF16 範圍聯集，不代表原件全文覆蓋。", "Rules and semantic review process only adopted canonical source observations; unadopted regions, completeness of the entire original, editing status and adoption relationships retain their declared unknowns. The character denominator is only the union of UTF16 ranges in adopted observations and does not represent full-text coverage of the original."},
                {"片段与字符统计以已采用 canonical 观察为分母；未采用区域及原件完整性未确认。", "片段與字元統計以已採用 canonical 觀察為分母；未採用區域及原件完整性未確認。", "Segment and character statistics use adopted canonical observations as the denominator; unadopted regions and original-source completeness are unconfirmed."},
                {"规则检查仅对已采用的 canonical 招标合同观察运行。", "規則檢查僅對已採用的 canonical 招標合同觀察執行。", " Rule checks run only on adopted canonical tender contract observations."},
                {"未提供可解析的 Bills of Quantities，不能确认数量、提交范围及金额协调。", "未提供可解析的 Bills of Quantities，不能確認數量、提交範圍及金額協調。", "No parseable Bills of Quantities were supplied; quantities, submission scope and monetary coordination cannot be confirmed."},
                {"未提供可解析的 General Summary，无法核对汇总金额。", "未提供可解析的 General Summary，無法核對匯總金額。", "No parseable General Summary was supplied; summary amounts cannot be checked."},
                {"规则扫描全部可解析招标合同条款；标准文件仅作参考对照，项目事实和文件目录仅作上下文，均未当作实际合同执行规则检查。语义覆盖仅计实际成功的模型片段，未检索片段不代表完成语义审查。", "規則掃描全部可解析招標合同條款；標準文件僅作參考對照，項目事實和文件目錄僅作上下文，均未當作實際合同執行規則檢查。語義覆蓋僅計實際成功的模型片段，未檢索片段不代表完成語義審查。", "Rules scan all parseable tender contract provisions. Standards are comparison references; project facts and the package manifest provide context. These supporting sources are not checked as actual contract obligations. Semantic coverage counts only excerpts in successful model calls; unretrieved excerpts have not completed semantic review."},
                {"本次未执行标准模板差异过滤，不能解读为只审查项目改动内容。", "本次未執行標準模板差異過濾，不能解讀為只審查項目改動內容。", "Standard-template difference filtering was not performed. This review cannot be interpreted as checking only project changes."},
                {"任务尚未完成规则和语义审查，当前覆盖仅反映源文件解析状态。", "任務尚未完成規則和語義審查，當前覆蓋僅反映源文件解析狀態。", "Rule and semantic review are not complete; current coverage reflects source parsing only."},
                {"规则已扫描全部可解析招标合同正文。", "規則已掃描全部可解析招標合同正文。", "Rules scanned all parseable tender contract body text."},
                {"标准文件仅作参考对照，规则未当作实际合同执行。", "標準文件僅作參考對照，規則未當作實際合同執行。", "Standards are comparison references; rules did not check them as actual contract obligations."},
                {"项目事实或文件目录不作为有效合同条款执行规则扫描。", "項目事實或文件目錄不作為有效合同條款執行規則掃描。", "Project facts and the package manifest are not rule-scanned as operative contract provisions."},
                {"规则和语义审查尚未完成。", "規則和語義審查尚未完成。", "Rule and semantic review are not complete."},
                {"资料类型：标准参考文件", "資料類型：標準參考文件", "Source role: Standard reference"},
                {"资料类型：招标合同条款", "資料類型：招標合同條款", "Source role: Tender provisions"},
                {"资料类型：项目事实资料", "資料類型：項目事實資料", "Source role: Project facts"},
                {"资料类型：文件目录及范围清单", "資料類型：文件目錄及範圍清單", "Source role: Package manifest and scope inventory"},
                {"规则检查对全部可解析招标合同正文运行。", "規則檢查對全部可解析招標合同正文執行。", "Rule checks run on all parseable tender contract body text."},
                {"规则检查对全部可解析正文运行。", "規則檢查對全部可解析正文執行。", "Rule checks run on all parseable body text."},
                {"语义审查未运行：本地 LLM 未启用或不可用；报告仅包含规则检查结果。", "語義審查未執行：本地 LLM 未啟用或不可用；報告僅包含規則檢查結果。", "Semantic review did not run: the local LLM is disabled or unavailable. This report contains rule-check results only."},
                {"评语、清单或问答参考被排除，避免答案泄漏。", "評語、清單或問答參考被排除，避免答案洩漏。", "Reference comments, checklists or questions and answers were excluded to prevent answer leakage."},
                {"文档解析不完整，未解析部分不纳入审查。", "文件解析不完整，未解析部分不納入審查。", "Document parsing is incomplete; unparsed portions are excluded from review."},
                {"解析覆盖记录无法读取，请重新解析。", "解析覆蓋記錄無法讀取，請重新解析。", "The parsing coverage record could not be read; reparse the source."},
                {"旧文件缺结构和覆盖记录，仅使用字符锚点，不推测Word页码。", "舊文件缺結構和覆蓋記錄，僅使用字元錨點，不推測 Word 頁碼。", "This legacy source lacks structure and coverage records. Only character anchors are used; Word page numbers are not inferred."},
                {"未完成解析的物理页：", "未完成解析的物理頁：", "Physical pages not fully parsed: "},
                {"没有可用的已解析正文：", "沒有可用的已解析正文：", "No usable parsed body text: "},
                {"DOCX locations are body/table positions; physical Word pages are unknown until rendering.", "DOCX 位置為正文／表格位置；渲染前 Word 物理頁碼未知。", "DOCX locations are body/table positions; physical Word pages are unknown until rendering."},
                {"Explicit run strike/dstrike and tracked deletions are retained; inherited style strike requires manual review.", "明確的文字刪除線／雙刪除線及追蹤刪除均保留；繼承樣式刪除線須人工覆核。", "Explicit run strike/dstrike and tracked deletions are retained; inherited style strike requires manual review."},
                {"OCR selection uses a per-page text/image heuristic; text accuracy and diagram topology require review.", "OCR 選擇採用逐頁文字／影像啟發式判斷；文字準確度及圖形結構須覆核。", "OCR selection uses a per-page text/image heuristic; text accuracy and diagram topology require review."}
        };
        for (String[] phrase : phrases) result = result.replace(phrase[0], tr(lang, phrase[0], phrase[1], phrase[2]));
        result = warningPattern(result, "主题 (\\d+) 局部比较窗口：预算略过 (\\d+) 组；引用目标或邻接限定未补齐的片段 (\\d+) 个。窗口不代表完整条款，未知限定须人工核对。",
                "主題 {1} 局部比較窗口：預算略過 {2} 組；引用目標或鄰接限定未補齊的片段 {3} 個。窗口不代表完整條款，未知限定須人工核對。",
                "Topic {1} local comparison window: {2} groups omitted by the budget; reference targets or adjacent qualifiers were not fully included for {3} chunks. The window is not a complete clause; unknown qualifiers require human review.", lang);
        result = warningPattern(result, "主题 (\\d+) 局部比较窗口：预算略过 (\\d+) 组、仅部分上下文提交 (\\d+) 组；引用目标或邻接限定未补齐的片段 (\\d+) 个。窗口不代表完整条款，未知限定须人工核对。",
                "主題 {1} 局部比較窗口：預算略過 {2} 組、僅部分上下文提交 {3} 組；引用目標或鄰接限定未補齊的片段 {4} 個。窗口不代表完整條款，未知限定須人工核對。",
                "Topic {1} local comparison window: {2} groups omitted by the budget, {3} groups submitted with partial context; reference targets or adjacent qualifiers were not fully included for {4} chunks. The window is not a complete clause; unknown qualifiers require human review.", lang);
        result = warningPattern(result, "语义仅覆盖 (\\d+)/(\\d+) 片段，其他片段未进入成功的模型调用。",
                "語義僅覆蓋 {1}/{2} 片段，其他片段未進入成功的模型呼叫。",
                "Semantic review covered only {1}/{2} excerpts; other excerpts were not included in successful model calls.", lang);
        result = warningPattern(result, "语义审查按 (\\d+) 个主题检索；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。",
                "語義審查按 {1} 個主題檢索；已提交的是局部原文窗口，其他限定是否完整未知。未進入模型的切片不代表已經完成語義審查。",
                "Semantic review retrieves by {1} topics. Submitted excerpts are local source windows; completeness of other qualifiers is unknown. Excerpts not submitted to the model have not completed semantic review.", lang);
        result = warningPattern(result, "语义审查按 (\\d+) 个主题检索，并计划 (\\d+) 组项目资料引用比较；已提交的是局部原文窗口，其他限定是否完整未知。未进入模型的切片不代表已经完成语义审查。",
                "語義審查按 {1} 個主題檢索，並計劃 {2} 組項目資料引用比較；已提交的是局部原文窗口，其他限定是否完整未知。未進入模型的切片不代表已經完成語義審查。",
                "Semantic review retrieves by {1} topics and plans {2} project-reference comparisons. Submitted excerpts are local source windows; completeness of other qualifiers is unknown. Excerpts not submitted to the model have not completed semantic review.", lang);
        result = warningPattern(result, "项目资料引用 ([^；\\r\\n]+) 未形成比较：(?:tender_target_not_located|未定位到对应招标条款)；不能推断条款缺失或不适用。",
                "項目資料引用 {1} 未形成比較：未定位對應招標條款；不能推斷條款缺失或不適用。",
                "Project reference {1} could not be compared because the tender provision was not located. This does not establish that the provision is missing or inapplicable.", lang);
        result = warningPattern(result, "项目资料引用 ([^；\\r\\n]+) 未形成比较：(?:ambiguous_tender_source_or_scope|对应招标来源或范围未确认)；不能推断条款缺失或不适用。",
                "項目資料引用 {1} 未形成比較：對應招標來源或範圍未確認；不能推斷條款缺失或不適用。",
                "Project reference {1} could not be compared because its tender source or scope is ambiguous. This does not establish that the provision is missing or inapplicable.", lang);
        result = warningPattern(result, "项目资料比较未提交：([^；\\r\\n]+)；预算或比较数量限制不代表已审查。",
                "項目資料比較未提交：{1}；預算或比較數量限制不代表已審查。",
                "Project-reference comparison not submitted: {1}. Budget or comparison limits do not establish completed review.", lang);
        result = warningPattern(result, "项目资料比较 ([^；\\r\\n]+) 未提交：超过本次比较数量限制；未提交材料不代表已审查。",
                "項目資料比較 {1} 未提交：超過本次比較數量限制；未提交材料不代表已審查。",
                "Project-reference comparison {1} was not submitted because the comparison limit was reached. Unsubmitted material has not completed review.", lang);
        result = warningPattern(result, "项目资料比较 ([^；\\r\\n]+) 未提交：完整招标条款及项目资料超过单次上下文预算；未提交材料不代表已审查。",
                "項目資料比較 {1} 未提交：完整招標條款及項目資料超過單次上下文預算；未提交材料不代表已審查。",
                "Project-reference comparison {1} was not submitted because the complete tender and project context exceeds the per-call budget. Unsubmitted material has not completed review.", lang);
        result = warningPattern(result, "主题 (\\d+) 来源包 (\\d+)：业务形状修复前存在未获接受的非空模型候选记录，修复答复为空数组；这不证明不存在问题，请人工核对该来源包已保存的原始尝试记录。",
                "主題 {1} 來源包 {2}：業務形狀修復前存在未獲接受的非空模型候選記錄，修復答覆為空陣列；這不證明不存在問題，請人工核對該來源包已保存的原始嘗試記錄。",
                "Topic {1}, source packet {2}: model candidate records were saved in a nonempty array before the business shape repair but were not accepted; the repair returned an empty array. This does not establish that no issues exist. Manually check the saved original attempts for this source packet.", lang);
        result = warningPattern(result, "主题 (\\d+) 的一条模型发现未通过逐字证据校验，已剔除。",
                "主題 {1} 的一條模型發現未通過逐字證據校驗，已剔除。",
                "A model finding for topic {1} failed exact source-quotation validation and was excluded.", lang);
        result = warningPattern(result, "主题 (\\d+) 的一条模型记录未通过证据或适用性校验，未采纳。",
                "主題 {1} 的一條模型記錄未通過證據或適用性校驗，未採納。",
                "A model record for topic {1} failed evidence or applicability validation and was not accepted.", lang);
        result = warningPattern(result, "主题 (\\d+) 未完成语义检查：", "主題 {1} 未完成語義檢查：", "Topic {1} did not complete semantic review: ", lang);
        result = warningPattern(result, "主题 (\\d+) 检索失败，使用词项检索：", "主題 {1} 檢索失敗，使用詞項檢索：", "Retrieval failed for topic {1}; lexical retrieval was used: ", lang);
        result = warningPattern(result, "主题 (\\d+) 的 (standard|project_fact|package_manifest) 检索失败：", "主題 {1} 的 {2} 檢索失敗：", "Retrieval of {2} context failed for topic {1}: ", lang);
        result = warningPattern(result, "主观风险按严重程度排序后限制为 (\\d+) 条，另有 (\\d+) 条未列入。",
                "主觀風險按嚴重程度排序後限制為 {1} 條，另有 {2} 條未列入。",
                "Subjective risks were sorted by severity and limited to {1} findings; {2} additional findings were omitted.", lang);
        result = warningPattern(result, "无法连接 ([^，\\r\\n]+)，请确认本地服务已启动（([^\\r\\n]+)）",
                "無法連接 {1}，請確認本地服務已啟動（{2}）", "Cannot connect to {1}; confirm that the local service has started ({2})", lang);
        return result;
    }
    private static String warningPattern(String value, String pattern, String hant, String en, String lang) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = tr(lang, matcher.group(), hant, en);
            for (int i = 1; i <= matcher.groupCount(); i++) replacement = replacement.replace("{" + i + "}", matcher.group(i));
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
    private String available(String value, String lang) { return nvl(value).trim().isEmpty() ? tr(lang, "未提供", "未提供", "Not available") : value; }
    private static String nvl(String value) { return value == null ? "" : value; }
    private static <T> List<T> safe(List<T> value) { return value == null ? Collections.emptyList() : value; }
    private static String tr(String lang, String hans, String hant, String en) { return "en".equalsIgnoreCase(lang) ? en : "zh-Hant".equalsIgnoreCase(lang) ? hant : hans; }
}
