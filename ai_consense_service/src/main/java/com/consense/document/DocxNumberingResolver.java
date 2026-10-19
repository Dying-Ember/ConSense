package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNum;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;
import org.w3c.dom.Node;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Restores a bounded subset of OOXML automatic paragraph labels. One instance is
 * a story in source order, not a renderer or a Word pagination implementation.
 * Unresolved numbering is retained as provenance, never guessed from body text.
 *
 * OOXML lvlRestart is a one-based parent level, zero means never restart, and
 * omission means the previous level. Style numPr/ilvl is ignored in favour of
 * the abstract level's pStyle association (ISO/IEC 29500-1, 17.9.11, 17.9.24).
 */
public final class DocxNumberingResolver {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final XWPFDocument document;
    private final Map<String, NumDefinition> definitions;
    private final StyleDefaults styleDefaults;
    private final Map<String, Counter> counters = new HashMap<>();
    private final Map<Node, NumberingInfo> resolvedParagraphs = new IdentityHashMap<>();
    private final Map<Node, Level> selectedParagraphLevels;
    private final Map<Node, NumberingInfo> observedParagraphs;
    private final Map<Node,String> numberingPaths;
    private final String numberingPartName,numberingPartSha;

    public DocxNumberingResolver(XWPFDocument document) {
        this.document = document;
        this.definitions = readDefinitions(document);
        this.styleDefaults = readStyleDefaults(document);
        selectedParagraphLevels=new IdentityHashMap<>();observedParagraphs=new IdentityHashMap<>();numberingPaths=new IdentityHashMap<>();String[] identity=numberingPartIdentity(document);numberingPartName=identity[0];numberingPartSha=identity[1];
    }

    private DocxNumberingResolver(XWPFDocument document, Map<String, NumDefinition> definitions, StyleDefaults styleDefaults,Map<Node,Level> selectedParagraphLevels,Map<Node,NumberingInfo> observedParagraphs,Map<Node,String> numberingPaths,String numberingPartName,String numberingPartSha) {
        this.document = document;
        this.definitions = definitions;
        this.styleDefaults = styleDefaults;
        this.selectedParagraphLevels=selectedParagraphLevels;this.observedParagraphs=observedParagraphs;this.numberingPaths=numberingPaths;this.numberingPartName=numberingPartName;this.numberingPartSha=numberingPartSha;
    }

    /** Headers, footers and notes must not advance body-list counters. */
    public DocxNumberingResolver newStory() { return new DocxNumberingResolver(document, definitions, styleDefaults,selectedParagraphLevels,observedParagraphs,numberingPaths,numberingPartName,numberingPartSha); }

    /** Call in source order. Re-reading the same XMLBeans DOM paragraph is idempotent. */
    public NumberingInfo resolve(Node paragraph) {
        if (paragraph == null || !word(paragraph, "p")) return NumberingInfo.unnumbered();
        NumberingInfo prior = resolvedParagraphs.get(paragraph);
        if (prior != null) return prior;
        NumberingInfo result = resolveOnce(paragraph);
        resolvedParagraphs.put(paragraph, result);
        observedParagraphs.put(paragraph,result);
        return result;
    }

