package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** Public, synthetic package tests: private originals and renderers are not prerequisites. */
class DraftNativeSourceStyleFormattingCandidateTest {
    private static final String STYLES="word/styles.xml";
    private static final String SOURCE_STYLES="<w:styles xmlns:w=\""+W+"\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"a\"><w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"Source Font\" w:hAnsi=\"Source Font\"/><w:sz w:val=\"24\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"a3\"><w:basedOn w:val=\"a\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"a5\"><w:basedOn w:val=\"a\"/><w:pPr><w:tabs><w:tab w:val=\"center\" w:pos=\"4320\"/><w:tab w:val=\"right\" w:pos=\"8640\"/></w:tabs></w:pPr></w:style></w:styles>";
    private static final String PG_MAR="<w:pgMar w:top=\"720\" w:right=\"1440\" w:bottom=\"720\" w:left=\"1584\" w:header=\"735\" w:footer=\"731\" w:gutter=\"0\"/>";

    @Test void formattedStaticMigrationUsesOwnA3A5AndTwoLineInlinePage() throws Exception {
        Case source=source(SOURCE_STYLES,PG_MAR);NativeStoryPlan plan=source.plan(source.format(),Collections.emptyMap());DocxEditResult result=source.apply(plan);
        Map<String,byte[]> after=unzip(result.getDocxBytes());unchanged(source.parts,after,new HashSet<>(Arrays.asList(MAIN,RELS,TYPES)));
        assertArrayEquals(source.parts.get(STYLES),after.get(STYLES));
        NativeStoryChange header=applied(result,Kind.HEADER),footer=applied(result,Kind.FOOTER);
        Document h=parse(after.get(header.getGeneratedPartName())),f=parse(after.get(footer.getGeneratedPartName()));
        assertEquals(1,elements(h,W,"p").size());assertEquals("a3",elements(h,W,"pStyle").get(0).getAttributeNS(W,"val"));
        assertEquals(ID.title.replace("\r\n","\n"),text(h));
        List<Element> paragraphs=elements(f,W,"p");assertEquals(2,paragraphs.size());
        assertEquals("a5",elements(f,W,"pStyle").get(0).getAttributeNS(W,"val"));assertEquals("a5",elements(f,W,"pStyle").get(1).getAttributeNS(W,"val"));
        assertEquals(2,elements(f,W,"tab").size());assertEquals(1,elements(f,W,"fldSimple").size());assertEquals("PAGE",elements(f,W,"fldSimple").get(0).getAttributeNS(W,"instr").trim());
        assertEquals(ID.number+"\tHD(QS) 3C/121/2/10\tNTT/",visible(paragraphs.get(0)));assertEquals("March 2020",visible(paragraphs.get(1)));
        assertEquals(0,elements(h,W,"rPr").size());assertEquals(0,elements(f,W,"rPr").size(),"No invented direct font/size");
        for(NativeStoryChange row:Arrays.asList(header,footer)){assertNotNull(row.getSourceFormatting());assertEquals(source.index.getEntryHashes().get(STYLES),row.getSourceFormatting().stylesPartSha256);assertEquals(result.getIndex().getEntryHashes().get(row.getGeneratedPartName()),row.getGeneratedPartSha256());}
        assertEquals(2,result.getNativeStoryLedger().stream().filter(r->"CLEAR_EXACT_STATIC_FOOTER".equals(r.getAction())).count());
        assertEquals(Arrays.asList("Keep","","","Tail"),catalog(result.getIndex()));
        assertEquals(attributes(elements(parse(source.parts.get(MAIN)),W,"pgMar").get(0)),attributes(elements(parse(after.get(MAIN)),W,"pgMar").get(0)));
        assertEquals(attributes(elements(parse(source.parts.get(MAIN)),W,"pgSz").get(0)),attributes(elements(parse(after.get(MAIN)),W,"pgSz").get(0)));
    }

