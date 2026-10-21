package com.consense.document;

import com.consense.document.DocxTemplateEditor.NodeAnchor;
import com.consense.document.DocxTemplateEditor.Paragraph;
import java.util.*;

/** Candidate typed intent. Every old value comes from the same OER native observation. */
public final class NativeStoryPlan {
    public final String sourceSha256, profile;
    public final Identity identity;
    public final NativeProperty evenAndOddHeaders;
    public final List<Section> sections;
    public final List<StaticFooterGroup> staticFooterGroups;
    public final StaticFooterSourceFormat staticFooterSourceFormat;
    public final SctTitleMigration sctTitleMigration;

    public NativeStoryPlan(String sourceSha256, String profile, Identity identity,
                           NativeProperty evenAndOddHeaders, List<Section> sections,
                           List<StaticFooterGroup> staticFooterGroups) {
        this(sourceSha256,profile,identity,evenAndOddHeaders,sections,staticFooterGroups,null);
    }
    public NativeStoryPlan(String sourceSha256, String profile, Identity identity,
                           NativeProperty evenAndOddHeaders, List<Section> sections,
                           List<StaticFooterGroup> staticFooterGroups, StaticFooterSourceFormat formatting) {
        this(sourceSha256,profile,identity,evenAndOddHeaders,sections,staticFooterGroups,formatting,null);
    }
    private NativeStoryPlan(String sourceSha256,String profile,Identity identity,NativeProperty evenAndOddHeaders,
                            List<Section> sections,List<StaticFooterGroup> staticFooterGroups,
                            StaticFooterSourceFormat formatting,SctTitleMigration migration) {
        this.sctTitleMigration=migration;
        this.staticFooterSourceFormat = formatting;
        this.sourceSha256 = required(sourceSha256); this.profile = required(profile);
        this.identity = Objects.requireNonNull(identity);
        this.evenAndOddHeaders = Objects.requireNonNull(evenAndOddHeaders);
        this.sections = frozen(sections); this.staticFooterGroups = frozen(staticFooterGroups);
    }

    public NativeStoryPlan withSctTitleMigration(SctTitleMigration migration) {
        return new NativeStoryPlan(sourceSha256,profile,identity,evenAndOddHeaders,sections,staticFooterGroups,
                staticFooterSourceFormat,Objects.requireNonNull(migration));
    }
    /** One exact SCT main-section policy; never a public arbitrary-header editing instruction. */
    public static final class SctTitleMigration {
        public final Section section;
        public final String stylesPartSha256,headerStyleId,headerStyleNodeSha256;
        public final List<SctBodyTitle> bodyTitles;
        public final int plainDonorOrdinal,continuedDonorOrdinal;
        public SctTitleMigration(Section section,String stylesPartSha256,String headerStyleId,String headerStyleNodeSha256,
                                 List<SctBodyTitle> bodyTitles,int plainDonorOrdinal,int continuedDonorOrdinal) {
            this.section=Objects.requireNonNull(section);this.stylesPartSha256=required(stylesPartSha256);
            this.headerStyleId=required(headerStyleId);this.headerStyleNodeSha256=required(headerStyleNodeSha256);
            this.bodyTitles=frozen(bodyTitles);this.plainDonorOrdinal=plainDonorOrdinal;this.continuedDonorOrdinal=continuedDonorOrdinal;
        }
    }
    public static final class SctBodyTitle {
        public final Paragraph source;public final String exactText;public final boolean preserveSourceColumnControl;
        public SctBodyTitle(Paragraph source,String exactText,boolean preserveSourceColumnControl) {
            this.source=Objects.requireNonNull(source);this.exactText=required(exactText);this.preserveSourceColumnControl=preserveSourceColumnControl;
        }
    }

    /** Only the explicit static-footer migration may reference these unchanged source styles. */
    public static final class StaticFooterSourceFormat {
        public final String stylesPartSha256, headerStyleId, footerStyleId;
        public final String emptyFooterPartName, emptyFooterPartSha256;
        public final Integer contractNumberHalfPoints;
        public StaticFooterSourceFormat(String stylesPartSha256, String headerStyleId, String footerStyleId) {
            this(stylesPartSha256,headerStyleId,footerStyleId,null,null,null);
        }
        public StaticFooterSourceFormat(String stylesPartSha256, String headerStyleId, String footerStyleId,
                                       String emptyFooterPartName, String emptyFooterPartSha256, Integer contractNumberHalfPoints) {
            if(stylesPartSha256==null||!stylesPartSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("STORY_STYLE_PART_HASH_INVALID");
            if(emptyFooterPartName==null?(emptyFooterPartSha256!=null||contractNumberHalfPoints!=null)
                    :!emptyFooterPartName.matches("word/footer[1-9][0-9]*\\.xml")||emptyFooterPartSha256==null||!emptyFooterPartSha256.matches("[0-9a-f]{64}")
                    ||contractNumberHalfPoints==null||contractNumberHalfPoints<1||contractNumberHalfPoints>144)
                throw new IllegalArgumentException("STORY_STYLE_EMPTY_FOOTER_GUARD_INVALID");
            this.stylesPartSha256=stylesPartSha256;
            this.headerStyleId=required(headerStyleId);this.footerStyleId=required(footerStyleId);
            this.emptyFooterPartName=emptyFooterPartName;this.emptyFooterPartSha256=emptyFooterPartSha256;this.contractNumberHalfPoints=contractNumberHalfPoints;
        }
    }

