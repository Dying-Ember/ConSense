package com.consense.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WordSymbolDecoderTest {
    @Test
    void decodesVerifiedWingdingsTickAndRetainsOriginalAttributes() throws Exception {
        WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode(symbol("Wingdings", "F0FC"));
        assertTrue(result.isResolved());
        assertEquals("Wingdings", result.getFont());
        assertEquals("F0FC", result.getHexCode());
        assertEquals("\u2713", result.getDecodedText());
        assertEquals("resolved", result.getResolutionStatus());
        assertEquals("https://support.microsoft.com/en-us/word/insert-a-check-mark-symbol", result.getMappingSource());
    }

    @Test
    void acceptsDocumentedLegacyByteEncodingAndPreservesLexicalCase() {
        WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode("wingdings", "00fc");
        assertTrue(result.isResolved());
        assertEquals("\u2713", result.getDecodedText());
        assertEquals("wingdings", result.getFont());
        assertEquals("00fc", result.getHexCode());
    }

    @Test
    void retainsWhitespaceAttributesWhileNormalizingOnlyForLookup() {
        WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode(" Wingdings ", " f0fc ");
        assertTrue(result.isResolved());
        assertEquals(" Wingdings ", result.getFont());
        assertEquals(" f0fc ", result.getHexCode());
    }

    @Test
    void samePrivateUseCodeInDifferentFontIsNotAssumedToBeATick() throws Exception {
        for (String font : new String[]{"Wingdings 2", "Wingdings 3", "Webdings", "Unknown Symbol Font", "Calibri"}) {
            WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode(symbol(font, "F0FC"));
            assertUnresolved(result, "unresolved_unsupported_font");
            assertEquals(font, result.getFont());
            assertEquals("F0FC", result.getHexCode());
        }
    }

    @Test
    void otherWingdingsCodesRemainUnresolvedRatherThanBecomeUnicodePrivateUseText() {
        for (String code : new String[]{"F0FE", "F03A", "2713", "E001"}) {
            WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode("Wingdings", code);
            assertUnresolved(result, "unresolved_unsupported_character_code");
            assertEquals(code, result.getHexCode());
        }
    }

    @Test
    void missingRequiredAttributesHaveExplicitStatuses() throws Exception {
        assertUnresolved(WordSymbolDecoder.decode(symbol(null, "F0FC")), "unresolved_missing_font");
        assertUnresolved(WordSymbolDecoder.decode(symbol("Wingdings", null)), "unresolved_missing_character_code");
    }

    @Test
    void malformedCodesAreRetainedAndNeverDecoded() {
        for (String code : new String[]{"FC", "0xFC", "F0FG", "1F0FC", "-0FC", "D83DDE00"}) {
            WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode("Wingdings", code);
            assertUnresolved(result, "unresolved_malformed_character_code");
            assertEquals(code, result.getHexCode());
        }
    }

    @Test
    void namespaceAndElementNameMustIdentifyAWordSymbol() throws Exception {
        assertUnresolved(WordSymbolDecoder.decode(null), "unresolved_not_word_symbol");
        assertUnresolved(WordSymbolDecoder.decode(xml("<w:t xmlns:w='" + WordSymbolDecoder.WORD_NAMESPACE + "'>F0FC</w:t>")), "unresolved_not_word_symbol");
        assertUnresolved(WordSymbolDecoder.decode(xml("<w:sym xmlns:w='urn:unrelated' w:font='Wingdings' w:char='F0FC'/>")), "unresolved_not_word_symbol");
    }

    @Test
    void acceptsStrictWordNamespaceAndDoesNotDependOnPrefix() throws Exception {
        WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode(xml("<x:sym xmlns:x='http://purl.oclc.org/ooxml/wordprocessingml/main' x:font='Wingdings' x:char='F0FC'/>"));
        assertTrue(result.isResolved());
        assertEquals("\u2713", result.getDecodedText());
    }

    @Test
    void provenanceRoundTripsThroughJacksonWithoutLoss() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (WordSymbolDecoder.Symbol original : new WordSymbolDecoder.Symbol[]{
                WordSymbolDecoder.decode("Wingdings", "F0FC"), WordSymbolDecoder.decode("Custom font", "F0FC"),
                WordSymbolDecoder.decode("Symbol", "F0AC"), WordSymbolDecoder.decode("Symbol", "F0AE"),
                WordSymbolDecoder.decode("Symbol", "F0B7")}) {
            String serialized = mapper.writeValueAsString(original);
            WordSymbolDecoder.Symbol copy = mapper.readValue(serialized, WordSymbolDecoder.Symbol.class);
            assertEquals(original.getFont(), copy.getFont());
            assertEquals(original.getHexCode(), copy.getHexCode());
            assertEquals(original.getDecodedText(), copy.getDecodedText());
            assertEquals(original.getResolutionStatus(), copy.getResolutionStatus());
            assertEquals(original.getMappingSource(), copy.getMappingSource());
            assertEquals(original.isResolved(), copy.isResolved());
            assertFalse(serialized.contains("\"resolved\":"));
        }
    }

    @Test
    void decodesActualCorpusSymbolPairsWithPrimaryMappingProvenance() throws Exception {
        String[][] cases = {{"F0AC", "\u2190"}, {"F0AE", "\u2192"}, {"F0B7", "\u2022"}};
        for (String[] pair : cases) {
            WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode(symbol("Symbol", pair[0]));
            assertTrue(result.isResolved());
            assertEquals(pair[1], result.getDecodedText());
            assertEquals("Symbol", result.getFont());
            assertEquals(pair[0], result.getHexCode());
            assertEquals("https://www.unicode.org/Public/MAPPINGS/VENDORS/ADOBE/symbol.txt", result.getMappingSource());
        }
    }

    @Test
    void acceptsDocumentedSymbolByteAliasesAndPreservesLexicalAttributes() {
        String[][] cases = {{"00ac", "\u2190"}, {"00ae", "\u2192"}, {"00b7", "\u2022"}};
        for (String[] pair : cases) {
            WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode(" Symbol ", " " + pair[0] + " ");
            assertTrue(result.isResolved());
            assertEquals(pair[1], result.getDecodedText());
            assertEquals(" Symbol ", result.getFont());
            assertEquals(" " + pair[0] + " ", result.getHexCode());
        }
    }

    @Test
    void strictSymbolNamespaceUsesTheSameVerifiedFontMapping() throws Exception {
        WordSymbolDecoder.Symbol result = WordSymbolDecoder.decode(xml("<x:sym xmlns:x='http://purl.oclc.org/ooxml/wordprocessingml/main' x:font='symbol' x:char='f0ac'/>"));
        assertTrue(result.isResolved());
        assertEquals("\u2190", result.getDecodedText());
        assertEquals("symbol", result.getFont());
        assertEquals("f0ac", result.getHexCode());
    }

    @Test
    void otherSymbolCodesAndUnrelatedFontsRemainExplicitlyUnresolved() {
        for (String code : new String[]{"F0FC", "F03A", "2190", "2192", "2022", "E0AC"})
            assertUnresolved(WordSymbolDecoder.decode("Symbol", code), "unresolved_unsupported_character_code");
        for (String font : new String[]{"Calibri", "Unknown Symbol Font", "Symbol MT", "Symbol 2"})
            for (String code : new String[]{"F0AC", "F0AE", "F0B7"})
                assertUnresolved(WordSymbolDecoder.decode(font, code), "unresolved_unsupported_font");
        for (String code : new String[]{"F0AC", "F0AE", "F0B7"})
            assertUnresolved(WordSymbolDecoder.decode("Wingdings", code), "unresolved_unsupported_character_code");
        for (String code : new String[]{"AC", "0xAC", "F0AG", "1F0AC"})
            assertUnresolved(WordSymbolDecoder.decode("Symbol", code), "unresolved_malformed_character_code");
    }

    private static void assertUnresolved(WordSymbolDecoder.Symbol result, String status) {
        assertFalse(result.isResolved());
        assertEquals("", result.getDecodedText());
        assertEquals(status, result.getResolutionStatus());
        assertNull(result.getMappingSource());
    }

    private static Node symbol(String font, String code) throws Exception {
        return xml("<w:sym xmlns:w='" + WordSymbolDecoder.WORD_NAMESPACE + "'"
                + (font == null ? "" : " w:font='" + font + "'")
                + (code == null ? "" : " w:char='" + code + "'") + "/>");
    }

    private static Node xml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }
}
