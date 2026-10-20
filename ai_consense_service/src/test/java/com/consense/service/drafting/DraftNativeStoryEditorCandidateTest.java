package com.consense.service.drafting;

import com.consense.config.ConsenseProperties;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.DocumentParser;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Core synthetic checks have no private path, heap or output prerequisites. Prepared, not run. */
class DraftNativeStoryEditorCandidateTest {
    static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String P="http://schemas.openxmlformats.org/package/2006/relationships";
    static final String C="http://schemas.openxmlformats.org/package/2006/content-types";
    static final String MAIN="word/document.xml",RELS="word/_rels/document.xml.rels",TYPES="[Content_Types].xml";
    static final Identity ID=new Identity("CONTRACT-42", "Project title <>& \uD83D\uDE80\r\nNext\tline");
    static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    static final String PAGE="<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>12</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";

    @Test void missingStoriesUseUniquePartsRelationshipsAndOverridesAndLeaveOtherEntriesByteEqual() throws Exception {
        Map<String,String> stories=new LinkedHashMap<>();stories.put("word/header1.xml",story("hdr",p("Orphan header")));stories.put("word/footer2.xml",story("ftr",p("Unreferenced preserved footer")));
        Fixture f=new Fixture(p("Body")+"<w:sectPr/>",relation("rId1","customXml","../customXml/item1.xml")+relation("rId2","footer","footer2.xml"),stories,"<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>");
        f.policies.add(policy(PageAction.PRESERVE_NO_PAGE));DocxEditResult result=f.apply();Map<String,byte[]> after=unzip(result.getDocxBytes());
        assertTrue(after.containsKey("word/header2.xml"));assertTrue(after.containsKey("word/footer3.xml"));assertArrayEquals(f.parts.get("word/footer2.xml"),after.get("word/footer2.xml"));
        assertArrayEquals(f.parts.get("word/header1.xml"),after.get("word/header1.xml"));
        assertEquals(ID.title.replace("\r\n","\n"),text(parse(after.get("word/header2.xml"))));
        assertEquals(ID.number,text(parse(after.get("word/footer3.xml"))));
        Document main=parse(after.get(MAIN)),rels=parse(after.get(RELS)),types=parse(after.get(TYPES));Set<String> ids=new HashSet<>();
        for(Element rel:elements(rels,P,"Relationship"))assertTrue(ids.add(rel.getAttribute("Id")));
        for(String kind:Arrays.asList("header","footer")) {
            Element ref=elements(main,W,kind+"Reference").get(0);String id=ref.getAttributeNS(R,"id");assertNotEquals("rId1",id);
            Element rel=elements(rels,P,"Relationship").stream().filter(e->id.equals(e.getAttribute("Id"))).findFirst().orElseThrow(AssertionError::new);
            String expected=kind+("header".equals(kind)?"2":"3")+".xml";assertEquals(R+"/"+kind,rel.getAttribute("Type"));assertEquals(expected,rel.getAttribute("Target"));
            assertEquals(1,elements(types,C,"Override").stream().filter(e->("/word/"+expected).equals(e.getAttribute("PartName"))).count());
        }
        unchanged(f.parts,after,new HashSet<>(Arrays.asList(MAIN,RELS,TYPES)));
        assertEquals(6,result.getNativeStoryLedger().size());assertFalse(result.getNativeStoryLedger().stream().anyMatch(e->e.getGeneratedPartName()!=null&&e.getGeneratedPartSha256()==null));
        assertEquals(0,elements(parse(after.get("word/footer3.xml")),W,"fldSimple").size());
    }

