package com.consense.document;

import lombok.Data;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Original PDF operators and widgets, never a claim of visually verified text. */
@Data
public class PdfPageEvidence {
    private String schemaVersion = "pdf-native-evidence-v1";
    private int pageNo, rotation;
    private double[] cropBox;
    private double[] mediaBox;
    private double userUnit;
    private String coordinateSpace = "normalized_rendered_crop_top_left";
    private List<PdfGlyph> glyphs = new ArrayList<>();
    private List<PdfFormField> formFields = new ArrayList<>();
    private List<DocumentBlock> nativeBlocks = new ArrayList<>();
    private List<JsonNode> placedImageRegions = new ArrayList<>();
    private List<JsonNode> vectorPathOperators = new ArrayList<>();
    private List<JsonNode> paintedVectorRegions = new ArrayList<>();
    private List<String> limitations = new ArrayList<>();

    @Data public static class PdfGlyph {
        private String id, unicode, fontName, visibilityStatus;
        /** Optional reader projection; unicode and original character codes stay literal. */
        private TextResolution textResolution;
        private String coordinateSpace = "normalized_rendered_crop_top_left";
        /** A ligature/cluster may represent multiple Unicode characters with one glyph box. */
        private int[] characterCodes;
        private int renderingMode;
        private double fillAlpha, strokeAlpha;
        private double[] bbox, textMatrix;
        /** Actual PDFBox operator/text-state values; none establish raster visibility. */
        private double[] currentTransformationMatrix, pdfboxPosition, clippingBoundsPdf;
        private double[] fillColorComponents, strokeColorComponents;
        private String fillColorSpace, strokeColorSpace, fontSubtype;
        private Boolean fontEmbedded;
        private double fontSize, fontSizeInPt, xScale, yScale, widthOfSpace, direction;
        private double textStateFontSize, characterSpacing, wordSpacing, horizontalScaling, leading, rise, lineWidth;
        private String effectiveVisibilityStatus = "unknown_not_raster_verified";
        private List<String> limitations = new ArrayList<>();
        private boolean eligibleForNativeText;

        /** One reader channel for ordinary text and normal/canonical source grouping. */
        public String readerText() {
            return textResolution != null && textResolution.matches(this) ? textResolution.getDecodedText() : unicode;
        }
    }

    @Data public static class TextResolution {
        public static final String SCHEMA = "consense.pdf-font-text-resolution.v1";
        public static final String VERSION = "embedded-adobe-symbol-pua-v1";
        public static final String RESOLVED = "resolved_embedded_symbol_selector";
        public static final String MAPPING_SOURCE = "https://www.unicode.org/Public/MAPPINGS/VENDORS/ADOBE/symbol.txt";
        private String schema = SCHEMA, decoderVersion = VERSION;
        private String rawUnicode, fontName, selector, decodedText = "", resolutionStatus, mappingSource;
        private int[] sourceCharacterCodes;
        private String fontProgramSha256, toUnicodeSha256;
        private Integer sourceGlyphId, selectorGlyphId, cmapPlatformId, cmapEncodingId;

        public static boolean symbolFont(String name) {
            return name != null && "Symbol".equalsIgnoreCase(name.replaceFirst("^[A-Z]{6}\\+", ""));
        }

        /** Only source-witnessed, unambiguous Adobe Symbol selectors are admitted. */
        public static String symbolText(String raw) {
            if ("\uF046".equals(raw)) return "\u03A6";
            if ("\uF03E".equals(raw)) return ">";
            if ("\uF0A3".equals(raw)) return "\u2264";
            // F06D is ambiguous in Adobe's mapping (U+00B5 and U+03BC).
            return null;
        }

        private boolean matches(PdfGlyph glyph) {
            String expected = symbolText(glyph.getUnicode());
            return SCHEMA.equals(schema) && VERSION.equals(decoderVersion) && RESOLVED.equals(resolutionStatus)
                    && expected != null && expected.equals(decodedText) && MAPPING_SOURCE.equals(mappingSource)
                    && java.util.Objects.equals(rawUnicode, glyph.getUnicode())
                    && java.util.Objects.equals(fontName, glyph.getFontName()) && symbolFont(fontName)
                    && "Type0".equals(glyph.getFontSubtype()) && Boolean.TRUE.equals(glyph.getFontEmbedded())
                    && sourceCharacterCodes != null && sourceCharacterCodes.length == 1
                    && sourceCharacterCodes[0] >= 0 && sourceCharacterCodes[0] <= 65535
                    && java.util.Arrays.equals(sourceCharacterCodes, glyph.getCharacterCodes())
                    && String.format(java.util.Locale.ROOT, "%04X", (int) rawUnicode.charAt(0)).equals(selector)
                    && sourceGlyphId != null && sourceGlyphId > 0 && sourceGlyphId == sourceCharacterCodes[0] && sourceGlyphId.equals(selectorGlyphId)
                    && Integer.valueOf(3).equals(cmapPlatformId) && Integer.valueOf(0).equals(cmapEncodingId)
                    && fontProgramSha256 != null && fontProgramSha256.matches("[a-f0-9]{64}")
                    && toUnicodeSha256 != null && toUnicodeSha256.matches("[a-f0-9]{64}");
        }
    }

    @Data public static class PdfFormField {
        private String id, fieldName, fieldType, fieldValue, appearanceValue, appearanceState;
        private String visibilityStatus, valueStatus;
        private Integer pageNo;
        private Integer annotationFlags;
        private double[] bbox, rectanglePdf, appearanceBbox, appearanceMatrix;
        private boolean needAppearances, appearancePresent, conflict;
        private String appearanceRawStreamBytesBase64, appearanceDecodedStreamBytesBase64;
        private String appearanceStreamObservationStatus = "not_present";
        private String appearanceGlyphObservationStatus = "not_present";
        private List<PdfGlyph> appearanceGlyphs = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
    }
}
