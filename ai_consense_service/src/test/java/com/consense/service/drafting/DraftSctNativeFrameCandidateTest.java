package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** SCT native-frame regressions; no originals, renderer or provider is needed. Prepared, not run. */
class DraftSctNativeFrameCandidateTest {
    private static final String STYLES="word/styles.xml",FOOTER="word/footer1.xml";
    private static final String EMPTY="<w:p><w:pPr><w:pStyle w:val=\"a5\"/><w:rPr><w:szCs w:val=\"16\"/></w:rPr></w:pPr></w:p>";
    private static final String MARGINS="<w:pgMar w:top=\"720\" w:right=\"1440\" w:bottom=\"426\" w:left=\"1440\" w:header=\"720\" w:footer=\"285\" w:gutter=\"0\"/>";
    private static final String SOURCE_STYLES="<w:styles xmlns:w=\""+W+"\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"a\"><w:name w:val=\"Normal\"/><w:rPr><w:sz w:val=\"24\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"a3\"><w:basedOn w:val=\"a\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"a5\"><w:basedOn w:val=\"a\"/><w:pPr><w:tabs><w:tab w:val=\"center\" w:pos=\"4320\"/><w:tab w:val=\"right\" w:pos=\"8640\"/></w:tabs></w:pPr></w:style></w:styles>";

    @Test void compactSctPreservesOriginalEmptyCarrierAndSourceNodesWithTwoVisibleFooterLines() throws Exception {
        Case source=new Case(EMPTY,null,false);NativeStoryPlan plan=source.plan(source.format(),Map.of("header",360));DocxEditResult result=assertDoesNotThrow(()->source.apply(plan),"SCT_FORMATTED_EMPTY_CARRIER_MUST_MIGRATE");
        Map<String,byte[]> after=unzip(result.getDocxBytes());unchanged(source.parts,after,Set.of(MAIN,RELS,TYPES,FOOTER));assertArrayEquals(source.parts.get(STYLES),after.get(STYLES));
        Document footer=parse(after.get(FOOTER));List<Element> paragraphs=elements(footer,W,"p");assertEquals(3,paragraphs.size(),"Original empty paragraph plus two visible source-control lines");
        assertTrue(elements(parse(source.parts.get(FOOTER)),W,"p").get(0).isEqualNode(paragraphs.get(0)),"Preserve every original empty-carrier attribute/property");
        assertEquals(ID.number+"\tHD(QS) 3C/121/1/2\tSCT/",visible(paragraphs.get(1)));assertEquals("March 2020",visible(paragraphs.get(2)));
        assertEquals(1,count(text(footer),ID.number));assertFalse(text(footer).contains(ID.title));assertEquals(2,elements(footer,W,"tab").size());
        assertEquals(1,elements(footer,W,"fldSimple").size());Element page=elements(footer,W,"fldSimple").get(0);assertEquals("PAGE",page.getAttributeNS(W,"instr").trim());assertTrue(elementsIn(page,"sz").isEmpty(),"New PAGE has no direct font-size override");
        List<Element> runs=elementsIn(paragraphs.get(1),"r");assertEquals("20",elementsIn(runs.get(0),"sz").get(0).getAttributeNS(W,"val"),"Only the contract-number run uses source2b SCT10pt");
        for(int i=1;i<runs.size();i++)assertTrue(elementsIn(runs.get(i),"sz").isEmpty(),"Control and new PAGE runs inherit unchanged12pt a5");
        assertEquals("a5",elementsIn(paragraphs.get(1),"pStyle").get(0).getAttributeNS(W,"val"));assertEquals("a5",elementsIn(paragraphs.get(2),"pStyle").get(0).getAttributeNS(W,"val"));
        List<Element> originalBody=elements(parse(source.parts.get(MAIN)),W,"p"),generatedBody=elements(parse(after.get(MAIN)),W,"p");assertEquals(originalBody.size(),generatedBody.size());
        for(int ordinal:new int[]{0,3,4})assertTrue(originalBody.get(ordinal).isEqualNode(generatedBody.get(ordinal)),"Original body/spacer/break remains exact at ordinal"+ordinal);
        assertEquals(1,elements(parse(after.get(MAIN)),W,"br").size());assertEquals(2,result.getNativeStoryLedger().stream().filter(r->"COMPACT_EXACT_CLEARED_STATIC_FOOTER_SPACING".equals(r.getAction())&&"APPLIED".equals(r.getStatus())).count());
    }