    @Test void existingLogoHeadingsPageAndNumPagesFieldsAndCachesSurviveIdentityChanges() throws Exception {
        Fixture f=loaded();f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.label("word/footer1.xml",1,0,8,"20250101");
        DocxEditResult result=f.apply();Map<String,byte[]> after=unzip(result.getDocxBytes());Document header=parse(after.get("word/header1.xml")),footer=parse(after.get("word/footer1.xml"));
        assertEquals("Standard SCT.C",elements(header,W,"p").get(0).getTextContent());assertEquals(1,elements(header,W,"drawing").size());
        assertEquals("CONTRACT-42SCT.C/1230",text(footer));
        assertEquals(" PAGE ",elements(footer,W,"instrText").get(0).getTextContent());assertEquals(" NUMPAGES ",elements(footer,W,"fldSimple").get(0).getAttributeNS(W,"instr"));
        assertEquals("12",elements(footer,W,"t").get(2).getTextContent());assertEquals("30",elements(footer,W,"fldSimple").get(0).getTextContent());
        assertEquals("right",elements(header,W,"jc").get(0).getAttributeNS(W,"val"));assertEquals(2,elements(footer,W,"b").size());
        unchanged(f.parts,after,new HashSet<>(Arrays.asList("word/header1.xml","word/footer1.xml")));
    }

    @Test void forgedInternallyConsistentRelationshipAndTargetEvidenceFailsIndependentCapture() throws Exception {
        Map<String,String> stories=new LinkedHashMap<>();stories.put("word/footer1.xml",story("ftr",p("20250101")));stories.put("word/footer2.xml",story("ftr",p("Other")));
        Fixture f=new Fixture(p("Body")+"<w:sectPr>"+reference("footer","rId8")+"</w:sectPr>",relation("rId8","footer","footer1.xml")+relation("rId9","footer","footer2.xml"),stories,"");f.policies.add(policy(PageAction.PRESERVE_NO_PAGE));
        ObjectNode raw=(ObjectNode)f.evidence.path("sections").get(0).path("headerFooterBindings").get(3);
        ((ObjectNode)raw.path("originReference")).put("rawRelationshipId","rId9");ObjectNode relation=(ObjectNode)raw.path("relationship");relation.put("id","rId9");relation.put("rawTarget","footer2.xml");
        ((ObjectNode)relation.path("sourcePosition")).put("nodePath",relation.path("sourcePosition").path("nodePath").asText().replace("Relationship[1]","Relationship[2]"));
        ((ObjectNode)raw.path("targetPart")).put("partName","word/footer2.xml").put("partSha256",f.index.getEntryHashes().get("word/footer2.xml"));
        NativeStoryPlan plan=f.plan();assertCode("STORY_REFERENCE_ID_MISMATCH",()->f.apply(plan,Collections.emptyList()));
    }

