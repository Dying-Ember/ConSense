package com.consense.ocr;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;

import java.util.List;
import java.util.Map;

/**
 * OCR 抽象：当前接本机 PaddleOCR / PP-Structure 服务。
 */
public interface OcrClient {

    /** 识别单张图片（PNG/JPEG 字节） */
    OcrResult recognize(byte[] imageBytes);
    default OcrResult recognize(byte[] imageBytes, Map<String,Object> sourceContext) { return recognize(imageBytes); }

    /** A legacy text-only client has the same observation task; richer clients must separate identity from detail work. */
    default OcrResult observeIdentity(byte[] imageBytes) { return recognize(imageBytes); }
    default OcrResult observeIdentity(byte[] imageBytes, Map<String,Object> sourceContext) { return observeIdentity(imageBytes); }
    default boolean canReuseIdentityObservation(OcrResult observation) { return true; }
    /** Optional CPU source observation; legacy clients cannot claim this task executed. */
    default JsonNode observeNativePdf(byte[] originalPdf, Map<String,Object> nativeContext) { return null; }

    /** 服务是否可达 */
    boolean available();

    /** OCR 识别结果（Java 8 无 record，改为不可变类，构造器签名与原 record 一致） */
    final class OcrResult {

        private final String text;
        private final double confidence;
        private final List<OcrLine> lines;
        private final JsonNode regions, frame, provenance, coverage, parserEvidence,wholePageGroups;

        public OcrResult(String text, double confidence, List<OcrLine> lines) {
            this(text, confidence, lines, null, null, null, null);
        }

        public OcrResult(String text, double confidence, List<OcrLine> lines,
                         JsonNode regions, JsonNode frame, JsonNode provenance, JsonNode coverage) {
            this(text, confidence, lines, regions, frame, provenance, coverage, null);
        }
        public OcrResult(String text, double confidence, List<OcrLine> lines,
                         JsonNode regions, JsonNode frame, JsonNode provenance, JsonNode coverage, JsonNode parserEvidence) {
            this(text,confidence,lines,regions,frame,provenance,coverage,parserEvidence,null);
        }
        public OcrResult(String text, double confidence, List<OcrLine> lines,
                         JsonNode regions, JsonNode frame, JsonNode provenance, JsonNode coverage, JsonNode parserEvidence,JsonNode wholePageGroups) {
            this.text = text == null ? "" : text;
            this.confidence = confidence;
            this.lines = lines == null ? Collections.emptyList() : lines;
            if (regions != null && !regions.isArray()) throw new IllegalArgumentException("OCR regions must be an array");
            this.regions = regions == null ? JsonUtils.mapper().createArrayNode() : regions.deepCopy();
            this.frame = frame == null || frame.isNull() ? null : frame.deepCopy();
            this.provenance = provenance == null || provenance.isNull() ? null : provenance.deepCopy();
            this.coverage = coverage == null || coverage.isNull() ? null : coverage.deepCopy();
            this.parserEvidence = parserEvidence == null ? null : parserEvidence.deepCopy();
            if(wholePageGroups!=null && !wholePageGroups.isArray())throw new IllegalArgumentException("OCR whole page groups must be an array");
            this.wholePageGroups=wholePageGroups==null?JsonUtils.mapper().createArrayNode():wholePageGroups.deepCopy();
        }
        public JsonNode getWholePageGroups(){return wholePageGroups.deepCopy();}

        public JsonNode getParserEvidence() { return parserEvidence == null ? null : parserEvidence.deepCopy(); }
        public JsonNode getRegions() { return regions.deepCopy(); }
        public JsonNode getFrame() { return frame == null ? null : frame.deepCopy(); }
        public JsonNode getProvenance() { return provenance == null ? null : provenance.deepCopy(); }
        public JsonNode getCoverage() { return coverage == null ? null : coverage.deepCopy(); }

        public String getText() {
            return text;
        }

        public double getConfidence() {
            return confidence;
        }

        public List<OcrLine> getLines() {
            return lines;
        }

        public boolean isEmpty() {
            return JsonUtils.isBlankText(text) && regions.size() == 0 && wholePageGroups.size()==0;
        }
    }

    final class OcrLine {

        private final String text;
        private final double confidence;
        /** 文字在原图坐标系内的 bbox：{x, y, w, h}（四点取最小外接矩形）；可能为 null。 */
        private final double[] bbox;
        private final JsonNode polygon;

        public OcrLine(String text, double confidence) {
            this(text, confidence, null);
        }

        public OcrLine(String text, double confidence, double[] bbox) {
            this(text, confidence, bbox, null);
        }
        public OcrLine(String text, double confidence, double[] bbox, JsonNode polygon) {
            this.text = text;
            this.confidence = confidence;
            this.bbox = bbox;
            this.polygon = polygon == null ? null : polygon.deepCopy();
        }
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public JsonNode getPolygon() { return polygon == null ? null : polygon.deepCopy(); }

        public String getText() {
            return text;
        }

        public double getConfidence() {
            return confidence;
        }

        public double[] getBbox() {
            return bbox;
        }
    }
}