    @Test void invalidFormatStyleBindingFailsBeforeAnyOutput() throws Exception {
        Case valid=source(SOURCE_STYLES,PG_MAR);NativeStoryPlan original=valid.plan(valid.format(),Collections.emptyMap());
        assertCode("STORY_SOURCE_STYLE_PART_MISMATCH",()->valid.apply(withFormat(original,new StaticFooterSourceFormat("0".repeat(64),"a3","a5"))));
        assertEquals("SOURCE_STYLE_PART_HASH_MISMATCH",assertThrows(IllegalStateException.class,()->valid.plan(new StaticFooterSourceFormat("0".repeat(64),"a3","a5"),Collections.emptyMap())).getMessage());
        Case missing=source(SOURCE_STYLES.replace("w:styleId=\"a3\"","w:styleId=\"other\""),PG_MAR);assertCode("STORY_SOURCE_STYLE_UNSUPPORTED",()->missing.apply(missing.plan(missing.format(),Collections.emptyMap())));
        Case duplicate=source(SOURCE_STYLES.replace("</w:styles>","<w:style w:type=\"paragraph\" w:styleId=\"a5\"/></w:styles>"),PG_MAR);assertCode("STORY_SOURCE_STYLE_AMBIGUOUS",()->duplicate.apply(duplicate.plan(duplicate.format(),Collections.emptyMap())));
        Case wrongType=source(SOURCE_STYLES.replace("w:type=\"paragraph\" w:styleId=\"a5\"","w:type=\"character\" w:styleId=\"a5\""),PG_MAR);assertCode("STORY_SOURCE_STYLE_UNSUPPORTED",()->wrongType.apply(wrongType.plan(wrongType.format(),Collections.emptyMap())));
        Case noStyles=source(null,PG_MAR);assertEquals("SOURCE_STYLE_PART_HASH_MISMATCH",assertThrows(IllegalStateException.class,()->noStyles.plan(new StaticFooterSourceFormat("0".repeat(64),"a3","a5"),Collections.emptyMap())).getMessage());
        Case orphanStyles=new Case(valid);assertCode("STORY_SOURCE_STYLE_BINDING_UNSUPPORTED",()->orphanStyles.apply(orphanStyles.plan(orphanStyles.format(),Collections.emptyMap())));
    }

