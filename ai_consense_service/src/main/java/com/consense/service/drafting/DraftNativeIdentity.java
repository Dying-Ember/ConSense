package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor.*;
import com.consense.common.JsonUtils;
import java.io.InputStream;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Candidate consumer projection of OER native section evidence, never a package parser. */
final class DraftNativeIdentity {
    private static final String MAIN = "word/document.xml";
    private static final String W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";

    static final class LabelSpec {
        final String part, partSha256, exactCatalogText, expectedText;
        final int paragraphOrdinal, start, end;
        LabelSpec(String part, String partSha256, int paragraphOrdinal, String exactCatalogText,
                  int start, int end, String expectedText) {
            this.part = part; this.partSha256 = partSha256; this.paragraphOrdinal = paragraphOrdinal;
            this.exactCatalogText = exactCatalogText; this.start = start; this.end = end; this.expectedText = expectedText;
        }
    }
    static final class StaticGroupSpec {
        final int pageOrdinal, firstOrdinal, secondOrdinal;
        final String firstText, secondText;
        StaticGroupSpec(int pageOrdinal, int firstOrdinal, String firstText, int secondOrdinal, String secondText) {
            this.pageOrdinal = pageOrdinal; this.firstOrdinal = firstOrdinal; this.firstText = firstText;
            this.secondOrdinal = secondOrdinal; this.secondText = secondText;
        }
    }
    static final class SectionPolicy {
        final PageAction pageAction; final String pagePrefix, pageSuffix, newStart;
        final List<String> sourceControlLines;
        SectionPolicy(PageAction pageAction, String pagePrefix, String pageSuffix, String newStart,
                      List<String> sourceControlLines) {
            this.pageAction = pageAction; this.pagePrefix = pagePrefix; this.pageSuffix = pageSuffix;
            this.newStart = newStart; this.sourceControlLines = new ArrayList<>(sourceControlLines);
        }
    }
    static final class VerifiedEdition {
        final String sourceSha256, profileId;
        final Map<String, String> entryHashes;
        final List<SectionPolicy> sections;
        final List<LabelSpec> labels;
        final List<StaticGroupSpec> staticFooters;
        VerifiedEdition(String sourceSha256, String profileId, Map<String, String> entryHashes,
                        List<SectionPolicy> sections, List<LabelSpec> labels, List<StaticGroupSpec> staticFooters) {
            this.sourceSha256 = sourceSha256; this.profileId = profileId; this.entryHashes = new LinkedHashMap<>(entryHashes);
            this.sections = new ArrayList<>(sections); this.labels = new ArrayList<>(labels);
            this.staticFooters = new ArrayList<>(staticFooters);
        }
    }