    @Test void emptyCarrierRequiresExplicitOptInExactPartHashAndNoOriginalFieldsOrControls() throws Exception {
        Case empty=new Case(EMPTY,null,false);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->empty.apply(empty.plan(new StaticFooterSourceFormat(empty.index.getEntryHashes().get(STYLES),"a3","a5"),Collections.emptyMap())));
        StaticFooterSourceFormat stale=new StaticFooterSourceFormat(empty.index.getEntryHashes().get(STYLES),"a3","a5",FOOTER,"0".repeat(64),20);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->empty.apply(empty.plan(stale,Collections.emptyMap())));
        for(String bad:Arrays.asList("",EMPTY+EMPTY,"<w:p><w:fldSimple w:instr=\" NUMPAGES \"><w:r><w:t/></w:r></w:fldSimple></w:p>","<w:p><w:r><w:br/></w:r></w:p>","<w:p><w:r><w:tab/></w:r></w:p>")) {
            Case controlled=new Case(bad,null,false);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->controlled.apply(controlled.plan(controlled.format(),Collections.emptyMap())));
        }
        Case absent=new Case(null,null,false);StaticFooterSourceFormat unused=new StaticFooterSourceFormat(absent.index.getEntryHashes().get(STYLES),"a3","a5",FOOTER,"f".repeat(64),20);assertEquals("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",assertThrows(EditException.class,()->absent.apply(absent.plan(unused,Collections.emptyMap())),"SCT_UNUSED_EMPTY_CARRIER_OPT_IN_MUST_REJECT").getCode());
        Case nonempty=new Case(p("Original source footer"),null,false);assertCode("STORY_STATIC_MIGRATION_UNSUPPORTED",()->nonempty.apply(nonempty.plan(nonempty.format(),Collections.emptyMap())));
    }

    @Test void headerPlacementPolicyChangesOnlyNewHeaderDistanceAndPreservesOriginalBodyFields() throws Exception {
        Case source=new Case(EMPTY,null,true);NativeStoryPlan plan=source.plan(source.format(),Map.of("header",360));DocxEditResult result=assertDoesNotThrow(()->source.apply(plan),"SCT_NEW_HEADER_POLICY_MUST_PRESERVE_FIELDS");Map<String,byte[]> after=unzip(result.getDocxBytes());
        Document original=parse(source.parts.get(MAIN)),generated=parse(after.get(MAIN));assertTrue(elements(original,W,"p").get(0).isEqualNode(elements(generated,W,"p").get(0)),"Original complete field/cache stays exact in body");
        Element margins=elements(generated,W,"pgMar").get(0);assertEquals("720",margins.getAttributeNS(W,"top"));assertEquals("426",margins.getAttributeNS(W,"bottom"));assertEquals("1440",margins.getAttributeNS(W,"left"));assertEquals("1440",margins.getAttributeNS(W,"right"));assertEquals("285",margins.getAttributeNS(W,"footer"));assertEquals("360",margins.getAttributeNS(W,"header"));
        NativeStoryChange change=result.getNativeStoryLedger().stream().filter(r->"EXPLICIT_SOURCE_BOUND_MARGIN_CHANGE".equals(r.getAction())).findFirst().orElseThrow(AssertionError::new);assertEquals(Map.of("header","360"),change.getNewAttributes());assertEquals("720",change.getOriginalProperty().observations.get(0).rawAttributes.get("{"+W+"}header"));
        NativeStoryChange header=result.getNativeStoryLedger().stream().filter(r->r.getOriginalBinding()!=null&&r.getOriginalBinding().kind==Kind.HEADER&&"APPLIED".equals(r.getStatus())).findFirst().orElseThrow(AssertionError::new);assertEquals(BindingState.NO_TARGET,header.getOriginalBinding().state);Document h=parse(after.get(header.getGeneratedPartName()));assertEquals(ID.title.replace("\r\n","\n"),text(h));assertEquals("a3",elements(h,W,"pStyle").get(0).getAttributeNS(W,"val"));assertTrue(elements(h,W,"sz").isEmpty());
        Case loadedHeader=new Case(EMPTY,"<w:p><w:r><w:t>Original appendix header</w:t></w:r>"+PAGE+"</w:p>",false);assertCode("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",()->loadedHeader.apply(loadedHeader.plan(loadedHeader.format(),Map.of("header",360))));
        assertEquals("BODY_MARGIN_CHANGE_UNSUPPORTED",assertThrows(IllegalStateException.class,()->source.plan(source.format(),Map.of("top",360))).getMessage());
    }

    @Test void configuredOptInUsesExactSctCarrierAndDocumentsNewHeaderPolicyWithoutChangingOtherEditions() throws Exception {
        JsonNode editions;try(InputStream in=getClass().getResourceAsStream("/drafting/native-story-editions.json")){assertNotNull(in);editions=JsonUtils.mapper().readTree(in);}assertEquals(6,editions.path("sources").size());JsonNode sct=null;
        for(JsonNode row:editions.path("sources")){if("native-identity-2a-sct-candidate01".equals(row.path("profileId").asText()))sct=row;else assertFalse(row.path("sourceFormatting").has("emptyFooterPartName"),"No other edition opts into the exact SCT carrier");}
        assertNotNull(sct);assertEquals("7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4",sct.path("sourceSha256").asText());JsonNode format=sct.path("sourceFormatting");assertEquals("c23a2d07ce83bc90596f413a9fbf08ff866adf73215f20b8c37f2ce2bcedf389",format.path("stylesPartSha256").asText());assertEquals(FOOTER,format.path("emptyFooterPartName").asText());assertEquals(sct.path("expectedEntryHashes").path(FOOTER).asText(),format.path("emptyFooterPartSha256").asText());assertEquals(20,format.path("contractNumberHalfPoints").asInt());assertEquals(360,format.path("candidateEdgeDistancesTwips").path("header").asInt());assertFalse(format.path("candidateEdgeDistancesTwips").has("footer"));assertTrue(format.path("generationPolicy").asText().contains("not an observed source header distance"));assertEquals("6d7802ea7b094124c0ddebb0b448c7e6bfe72828ffbc91ad1c30133902a6f64c",format.path("formattingReference").path("sourceSha256").asText());
    }

    private static final class Case {
        final Map<String,byte[]> parts;final byte[] bytes;final TemplateIndex index;final JsonNode observation;final DraftNativeIdentity.VerifiedEdition edition;
        Case(String footer,String header,boolean bodyField) throws Exception {
            Map<String,String> stories=new LinkedHashMap<>();String references="",relationships="";if(footer!=null){stories.put(FOOTER,story("ftr",footer));references=reference("footer","rId8");relationships=relation("rId8","footer","footer1.xml");}if(header!=null){stories.put("word/header1.xml",story("hdr",header));references=reference("header","rId7")+references;relationships=relation("rId7","header","header1.xml")+relationships;}
            String body=(bodyField?"<w:p><w:r><w:t>Keep</w:t></w:r>"+PAGE+"</w:p>":p("Keep"))+p("HD(QS) 3C/121/1/2- SCT/1 -")+p("March 2020")+"<w:p/>"+"<w:p><w:r><w:br w:type=\"page\"/><w:t>Tail</w:t></w:r></w:p><w:sectPr>"+references+"<w:pgSz w:w=\"11909\" w:h=\"16834\"/>"+MARGINS+"<w:pgNumType w:start=\"17\"/></w:sectPr>";
            Fixture fixture=new Fixture(body,relationships,stories,"");fixture.policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,"SCT/",null,"1",Arrays.asList("HD(QS) 3C/121/1/2","March 2020")));fixture.groups.add(new DraftNativeIdentity.StaticGroupSpec(1,2,"HD(QS) 3C/121/1/2- SCT/1 -",3,"March 2020"));parts=new LinkedHashMap<>(fixture.parts);parts.put(STYLES,SOURCE_STYLES.getBytes(StandardCharsets.UTF_8));parts.put(TYPES,new String(parts.get(TYPES),StandardCharsets.UTF_8).replace("</Types>","<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));parts.put(RELS,new String(parts.get(RELS),StandardCharsets.UTF_8).replace("</Relationships>",relation("rIdStyles","styles","styles.xml")+"</Relationships>").getBytes(StandardCharsets.UTF_8));validateFixturePackage(parts);bytes=zip(parts);index=EDITOR.inspect(bytes);observation=observe(bytes,"SCT","synthetic-sct-native-frame.docx");edition=new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(),"synthetic-sct-native-frame",index.getEntryHashes(),fixture.policies,fixture.labels,fixture.groups);
        }
        StaticFooterSourceFormat format(){return new StaticFooterSourceFormat(index.getEntryHashes().get(STYLES),"a3","a5",FOOTER,index.getEntryHashes().get(FOOTER),20);}
        NativeStoryPlan plan(StaticFooterSourceFormat format,Map<String,Integer> edges){return DraftNativeIdentity.plan(index,observation,ID,edition,format,edges);}
        DocxEditResult apply(NativeStoryPlan plan){return EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,new NativeLayoutPlan(index.getSourceSha256(),"synthetic-sct-native-frame",Collections.emptyList(),Collections.emptyList()).withStories(plan));}
    }
    private static List<Element> elementsIn(Element parent,String local){List<Element> out=new ArrayList<>();NodeList nodes=parent.getElementsByTagNameNS(W,local);for(int i=0;i<nodes.getLength();i++)out.add((Element)nodes.item(i));return out;}
}
