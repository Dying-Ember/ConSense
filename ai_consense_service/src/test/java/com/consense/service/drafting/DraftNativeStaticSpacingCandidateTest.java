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

/** Small public regression: only successfully migrated static source paragraphs may compact. */
class DraftNativeStaticSpacingCandidateTest {
    private static final String ACTION="COMPACT_EXACT_CLEARED_STATIC_FOOTER_SPACING";
    private static final String STYLE="word/styles.xml";
    private static final String STYLES="<w:styles xmlns:w=\""+W+"\"><w:style w:type=\"paragraph\" w:styleId=\"a3\"/><w:style w:type=\"paragraph\" w:styleId=\"a5\"/></w:styles>";
    private static final String PROPS="<w:pPr><w:pStyle w:val=\"a3\"/><w:tabs><w:tab w:val=\"center\" w:pos=\"4320\"/></w:tabs><w:spacing w:before=\"120\" w:after=\"80\" w:line=\"276\" w:lineRule=\"auto\" w:beforeAutospacing=\"0\"/><w:ind w:left=\"40\"/><w:rPr><w:sz w:val=\"32\"/></w:rPr></w:pPr>";

    @Test void successfulPairMigrationCompactsAllCapturedFootersAndPreservesEveryOtherNativeNode() throws Exception {
        Case source=source("","");NativeStoryPlan plan=source.plan(true);DocxEditResult result=source.apply(plan);
        Map<String,byte[]> after=unzip(result.getDocxBytes());unchanged(source.parts,after,Set.of(MAIN,RELS,TYPES));
        List<Element> before=elements(parse(source.parts.get(MAIN)),W,"p"),generated=elements(parse(after.get(MAIN)),W,"p");assertEquals(8,before.size());assertEquals(before.size(),generated.size());
        Set<Integer> targets=Set.of(1,2,5,6);for(int i=0;i<before.size();i++) {
            Element expected=(Element)before.get(i).cloneNode(true);if(targets.contains(i)){for(Element text:descendants(expected,W,"t"))text.setTextContent("");Element spacing=descendants(expected,W,"spacing").get(0);spacing.setAttributeNS(W,"w:before","0");spacing.setAttributeNS(W,"w:after","0");spacing.setAttributeNS(W,"w:line","1");spacing.setAttributeNS(W,"w:lineRule","exact");}
            assertEquals(shape(expected),shape(generated.get(i)),"Only clear text and four spacing attributes at paragraph "+(i+1));
        }
        List<NativeStoryChange> rows=new ArrayList<>();for(NativeStoryChange row:result.getNativeStoryLedger())if(ACTION.equals(row.getAction()))rows.add(row);assertEquals(4,rows.size());
        Set<String> actualIds=new HashSet<>();for(NativeStoryChange row:rows){actualIds.add(row.getSourceParagraph().getId());assertEquals("APPLIED",row.getStatus());assertSame(plan.staticFooterSourceFormat,row.getSourceFormatting());assertEquals(Map.of("before","0","after","0","line","1","lineRule","exact"),row.getNewAttributes());assertNotNull(row.getOldExactText());assertTrue(row.getSourcePageOrdinal()==1||row.getSourcePageOrdinal()==2);}
        Set<String> expectedIds=new HashSet<>();for(StaticFooterGroup group:plan.staticFooterGroups)for(StaticFooterParagraph p:group.exactParagraphs)expectedIds.add(p.source.getId());assertEquals(expectedIds,actualIds);
        assertEquals("276",descendants(generated.get(3),W,"spacing").get(0).getAttributeNS(W,"line"),"An unrelated original empty paragraph is not authorized by empty text");
        assertEquals(4,result.getNativeStoryLedger().stream().filter(r->"CLEAR_EXACT_STATIC_FOOTER".equals(r.getAction())).count());
    }

    @Test void legacyMigrationStaysExactAndUnsupportedSpacingOrMissingReplacementCannotAuthorizeCompaction() throws Exception {
        Case legacy=source("","");DocxEditResult output=legacy.apply(legacy.plan(false));List<Element> before=elements(parse(legacy.parts.get(MAIN)),W,"p"),after=elements(parse(unzip(output.getDocxBytes()).get(MAIN)),W,"p");
        assertEquals(0,output.getNativeStoryLedger().stream().filter(r->ACTION.equals(r.getAction())).count());
        for(int i:Set.of(1,2,5,6)){Element expected=(Element)before.get(i).cloneNode(true);for(Element text:descendants(expected,W,"t"))text.setTextContent("");assertEquals(shape(expected),shape(after.get(i)),"Null source format retains full legacy properties");}
        Case duplicateSpacing=source("", "<w:spacing w:line=\"99\"/>");assertCode("STORY_STATIC_SPACING_SCOPE_UNSUPPORTED",()->duplicateSpacing.apply(duplicateSpacing.plan(true)));
        Case duplicateProperties=source("<w:pPr/>","");assertCode("STORY_STATIC_SPACING_SCOPE_UNSUPPORTED",()->duplicateProperties.apply(duplicateProperties.plan(true)));
        Case unsupported=source("<w:r><w:drawing/></w:r>","");assertCode("LAYOUT_CLEARED_INLINE_UNSUPPORTED",()->unsupported.apply(unsupported.plan(true)));
        NativeStoryPlan base=legacy.plan(true);Section s=base.sections.get(0);List<Story> stories=new ArrayList<>();for(Story story:s.stories)stories.add(story.expectedBinding.kind==Kind.FOOTER?new Story(story.expectedBinding,StoryAction.PRESERVE,PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,story.contractLabels,story.retainedSourceControlLines,story.pagePrefix,story.pageSuffix):story);
        Section missing=new Section(s.observedSectionId,s.sourceSection,s.sourcePosition,s.titlePg,s.pgNumType,s.pgMar,s.pgSz,s.type,stories,s.numberingChange,s.marginChange);NativeStoryPlan invalid=new NativeStoryPlan(base.sourceSha256,base.profile,base.identity,base.evenAndOddHeaders,Collections.singletonList(missing),base.staticFooterGroups,base.staticFooterSourceFormat);assertCode("STORY_STATIC_CARRIER_REQUIRED",()->legacy.apply(invalid));
    }