    /** Reuses the already selected level and label; observing layout never advances list counters. */
    ObjectNode layoutEvidence(Node paragraph,String sourceSha) {
        NumberingInfo info=observedParagraphs.get(paragraph);if(info==null)return null;
        ObjectNode out=JsonUtils.mapper().createObjectNode(),numbering=out.putObject("numbering");numbering.put("numbered",info.isNumbered());numbering.put("resolutionStatus",info.getResolutionStatus());
        if(info.getNumId()!=null){numbering.put("numId",info.getNumId());numbering.put("abstractNumId",info.getAbstractNumId());if(info.getLevel()!=null)numbering.put("level",info.getLevel());numbering.put("format",info.getFormat());numbering.put("label",info.getLabel());numbering.put("source",info.getSource());}
        ArrayNode layers=out.putArray("layers");Level selected=selectedParagraphLevels.get(paragraph);
        if(selected!=null&&numberingPartName!=null&&numberingPartSha!=null){
            java.util.function.Function<Node,ObjectNode> position=node->OerNativeDocxOutline.layoutPosition(node,sourceSha,numberingPartName,numberingPartSha,numberingPaths);
            OerNativeDocxOutline.layoutLayer(layers,child(selected.abstractSource,"pPr"),"numbering_abstract_level",null,position);
            if(selected.source!=selected.abstractSource)OerNativeDocxOutline.layoutLayer(layers,child(selected.source,"pPr"),"numbering_level_override",null,position);
        }else if(selected!=null)numbering.put("layoutStatus","unknown_numbering_part_identity");
        return out;
    }

    private static String[] numberingPartIdentity(XWPFDocument document){
        if(document.getNumbering()!=null)try(InputStream input=document.getNumbering().getPackagePart().getInputStream()){
            return new String[]{document.getNumbering().getPackagePart().getPartName().getName().substring(1),DocumentParseProbe.sha256(org.springframework.util.StreamUtils.copyToByteArray(input))};
        }catch(Exception unavailable){/* In-memory, unsaved definitions have no original package identity. */}
        return new String[]{null,null};
    }

    private NumberingInfo resolveOnce(Node paragraph) {
        Node properties = child(paragraph, "pPr");
        Node direct = child(properties, "numPr");
        String styleId = val(child(properties, "pStyle"));
        StyleReference style = styleReference(styleId);
        String directId = val(child(direct, "numId"));
        String numId = directId != null ? directId : style.numId;
        String source = directId != null ? "paragraph" : style.fromDefaults ? "doc_defaults" : "paragraph_style";
        if (numId == null) {
            if (direct != null || style.failure != null) return NumberingInfo.unresolved(null, null, null, null, null, source,
                    style.failure != null ? style.failure : "missing_num_id");
            return NumberingInfo.unnumbered();
        }
        if ("0".equals(numId)) return NumberingInfo.cancelled(source);
        NumDefinition definition = definitions.get(numId);
        Integer level = integer(val(child(direct, "ilvl")));
        if (child(direct, "ilvl") != null && level == null)
            return NumberingInfo.unresolved(numId, null, null, null, null, source, "invalid_level");
        if (definition == null) return NumberingInfo.unresolved(numId, level, null, null, null, source, "missing_numbering_instance");
        if (directId == null && style.failure != null) return unresolved(definition, level, null, source, style.failure);
        if (level == null && directId == null && style.fromDefaults) level = styleDefaults.level;
        if (level == null && directId == null && !style.fromDefaults) {
            for (String inheritedStyle : style.chain) {
                for (Level candidate : definition.levels.values()) {
                    if (inheritedStyle.equals(candidate.styleId)) { level = candidate.index; break; }
                }
                if (level != null) break;
            }
            if (level == null) return NumberingInfo.unresolved(numId, null, definition.abstractId, null, null, source,
                    "style_level_not_linked");
        }
        if (level == null) level = 0;
        Level selected = definition.levels.get(level);
        if(selected!=null)selectedParagraphLevels.put(paragraph,selected);
        if (level < 0 || level > 8) return unresolved(definition, level, selected, source, "level_out_of_range");
        if (definition.failure != null) return unresolved(definition, level, selected, source, definition.failure);
        if (selected == null) return unresolved(definition, level, null, source, "missing_level_definition");
        if (child(direct, "numberingChange") != null) return unresolved(definition, level, selected, source, "tracked_numbering_change");
        if (selected.failure != null) return unresolved(definition, level, selected, source, selected.failure);

        Counter counter = counters.computeIfAbsent(numId, ignored -> new Counter());
        long value;
        if (counter.values[level] == null) value = selected.start;
        else if (counter.values[level] == Long.MAX_VALUE) return unresolved(definition, level, selected, source, "counter_overflow");
        else value = counter.values[level] + 1;
        counter.values[level] = value;
        // Invalid restart values are ignored by the specification, using the
        // same default as an omitted property. Never-reset levels are preserved.
        for (Level descendant : definition.levels.values()) {
            if (descendant.index > level && descendant.restartLevel >= level) counter.values[descendant.index] = null;
        }
        try {
            String label = expand(selected, definition, counter, level);
            return new NumberingInfo(true, true, numId, level, definition.abstractId, selected.format,
                    selected.levelText, label, source, "resolved", null, value, selected.suffix, selected.fontSymbols, selected.fontAttributes);
        } catch (UnsupportedLabel failure) {
            return unresolved(definition, level, selected, source, failure.getMessage());
        }
    }