    @Test void sharedTargetIsChangedOnceAndConflictingPolicyIsRejected() throws Exception {
        Fixture f=shared();f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.label("word/footer1.xml",1,0,8,"20250101");
        DocxEditResult result=f.apply();String joined=text(parse(unzip(result.getDocxBytes()).get("word/header1.xml")));
        assertEquals(1,count(joined,"Project title"));assertTrue(result.getNativeStoryLedger().stream().anyMatch(e->"SHARED_TARGET_ALREADY_APPLIED".equals(e.getStatus())));
        Fixture conflict=shared();conflict.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));conflict.policies.add(policy(PageAction.PRESERVE_NO_PAGE));
        assertEquals("SHARED_STORY_INTENT_CONFLICT",assertThrows(IllegalStateException.class,conflict::plan).getMessage());
        NativeStoryPlan original=f.plan();List<Section> sections=new ArrayList<>();int ordinal=0;
        for(Section section:original.sections){List<Story> intents=new ArrayList<>();for(Story story:section.stories)intents.add("word/footer1.xml".equals(story.expectedBinding.targetPartName)?new Story(story.expectedBinding,story.action,story.pageAction,story.contractLabels,story.retainedSourceControlLines,ordinal==0?"a|b":"a",ordinal==0?"c":"b|c"):story);sections.add(new Section(section.observedSectionId,section.sourceSection,section.sourcePosition,section.titlePg,section.pgNumType,section.pgMar,section.pgSz,section.type,intents,section.numberingChange,section.marginChange));ordinal++;}
        NativeStoryPlan forged=new NativeStoryPlan(original.sourceSha256,original.profile,original.identity,original.evenAndOddHeaders,sections,original.staticFooterGroups);assertCode("SHARED_STORY_INTENT_CONFLICT",()->f.apply(forged,Collections.emptyList()));
    }

    @Test void capturedSectionAndStoryAnchorsSurviveEarlierBusinessParagraphDeletion() throws Exception {
        Fixture f=new Fixture(p("Remove this")+p("Keep this")+"<w:sectPr/>","",Collections.emptyMap(),"");f.policies.add(policy(PageAction.PRESERVE_NO_PAGE));NativeStoryPlan plan=f.plan();
        DocxEditResult result=f.apply(plan,Collections.singletonList(Edit.deleteParagraph("remove",f.index.mainParagraph(1))));
        assertEquals("Keep this",result.getIndex().mainParagraph(1).getCatalogText());assertEquals(1,elements(parse(unzip(result.getDocxBytes()).get(MAIN)),W,"sectPr").size());
        assertEquals(1,elements(parse(unzip(result.getDocxBytes()).get(MAIN)),W,"headerReference").size());
    }

    @Test void staticMigrationClearsOnlyExactPairKeepsBreakAndRetainsControlsWithNewPage() throws Exception {
        Fixture f=migration("");NativeStoryPlan plan=f.plan();DocxEditResult result=f.apply(plan,Collections.emptyList());Map<String,byte[]> after=unzip(result.getDocxBytes());
        assertEquals("Keep",result.getIndex().mainParagraph(1).getCatalogText());assertEquals("",result.getIndex().mainParagraph(2).getCatalogText());assertEquals("",result.getIndex().mainParagraph(3).getCatalogText());assertEquals("Tail",result.getIndex().mainParagraph(4).getCatalogText());
        assertEquals(1,elements(parse(after.get(MAIN)),W,"br").size());assertEquals("1",elements(parse(after.get(MAIN)),W,"pgNumType").get(0).getAttributeNS(W,"start"));
        Document footer=parse(after.get("word/footer1.xml"));assertEquals(1,elements(footer,W,"fldSimple").size());assertEquals(" PAGE ",elements(footer,W,"fldSimple").get(0).getAttributeNS(W,"instr"));
        assertTrue(text(footer).contains("HD(QS)"));assertTrue(text(footer).contains("March 2020"));assertTrue(text(footer).contains("SCT/"));
        assertEquals(2,result.getNativeStoryLedger().stream().filter(e->"CLEAR_EXACT_STATIC_FOOTER".equals(e.getAction())&&e.getSourcePageOrdinal()==1&&e.getSourceParagraph()!=null&&e.getOldExactText()!=null).count());
        unchanged(f.parts,after,new HashSet<>(Arrays.asList(MAIN,RELS,TYPES,"word/footer1.xml")));
    }

    @Test void staticClearanceRejectsFieldsBreaksAndDifferentPairBeforeOutput() throws Exception {
        for(String bad:Arrays.asList("<w:r><w:br/></w:r>","<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>","<w:fldSimple w:instr=\"PAGE\"><w:r><w:t/></w:r></w:fldSimple>")) {
            Fixture f=migration(bad);assertCode("STORY_STATIC_CONTROL_UNSUPPORTED",f::apply);
        }
        Fixture f=migration("");NativeStoryPlan plan=f.plan();List<StaticFooterGroup> wrong=Collections.singletonList(new StaticFooterGroup(1,Arrays.asList(plan.staticFooterGroups.get(0).exactParagraphs.get(0),new StaticFooterParagraph(f.index.mainParagraph(4),"Tail"))));
        assertCode("STORY_STATIC_PAIR_MISMATCH",()->f.apply(new NativeStoryPlan(plan.sourceSha256,plan.profile,plan.identity,plan.evenAndOddHeaders,plan.sections,wrong),Collections.emptyList()));
    }

    @Test void coverNoPageAndRomanAppendixResetsRemainSourceDeclarations() throws Exception {
        Map<String,String> stories=new LinkedHashMap<>();stories.put("word/footer1.xml",story("ftr",p("20250101")));stories.put("word/footer2.xml",story("ftr","<w:p><w:r><w:t>20250101</w:t><w:t>SCT.C/</w:t></w:r>"+PAGE+"</w:p>"));
        String body="<w:p><w:pPr><w:sectPr>"+reference("footer","rId8")+"<w:pgNumType w:fmt=\"lowerRoman\" w:start=\"1\"/></w:sectPr></w:pPr><w:r><w:t>Cover</w:t></w:r></w:p>"+p("Appendix")+"<w:sectPr>"+reference("footer","rId9")+"<w:pgNumType w:fmt=\"decimal\" w:start=\"1\"/></w:sectPr>";
        Fixture f=new Fixture(body,relation("rId8","footer","footer1.xml")+relation("rId9","footer","footer2.xml"),stories,"");f.policies.add(policy(PageAction.PRESERVE_NO_PAGE));f.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));f.label("word/footer1.xml",1,0,8,"20250101");f.label("word/footer2.xml",1,0,8,"20250101");
        DocxEditResult result=f.apply();Map<String,byte[]> after=unzip(result.getDocxBytes());assertEquals(0,elements(parse(after.get("word/footer1.xml")),W,"fldChar").size());assertTrue(text(parse(after.get("word/footer2.xml"))).contains("SCT.C/12"));
        List<Element> numbering=elements(parse(after.get(MAIN)),W,"pgNumType");assertEquals("lowerRoman",numbering.get(0).getAttributeNS(W,"fmt"));assertEquals("decimal",numbering.get(1).getAttributeNS(W,"fmt"));for(Element n:numbering)assertEquals("1",n.getAttributeNS(W,"start"));
        assertEquals(0,elements(parse(after.get(MAIN)),W,"titlePg").size());
    }

    @Test void fieldCacheLabelBusinessOverlapAndMalformedFieldsCannotPass() throws Exception {
        Fixture cache=loaded();cache.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));cache.label("word/footer1.xml",1,14,16,"12");assertCode("FIELD_TEXT_EDIT_UNSUPPORTED",cache::apply);
        Fixture overlap=loaded();overlap.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));Paragraph footer=overlap.index.getParagraphs().stream().filter(p->"word/footer1.xml".equals(p.getPart())).findFirst().orElseThrow(AssertionError::new);
        assertCode("STORY_BUSINESS_EDIT_OVERLAP",()->overlap.apply(overlap.plan(),Collections.singletonList(Edit.replaceCatalogLabelOutsideFields("duplicate",footer,0,8,"20250101","C42"))));
        Fixture duplicate=loaded();duplicate.policies.add(policy(PageAction.PRESERVE_EXISTING_PAGE));duplicate.label("word/footer1.xml",1,0,8,"20250101");duplicate.label("word/footer1.xml",1,0,8,"20250101");assertCode("STORY_LABEL_OVERLAP",duplicate::apply);
        Map<String,String> stories=new LinkedHashMap<>();stories.put("word/footer1.xml",story("ftr","<w:p><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"));Fixture malformed=new Fixture(p("Body")+"<w:sectPr>"+reference("footer","rId8")+"</w:sectPr>",relation("rId8","footer","footer1.xml"),stories,"");malformed.policies.add(policy(PageAction.PRESERVE_NO_PAGE));assertCode("STORY_FIELD_SCOPE_UNSUPPORTED",malformed::apply);
    }

    @Test void withStoriesRejectsDifferentSourceAndRetainsExistingBodyLayout() throws Exception {
        Fixture f=new Fixture("<w:p/>"+p("Keep")+"<w:sectPr/>","",Collections.emptyMap(),"");f.policies.add(policy(PageAction.PRESERVE_NO_PAGE));NativeStoryPlan plan=f.plan();NodeAnchor empty=f.index.getAnchors().stream().filter(a->a.getId().equals(f.index.mainParagraph(1).getId())).findFirst().orElseThrow(AssertionError::new);
        NativeLayoutPlan layout=new NativeLayoutPlan(f.index.getSourceSha256(),"body-profile",Collections.emptyList(),Collections.singletonList(empty)).withStories(plan);assertSame(plan,layout.getStories());
        DocxEditResult result=EDITOR.applyWithParagraphBookmarks(f.bytes,new SourceEditBatch(f.index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,layout);assertFalse(result.getNativeLayoutLedger().isEmpty());
        NativeStoryPlan wrong=new NativeStoryPlan("wrong",plan.profile,plan.identity,plan.evenAndOddHeaders,plan.sections,plan.staticFooterGroups);assertCode("STORY_SOURCE_HASH_MISMATCH",()->layout.withStories(wrong));
    }

    static Fixture loaded() throws Exception {Map<String,String> stories=new LinkedHashMap<>();stories.put("word/header1.xml",story("hdr","<w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:t>Standard SCT.C</w:t><w:drawing/></w:r></w:p>"));stories.put("word/footer1.xml",story("ftr","<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>20250101</w:t><w:t>SCT.C/</w:t></w:r>"+PAGE+"<w:fldSimple w:instr=\" NUMPAGES \"><w:r><w:t>30</w:t></w:r></w:fldSimple></w:p>"));return new Fixture(p("Body")+"<w:sectPr>"+reference("header","rId7")+reference("footer","rId8")+"<w:pgNumType w:fmt=\"lowerRoman\" w:start=\"1\"/></w:sectPr>",relation("rId7","header","header1.xml")+relation("rId8","footer","footer1.xml"),stories,"");}
    static Fixture shared() throws Exception {Fixture one=loaded();String section=reference("header","rId7")+reference("footer","rId8");return new Fixture("<w:p><w:pPr><w:sectPr>"+section+"</w:sectPr></w:pPr><w:r><w:t>First</w:t></w:r></w:p>"+p("Second")+"<w:sectPr/>",new String(one.parts.get(RELS),StandardCharsets.UTF_8).replace("<Relationships xmlns=\""+P+"\">","").replace("</Relationships>","").replace(relation("rIdSettings","settings","settings.xml"),""),storyStrings(one.parts),"");}
    static Fixture migration(String control) throws Exception {Map<String,String> stories=new LinkedHashMap<>();stories.put("word/footer1.xml",story("ftr","<w:p/>"));Fixture f=new Fixture(p("Keep")+"<w:p><w:r><w:t>HD(QS)- SCT/1 -</w:t></w:r>"+control+"</w:p>"+p("March 2020")+"<w:p><w:r><w:br w:type=\"page\"/><w:t>Tail</w:t></w:r></w:p><w:sectPr>"+reference("footer","rId8")+"<w:pgNumType w:start=\"17\"/></w:sectPr>",relation("rId8","footer","footer1.xml"),stories,"");f.policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,"SCT/"," -","1",Arrays.asList("HD(QS)","March 2020")));f.groups.add(new DraftNativeIdentity.StaticGroupSpec(1,2,"HD(QS)- SCT/1 -",3,"March 2020"));return f;}
    static DraftNativeIdentity.SectionPolicy policy(PageAction action){return new DraftNativeIdentity.SectionPolicy(action,null,null,null,Collections.emptyList());}
    static String p(String text){return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>";}
    static String story(String local,String body){return "<w:"+local+" xmlns:w=\""+W+"\">"+body+"</w:"+local+">";}
    static String reference(String kind,String id){return "<w:"+kind+"Reference w:type=\"default\" r:id=\""+id+"\"/>";}
    static String relation(String id,String kind,String target){return "<Relationship Id=\""+id+"\" Type=\""+R+"/"+kind+"\" Target=\""+target+"\"/>";}

    static final class Fixture {
        final Map<String,byte[]> parts=new LinkedHashMap<>();final byte[] bytes;final TemplateIndex index;final JsonNode evidence;
        final List<DraftNativeIdentity.SectionPolicy> policies=new ArrayList<>();final List<DraftNativeIdentity.LabelSpec> labels=new ArrayList<>();final List<DraftNativeIdentity.StaticGroupSpec> groups=new ArrayList<>();
        Fixture(String body,String rels,Map<String,String> stories,String extraTypes) throws Exception {StringBuilder types=new StringBuilder("<Types xmlns=\""+C+"\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>");
            for(Map.Entry<String,String> entry:stories.entrySet()){parts.put(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8));types.append("<Override PartName=\"/").append(entry.getKey()).append("\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.").append(entry.getKey().contains("/header")?"header":"footer").append("+xml\"/>");}
            parts.put(TYPES,(types+extraTypes+"</Types>").getBytes(StandardCharsets.UTF_8));parts.put(MAIN,("<w:document xmlns:w=\""+W+"\" xmlns:r=\""+R+"\"><w:body>"+body+"</w:body></w:document>").getBytes(StandardCharsets.UTF_8));parts.put(RELS,("<Relationships xmlns=\""+P+"\">"+rels+relation("rIdSettings","settings","settings.xml")+"</Relationships>").getBytes(StandardCharsets.UTF_8));parts.put("word/settings.xml",("<w:settings xmlns:w=\""+W+"\"/>").getBytes(StandardCharsets.UTF_8));parts.put("_rels/.rels",("<Relationships xmlns=\""+P+"\">"+relation("root","officeDocument","word/document.xml")+"</Relationships>").getBytes(StandardCharsets.UTF_8));parts.put("customXml/item1.xml","<untouched/>".getBytes(StandardCharsets.UTF_8));validateFixturePackage(parts);bytes=zip(parts);index=EDITOR.inspect(bytes);evidence=observe(bytes);assertEquals("observed_settings_part",evidence.path("headerFooterSettings").path("status").asText());assertEquals("absent",evidence.path("headerFooterSettings").path("evenAndOddHeaders").path("status").asText());assertFalse(evidence.path("headerFooterSettings").path("evenAndOddHeaders").path("present").asBoolean());}
        void label(String part,int ordinal,int start,int end,String expected){Paragraph p=index.getParagraphs().stream().filter(q->part.equals(q.getPart())&&q.getOrdinal()==ordinal).findFirst().orElseThrow(AssertionError::new);labels.add(new DraftNativeIdentity.LabelSpec(part,index.getEntryHashes().get(part),ordinal,p.getCatalogText(),start,end,expected));}
        NativeStoryPlan plan(){return DraftNativeIdentity.plan(index,evidence,ID,new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(),"synthetic-native-story",index.getEntryHashes(),policies,labels,groups));}
        DocxEditResult apply(){return apply(plan(),Collections.emptyList());}
        DocxEditResult apply(NativeStoryPlan plan,List<Edit> edits){return EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),edits),Collections.emptyMap(),false,new NativeLayoutPlan(index.getSourceSha256(),"synthetic-native-story",Collections.emptyList(),Collections.emptyList()).withStories(plan));}
    }
    static JsonNode observe(byte[] bytes){return observe(bytes,"NTT","native-story-synthetic.docx");}
    static JsonNode observe(byte[] bytes,String family,String fileName){OcrClient ocr=mock(OcrClient.class);JsonNode result=new DocumentParser(new ConsenseProperties(),ocr).parseTemplate(family,fileName,bytes).getCoverage().getNativeDocxSections();verifyNoInteractions(ocr);return result;}
    static void validateFixturePackage(Map<String,byte[]> parts) throws Exception {
        Document types=parse(parts.get(TYPES));Map<String,String> defaults=new HashMap<>(),overrides=new HashMap<>(),actualTypes=new HashMap<>();
        for(Element e:elements(types,C,"Default")){assertTrue(e.getAttribute("ContentType").contains("/"),"Fixture MIME type");assertNull(defaults.put(e.getAttribute("Extension"),e.getAttribute("ContentType")),"Duplicate fixture Default");}
        for(Element e:elements(types,C,"Override")){assertTrue(e.getAttribute("ContentType").contains("/"),"Fixture MIME type");assertNull(overrides.put(e.getAttribute("PartName"),e.getAttribute("ContentType")),"Duplicate fixture Override");}
        for(String part:parts.keySet())if(!TYPES.equals(part)){String type=overrides.getOrDefault("/"+part,defaults.get(part.substring(part.lastIndexOf('.')+1)));assertNotNull(type,"Fixture content type missing: "+part);actualTypes.put(part,type);if(part.endsWith(".rels"))assertEquals("application/vnd.openxmlformats-package.relationships+xml",type);if(part.endsWith(".xml")||part.endsWith(".rels"))parse(parts.get(part));}
        Map<String,Element> mainRelationships=new HashMap<>();int roots=0;
        for(String part:parts.keySet())if(part.endsWith(".rels")){Set<String> ids=new HashSet<>();for(Element relation:elements(parse(parts.get(part)),P,"Relationship")){assertFalse(relation.getAttribute("Id").isEmpty());assertTrue(ids.add(relation.getAttribute("Id")),"Duplicate fixture rId");assertFalse(relation.getAttribute("Type").isEmpty());if("External".equals(relation.getAttribute("TargetMode")))continue;String owner="_rels/.rels".equals(part)?"/":("/"+part).replace("/_rels/","/").replaceFirst("\\.rels$","");String target=new java.net.URI(owner).resolve(relation.getAttribute("Target")).normalize().getPath().substring(1);assertTrue(parts.containsKey(target),"Dangling internal fixture target: "+target);if(RELS.equals(part))mainRelationships.put(relation.getAttribute("Id"),relation);if("_rels/.rels".equals(part)&&(R+"/officeDocument").equals(relation.getAttribute("Type"))){assertEquals(MAIN,target);roots++;}}}
        assertEquals(1,roots,"One fixture officeDocument relationship");
        Document main=parse(parts.get(MAIN));for(String kind:Arrays.asList("header","footer"))for(Element reference:elements(main,W,kind+"Reference")){Element relation=mainRelationships.get(reference.getAttributeNS(R,"id"));assertNotNull(relation,"Unresolved fixture story reference");assertEquals(R+"/"+kind,relation.getAttribute("Type"));String target=new java.net.URI("/"+MAIN).resolve(relation.getAttribute("Target")).normalize().getPath().substring(1);assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml."+kind+"+xml",actualTypes.get(target));}
    }
    static byte[] zip(Map<String,byte[]> parts) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:parts.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return out.toByteArray();}
    static Map<String,byte[]> unzip(byte[] bytes) throws Exception {Map<String,byte[]> out=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;byte[] b=new byte[8192];while((e=zip.getNextEntry())!=null){ByteArrayOutputStream part=new ByteArrayOutputStream();int n;while((n=zip.read(b))!=-1)part.write(b,0,n);out.put(e.getName(),part.toByteArray());}}return out;}
    static Map<String,String> storyStrings(Map<String,byte[]> parts){Map<String,String> out=new LinkedHashMap<>();parts.forEach((key,value)->{if(key.matches("word/(header|footer)[0-9]+\\.xml"))out.put(key,new String(value,StandardCharsets.UTF_8));});return out;}
    static Document parse(byte[] bytes) throws Exception {DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));}
    static List<Element> elements(Document d,String ns,String name){List<Element> result=new ArrayList<>();NodeList nodes=d.getElementsByTagNameNS(ns,name);for(int i=0;i<nodes.getLength();i++)result.add((Element)nodes.item(i));return result;}
    static String text(Document d){return visible(d.getDocumentElement());}
    static String visible(Node node){if(node instanceof Element&&W.equals(node.getNamespaceURI())){if("t".equals(node.getLocalName()))return node.getTextContent();if("br".equals(node.getLocalName()))return "\n";if("tab".equals(node.getLocalName()))return "\t";}StringBuilder s=new StringBuilder();for(Node c=node.getFirstChild();c!=null;c=c.getNextSibling())s.append(visible(c));return s.toString();}
    static void unchanged(Map<String,byte[]> before,Map<String,byte[]> after,Set<String> changed){for(Map.Entry<String,byte[]> entry:before.entrySet())if(!changed.contains(entry.getKey()))assertArrayEquals(entry.getValue(),after.get(entry.getKey()),entry.getKey());}
    static void assertCode(String code,org.junit.jupiter.api.function.Executable action){assertEquals(code,assertThrows(EditException.class,action).getCode());}
    static int count(String value,String needle){return (value.length()-value.replace(needle,"").length())/needle.length();}
}
