package com.consense.document;

import lombok.Data;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A source location, not an inferred Word page. PDF boxes use top-left normalized coordinates. */
@Data
public class DocumentBlock {
    private String id;
    private String kind;
    private String location;
    private Integer pageNo;
    private String text = "";
    private String originalText = "";
    private String deletedText = "";
    private String strikeText = "";
    private List<String> cells = new ArrayList<>();
    /** x, y, width, height, each relative to the physical page/image dimensions. */
    private double[] bbox;
    private Double confidence;
    private String source;
    private String paragraphStyle;
    /** Resolved OOXML style name; style IDs are often numeric and carry no semantics. */
    private String paragraphStyleName;
    /** Derivative parser identity; the original DOCX byte hash is recorded separately. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String wordStructureVersion;
    /** Original package-part and namespace path; no rendered Word page is inferred. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode sourcePosition;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<WordNumbering> wordNumbering = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<WordSymbol> wordSymbols = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> wordWarnings = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<WordCell> wordCells = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<JsonNode> wordNativeObjects = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<JsonNode> wordTextSpans = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<JsonNode> wordFields = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<PdfPageEvidence.PdfGlyph> pdfGlyphs = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PdfPageEvidence.PdfFormField pdfFormField;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String nativeVisibilityStatus;
    /** Provider observations remain candidates; raw text, topology and unknowns are not generated facts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode visualRegion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode visualFrame;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode visualProvenance;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode readingContext;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode nativeTableCandidate;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode parserPageGroup;
    /** Complete conflicting groups remain observable; they cannot become effective quote or embedding text. */
    @JsonIgnore public String getEffectiveText() { return "ocr_candidate".equals(source) || "native_table_candidate".equals(source)
            || "parser_page_group_candidate".equals(source) ? "" : text; }

    /** Native OOXML topology. Physical layout and cell images require rendering separately. */
    @Data public static class WordCell {
        private String location, tableLocation, verticalMerge, horizontalMerge, topologyStatus;
        private String gridSpanRaw, gridBeforeRaw;
        private Integer rowIndex, cellIndex, gridColumn, columnSpan, rowSpan;
        private String text, originalText, deletedText, strikeText;
        /** Exact original physical cell and owning native row/table; no rendered geometry. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private JsonNode sourcePosition;
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<JsonNode> wordTextSpans = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<JsonNode> wordFields = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
    }

    @Data public static class WordNumbering {
        private String location, numId, abstractNumId, format, levelText, label, suffix, resolutionStatus, reason, source;
        private Integer level;
        private Long value;
        private boolean appliedToEffectiveText;
        /** Literal glyphs in numbering.xml, distinct from body w:sym occurrences. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<WordSymbol> fontSymbols;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, String> fontAttributes;
    }
    @Data public static class WordSymbol {
        private String location, font, hexCode, decodedText, resolutionStatus, mappingSource;
        private boolean deleted, struck;
    }
}