    /** Requires the future same-source pgNumType/pgMar/pgSz/type observation schema. */
    static NativeStoryPlan plan(TemplateIndex index, JsonNode nativeSections, Identity identity, VerifiedEdition edition) {
        return plan(index,nativeSections,identity,edition,null,Collections.emptyMap());
    }
    static NativeStoryPlan plan(TemplateIndex index, JsonNode nativeSections, Identity identity, VerifiedEdition edition,
                                StaticFooterSourceFormat formatting, Map<String,Integer> edgeDistancesTwips) {
        Objects.requireNonNull(edgeDistancesTwips);
        require(formatting!=null||edgeDistancesTwips.isEmpty(),"SOURCE_STYLE_FORMAT_REQUIRED_FOR_EDGE_DISTANCE");
        for(String name:edgeDistancesTwips.keySet())require(Arrays.asList("header","footer").contains(name),"BODY_MARGIN_CHANGE_UNSUPPORTED");
        if(formatting!=null)require(formatting.stylesPartSha256.equals(index.getEntryHashes().get("word/styles.xml")),"SOURCE_STYLE_PART_HASH_MISMATCH");
        require(index.getSourceSha256().equals(edition.sourceSha256), "SOURCE_HASH_MISMATCH");
        require("docx-native-sections-v1".equals(nativeSections.path("version").asText()), "NATIVE_SECTION_VERSION_UNSUPPORTED");
        position(index, nativeSections.path("sourcePosition"));
        for (Map.Entry<String, String> entry : edition.entryHashes.entrySet())
            require(entry.getValue().equals(index.getEntryHashes().get(entry.getKey())), "PROFILE_PART_HASH_MISMATCH");
        require(nativeSections.path("sections").size() == edition.sections.size(), "SECTION_COUNT_MISMATCH");
        NativeProperty evenAndOdd = property(index, nativeSections.path("headerFooterSettings").path("evenAndOddHeaders"), "evenAndOddHeaders");
        List<Section> sections = new ArrayList<>();
        Map<String, List<Label>> labelsByPart = labels(index, edition.labels);
        int ordinal = 0;
        for (JsonNode observed : nativeSections.path("sections")) {
            SectionPolicy policy = edition.sections.get(ordinal++);
            Position at = position(index, observed.path("endBoundary").path("sourcePosition"));
            NodeAnchor anchor = sectionAnchor(index, at);
            NativeProperty titlePg = property(index, observed.path("titlePg"), "titlePg");
            JsonNode properties = observed.path("layoutProperties");
            require("docx-native-section-layout-properties-v1".equals(properties.path("version").asText()), "NATIVE_SECTION_PROPERTIES_NOT_OBSERVED");
            NativeProperty pgNumType = property(index, properties.path("pgNumType"), "pgNumType");
            NativeProperty pgMar = property(index, properties.path("pgMar"), "pgMar");
            require(edgeDistancesTwips.isEmpty()||pgMar.present,"SOURCE_EDGE_MARGIN_NOT_OBSERVED");
            NativeProperty pgSz = property(index, properties.path("pgSz"), "pgSz");
            NativeProperty type = property(index, properties.path("type"), "type");
            for (NativeProperty property : Arrays.asList(titlePg, pgNumType, pgMar, pgSz, type))
                for (PropertyOccurrence occurrence : property.observations)
                    require(occurrence.sourcePosition.nativeNodePath.equals(at.nativeNodePath + "/" + W + property.name + "[1]"),
                            "NATIVE_PROPERTY_SECTION_MISMATCH");
            require(observed.path("headerFooterBindings").size() == 6, "SECTION_SIX_SLOTS_REQUIRED");
            Set<String> slots = new HashSet<>(); List<Story> stories = new ArrayList<>();
            for (JsonNode raw : observed.path("headerFooterBindings")) {
                Binding binding = binding(index, raw);
                require(slots.add(binding.kind + "/" + binding.variant), "DUPLICATE_STORY_SLOT");
                boolean activeCandidate = binding.variant == Variant.DEFAULT
                        || binding.variant == Variant.FIRST && on(titlePg)
                        || binding.variant == Variant.EVEN && on(evenAndOdd);
                StoryAction action = StoryAction.PRESERVE;
                if (binding.kind == Kind.HEADER && (activeCandidate || binding.state != BindingState.NO_TARGET))
                    action = StoryAction.APPEND_PROJECT_TITLE;
                if (binding.kind == Kind.FOOTER && (activeCandidate || binding.state != BindingState.NO_TARGET))
                    action = policy.pageAction == PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE
                            ? StoryAction.CREATE_NATIVE_FOOTER : StoryAction.UPSERT_CONTRACT_NUMBER;
                stories.add(new Story(binding, action, binding.kind == Kind.HEADER ? PageAction.PRESERVE_SOURCE_FIELDS : policy.pageAction,
                        labelsByPart.getOrDefault(binding.targetPartName, Collections.emptyList()),
                        binding.kind == Kind.HEADER ? Collections.emptyList() : policy.sourceControlLines,
                        binding.kind == Kind.HEADER ? null : policy.pagePrefix, binding.kind == Kind.HEADER ? null : policy.pageSuffix));
            }
            NumberingChange numbering = policy.newStart == null ? null : new NumberingChange(pgNumType, policy.newStart, edition.profileId);
            if (numbering != null) {
                require(policy.pageAction == PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE && !edition.staticFooters.isEmpty(), "NUMBER_START_REQUIRES_STATIC_MIGRATION");
                require("17".equals(singleAttributes(pgNumType).get(W + "start")) && "1".equals(policy.newStart), "UNVERIFIED_NUMBER_START_MIGRATION");
            }
            sections.add(new Section(observed.path("sectionId").asText(), anchor, at, titlePg, pgNumType,
                    pgMar, pgSz, type, stories, numbering, edgeDistancesTwips.isEmpty()?null:new MarginChange(pgMar,edgeDistancesTwips,edition.profileId)));
        }
        List<StaticFooterGroup> cleared = staticFooters(index, edition.staticFooters);
        NativeStoryPlan plan = new NativeStoryPlan(index.getSourceSha256(), edition.profileId, identity, evenAndOdd, sections, cleared, formatting);
        rejectConflictingSharedParts(plan);
        return plan;
    }