    private static NumberingInfo unresolved(NumDefinition definition, Integer level, Level selected, String source, String reason) {
        return new NumberingInfo(true, false, definition.numId, level, definition.abstractId,
                selected == null ? null : selected.format, selected == null ? null : selected.levelText,
                "", source, "unresolved", reason, null, null,
                selected == null ? null : selected.fontSymbols, selected == null ? null : selected.fontAttributes);
    }

    private StyleReference styleReference(String styleId) {
        StyleReference reference = new StyleReference();
        if (styleId == null) {
            styleId = styleDefaults.styleId;
            if (styleDefaults.failure != null) reference.failure = styleDefaults.failure;
        }
        Set<String> visited = new HashSet<>();
        while (styleId != null) {
            if (!visited.add(styleId)) { reference.failure = "style_inheritance_cycle"; break; }
            reference.chain.add(styleId);
            XWPFStyle style = document.getStyles() == null ? null : document.getStyles().getStyle(styleId);
            if (style == null) break;
            Node root = element(style.getCTStyle().getDomNode());
            Node numPr = child(child(root, "pPr"), "numPr");
            if (reference.numId == null) reference.numId = val(child(numPr, "numId"));
            styleId = val(child(root, "basedOn"));
        }
        if (reference.numId == null && styleDefaults.numId != null) {
            reference.numId = styleDefaults.numId;
            reference.fromDefaults = true;
            if (reference.failure == null) reference.failure = styleDefaults.numberingFailure;
        } else if (reference.numId == null && styleDefaults.numberingFailure != null) {
            reference.failure = styleDefaults.numberingFailure;
        }
        return reference;
    }

    private static StyleDefaults readStyleDefaults(XWPFDocument document) {
        StyleDefaults defaults = new StyleDefaults();
        if (document.getStyles() == null) return defaults;
        // XWPFStyles has no public accessor for the complete CTStyles/default
        // style list in POI 5.2.5. The source package part is the authoritative
        // read-only form for parser-created (not subsequently edited) documents.
        try (InputStream stream = document.getStyles().getPackagePart().getInputStream()) {
            Node root = element(StylesDocument.Factory.parse(stream).getStyles().getDomNode());
            for (Node style : children(root, "style")) {
                if ("paragraph".equals(attribute(style, "type")) && attribute(style, "default") != null && onOff(style, "default")) {
                    defaults.styleId = attribute(style, "styleId"); break;
                }
            }
            Node ppr = child(child(child(root, "docDefaults"), "pPrDefault"), "pPr");
            Node numPr = child(ppr, "numPr");
            defaults.numId = val(child(numPr, "numId"));
            Node defaultLevel = child(numPr, "ilvl");
            defaults.level = defaultLevel == null ? Integer.valueOf(0) : integer(val(defaultLevel));
            if (defaultLevel != null && defaults.level == null) defaults.numberingFailure = "invalid_default_level";
            if (numPr != null && defaults.numId == null) defaults.numberingFailure = "missing_default_num_id";
        } catch (Exception unavailable) {
            defaults.failure = "default_style_metadata_unavailable";
        }
        return defaults;
    }