    public static final class Identity {
        public final String number, title;
        public Identity(String number, String title) {
            this.number = plain(number); this.title = plain(title);
        }
        private static String plain(String value) {
            required(value);
            if (value.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)))
                throw new IllegalArgumentException("IDENTITY_EMPTY");
            if (value.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\t' && c != '\r'
                    || c >= 0xD800 && c <= 0xDFFF || c == 0xFFFE || c == 0xFFFF))
                throw new IllegalArgumentException("IDENTITY_INVALID_XML_CHARACTER");
            return value;
        }
    }

    public static final class Position {
        public final String sourceSha256, partName, partSha256, nativeNodePath;
        public Position(String sourceSha256, String partName, String partSha256, String nativeNodePath) {
            this.sourceSha256 = required(sourceSha256); this.partName = required(partName);
            this.partSha256 = required(partSha256); this.nativeNodePath = required(nativeNodePath);
        }
    }

    /** attributes use expanded namespace names exactly as observed, without inferred defaults. */
    public static final class PropertyOccurrence {
        public final Map<String, String> rawAttributes;
        public final Position sourcePosition;
        public PropertyOccurrence(Map<String, String> rawAttributes, Position sourcePosition) {
            this.rawAttributes = Collections.unmodifiableMap(new LinkedHashMap<>(rawAttributes));
            this.sourcePosition = Objects.requireNonNull(sourcePosition);
        }
    }
    public static final class NativeProperty {
        public final String name, status;
        public final boolean present;
        public final List<PropertyOccurrence> observations;
        public NativeProperty(String name, String status, boolean present, List<PropertyOccurrence> observations) {
            this.name = required(name); this.status = required(status); this.present = present;
            this.observations = frozen(observations);
        }
    }

    public enum Kind { HEADER, FOOTER }
    public enum Variant { DEFAULT, FIRST, EVEN }
    public enum BindingState { EXPLICIT_TARGET_LOADED, INHERITED_TARGET_CANDIDATE, NO_TARGET }
    public enum StoryAction { PRESERVE, APPEND_PROJECT_TITLE, UPSERT_CONTRACT_NUMBER, CREATE_NATIVE_FOOTER }
    public enum PageAction { PRESERVE_SOURCE_FIELDS, PRESERVE_EXISTING_PAGE, PRESERVE_NO_PAGE, CREATE_PAGE_FROM_STATIC_SEQUENCE }

    /** NO_TARGET is evidence of absence for this original slot, never an empty target wildcard. */
    public static final class Binding {
        public final Kind kind; public final Variant variant; public final BindingState state;
        public final String targetPartName, targetPartSha256, targetRootNativePath;
        public final String relationshipId, relationshipType, rawTarget, rawTargetMode;
        public final String originSectionId, inheritedFromSectionId;
        public final Position originReferencePosition, relationshipPosition;
        public Binding(Kind kind, Variant variant, BindingState state,
                       String targetPartName, String targetPartSha256, String targetRootNativePath,
                       String relationshipId, String relationshipType, String rawTarget, String rawTargetMode,
                       String originSectionId, String inheritedFromSectionId,
                       Position originReferencePosition, Position relationshipPosition) {
            this.kind = Objects.requireNonNull(kind); this.variant = Objects.requireNonNull(variant);
            this.state = Objects.requireNonNull(state);
            this.targetPartName = targetPartName; this.targetPartSha256 = targetPartSha256;
            this.targetRootNativePath = targetRootNativePath; this.relationshipId = relationshipId;
            this.relationshipType = relationshipType; this.rawTarget = rawTarget; this.rawTargetMode = rawTargetMode;
            this.originSectionId = originSectionId; this.inheritedFromSectionId = inheritedFromSectionId;
            this.originReferencePosition = originReferencePosition; this.relationshipPosition = relationshipPosition;
        }
    }

    /** Existing labels use captured paragraph offsets outside native fields, never regex at apply time. */
    public static final class Label {
        public final Paragraph paragraph; public final int start, end; public final String expectedText;
        public Label(Paragraph paragraph, int start, int end, String expectedText) {
            this.paragraph = Objects.requireNonNull(paragraph); this.start = start; this.end = end;
            this.expectedText = required(expectedText);
        }
    }
    public static final class Story {
        public final Binding expectedBinding;
        public final StoryAction action; public final PageAction pageAction;
        public final List<Label> contractLabels;
        public final List<String> retainedSourceControlLines;
        public final String pagePrefix, pageSuffix;
        public Story(Binding expectedBinding, StoryAction action, PageAction pageAction,
                     List<Label> contractLabels, List<String> retainedSourceControlLines,
                     String pagePrefix, String pageSuffix) {
            this.expectedBinding = Objects.requireNonNull(expectedBinding);
            this.action = Objects.requireNonNull(action); this.pageAction = Objects.requireNonNull(pageAction);
            this.contractLabels = frozen(contractLabels); this.retainedSourceControlLines = frozen(retainedSourceControlLines);
            this.pagePrefix = pagePrefix; this.pageSuffix = pageSuffix;
        }
    }

    /** Only the source-bound migration from static labels can replace a page start. */
    public static final class NumberingChange {
        public final NativeProperty expected;
        public final String newStart;
        public final String migrationProfileId;
        public NumberingChange(NativeProperty expected, String newStart, String migrationProfileId) {
            this.expected = Objects.requireNonNull(expected); this.newStart = required(newStart);
            this.migrationProfileId = required(migrationProfileId);
        }
    }
    /** Attribute names are a closed pgMar allowlist; no user XML or generic property mutation. */
    public static final class MarginChange {
        public final NativeProperty expected;
        public final Map<String, Integer> newTwips;
        public final String verifiedEditionProfileId;
        public MarginChange(NativeProperty expected, Map<String, Integer> newTwips, String verifiedEditionProfileId) {
            this.expected = Objects.requireNonNull(expected);
            for (Map.Entry<String, Integer> value : newTwips.entrySet())
                if (!Arrays.asList("top", "bottom", "left", "right", "header", "footer", "gutter").contains(value.getKey())
                        || value.getValue() == null || value.getValue() < 0)
                    throw new IllegalArgumentException("INVALID_MARGIN_CHANGE");
            this.newTwips = Collections.unmodifiableMap(new LinkedHashMap<>(newTwips));
            this.verifiedEditionProfileId = required(verifiedEditionProfileId);
        }
    }
    public static final class Section {
        public final String observedSectionId;
        public final NodeAnchor sourceSection;
        public final Position sourcePosition;
        public final NativeProperty titlePg, pgNumType, pgMar, pgSz, type;
        public final List<Story> stories;
        public final NumberingChange numberingChange;
        public final MarginChange marginChange;
        public Section(String observedSectionId, NodeAnchor sourceSection, Position sourcePosition,
                       NativeProperty titlePg, NativeProperty pgNumType, NativeProperty pgMar,
                       NativeProperty pgSz, NativeProperty type, List<Story> stories,
                       NumberingChange numberingChange, MarginChange marginChange) {
            this.observedSectionId = required(observedSectionId); this.sourceSection = Objects.requireNonNull(sourceSection);
            this.sourcePosition = Objects.requireNonNull(sourcePosition); this.titlePg = Objects.requireNonNull(titlePg);
            this.pgNumType = Objects.requireNonNull(pgNumType); this.pgMar = Objects.requireNonNull(pgMar);
            this.pgSz = Objects.requireNonNull(pgSz); this.type = Objects.requireNonNull(type);
            this.stories = frozen(stories); this.numberingChange = numberingChange; this.marginChange = marginChange;
        }
    }
    public static final class StaticFooterParagraph {
        public final Paragraph source; public final String exactText;
        public StaticFooterParagraph(Paragraph source, String exactText) {
            this.source = Objects.requireNonNull(source); this.exactText = Objects.requireNonNull(exactText);
        }
    }
    public static final class StaticFooterGroup {
        public final int sourcePageOrdinal;
        public final List<StaticFooterParagraph> exactParagraphs;
        public StaticFooterGroup(int sourcePageOrdinal, List<StaticFooterParagraph> exactParagraphs) {
            if (sourcePageOrdinal < 1 || exactParagraphs.size() != 2) throw new IllegalArgumentException("INVALID_STATIC_FOOTER_GROUP");
            this.sourcePageOrdinal = sourcePageOrdinal; this.exactParagraphs = frozen(exactParagraphs);
        }
    }
    private static String required(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("REQUIRED_VALUE_MISSING");
        return value;
    }
    private static <T> List<T> frozen(List<T> values) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(values));
        for (T value : copy) Objects.requireNonNull(value);
        return Collections.unmodifiableList(copy);
    }
}