    static final String INTEGRATION_VERSION="source-bound-native-stories-integration11-v1";
    private static final JsonNode EDITIONS=editions();
    private static JsonNode editions(){try(InputStream input=DraftNativeIdentity.class.getResourceAsStream("/drafting/native-story-editions.json")){if(input==null)throw new IllegalStateException("NATIVE_STORY_EDITIONS_MISSING");return JsonUtils.mapper().readTree(input);}catch(java.io.IOException failure){throw new IllegalStateException("NATIVE_STORY_EDITIONS_INVALID",failure);}}
    /** Existing qualified values only; neither visible suggestions nor Project metadata are authority. */
    static Identity effectiveIdentity(Map<String,Object> prepared){Map<String,Object> values=DraftBusinessRules.asMap(prepared.get("effectiveValues")),identity=DraftBusinessRules.asMap(values.get("contractTitle"));Object number=identity.get("number"),title=identity.get("title");return number instanceof String&&title instanceof String?new Identity((String)number,(String)title):null;}
    static NativeLayoutPlan attach(TemplateIndex index,JsonNode nativeSections,String key,Map<String,Object> prepared,String rendererKind,NativeLayoutPlan body){
        Identity identity=effectiveIdentity(prepared);
        if(identity==null){DraftFormattedRules.addIssue(prepared,key,"NATIVE_STORY_IDENTITY_UNAVAILABLE","The existing effective project identity is unavailable;source headers and footers were preserved.");return body;}
        JsonNode source=null;for(JsonNode row:EDITIONS.path("sources"))if(key.equals(row.path("key").asText())&&index.getSourceSha256().equals(row.path("sourceSha256").asText())){require(source==null,"NATIVE_STORY_EDITION_AMBIGUOUS");source=row;}
        if(source==null){DraftFormattedRules.addIssue(prepared,key,"NATIVE_STORY_SOURCE_EDITION_UNVERIFIED","Native header/footer bindings for this source edition require review;the source stories were preserved.");return body;}
        require(nativeSections!=null&&!nativeSections.isNull(),"NATIVE_SECTION_OBSERVATION_REQUIRED");
        StaticFooterSourceFormat format=null;Map<String,Integer> edges=new LinkedHashMap<>();JsonNode styling=source.path("sourceFormatting");
        if(!styling.isMissingNode()&&Objects.equals(rendererKind,styling.path("rendererKind").asText())){format=new StaticFooterSourceFormat(styling.path("stylesPartSha256").asText(),styling.path("headerStyleId").asText(),styling.path("footerStyleId").asText(),styling.path("emptyFooterPartName").asText(null),styling.path("emptyFooterPartSha256").asText(null),styling.hasNonNull("contractNumberHalfPoints")?styling.path("contractNumberHalfPoints").asInt():null);styling.path("candidateEdgeDistancesTwips").fields().forEachRemaining(entry->edges.put(entry.getKey(),entry.getValue().asInt()));}
        NativeStoryPlan story=plan(index,nativeSections,identity,edition(source),format,edges);
        JsonNode titles=source.path("sctTitleMigration");
        if(!titles.isMissingNode()&&Objects.equals(rendererKind,titles.path("rendererKind").asText()))try {
            story=story.withSctTitleMigration(sctTitles(index,story,titles));
        }catch(IllegalStateException|IllegalArgumentException unsupported){
            DraftFormattedRules.addIssue(prepared,key,"SCT_TITLE_MIGRATION_UNAVAILABLE","The optional SCT heading layout requires review; the original headings and the usable project package were preserved.");
        }
        return (body==null?new NativeLayoutPlan(index.getSourceSha256(),INTEGRATION_VERSION+":story-only",Collections.emptyList(),Collections.emptyList()):body).withStories(story);
    }
    private static SctTitleMigration sctTitles(TemplateIndex index,NativeStoryPlan plan,JsonNode raw) {
        require("source-bound-sct-true-header-v1".equals(raw.path("version").asText())
                &&"absent".equals(raw.path("expectedTitlePg").asText())&&raw.path("generatedTitlePg").asBoolean()
                &&"link_verified_default".equals(raw.path("firstFooterPolicy").asText())
                &&raw.path("candidateFrameTwips").size()==2&&raw.path("candidateFrameTwips").path("top").asInt()==1080
                &&raw.path("candidateFrameTwips").path("header").asInt()==360,"SCT_TITLE_POLICY_UNSUPPORTED");
        int ordinal=raw.path("sectionOrdinal").asInt();require(ordinal>0&&ordinal<=plan.sections.size(),"SCT_TITLE_SECTION_UNAVAILABLE");
        Section section=plan.sections.get(ordinal-1);
        require(section.observedSectionId.equals(raw.path("sectionId").asText())
                &&section.sourceSection.getId().equals(raw.path("sectionAnchorId").asText())
                &&section.sourceSection.getSha256().equals(raw.path("sectionNodeSha256").asText())
                &&section.sourcePosition.nativeNodePath.equals(raw.path("sectionNativeNodePath").asText()),"SCT_TITLE_SECTION_MISMATCH");
        List<SctBodyTitle> titles=new ArrayList<>();
        for(JsonNode title:raw.path("bodyTitles")) {
            Paragraph paragraph=index.mainParagraph(title.path("paragraphOrdinal").asInt());
            require(paragraph.getId().equals(title.path("anchorId").asText())&&paragraph.getSha256().equals(title.path("nodeSha256").asText())
                    &&paragraph.getNativeNodePath().equals(title.path("nativeNodePath").asText())
                    &&Objects.equals(index.getEntryHashes().get(paragraph.getPart()),title.path("partSha256").asText())
                    &&section.sourceSection.getId().equals(paragraph.getSectionId())
                    &&paragraph.getCatalogText().equals(title.path("exactText").asText()),"SCT_TITLE_ANCHOR_MISMATCH");
            titles.add(new SctBodyTitle(paragraph,title.path("exactText").asText(),title.path("preserveSourceColumnControl").asBoolean()));
        }
        require(!titles.isEmpty(),"SCT_TITLE_SCOPE_EMPTY");
        return new SctTitleMigration(section,raw.path("stylesPartSha256").asText(),raw.path("headerStyleId").asText(),
                raw.path("headerStyleNodeSha256").asText(),titles,raw.path("plainDonorOrdinal").asInt(),raw.path("continuedDonorOrdinal").asInt());
    }