    private static String expand(Level selected, NumDefinition definition, Counter counter, int currentLevel) {
        if ("none".equals(selected.format)) return "";
        if (selected.levelText == null) throw new UnsupportedLabel("missing_level_text");
        String text = selected.displayLevelText == null ? selected.levelText : selected.displayLevelText;
        if (containsPrivateUse(text)) throw new UnsupportedLabel("font_specific_level_symbol");
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character != '%' || i + 1 == text.length() || text.charAt(i + 1) < '1' || text.charAt(i + 1) > '9') {
                label.append(character); continue;
            }
            int referenced = text.charAt(++i) - '1';
            if (referenced > currentLevel) continue;
            Level parent = definition.levels.get(referenced);
            if (parent == null || parent.failure != null) throw new UnsupportedLabel("unresolved_referenced_level");
            long number = counter.values[referenced] == null ? parent.start : counter.values[referenced];
            label.append(format(number, selected.legal ? "decimal" : parent.format));
        }
        if ("bullet".equals(selected.format) && text.indexOf('%') >= 0) throw new UnsupportedLabel("dynamic_bullet_label");
        if (!supportedFormat(selected.format)) throw new UnsupportedLabel("unsupported_number_format:" + selected.format);
        return label.toString();
    }

    private static boolean supportedFormat(String format) {
        return "decimal".equals(format) || "decimalZero".equals(format) || "lowerLetter".equals(format)
                || "upperLetter".equals(format) || "lowerRoman".equals(format) || "upperRoman".equals(format)
                || "bullet".equals(format) || "none".equals(format);
    }

    static String format(long number, String format) {
        if ("decimal".equals(format)) return Long.toString(number);
        if (number < 0) throw new UnsupportedLabel("negative_counter");
        if ("decimalZero".equals(format)) return number < 10 ? "0" + number : Long.toString(number);
        if ("lowerLetter".equals(format) || "upperLetter".equals(format)) {
            if (number < 1) throw new UnsupportedLabel("non_positive_letter_counter");
            // ECMA-376 17.18.59 repeats one letter: 27=aa, 28=bb, 53=aaa.
            long repeats = (number - 1) / 26 + 1;
            char letter = (char) (("lowerLetter".equals(format) ? 'a' : 'A') + (number - 1) % 26);
            StringBuilder letters = new StringBuilder();
            appendRepeated(letters, letter, repeats);
            return letters.toString();
        }
        if ("lowerRoman".equals(format) || "upperRoman".equals(format)) {
            if (number < 1) throw new UnsupportedLabel("non_positive_roman_counter");
            int[] values = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
            String[] symbols = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
            StringBuilder roman = new StringBuilder();
            appendRepeated(roman, 'M', number / 1000);
            number %= 1000;
            for (int i = 1; i < values.length; i++) while (number >= values[i]) { roman.append(symbols[i]); number -= values[i]; }
            return "lowerRoman".equals(format) ? roman.toString().toLowerCase(java.util.Locale.ROOT) : roman.toString();
        }
        throw new UnsupportedLabel("unsupported_placeholder_format:" + format);
    }

    private static void appendRepeated(StringBuilder target, char character, long count) {
        if (count > Integer.MAX_VALUE - target.length())
            throw new UnsupportedLabel("numbering_label_exceeds_string_capacity");
        for (long i = 0; i < count; i++) target.append(character);
    }

    private static Map<String, NumDefinition> readDefinitions(XWPFDocument document) {
        Map<String, NumDefinition> result = new HashMap<>();
        if (document.getNumbering() == null) return result;
        Map<String, Node> abstracts = new HashMap<>();
        for (XWPFAbstractNum abstractNum : document.getNumbering().getAbstractNums()) {
            Node root = element(abstractNum.getCTAbstractNum().getDomNode());
            abstracts.put(attribute(root, "abstractNumId"), root);
        }
        for (XWPFNum num : document.getNumbering().getNums()) {
            Node root = element(num.getCTNum().getDomNode());
            NumDefinition definition = new NumDefinition();
            definition.numId = attribute(root, "numId");
            definition.abstractId = val(child(root, "abstractNumId"));
            Node abstractRoot = abstracts.get(definition.abstractId);
            if (abstractRoot == null) definition.failure = "missing_abstract_numbering";
            else {
                // styleLink names this definition; numStyleLink instead inherits another definition.
                if (child(abstractRoot, "numStyleLink") != null)
                    definition.failure = "numbering_style_reference_not_supported";
                for (Node levelNode : children(abstractRoot, "lvl")) {
                    Level level = readLevel(levelNode);
                    if (level.index >= 0 && level.index <= 8) definition.levels.put(level.index, level);
                    else definition.failure = "invalid_level_definition";
                }
                for (Node override : children(root, "lvlOverride")) {
                    Integer index = integer(attribute(override, "ilvl"));
                    if (index == null || index < 0 || index > 8) { definition.failure = "invalid_level_override"; continue; }
                    Node replacement = child(override, "lvl");
                    if (replacement != null) {
                        Level level = readLevel(replacement);
                        Level inherited=definition.levels.get(index);level.abstractSource=inherited==null?null:inherited.abstractSource;
                        if (level.index != index) definition.failure = "mismatched_level_override";
                        definition.levels.put(index, level);
                    }
                    Node start = child(override, "startOverride");
                    if (start != null) {
                        Long value = longInteger(val(start));
                        Level level = definition.levels.get(index);
                        if (level == null) definition.failure = "missing_override_level";
                        else if (value == null) level.failure = "invalid_start_override";
                        else level.start = value;
                    }
                }
            }
            result.put(definition.numId, definition);
        }
        return result;
    }

    private static Level readLevel(Node node) {
        Level level = new Level();
        level.source=node;level.abstractSource=node;
        Integer index = integer(attribute(node, "ilvl"));
        level.index = index == null ? -1 : index;
        Node start = child(node, "start");
        Long value = start == null ? 0L : longInteger(val(start));
        if (value == null) level.failure = "invalid_start_value"; else level.start = value;
        level.format = val(child(node, "numFmt"));
        if (level.format == null) level.format = "decimal";
        if (attribute(child(node, "numFmt"), "format") != null) level.failure = "custom_number_format_not_supported";
        level.levelText = val(child(node, "lvlText"));
        if ((level.levelText == null || level.levelText.isEmpty())
                && attribute(child(node, "lvlText"), "null") != null && onOff(child(node, "lvlText"), "null")) level.levelText = "";
        level.styleId = val(child(node, "pStyle"));
        level.suffix = val(child(node, "suff"));
        if (level.suffix == null) level.suffix = "tab";
        level.legal = onOff(child(node, "isLgl"), "val");
        Integer restart = integer(val(child(node, "lvlRestart")));
        level.restartLevel = restart != null && restart == 0 ? -1
                : restart != null && restart > 0 && restart <= level.index ? restart - 1 : level.index - 1;
        if (child(node, "lvlPicBulletId") != null) level.failure = "picture_bullet_not_supported";
        if (child(node, "lvlRestart") != null && (restart == null || restart < 0)) level.failure = "invalid_restart_value";
        Node fonts = child(child(node, "rPr"), "rFonts");
        boolean symbolFont = false;
        for (String slot : new String[] {"ascii", "hAnsi", "eastAsia", "cs"}) {
            String font = attribute(fonts, slot);
            if (font != null && (font.trim().equalsIgnoreCase("Symbol") || font.trim().toLowerCase(java.util.Locale.ROOT).startsWith("wingdings")
                    || font.trim().equalsIgnoreCase("Webdings"))) symbolFont = true;
        }
        if ("bullet".equals(level.format) && level.levelText != null
                && (containsPrivateUse(level.levelText) || symbolFont)) {
            decodeBulletSymbols(level, fonts, symbolFont);
        } else if (symbolFont && level.failure == null) level.failure = "font_specific_level_symbol";
        return level;
    }

    private static void decodeBulletSymbols(Level level, Node fonts, boolean symbolFont) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (fonts != null && fonts.getAttributes() != null) for (int i = 0; i < fonts.getAttributes().getLength(); i++) {
            Node attribute = fonts.getAttributes().item(i);
            if (W.equals(attribute.getNamespaceURI())) attributes.put(attribute.getLocalName(), attribute.getNodeValue());
        }
        boolean themed = attributes.containsKey("asciiTheme") || attributes.containsKey("hAnsiTheme")
                || attributes.containsKey("eastAsiaTheme") || attributes.containsKey("cstheme");
        String font = null; boolean conflicting = false;
        for (String slot : new String[] {"ascii", "hAnsi", "eastAsia", "cs"}) {
            String candidate = attributes.get(slot);
            if (candidate == null || candidate.trim().isEmpty()) continue;
            if (font == null) font = candidate;
            else if (!font.trim().equalsIgnoreCase(candidate.trim())) conflicting = true;
        }
        level.fontAttributes = attributes;
        level.fontSymbols = new ArrayList<>();
        StringBuilder display = new StringBuilder(); boolean complete = true;
        for (int i = 0; i < level.levelText.length();) {
            int code = level.levelText.codePointAt(i); i += Character.charCount(code);
            if (Character.isWhitespace(code) || Character.isSpaceChar(code)
                    || (Character.getType(code) != Character.PRIVATE_USE && !(symbolFont && code <= 0xFF))) { display.appendCodePoint(code); continue; }
            String hex = String.format(java.util.Locale.ROOT, "%04X", code);
            WordSymbolDecoder.Symbol symbol = themed
                    ? new WordSymbolDecoder.Symbol(font, hex, "", "unresolved_numbering_theme_font", null)
                    : conflicting ? new WordSymbolDecoder.Symbol("", hex, "", "unresolved_conflicting_numbering_fonts", null)
                    : WordSymbolDecoder.decode(font, hex);
            level.fontSymbols.add(symbol);
            if (symbol.isResolved()) display.append(symbol.getDecodedText());
            else { complete = false; display.appendCodePoint(code); }
        }
        if (complete) level.displayLevelText = display.toString();
        else if (level.failure == null) level.failure = "font_specific_level_symbol";
    }

    private static boolean containsPrivateUse(String value) {
        for (int i = 0; i < value.length();) {
            int code = value.codePointAt(i);
            if (Character.getType(code) == Character.PRIVATE_USE) return true;
            i += Character.charCount(code);
        }
        return false;
    }
    private static Integer integer(String value) { try { return value == null ? null : Integer.valueOf(value); } catch (NumberFormatException ignored) { return null; } }
    private static Long longInteger(String value) { try { return Long.valueOf(value); } catch (RuntimeException ignored) { return null; } }
    private static boolean word(Node node, String local) { return node != null && node.getNodeType() == Node.ELEMENT_NODE && W.equals(node.getNamespaceURI()) && local.equals(node.getLocalName()); }
    private static Node element(Node node) { if (node == null || node.getNodeType() == Node.ELEMENT_NODE) return node; for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (child.getNodeType() == Node.ELEMENT_NODE) return child; return null; }
    private static Node child(Node node, String local) { if (node == null) return null; for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (word(child, local)) return child; return null; }
    private static List<Node> children(Node node, String local) { List<Node> result = new ArrayList<>(); if (node != null) for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (word(child, local)) result.add(child); return result; }
    private static String attribute(Node node, String name) { if (node == null || node.getAttributes() == null) return null; Node value = node.getAttributes().getNamedItemNS(W, name); return value == null ? null : value.getNodeValue(); }
    private static String val(Node node) { return attribute(node, "val"); }
    private static boolean onOff(Node node, String attribute) { if (node == null) return false; String value = attribute(node, attribute); return value == null || !("0".equals(value) || "false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value)); }

    private static final class StyleReference { final List<String> chain = new ArrayList<>(); String numId; String failure; boolean fromDefaults; }
    private static final class StyleDefaults { String styleId; String numId; Integer level; String failure; String numberingFailure; }
    private static final class NumDefinition { String numId; String abstractId; String failure; final Map<Integer, Level> levels = new HashMap<>(); }
    private static final class Level { int index; long start; int restartLevel; String format; String levelText; String displayLevelText; String styleId; String suffix; String failure; boolean legal; Node source,abstractSource; List<WordSymbolDecoder.Symbol> fontSymbols; Map<String, String> fontAttributes; }
    private static final class Counter { final Long[] values = new Long[9]; }
    private static final class UnsupportedLabel extends RuntimeException { UnsupportedLabel(String reason) { super(reason); } }

    /** Immutable provenance suitable for JSON serialization through its getters. */
    public static final class NumberingInfo {
        private final boolean numbered, resolved;
        private final String numId, abstractNumId, format, levelText, label, source, resolutionStatus, reason, suffix;
        private final Integer level;
        private final Long value;
        private final List<WordSymbolDecoder.Symbol> fontSymbols;
        private final Map<String, String> fontAttributes;
        private NumberingInfo(boolean numbered, boolean resolved, String numId, Integer level, String abstractNumId,
                              String format, String levelText, String label, String source, String resolutionStatus,
                              String reason, Long value, String suffix) {
            this(numbered, resolved, numId, level, abstractNumId, format, levelText, label, source, resolutionStatus, reason, value, suffix, null, null);
        }
        private NumberingInfo(boolean numbered, boolean resolved, String numId, Integer level, String abstractNumId,
                              String format, String levelText, String label, String source, String resolutionStatus,
                              String reason, Long value, String suffix, List<WordSymbolDecoder.Symbol> fontSymbols, Map<String, String> fontAttributes) {
            this.fontSymbols = fontSymbols == null ? null : Collections.unmodifiableList(new ArrayList<>(fontSymbols));
            this.fontAttributes = fontAttributes == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fontAttributes));
            this.numbered = numbered; this.resolved = resolved; this.numId = numId; this.level = level;
            this.abstractNumId = abstractNumId; this.format = format; this.levelText = levelText; this.label = label;
            this.source = source; this.resolutionStatus = resolutionStatus; this.reason = reason; this.value = value; this.suffix = suffix;
        }
        private static NumberingInfo unnumbered() { return new NumberingInfo(false, true, null, null, null, null, null, "", null, "not_numbered", null, null, null); }
        private static NumberingInfo cancelled(String source) { return new NumberingInfo(false, true, "0", null, null, null, null, "", source, "cancelled", null, null, null); }
        private static NumberingInfo unresolved(String numId, Integer level, String abstractId, String format, String levelText, String source, String reason) {
            return new NumberingInfo(true, false, numId, level, abstractId, format, levelText, "", source, "unresolved", reason, null, null);
        }
        public boolean isNumbered() { return numbered; }
        public boolean isResolved() { return resolved; }
        public String getNumId() { return numId; }
        public Integer getLevel() { return level; }
        public String getAbstractNumId() { return abstractNumId; }
        public String getFormat() { return format; }
        public String getLevelText() { return levelText; }
        public String getLabel() { return label; }
        public String getSource() { return source; }
        public String getResolutionStatus() { return resolutionStatus; }
        public String getReason() { return reason; }
        public Long getValue() { return value; }
        public String getSuffix() { return suffix; }
        public List<WordSymbolDecoder.Symbol> getFontSymbols() { return fontSymbols; }
        public Map<String, String> getFontAttributes() { return fontAttributes; }
    }
}