    private static Case source(String extra,String spacing) throws Exception {
        String properties=PROPS.replace("<w:ind",spacing+"<w:ind");String body=p("Keep")+footer(properties,"HD(QS) 3C/121/2/10- NTT/1 -",extra)+footer(properties,"March 2020","")+"<w:p>"+PROPS+"</w:p>"+p("Middle")+footer(properties,"HD(QS) 3C/121/2/10- NTT/2 -","")+footer(properties,"March 2020","")+p("Tail")+"<w:sectPr/>";
        Fixture fixture=new Fixture(body,"",Collections.emptyMap(),"");fixture.policies.add(new DraftNativeIdentity.SectionPolicy(PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE,"NTT/",null,null,Arrays.asList("HD(QS) 3C/121/2/10","March 2020")));fixture.groups.add(new DraftNativeIdentity.StaticGroupSpec(1,2,"HD(QS) 3C/121/2/10- NTT/1 -",3,"March 2020"));fixture.groups.add(new DraftNativeIdentity.StaticGroupSpec(2,6,"HD(QS) 3C/121/2/10- NTT/2 -",7,"March 2020"));return new Case(fixture);
    }
    private static String footer(String props,String text,String extra){return "<w:p>"+props+"<w:r><w:rPr><w:rFonts w:ascii=\"Source Font\"/><w:sz w:val=\"32\"/></w:rPr><w:lastRenderedPageBreak/><w:t>"+text+"</w:t></w:r>"+extra+"</w:p>";}
    private static final class Case {
        final Map<String,byte[]> parts;final byte[] bytes;final TemplateIndex index;final JsonNode observation;final DraftNativeIdentity.VerifiedEdition edition;
        Case(Fixture f) throws Exception {parts=new LinkedHashMap<>(f.parts);parts.put(STYLE,STYLES.getBytes(StandardCharsets.UTF_8));parts.put(TYPES,new String(parts.get(TYPES),StandardCharsets.UTF_8).replace("</Types>","<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));parts.put(RELS,new String(parts.get(RELS),StandardCharsets.UTF_8).replace("</Relationships>",relation("rIdStyles","styles","styles.xml")+"</Relationships>").getBytes(StandardCharsets.UTF_8));validateFixturePackage(parts);bytes=zip(parts);index=EDITOR.inspect(bytes);observation=observe(bytes);edition=new DraftNativeIdentity.VerifiedEdition(index.getSourceSha256(),"synthetic-native-story",index.getEntryHashes(),f.policies,f.labels,f.groups);}
        NativeStoryPlan plan(boolean formatted){return DraftNativeIdentity.plan(index,observation,ID,edition,formatted?new StaticFooterSourceFormat(index.getEntryHashes().get(STYLE),"a3","a5"):null,Collections.emptyMap());}
        DocxEditResult apply(NativeStoryPlan plan){return EDITOR.applyWithParagraphBookmarks(bytes,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,new NativeLayoutPlan(index.getSourceSha256(),"synthetic-native-story",Collections.emptyList(),Collections.emptyList()).withStories(plan));}
    }
    private static List<Element> descendants(Element parent,String namespace,String local){List<Element> out=new ArrayList<>();NodeList nodes=parent.getElementsByTagNameNS(namespace,local);for(int i=0;i<nodes.getLength();i++)out.add((Element)nodes.item(i));return out;}
    private static String shape(Node node){StringBuilder out=new StringBuilder();shape(node,out);return out.toString();}
    private static void shape(Node n,StringBuilder out){if(n instanceof Element){out.append('<').append(n.getNamespaceURI()).append('|').append(n.getLocalName());Map<String,String> a=new TreeMap<>();NamedNodeMap attrs=n.getAttributes();for(int i=0;i<attrs.getLength();i++){Node v=attrs.item(i);if(!"http://www.w3.org/2000/xmlns/".equals(v.getNamespaceURI()))a.put("{"+v.getNamespaceURI()+"}"+v.getLocalName(),v.getNodeValue());}out.append(a).append('>');for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())shape(c,out);out.append("</>");}else if(n.getNodeType()==Node.TEXT_NODE||n.getNodeType()==Node.CDATA_SECTION_NODE)out.append(n.getNodeValue());}
}