    private static VerifiedEdition edition(JsonNode source){
        Map<String,String> hashes=new LinkedHashMap<>();source.path("expectedEntryHashes").fields().forEachRemaining(entry->hashes.put(entry.getKey(),entry.getValue().asText()));List<String> controls=new ArrayList<>();source.path("sourceControlLinesToRetain").forEach(node->controls.add(node.asText()));
        List<SectionPolicy> policies=new ArrayList<>();for(JsonNode section:source.path("sections")){JsonNode numbering=section.path("sourceNumberingDiagnostic");policies.add(new SectionPolicy(PageAction.valueOf(section.path("footerPagePolicy").asText()),section.path("pagePrefix").asText(null),section.path("pageSuffix").asText(null),"PRESERVE".equals(numbering.path("action").asText())?null:numbering.path("nextRaw").path("start").asText(null),controls));}
        List<LabelSpec> labels=new ArrayList<>();for(JsonNode label:source.path("contractLabelSpecs"))labels.add(new LabelSpec(label.path("partName").asText(),label.path("partSha256").asText(),label.path("paragraphOrdinal").asInt(),label.path("exactCatalogText").asText(),label.path("start").asInt(),label.path("end").asInt(),label.path("expectedText").asText()));
        List<StaticGroupSpec> groups=new ArrayList<>();for(JsonNode group:source.path("staticFooterMigration")){JsonNode a=group.path("paragraphs").get(0),b=group.path("paragraphs").get(1);groups.add(new StaticGroupSpec(group.path("pageOrdinal").asInt(),a.path("ordinal").asInt(),a.path("exactText").asText(),b.path("ordinal").asInt(),b.path("exactText").asText()));}
        return new VerifiedEdition(source.path("sourceSha256").asText(),source.path("profileId").asText(),hashes,policies,labels,groups);
    }

