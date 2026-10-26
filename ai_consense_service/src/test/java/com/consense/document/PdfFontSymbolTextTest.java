package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authored font-metadata controls; no real source parsing, OCR, API or model. */
class PdfFontSymbolTextTest {
    @Test void gatedActualOriginalFontMetadataKeepsRawUnicodeAndSourceBytes() throws Exception {
        String sourceValue = System.getProperty("consense.sl.symbol.original", System.getenv("CONSENSE_SL_SYMBOL_ORIGINAL"));
        Assumptions.assumeTrue(sourceValue != null && !sourceValue.isBlank(), "Explicit original PDF font-metadata fixture not supplied");
        Path source = Path.of(sourceValue).toAbsolutePath(); byte[] original = Files.readAllBytes(source);
        String sourceHash = DocumentParseProbe.sha256(original);
        assertEquals("6331c283da806846a06644814fa2271182314dbc2235c711a16e8492faaa0492", sourceHash);
        List<Map<String,Object>> observed = new ArrayList<>();
        try (PDDocument document = PDDocument.load(original)) {
            assertEquals(4300, document.getNumberOfPages());
            for (int pageNo : new int[]{2546, 4260}) {
                PDPage page = document.getPage(pageNo - 1); List<PdfPageEvidence.PdfGlyph> privateGlyphs = new ArrayList<>();
                PdfNativeSupport.TextDecoder decoder = new PdfNativeSupport.TextDecoder();
                PDFTextStripper stripper = new PDFTextStripper() {
                    int ordinal;
                    @Override protected void processTextPosition(TextPosition position) {
                        String id = "pdf-page/" + pageNo + "/glyph/" + ordinal++;
                        if (position.getUnicode() == null || position.getUnicode().codePoints().noneMatch(c -> Character.getType(c) == Character.PRIVATE_USE)) return;
                        PdfPageEvidence.PdfGlyph glyph = PdfNativeSupport.glyph(position, getGraphicsState(), page, id);
                        glyph.setTextResolution(decoder.decode(position)); privateGlyphs.add(glyph);
                    }
                };
                stripper.setStartPage(pageNo); stripper.setEndPage(pageNo); stripper.getText(document);
                assertFalse(privateGlyphs.isEmpty(), "Actual source font PUA observation must not disappear");
                for (PdfPageEvidence.PdfGlyph glyph : privateGlyphs) {
                    String expected = PdfPageEvidence.TextResolution.symbolText(glyph.getUnicode());
                    if (expected != null) assertEquals(expected, glyph.readerText(), glyph.getTextResolution().toString());
                    else assertEquals(glyph.getUnicode(), glyph.readerText());
                }
                if (pageNo == 2546) {
                    assertEquals(6, privateGlyphs.size());
                    assertTrue(privateGlyphs.stream().allMatch(g -> PdfPageEvidence.TextResolution.RESOLVED.equals(g.getTextResolution().getResolutionStatus())));
                } else {
                    assertTrue(privateGlyphs.stream().anyMatch(g -> "\uF06D".equals(g.getUnicode())
                            && g.getTextResolution().getResolutionStatus().startsWith("unresolved_")));
                }
                observed.add(Map.of("physicalPageNo", pageNo, "privateUseGlyphs", privateGlyphs));
            }
        }
        assertEquals(sourceHash, DocumentParseProbe.sha256(Files.readAllBytes(source)), "Original PDF bytes remain unchanged");
        Path workspace = Path.of("").toAbsolutePath();
        if (workspace.getFileName().toString().equals("ai_consense_service")) workspace = workspace.getParent();
        Path directory = workspace.resolve(".local/sl-text-clause-quality-20261024/symbol-font"); Files.createDirectories(directory);
        Path output = directory.resolve("original-font-component-" + java.util.UUID.randomUUID() + ".json");
        Files.writeString(output, JsonUtils.write(Map.of("schema", "consense.sl.actual-original-font-component.v1",
                "sourcePath", source.toString(), "sourceSha256Before", sourceHash, "sourceSha256After", sourceHash,
                "sourceBytesChanged", false, "observedPages", observed, "businessParserCalls", 0,
                "ocrOrModelCalls", 0, "sourceQualityAccepted", false)), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Actual original font-metadata component receipt: " + output);
    }

    @Test void verifiedSymbolSelectorDecodesIndependentlyOfItsDifferentOriginalCid() throws Exception {
        for (String[] pair : new String[][]{{"\uF046", "\u03A6"}, {"\uF03E", ">"}, {"\uF0A3", "\u2264"}}) {
            try (Fixture fixture = new Fixture(pair[0])) {
                PdfPageEvidence.PdfGlyph glyph = fixture.glyph();
                assertEquals(pair[1], glyph.readerText());
                assertEquals(pair[0], glyph.getUnicode()); assertArrayEquals(new int[]{41}, glyph.getCharacterCodes());
                PdfPageEvidence.TextResolution proof = glyph.getTextResolution();
                assertEquals(PdfPageEvidence.TextResolution.RESOLVED, proof.getResolutionStatus());
                assertEquals(41, proof.getSourceGlyphId().intValue()); assertEquals(41, proof.getSelectorGlyphId().intValue());
                assertEquals(DocumentParseProbe.sha256(fixture.fontBytes), proof.getFontProgramSha256());
                assertEquals(DocumentParseProbe.sha256(fixture.cmapBytes), proof.getToUnicodeSha256());
                assertEquals(PdfPageEvidence.TextResolution.VERSION, proof.getDecoderVersion());
                verify(fixture.symbolCmap).getGlyphId(pair[0].charAt(0));
                verify(fixture.font).codeToGID(41);
            }
        }
    }

    @Test void fontNameAloneNonembeddedOrDifferentSourceAndSelectorGlyphsCannotResolve() throws Exception {
        for (String failure : List.of("font", "not_embedded", "source_unicode", "glyph", "no_symbol_cmap", "no_to_unicode", "encoding", "cid_map")) {
            try (Fixture fixture = new Fixture("\uF046")) {
                if (failure.equals("font")) when(fixture.font.getName()).thenReturn("UnrelatedFont");
                if (failure.equals("not_embedded")) when(fixture.font.isEmbedded()).thenReturn(false);
                if (failure.equals("source_unicode")) when(fixture.font.toUnicode(41)).thenReturn("\uF03E");
                if (failure.equals("glyph")) when(fixture.font.codeToGID(41)).thenReturn(99);
                if (failure.equals("no_symbol_cmap")) when(fixture.cmaps.getSubtable(3, 0)).thenReturn(null);
                if (failure.equals("no_to_unicode")) fixture.dictionary.removeItem(COSName.TO_UNICODE);
                if (failure.equals("encoding")) fixture.dictionary.setItem(COSName.ENCODING, COSName.IDENTITY_V);
                if (failure.equals("cid_map")) fixture.cidDictionary.removeItem(COSName.CID_TO_GID_MAP);
                PdfPageEvidence.PdfGlyph glyph = fixture.glyph();
                assertEquals("\uF046", glyph.readerText(), failure);
                assertTrue(glyph.getTextResolution().getResolutionStatus().startsWith("unresolved_"), failure);
                assertEquals("", glyph.getTextResolution().getDecodedText(), failure);
            }
        }
    }

    @Test void ambiguousMuAndUnsupportedPrivateFontSelectorsRemainLiteralAndExplicitUnknown() throws Exception {
        for (String raw : List.of("\uF06D", "\uF099")) {
            try (Fixture fixture = new Fixture(raw)) {
                PdfPageEvidence.PdfGlyph glyph = fixture.glyph();
                assertEquals(raw, glyph.readerText());
                assertEquals("unresolved_unsupported_selector", glyph.getTextResolution().getResolutionStatus());
                verify(fixture.font, never()).codeToGID(anyInt());
            }
        }
        try (Fixture fixture = new Fixture("Φ")) {
            assertNull(fixture.glyph().getTextResolution());
            assertEquals("Φ", fixture.glyph().readerText());
        }
    }

    @Test void changedResolutionCannotOverrideTheRawFontUnicodeCodesOrGlyphProof() throws Exception {
        for (String mutation : List.of("decoded", "font", "unicode", "codes", "source_codes", "glyph", "source", "version")) {
            try (Fixture fixture = new Fixture("\uF046")) {
                PdfPageEvidence.PdfGlyph glyph = fixture.glyph();
                PdfPageEvidence.TextResolution proof = glyph.getTextResolution();
                if (mutation.equals("decoded")) proof.setDecodedText("arbitrary replacement");
                if (mutation.equals("font")) glyph.setFontName("AnotherFont");
                if (mutation.equals("unicode")) glyph.setUnicode("\uF03E");
                if (mutation.equals("codes")) glyph.setCharacterCodes(new int[]{42});
                if (mutation.equals("source_codes")) { glyph.setCharacterCodes(new int[]{42}); proof.setSourceCharacterCodes(new int[]{42}); }
                if (mutation.equals("glyph")) proof.setSelectorGlyphId(99);
                if (mutation.equals("source")) proof.setMappingSource("untrusted source");
                if (mutation.equals("version")) proof.setDecoderVersion("unknown decoder");
                assertEquals(glyph.getUnicode(), glyph.readerText(), mutation);
            }
        }
    }

    @Test void ordinaryReaderAndRawObservationKeepIndependentLiteralChannelsAndOffsets() throws Exception {
        try (Fixture first = new Fixture("\uF046"); Fixture second = new Fixture("\uF0A3")) {
            List<PdfPageEvidence.PdfGlyph> glyphs = List.of(first.glyph(), second.glyph());
            glyphs.get(0).setId("glyph/0"); glyphs.get(1).setId("glyph/1");
            assertEquals("Φ≤", PdfNativeSupport.readerLine("\uF046\uF0A3", glyphs));
            assertEquals("Φ \t≤\n", PdfNativeSupport.readerLine("\uF046 \t\uF0A3\n", glyphs), "Existing layout whitespace remains verbatim between exact source-bound literals");
            assertEquals("\uF046 ! \uF0A3", PdfNativeSupport.readerLine("\uF046 ! \uF0A3", glyphs), "A non-whitespace mismatch cannot be guessed or partially rewritten");
            assertEquals("\uF0A3 \uF046", PdfNativeSupport.readerLine("\uF0A3 \uF046", glyphs), "A reordered glyph line cannot adopt decoded text");
            JsonNode raw = OerNativePdfObservation.glyphChannel(glyphs);
            assertEquals("\uF046\uF0A3", raw.path("text").asText());
            assertEquals(0, raw.path("spans").get(0).path("start").asInt());
            assertEquals(1, raw.path("spans").get(0).path("end").asInt());
            assertEquals(1, raw.path("spans").get(1).path("start").asInt());
            assertEquals(2, raw.path("spans").get(1).path("end").asInt());
            PdfPageEvidence.PdfGlyph restored = JsonUtils.mapper().readValue(JsonUtils.write(glyphs.get(0)), PdfPageEvidence.PdfGlyph.class);
            assertEquals("Φ", restored.readerText()); assertEquals("\uF046", restored.getUnicode());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final PDDocument document = new PDDocument();
        final byte[] fontBytes = "AUTHORED embedded font metadata control".getBytes(StandardCharsets.US_ASCII);
        final byte[] cmapBytes = "AUTHORED exact ToUnicode bytes control".getBytes(StandardCharsets.US_ASCII);
        final PDType0Font font = mock(PDType0Font.class);
        final PDCIDFontType2 descendant = mock(PDCIDFontType2.class);
        final TrueTypeFont trueType = mock(TrueTypeFont.class);
        final CmapTable cmaps = mock(CmapTable.class);
        final CmapSubtable symbolCmap = mock(CmapSubtable.class);
        final TextPosition position;
        final COSDictionary dictionary = new COSDictionary();
        final COSDictionary cidDictionary = new COSDictionary();
        Fixture(String raw) throws Exception {
            PDFontDescriptor descriptor = new PDFontDescriptor(new COSDictionary());
            descriptor.setFontFile2(new PDStream(document, new ByteArrayInputStream(fontBytes)));
            dictionary.setItem(COSName.TO_UNICODE, new PDStream(document, new ByteArrayInputStream(cmapBytes)));
            dictionary.setItem(COSName.ENCODING, COSName.IDENTITY_H); cidDictionary.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
            when(font.getName()).thenReturn("Symbol"); when(font.getSubType()).thenReturn("Type0");
            when(font.isEmbedded()).thenReturn(true); when(font.getFontDescriptor()).thenReturn(descriptor);
            when(font.getCOSObject()).thenReturn(dictionary); when(font.getDescendantFont()).thenReturn(descendant);
            when(descendant.isEmbedded()).thenReturn(true); when(descendant.getTrueTypeFont()).thenReturn(trueType);
            when(descendant.getCOSObject()).thenReturn(cidDictionary);
            when(trueType.getCmap()).thenReturn(cmaps); when(cmaps.getSubtable(3, 0)).thenReturn(symbolCmap);
            when(font.toUnicode(41)).thenReturn(raw); when(font.codeToGID(41)).thenReturn(41);
            when(symbolCmap.getGlyphId(raw.charAt(0))).thenReturn(41);
            position = new TextPosition(0, 612, 792, new Matrix(12, 0, 0, 12, 100, 600),
                    106, 600, 12, 6, 3, raw, new int[]{41}, font, 12, 12);
        }
        PdfPageEvidence.PdfGlyph glyph() {
            PdfPageEvidence.PdfGlyph glyph = new PdfPageEvidence.PdfGlyph();
            glyph.setUnicode(position.getUnicode()); glyph.setCharacterCodes(position.getCharacterCodes());
            glyph.setFontName(font.getName()); glyph.setFontSubtype("Type0"); glyph.setFontEmbedded(font.isEmbedded());
            glyph.setTextResolution(new PdfNativeSupport.TextDecoder().decode(position));
            return glyph;
        }
        @Override public void close() throws Exception { document.close(); }
    }
}