    @Test void compactFormatRejectsExistingTargetsAndNonStaticPolicies() throws Exception {
        Fixture existing=migration("");Case loaded=new Case(existing,SOURCE_STYLES);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->loaded.apply(loaded.plan(loaded.format(),Collections.emptyMap())));
        Fixture plain=new Fixture(p("Body")+"<w:sectPr>"+PG_MAR+"</w:sectPr>","",Collections.emptyMap(),"");plain.policies.add(policy(PageAction.PRESERVE_NO_PAGE));Case absentMigration=new Case(plain,SOURCE_STYLES);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->absentMigration.apply(absentMigration.plan(absentMigration.format(),Collections.emptyMap())));
        Fixture inherited=shared();inherited.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));inherited.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));Case sharing=new Case(inherited,SOURCE_STYLES);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->sharing.apply(sharing.plan(sharing.format(),Collections.emptyMap())));
        Case valid=source(SOURCE_STYLES,PG_MAR);NativeStoryPlan base=valid.plan(valid.format(),Collections.emptyMap());Section s=base.sections.get(0);List<Story> stories=new ArrayList<>();
        for(Story story:s.stories)stories.add(story.expectedBinding.kind==Kind.FOOTER?new Story(story.expectedBinding,StoryAction.PRESERVE,PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,story.contractLabels,story.retainedSourceControlLines,story.pagePrefix,story.pageSuffix):story);
        Section invalid=new Section(s.observedSectionId,s.sourceSection,s.sourcePosition,s.titlePg,s.pgNumType,s.pgMar,s.pgSz,s.type,stories,s.numberingChange,s.marginChange);
        NativeStoryPlan preserved=new NativeStoryPlan(base.sourceSha256,base.profile,base.identity,base.evenAndOddHeaders,Collections.singletonList(invalid),base.staticFooterGroups,base.staticFooterSourceFormat);
        assertCode("STORY_STATIC_CARRIER_REQUIRED",()->valid.apply(preserved));
    }

    @Test void headerFooterDistancesAreTheOnlyFormatMarginChangesAndLedgerKeepsCompleteOriginals() throws Exception {
        Case source=source(SOURCE_STYLES,PG_MAR);Map<String,Integer> edges=new LinkedHashMap<>();edges.put("header",168);edges.put("footer",168);NativeStoryPlan plan=source.plan(source.format(),edges);DocxEditResult result=source.apply(plan);
        NativeStoryChange change=result.getNativeStoryLedger().stream().filter(r->"EXPLICIT_SOURCE_BOUND_MARGIN_CHANGE".equals(r.getAction())).findFirst().orElseThrow(AssertionError::new);
        assertSame(plan.sections.get(0).pgMar,change.getOriginalProperty());assertEquals(7,change.getOriginalProperty().observations.get(0).rawAttributes.size());assertNotNull(change.getOriginalProperty().observations.get(0).sourcePosition);assertEquals(Map.of("header","168","footer","168"),change.getNewAttributes());
        Map<String,String> expected=new LinkedHashMap<>(attributes(elements(parse(source.parts.get(MAIN)),W,"pgMar").get(0)));expected.put("{"+W+"}header","168");expected.put("{"+W+"}footer","168");assertEquals(expected,attributes(elements(parse(unzip(result.getDocxBytes()).get(MAIN)),W,"pgMar").get(0)));
        assertEquals("BODY_MARGIN_CHANGE_UNSUPPORTED",assertThrows(IllegalStateException.class,()->source.plan(source.format(),Map.of("top",1))).getMessage());
        Section s=plan.sections.get(0);Section malicious=new Section(s.observedSectionId,s.sourceSection,s.sourcePosition,s.titlePg,s.pgNumType,s.pgMar,s.pgSz,s.type,s.stories,s.numberingChange,new MarginChange(s.pgMar,Map.of("bottom",1),plan.profile));
        NativeStoryPlan bad=new NativeStoryPlan(plan.sourceSha256,plan.profile,plan.identity,plan.evenAndOddHeaders,Collections.singletonList(malicious),plan.staticFooterGroups,plan.staticFooterSourceFormat);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->source.apply(bad));
        Case absent=source(SOURCE_STYLES,"");assertEquals("SOURCE_EDGE_MARGIN_NOT_OBSERVED",assertThrows(IllegalStateException.class,()->absent.plan(absent.format(),edges)).getMessage());
        Case duplicateMargins=source(SOURCE_STYLES,PG_MAR+PG_MAR);assertEquals("NATIVE_PROPERTY_UNSUPPORTED",assertThrows(IllegalStateException.class,()->duplicateMargins.plan(duplicateMargins.format(),edges)).getMessage());
        Map<String,String> wrong=new LinkedHashMap<>(s.pgMar.observations.get(0).rawAttributes);wrong.put("{"+W+"}header","736");NativeProperty stale=new NativeProperty("pgMar",s.pgMar.status,true,Collections.singletonList(new PropertyOccurrence(wrong,s.pgMar.observations.get(0).sourcePosition)));
        Section staleSection=new Section(s.observedSectionId,s.sourceSection,s.sourcePosition,s.titlePg,s.pgNumType,stale,s.pgSz,s.type,s.stories,s.numberingChange,new MarginChange(stale,edges,plan.profile));NativeStoryPlan stalePlan=new NativeStoryPlan(plan.sourceSha256,plan.profile,plan.identity,plan.evenAndOddHeaders,Collections.singletonList(staleSection),plan.staticFooterGroups,plan.staticFooterSourceFormat);assertCode("STORY_PROPERTY_VALUE_MISMATCH",()->source.apply(stalePlan));
    }

    private static Case source(String styles,String margins) throws Exception {
        Fixture f=new Fixture(p("Keep")+p("HD(QS) 3C/121/2/10- NTT/1 -")+p("March 2020")+p("Tail")+"<w:sectPr><w:pgSz w:w=\"11909\" w:h=\"16834\"/>"+margins+"</w:sectPr>","",Collections.emptyMap(),"");
        f.policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,"NTT/",null,null,Arrays.asList("HD(QS) 3C/121/2/10","March 2020")));f.groups.add(new DraftNativeIdentity.StaticGroupSpec(1,2,"HD(QS) 3C/121/2/10- NTT/1 -",3,"March 2020"));return new Case(f,styles);
    }
    private static final class Case {
        final Map<String,byte[]> parts;final byte[] bytes;final TemplateIndex index;final JsonNode observation;final DraftNativeIdentity.VerifiedEdition edition;
        Case(Fixture fixture,String styles) throws Exception {parts=new LinkedHashMap<>(fixture.parts);if(styles!=null){parts.put(STYLES,styles.getBytes(StandardCharsets.UTF_8));parts.put(TYPES,new String(parts.get(TYPES),StandardCharsets.UTF_8).replace("</Types>","<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));parts.put(RELS,new String(parts.get(RELS),StandardCharsets.UTF_8).replace("</Relationships>",relation("rIdStyles","styles","styles.xml")+"</Relationships>").getBytes(StandardCharsets.UTF_8));}validateFixturePackage(parts);bytes=zip(parts);index=EDITOR.inspect(bytes);observation=observe(bytes);edition=new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(),"synthetic-native-story",index.getEntryHashes(),fixture.policies,fixture.labels,fixture.groups);}
        Case(Case source) throws Exception {parts=new LinkedHashMap<>(source.parts);parts.put(RELS,new String(parts.get(RELS),StandardCharsets.UTF_8).replace(relation("rIdStyles","styles","styles.xml"),"").getBytes(StandardCharsets.UTF_8));validateFixturePackage(parts);bytes=zip(parts);index=EDITOR.inspect(bytes);observation=observe(bytes);edition=new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(),"synthetic-native-story",index.getEntryHashes(),source.edition.sections,source.edition.labels,source.edition.staticFooters);}
        StaticFooterSourceFormat format(){return new StaticFooterSourceFormat(index.getEntryHashes().get(STYLES),"a3","a5");}
        NativeStoryPlan plan(StaticFooterSourceFormat format,Map<String,Integer> edges){return DraftNativeIdentity.plan(index,observation,ID,edition,format,edges);}
        DocxEditResult apply(NativeStoryPlan plan){return EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,new NativeLayoutPlan(index.getSourceSha256(),"synthetic-native-story",Collections.emptyList(),Collections.emptyList()).withStories(plan));}
    }
    private static NativeStoryPlan withFormat(NativeStoryPlan p,StaticFooterSourceFormat f){return new NativeStoryPlan(p.sourceSha256,p.profile,p.identity,p.evenAndOddHeaders,p.sections,p.staticFooterGroups,f);}
    private static NativeStoryChange applied(DocxEditResult result,Kind kind){return result.getNativeStoryLedger().stream().filter(r->r.getOriginalBinding()!=null&&r.getOriginalBinding().kind==kind&&"APPLIED".equals(r.getStatus())).findFirst().orElseThrow(AssertionError::new);}
    private static List<String> catalog(TemplateIndex index){List<String> values=new ArrayList<>();for(Paragraph p:index.getMainParagraphs())values.add(p.getCatalogText());return values;}
    private static Map<String,String> attributes(Element e){Map<String,String> values=new LinkedHashMap<>();NamedNodeMap raw=e.getAttributes();for(int i=0;i<raw.getLength();i++){Node a=raw.item(i);if(!"http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI()))values.put("{"+(a.getNamespaceURI()==null?"":a.getNamespaceURI())+"}"+a.getLocalName(),a.getNodeValue());}return values;}
}