    private static Binding binding(TemplateIndex index, JsonNode raw) {
        Kind kind; Variant variant; BindingState state;
        try {
            kind = Kind.valueOf(raw.path("kind").asText().toUpperCase(Locale.ROOT));
            variant = Variant.valueOf(raw.path("variant").asText().toUpperCase(Locale.ROOT));
            state = BindingState.valueOf(raw.path("status").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) { throw unsupported("NATIVE_BINDING_UNSUPPORTED"); }
        if (state == BindingState.NO_TARGET) {
            require(raw.path("targetPart").isNull() && !raw.hasNonNull("relationship"), "ABSENT_SLOT_HAS_TARGET");
            return new Binding(kind, variant, state, null, null, null, null, null, null, null, null, null, null, null);
        }
        JsonNode target = raw.path("targetPart"), relationship = raw.path("relationship");
        String part = target.path("partName").asText(), partHash = target.path("partSha256").asText();
        require(!part.isEmpty() && partHash.equals(index.getEntryHashes().get(part)), "STORY_PART_HASH_MISMATCH");
        require(part.matches("word/" + (kind == Kind.HEADER ? "header" : "footer") + "[0-9]+\\.xml"), "STORY_PART_UNSUPPORTED");
        String id = relationship.path("id").asText(), rawId = raw.path("originReference").path("rawRelationshipId").asText();
        require(!id.isEmpty() && id.equals(rawId), "RELATIONSHIP_ID_MISMATCH");
        String relationType = relationship.path("type").asText();
        require(relationType.endsWith("/" + kind.name().toLowerCase(Locale.ROOT)), "RELATIONSHIP_TYPE_MISMATCH");
        String mode = relationship.path("rawTargetMode").isNull() ? null : relationship.path("rawTargetMode").asText();
        require(mode == null || "Internal".equals(mode), "EXTERNAL_STORY_UNSUPPORTED");
        Position origin = position(index, raw.path("originReference").path("sourcePosition"));
        Position relationAt = position(index, relationship.path("sourcePosition"));
        return new Binding(kind, variant, state, part, partHash, target.path("rootNodePath").asText(), id,
                relationType, relationship.path("rawTarget").asText(), mode, raw.path("originSectionId").asText(),
                raw.path("inheritedFromSectionId").isNull() ? null : raw.path("inheritedFromSectionId").asText(), origin, relationAt);
    }
    private static Position position(TemplateIndex index, JsonNode raw) {
        String source = raw.path("sourceSha256").asText(), part = raw.path("partName").asText();
        String partHash = raw.path("partSha256").asText(), path = raw.path("nodePath").asText();
        require(source.equals(index.getSourceSha256()) && partHash.equals(index.getEntryHashes().get(part)) && !path.isEmpty(), "NATIVE_SOURCE_POSITION_MISMATCH");
        return new Position(source, part, partHash, path);
    }
    private static NodeAnchor sectionAnchor(TemplateIndex index, Position at) {
        require(MAIN.equals(at.partName), "SECTION_PART_UNSUPPORTED"); NodeAnchor found = null;
        for (NodeAnchor anchor : index.getAnchors())
            if (MAIN.equals(anchor.getPart()) && "sectPr".equals(anchor.getKind())
                    && at.nativeNodePath.equals(anchor.getNativeNodePath())) {
                require(found == null, "AMBIGUOUS_SECTION_ANCHOR"); found = anchor;
            }
        require(found != null, "SECTION_ANCHOR_NOT_FOUND"); return found;
    }
    private static NativeProperty property(TemplateIndex index, JsonNode raw, String name) {
        require(raw.isObject() && raw.path("present").isBoolean() && raw.path("observations").isArray(), "NATIVE_SECTION_PROPERTIES_NOT_OBSERVED");
        String status = raw.path("status").asText();
        boolean observed = "titlePg".equals(name) || "evenAndOddHeaders".equals(name)
                ? "observed".equals(status) : "observed_raw_declaration".equals(status);
        require(observed || "absent".equals(status), "NATIVE_PROPERTY_UNSUPPORTED");
        List<PropertyOccurrence> occurrences = new ArrayList<>();
        for (JsonNode occurrence : raw.path("observations")) {
            Map<String, String> attributes = new LinkedHashMap<>();
            occurrence.path("rawAttributes").fields().forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().asText()));
            occurrences.add(new PropertyOccurrence(attributes, position(index, occurrence.path("sourcePosition"))));
        }
        boolean present = raw.path("present").asBoolean();
        require(present ? occurrences.size() == 1 && observed : occurrences.isEmpty() && "absent".equals(status), "NATIVE_PROPERTY_CARDINALITY_MISMATCH");
        return new NativeProperty(name, status, present, occurrences);
    }
    private static boolean on(NativeProperty property) {
        if (!property.present) return false;
        String raw = singleAttributes(property).get(W + "val");
        require(raw == null || Arrays.asList("true", "1", "on", "false", "0", "off").contains(raw), "NATIVE_ON_OFF_VALUE_UNSUPPORTED");
        return raw == null || Arrays.asList("true", "1", "on").contains(raw);
    }
    private static Map<String, String> singleAttributes(NativeProperty property) {
        require(property.observations.size() == 1, "NATIVE_SINGLE_PROPERTY_REQUIRED"); return property.observations.get(0).rawAttributes;
    }
    private static Map<String, List<Label>> labels(TemplateIndex index, List<LabelSpec> specs) {
        Map<String, List<Label>> result = new LinkedHashMap<>();
        for (LabelSpec spec : specs) {
            require(spec.partSha256.equals(index.getEntryHashes().get(spec.part)), "LABEL_PART_HASH_MISMATCH");
            Paragraph found = null;
            for (Paragraph p : index.getParagraphs()) if (spec.part.equals(p.getPart()) && spec.paragraphOrdinal == p.getOrdinal()) {
                require(found == null, "AMBIGUOUS_LABEL_PARAGRAPH"); found = p;
            }
            require(found != null && spec.exactCatalogText.equals(found.getCatalogText()), "LABEL_CATALOG_MISMATCH");
            require(spec.start >= 0 && spec.end <= found.getCatalogText().length() && spec.start < spec.end
                    && spec.expectedText.equals(found.getCatalogText().substring(spec.start, spec.end)), "LABEL_SPAN_MISMATCH");
            result.computeIfAbsent(spec.part, p -> new ArrayList<>()).add(new Label(found, spec.start, spec.end, spec.expectedText));
        }
        return result;
    }
    private static List<StaticFooterGroup> staticFooters(TemplateIndex index, List<StaticGroupSpec> specs) {
        List<StaticFooterGroup> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); int page = 0;
        for (StaticGroupSpec spec : specs) {
            require(spec.pageOrdinal == ++page && spec.secondOrdinal == spec.firstOrdinal + 1, "STATIC_FOOTER_SEQUENCE_MISMATCH");
            Paragraph first = index.mainParagraph(spec.firstOrdinal), second = index.mainParagraph(spec.secondOrdinal);
            require(spec.firstText.equals(first.getCatalogText()) && spec.secondText.equals(second.getCatalogText()), "STATIC_FOOTER_TEXT_MISMATCH");
            require(seen.add(first.getId()) && seen.add(second.getId()), "STATIC_FOOTER_DUPLICATE");
            result.add(new StaticFooterGroup(spec.pageOrdinal, Arrays.asList(new StaticFooterParagraph(first, spec.firstText), new StaticFooterParagraph(second, spec.secondText))));
        }
        return result;
    }
    private static void rejectConflictingSharedParts(NativeStoryPlan plan) {
        Map<String, String> intents = new LinkedHashMap<>();
        for (Section section : plan.sections) for (Story story : section.stories) {
            String part = story.expectedBinding.targetPartName;
            if (part == null || story.action == StoryAction.PRESERVE) continue;
            String fingerprint = story.action + ":" + story.pageAction + ":" + story.pagePrefix + ":" + story.pageSuffix + ":" + story.retainedSourceControlLines;
            String prior = intents.putIfAbsent(part, fingerprint);
            require(prior == null || prior.equals(fingerprint), "SHARED_STORY_INTENT_CONFLICT");
        }
    }
    private static void require(boolean condition, String code) { if (!condition) throw unsupported(code); }
    private static IllegalStateException unsupported(String code) { return new IllegalStateException(code); }
}
