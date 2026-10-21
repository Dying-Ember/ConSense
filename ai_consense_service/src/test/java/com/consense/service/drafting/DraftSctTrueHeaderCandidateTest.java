package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.document.NativeStoryPlan;
import com.consense.document.NativeStoryPlan.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeStoryEditorCandidateTest.*;

/** Prepared software checks; no renderer, provider, service or private-original dependency. */
class DraftSctTrueHeaderCandidateTest {
    static final String PLAIN="SPECIAL CONDITIONS OF TENDER",CONTINUED=PLAIN+"  (Cont’d)";
    static final String MARGINS="<w:pgMar w:top=\"720\" w:right=\"1440\" w:bottom=\"426\" w:left=\"1440\" w:header=\"720\" w:footer=\"285\" w:gutter=\"0\"/>";
    static final String STYLES="<w:styles xmlns:w=\""+W+"\"><w:style w:type=\"paragraph\" w:styleId=\"a3\"><w:name w:val=\"Header\"/><w:rPr><w:sz w:val=\"24\"/></w:rPr></w:style></w:styles>";
    static final String RUNS="<w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:lastRenderedPageBreak/><w:t xml:space=\"preserve\">SPECIAL CONDITIONS OF </w:t></w:r><w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:t>TENDER</w:t></w:r><w:r><w:t xml:space=\"preserve\">  (Cont’d)</w:t></w:r>";
    static final class Case {
        final byte[] bytes;final TemplateIndex index;final NativeStoryPlan base;final Map<String,byte[]> parts;final int targetSection;
        Case(boolean nextSection,String mainFlag,String extraTitleControl,boolean page)throws Exception {
            String first="<w:p><w:pPr><w:sectPr>"+reference("footer","rId8")+MARGINS+"</w:sectPr></w:pPr><w:r><w:t>Cover title</w:t></w:r></w:p>";
            String titles="<w:tbl><w:tr><w:tc><w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr><w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:t>"+PLAIN+"</w:t></w:r>"+extraTitleControl+"</w:p></w:tc></w:tr></w:tbl>"+p("Main source continuation")+"<w:tbl><w:tr><w:tc><w:p>"+RUNS+"</w:p></w:tc></w:tr></w:tbl>";
            String mainSection=reference("footer","rId8")+(mainFlag.isEmpty()?"":"<w:footerReference w:type=\"first\" r:id=\"rId8\"/>")+MARGINS+"<w:pgNumType w:fmt=\"decimal\" w:start=\"1\"/>"+mainFlag;
            String body=first+titles+(nextSection?"<w:p><w:pPr><w:sectPr>"+mainSection+"</w:sectPr></w:pPr></w:p>"+p("TO "+PLAIN)+"<w:sectPr>"+MARGINS+"</w:sectPr>":"<w:sectPr>"+mainSection+"</w:sectPr>");
            Fixture fixture=new Fixture(body,relation("rId8","footer","footer1.xml"),Map.of("word/footer1.xml",story("ftr","<w:p><w:r><w:t>OLDNUM</w:t></w:r>"+(page?PAGE:"")+"</w:p>")),"","SCT");
            parts=new LinkedHashMap<>(fixture.parts);parts.put("word/styles.xml",STYLES.getBytes(StandardCharsets.UTF_8));
            parts.put(TYPES,new String(parts.get(TYPES),StandardCharsets.UTF_8).replace("</Types>","<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
            parts.put(RELS,new String(parts.get(RELS),StandardCharsets.UTF_8).replace("</Relationships>",relation("rIdStyle","styles","styles.xml")+"</Relationships>").getBytes(StandardCharsets.UTF_8));validateFixturePackage(parts);
            bytes=zip(parts);index=EDITOR.inspect(bytes);JsonNode observation=observe(bytes,"SCT","synthetic-sct-main.docx");
            List<DraftNativeIdentity.SectionPolicy> policies=new ArrayList<>();for(int i=0;i<(nextSection?3:2);i++)policies.add(policy(page?PageAction.PRESERVE_EXISTING_PAGE:PageAction.PRESERVE_NO_PAGE));
            Paragraph number=index.getParagraphs().stream().filter(p->"word/footer1.xml".equals(p.getPart())).findFirst().orElseThrow(AssertionError::new);
            List<DraftNativeIdentity.LabelSpec> labels=List.of(new DraftNativeIdentity.LabelSpec(number.getPart(),index.getEntryHashes().get(number.getPart()),number.getOrdinal(),number.getCatalogText(),0,6,"OLDNUM"));
            base=DraftNativeIdentity.plan(index,observation,ID,new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(),"synthetic-exact-sct-title",index.getEntryHashes(),policies,labels,Collections.emptyList()));targetSection=1;
        }
        SctTitleMigration migration(String styleHash,List<SctBodyTitle> titles){return new SctTitleMigration(base.sections.get(targetSection),index.getEntryHashes().get("word/styles.xml"),"a3",styleHash,titles,2,4);}
        List<SctBodyTitle> titles(){return List.of(new SctBodyTitle(index.mainParagraph(2),PLAIN,false),new SctBodyTitle(index.mainParagraph(4),CONTINUED,false));}
        SctTitleMigration migration()throws Exception{return migration(styleHash(),titles());}
        String styleHash()throws Exception{return xmlHash(elements(parse(parts.get("word/styles.xml")),W,"style").get(0));}
        DocxEditResult apply(NativeStoryPlan plan,List<Edit> edits,Map<String,String> markers){return EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),edits),markers,false,new NativeLayoutPlan(index.getSourceSha256(),"synthetic-sct-body",Collections.emptyList(),Collections.emptyList()).withStories(plan));}
        DocxEditResult apply(SctTitleMigration policy)throws Exception{return apply(base.withSctTitleMigration(policy),Collections.emptyList(),Collections.emptyMap());}
        DocxEditResult baseline(){return apply(base,Collections.emptyList(),Collections.emptyMap());}
    }

    @Test void firstAndDefaultHeadersUseOriginalRunsAndFirstFooterReusesCompleteVerifiedDefault()throws Exception {
        Case c=new Case(true,"","",true);DocxEditResult baseline=c.baseline(),result=c.apply(c.migration());Map<String,byte[]> after=unzip(result.getDocxBytes()),before=unzip(baseline.getDocxBytes());
        assertEquals(2,actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());assertEquals(1,actions(result,"INSTALL_SCT_FIRST_TITLE_HEADER").size());
        Document main=parse(after.get(MAIN));Element section=elements(main,W,"sectPr").get(1);String defaultHeader=part(after,section,"header","default"),firstHeader=part(after,section,"header","first");
        assertNotEquals(defaultHeader,firstHeader);assertEquals(CONTINUED,visible(elements(parse(after.get(defaultHeader)),W,"p").get(1)));assertEquals(PLAIN,visible(elements(parse(after.get(firstHeader)),W,"p").get(1)));
        for(String name:List.of(defaultHeader,firstHeader)){Document h=parse(after.get(name));assertEquals(ID.title.replace("\r\n","\n"),visible(elements(h,W,"p").get(0)));assertEquals(0,elements(h,W,"lastRenderedPageBreak").size());assertEquals(0,elements(h,W,"bookmarkStart").size());assertEquals(2,elements(h,W,"p").size());assertEquals(2,elements(h,W,"jc").size());assertEquals(0,elements(h,W,"sz").size());}
        Element source=elements(parse(c.parts.get(MAIN)),W,"p").get(3),heading=elements(parse(after.get(defaultHeader)),W,"p").get(1);
        List<Element> sourceRuns=direct(source,"r"),newRuns=direct(heading,"r");assertEquals(sourceRuns.size(),newRuns.size());
        for(int i=0;i<sourceRuns.size();i++){Element copy=(Element)sourceRuns.get(i).cloneNode(true);for(Element metadata:new ArrayList<>(direct(copy,"lastRenderedPageBreak")))copy.removeChild(metadata);assertEquals(shape(copy),shape(newRuns.get(i)),"Donor run formatting, text and xml:space survive; body metadata stays in the body");}
        String footer=part(after,section,"footer","default");assertEquals(footer,part(after,section,"footer","first"));assertArrayEquals(before.get(footer),after.get(footer),"Adding first footer does not clone or rewrite field/cache content");
        assertEquals(1,elements(parse(after.get(footer)),W,"fldChar").stream().filter(e->"begin".equals(e.getAttributeNS(W,"fldCharType"))).count());assertEquals("12",elements(parse(after.get(footer)),W,"t").get(1).getTextContent());
        assertEquals("1080",direct(section,"pgMar").get(0).getAttributeNS(W,"top"));assertEquals("360",direct(section,"pgMar").get(0).getAttributeNS(W,"header"));assertEquals(1,direct(section,"titlePg").size());assertEquals("1",direct(section,"pgNumType").get(0).getAttributeNS(W,"start"));
        assertEquals(PLAIN,visible(elements(parse(after.get(firstHeader)),W,"p").get(1)));assertFalse(text(parse(after.get(part(after,elements(main,W,"sectPr").get(2),"header","default")))).contains(CONTINUED));
        for(int i:new int[]{0,2})assertEquals(shape(elements(parse(before.get(MAIN)),W,"sectPr").get(i)),shape(elements(main,W,"sectPr").get(i)),"Other section declarations are unchanged");
    }

    @Test void clearsOnlyWhitelistedTableParagraphsPreservesContainersMetadataAndLaterSourceBookmarks()throws Exception {
        Case c=new Case(true,"","",true);Map<String,String> markers=Map.of(c.index.mainParagraph(2).getId(),"CSTitlePlain",c.index.mainParagraph(4).getId(),"CSTitleContinued");DocxEditResult result=c.apply(c.base.withSctTitleMigration(c.migration()),Collections.emptyList(),markers);Map<String,byte[]> after=unzip(result.getDocxBytes());
        assertEquals(c.index.getMainParagraphs().size(),result.getIndex().getMainParagraphs().size());assertEquals("",result.getIndex().mainParagraph(2).getCatalogText());assertEquals("",result.getIndex().mainParagraph(4).getCatalogText());assertEquals("Main source continuation",result.getIndex().mainParagraph(3).getCatalogText());assertEquals("TO "+PLAIN,result.getIndex().mainParagraph(6).getCatalogText());
        Document body=parse(after.get(MAIN));assertEquals(2,elements(body,W,"tbl").size());assertEquals(2,elements(body,W,"tc").size());assertEquals(1,elements(body,W,"lastRenderedPageBreak").size());assertEquals(2,elements(body,W,"bookmarkStart").size());
        for(int ordinal:new int[]{2,4}){Element node=elements(body,W,"p").get(ordinal-1);assertEquals(1,direct(direct(node,"pPr").get(0),"spacing").size());assertEquals("1",direct(direct(node,"pPr").get(0),"spacing").get(0).getAttributeNS(W,"line"));assertEquals(1,direct(node,"bookmarkStart").size());}
        unchanged(c.parts,after,Set.of(MAIN,RELS,TYPES,"word/footer1.xml"));
    }

    @Test void manualTitleEditSkipsOptionalMigrationAndKeepsOtherUsableResults()throws Exception {
        Case c=new Case(true,"","",true);Paragraph title=c.index.mainParagraph(2);DocxEditResult result=c.apply(c.base.withSctTitleMigration(c.migration()),List.of(Edit.replaceSpan("manual-title",title,0,title.getText().length(),title.getText(),"User chosen title")),Collections.emptyMap());
        assertEquals("User chosen title",result.getIndex().mainParagraph(2).getCatalogText());assertEquals(CONTINUED,result.getIndex().mainParagraph(4).getCatalogText());assertSkipped(result,"SCT_TITLE_BUSINESS_EDIT_OVERLAP");assertNoPartialSwitch(result,1);assertTrue(text(parse(unzip(result.getDocxBytes()).get("word/footer1.xml"))).contains(ID.number));
    }

    @Test void changedStyleOrForeignTitleAnchorPreservesBasePackageWithoutPartialSwitch()throws Exception {
        Case c=new Case(true,"","",true),foreign=new Case(true,"","<w:r><w:t>Extra</w:t></w:r>",true);DocxEditResult baseline=c.baseline();
        for(SctTitleMigration bad:List.of(c.migration("0".repeat(64),c.titles()),c.migration(c.styleHash(),List.of(new SctBodyTitle(foreign.index.mainParagraph(2),PLAIN,false),c.titles().get(1))))){DocxEditResult result=c.apply(bad);assertTrue(actions(result,"MIGRATE_EXACT_SCT_TITLES").get(0).getStatus().startsWith("SKIPPED_"));assertSameEntries(baseline,result);assertNoPartialSwitch(result,1);}
    }

    @Test void unsupportedFieldDrawingAndUnlistedColumnDoNotClearAnyTitle()throws Exception {
        for(String control:List.of("<w:r><w:drawing/></w:r>","<w:r><w:br w:type=\"column\"/></w:r>","<w:fldSimple w:instr=\" PAGE \"><w:r><w:t/></w:r></w:fldSimple>","<w:bookmarkStart w:id=\"2\" w:name=\"Unclosed\"/>")){
            Case c=new Case(true,"",control,true);DocxEditResult result=c.apply(c.migration());assertEquals(PLAIN,result.getIndex().mainParagraph(2).getCatalogText());assertEquals(CONTINUED,result.getIndex().mainParagraph(4).getCatalogText());assertNoPartialSwitch(result,1);assertEquals(0,actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());assertSameEntries(c.baseline(),result);
        }
    }

    @Test void missingPageCarrierAndOriginalFirstPageFlagLeaveOptionalPolicyUnapplied()throws Exception {
        for(Case c:List.of(new Case(true,"","",false),new Case(true,"<w:titlePg/>","",true))){DocxEditResult result=c.apply(c.migration());assertEquals(PLAIN,result.getIndex().mainParagraph(2).getCatalogText());assertEquals(0,actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());assertSameEntries(c.baseline(),result);assertTrue(actions(result,"MIGRATE_EXACT_SCT_TITLES").get(0).getStatus().startsWith("SKIPPED_"));}
    }

    @Test void stagedInstallationFailureDoesNotLeakHeaderPartsRelationshipsMarginsOrFirstPageFlag()throws Exception {
        Case c=new Case(true,"","",true);List<Section> sections=new ArrayList<>(c.base.sections);Section next=sections.get(2);List<Story> slots=new ArrayList<>();for(Story story:next.stories)slots.add(story.expectedBinding.kind==Kind.HEADER?new Story(story.expectedBinding,StoryAction.PRESERVE,story.pageAction,story.contractLabels,story.retainedSourceControlLines,story.pagePrefix,story.pageSuffix):story);sections.set(2,new Section(next.observedSectionId,next.sourceSection,next.sourcePosition,next.titlePg,next.pgNumType,next.pgMar,next.pgSz,next.type,slots,next.numberingChange,next.marginChange));
        NativeStoryPlan base=new NativeStoryPlan(c.base.sourceSha256,c.base.profile,c.base.identity,c.base.evenAndOddHeaders,sections,c.base.staticFooterGroups,c.base.staticFooterSourceFormat);
        DocxEditResult before=c.apply(base,Collections.emptyList(),Collections.emptyMap()),result=c.apply(base.withSctTitleMigration(c.migration()),Collections.emptyList(),Collections.emptyMap());assertSkipped(result,"SCT_TITLE_NEXT_SECTION_INHERITANCE_UNSAFE");assertSameEntries(before,result);assertNoPartialSwitch(result,1);assertEquals(PLAIN,result.getIndex().mainParagraph(2).getCatalogText());
    }

    @Test void registryEnablesOnlyTwoExactSctMainSectionsAndKeepsNativeObservationUnknown()throws Exception {
        JsonNode registry;try(InputStream input=getClass().getResourceAsStream("/drafting/native-story-editions.json")){assertNotNull(input);registry=JsonUtils.mapper().readTree(input);}int opted=0;Set<Integer> counts=new HashSet<>();
        for(JsonNode source:registry.path("sources")){JsonNode policy=source.path("sctTitleMigration");if(policy.isMissingNode())continue;opted++;assertEquals("SCT",source.path("key").asText());assertTrue(Set.of("7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4","6d7802ea7b094124c0ddebb0b448c7e6bfe72828ffbc91ad1c30133902a6f64c").contains(source.path("sourceSha256").asText()));counts.add(policy.path("bodyTitles").size());assertEquals("absent",policy.path("expectedTitlePg").asText());assertEquals("link_verified_default",policy.path("firstFooterPolicy").asText());assertEquals(1080,policy.path("candidateFrameTwips").path("top").asInt());assertEquals(360,policy.path("candidateFrameTwips").path("header").asInt());assertTrue(policy.path("generationPolicy").asText().contains("semantic scope remains unknown"));assertFalse(source.path("sourceFormatting").path("candidateEdgeDistancesTwips").has("top"),"The general body-margin allowlist is unchanged");}
        assertEquals(2,opted);assertEquals(Set.of(27,21),counts);
    }

    @Test void numericCjkLongIdentityIsExactInBothHeadersAndFooterAndNeverRewritesPageCache()throws Exception {
        Case c=new Case(true,"","",true);Identity identity=new Identity("CON-2026/1108-027","2026 NTT-SCT 27B 消防 Phase 12 ".repeat(8));NativeStoryPlan plan=new NativeStoryPlan(c.base.sourceSha256,c.base.profile,identity,c.base.evenAndOddHeaders,c.base.sections,c.base.staticFooterGroups,c.base.staticFooterSourceFormat).withSctTitleMigration(c.migration());
        DocxEditResult result=c.apply(plan,Collections.emptyList(),Collections.emptyMap());assertEquals(2,actions(result,"CLEAR_EXACT_SCT_BODY_TITLE").size());Map<String,byte[]> after=unzip(result.getDocxBytes());Element section=elements(parse(after.get(MAIN)),W,"sectPr").get(1);
        for(String variant:List.of("first","default"))assertEquals(identity.title,visible(elements(parse(after.get(part(after,section,"header",variant))),W,"p").get(0)));
        Document footer=parse(after.get(part(after,section,"footer","first")));assertEquals(identity.number+"12",text(footer));assertEquals("12",elements(footer,W,"t").get(1).getTextContent());assertEquals(1,actions(result,"LINK_SCT_FIRST_FOOTER_TO_VERIFIED_DEFAULT").size());
    }

    static List<NativeStoryChange> actions(DocxEditResult r,String action){List<NativeStoryChange> out=new ArrayList<>();for(NativeStoryChange row:r.getNativeStoryLedger())if(action.equals(row.getAction()))out.add(row);return out;}
    static void assertSkipped(DocxEditResult r,String code){assertEquals("SKIPPED_"+code,actions(r,"MIGRATE_EXACT_SCT_TITLES").get(0).getStatus());}
    static void assertNoPartialSwitch(DocxEditResult r,int sectionIndex)throws Exception{Element section=elements(parse(unzip(r.getDocxBytes()).get(MAIN)),W,"sectPr").get(sectionIndex);assertEquals(0,direct(section,"titlePg").size());assertEquals("720",direct(section,"pgMar").get(0).getAttributeNS(W,"top"));for(String kind:List.of("header","footer"))assertEquals(0,direct(section,kind+"Reference").stream().filter(e->"first".equals(e.getAttributeNS(W,"type"))).count());}
    static void assertSameEntries(DocxEditResult a,DocxEditResult b)throws Exception{Map<String,byte[]> x=unzip(a.getDocxBytes()),y=unzip(b.getDocxBytes());assertEquals(x.keySet(),y.keySet());unchanged(x,y,Collections.emptySet());}
    static List<Element> direct(Element root,String local){List<Element> out=new ArrayList<>();for(Node child=root.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&local.equals(child.getLocalName()))out.add((Element)child);return out;}
    static String part(Map<String,byte[]> parts,Element section,String kind,String variant)throws Exception{Element ref=direct(section,kind+"Reference").stream().filter(e->variant.equals(e.getAttributeNS(W,"type"))).findFirst().orElseThrow(AssertionError::new);String id=ref.getAttributeNS(R,"id");Element relation=elements(parse(parts.get(RELS)),P,"Relationship").stream().filter(e->id.equals(e.getAttribute("Id"))).findFirst().orElseThrow(AssertionError::new);assertEquals(R+"/"+kind,relation.getAttribute("Type"));return new java.net.URI("/"+MAIN).resolve(relation.getAttribute("Target")).normalize().getPath().substring(1);}
    static String xmlHash(Node node)throws Exception{Transformer transformer=TransformerFactory.newInstance().newTransformer();transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8");transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");transformer.setOutputProperty(OutputKeys.INDENT,"no");ByteArrayOutputStream out=new ByteArrayOutputStream();transformer.transform(new DOMSource(node),new StreamResult(out));byte[] digest=MessageDigest.getInstance("SHA-256").digest(out.toByteArray());StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}
    static String shape(Node n){StringBuilder out=new StringBuilder();shape(n,out);return out.toString();}
    static void shape(Node n,StringBuilder out){if(n instanceof Element){out.append('<').append(n.getNamespaceURI()).append('|').append(n.getLocalName());Map<String,String> attrs=new TreeMap<>();NamedNodeMap raw=n.getAttributes();for(int i=0;i<raw.getLength();i++){Node a=raw.item(i);if(!"http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI()))attrs.put("{"+a.getNamespaceURI()+"}"+a.getLocalName(),a.getNodeValue());}out.append(attrs).append('>');for(Node child=n.getFirstChild();child!=null;child=child.getNextSibling())shape(child,out);out.append("</>");}else if(n.getNodeType()==Node.TEXT_NODE)out.append(n.getNodeValue());}
}
