package com.consense.document;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFEndnote;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 多格式文档解析：
 *  PDF 优先取文本层，文本层不足（扫描件）时按页渲染转 OCR；
 *  DOCX / DOC / MSG / EML / TXT / MD 各自解析为按页或整段的纯文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParser {

    private static final long MAX_ZIP_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_ZIP_XML_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 5000;

    static {
        // Legitimate tender EMF images compress below POI's default 1% ratio.
        // Configure the process-wide POI guard once, never around individual requests.
        ZipSecureFile.setMinInflateRatio(0.001);
        ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_ZIP_XML_BYTES);
    }

    private final ConsenseProperties props;
    private final OcrClient ocrClient;

    public ParsedDocument parse(String fileName, byte[] bytes) {
        return parse(fileName,bytes,DocumentParseProbe.disabled());
    }

    /** Only routing observations are collected here; this result is never a complete parse. */
    public ParsedDocument observe(String fileName, byte[] bytes) {
        String ext = extensionOf(fileName), sha = DocumentParseProbe.sha256(bytes);
        ParseCoverage coverage = new ParseCoverage();
        List<DocumentBlock> blocks = new ArrayList<>(); List<PageText> pages = new ArrayList<>();
        Map<Integer, OcrClient.OcrResult> cached = new HashMap<>(); Map<Integer, String> imageHashes = new HashMap<>();
        ObjectNode routing = JsonUtils.mapper().createObjectNode(); routing.put("format", ext);
        int pageCount = 0; boolean ocrUsed = false; String text = "";
        try {
            if (!"pdf".equals(ext) && bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw new IOException("Declared extension differs from actual PDF container");
            if (java.util.Arrays.asList("txt", "md", "eml", "emlx").contains(ext)) {
                String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (decoded.indexOf('\0') >= 0) throw new IOException("Binary bytes are not a verified textual format");
            }
            if ("pdf".equals(ext)) {
                try (PDDocument pdf = PDDocument.load(bytes)) {
                    pageCount = pdf.getNumberOfPages(); coverage.setTotalPages(pageCount);
                    if (pdf.getDocumentInformation().getTitle() != null) routing.put("pdfTitle", pdf.getDocumentInformation().getTitle());
                    if (pdf.getDocumentInformation().getSubject() != null) routing.put("pdfSubject", pdf.getDocumentInformation().getSubject());
                    if (pageCount > 0) {
                        PdfPageEvidence frame = PdfNativeSupport.page(1, pdf.getPage(0));
                        PositionedStripper stripper = new PositionedStripper(1, pdf.getPage(0), frame);
                        stripper.setStartPage(1); stripper.setEndPage(1); text = normalize(stripper.getText(pdf)); blocks.addAll(stripper.blocks);
                        if (text.replaceAll("\\s", "").length() < props.getOcr().getTextLayerMinChars()) {
                            PDFRenderer renderer = new PDFRenderer(pdf);
                            renderer.setAnnotationsFilter(annotation -> !(annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget)
                                    || annotation.getNormalAppearanceStream() != null);
                            BufferedImage image = renderer.renderImageWithDPI(0, 200, ImageType.RGB);
                            if (!(text.isEmpty() && isWhitePage(image))) {
                                if (!props.getOcr().isEnabled() || !ocrClient.available()) throw new IOException("Identity OCR disabled or unavailable");
                                ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out);
                                byte[] input = out.toByteArray(); OcrClient.OcrResult observed = ocrClient.observeIdentity(input,
                                        pdfSourceContext(fileName, sha, pageCount, 1, pdf.getPage(0), image, input));
                                if (observed == null) throw new IOException("Identity OCR returned no observation");
                                cached.put(1, observed); imageHashes.put(1, DocumentParseProbe.sha256(input));
                                blocks.addAll(ocrBlocks(observed, 1, image.getWidth(), image.getHeight()));
                                if (text.isEmpty()) text = normalize(observed.getText());
                                ocrUsed = true; coverage.setOcrPages(1);
                            }
                        }
                        pages.add(new PageText(1, text, ocrUsed ? "identity_ocr" : "identity_native")); coverage.setParsedPages(1);
                    }
                }
            } else if ("docx".equals(ext)) {
                preflightDocx(bytes);
                try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                    int index = 0; DocxNumberingResolver numbering = new DocxNumberingResolver(word);
                    for (XWPFParagraph paragraph : word.getParagraphs()) {
                        DocumentBlock observed = wordBlock(paragraph.getCTP().getDomNode(), "identity_paragraph", "body/" + index + "/paragraph", numbering);
                        if (!observed.getText().isEmpty()) blocks.add(observed);
                        index++; if (joinBlocks(blocks).length() >= 4096) break;
                    }
                    text = joinBlocks(blocks); routing.put("nonDrawingFormatVerified", true);
                    routing.put("observedBodyParagraphs", index); routing.put("totalBodyParagraphs", word.getParagraphs().size());
                }
            } else if (java.util.Arrays.asList("txt", "md", "eml", "emlx", "msg", "doc").contains(ext)) {
                // Non-PDF textual formats cannot be native drawing containers; unsupported semantics stay explicit.
                ParsedDocument nativeObservation = parse(fileName, bytes);
                text = nativeObservation.getText(); blocks.addAll(nativeObservation.getBlocks()); routing.put("nonDrawingFormatVerified", true);
            } else coverage.getLimitations().add("Unsupported identity observation format: " + ext);
        } catch (Exception failure) {
            coverage.getLimitations().add("Identity observation incomplete: " + abbreviate(failure.getMessage(), 180));
        }
        coverage.setComplete(false); coverage.getLimitations().add("Routing observation only; unobserved pages and relationships are unknown.");
        ParsedDocument result = new ParsedDocument(text, pages, pageCount, ocrUsed, "Identity observation; full parsing not started", blocks,
                coverage, sha, Collections.emptyList());
        result.observationOnly = true; result.routingObservation = routing; result.observationFormat = ext;
        result.observationConfig = ocrConfigIdentity(); result.observedOcr.putAll(cached); result.observedImageHashes.putAll(imageHashes);
        return result;
    }

    public ParsedDocument parse(String fileName, byte[] bytes, ParsedDocument observation) {
        return parse(fileName,bytes,observation,null);
    }
    public ParsedDocument parse(String fileName, byte[] bytes, ParsedDocument observation, OerDocumentIdentity identity) {
        validateObservation(fileName, bytes, observation);
        return parse(fileName, bytes, DocumentParseProbe.disabled(), observation, readingPlan(fileName,bytes,observation,identity));
    }

    private String ocrConfigIdentity() { return DocumentParseProbe.sha256(JsonUtils.write(props.getOcr()).getBytes(StandardCharsets.UTF_8)); }
    private void validateObservation(String fileName, byte[] bytes, ParsedDocument observation) {
        if (observation == null || !observation.observationOnly || !DocumentParseProbe.sha256(bytes).equals(observation.getSourceSha256())
                || !extensionOf(fileName).equals(observation.observationFormat) || !ocrConfigIdentity().equals(observation.observationConfig))
            throw new IllegalArgumentException("Identity observations belong to different source bytes, format or OCR configuration");
    }

    /** Vetting may opt into a fresh job linked to its actual stored source and original bytes. */
    public ParsedDocument parse(String fileName,byte[] bytes,String parseJobId,String sourceId,String expectedSourceSha256) {
        DocumentParseProbe probe=DocumentParseProbe.start(props,parseJobId,sourceId,expectedSourceSha256,fileName,bytes);
        return parse(fileName,bytes,probe);
    }
    public ParsedDocument parse(String fileName, byte[] bytes, String parseJobId, String sourceId, String expectedSourceSha256, ParsedDocument observation) {
        return parse(fileName,bytes,parseJobId,sourceId,expectedSourceSha256,observation,null);
    }
    public ParsedDocument parse(String fileName, byte[] bytes, String parseJobId, String sourceId, String expectedSourceSha256, ParsedDocument observation, OerDocumentIdentity identity) {
        validateObservation(fileName, bytes, observation);
        OerReadingPlan plan=readingPlan(fileName,bytes,observation,identity);
        return parse(fileName, bytes, DocumentParseProbe.start(props, parseJobId, sourceId, expectedSourceSha256, fileName, bytes), observation,plan);
    }
    private OerReadingPlan readingPlan(String fileName,byte[] bytes,ParsedDocument observation,OerDocumentIdentity identity) {
        String sha=DocumentParseProbe.sha256(bytes);
        if(identity==null && observation!=null) {
            OerDocumentIdentity candidate=new OerSourceRouting().route(fileName,sha,observation);
            if(!"unknown".equals(candidate.getIdentityStatus()))identity=candidate;
        }
        return new OerReadingPlan(sha,extensionOf(fileName),identity);
    }

    private ParsedDocument parse(String fileName,byte[] bytes,DocumentParseProbe probe) {
        return parse(fileName, bytes, probe, null);
    }
    private ParsedDocument parse(String fileName,byte[] bytes,DocumentParseProbe probe,ParsedDocument initialObservation) {
        return parse(fileName,bytes,probe,initialObservation,readingPlan(fileName,bytes,initialObservation,null));
    }
    private ParsedDocument parse(String fileName,byte[] bytes,DocumentParseProbe probe,ParsedDocument initialObservation,OerReadingPlan readingPlan) {
        boolean returnedNormally=false;Throwable operationFailure=null;
        String ext = extensionOf(fileName);
        try (DocumentParseProbe.Timer timer=probe.measure("parse_dispatch_and_document",null,"Actual parser operation; measured probe event I/O excluded, nested stages are not additive")) {
            ParsedDocument result;
            if ("pdf".equals(ext)) {
                result=parsePdf(fileName,bytes,probe,initialObservation,readingPlan);
            } else if ("docx".equals(ext)) result=parseDocx(bytes,probe);
            else if ("doc".equals(ext)) result=parseDoc(bytes);
            else if ("msg".equals(ext)) result=parseMsg(bytes);
            else if ("eml".equals(ext) || "emlx".equals(ext)) result=parseEml(bytes);
            else result=parsePlainText(bytes);
            result = new ParsedDocument(result.getText(), result.getPages(), result.getPageCount(), result.isOcrUsed(),
                    result.getMessage(), result.getBlocks(), result.getCoverage(), DocumentParseProbe.sha256(bytes), result.getNativePdfEvidence());
            readingPlan.annotate(result.getBlocks());result.getCoverage().setReadingPlan(readingPlan.metadata());
            if (initialObservation != null && initialObservation.routingObservation != null)
                result.routingObservation = initialObservation.routingObservation.deepCopy();
            if(probe.enabled())probe.event("parse_output",null,DocumentParseProbe.map("parseStatus",result.getParseStatus(),"result",result,
                    "textUtf16Chars",result.getText().length(),"textUtf8Sha256",DocumentParseProbe.sha256(result.getText().getBytes(StandardCharsets.UTF_8)),
                    "boundary","Exact parser output and deletion/strike metadata; source effectiveness and OCR accuracy remain unverified"));
            returnedNormally=true;return result;
        } catch(DocumentParseProbe.Failure observation) {operationFailure=observation;throw observation;
        } catch (BizException e) {
            operationFailure=e;probe.originalFailure("parse_failure",null,e);
            throw e;
        } catch (Exception e) {
            probe.originalFailure("parse_failure",null,e);
            log.warn("解析 {} 失败: {}", fileName, e.getMessage());
            BizException wrapped=new BizException(4003, "无法解析文件 " + fileName + "：" + e.getMessage());operationFailure=wrapped;throw wrapped;
        } catch(Error e){operationFailure=e;probe.originalFailure("parse_failure",null,e);throw e;
        } finally {probe.finish(returnedNormally?"returned_normally":"aborted",operationFailure);}
    }

    // ------------------------------------------------------------------ PDF

    private ParsedDocument parsePdf(String fileName,byte[] bytes,DocumentParseProbe probe,ParsedDocument initialObservation,OerReadingPlan readingPlan) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            int totalPages = document.getNumberOfPages();
            String sourceSha = DocumentParseProbe.sha256(bytes);
            List<PageText> pages = new ArrayList<>(totalPages);
            List<DocumentBlock> blocks = new ArrayList<>();
            List<PdfPageEvidence> nativeEvidence = new ArrayList<>();
            for (int pageNo = 1; pageNo <= totalPages; pageNo++)
                nativeEvidence.add(PdfNativeSupport.page(pageNo, document.getPage(pageNo - 1)));
            PdfNativeSupport.collectForms(document, nativeEvidence);
            ParseCoverage coverage = new ParseCoverage();
            coverage.setTotalPages(totalPages);
            for (PdfPageEvidence evidence : nativeEvidence) if (evidence.getPageNo() == 0)
                coverage.getLimitations().add("Unassigned form widgets retained without inventing a physical page; form values and appearances need review.");
            PDFRenderer renderer = new PDFRenderer(document);
            // PageDrawer constructs missing annotation appearances by default. A stored
            // AcroForm /V is not an original visible /AP; do not synthesize it for OCR.
            renderer.setAnnotationsFilter(annotation -> !(annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget)
                    || annotation.getNormalAppearanceStream() != null);
            Boolean ocrReady = null;
            for (int pageNo = 1; pageNo <= totalPages; pageNo++) {
                PDPage page = document.getPage(pageNo - 1);
                PdfPageEvidence pageEvidence = nativeEvidence.get(pageNo - 1);
                PositionedStripper stripper = new PositionedStripper(pageNo, page, pageEvidence);
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text;
                List<DocumentBlock> pageBlocks;
                boolean requiresOcr;
                List<DocumentBlock> formBlocks = new ArrayList<>();
                StringBuilder effectiveForms = new StringBuilder();
                try (DocumentParseProbe.Timer timer=probe.measure("pdf_native_extraction_and_heuristic",pageNo,"Actual native extraction and original short-circuit OCR selection")) {
                    for (PdfPageEvidence.PdfFormField field : pageEvidence.getFormFields()) {
                        DocumentBlock formBlock = block("pdf_form_widget", field.getId(), pageNo, "", "native_form");
                        formBlock.setPdfFormField(field); formBlock.setBbox(field.getBbox());
                        formBlock.setOriginalText(field.getAppearanceValue() == null ? "" : field.getAppearanceValue());
                        formBlock.setNativeVisibilityStatus(field.getVisibilityStatus());
                        if (!field.isConflict() && "operator_visible_unverified".equals(field.getVisibilityStatus())
                                && "appearance_text_unverified".equals(field.getValueStatus()))
                            formBlock.setText(field.getAppearanceValue());
                        if (!JsonUtils.isBlankText(formBlock.getText())) effectiveForms.append('\n').append(formBlock.getText());
                        if (field.isConflict() || (field.getFieldValue() != null && !field.getFieldValue().isEmpty()
                                && !"appearance_text_unverified".equals(field.getValueStatus()))) {
                            if (!coverage.getNeedsReviewPages().contains(pageNo)) coverage.getNeedsReviewPages().add(pageNo);
                            coverage.getLimitations().add("Page " + pageNo + " form " + field.getId() + ": " + field.getValueStatus() + "; stored and appearance values retained separately.");
                        }
                        formBlocks.add(formBlock);
                    }
                    String nativeExtractedText=stripper.getText(document);
                    pageBlocks = new ArrayList<>(stripper.blocks);
                    pageBlocks.addAll(formBlocks);
                    pageEvidence.setNativeBlocks(new ArrayList<>(pageBlocks));
                    // Preserve native paragraph/line separators; block separation is not document typography.
                    text = normalize(nativeExtractedText + effectiveForms);
                    boolean visualGap=needsOcr(text, pageEvidence,probe,pageNo);
                    requiresOcr = readingPlan.requiresFormLayout() || visualGap;
                    probe.event("pdf_reading_task_selection",pageNo,DocumentParseProbe.map("documentFamily",readingPlan.metadata().path("documentFamily").asText(),
                            "formLayoutRequired",readingPlan.requiresFormLayout(),"observedVisualGap",visualGap,"requiresOcr",requiresOcr));
                    probe.event("pdf_native_extraction",pageNo,DocumentParseProbe.map("nativeExtractedText",nativeExtractedText,"normalizedText",text,"blocks",pageBlocks,"requiresOcr",requiresOcr,
                            "pageRotation",page.getRotation(),"cropBox",page.getCropBox().toString()));
                } catch (Exception e) {
                    if(e instanceof DocumentParseProbe.Failure)throw e;
                    probe.event("pdf_native_extraction_failure",pageNo,DocumentParseProbe.map("failureChain",DocumentParseProbe.failures(e),"nativeBlocksBeforeDiscard",stripper.blocks));
                    List<DocumentBlock> nativeBlocks = new ArrayList<>(stripper.blocks);
                    nativeBlocks.addAll(formBlocks); pageEvidence.setNativeBlocks(nativeBlocks);
                    text = "";
                    pageBlocks = new ArrayList<>(formBlocks);
                    requiresOcr = true;
                    coverage.getLimitations().add("Page " + pageNo + " native extraction failed: "
                            + abbreviate(e.getMessage(), 120));
                }
                String status = "native";
                if (requiresOcr) {
                    try {
                        BufferedImage image;
                        try(DocumentParseProbe.Timer timer=probe.measure("pdf_render",pageNo,"Original PDFRenderer RGB render at fixed 200 DPI")) {
                            image = renderer.renderImageWithDPI(pageNo - 1, 200, ImageType.RGB);
                        }
                        probe.image("pdf_render_image",pageNo,image,200);
                        // An empty text layer alone does not prove an empty physical page:
                        // scans and vector artwork can both lack text and image resources.
                        boolean white=false;
                        if(text.isEmpty())try(DocumentParseProbe.Timer timer=probe.measure("pdf_blank_page_test",pageNo,"Original exact all-white pixel test, called only for empty native text")) {white=isWhitePage(image);}
                        probe.event("pdf_blank_page_predicate",pageNo,DocumentParseProbe.map("nativeTextEmpty",text.isEmpty(),"whiteTestEvaluated",text.isEmpty(),"allWhite",text.isEmpty()?white:null));
                        if (text.isEmpty() && white) {
                            coverage.getBlankPages().add(pageNo);
                            status = "blank";
                        } else {
                            OcrClient actualOcr=probe.enabled()?new ObservedOcrClient(ocrClient,probe,pageNo):ocrClient;
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            try(DocumentParseProbe.Timer timer=probe.measure("pdf_ocr_input_png_encoding",pageNo,"PNG encoding of the actual OCR input")) {ImageIO.write(image, "png", buffer);}
                            byte[] input = buffer.toByteArray();
                            OcrClient.OcrResult result = initialObservation != null && DocumentParseProbe.sha256(input).equals(initialObservation.observedImageHashes.get(pageNo))
                                    ? initialObservation.observedOcr.get(pageNo) : null;
                            boolean reused = result != null && ocrClient.canReuseIdentityObservation(result);
                            if (!reused) result = null;
                            if (!reused) {
                                if (ocrReady == null) ocrReady = props.getOcr().isEnabled() && actualOcr.available();
                                if (!ocrReady) throw new IOException("OCR disabled or service unavailable");
                                Map<String,Object> sourceContext=pdfSourceContext(fileName, sourceSha, totalPages, pageNo, page, image, input);
                                sourceContext.put("readingPlan",readingPlan.metadata());
                                result = actualOcr.recognize(input, sourceContext);
                            }
                            probe.event("pdf_ocr_routing",pageNo,DocumentParseProbe.map("configuredEnabled",props.getOcr().isEnabled(),"ocrReady",ocrReady,"observationReused",reused));
                            if (result == null || result.isEmpty()) throw new IOException("OCR returned no text");
                            List<DocumentBlock> visualBlocks = ocrBlocks(result, pageNo, image.getWidth(), image.getHeight());
                            StringBuilder supplemental = new StringBuilder();
                            List<DocumentBlock> retained = new ArrayList<>(pageBlocks);
                            for (DocumentBlock visual : visualBlocks) {
                                coordinateVisualGroup(visual,pageBlocks,!JsonUtils.isBlankText(text));
                                if (!JsonUtils.isBlankText(visual.getEffectiveText())) supplemental.append('\n').append(visual.getEffectiveText());
                                retained.add(visual);
                            }
                            int groupIndex=0;
                            for(JsonNode group:result.getWholePageGroups()) {
                                DocumentBlock whole=block("visual_parser_group","pdf-page/"+pageNo+"/parser-page-group/"+groupIndex++,pageNo,"","parser_page_group_candidate");
                                whole.setParserPageGroup(group.deepCopy());retained.add(whole);coverage.getWholePageGroups().add(group.deepCopy());
                            }
                            text = normalize(text + supplemental);
                            pageBlocks = retained;
                            ObjectNode observation = JsonUtils.mapper().createObjectNode(); observation.put("pageNo", pageNo);
                            if (result.getCoverage() != null) observation.set("coverage", result.getCoverage());
                            if (result.getFrame() != null) observation.set("frame", result.getFrame());
                            if (result.getProvenance() != null) observation.set("provenance", result.getProvenance());
                            if (result.getParserEvidence() != null) observation.set("parserEvidence", result.getParserEvidence());
                            coverage.getVisualCoverage().add(observation);
                            coverage.getLimitations().add("Page " + pageNo + " visual regions, writing states and native/visual relationships remain source-unverified.");
                            coverage.setOcrPages(coverage.getOcrPages() + 1);
                            coverage.setOcrQualityStatus("needs_review");
                            if (!coverage.getNeedsReviewPages().contains(pageNo)) coverage.getNeedsReviewPages().add(pageNo);
                            status = "ocr";
                        }
                    } catch (Exception e) {
                        if(e instanceof DocumentParseProbe.Failure)throw e;
                        probe.event("pdf_ocr_page_failure",pageNo,DocumentParseProbe.map("failureChain",DocumentParseProbe.failures(e)));
                        coverage.getFailedPages().add(pageNo);
                        coverage.getLimitations().add("Page " + pageNo + ": " + abbreviate(e.getMessage(), 180));
                        status = "ocr_failed";
                        log.warn("第 {} 页 OCR 未完成: {}", pageNo, e.getMessage());
                    }
                }
                if (!"ocr_failed".equals(status)) {
                    coverage.setParsedPages(coverage.getParsedPages() + 1);
                }
                if (pageBlocks.isEmpty() && !JsonUtils.isBlankText(text)) {
                    pageBlocks.add(block("pdf_page", "pdf-page/" + pageNo, pageNo, text, status));
                }
                pages.add(new PageText(pageNo, text, status));
                blocks.addAll(pageBlocks);
                probe.event("pdf_physical_page_output",pageNo,DocumentParseProbe.map("status",status,"text",text,"blocks",pageBlocks,
                        "pageParsed",!"ocr_failed".equals(status),"coverageSoFar",coverage));
            }
            String text = joinPages(pages);
            coverage.setComplete(coverage.getFailedPages().isEmpty());
            observeNativeTables(fileName,bytes,sourceSha,totalPages,nativeEvidence,blocks,coverage,readingPlan,probe);
            coverage.getLimitations().add("OCR selection uses a per-page text/image heuristic; text accuracy and diagram topology require review.");
            String message = "PDF 共 " + totalPages + " 页，已解析 " + coverage.getParsedPages()
                    + " 页，OCR " + coverage.getOcrPages() + " 页，确认空白 " + coverage.getBlankPages().size() + " 页"
                    + (coverage.isComplete() ? "" : "；未完成页 " + coverage.getFailedPages())
                    + (coverage.getOcrPages() > 0 ? "；OCR 文字质量待原页核对 " + coverage.getNeedsReviewPages() : "");
            return new ParsedDocument(text, pages, totalPages, coverage.getOcrPages() > 0,
                    message, blocks, coverage, DocumentParseProbe.sha256(bytes), nativeEvidence);
        }
    }

    private void observeNativeTables(String fileName,byte[] original,String sha,int pageCount,List<PdfPageEvidence> nativeEvidence,
                                     List<DocumentBlock> blocks,ParseCoverage coverage,OerReadingPlan plan,DocumentParseProbe probe) {
        List<Integer> selected=new ArrayList<>(),unselected=new ArrayList<>();List<PdfPageEvidence> observations=new ArrayList<>();
        java.util.Set<Integer> observedTablePages=new java.util.HashSet<>();
        for(DocumentBlock block:blocks)if("table".equals(block.getKind()) && block.getPageNo()!=null)observedTablePages.add(block.getPageNo());
        for(PdfPageEvidence page:nativeEvidence) {
            if(page.getPageNo()<1)continue;
            boolean gap=page.getPlacedImageRegions().stream().anyMatch(region->region.path("requiresVisualObservation").asBoolean())
                    || page.getPaintedVectorRegions().stream().anyMatch(region->region.path("requiresVisualObservation").asBoolean())
                    || nativeTextLayoutGap(page)
                    || observedTablePages.contains(page.getPageNo());
            if(gap && !page.getGlyphs().isEmpty()){selected.add(page.getPageNo());observations.add(page);}else unselected.add(page.getPageNo());
        }
        ObjectNode state=JsonUtils.mapper().createObjectNode();state.put("sourceSha256",sha);state.put("sourcePageCount",pageCount);
        state.set("selectedPhysicalPages",JsonUtils.mapper().valueToTree(selected));state.set("unselectedPhysicalPages",JsonUtils.mapper().valueToTree(unselected));
        state.put("unselectedNativeTableStatus","unknown");state.put("sourceQualityAccepted",false);state.put("canonicalAdopted",false);
        coverage.setNativeTableCandidates(state);
        if(selected.isEmpty()){state.put("status","not_requested_no_observed_native_table_gap");return;}
        if(!plan.confirmedNonDrawing()){state.put("status","not_executed_identity_unbound");return;}
        Map<String,Object> context=DocumentParseProbe.map("source",DocumentParseProbe.map("sourceFilename",fileName,"sourceSha256",sha,
                "mediaType","application/pdf","pageCount",pageCount),"selectedPhysicalPages",selected,"readingPlan",plan.metadata(),"javaNativeEvidence",observations);
        try(DocumentParseProbe.Timer timer=probe.measure("native_pdf_table_candidates",null,"Single original-byte CPU candidate task for actual observed native gaps; no adoption or Java reparse")) {
            probe.binary("native_pdf_actual_input",null,DocumentParseProbe.map("nativeContext",context,"boundary","Exact original PDF supplied once; Java and provider glyph namespaces are independent"),original,"pdf");
            JsonNode response=ocrClient.observeNativePdf(original,context);
            if(response==null){state.put("status","not_executed_legacy_or_unsupported_client");return;}
            if(!response.isObject() || !"consense.native-pdf-candidates.v1".equals(response.path("schema").asText())
                    || !java.util.Arrays.asList("candidate_observed","partial","failed").contains(response.path("status").asText())
                    || !sha.equals(response.path("source").path("sourceSha256").asText()) || !response.path("pages").isArray())
                throw new IOException("Native table candidate original source or selected physical-page bindings differ");
            boolean fatal="failed".equals(response.path("status").asText()) && response.path("pages").isEmpty() && response.path("failure").isObject();
            if(!fatal && (response.path("source").path("pageCount").asInt()!=pageCount
                    || !response.path("selectedPhysicalPages").equals(JsonUtils.mapper().valueToTree(selected)) || response.path("pages").size()!=selected.size()))
                throw new IOException("Native table candidate original page-count or task differs");
            state.setAll((ObjectNode)response.deepCopy());state.put("sourcePageCount",pageCount);
            state.put("sourceQualityAccepted",false);state.put("canonicalAdopted",false);
            state.set("requestedPhysicalPages",JsonUtils.mapper().valueToTree(selected));
            state.set("unselectedPhysicalPages",JsonUtils.mapper().valueToTree(unselected));state.put("unselectedNativeTableStatus","unknown");
            java.util.Set<Integer> observedPages=new java.util.HashSet<>();
            for(JsonNode page:response.path("pages"))if(!selected.contains(page.path("physicalPageNo").asInt())
                    || !observedPages.add(page.path("physicalPageNo").asInt()) || !page.path("methods").isArray() || page.path("methods").size()!=3)
                throw new IOException("Native table candidate omitted or duplicated an entire page or method");
            for(JsonNode page:response.path("pages"))for(JsonNode method:page.path("methods")) {
                int physical=page.path("physicalPageNo").asInt();DocumentBlock candidate=block("native_table_candidate",
                        "pdf-page/"+physical+"/native-table-method/"+method.path("method").asText(),physical,"","native_table_candidate");
                ObjectNode observed=((ObjectNode)method).deepCopy();observed.put("physicalPageNo",physical);observed.set("frame",page.path("frame").deepCopy());
                observed.set("coverage",(page.has("coverage")?page.path("coverage"):response.path("coverage")).deepCopy());observed.set("bundleRef",response.path("bundleRef").deepCopy());
                observed.set("sourceObservationRef",page.path("sourceObservationRef").deepCopy());
                observed.set("nativeGlyphNamespace",page.path("nativeGlyphNamespace").deepCopy());observed.put("sourceSha256",sha);
                observed.put("semanticStatus","candidate_unverified");candidate.setNativeTableCandidate(observed);blocks.add(candidate);
            }
            probe.event("native_pdf_table_candidate_result",null,DocumentParseProbe.map("result",state,"adoptionPerformed",false));
        } catch(Exception failure) {
            if(failure instanceof DocumentParseProbe.Failure)throw (DocumentParseProbe.Failure)failure;
            state.put("status","native_candidate_task_failed");state.set("failureChain",JsonUtils.mapper().valueToTree(DocumentParseProbe.failures(failure)));
            coverage.getLimitations().add("Native table candidate task failed for physical pages "+selected+"; prior native text and full method scope retained as unknown.");
            probe.event("native_pdf_table_candidate_failure",null,DocumentParseProbe.map("failureChain",DocumentParseProbe.failures(failure),"selectedPhysicalPages",selected));
        }
        for(Integer physical:selected)if(!coverage.getNeedsReviewPages().contains(physical))coverage.getNeedsReviewPages().add(physical);
    }

    /** Separated native runs need structure observations even when no line/image was painted. */
    private static boolean nativeTextLayoutGap(PdfPageEvidence page) {
        List<double[]> boxes=new ArrayList<>();
        for(PdfPageEvidence.PdfGlyph glyph:page.getGlyphs()){
            double[] box=glyph.getBbox();
            if(!glyph.isEligibleForNativeText() || JsonUtils.isBlankText(glyph.getUnicode()) || box==null || box.length!=4
                    || !java.util.Arrays.stream(box).allMatch(Double::isFinite) || box[2]<=0 || box[3]<=0)continue;
            boxes.add(box);
        }
        boxes.sort(java.util.Comparator.comparingDouble(box->box[1]+box[3]/2));
        double[] crop=page.getCropBox();double width=crop!=null&&crop.length==4?crop[2]:0,height=crop!=null&&crop.length==4?crop[3]:0;
        if(page.getRotation()==90 || page.getRotation()==270){double swap=width;width=height;height=swap;}
        if(width<=0 || height<=0)return false;
        List<double[]> row=new ArrayList<>();double center=0,rowHeight=0;
        for(double[] box:boxes){
            double y=box[1]+box[3]/2;
            if(!row.isEmpty() && Math.abs(y-center)>Math.max(rowHeight,box[3])/2){
                if(separatedNativeRuns(row,height/width))return true;row.clear();
            }
            if(row.isEmpty()){center=y;rowHeight=box[3];}else rowHeight=Math.max(rowHeight,box[3]);
            row.add(box);
        }
        return separatedNativeRuns(row,height/width);
    }
    private static boolean separatedNativeRuns(List<double[]> row,double pageAspect) {
        row.sort(java.util.Comparator.comparingDouble(box->box[0]));double[] previous=null;
        for(double[] box:row){
            if(previous!=null && box[0]-previous[0]-previous[2]>Math.max(4*Math.max(previous[2],box[2]),2*Math.max(previous[3],box[3])*pageAspect))return true;
            if(previous==null || box[0]+box[2]>previous[0]+previous[2])previous=box;
        }
        return false;
    }

    private static Map<String,Object> pdfSourceContext(String fileName,String sourceSha,int pageCount,int pageNo,
                                                       PDPage page,BufferedImage image,byte[] input) throws IOException {
        PDRectangle crop=page.getCropBox(),media=page.getMediaBox();int rotation=page.getRotation();double unit=page.getUserUnit();
        if ((rotation!=0 && rotation!=90 && rotation!=180 && rotation!=270) || !Double.isFinite(unit) || unit<=0
                || !validBox(crop) || !validBox(media)) throw new IOException("Original PDF page frame cannot be bound to raster");
        double width=crop.getWidth()*unit,height=crop.getHeight()*unit;
        if(rotation==90 || rotation==270){double swap=width;width=height;height=swap;}
        String imageSha=DocumentParseProbe.sha256(input);
        Map<String,Object> raster=DocumentParseProbe.map("coordinateFrame","raster","coordinateUnit","pixel",
                "width",image.getWidth(),"height",image.getHeight(),"artifactName","page.png","sha256",imageSha);
        Map<String,Object> target=DocumentParseProbe.map("coordinateFrame","original_page","coordinateUnit","pt",
                "width",width,"height",height,"physicalPageNo",pageNo,"sourceSha256",sourceSha,
                "cropBox",pageBox(crop),"mediaBox",pageBox(media),"rotation",rotation,"userUnit",unit,
                "renderer","PDFBox PDFRenderer","renderDpi",200,"origin","top-left","frameMeaning","rotated CropBox display");
        // PDFRenderer returns the rotated CropBox; actual raster dimensions include its rounding.
        return DocumentParseProbe.map("source",DocumentParseProbe.map("sourceFilename",fileName,"sourceSha256",sourceSha,
                        "mediaType","application/pdf","pageCount",pageCount),"physicalPageNo",pageNo,"imageSha256",imageSha,
                "width",image.getWidth(),"height",image.getHeight(),"originalPageTransform",DocumentParseProbe.map("kind","affine",
                        "matrix",new double[]{width/image.getWidth(),0,0,0,height/image.getHeight(),0},"inputFrame",raster,"targetFrame",target));
    }
    private static boolean validBox(PDRectangle box) {
        return box!=null && Float.isFinite(box.getLowerLeftX()) && Float.isFinite(box.getLowerLeftY())
                && Float.isFinite(box.getWidth()) && Float.isFinite(box.getHeight()) && box.getWidth()>0 && box.getHeight()>0;
    }
    private static double[] pageBox(PDRectangle box) { return new double[]{box.getLowerLeftX(),box.getLowerLeftY(),box.getWidth(),box.getHeight()}; }

    private static boolean isWhitePage(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) return false;
        }
        return true;
    }

    private boolean needsOcr(String text, PdfPageEvidence page,DocumentParseProbe probe,int physicalPage) {
        int visible = text.replaceAll("\\s", "").length();
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD' || text.charAt(i) == '\u0000') bad++;
        }
        boolean shortText=visible < props.getOcr().getTextLayerMinChars();
        boolean corrupt=bad > Math.max(3,visible/10);
        boolean placedGap=page.getPlacedImageRegions().stream().anyMatch(region->region.path("requiresVisualObservation").asBoolean());
        boolean paintedGap=page.getPaintedVectorRegions().stream().anyMatch(region->region.path("requiresVisualObservation").asBoolean());
        boolean selected=shortText||corrupt||placedGap||paintedGap;
        probe.event("pdf_ocr_selection_predicate",physicalPage,DocumentParseProbe.map("visibleChars",visible,"badChars",bad,
                "minimumTextChars",props.getOcr().getTextLayerMinChars(),"shortText",shortText,"corruptText",corrupt,
                "placedImageRegions",page.getPlacedImageRegions(),"placedImageGap",placedGap,"paintedVectorRegions",page.getPaintedVectorRegions(),
                "paintedVectorGap",paintedGap,"requiresOcr",selected));
        return selected;
    }

    private List<DocumentBlock> ocrBlocks(OcrClient.OcrResult result, int page, int width, int height) {
        List<DocumentBlock> blocks = new ArrayList<>();
        int index = 0;
        JsonNode regions = result.getRegions();
        if (regions.size() > 0) {
            for (JsonNode region : regions) {
                DocumentBlock observed = block(region.path("kind").asText("visual_region"),
                        "pdf-page/" + page + "/visual-region/" + region.path("id").asText(Integer.toString(index++)), page,
                        normalize(region.path("text").asText("")), "ocr");
                observed.setOriginalText(region.path("originalText").asText(region.path("text").asText("")));
                observed.setVisualRegion(region.deepCopy()); observed.setVisualFrame(result.getFrame()); observed.setVisualProvenance(result.getProvenance());
                if (region.path("confidence").isNumber()) observed.setConfidence(region.path("confidence").asDouble());
                JsonNode box = region.path("bbox");
                if (box.isArray() && box.size() == 4 && box.get(0).isNumber() && box.get(1).isNumber()
                        && box.get(2).isNumber() && box.get(3).isNumber())
                    observed.setBbox(normalizedObservationBox(new double[]{box.get(0).asDouble(), box.get(1).asDouble(),
                            box.get(2).asDouble(), box.get(3).asDouble()}, width, height));
                blocks.add(observed);
            }
            return blocks;
        }
        for (OcrClient.OcrLine line : result.getLines()) {
            DocumentBlock block = block("ocr_line", "pdf-page/" + page + "/ocr-line/" + index++, page,
                    normalize(line.getText()), "ocr");
            block.setConfidence(line.getConfidence());
            block.setOriginalText(line.getText() == null ? "" : line.getText());
            block.setBbox(normalizedObservationBox(line.getBbox(), width, height));
            if (line.getPolygon() != null || result.getFrame() != null || result.getProvenance() != null) {
                ObjectNode raw = JsonUtils.mapper().createObjectNode(); raw.put("id", block.getId()); raw.put("kind", "ocr_line"); raw.put("text", line.getText());
                if (line.getPolygon() != null) raw.set("polygon", line.getPolygon());
                if (line.getBbox() != null) raw.set("bbox", JsonUtils.mapper().valueToTree(line.getBbox()));
                block.setVisualRegion(raw); block.setVisualFrame(result.getFrame()); block.setVisualProvenance(result.getProvenance());
            }
            blocks.add(block);
        }
        if (blocks.isEmpty() && result.getWholePageGroups().isEmpty()) {
            DocumentBlock block = block("ocr_page", "pdf-page/" + page, page, result.getText(), "ocr");
            block.setConfidence(result.getConfidence());
            blocks.add(block);
        }
        return blocks;
    }

    private static double[] normalizedObservationBox(double[] box, double width, double height) {
        if (box == null || box.length != 4 || width <= 0 || height <= 0) return null;
        for (double value : box) if (!Double.isFinite(value)) return null;
        if (box[0] < 0 || box[1] < 0 || box[2] < 0 || box[3] < 0
                || box[0] + box[2] > width || box[1] + box[3] > height) return null;
        return new double[]{box[0] / width, box[1] / height, box[2] / width, box[3] / height};
    }

    private static void coordinateVisualGroup(DocumentBlock visual,List<DocumentBlock> nativeBlocks,boolean nativeTextPresent) {
        ObjectNode context=JsonUtils.mapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode conflicts=context.putArray("conflictingNativeBlockIds");
        double[] a = visual.getBbox();
        if(a==null && nativeTextPresent)context.put("adoptionStatus","blocked_unknown_visual_scope");
        for (DocumentBlock block : nativeBlocks) {
            double[] b = block.getBbox();
            if (a==null || b == null || JsonUtils.isBlankText(block.getText())) continue;
            double intersection = Math.max(0, Math.min(a[0]+a[2], b[0]+b[2])-Math.max(a[0],b[0]))
                    * Math.max(0, Math.min(a[1]+a[3], b[1]+b[3])-Math.max(a[1],b[1]));
            if(intersection>0)conflicts.add(block.getId());
        }
        if(conflicts.size()>0)context.put("adoptionStatus","blocked_native_visual_conflict");
        if(context.has("adoptionStatus")){visual.setSource("ocr_candidate");context.put("effectiveTextStatus","withheld_whole_group");}
        else {context.put("adoptionStatus","observed_unverified");context.put("effectiveTextStatus","supplemental_unverified");}
        context.put("groupIntegrity","complete_provider_region_retained");visual.setReadingContext(context);
    }

    // ----------------------------------------------------------------- DOCX

    private ParsedDocument parseDocx(byte[] bytes,DocumentParseProbe probe) throws Exception {
        try(DocumentParseProbe.Timer timer=probe.measure("docx_zip_preflight",null,"Original bounded OOXML inflation guard")){preflightDocx(bytes);}
        List<DocumentBlock> blocks = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            Map<DocumentBlock,Node> sourceNodes=new IdentityHashMap<>();Map<Node,String> nativePaths=new IdentityHashMap<>();
            Map<String,String> partHashes=new HashMap<>();String originalSha=DocumentParseProbe.sha256(bytes);
            DocxNumberingResolver numbering = new DocxNumberingResolver(document);
            Map<String,org.apache.poi.openxml4j.opc.PackagePart> wordParts=new HashMap<>();
            wordParts.put("body/",document.getPackagePart());
            int bodyIndex = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    DocumentBlock block = wordBlock(paragraph.getCTP().getDomNode(), "paragraph",
                            "body/" + bodyIndex + "/paragraph", numbering);
                    sourceNodes.put(block,paragraph.getCTP().getDomNode());
                    block.setParagraphStyle(paragraph.getStyle());
                    if (paragraph.getStyle() != null && document.getStyles() != null) {
                        XWPFStyle style = document.getStyles().getStyle(paragraph.getStyle());
                        if (style != null) block.setParagraphStyleName(style.getName());
                    }
                    if (hasContent(block)) blocks.add(block);
                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    int rowIndex = 0;
                    for (XWPFTableRow row : table.getRows()) {
                        String location = "body/" + bodyIndex + "/table-row/" + rowIndex++;
                        WordText rowText = new WordText();
                        List<String> values = new ArrayList<>();
                        int cellIndex = 0;
                        for (XWPFTableCell cell : row.getTableCells()) {
                            WordText value = new WordText();
                            collectWordText(cell.getCTTc().getDomNode(), false, false, value, numbering,
                                    location + "/cell/" + cellIndex++);
                            values.add(normalize(value.effective.toString()));
                            rowText.add(value);
                        }
                        DocumentBlock block = wordBlock(rowText, "table_row", location);
                        sourceNodes.put(block,row.getCtRow().getDomNode());
                        block.setCells(values);
                        block.setText(String.join(" | ", values));
                        if (hasContent(block)) blocks.add(block);
                    }
                }
                bodyIndex++;
            }
            int index = 0;
            for (XWPFHeader header : document.getHeaderList()) {
                wordParts.put("header/"+index+"/",header.getPackagePart());
                DocumentBlock block = wordBlock(header._getHdrFtr().getDomNode(), "header", "header/" + index++, numbering.newStory());
                sourceNodes.put(block,header._getHdrFtr().getDomNode());
                if (hasContent(block)) blocks.add(block);
            }
            index = 0;
            for (XWPFFooter footer : document.getFooterList()) {
                wordParts.put("footer/"+index+"/",footer.getPackagePart());
                DocumentBlock block = wordBlock(footer._getHdrFtr().getDomNode(), "footer", "footer/" + index++, numbering.newStory());
                sourceNodes.put(block,footer._getHdrFtr().getDomNode());
                if (hasContent(block)) blocks.add(block);
            }
            DocxNumberingResolver footnoteNumbering = numbering.newStory();
            for (XWPFFootnote note : document.getFootnotes()) {
                wordParts.put("word-footnote/"+note.getId()+"/",note.getPart().getPackagePart());
                // OOXML reserves IDs -1 and 0 for separator notes, which are not contract provisions.
                if (note.getId().signum() <= 0) continue;
                DocumentBlock block = wordBlock(note.getCTFtnEdn().getDomNode(), "footnote",
                        "word-footnote/" + note.getId(), footnoteNumbering);
                sourceNodes.put(block,note.getCTFtnEdn().getDomNode());
                if (hasContent(block)) blocks.add(block);
            }
            DocxNumberingResolver endnoteNumbering = numbering.newStory();
            for (XWPFEndnote note : document.getEndnotes()) {
                wordParts.put("word-endnote/"+note.getId()+"/",note.getPart().getPackagePart());
                if (note.getId().signum() <= 0) continue;
                DocumentBlock block = wordBlock(note.getCTFtnEdn().getDomNode(), "endnote",
                        "word-endnote/" + note.getId(), endnoteNumbering);
                sourceNodes.put(block,note.getCTFtnEdn().getDomNode());
                if (hasContent(block)) blocks.add(block);
            }
            for(DocumentBlock observed:blocks)for(Map.Entry<String,org.apache.poi.openxml4j.opc.PackagePart> part:wordParts.entrySet())
                if((observed.getLocation()+"/").startsWith(part.getKey())){
                    bindWordNativeObjects(observed,part.getValue());
                    Node node=sourceNodes.get(observed);String path=nativeWordPath(node,nativePaths),partName=part.getValue().getPartName().getName().substring(1);
                    if(path!=null){
                        if(!partHashes.containsKey(partName))try(java.io.InputStream input=part.getValue().getInputStream()){
                            partHashes.put(partName,DocumentParseProbe.sha256(org.springframework.util.StreamUtils.copyToByteArray(input)));
                        }
                        observed.setSourcePosition(JsonUtils.mapper().valueToTree(DocumentParseProbe.map("kind","docx_native","sourceSha256",originalSha,
                                "partName",partName,"partSha256",partHashes.get(partName),"nodePath",path)));
                    }break;
                }
            ParseCoverage coverage = unpaginatedCoverage("DOCX locations are body/table positions; physical Word pages are unknown until rendering.");
            coverage.getLimitations().add("Explicit run strike/dstrike and tracked deletions are retained; inherited style strike requires manual review.");
            int unresolvedNumbering = 0, unresolvedSymbols = 0, structuralWarnings = 0;
            for (DocumentBlock block : blocks) {
                structuralWarnings += block.getWordWarnings().size();
                for (DocumentBlock.WordNumbering item : block.getWordNumbering())
                    if (!"resolved".equals(item.getResolutionStatus())) unresolvedNumbering++;
                for (DocumentBlock.WordSymbol item : block.getWordSymbols())
                    if (!"resolved".equals(item.getResolutionStatus())) unresolvedSymbols++;
            }
            if (unresolvedNumbering + unresolvedSymbols + structuralWarnings > 0) {
                coverage.setComplete(false);
                coverage.getLimitations().add("Unresolved Word numbering: " + unresolvedNumbering
                        + "; unresolved font symbols: " + unresolvedSymbols + "; structural warnings: " + structuralWarnings
                        + ". Original properties and locations are retained; unresolved values must not be treated as absent.");
            }
            if(probe.enabled())for(DocumentBlock observed:blocks)probe.event("docx_source_block",null,DocumentParseProbe.map("block",observed,
                    "blockJsonUtf8Sha256",DocumentParseProbe.sha256(JsonUtils.write(observed).getBytes(StandardCharsets.UTF_8)),
                    "physicalWordPage",null,"boundary","Exact original body/table/header/footer/note output; deleted and strike text retained independently"));
            return new ParsedDocument(joinBlocks(blocks), Collections.emptyList(), 0, false,
                    "DOCX 已解析 " + blocks.size() + " 个段落/表格/页眉页脚/脚注尾注位置；未推算页码", blocks, coverage);
        }
    }

    /** Bound actual inflation before POI materializes OOXML; ZIP declared sizes are untrusted. */
    private void preflightDocx(byte[] bytes) throws IOException {
        ConsenseProperties.Document cfg = props.getDocument();
        long entryLimit = positiveLimit(cfg.getMaxZipEntryBytes(), MAX_ZIP_ENTRY_BYTES);
        long xmlLimit = positiveLimit(cfg.getMaxZipXmlBytes(), MAX_ZIP_XML_BYTES);
        long totalLimit = positiveLimit(cfg.getMaxZipTotalBytes(), MAX_ZIP_TOTAL_BYTES);
        int countLimit = (int) positiveLimit(cfg.getMaxZipEntries(), MAX_ZIP_ENTRIES);
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++count > countLimit) {
                    throw new IOException("DOCX ZIP entry count exceeds " + countLimit);
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                boolean xml = name.endsWith(".xml") || name.endsWith(".rels");
                long expanded = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    expanded += read;
                    total += read;
                    if (expanded > entryLimit) {
                        throw new IOException("DOCX ZIP entry expanded bytes exceed " + entryLimit
                                + ": " + entry.getName());
                    }
                    if (xml && expanded > xmlLimit) {
                        throw new IOException("DOCX ZIP XML expanded bytes exceed " + xmlLimit
                                + ": " + entry.getName());
                    }
                    if (total > totalLimit) {
                        throw new IOException("DOCX ZIP total expanded bytes exceed " + totalLimit);
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static long positiveLimit(long configured, long ceiling) throws IOException {
        if (configured <= 0) throw new IOException("DOCX ZIP limits must be positive");
        return Math.min(configured, ceiling);
    }

    // ------------------------------------------------------------------ DOC

    private ParsedDocument parseDoc(byte[] bytes) throws Exception {
        String text;
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            text = normalize(extractor.getText());
        }
        return unpaginated(text, "doc", "DOC 已解析；未渲染页码", "Legacy DOC text has no rendered page or run-deletion mapping.");
    }

    // ------------------------------------------------------------------ MSG

    private ParsedDocument parseMsg(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (MAPIMessage message = new MAPIMessage(new ByteArrayInputStream(bytes))) {
            appendHeader(builder, "From", message.getDisplayFrom());
            appendHeader(builder, "To", message.getDisplayTo());
            appendHeader(builder, "Cc", message.getDisplayCC());
            appendHeader(builder, "Subject", message.getSubject());
            builder.append('\n');
            String body = message.getTextBody();
            if (!JsonUtils.isBlankText(body)) {
                builder.append(body.trim());
            } else {
                String html = message.getHtmlBody();
                builder.append(html == null ? "" : stripHtml(html));
            }
        }
        String text = normalize(builder.toString());
        return unpaginated(text, "email", "MSG 邮件解析完成", "Email body positions have no physical pages.");
    }

    // ------------------------------------------------------------------ EML

    private ParsedDocument parseEml(byte[] bytes) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(bytes));
        StringBuilder builder = new StringBuilder();
        appendHeader(builder, "From", message.getHeader("From", null));
        appendHeader(builder, "To", message.getHeader("To", null));
        appendHeader(builder, "Cc", message.getHeader("Cc", null));
        appendHeader(builder, "Subject", message.getHeader("Subject", null));
        appendHeader(builder, "Date", message.getHeader("Date", null));
        builder.append('\n');
        collectPart(message, builder);
        String text = normalize(builder.toString());
        return unpaginated(text, "email", "EML 邮件解析完成", "Email body positions have no physical pages.");
    }

    private void collectPart(javax.mail.Part part, StringBuilder builder) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            builder.append(content == null ? "" : content.toString()).append('\n');
            return;
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            builder.append(content == null ? "" : stripHtml(content.toString())).append('\n');
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                // 附件正文也纳入证据范围
                if (javax.mail.Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
                    builder.append("[附件] ").append(bodyPart.getFileName()).append('\n');
                }
                collectPart(bodyPart, builder);
            }
        }
    }

    // ------------------------------------------------------------------ TXT

    private ParsedDocument parsePlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = normalize(text);
        return unpaginated(text, "text", "纯文本解析完成", "Text positions have no physical pages.");
    }

    // -------------------------------------------------------------- helpers

    private ParsedDocument unpaginated(String text, String kind, String message, String limitation) {
        List<DocumentBlock> blocks = new ArrayList<>();
        String[] paragraphs = text.split("\\n{2,}");
        for (int i = 0; i < paragraphs.length; i++) {
            if (!JsonUtils.isBlankText(paragraphs[i])) {
                blocks.add(block(kind, "body/" + i, null, normalize(paragraphs[i]), kind));
            }
        }
        return new ParsedDocument(text, Collections.emptyList(), 0, false, message,
                blocks, unpaginatedCoverage(limitation));
    }

    private ParseCoverage unpaginatedCoverage(String limitation) {
        ParseCoverage coverage = new ParseCoverage();
        coverage.setComplete(true);
        coverage.getLimitations().add(limitation);
        return coverage;
    }

    private DocumentBlock block(String kind, String location, Integer page, String text, String source) {
        DocumentBlock block = new DocumentBlock();
        block.setId(location.replace('/', ':'));
        block.setKind(kind);
        block.setLocation(location);
        block.setPageNo(page);
        block.setText(normalize(text));
        block.setOriginalText(normalize(text));
        block.setSource(source);
        return block;
    }

    private DocumentBlock wordBlock(Node node, String kind, String location, DocxNumberingResolver numbering) {
        WordText text = new WordText();
        collectWordText(node, false, false, text, numbering, location);
        return wordBlock(text, kind, location);
    }

    private DocumentBlock wordBlock(WordText text, String kind, String location) {
        DocumentBlock block = block(kind, location, null, text.effective.toString(), "docx");
        block.setOriginalText(normalize(text.original.toString()));
        block.setDeletedText(normalize(text.deleted.toString()));
        block.setStrikeText(normalize(text.struck.toString()));
        block.setWordStructureVersion("docx-numbering-symbol-v1");
        block.setWordNumbering(text.numbering);
        block.setWordSymbols(text.symbols);
        block.setWordWarnings(text.warnings);
        block.setWordCells(text.cells);
        block.setWordNativeObjects(text.nativeObjects);
        return block;
    }

    private boolean hasContent(DocumentBlock block) {
        return !JsonUtils.isBlankText(block.getOriginalText()) || !block.getWordNumbering().isEmpty()
                || !block.getWordSymbols().isEmpty() || !block.getWordWarnings().isEmpty() || !block.getWordCells().isEmpty()
                || !block.getWordNativeObjects().isEmpty();
    }

    /** Traverse OOXML so deleted runs do not enter the effective contract text. */
    private void collectWordText(Node node, boolean deleted, boolean struck, WordText out,
                                 DocxNumberingResolver numbering, String location) {
        String name = node.getLocalName();
        boolean isDeleted = deleted || "del".equals(name) || "moveFrom".equals(name);
        boolean isStruck = struck;
        boolean math="http://schemas.openxmlformats.org/officeDocument/2006/math".equals(node.getNamespaceURI());
        if(math||("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(node.getNamespaceURI())
                && ("drawing".equals(name)||"pict".equals(name)||"object".equals(name)))) {
            ObjectNode nativeObject=JsonUtils.mapper().createObjectNode();
            nativeObject.put("kind",math?"math":name);nativeObject.put("location",location);
            nativeObject.put("status","native_ooxml_unrendered");nativeObject.put("semanticStatus","unknown");
            nativeObject.put("deleted",isDeleted);nativeObject.put("struck",isStruck);
            try { nativeObject.put("nativeXml",org.apache.xmlbeans.XmlObject.Factory.parse(node).xmlText()); }
            catch(Exception unavailable){nativeObject.put("nativeXmlStatus","unavailable_original_package_retained");}
            wordRelationshipRefs(node,nativeObject.putArray("relationshipRefs"));
            out.nativeObjects.add(nativeObject);out.warnings.add("Unrendered native Word "+nativeObject.path("kind").asText()+" at "+location+"; source semantics remain unknown.");
            if(math)return;
        }
        if ("tc".equals(name)) {
            WordText cell = new WordText(); int childIndex = 0;
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
                collectWordText(child, isDeleted, isStruck, cell, numbering, location + "/node/" + childIndex++);
            DocumentBlock.WordCell metadata = new DocumentBlock.WordCell();
            metadata.setLocation(location); metadata.setTableLocation(wordDomLocation(node.getParentNode().getParentNode()));
            metadata.setRowIndex(previousWordSiblings(node.getParentNode(), "tr"));
            metadata.setCellIndex(previousWordSiblings(node, "tc"));
            metadata.setColumnSpan(wordGridSpan(node)); metadata.setGridColumn(wordGridColumn(node));
            metadata.setVerticalMerge(wordVerticalMerge(node));
            metadata.setHorizontalMerge(wordMerge(node, "hMerge"));
            metadata.setGridSpanRaw(wordValue(wordChild(wordChild(node, "tcPr"), "gridSpan")));
            metadata.setGridBeforeRaw(wordValue(wordChild(wordChild(node.getParentNode(), "trPr"), "gridBefore")));
            metadata.setRowSpan("restart".equals(metadata.getVerticalMerge()) ? wordRowSpan(node)
                    : "continue".equals(metadata.getVerticalMerge()) ? null : 1);
            boolean unresolved = metadata.getColumnSpan() == null || metadata.getGridColumn() == null
                    || "unknown".equals(metadata.getVerticalMerge()) || !"none".equals(metadata.getHorizontalMerge());
            metadata.setTopologyStatus(unresolved ? "native_ooxml_topology_unresolved" : "native_ooxml_unrendered");
            if (unresolved) { metadata.getLimitations().add("Malformed or legacy merge topology retained without guessing rendered cell membership.");
                cell.warnings.add("Unresolved native Word table topology at " + location); }
            metadata.setText(normalize(cell.effective.toString())); metadata.setOriginalText(normalize(cell.original.toString()));
            metadata.setDeletedText(normalize(cell.deleted.toString())); metadata.setStrikeText(normalize(cell.struck.toString()));
            cell.cells.add(0, metadata); out.add(cell); return;
        }
        if ("txbxContent".equals(name)) {
            numbering = numbering.newStory();
        }
        if ("AlternateContent".equals(name)) {
            WordText alternative = new WordText();
            int alternativeIndex = 0;
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                collectWordText(child, isDeleted, isStruck, alternative, numbering.newStory(),
                        location + "/alternative/" + alternativeIndex++);
            }
            alternative.warnings.add("Unrendered OOXML AlternateContent branches at " + location);
            for (DocumentBlock.WordNumbering item : alternative.numbering) {
                item.setResolutionStatus("unresolved"); item.setReason("alternate_content_branch_not_selected");
            }
            if (!JsonUtils.isBlankText(alternative.effective.toString()))
                alternative.effective.insert(0, "[unresolved Word AlternateContent] ");
            out.add(alternative);
            return;
        }
        if ("p".equals(name)) {
            DocxNumberingResolver.NumberingInfo resolved = numbering.resolve(node);
            WordText paragraph = new WordText();
            int childIndex = 0;
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                collectWordText(child, isDeleted, isStruck, paragraph, numbering, location + "/node/" + childIndex++);
            }
            if (resolved != null && resolved.isNumbered()) {
                // Note parts are enumerated by storage order. Cross-note label order
                // cannot be asserted without following/rendering body references.
                boolean trusted = resolved.isResolved() && !location.startsWith("word-footnote/")
                        && !location.startsWith("word-endnote/");
                boolean active = !JsonUtils.isBlankText(paragraph.effective.toString());
                DocumentBlock.WordNumbering metadata = new DocumentBlock.WordNumbering();
                metadata.setLocation(location); metadata.setNumId(resolved.getNumId());
                metadata.setAbstractNumId(resolved.getAbstractNumId()); metadata.setLevel(resolved.getLevel());
                metadata.setFormat(resolved.getFormat()); metadata.setLevelText(resolved.getLevelText());
                metadata.setSuffix(resolved.getSuffix());
                metadata.setLabel(resolved.getLabel()); metadata.setSource(resolved.getSource());
                metadata.setValue(resolved.getValue()); metadata.setReason(trusted ? resolved.getReason()
                        : resolved.isResolved() ? "note_reference_order_not_verified" : resolved.getReason());
                metadata.setResolutionStatus(trusted ? "resolved" : "unresolved");
                metadata.setAppliedToEffectiveText(active); paragraph.numbering.add(metadata);
                String label = (trusted ? resolved.getLabel() : "[unresolved Word numbering]")
                        + (trusted && "nothing".equals(resolved.getSuffix()) ? "" : " ");
                if (!JsonUtils.isBlankText(paragraph.original.toString())) paragraph.original.insert(0, label);
                if (active) paragraph.effective.insert(0, label);
                else {
                    if (!JsonUtils.isBlankText(paragraph.deleted.toString())) paragraph.deleted.insert(0, label);
                    if (!JsonUtils.isBlankText(paragraph.struck.toString())) paragraph.struck.insert(0, label);
                }
            }
            appendWordText(paragraph, "\n", isDeleted, isStruck);
            out.add(paragraph);
            return;
        }
        if ("r".equals(name)) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!"rPr".equals(child.getLocalName())) continue;
                for (Node property = child.getFirstChild(); property != null; property = property.getNextSibling()) {
                    if (("strike".equals(property.getLocalName()) || "dstrike".equals(property.getLocalName()))
                            && onOff(property)) isStruck = true;
                }
            }
        }
        if ("t".equals(name) || "delText".equals(name)) {
            appendWordText(out, nodeText(node), isDeleted || "delText".equals(name), isStruck);
            return;
        }
        if ("sym".equals(name)) {
            WordSymbolDecoder.Symbol decoded = WordSymbolDecoder.decode(node);
            DocumentBlock.WordSymbol metadata = new DocumentBlock.WordSymbol();
            metadata.setLocation(location); metadata.setFont(decoded.getFont()); metadata.setHexCode(decoded.getHexCode());
            metadata.setDecodedText(decoded.getDecodedText()); metadata.setResolutionStatus(decoded.getResolutionStatus());
            metadata.setMappingSource(decoded.getMappingSource()); metadata.setDeleted(isDeleted); metadata.setStruck(isStruck);
            out.symbols.add(metadata);
            appendWordText(out, decoded.isResolved() ? decoded.getDecodedText() : "[unresolved Word symbol]", isDeleted, isStruck);
            return;
        }
        if ("tab".equals(name)) appendWordText(out, " ", isDeleted, isStruck);
        if ("br".equals(name) || "cr".equals(name)) appendWordText(out, "\n", isDeleted, isStruck);
        int childIndex = 0;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectWordText(child, isDeleted, isStruck, out, numbering, location + "/node/" + childIndex++);
        }
    }

    private void appendWordText(WordText out, String text, boolean deleted, boolean struck) {
        out.original.append(text);
        if (deleted) out.deleted.append(text);
        if (struck) out.struck.append(text);
        if (!deleted && !struck) out.effective.append(text);
    }

    private static void wordRelationshipRefs(Node node,com.fasterxml.jackson.databind.node.ArrayNode refs) {
        if(node.getAttributes()!=null)for(int i=0;i<node.getAttributes().getLength();i++){
            Node attribute=node.getAttributes().item(i);
            if("http://schemas.openxmlformats.org/officeDocument/2006/relationships".equals(attribute.getNamespaceURI())){
                ObjectNode ref=refs.addObject();ref.put("relationshipId",attribute.getNodeValue());
                ref.put("attribute",attribute.getLocalName());ref.put("status","unresolved_original_package_retained");
            }
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())wordRelationshipRefs(child,refs);
    }

    private static void bindWordNativeObjects(DocumentBlock block,org.apache.poi.openxml4j.opc.PackagePart part) {
        for(JsonNode raw:block.getWordNativeObjects()){
            ObjectNode object=(ObjectNode)raw;object.put("sourcePart",part.getPartName().getName());
            for(JsonNode rawRef:object.path("relationshipRefs")){
                ObjectNode ref=(ObjectNode)rawRef;
                try {
                    org.apache.poi.openxml4j.opc.PackageRelationship relation=part.getRelationship(ref.path("relationshipId").asText());
                    if(relation==null)continue;
                    ref.put("targetUri",relation.getTargetURI().toString());ref.put("relationshipType",relation.getRelationshipType());
                    if(relation.getTargetMode()==org.apache.poi.openxml4j.opc.TargetMode.EXTERNAL){ref.put("status","external_reference_not_fetched");continue;}
                    org.apache.poi.openxml4j.opc.PackagePart asset=part.getRelatedPart(relation);
                    java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");long size=0;
                    try(java.io.InputStream input=asset.getInputStream()){byte[] buffer=new byte[8192];int read;
                        while((read=input.read(buffer))!=-1){hash.update(buffer,0,read);size+=read;}}
                    StringBuilder hex=new StringBuilder();for(byte value:hash.digest())hex.append(String.format("%02x",value&255));
                    ref.put("targetPart",asset.getPartName().getName());ref.put("mediaType",asset.getContentType());
                    ref.put("sha256",hex.toString());ref.put("byteCount",size);ref.put("status","original_package_asset_bytes_bound");
                }catch(Exception unavailable){ref.put("status","unresolved_original_package_retained");}
            }
        }
    }

    private static Node wordChild(Node node, String name) {
        if (node == null) return null;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            if (name.equals(child.getLocalName())) return child;
        return null;
    }
    private static String wordValue(Node node) {
        if (node == null || node.getAttributes() == null) return null;
        Node val = node.getAttributes().getNamedItemNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val");
        return val == null ? null : val.getNodeValue();
    }
    private static Integer wordPositiveValue(Node node, int fallback) {
        if (node == null) return fallback;
        try { int value = Integer.parseInt(wordValue(node)); return value > 0 ? value : null; }
        catch (RuntimeException malformed) { return null; }
    }
    private static Integer wordGridSpan(Node cell) { return wordPositiveValue(wordChild(wordChild(cell, "tcPr"), "gridSpan"), 1); }
    private static String wordVerticalMerge(Node cell) { return wordMerge(cell, "vMerge"); }
    private static String wordMerge(Node cell, String name) {
        Node merge = wordChild(wordChild(cell, "tcPr"), name);
        if (merge == null) return "none";
        String value = wordValue(merge);
        return value == null || "continue".equals(value) ? "continue" : "restart".equals(value) ? "restart" : "unknown";
    }
    private static int previousWordSiblings(Node node, String name) {
        int count = 0;
        for (Node previous = node.getPreviousSibling(); previous != null; previous = previous.getPreviousSibling())
            if (name.equals(previous.getLocalName())) count++;
        return count;
    }
    private static Integer wordGridColumn(Node cell) {
        int column = 0;
        Node before = wordChild(wordChild(cell.getParentNode(), "trPr"), "gridBefore");
        if (before != null) try { column = Integer.parseInt(wordValue(before)); if (column < 0) return null; }
        catch (RuntimeException malformed) { return null; }
        for (Node previous = cell.getPreviousSibling(); previous != null; previous = previous.getPreviousSibling())
            if ("tc".equals(previous.getLocalName())) { Integer span = wordGridSpan(previous); if (span == null) return null; column += span; }
        return column;
    }
    private static Integer wordRowSpan(Node cell) {
        int span = 1; Integer column = wordGridColumn(cell), width = wordGridSpan(cell);
        if (column == null || width == null) return null;
        for (Node row = cell.getParentNode().getNextSibling(); row != null; row = row.getNextSibling()) {
            if (!"tr".equals(row.getLocalName())) continue;
            Node continuation = null;
            for (Node candidate = row.getFirstChild(); candidate != null; candidate = candidate.getNextSibling())
                if ("tc".equals(candidate.getLocalName()) && column.equals(wordGridColumn(candidate))) { continuation = candidate; break; }
            if (continuation == null || !width.equals(wordGridSpan(continuation)) || !"continue".equals(wordVerticalMerge(continuation))) break;
            span++;
        }
        return span;
    }
    private static String wordDomLocation(Node node) {
        if (node == null || node.getNodeType() == Node.DOCUMENT_NODE) return "word";
        return wordDomLocation(node.getParentNode()) + "/" + node.getLocalName() + "[" + previousWordSiblings(node, node.getLocalName()) + "]";
    }

    private static String nativeWordPath(Node node,Map<Node,String> paths){
        if(node==null || node.getNodeType()!=Node.ELEMENT_NODE)return null;
        if(!paths.containsKey(node)){
            Node root=node;while(root.getParentNode()!=null && root.getParentNode().getNodeType()==Node.ELEMENT_NODE)root=root.getParentNode();
            cacheWordPaths(root,"/"+wordExpandedName(root)+"[1]",paths);
        }
        return paths.get(node);
    }
    private static void cacheWordPaths(Node node,String path,Map<Node,String> paths){
        paths.put(node,path);Map<String,Integer> counts=new HashMap<>();
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()==Node.ELEMENT_NODE){
            String name=wordExpandedName(child);int ordinal=counts.getOrDefault(name,0)+1;counts.put(name,ordinal);
            cacheWordPaths(child,path+"/"+name+"["+ordinal+"]",paths);
        }
    }
    private static String wordExpandedName(Node node){return "{"+(node.getNamespaceURI()==null?"":node.getNamespaceURI())+"}"+node.getLocalName();}

    private String nodeText(Node node) {
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            } else {
                text.append(nodeText(child));
            }
        }
        return text.toString();
    }

    private boolean onOff(Node property) {
        NamedNodeMap attrs = property.getAttributes();
        Node value = attrs == null ? null : attrs.getNamedItemNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val");
        String text = value == null ? "true" : value.getNodeValue();
        return !("0".equals(text) || "false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text));
    }

    private String joinBlocks(List<DocumentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (!JsonUtils.isBlankText(block.getText())) text.append(block.getText()).append("\n\n");
        }
        return normalize(text.toString());
    }

    private static double[] normalizedBox(double[] box, double width, double height) {
        if (box == null || box.length != 4 || width <= 0 || height <= 0) return null;
        for (double value : box) if (!Double.isFinite(value)) return null;
        double x = Math.max(0, Math.min(1, box[0] / width));
        double y = Math.max(0, Math.min(1, box[1] / height));
        return new double[]{x, y, Math.max(0, Math.min(1 - x, box[2] / width)),
                Math.max(0, Math.min(1 - y, box[3] / height))};
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) return "unknown failure";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static final class WordText {
        private final StringBuilder effective = new StringBuilder();
        private final StringBuilder original = new StringBuilder();
        private final StringBuilder deleted = new StringBuilder();
        private final StringBuilder struck = new StringBuilder();
        private final List<DocumentBlock.WordNumbering> numbering = new ArrayList<>();
        private final List<DocumentBlock.WordSymbol> symbols = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<DocumentBlock.WordCell> cells = new ArrayList<>();
        private final List<JsonNode> nativeObjects = new ArrayList<>();
        private void add(WordText other) {
            effective.append(other.effective); original.append(other.original);
            deleted.append(other.deleted); struck.append(other.struck);
            numbering.addAll(other.numbering); symbols.addAll(other.symbols);
            warnings.addAll(other.warnings);
            cells.addAll(other.cells);
            nativeObjects.addAll(other.nativeObjects);
        }
    }

    private final class PositionedStripper extends PDFTextStripper {
        private final List<DocumentBlock> blocks = new ArrayList<>();
        private final int pageNo;
        private final int pageRotation;
        private final PDPage page;
        private final PdfPageEvidence evidence;
        private final PdfNativeSupport.PaintCollector paintCollector;
        private final Map<TextPosition, PdfPageEvidence.PdfGlyph> glyphByPosition = new IdentityHashMap<>();

        private PositionedStripper(int pageNo, PDPage page, PdfPageEvidence evidence) throws IOException {
            this.pageNo = pageNo;
            this.page = page; this.evidence = evidence;this.paintCollector=new PdfNativeSupport.PaintCollector(page,evidence,JsonUtils.mapper());
            pageRotation = page.getRotation();
            setSortByPosition(true);
        }

        @Override protected void processOperator(Operator operator,List<COSBase> operands) throws IOException {
            paintCollector.accept(operator,operands,getGraphicsState());
            if("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName) {
                COSName name=(COSName)operands.get(0);PDXObject object=getResources()==null?null:getResources().getXObject(name);
                if(object instanceof PDImageXObject) {
                    PDImageXObject image=(PDImageXObject)object;recordImagePlacement(name.getName(),image.getWidth(),image.getHeight());
                } else if(object instanceof PDFormXObject) {
                    PdfNativeSupport.PaintCollector.State outer=paintCollector.enterChild();
                    try{super.processOperator(operator,operands);}finally{paintCollector.exitChild(outer);}return;
                }
            } else if("BI".equals(operator.getName())) {
                org.apache.pdfbox.cos.COSDictionary parameters=operator.getImageParameters();
                recordImagePlacement("inline-image",parameters==null?0:parameters.getInt(COSName.WIDTH,parameters.getInt(COSName.getPDFName("W"),0)),
                        parameters==null?0:parameters.getInt(COSName.HEIGHT,parameters.getInt(COSName.getPDFName("H"),0)));
            }
            super.processOperator(operator,operands);
        }
        private void recordImagePlacement(String resource,int intrinsicWidth,int intrinsicHeight) {
            org.apache.pdfbox.util.Matrix matrix=getGraphicsState().getCurrentTransformationMatrix();
            java.awt.geom.Point2D.Float[] corners={matrix.transformPoint(0,0),matrix.transformPoint(1,0),matrix.transformPoint(1,1),matrix.transformPoint(0,1)};
            double left=Double.POSITIVE_INFINITY,bottom=Double.POSITIVE_INFINITY,right=Double.NEGATIVE_INFINITY,top=Double.NEGATIVE_INFINITY;
            double[][] raw=new double[4][2];for(int index=0;index<corners.length;index++) {
                raw[index]=new double[]{corners[index].x,corners[index].y};left=Math.min(left,corners[index].x);right=Math.max(right,corners[index].x);
                bottom=Math.min(bottom,corners[index].y);top=Math.max(top,corners[index].y);
            }
            PDRectangle crop=page.getCropBox();double[] normalized=PdfNativeSupport.rotate(new double[]{(left-crop.getLowerLeftX())/crop.getWidth(),
                    (crop.getUpperRightY()-top)/crop.getHeight(),(right-left)/crop.getWidth(),(top-bottom)/crop.getHeight()},page.getRotation());
            boolean finite=validBox(crop);for(double value:normalized)finite&=Double.isFinite(value);
            boolean intersects=right>crop.getLowerLeftX() && left<crop.getUpperRightX() && top>crop.getLowerLeftY() && bottom<crop.getUpperRightY();
            ObjectNode region=JsonUtils.mapper().createObjectNode();region.put("id","pdf-page/"+pageNo+"/placed-image/"+evidence.getPlacedImageRegions().size());
            region.put("resourceName",resource);region.put("intrinsicWidth",intrinsicWidth);region.put("intrinsicHeight",intrinsicHeight);
            region.set("polygonOriginalPdf",JsonUtils.mapper().valueToTree(raw));region.set("bbox",finite?JsonUtils.mapper().valueToTree(normalized):JsonUtils.mapper().nullNode());
            region.set("transformationMatrix",JsonUtils.mapper().valueToTree(PdfNativeSupport.matrix(matrix)));region.put("coordinateSpace","normalized_rendered_crop_top_left");
            region.put("geometryStatus",finite?"observed_without_clipping":"invalid_unknown");
            region.put("intersectsCrop",intersects);region.put("requiresVisualObservation",!finite || intersects && getGraphicsState().getNonStrokeAlphaConstant()>0);
            region.put("coverageStatus","draw_operator_observed_not_pixel_visibility_or_content_verified");evidence.getPlacedImageRegions().add(region);
        }

        @Override protected void processTextPosition(TextPosition position) {
            PdfPageEvidence.PdfGlyph glyph = PdfNativeSupport.glyph(position, getGraphicsState(), page,
                    "pdf-page/" + pageNo + "/glyph/" + evidence.getGlyphs().size());
            evidence.getGlyphs().add(glyph); glyphByPosition.put(position, glyph);
            // Invisible historical OCR remains raw evidence and cannot satisfy native coverage.
            if (glyph.isEligibleForNativeText()) super.processTextPosition(position);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (!JsonUtils.isBlankText(text)) {
                DocumentBlock block = block("pdf_line", "pdf-page/" + pageNo + "/line/" + blocks.size(),
                        pageNo, text, "native");
                block.setNativeVisibilityStatus("operator_visible_unverified");
                for (TextPosition position : positions) {
                    PdfPageEvidence.PdfGlyph glyph = glyphByPosition.get(position);
                    if (glyph != null) block.getPdfGlyphs().add(glyph);
                }
                if (!positions.isEmpty()) {
                    double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
                    double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
                    for (TextPosition position : positions) {
                        // Direction-adjusted text coordinates must be mapped back to the rendered page.
                        int direction = Math.round(position.getDir());
                        boolean quarterTurn = direction % 180 != 0;
                        double frameWidth = quarterTurn ? position.getPageHeight() : position.getPageWidth();
                        double frameHeight = quarterTurn ? position.getPageWidth() : position.getPageHeight();
                        double[] glyph = normalizedBox(new double[]{position.getXDirAdj(),
                                position.getYDirAdj() - position.getHeightDir(),
                                position.getWidthDirAdj(), position.getHeightDir()}, frameWidth, frameHeight);
                        if (glyph == null) continue;
                        glyph = rotateBox(glyph, pageRotation - direction);
                        x = Math.min(x, glyph[0]);
                        y = Math.min(y, glyph[1]);
                        right = Math.max(right, glyph[0] + glyph[2]);
                        bottom = Math.max(bottom, glyph[1] + glyph[3]);
                    }
                    block.setBbox(normalizedBox(new double[]{x, y, right - x, bottom - y}, 1, 1));
                }
                blocks.add(block);
            }
            super.writeString(text, positions);
        }
    }

    private static double[] rotateBox(double[] b, int rotation) {
        switch (((rotation % 360) + 360) % 360) {
            case 90: return new double[]{1 - b[1] - b[3], b[0], b[3], b[2]};
            case 180: return new double[]{1 - b[0] - b[2], 1 - b[1] - b[3], b[2], b[3]};
            case 270: return new double[]{b[1], 1 - b[0] - b[2], b[3], b[2]};
            default: return b;
        }
    }

    private void appendHeader(StringBuilder builder, String label, String value) {
        if (!JsonUtils.isBlankText(value)) {
            builder.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("[ \\t]+", " ");
    }

    private String joinPages(List<PageText> pages) {
        StringBuilder builder = new StringBuilder();
        for (PageText page : pages) {
            if (JsonUtils.isBlankText(page.getText())) {
                continue;
            }
            builder.append("--- P").append(page.getPageNo()).append(" ---\n")
                    .append(page.getText()).append("\n\n");
        }
        return normalize(builder.toString());
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------- results

    /** 解析结果（构造器签名与原 record 一致） */
    public static final class ParsedDocument {

        private final String text;
        private final List<PageText> pages;
        private final int pageCount;
        private final boolean ocrUsed;
        private final String message;
        private final List<DocumentBlock> blocks;
        private final ParseCoverage coverage;
        private final String sourceSha256;
        private final List<PdfPageEvidence> nativePdfEvidence;
        private boolean observationOnly;
        private String observationFormat, observationConfig;
        private JsonNode routingObservation;
        private final Map<Integer, OcrClient.OcrResult> observedOcr = new HashMap<>();
        private final Map<Integer, String> observedImageHashes = new HashMap<>();

        public ParsedDocument(String text, List<PageText> pages, int pageCount,
                              boolean ocrUsed, String message) {
            this(text, pages, pageCount, ocrUsed, message, Collections.emptyList(), legacyCoverage(pageCount));
        }

        public ParsedDocument(String text, List<PageText> pages, int pageCount,
                              boolean ocrUsed, String message, List<DocumentBlock> blocks, ParseCoverage coverage) {
            this(text, pages, pageCount, ocrUsed, message, blocks, coverage, null, Collections.emptyList());
        }

        public ParsedDocument(String text, List<PageText> pages, int pageCount, boolean ocrUsed, String message,
                              List<DocumentBlock> blocks, ParseCoverage coverage, String sourceSha256,
                              List<PdfPageEvidence> nativePdfEvidence) {
            this.text = text;
            this.pages = pages;
            this.pageCount = pageCount;
            this.ocrUsed = ocrUsed;
            this.message = message;
            this.blocks = blocks;
            this.coverage = coverage;
            this.sourceSha256 = sourceSha256;
            this.nativePdfEvidence = nativePdfEvidence;
            markUnverifiedOcrQuality();
            if (JsonUtils.isBlankText(text) && !allPagesConfirmedBlank()) this.coverage.setComplete(false);
        }

        private void markUnverifiedOcrQuality() {
            // Preserve extracted text and physical completion. Page identities come only from
            // actual OCR page declarations, never from an OCR flag, confidence or source role.
            if (coverage.getNeedsReviewPages() == null) coverage.setNeedsReviewPages(new ArrayList<>());
            boolean declaredOcrPage = false;
            boolean invalidOcrPageIdentity = false;
            if (pages != null) for (PageText page : pages) {
                if (page != null && "ocr".equals(page.getStatus())) {
                    declaredOcrPage = true;
                    if (page.getPageNo() <= 0 || (pageCount > 0 && page.getPageNo() > pageCount)) {
                        invalidOcrPageIdentity = true;
                    } else if (!coverage.getNeedsReviewPages().contains(page.getPageNo())) {
                        coverage.getNeedsReviewPages().add(page.getPageNo());
                    }
                }
            }
            if (ocrUsed || coverage.getOcrPages() > 0 || declaredOcrPage) {
                coverage.setOcrQualityStatus("needs_review");
                coverage.setOcrQualityPageScopeUnknown(coverage.getNeedsReviewPages().isEmpty()
                        || coverage.getOcrPages() > coverage.getNeedsReviewPages().size() || invalidOcrPageIdentity);
                String limitation = "OCR text quality is independently unverified; original-page review is required for text, footnotes, numbering and blank-field relationships. Physical extraction completion is not quality confirmation.";
                if (!coverage.getLimitations().contains(limitation)) coverage.getLimitations().add(limitation);
            }
        }

        private static ParseCoverage legacyCoverage(int pageCount) {
            ParseCoverage coverage = new ParseCoverage();
            coverage.setTotalPages(pageCount);
            coverage.setParsedPages(pageCount);
            coverage.setComplete(true);
            return coverage;
        }

        public String getText() {
            return text;
        }

        public List<PageText> getPages() {
            return pages;
        }

        public int getPageCount() {
            return pageCount;
        }

        public boolean isOcrUsed() {
            return ocrUsed;
        }

        public String getMessage() {
            return message;
        }

        public List<DocumentBlock> getBlocks() { return blocks; }

        public ParseCoverage getCoverage() { return coverage; }
        public String getSourceSha256() { return sourceSha256; }
        public List<PdfPageEvidence> getNativePdfEvidence() { return nativePdfEvidence; }

        public JsonNode getRoutingObservation() { return routingObservation == null ? null : routingObservation.deepCopy(); }
        public String getParseStatus() {
            if (observationOnly) return "IDENTITY_OBSERVED";
            if (JsonUtils.isBlankText(text) && !allPagesConfirmedBlank()) {
                for (DocumentBlock block : blocks) if (block.getVisualRegion() != null || block.getNativeTableCandidate()!=null || block.getParserPageGroup()!=null) return "PARTIAL";
                return "FAILED";
            }
            return coverage.isComplete() && !"needs_review".equals(coverage.getOcrQualityStatus())
                    && coverage.getNeedsReviewPages().isEmpty() ? "PARSED" : "PARTIAL";
        }

        private boolean allPagesConfirmedBlank() {
            return coverage.isComplete() && coverage.getTotalPages() > 0
                    && coverage.getParsedPages() == coverage.getTotalPages()
                    && coverage.getBlankPages().size() == coverage.getTotalPages();
        }
    }

    public static final class PageText {

        private final int pageNo;
        private final String text;
        private final String status;

        public PageText(int pageNo, String text) {
            this(pageNo, text, "native");
        }

        public PageText(int pageNo, String text, String status) {
            this.pageNo = pageNo;
            this.text = text;
            this.status = status;
        }

        public int getPageNo() {
            return pageNo;
        }

        public String getText() {
            return text;
        }

        public String getStatus() { return status; }
    }
}
