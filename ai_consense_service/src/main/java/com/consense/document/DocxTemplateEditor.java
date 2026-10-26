package com.consense.document;

import com.consense.document.NativeStoryPlan.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Source-bound editing of an existing DOCX package. This utility does not decide clause applicability. */
public final class DocxTemplateEditor {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MAIN="word/document.xml";
    private static final String M="http://schemas.openxmlformats.org/officeDocument/2006/math";
    // The same hard ceilings as DocumentParser; actual inflation is checked, not ZIP declared sizes.
    private static final long ENTRY_LIMIT=128L*1024*1024, XML_LIMIT=32L*1024*1024, TOTAL_LIMIT=256L*1024*1024;
    private static final int COUNT_LIMIT=5000;

    public TemplateIndex inspect(byte[] sourceDocx) { return load(sourceDocx).index; }
    /** Renderer-only copy: reuse the guarded Latin-run policy without changing text or saved artifacts. */
    public DocxEditResult libreOfficeRenderingCopy(byte[] sourceDocx) {
        PackageModel model=load(sourceDocx);Set<String> parts=new LinkedHashSet<>();List<NativeLayoutChange> changes=new ArrayList<>();
        for(Paragraph paragraph:model.index.getParagraphs()) {
            List<Element> fonts;
            try { fonts=latinFontControls(model.nodes.get(paragraph.id),paragraph.id,false,false,true); }
            catch(EditException unsupported) { continue; }
            if(fonts.isEmpty())continue;
            for(Element font:fonts)font.removeAttributeNS(W,"hint");
            parts.add(paragraph.part);
            changes.add(new NativeLayoutChange("normalize_verified_latin_font_hint","APPLIED",model.index.anchor(paragraph.id),paragraph.id));
        }
        if(parts.isEmpty())return new DocxEditResult(sourceDocx,model.index);
        for(String part:parts)model.entries.put(part,xml(model.xmlParts.get(part)));
        byte[] result=write(model.entries);
        return new DocxEditResult(result,load(result).index,model.index.sourceSha256,Collections.emptyList(),Collections.emptyList(),model.index.entryHashes,"libreoffice-latin-hints-v1",changes);
    }
    /** Source-bound named paragraphs; current paths are resolved only inside the frozen package. */
    public static final class NativeHeadingGap {
        private final String bookmark,sourceSha256;
        public NativeHeadingGap(String bookmark,String sourceSha256){if(bookmark==null||!bookmark.matches("CS[A-Za-z0-9]{1,38}")||sourceSha256==null||!sourceSha256.matches("[a-f0-9]{64}"))throw error("HEADING_FLOW_IDENTITY_INVALID","heading-gap");this.bookmark=bookmark;this.sourceSha256=sourceSha256;}
        public String getBookmark(){return bookmark;}public String getSourceSha256(){return sourceSha256;}
    }
    public static final class NativeHeadingFlow {
        private final String headingBookmark,bodyBookmark;private final int emptyParagraphs;private final List<NativeHeadingGap> sourceGaps;
        public NativeHeadingFlow(String headingBookmark,String bodyBookmark,int emptyParagraphs){this(headingBookmark,bodyBookmark,emptyParagraphs,Collections.emptyList());}
        public NativeHeadingFlow(String headingBookmark,String bodyBookmark,int emptyParagraphs,List<NativeHeadingGap> sourceGaps){if(headingBookmark==null||bodyBookmark==null||!headingBookmark.matches("CS[A-Za-z0-9]{1,38}")||!bodyBookmark.matches("CS[A-Za-z0-9]{1,38}")||headingBookmark.equals(bodyBookmark)||emptyParagraphs<0||sourceGaps==null||!sourceGaps.isEmpty()&&sourceGaps.size()!=emptyParagraphs||sourceGaps.stream().anyMatch(Objects::isNull))throw error("HEADING_FLOW_IDENTITY_INVALID","heading-flow");this.headingBookmark=headingBookmark;this.bodyBookmark=bodyBookmark;this.emptyParagraphs=emptyParagraphs;this.sourceGaps=Collections.unmodifiableList(new ArrayList<>(sourceGaps));}
        public String getHeadingBookmark(){return headingBookmark;}public String getBodyBookmark(){return bodyBookmark;}public int getEmptyParagraphs(){return emptyParagraphs;}public List<NativeHeadingGap> getSourceGaps(){return sourceGaps;}
    }
    /** Validate the complete registered denominator, then add only measured heading page boundaries. */
    public DocxEditResult updateNativeHeadingBoundaries(byte[] sourceDocx,String expectedSha256,List<NativeHeadingFlow> flows,Set<String> splitHeadings){
        PackageModel model=load(sourceDocx);if(!model.index.sourceSha256.equals(expectedSha256))throw error("SOURCE_HASH_MISMATCH","heading-flow");
        if(flows==null||flows.isEmpty()||splitHeadings==null)throw error("HEADING_FLOW_SCOPE_REQUIRED","heading-flow");
        Map<String,Element> headings=validateNativeHeadingFlows(model,flows,null);
        if(!headings.keySet().containsAll(splitHeadings))throw error("HEADING_FLOW_TARGET_SET_MISMATCH","heading-flow");
        if(splitHeadings.isEmpty())return new DocxEditResult(sourceDocx,model.index);
        byte[] original=model.entries.get(MAIN);Document document=model.xmlParts.get(MAIN);if(!Arrays.equals(original,tocXmlKeepingOriginalDeclaration(document,original)))throw error("HEADING_BOUNDARY_XML_NOT_REVERSIBLE","heading-flow");
        Map<Element,Element> previous=new LinkedHashMap<>();
        for(String name:nativeHeadingRepairRoots(flows,splitHeadings)){Element heading=headings.get(name);List<Element> props=direct(heading,"pPr");Element old=props.isEmpty()?null:(Element)props.get(0).cloneNode(true);previous.put(heading,old);if(!props.isEmpty())for(Element value:new ArrayList<>(direct(props.get(0),"pageBreakBefore")))props.get(0).removeChild(value);pageBreakBeforeHeading(heading);}
        byte[] changed=tocXmlKeepingOriginalDeclaration(document,original);
        for(Map.Entry<Element,Element> entry:previous.entrySet()){Element current=direct(entry.getKey(),"pPr").get(0);if(entry.getValue()==null)entry.getKey().removeChild(current);else entry.getKey().replaceChild(entry.getValue(),current);}
        if(!Arrays.equals(original,tocXmlKeepingOriginalDeclaration(document,original)))throw error("HEADING_BOUNDARY_XML_NOT_REVERSIBLE","heading-flow");
        model.entries.put(MAIN,changed);byte[] result=write(model.entries);TemplateIndex index=load(result).index;for(Map.Entry<String,String> entry:model.index.entryHashes.entrySet())if(!MAIN.equals(entry.getKey())&&!entry.getValue().equals(index.entryHashes.get(entry.getKey())))throw error("HEADING_BOUNDARY_PART_CHANGED",entry.getKey());
        return new DocxEditResult(result,index,model.index.sourceSha256,Collections.emptyList(),Collections.emptyList(),model.index.entryHashes);
    }
    /** Read-only TOC fallback authority; retained original guidance never certifies heading pagination. */
    public int verifyNativeHeadingRetainedSourceGaps(byte[] sourceDocx,String expectedSha256,List<NativeHeadingFlow> flows){
        PackageModel model=load(sourceDocx);if(!model.index.sourceSha256.equals(expectedSha256))throw error("SOURCE_HASH_MISMATCH","heading-flow");if(flows==null||flows.isEmpty())throw error("HEADING_FLOW_SCOPE_REQUIRED","heading-flow");Set<String> retained=new LinkedHashSet<>();validateNativeHeadingFlows(model,flows,retained);if(retained.isEmpty())throw error("HEADING_FLOW_RETAINED_GAP_REQUIRED","heading-flow");return retained.size();
    }
    private static Map<String,Element> validateNativeHeadingFlows(PackageModel model,List<NativeHeadingFlow> flows,Set<String> retained){
        Map<String,Element> names=new LinkedHashMap<>(),headings=new LinkedHashMap<>();
        Map<Element,String> owners=new IdentityHashMap<>();Set<String> bodyNames=new HashSet<>();
        Set<Element> gaps=Collections.newSetFromMap(new IdentityHashMap<>());
        for(NativeHeadingFlow flow:flows){
            if(flow==null||headings.containsKey(flow.headingBookmark)||!bodyNames.add(flow.bodyBookmark))throw error("HEADING_FLOW_IDENTITY_AMBIGUOUS","heading-flow");
            Element heading=headingFlowBookmark(model,flow.headingBookmark),body=headingFlowBookmark(model,flow.bodyBookmark);
            for(Map.Entry<String,Element> endpoint:Map.of(flow.headingBookmark,heading,flow.bodyBookmark,body).entrySet()){
                Element previous=names.putIfAbsent(endpoint.getKey(),endpoint.getValue());String owner=owners.putIfAbsent(endpoint.getValue(),endpoint.getKey());
                if(previous!=null&&previous!=endpoint.getValue()||owner!=null&&!owner.equals(endpoint.getKey()))throw error("HEADING_FLOW_IDENTITY_AMBIGUOUS",endpoint.getKey());
            }
            validateHeadingFlowParagraph(heading,true,flow.headingBookmark);validateHeadingFlowParagraph(body,false,flow.bodyBookmark);
            if(descendantText(heading,"t").trim().isEmpty()||descendantText(body,"t").trim().isEmpty())throw error("HEADING_FLOW_TEXT_MISSING",flow.headingBookmark);
            headings.put(flow.headingBookmark,heading);Node next=nextNativeFlowSibling(heading);int count=0;
            while(next!=null&&next!=body){if(next instanceof Element){Element gap=(Element)next;if(!is(gap,"p")||!gaps.add(gap))throw error("HEADING_FLOW_CHAIN_CHANGED",flow.headingBookmark);if(!descendantText(gap,"t").trim().isEmpty()){if(retained==null||flow.sourceGaps.size()!=flow.emptyParagraphs||count>=flow.sourceGaps.size())throw error("HEADING_FLOW_CHAIN_CHANGED",flow.headingBookmark);verifyOriginalHeadingGap(model,gap,flow.sourceGaps.get(count),retained);}validateHeadingFlowParagraph(gap,false,flow.headingBookmark);count++;}next=nextNativeFlowSibling(next);}
            if(next!=body||count!=flow.emptyParagraphs)throw error("HEADING_FLOW_CHAIN_CHANGED",flow.headingBookmark);
        }
        for(Element gap:gaps)if(owners.containsKey(gap))throw error("HEADING_FLOW_CHAIN_CHANGED","heading-flow");
        return headings;
    }
    private static void verifyOriginalHeadingGap(PackageModel model,Element gap,NativeHeadingGap source,Set<String> retained){
        if(headingFlowBookmark(model,source.bookmark)!=gap||!retained.add(source.bookmark))throw error("HEADING_FLOW_CHAIN_CHANGED",source.bookmark);
        Element copy=(Element)gap.cloneNode(true);String markerId=null;NodeList starts=copy.getElementsByTagNameNS(W,"bookmarkStart");
        for(int i=0;i<starts.getLength();i++){Element start=(Element)starts.item(i);if(source.bookmark.equals(start.getAttributeNS(W,"name")))markerId=start.getAttributeNS(W,"id");}
        // Only the source CS range was added; every other original node and property must hash identically.
        for(String kind:Arrays.asList("bookmarkStart","bookmarkEnd")){NodeList markers=copy.getElementsByTagNameNS(W,kind);for(int i=markers.getLength()-1;i>=0;i--){Element marker=(Element)markers.item(i);if(Objects.equals(markerId,marker.getAttributeNS(W,"id")))marker.getParentNode().removeChild(marker);}}
        if(!source.sourceSha256.equals(hash(xml(copy))))throw error("HEADING_FLOW_RETAINED_SOURCE_CHANGED",source.bookmark);
    }
    /** Move the whole verified predecessor chain, so a repaired intro cannot strand its own heading. */
    public static Set<String> nativeHeadingRepairRoots(List<NativeHeadingFlow> flows,Set<String> measuredSplits){
        if(flows==null||flows.isEmpty()||measuredSplits==null)throw error("HEADING_FLOW_SCOPE_REQUIRED","heading-flow");
        Map<String,String> predecessors=new LinkedHashMap<>();Set<String> starts=new HashSet<>();
        for(NativeHeadingFlow flow:flows)if(flow==null||!starts.add(flow.headingBookmark)||predecessors.putIfAbsent(flow.bodyBookmark,flow.headingBookmark)!=null)throw error("HEADING_FLOW_IDENTITY_AMBIGUOUS","heading-flow");
        if(!starts.containsAll(measuredSplits))throw error("HEADING_FLOW_TARGET_SET_MISMATCH","heading-flow");
        Set<String> roots=new LinkedHashSet<>();for(String split:measuredSplits){String root=split;Set<String> seen=new HashSet<>();while(predecessors.containsKey(root)){if(!seen.add(root))throw error("HEADING_FLOW_CYCLE","heading-flow");root=predecessors.get(root);}roots.add(root);}return Collections.unmodifiableSet(roots);
    }
    /** Native zero-display markers remain in place while the exact source-bound paragraph chain is checked. */
    private static Node nextNativeFlowSibling(Node current){
        Node next=current.getNextSibling();while(next!=null&&(!(next instanceof Element)||transparentNativeFlowBookmark((Element)next)))next=next.getNextSibling();return next;
    }
    private static boolean transparentNativeFlowBookmark(Element marker){
        boolean start=is(marker,"bookmarkStart");if(!start&&!is(marker,"bookmarkEnd")||!is(marker.getParentNode(),"body")||marker.hasChildNodes()||!marker.getAttributeNS(W,"id").matches("[0-9]+"))return false;
        if(start&&marker.getAttributeNS(W,"name").trim().isEmpty())return false;
        NamedNodeMap attributes=marker.getAttributes();for(int i=0;i<attributes.getLength();i++){Node attribute=attributes.item(i);if(!W.equals(attribute.getNamespaceURI())||!("id".equals(attribute.getLocalName())||start&&"name".equals(attribute.getLocalName())))return false;}return true;
    }
    /** A duplicate/end-before-start marker must not be hidden by the general feature index. */
    private static Element headingFlowBookmark(PackageModel model,String name){Document document=model.xmlParts.get(MAIN);Element start=null,end=null;int named=0;NodeList starts=document.getElementsByTagNameNS(W,"bookmarkStart");for(int i=0;i<starts.getLength();i++){Element node=(Element)starts.item(i);if(name.equals(node.getAttributeNS(W,"name"))){start=node;named++;}}if(named!=1)throw error("HEADING_FLOW_BOOKMARK_AMBIGUOUS",name);String id=start.getAttributeNS(W,"id");if(id.isEmpty())throw error("HEADING_FLOW_BOOKMARK_AMBIGUOUS",name);int sameId=0;for(int i=0;i<starts.getLength();i++)if(id.equals(((Element)starts.item(i)).getAttributeNS(W,"id")))sameId++;NodeList ends=document.getElementsByTagNameNS(W,"bookmarkEnd");int matches=0;for(int i=0;i<ends.getLength();i++){Element node=(Element)ends.item(i);if(id.equals(node.getAttributeNS(W,"id"))){end=node;matches++;}}if(sameId!=1||matches!=1)throw error("HEADING_FLOW_BOOKMARK_AMBIGUOUS",name);Element paragraph=start;while(paragraph!=null&&!is(paragraph,"p"))paragraph=paragraph.getParentNode() instanceof Element?(Element)paragraph.getParentNode():null;Element finish=end;while(finish!=null&&!is(finish,"p"))finish=finish.getParentNode() instanceof Element?(Element)finish.getParentNode():null;if(paragraph==null||paragraph!=finish||!is(paragraph.getParentNode(),"body")||(start.compareDocumentPosition(end)&Node.DOCUMENT_POSITION_FOLLOWING)==0)throw error("HEADING_FLOW_BOOKMARK_RANGE_CHANGED",name);return paragraph;}
    private static void validateHeadingFlowParagraph(Element paragraph,boolean heading,String id){Element checked=(Element)paragraph.cloneNode(true);List<Element> props=direct(checked,"pPr");if(props.size()>1)throw error("HEADING_FLOW_PROPERTIES_AMBIGUOUS",id);if(heading&&!props.isEmpty()){List<Element> breaks=direct(props.get(0),"pageBreakBefore");if(breaks.size()>1)throw error("HEADING_FLOW_PROPERTIES_AMBIGUOUS",id);for(Element value:breaks){if(!Arrays.asList("","0","1","true","false","on","off").contains(value.getAttributeNS(W,"val")))throw error("HEADING_FLOW_PROPERTIES_AMBIGUOUS",id);props.get(0).removeChild(value);}}validateClearedLayoutParagraph(checked,false,id);}

    public PreparedTemplate prepare(byte[] sourceDocx) { return new PreparedTemplate(load(sourceDocx)); }
    /** Native field identities only; PDF pagination remains the caller's measured evidence. */
    public List<TocPageReference> tocPageReferences(byte[] sourceDocx) { return tocPageReferences(load(sourceDocx)); }
    /** A cleared endpoint may remain as an empty native paragraph; this does not certify its heading flow. */
    public Paragraph verifyEmptyNativeBookmarkParagraph(byte[] sourceDocx,String expectedSha256,String bookmark,String expectedParagraphId){
        PackageModel model=load(sourceDocx);if(!model.index.sourceSha256.equals(expectedSha256))throw error("SOURCE_HASH_MISMATCH","cleared-endpoint");Element paragraph=headingFlowBookmark(model,bookmark);String id=MAIN+"#"+path(paragraph);
        if(!id.equals(expectedParagraphId)||!editableText(paragraph).isEmpty())throw error("TOC_CLEARED_ENDPOINT_NOT_EMPTY",bookmark);
        validateClearedLayoutParagraph(paragraph,false,bookmark);
        for(String unsupported:Arrays.asList("numPr","pStyle","pBdr","fldChar","instrText","fldSimple","drawing","pict","oMath","sym","object","tab","br","cr","sectPr","pageBreakBefore"))if(has(paragraph,unsupported))throw error("TOC_CLEARED_ENDPOINT_CONTROL_UNSUPPORTED",bookmark);
        return model.index.paragraph(id);
    }
    /** Read-only authority for disambiguating a rendered PAGE value from this exact footer's literals. */
    public Map<String,TocFooterPageSource> tocFooterPageSources(byte[] sourceDocx,String expectedSha256) {
        PackageModel model=load(sourceDocx);if(!model.index.sourceSha256.equals(expectedSha256))throw error("SOURCE_HASH_MISMATCH","toc-footer");
        List<Element> relationships=storyRelationships(model,false);Element settings=null;
        for(Element relation:relationships)if((OFFICE_REL+"/settings").equals(relation.getAttribute("Type"))){if(settings!=null||!internalStoryRelation(relation))throw error("TOC_FOOTER_SETTINGS_UNSUPPORTED","toc-footer");settings=storyXml(model,resolveStoryTarget(relation.getAttribute("Target"))).getDocumentElement();}
        if(settings!=null)for(Element odd:direct(settings,"evenAndOddHeaders"))if(!Arrays.asList("0","false","off").contains(odd.getAttributeNS(W,"val")))throw error("TOC_FOOTER_VARIANT_UNSUPPORTED","toc-footer");
        Map<String,TocFooterPageSource> result=new LinkedHashMap<>(),sections=new HashMap<>();
        for(TocPageReference reference:tocPageReferences(model)){
            Feature bookmark=null;for(Feature feature:model.index.features)if("BOOKMARK".equals(feature.kind)&&reference.targetBookmark.equals(feature.name)&&feature.complete)bookmark=feature;
            Paragraph paragraph=null;for(Paragraph candidate:model.index.mainParagraphs)if(bookmark.startId.startsWith(candidate.id+"/")&&(paragraph==null||candidate.id.length()>paragraph.id.length()))paragraph=candidate;
            if(paragraph==null||paragraph.sectionId==null)throw error("TOC_FOOTER_SECTION_UNMAPPED",reference.targetBookmark);
            TocFooterPageSource source=sections.get(paragraph.sectionId);
            if(source==null){
                Element section=model.nodes.get(paragraph.sectionId);if(section==null||!is(section,"sectPr"))throw error("TOC_FOOTER_SECTION_UNMAPPED",reference.targetBookmark);
                for(Element title:direct(section,"titlePg"))if(!Arrays.asList("0","false","off").contains(title.getAttributeNS(W,"val")))throw error("TOC_FOOTER_VARIANT_UNSUPPORTED",paragraph.sectionId);
                List<Element> numbers=direct(section,"pgNumType");if(numbers.size()>1)throw error("TOC_FOOTER_NUMBERING_UNSUPPORTED",paragraph.sectionId);
                if(!numbers.isEmpty()){Element number=numbers.get(0);if(!Arrays.asList("","decimal").contains(number.getAttributeNS(W,"fmt"))||number.hasAttributeNS(W,"chapStyle")||number.hasAttributeNS(W,"chapSep"))throw error("TOC_FOOTER_NUMBERING_UNSUPPORTED",paragraph.sectionId);}
                List<Element> refs=direct(section,"footerReference");if(refs.size()!=1||!"default".equals(refs.get(0).getAttributeNS(W,"type")))throw error("TOC_FOOTER_VARIANT_UNSUPPORTED",paragraph.sectionId);
                String id=refs.get(0).getAttributeNS(OFFICE_REL,"id");Element relation=null;int matches=0;for(Element entry:relationships)if(id.equals(entry.getAttribute("Id"))){relation=entry;matches++;}
                if(matches!=1||!(OFFICE_REL+"/footer").equals(relation.getAttribute("Type"))||!internalStoryRelation(relation))throw error("TOC_FOOTER_RELATIONSHIP_UNSUPPORTED",paragraph.sectionId);
                String part=resolveStoryTarget(relation.getAttribute("Target"));Element footer=storyXml(model,part).getDocumentElement();if(!is(footer,"ftr"))throw error("TOC_FOOTER_PART_UNSUPPORTED",part);validateStoryContentType(model,part,Kind.FOOTER);
                List<Feature> fields=new ArrayList<>();for(Feature feature:model.index.features)if("FIELD".equals(feature.kind)&&part.equals(feature.part))fields.add(feature);
                if(fields.size()!=1||!fields.get(0).complete||!tocPageInstruction(fields.get(0).instruction))throw error("TOC_FOOTER_PAGE_FIELD_UNSUPPORTED",part);
                Feature field=fields.get(0);Element start=model.nodes.get(field.startId),end=model.nodes.get(field.endId);if(start==null||end==null||tocFooterAncestor(start,"p")!=tocFooterAncestor(end,"p"))throw error("TOC_FOOTER_PAGE_FIELD_UNSUPPORTED",part);
                if(is(start,"fldSimple")?has(footer,"fldChar"):footer.getElementsByTagNameNS(W,"fldChar").getLength()!=3)throw error("TOC_FOOTER_PAGE_FIELD_UNSUPPORTED",part);
                for(String unsupported:Arrays.asList("drawing","pict","oMath","tbl","sdt","hyperlink","altChunk"))if(has(footer,unsupported))throw error("TOC_FOOTER_CONTENT_UNSUPPORTED",part);
                List<String> literals=new ArrayList<>();int controls=0;
                for(Node child=footer.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element){if(!is(child,"p"))throw error("TOC_FOOTER_CONTENT_UNSUPPORTED",part);StringBuilder literal=new StringBuilder();NodeList nodes=((Element)child).getElementsByTagName("*");
                    for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);if(tocFooterAncestor(node,"pPr")!=null||tocFooterAncestor(node,"rPr")!=null||is(node,"pPr")||is(node,"rPr"))continue;
                        if(node==start){appendTocFooterLiteral(literals,literal);controls++;}
                        if(is(start,"fldSimple")&&contains(start,node)||node==start||node==end||insideFeature(node,start,end))continue;
                        if(is(node,"t"))literal.append(node.getTextContent());else if(is(node,"tab")||is(node,"br")||is(node,"cr"))appendTocFooterLiteral(literals,literal);
                        else if(!is(node,"r")&&!is(node,"bookmarkStart")&&!is(node,"bookmarkEnd"))throw error("TOC_FOOTER_CONTENT_UNSUPPORTED",part);
                    }appendTocFooterLiteral(literals,literal);
                }
                if(controls!=1)throw error("TOC_FOOTER_LITERAL_SCOPE_UNSUPPORTED",part);
                source=new TocFooterPageSource(model.index.sourceSha256,paragraph.sectionId,part,hash(model.entries.get(part)),field.startId,literals);sections.put(paragraph.sectionId,source);
            }result.put(reference.targetBookmark,source);
        }return Collections.unmodifiableMap(result);
    }
    private static boolean tocPageInstruction(String instruction){String value=instruction.trim().replaceAll("(?U)\\s+"," ");return "PAGE".equalsIgnoreCase(value)||"PAGE \\* MERGEFORMAT".equalsIgnoreCase(value);}
    private static Element tocFooterAncestor(Element node,String local){for(Node parent=node.getParentNode();parent instanceof Element;parent=parent.getParentNode())if(is(parent,local))return (Element)parent;return null;}
    private static boolean internalStoryRelation(Element relation){String mode=optionalAttribute(relation,"TargetMode");return mode==null||"Internal".equals(mode);}
    private static void appendTocFooterLiteral(List<String> literals,StringBuilder text){String value=text.toString().trim().replaceAll("(?U)\\s+"," ");if(!value.isEmpty())literals.add(value);text.setLength(0);}
    public static final class TocFooterPageSource {
        private final String sourceSha256,sectionId,part,partSha256,pageFieldId;private final List<String> literals;
        private TocFooterPageSource(String source,String section,String part,String sha,String field,List<String> literals){sourceSha256=source;sectionId=section;this.part=part;partSha256=sha;pageFieldId=field;this.literals=Collections.unmodifiableList(new ArrayList<>(literals));}
        public String getSourceSha256(){return sourceSha256;}public String getSectionId(){return sectionId;}public String getPart(){return part;}public String getPartSha256(){return partSha256;}public String getPageFieldId(){return pageFieldId;}public List<String> getLiterals(){return literals;}
    }
    /** Refresh only displayed TOC titles bound to complete, unique current heading bookmarks. */
    public TocTitleCacheUpdate updateTocTitleCaches(byte[] sourceDocx,String expectedSha256) {
        PackageModel model=load(sourceDocx);if(!model.index.sourceSha256.equals(expectedSha256))throw error("SOURCE_HASH_MISMATCH","toc-title");
        List<TocPageReference> references=tocPageReferences(model);Map<Element,String> replacements=new LinkedHashMap<>(),before=new LinkedHashMap<>();
        List<Map<String,String>> changes=new ArrayList<>(),unknown=new ArrayList<>();
        for(TocPageReference reference:references)try {
            Element heading=headingFlowBookmark(model,reference.targetBookmark);Feature bookmark=null;
            for(Feature feature:model.index.features)if("BOOKMARK".equals(feature.kind)&&reference.targetBookmark.equals(feature.name))bookmark=feature;
            Element headingStart=model.nodes.get(bookmark.startId),headingEnd=model.nodes.get(bookmark.endId);
            if(has(heading,"fldChar")||has(heading,"instrText")||has(heading,"fldSimple")||has(heading,"drawing")||has(heading,"pict")||has(heading,"oMath"))throw error("TOC_TITLE_HEADING_CONTROL_UNSUPPORTED",reference.targetBookmark);
            NodeList headingTexts=heading.getElementsByTagNameNS(W,"t");for(int i=0;i<headingTexts.getLength();i++)if(!headingTexts.item(i).getTextContent().trim().isEmpty()&&!insideFeature(headingTexts.item(i),headingStart,headingEnd))throw error("TOC_TITLE_HEADING_RANGE_INCOMPLETE",reference.targetBookmark);
            String full=editableText(heading).trim();int separator=full.indexOf('\t');
            if(separator<=0||full.indexOf('\n')>=0||full.indexOf('\r')>=0)throw error("TOC_TITLE_HEADING_SEPARATOR_UNSUPPORTED",reference.targetBookmark);
            String number=full.substring(0,separator).trim(),title=full.substring(separator+1).trim();
            if(title.isEmpty()||title.indexOf('\t')>=0)throw error("TOC_TITLE_HEADING_SEPARATOR_UNSUPPORTED",reference.targetBookmark);
            Element field=model.nodes.get(reference.fieldStartId),hyperlink=field;while(hyperlink!=null&&!is(hyperlink,"hyperlink"))hyperlink=hyperlink.getParentNode() instanceof Element?(Element)hyperlink.getParentNode():null;
            StringBuilder label=new StringBuilder(),cached=new StringBuilder();List<Element> titleNodes=new ArrayList<>();int tabs=0;NodeList nodes=hyperlink.getElementsByTagName("*");
            for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);if(node==field)break;
                if(is(node,"tab")){tabs++;continue;}
                if(is(node,"fldChar")||is(node,"instrText")||is(node,"fldSimple")||is(node,"br")||is(node,"cr")||is(node,"drawing")||is(node,"pict"))throw error("TOC_TITLE_ROW_CONTROL_UNSUPPORTED",reference.targetBookmark);
                if(is(node,"t")){if(tabs==0)label.append(node.getTextContent());else if(tabs==1){titleNodes.add(node);cached.append(node.getTextContent());}else if(!node.getTextContent().isEmpty())throw error("TOC_TITLE_ROW_SEPARATOR_UNSUPPORTED",reference.targetBookmark);}
            }
            if(tabs!=2||!number.equals(label.toString().trim()))throw error("TOC_TITLE_LABEL_MISMATCH",reference.targetBookmark);
            if(title.equals(cached.toString().trim()))continue;
            // A changed multi-run title needs explicit formatting review; never move text between styles.
            if(titleNodes.size()!=1)throw error("TOC_TITLE_FORMAT_SCOPE_UNSUPPORTED",reference.targetBookmark);
            Element node=titleNodes.get(0);if(replacements.put(node,title)!=null)throw error("TOC_TITLE_TARGET_AMBIGUOUS",reference.targetBookmark);before.put(node,node.getTextContent());
            Map<String,String> receipt=new LinkedHashMap<>();receipt.put("targetBookmark",reference.targetBookmark);receipt.put("cacheNodeId",MAIN+"#"+path(node));receipt.put("before",node.getTextContent());receipt.put("after",title);changes.add(Collections.unmodifiableMap(receipt));
        }catch(EditException unsupported){Map<String,String> issue=new LinkedHashMap<>();issue.put("targetBookmark",reference.targetBookmark);issue.put("reason",unsupported.getCode());unknown.add(Collections.unmodifiableMap(issue));}
        if(replacements.isEmpty())return new TocTitleCacheUpdate(new DocxEditResult(sourceDocx,model.index),references.size(),changes,unknown);
        byte[] original=model.entries.get(MAIN);Document document=model.xmlParts.get(MAIN);if(!Arrays.equals(original,tocXmlKeepingOriginalDeclaration(document,original)))throw error("TOC_TITLE_XML_NOT_REVERSIBLE","toc-title");
        for(Map.Entry<Element,String> entry:replacements.entrySet())entry.getKey().setTextContent(entry.getValue());byte[] changed=tocXmlKeepingOriginalDeclaration(document,original);
        for(Map.Entry<Element,String> entry:before.entrySet())entry.getKey().setTextContent(entry.getValue());if(!Arrays.equals(original,tocXmlKeepingOriginalDeclaration(document,original)))throw error("TOC_TITLE_XML_NOT_REVERSIBLE","toc-title");
        model.entries.put(MAIN,changed);byte[] bytes=write(model.entries);TemplateIndex index=load(bytes).index;
        for(Map.Entry<String,String> entry:model.index.entryHashes.entrySet())if(!MAIN.equals(entry.getKey())&&!entry.getValue().equals(index.entryHashes.get(entry.getKey())))throw error("TOC_TITLE_PART_CHANGED",entry.getKey());
        return new TocTitleCacheUpdate(new DocxEditResult(bytes,index,model.index.sourceSha256,Collections.emptyList(),Collections.emptyList(),model.index.entryHashes),references.size(),changes,unknown);
    }
    public static final class TocTitleCacheUpdate {
        private final DocxEditResult result;private final int targetCount;private final List<Map<String,String>> changes,unknown;
        private TocTitleCacheUpdate(DocxEditResult result,int targetCount,List<Map<String,String>> changes,List<Map<String,String>> unknown){this.result=result;this.targetCount=targetCount;this.changes=Collections.unmodifiableList(new ArrayList<>(changes));this.unknown=Collections.unmodifiableList(new ArrayList<>(unknown));}
        public DocxEditResult getResult(){return result;}public int getTargetCount(){return targetCount;}
        public List<Map<String,String>> getChanges(){return changes;}public List<Map<String,String>> getUnknown(){return unknown;}
    }
    /** Only complete numeric PAGEREF caches inside the same native TOC may change. */
    public DocxEditResult updateTocPageCaches(byte[] sourceDocx,String expectedSha256,Map<String,String> measuredPages) {
        PackageModel model=load(sourceDocx);
        if(!model.index.sourceSha256.equals(expectedSha256))throw error("SOURCE_HASH_MISMATCH","toc-cache");
        List<TocPageReference> references=tocPageReferences(model);Set<String> targets=new LinkedHashSet<>();
        for(TocPageReference reference:references)targets.add(reference.targetBookmark);
        if(references.isEmpty()||measuredPages==null||!targets.equals(measuredPages.keySet()))throw error("TOC_CACHE_TARGET_SET_MISMATCH","toc-cache");
        byte[] original=model.entries.get(MAIN);Document document=model.xmlParts.get(MAIN);
        Map<Element,String> replacements=new LinkedHashMap<>();
        for(TocPageReference reference:references) {
            String page=measuredPages.get(reference.targetBookmark);
            if(page==null||!page.matches("[1-9][0-9]*")||page.length()!=reference.cachedPage.length())throw error("TOC_CACHE_WIDTH_UNSUPPORTED",reference.fieldStartId);
            Element node=model.nodes.get(reference.cacheNodeId);replacements.put(node,page);
        }
        byte[] changed=tocCacheXmlKeepingOriginalTextSlots(document,original,replacements);
        model.entries.put(MAIN,changed);byte[] result=write(model.entries);TemplateIndex index=load(result).index;
        for(Map.Entry<String,String> entry:model.index.entryHashes.entrySet())if(!MAIN.equals(entry.getKey())&&!entry.getValue().equals(index.entryHashes.get(entry.getKey())))throw error("TOC_CACHE_PART_CHANGED",entry.getKey());
        return new DocxEditResult(result,index,model.index.sourceSha256,Collections.emptyList(),Collections.emptyList(),model.index.entryHashes);
    }
    /** DOM supplies the verified cache nodes; lexical slots preserve every other original UTF-8 byte. */
    private static byte[] tocCacheXmlKeepingOriginalTextSlots(Document document,byte[] original,Map<Element,String> replacements){
        String source=new String(original,StandardCharsets.UTF_8);if(!Arrays.equals(original,source.getBytes(StandardCharsets.UTF_8)))throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");
        Set<String> tags=new LinkedHashSet<>();for(Element target:replacements.keySet())tags.add(target.getTagName());Map<Integer,String> values=new TreeMap<>(Collections.reverseOrder());Set<Element> matched=Collections.newSetFromMap(new IdentityHashMap<>());
        for(String tag:tags){NodeList nodes=document.getElementsByTagName(tag);Matcher slots=Pattern.compile("<"+Pattern.quote(tag)+"(?:\\s[^<>]*?)?(?:/>|>([^<]*)</"+Pattern.quote(tag)+"\\s*>)").matcher(source);int ordinal=0;
            while(slots.find()){
                if(ordinal>=nodes.getLength())throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");Element node=(Element)nodes.item(ordinal++);String replacement=replacements.get(node);if(replacement==null)continue;
                if(!matched.add(node)||slots.group(1)==null||!slots.group(1).matches("[1-9][0-9]*")||!slots.group(1).equals(node.getTextContent())||replacement.length()!=slots.group(1).length()||values.put(slots.start(1),replacement)!=null)throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");
            }
            if(ordinal!=nodes.getLength())throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");
        }
        if(matched.size()!=replacements.size())throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");StringBuilder changed=new StringBuilder(source);for(Map.Entry<Integer,String> entry:values.entrySet())changed.replace(entry.getKey(),entry.getKey()+entry.getValue().length(),entry.getValue());byte[] result=changed.toString().getBytes(StandardCharsets.UTF_8);
        Map<Element,String> before=new LinkedHashMap<>();byte[] expected;
        try{for(Map.Entry<Element,String> entry:replacements.entrySet()){before.put(entry.getKey(),entry.getKey().getTextContent());entry.getKey().setTextContent(entry.getValue());}expected=xml(document);}finally{for(Map.Entry<Element,String> entry:before.entrySet())entry.getKey().setTextContent(entry.getValue());}
        try{if(!Arrays.equals(expected,xml(parse(result))))throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");}catch(EditException invalid){throw invalid;}catch(Exception invalid){throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");}return result;
    }
    /** This cache-only write preserves the source declaration; the complete remaining XML must still roundtrip exactly. */
    private static byte[] tocXmlKeepingOriginalDeclaration(Document document,byte[] original) {
        byte[] rendered=xml(document);
        String sourceHead=new String(original,0,Math.min(original.length,128),StandardCharsets.UTF_8),renderedHead=new String(rendered,0,Math.min(rendered.length,128),StandardCharsets.UTF_8);
        int sourceEnd=sourceHead.indexOf("?>")+2,renderedEnd=renderedHead.indexOf("?>")+2;
        if(!sourceHead.startsWith("<?xml ")||!renderedHead.startsWith("<?xml ")||sourceEnd<2||renderedEnd<2)throw error("TOC_CACHE_XML_NOT_REVERSIBLE","toc-cache");
        byte[] result=new byte[sourceEnd+rendered.length-renderedEnd];System.arraycopy(original,0,result,0,sourceEnd);System.arraycopy(rendered,renderedEnd,result,sourceEnd,rendered.length-renderedEnd);return result;
    }
    public static final class TocPageReference {
        private final String targetBookmark,fieldStartId,cacheNodeId,cachedPage;
        private TocPageReference(String target,String field,String cache,String page){targetBookmark=target;fieldStartId=field;cacheNodeId=cache;cachedPage=page;}
        public String getTargetBookmark(){return targetBookmark;}public String getFieldStartId(){return fieldStartId;}
        public String getCacheNodeId(){return cacheNodeId;}public String getCachedPage(){return cachedPage;}
    }
    private static List<TocPageReference> tocPageReferences(PackageModel model) {
        List<Feature> tocs=new ArrayList<>();
        for(Feature feature:model.index.features)if("FIELD".equals(feature.kind)&&feature.instruction.trim().matches("(?is)^TOC(?:\\s.*)?$"))tocs.add(feature);
        if(tocs.isEmpty())return Collections.emptyList();
        if(tocs.size()!=1||!tocs.get(0).complete||!MAIN.equals(tocs.get(0).part))throw error("TOC_CACHE_SCOPE_UNSUPPORTED","toc-cache");
        Feature toc=tocs.get(0);Element tocStart=model.nodes.get(toc.startId),tocEnd=model.nodes.get(toc.endId);
        List<TocPageReference> result=new ArrayList<>();Set<String> targets=new HashSet<>();NodeList nodes=model.xmlParts.get(MAIN).getElementsByTagName("*");
        for(Feature field:model.index.features)if("FIELD".equals(field.kind)&&MAIN.equals(field.part)&&insideFeature(model.nodes.get(field.startId),tocStart,tocEnd)) {
            java.util.regex.Matcher command=java.util.regex.Pattern.compile("(?is)^\\s*PAGEREF\\s+(\\S+)\\s+\\\\h\\s*$").matcher(field.instruction);
            if(!field.complete||!command.matches())throw error("TOC_CACHE_FIELD_UNSUPPORTED",field.startId);
            String target=command.group(1);if(!targets.add(target))throw error("TOC_CACHE_DUPLICATE_TARGET",target);
            int bookmarkCount=0;for(Feature bookmark:model.index.features)if("BOOKMARK".equals(bookmark.kind)&&target.equals(bookmark.name)&&bookmark.complete)bookmarkCount++;
            if(bookmarkCount!=1)throw error("TOC_CACHE_BOOKMARK_AMBIGUOUS",target);
            Element start=model.nodes.get(field.startId),end=model.nodes.get(field.endId),hyperlink=null;
            for(Node parent=start.getParentNode();parent instanceof Element;parent=parent.getParentNode())if(is(parent,"hyperlink")){hyperlink=(Element)parent;break;}
            if(hyperlink==null||!contains(hyperlink,end)||!target.equals(hyperlink.getAttributeNS(W,"anchor")))throw error("TOC_CACHE_HYPERLINK_MISMATCH",target);
            Element separate=null,cache=null;
            for(int i=0;i<nodes.getLength();i++) {
                Element node=(Element)nodes.item(i);if(!insideFeature(node,start,end))continue;
                if(is(node,"fldChar")){if(!"separate".equals(node.getAttributeNS(W,"fldCharType"))||separate!=null)throw error("TOC_CACHE_FIELD_UNSUPPORTED",field.startId);separate=node;}
                if(is(node,"fldSimple"))throw error("TOC_CACHE_FIELD_UNSUPPORTED",field.startId);
                if(is(node,"t")){if(separate==null||cache!=null||!node.getTextContent().matches("[1-9][0-9]*"))throw error("TOC_CACHE_RESULT_UNSUPPORTED",field.startId);cache=node;}
            }
            if(separate==null||cache==null)throw error("TOC_CACHE_RESULT_UNSUPPORTED",field.startId);
            result.add(new TocPageReference(target,field.startId,MAIN+"#"+path(cache),cache.getTextContent()));
        }
        if(result.isEmpty())throw error("TOC_CACHE_RESULT_UNSUPPORTED",toc.startId);
        return Collections.unmodifiableList(result);
    }
    /** Request-local read-only validation; generation still reloads and edits the original package. */
    public static final class PreparedTemplate {
        private final PackageModel model;
        private PreparedTemplate(PackageModel model){this.model=model;}
        public TemplateIndex getIndex(){return model.index;}
        public void validateTextEdits(SourceEditBatch batch) {
            if(batch==null||!model.index.sourceSha256.equals(batch.expectedSourceSha256))throw error("SOURCE_HASH_MISMATCH","batch");
            for(Edit edit:batch.edits)if(edit==null||!textEdit(edit))throw error("TEXT_REVIEW_OPERATIONS_REQUIRED","batch");
            validateOperations(model,batch);
        }
    }
    public DocxEditResult apply(byte[] sourceDocx, SourceEditBatch batch) {
        return applyWithParagraphBookmarks(sourceDocx,batch,Collections.emptyMap());
    }
    /** Attach invisible persistent markers to the surviving original paragraph objects after edits.
     * Validation and compiler decisions still use the immutable original package/hash. */
    public DocxEditResult applyWithParagraphBookmarks(byte[] sourceDocx,SourceEditBatch batch,Map<String,String> bookmarks) {
        return applyWithParagraphBookmarks(sourceDocx,batch,bookmarks,false);
    }
    public DocxEditResult applyWithParagraphBookmarks(byte[] sourceDocx,SourceEditBatch batch,Map<String,String> bookmarks,boolean markAddedParagraphs) {
        return applyWithParagraphBookmarks(sourceDocx,batch,bookmarks,markAddedParagraphs,null);
    }
    /** Layout uses captured immutable source anchors, before business edits can shift native paths. */
    public DocxEditResult applyWithParagraphBookmarks(byte[] sourceDocx,SourceEditBatch batch,Map<String,String> bookmarks,boolean markAddedParagraphs,NativeLayoutPlan layout) {
        if(sourceDocx!=null)sourceDocx=sourceDocx.clone();
        PackageModel model=load(sourceDocx);
        if(batch==null||!model.index.sourceSha256.equals(batch.expectedSourceSha256))throw error("SOURCE_HASH_MISMATCH","batch");
        CapturedLayout captured=captureLayout(model,layout);
        CapturedStories stories=captureStories(model,layout==null?null:layout.stories);
        if(batch.edits.isEmpty()&&bookmarks.isEmpty()&&layout==null)return new DocxEditResult(sourceDocx,model.index);
        SourceEditBatch originalBatch=batch;batch=validateOperations(model,batch);
        validateStoryBusinessScopes(model,stories,batch);
        List<Edit> ordered=new ArrayList<>(batch.edits);
        validateRemainingRows(model,batch);
        retainRequiredCellParagraphs(model,batch);
        validateFeatureScopes(model,batch);
        List<FeatureImpact> impacts=featureImpacts(model,batch);
        ordered.sort(Comparator.comparing((Edit e)->e.targetId).thenComparing((Edit e)->-e.start));
        Set<Element> explicitlyCleared=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Edit edit:ordered) {
            Element target=model.nodes.get(edit.targetId);
            if(edit.type==EditType.INSERT_TEXT)insertText(target,edit);
            else if(edit.type==EditType.REPLACE_SPAN)replace(model,target,edit);
            else if(insertion(edit))insert(model,target,edit);
            else if(edit.type==EditType.CLEAR_EDITORIAL_CELL) {
                NodeList ps=target.getElementsByTagNameNS(W,"p");for(int i=0;i<ps.getLength();i++)clear((Element)ps.item(i));
            }else if(edit.type==EditType.CLEAR_PARAGRAPH) {clear(target);explicitlyCleared.add(target);}
            else if(edit.type==EditType.CLEAR_PARAGRAPH_AND_NUMBERING) {clear(target);clearNumbering(target);explicitlyCleared.add(target);}
            else if(edit.type==EditType.KEEP_ROW_TOGETHER)keepRowTogether(target);
            else if(edit.type==EditType.DELETE_PARAGRAPH) {
                if(model.retainedCellParagraphs.contains(target))clear(target);
                else target.getParentNode().removeChild(target);
            }else target.getParentNode().removeChild(target);
        }
        List<NativeLayoutChange> layoutChanges=applyLayout(model,captured,explicitlyCleared);
        applyStories(model,stories);
        applySctTitles(model,stories);
        releaseMigratedSctTitleBoundaries(captured,stories,layoutChanges);
        attachParagraphBookmarks(model,bookmarks,markAddedParagraphs);
        Set<String> changedParts=new LinkedHashSet<>();
        if(!bookmarks.isEmpty()||!layoutChanges.isEmpty())changedParts.add(MAIN);
        changedParts.addAll(stories.dirtyParts);
        for(Edit edit:ordered)changedParts.add(edit.targetId.substring(0,edit.targetId.indexOf('#')));
        for(String part:changedParts)model.entries.put(part,xml(model.xmlParts.get(part)));
        if(stories.plan!=null)validateStoryPackageLimits(model);
        byte[] result=write(model.entries);
        TemplateIndex updated=load(result).index;
        return new DocxEditResult(result,updated,model.index.sourceSha256,impacts,ledger(model,originalBatch,updated.sourceSha256),model.index.entryHashes,layout==null?null:layout.profile,layoutChanges,storyLedger(model,stories));
    }

    private static SourceEditBatch validateOperations(PackageModel model,SourceEditBatch batch) {
        Set<String> ids=new HashSet<>();
        for(Edit edit:batch.edits)if(edit==null||edit.operationId==null||edit.operationId.trim().isEmpty()||!ids.add(edit.operationId))throw error("INVALID_OPERATION_ID","batch");
        batch=expand(batch);
        for(Edit edit:batch.edits) {
            Element target=model.nodes.get(edit.targetId);
            if(target==null)throw error("TARGET_NOT_FOUND",edit.operationId);
            String part=edit.targetId.substring(0,edit.targetId.indexOf('#'));Element root=target.getOwnerDocument().getDocumentElement();
            boolean nativeLabel=edit.labelOutsideFields&&!edit.tocFieldLabel&&edit.type==EditType.REPLACE_SPAN
                    &&(part.matches("word/header[0-9]+\\.xml")&&is(root,"hdr")||part.matches("word/footer[0-9]+\\.xml")&&is(root,"ftr"));
            if(!MAIN.equals(part)&&!nativeLabel)throw error("STORY_EDIT_UNSUPPORTED",edit.operationId);
            if(!hash(xml(target)).equals(edit.targetSha256))throw error("TARGET_HASH_MISMATCH",edit.operationId);
            if(textEdit(edit)) {
                validateSpan(target,edit);
                if(edit.labelOutsideFields)validateGlobalLabelFields(target,edit);
                if(edit.donorId!=null) {Element donor=model.nodes.get(edit.donorId);if(!is(donor,"r")||!intrinsicHash(donor).equals(edit.donorSha256))throw error("STYLE_DONOR_MISMATCH",edit.operationId);}
            }
            else if(insertion(edit))validateInsertion(model,target,edit);
            else validateStructural(target,edit);
            for(Edit other:batch.edits) {
                if(other==edit)continue;Element otherTarget=model.nodes.get(other.targetId);
                if(otherTarget==null)continue;
                if(target==otherTarget) {
                    if(insertion(edit)||insertion(other)) {
                        // A text-only paragraph clear keeps this sibling anchor and style donor intact.
                        if((destructive(edit)||destructive(other))&&edit.type!=EditType.CLEAR_PARAGRAPH&&other.type!=EditType.CLEAR_PARAGRAPH)throw error("OVERLAPPING_OPERATIONS",edit.operationId);
                    }else if(!textEdit(edit)||!textEdit(other)||overlappingSpans(edit.start,edit.end,other.start,other.end))throw error("OVERLAPPING_OPERATIONS",edit.operationId);
                }else if(contains(target,otherTarget)||contains(otherTarget,target))throw error("OVERLAPPING_OPERATIONS",edit.operationId);
            }
        }
        return batch;
    }

    public static final class NativeLayoutBoundary {
        private final NodeAnchor table,heading,footer;
        public NativeLayoutBoundary(NodeAnchor table,NodeAnchor heading,NodeAnchor footer){this.table=Objects.requireNonNull(table);this.heading=Objects.requireNonNull(heading);this.footer=Objects.requireNonNull(footer);}
    }
    public static final class NativeLayoutPlan {
        private final String sourceSha256,profile;
        private final NativeStoryPlan stories;
        private final List<NativeLayoutBoundary> boundaries;
        private final List<NodeAnchor> emptyExclusions;
        private final List<NativeLayoutSpacing> emptySpacing;
        private final List<NodeAnchor> cohesiveRows;
        private final List<NodeAnchor> compactGapRows,keepNextParagraphs;
        private final List<NativeLayoutClearedParagraph> clearedParagraphs;
        private final List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks;
        private final List<NativeLayoutParagraphBoundary> paragraphBoundaries;
        private final List<NodeAnchor> latinFontHintParagraphs,latinFontSlotParagraphs,cellKeepNextParagraphs,cellWidowParagraphs,bodyWidowParagraphs;
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions){this(sourceSha256,profile,boundaries,emptyExclusions,Collections.emptyList());}
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,Collections.emptyList());}
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,Collections.emptyList());}
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,Collections.emptyList(),Collections.emptyList());}
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,Collections.emptyList());}
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs,List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,Collections.emptyList());}
        public NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs,List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks,List<NativeLayoutParagraphBoundary> paragraphBoundaries){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,null);}
        private NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs,List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks,List<NativeLayoutParagraphBoundary> paragraphBoundaries,NativeStoryPlan stories){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,Collections.emptyList());}
        private NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs,List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks,List<NativeLayoutParagraphBoundary> paragraphBoundaries,NativeStoryPlan stories,List<NodeAnchor> latinFontHintParagraphs){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,latinFontHintParagraphs,Collections.emptyList(),Collections.emptyList());}
        private NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs,List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks,List<NativeLayoutParagraphBoundary> paragraphBoundaries,NativeStoryPlan stories,List<NodeAnchor> latinFontHintParagraphs,List<NodeAnchor> cellKeepNextParagraphs,List<NodeAnchor> cellWidowParagraphs){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,latinFontHintParagraphs,cellKeepNextParagraphs,cellWidowParagraphs,Collections.emptyList());}
        private NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs,List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks,List<NativeLayoutParagraphBoundary> paragraphBoundaries,NativeStoryPlan stories,List<NodeAnchor> latinFontHintParagraphs,List<NodeAnchor> cellKeepNextParagraphs,List<NodeAnchor> cellWidowParagraphs,List<NodeAnchor> latinFontSlotParagraphs){this(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,latinFontHintParagraphs,cellKeepNextParagraphs,cellWidowParagraphs,latinFontSlotParagraphs,Collections.emptyList());}
        private NativeLayoutPlan(String sourceSha256,String profile,List<NativeLayoutBoundary> boundaries,List<NodeAnchor> emptyExclusions,List<NativeLayoutSpacing> emptySpacing,List<NodeAnchor> cohesiveRows,List<NodeAnchor> compactGapRows,List<NativeLayoutClearedParagraph> clearedParagraphs,List<NodeAnchor> keepNextParagraphs,List<NativeLayoutClearedKeepNextBreak> clearedKeepNextBreaks,List<NativeLayoutParagraphBoundary> paragraphBoundaries,NativeStoryPlan stories,List<NodeAnchor> latinFontHintParagraphs,List<NodeAnchor> cellKeepNextParagraphs,List<NodeAnchor> cellWidowParagraphs,List<NodeAnchor> latinFontSlotParagraphs,List<NodeAnchor> bodyWidowParagraphs){this.sourceSha256=Objects.requireNonNull(sourceSha256);this.profile=Objects.requireNonNull(profile);this.boundaries=Collections.unmodifiableList(new ArrayList<>(boundaries));this.emptyExclusions=Collections.unmodifiableList(new ArrayList<>(emptyExclusions));this.emptySpacing=Collections.unmodifiableList(new ArrayList<>(emptySpacing));this.cohesiveRows=Collections.unmodifiableList(new ArrayList<>(cohesiveRows));this.compactGapRows=Collections.unmodifiableList(new ArrayList<>(compactGapRows));this.clearedParagraphs=Collections.unmodifiableList(new ArrayList<>(clearedParagraphs));this.keepNextParagraphs=Collections.unmodifiableList(new ArrayList<>(keepNextParagraphs));this.clearedKeepNextBreaks=Collections.unmodifiableList(new ArrayList<>(clearedKeepNextBreaks));this.paragraphBoundaries=Collections.unmodifiableList(new ArrayList<>(paragraphBoundaries));this.stories=stories;this.latinFontHintParagraphs=Collections.unmodifiableList(new ArrayList<>(latinFontHintParagraphs));this.cellKeepNextParagraphs=Collections.unmodifiableList(new ArrayList<>(cellKeepNextParagraphs));this.cellWidowParagraphs=Collections.unmodifiableList(new ArrayList<>(cellWidowParagraphs));this.latinFontSlotParagraphs=Collections.unmodifiableList(new ArrayList<>(latinFontSlotParagraphs));this.bodyWidowParagraphs=Collections.unmodifiableList(new ArrayList<>(bodyWidowParagraphs));}
        public NativeLayoutPlan withStories(NativeStoryPlan value){Objects.requireNonNull(value);if(!sourceSha256.equals(value.sourceSha256))throw error("STORY_SOURCE_HASH_MISMATCH",profile);return new NativeLayoutPlan(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,value,latinFontHintParagraphs,cellKeepNextParagraphs,cellWidowParagraphs,latinFontSlotParagraphs,bodyWidowParagraphs);}
        public NativeLayoutPlan withLatinFontHintParagraphs(List<NodeAnchor> value){return new NativeLayoutPlan(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,value,cellKeepNextParagraphs,cellWidowParagraphs,latinFontSlotParagraphs,bodyWidowParagraphs);}
        public NativeLayoutPlan withCellParagraphFlow(List<NodeAnchor> keepNext,List<NodeAnchor> widows){return new NativeLayoutPlan(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,latinFontHintParagraphs,keepNext,widows,latinFontSlotParagraphs,bodyWidowParagraphs);}
        public NativeLayoutPlan withLatinFontSlotParagraphs(List<NodeAnchor> value){return new NativeLayoutPlan(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,latinFontHintParagraphs,cellKeepNextParagraphs,cellWidowParagraphs,value,bodyWidowParagraphs);}
        public NativeLayoutPlan withBodyParagraphWidows(List<NodeAnchor> value){return new NativeLayoutPlan(sourceSha256,profile,boundaries,emptyExclusions,emptySpacing,cohesiveRows,compactGapRows,clearedParagraphs,keepNextParagraphs,clearedKeepNextBreaks,paragraphBoundaries,stories,latinFontHintParagraphs,cellKeepNextParagraphs,cellWidowParagraphs,latinFontSlotParagraphs,value);}
        public NativeStoryPlan getStories(){return stories;}
    }
    /** An unchanged original empty paragraph associated with an exact manual footer. */
    public static final class NativeLayoutSpacing {
        private final NodeAnchor paragraph,footer;
        public NativeLayoutSpacing(NodeAnchor paragraph,NodeAnchor footer){this.paragraph=Objects.requireNonNull(paragraph);this.footer=Objects.requireNonNull(footer);}
    }
    /** Renderer-only padding, conditional on an exact adopted negative heading and cleared body. */
    public static final class NativeLayoutClearedParagraph {
        private final NodeAnchor paragraph,guard;private final String guardText;private final boolean removePageBreak;
        public NativeLayoutClearedParagraph(NodeAnchor paragraph,NodeAnchor guard,String guardText,boolean removePageBreak){this.paragraph=Objects.requireNonNull(paragraph);this.guard=Objects.requireNonNull(guard);this.guardText=Objects.requireNonNull(guardText);this.removePageBreak=removePageBreak;}
    }
    /** An exact stale page break in guidance already cleared by business edits, within a registered heading chain. */
    public static final class NativeLayoutClearedKeepNextBreak {
        private final NodeAnchor paragraph,guard;private final String guardText;
        public NativeLayoutClearedKeepNextBreak(NodeAnchor paragraph,NodeAnchor guard,String guardText){this.paragraph=Objects.requireNonNull(paragraph);this.guard=Objects.requireNonNull(guard);this.guardText=Objects.requireNonNull(guardText);}
    }
    /** Exact heading-to-body chain; only cleared intervening guidance admits a renderer page boundary. */
    public static final class NativeLayoutParagraphBoundary {
        private final NodeAnchor heading,body;private final List<NodeAnchor> gaps;private final boolean compactClearedGaps,releaseTrailingPageBreak,keepBodyLines;
        public NativeLayoutParagraphBoundary(NodeAnchor heading,NodeAnchor body,List<NodeAnchor> gaps){this(heading,body,gaps,false);}
        public NativeLayoutParagraphBoundary(NodeAnchor heading,NodeAnchor body,List<NodeAnchor> gaps,boolean compactClearedGaps){this(heading,body,gaps,compactClearedGaps,false);}
        public NativeLayoutParagraphBoundary(NodeAnchor heading,NodeAnchor body,List<NodeAnchor> gaps,boolean compactClearedGaps,boolean releaseTrailingPageBreak){this(heading,body,gaps,compactClearedGaps,releaseTrailingPageBreak,false);}
        public NativeLayoutParagraphBoundary(NodeAnchor heading,NodeAnchor body,List<NodeAnchor> gaps,boolean compactClearedGaps,boolean releaseTrailingPageBreak,boolean keepBodyLines){this.heading=Objects.requireNonNull(heading);this.body=Objects.requireNonNull(body);this.gaps=Collections.unmodifiableList(new ArrayList<>(gaps));this.compactClearedGaps=compactClearedGaps;this.releaseTrailingPageBreak=releaseTrailingPageBreak;this.keepBodyLines=keepBodyLines;if(releaseTrailingPageBreak&&(!compactClearedGaps||!gaps.isEmpty()))throw error("LAYOUT_TRAILING_PAGE_BREAK_SCOPE_CHANGED",heading.id);if(keepBodyLines&&!compactClearedGaps)throw error("LAYOUT_BODY_LINES_SCOPE_CHANGED",body.id);}
    }
    public static final class NativeLayoutChange {
        private final String action,status,generatedId,layoutId;
        private final NodeAnchor source;
        private NativeLayoutChange(String action,String status,NodeAnchor source,String generatedId){this(action,status,source,generatedId,null);}
        private NativeLayoutChange(String action,String status,NodeAnchor source,String generatedId,String layoutId){this.action=action;this.status=status;this.source=source;this.generatedId=generatedId;this.layoutId=layoutId;}
        public String getAction(){return action;} public String getStatus(){return status;} public NodeAnchor getSource(){return source;} public String getGeneratedId(){return generatedId;} public String getLayoutId(){return layoutId;}
    }
    private static final class CapturedLayout {
        final NativeLayoutPlan plan;
        final List<Element[]> boundaries=new ArrayList<>(),spacing=new ArrayList<>(),cleared=new ArrayList<>();final List<Element> empty=new ArrayList<>(),cohesiveRows=new ArrayList<>(),compactGapRows=new ArrayList<>(),keepNext=new ArrayList<>();
        final Map<String,Element[]> clearedKeepNextBreaks=new LinkedHashMap<>();
        final List<List<Element>> paragraphBoundaries=new ArrayList<>();
        final List<Element> latinFontHintParagraphs=new ArrayList<>(),latinFontSlotParagraphs=new ArrayList<>(),cellKeepNextParagraphs=new ArrayList<>(),cellWidowParagraphs=new ArrayList<>(),bodyWidowParagraphs=new ArrayList<>();
        final Set<Element> sctTitleBoundaryHeadings=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Element,Element> derivedSctTitleBreaks=new LinkedHashMap<>();
        CapturedLayout(NativeLayoutPlan plan){this.plan=plan;}
    }
    private static CapturedLayout captureLayout(PackageModel model,NativeLayoutPlan plan) {
        CapturedLayout result=new CapturedLayout(plan);if(plan==null)return result;
        if(!model.index.sourceSha256.equals(plan.sourceSha256))throw error("LAYOUT_SOURCE_HASH_MISMATCH",plan.profile);
        Set<String> selected=new HashSet<>();
        for(NativeLayoutBoundary boundary:plan.boundaries) {
            Element table=layoutAnchor(model,boundary.table),heading=layoutAnchor(model,boundary.heading),footer=layoutAnchor(model,boundary.footer);
            if(!selected.add(boundary.table.id)||!is(table,"tbl")||!is(table.getParentNode(),"body")||!firstRowHeading(table,heading)||(footer.compareDocumentPosition(table)&Node.DOCUMENT_POSITION_FOLLOWING)==0)throw error("LAYOUT_BOUNDARY_SCOPE_CHANGED",boundary.table.id);
            result.boundaries.add(new Element[]{table,heading});
            if(plan.stories!=null&&plan.stories.sctTitleMigration!=null
                    &&plan.stories.sctTitleMigration.bodyTitles.stream().anyMatch(title->title.source.id.equals(boundary.heading.id))
                    &&sourceBoundaryWithoutHardPagination(model,footer,heading))result.sctTitleBoundaryHeadings.add(heading);
        }
        for(NodeAnchor anchor:plan.emptyExclusions){Element paragraph=layoutAnchor(model,anchor);if(!selected.add(anchor.id)||!is(paragraph.getParentNode(),"body")||!layoutEmpty(paragraph))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",anchor.id);result.empty.add(paragraph);}
        for(NativeLayoutSpacing spacing:plan.emptySpacing){Element paragraph=layoutAnchor(model,spacing.paragraph),footer=layoutAnchor(model,spacing.footer);if(!selected.add(spacing.paragraph.id)||!layoutEmpty(paragraph)||!is(footer.getParentNode(),"body")||!is(footer,"p"))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",spacing.paragraph.id);result.spacing.add(new Element[]{paragraph,footer});}
        Set<String> cohesiveRowIds=new HashSet<>(),compactRowIds=new HashSet<>();
        for(NodeAnchor anchor:plan.cohesiveRows){Element row=layoutAnchor(model,anchor);if(!selected.add(anchor.id))throw error("LAYOUT_ROW_SCOPE_CHANGED",anchor.id);validateStructural(row,Edit.keepRowTogether("native-layout-row",anchor));cohesiveRowIds.add(anchor.id);result.cohesiveRows.add(row);}
        for(NodeAnchor anchor:plan.compactGapRows){Element row=layoutAnchor(model,anchor);if(!compactRowIds.add(anchor.id)||!selected.add(anchor.id)&&!cohesiveRowIds.contains(anchor.id))throw error("LAYOUT_ROW_SCOPE_CHANGED",anchor.id);validateStructural(row,Edit.keepRowTogether("native-layout-gap",anchor));validateNativeGapRow(row,anchor.id);result.compactGapRows.add(row);}
        for(NativeLayoutClearedParagraph item:plan.clearedParagraphs){Element paragraph=layoutAnchor(model,item.paragraph),guard=layoutAnchor(model,item.guard);if(!selected.add(item.paragraph.id)||!is(paragraph.getParentNode(),"body")||!is(guard,"p")||!is(guard.getParentNode(),"body")||!item.guardText.matches("SCC\\d+\\.\\d{3}Not used"))throw error("LAYOUT_CLEARED_SCOPE_CHANGED",item.paragraph.id);validateClearedLayoutParagraph(paragraph,item.removePageBreak,item.paragraph.id);result.cleared.add(new Element[]{paragraph,guard});}
        Set<String> keepIds=new HashSet<>();for(NodeAnchor anchor:plan.keepNextParagraphs)keepIds.add(anchor.id);
        for(NativeLayoutClearedKeepNextBreak item:plan.clearedKeepNextBreaks){Element paragraph=layoutAnchor(model,item.paragraph),guard=layoutAnchor(model,item.guard);if(!keepIds.contains(item.paragraph.id)||!keepIds.contains(item.guard.id)||paragraph==guard||!is(guard,"p")||!is(guard.getParentNode(),"body")||!descendantText(guard,"t").equals(item.guardText)||!hasPageBreak(paragraph)||result.clearedKeepNextBreaks.put(item.paragraph.id,new Element[]{paragraph,guard})!=null)throw error("LAYOUT_KEEP_NEXT_SCOPE_CHANGED",item.paragraph.id);}
        for(NodeAnchor anchor:plan.keepNextParagraphs){Element paragraph=layoutAnchor(model,anchor);boolean clearedBreak=result.clearedKeepNextBreaks.containsKey(anchor.id);if(!selected.add(anchor.id)||!is(paragraph,"p")||!is(paragraph.getParentNode(),"body")||has(paragraph,"sectPr")||has(paragraph,"pageBreakBefore")||hasPageBreak(paragraph)&&!clearedBreak)throw error("LAYOUT_KEEP_NEXT_SCOPE_CHANGED",anchor.id);validateClearedLayoutParagraph(paragraph,clearedBreak,anchor.id);result.keepNext.add(paragraph);}
        Set<String> paragraphBoundaryIds=new HashSet<>(),paragraphBoundaryBodies=new HashSet<>(),paragraphBoundaryGaps=new HashSet<>(),paragraphBoundaryEndpoints=new HashSet<>(),trailingBreakIds=new HashSet<>();
        for(NativeLayoutParagraphBoundary boundary:plan.paragraphBoundaries)if(boundary.releaseTrailingPageBreak&&!trailingBreakIds.add(boundary.heading.id))throw error("LAYOUT_TRAILING_PAGE_BREAK_SCOPE_CHANGED",boundary.heading.id);
        for(NativeLayoutParagraphBoundary boundary:plan.paragraphBoundaries){
            List<Element> chain=new ArrayList<>();Element heading=layoutAnchor(model,boundary.heading),body=layoutAnchor(model,boundary.body);chain.add(heading);for(NodeAnchor gap:boundary.gaps){chain.add(layoutAnchor(model,gap));if(!paragraphBoundaryGaps.add(gap.id))throw error("LAYOUT_PARAGRAPH_BOUNDARY_SCOPE_CHANGED",gap.id);}chain.add(body);
            if(!paragraphBoundaryIds.add(boundary.heading.id)||!paragraphBoundaryBodies.add(boundary.body.id)||boundary.gaps.isEmpty()&&!boundary.compactClearedGaps)throw error("LAYOUT_PARAGRAPH_BOUNDARY_SCOPE_CHANGED",boundary.heading.id);
            paragraphBoundaryEndpoints.add(boundary.heading.id);paragraphBoundaryEndpoints.add(boundary.body.id);
            for(int i=0;i<chain.size();i++){Element node=chain.get(i);if(!is(node,"p")||!is(node.getParentNode(),"body"))throw error("LAYOUT_PARAGRAPH_BOUNDARY_SCOPE_CHANGED",boundary.heading.id);
                String id=i==0?boundary.heading.id:i==chain.size()-1?boundary.body.id:boundary.gaps.get(i-1).id;
                if(trailingBreakIds.contains(id))trailingLayoutPageBreak(node,id);else validateClearedLayoutParagraph(node,false,id);
                if(i>0){Node following=nextNativeFlowSibling(chain.get(i-1));if(following!=node)throw error("LAYOUT_PARAGRAPH_BOUNDARY_SCOPE_CHANGED",boundary.heading.id);}
            }
            if(descendantText(heading,"t").trim().isEmpty()||descendantText(body,"t").trim().isEmpty())throw error("LAYOUT_PARAGRAPH_BOUNDARY_SCOPE_CHANGED",boundary.heading.id);result.paragraphBoundaries.add(chain);
        }
        for(String gap:paragraphBoundaryGaps)if(paragraphBoundaryEndpoints.contains(gap))throw error("LAYOUT_PARAGRAPH_BOUNDARY_SCOPE_CHANGED",gap);
        for(NodeAnchor anchor:plan.latinFontHintParagraphs){Element paragraph=layoutAnchor(model,anchor);if(!selected.add(anchor.id))throw error("LAYOUT_LATIN_HINT_SCOPE_CHANGED",anchor.id);latinHintFonts(paragraph,anchor.id,true);result.latinFontHintParagraphs.add(paragraph);}
        // A font slot may share a conditional cleared-body target; its unchanged source hash is rechecked before application.
        Set<String> fontSlotIds=new HashSet<>();for(NodeAnchor anchor:plan.latinFontSlotParagraphs){Element paragraph=layoutAnchor(model,anchor);if(!fontSlotIds.add(anchor.id)||plan.latinFontHintParagraphs.stream().anyMatch(hint->hint.id.equals(anchor.id)))throw error("LAYOUT_LATIN_FONT_SLOT_SCOPE_CHANGED",anchor.id);latinFontControls(paragraph,anchor.id,true,true);result.latinFontSlotParagraphs.add(paragraph);}
        Set<String> cellFlowIds=new HashSet<>();for(List<NodeAnchor> anchors:Arrays.asList(plan.cellKeepNextParagraphs,plan.cellWidowParagraphs))for(NodeAnchor anchor:anchors){Element paragraph=layoutAnchor(model,anchor);if(!cellFlowIds.add(anchor.id))throw error("LAYOUT_CELL_FLOW_SCOPE_CHANGED",anchor.id);validateCellParagraphFlow(paragraph,anchor.id);(anchors==plan.cellKeepNextParagraphs?result.cellKeepNextParagraphs:result.cellWidowParagraphs).add(paragraph);}
        for(NodeAnchor anchor:plan.bodyWidowParagraphs){Element paragraph=layoutAnchor(model,anchor);if(!selected.add(anchor.id))throw error("LAYOUT_BODY_WIDOW_SCOPE_CHANGED",anchor.id);validateBodyParagraphWidows(paragraph,anchor.id);result.bodyWidowParagraphs.add(paragraph);}
        return result;
    }
    /** Cached page positions cease to require a forced body boundary after its exact title moves to a real header. */
    private static boolean sourceBoundaryWithoutHardPagination(PackageModel model,Element footer,Element heading) {
        if(hasPageBreak(heading)||has(heading,"pageBreakBefore")||has(heading,"sectPr"))return false;
        boolean inside=false;NodeList nodes=model.xmlParts.get(MAIN).getElementsByTagName("*");
        for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);if(node==footer)inside=true;if(node==heading)return inside;
            if(inside&&(is(node,"sectPr")||is(node,"pageBreakBefore")||is(node,"br")&&"page".equals(node.getAttributeNS(W,"type"))))return false;}
        return false;
    }
    private static void releaseMigratedSctTitleBoundaries(CapturedLayout layout,CapturedStories stories,List<NativeLayoutChange> changes) {
        if(layout.derivedSctTitleBreaks.isEmpty())return;
        Set<String> installed=new HashSet<>();Set<Element> cleared=Collections.newSetFromMap(new IdentityHashMap<>());
        for(NativeStoryChange change:stories.changes)if("APPLIED".equals(change.status)) {
            if("INSTALL_SCT_FIRST_TITLE_HEADER".equals(change.action)||"INSTALL_SCT_DEFAULT_TITLE_HEADER".equals(change.action))installed.add(change.action);
            if("CLEAR_EXACT_SCT_BODY_TITLE".equals(change.action)&&change.paragraph!=null)
                for(int i=0;i<stories.sctTitles.policy.bodyTitles.size();i++)if(stories.sctTitles.policy.bodyTitles.get(i).source.id.equals(change.paragraph.id))cleared.add(stories.sctTitles.nodes.get(i));
        }
        if(installed.size()!=2)return;
        for(Map.Entry<Element,Element> entry:layout.derivedSctTitleBreaks.entrySet())if(cleared.contains(entry.getValue())) {
            Element paragraph=entry.getKey();List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)throw error("SCT_TITLE_BOUNDARY_CONTROL_CHANGED",path(paragraph));
            List<Element> breaks=direct(props.get(0),"pageBreakBefore");if(breaks.size()!=1)throw error("SCT_TITLE_BOUNDARY_CONTROL_CHANGED",path(paragraph));
            props.get(0).removeChild(breaks.get(0));
            for(int i=0;i<changes.size();i++){NativeLayoutChange change=changes.get(i);if("page_break_before".equals(change.action)&&Objects.equals(change.generatedId,MAIN+"#"+path(paragraph)))
                changes.set(i,new NativeLayoutChange(change.action,"RELEASED_VERIFIED_SCT_TITLE_MIGRATION",change.source,change.generatedId,change.layoutId));}
        }
    }
    private static Element layoutAnchor(PackageModel model,NodeAnchor anchor){Element node=model.nodes.get(anchor.id);if(!MAIN.equals(anchor.part)||node==null||!hash(xml(node)).equals(anchor.sha256))throw error("LAYOUT_ANCHOR_MISMATCH",anchor.id);return node;}
    private static boolean firstRowHeading(Element table,Element heading){List<Element> rows=direct(table,"tr");if(rows.isEmpty()||!contains(rows.get(0),heading))return false;for(Node parent=heading.getParentNode();parent!=null;parent=parent.getParentNode())if(is(parent,"tbl"))return parent==table;return false;}
    private static boolean layoutEmpty(Element paragraph){if(!is(paragraph,"p"))return false;for(Node child=paragraph.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"pPr"))return false;return paragraph.getElementsByTagNameNS(W,"sectPr").getLength()==0&&paragraph.getElementsByTagNameNS(W,"pageBreakBefore").getLength()==0;}
    private static List<NativeLayoutChange> applyLayout(PackageModel model,CapturedLayout captured,Set<Element> explicitlyCleared) {
        List<NativeLayoutChange> changes=new ArrayList<>();if(captured.plan==null)return changes;Document document=model.xmlParts.get(MAIN);Element root=document.getDocumentElement();
        long nextMarker=0;Set<String> markerNames=new HashSet<>();NodeList markers=document.getElementsByTagNameNS(W,"bookmarkStart");for(int i=0;i<markers.getLength();i++){Element marker=(Element)markers.item(i);markerNames.add(marker.getAttributeNS(W,"name"));try{nextMarker=Math.max(nextMarker,Long.parseLong(marker.getAttributeNS(W,"id"))+1);}catch(NumberFormatException ignored){}}
        Set<String> clearedKeepNextEligibility=new HashSet<>();for(NativeLayoutClearedKeepNextBreak item:captured.plan.clearedKeepNextBreaks){Element[] nodes=captured.clearedKeepNextBreaks.get(item.paragraph.id);if(contains(root,nodes[0])&&contains(root,nodes[1])&&hash(xml(nodes[1])).equals(item.guard.sha256)&&descendantText(nodes[0],"t").trim().isEmpty())clearedKeepNextEligibility.add(item.paragraph.id);}
        // Business edits have finished. Snapshot exact source eligibility before sibling layout controls add properties or markers.
        Set<Element> unchangedParagraphEligibility=Collections.newSetFromMap(new IdentityHashMap<>());
        for(List<NodeAnchor> anchors:Arrays.asList(captured.plan.latinFontSlotParagraphs,captured.plan.latinFontHintParagraphs,captured.plan.bodyWidowParagraphs))for(NodeAnchor anchor:anchors){Element paragraph=model.nodes.get(anchor.id);if(contains(root,paragraph)&&hash(xml(paragraph)).equals(anchor.sha256))unchangedParagraphEligibility.add(paragraph);}
        Set<Integer> paragraphBoundaryEligibility=new HashSet<>();for(int i=0;i<captured.paragraphBoundaries.size();i++){List<Element> chain=captured.paragraphBoundaries.get(i);NativeLayoutParagraphBoundary boundary=captured.plan.paragraphBoundaries.get(i);boolean eligible=contains(root,chain.get(0))&&contains(root,chain.get(chain.size()-1))&&hash(xml(chain.get(0))).equals(boundary.heading.sha256)&&hash(xml(chain.get(chain.size()-1))).equals(boundary.body.sha256);for(int j=1;j<chain.size()-1;j++)eligible=eligible&&contains(root,chain.get(j))&&descendantText(chain.get(j),"t").trim().isEmpty();for(int j=1;j<chain.size();j++){Node following=nextNativeFlowSibling(chain.get(j-1));eligible=eligible&&following==chain.get(j);}if(eligible)paragraphBoundaryEligibility.add(i);}
        for(int i=0;i<captured.empty.size();i++){Element paragraph=captured.empty.get(i);NodeAnchor anchor=captured.plan.emptyExclusions.get(i);if(!contains(root,paragraph)){changes.add(new NativeLayoutChange("remove_verified_empty","SKIPPED_REMOVED",anchor,null));continue;}if(!layoutEmpty(paragraph)||!hash(xml(paragraph)).equals(anchor.sha256))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",anchor.id);paragraph.getParentNode().removeChild(paragraph);changes.add(new NativeLayoutChange("remove_verified_empty","APPLIED",anchor,null));}
        for(int i=0;i<captured.boundaries.size();i++) {
            Element table=captured.boundaries.get(i)[0],heading=captured.boundaries.get(i)[1];NodeAnchor anchor=captured.plan.boundaries.get(i).table;
            if(!contains(root,table)||!contains(root,heading)){changes.add(new NativeLayoutChange("page_break_before","SKIPPED_REMOVED",anchor,null));continue;}
            if(!is(table.getParentNode(),"body")||!firstRowHeading(table,heading)||!hash(xml(heading)).equals(captured.plan.boundaries.get(i).heading.sha256))throw error("LAYOUT_BOUNDARY_SCOPE_CHANGED",anchor.id);
            Element paragraph=document.createElementNS(W,"w:p"),props=document.createElementNS(W,"w:pPr");
            props.appendChild(document.createElementNS(W,"w:pageBreakBefore"));compactSpacing(document,props);paragraph.appendChild(props);table.getParentNode().insertBefore(paragraph,table);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("page_break_before","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
            if(captured.sctTitleBoundaryHeadings.contains(heading))captured.derivedSctTitleBreaks.put(paragraph,heading);
        }
        for(int i=0;i<captured.spacing.size();i++){
            Element paragraph=captured.spacing.get(i)[0],footer=captured.spacing.get(i)[1];NativeLayoutSpacing spacing=captured.plan.emptySpacing.get(i);NodeAnchor anchor=spacing.paragraph;
            if(!contains(root,paragraph)||!contains(root,footer)){changes.add(new NativeLayoutChange("compact_verified_empty_spacing","SKIPPED_REMOVED",anchor,null));continue;}
            // A validated business clear can discard this manual footer with its branch. Its
            // renderer spacing no longer applies; preserve the original empty paragraph.
            if(explicitlyCleared.contains(footer)&&!hash(xml(footer)).equals(spacing.footer.sha256)){changes.add(new NativeLayoutChange("compact_verified_empty_spacing","SKIPPED_CLEARED_FOOTER",anchor,MAIN+"#"+path(paragraph)));continue;}
            if(!layoutEmpty(paragraph)||!hash(xml(paragraph)).equals(anchor.sha256)||!hash(xml(footer)).equals(spacing.footer.sha256))throw error("LAYOUT_EMPTY_SCOPE_CHANGED",anchor.id);
            Element props=direct(paragraph,"pPr").isEmpty()?document.createElementNS(W,"w:pPr"):direct(paragraph,"pPr").get(0);if(props.getParentNode()==null)paragraph.insertBefore(props,paragraph.getFirstChild());compactSpacing(document,props);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("compact_verified_empty_spacing","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        for(int i=0;i<captured.cleared.size();i++){
            Element paragraph=captured.cleared.get(i)[0],guard=captured.cleared.get(i)[1];NativeLayoutClearedParagraph item=captured.plan.clearedParagraphs.get(i);NodeAnchor anchor=item.paragraph;
            if(!contains(root,paragraph)||!contains(root,guard)){changes.add(new NativeLayoutChange("compact_verified_cleared_spacing","SKIPPED_REMOVED",anchor,null));continue;}
            if(!descendantText(guard,"t").replaceAll("(?U)\\s+","").equals(item.guardText.replace(" ",""))||!descendantText(paragraph,"t").trim().isEmpty()){changes.add(new NativeLayoutChange("compact_verified_cleared_spacing","SKIPPED_NOT_CLEARED",anchor,MAIN+"#"+path(paragraph)));continue;}
            validateClearedLayoutParagraph(paragraph,item.removePageBreak,anchor.id);
            if(item.removePageBreak){List<Element> remove=new ArrayList<>();NodeList br=paragraph.getElementsByTagNameNS(W,"br");for(int j=0;j<br.getLength();j++)if("page".equals(((Element)br.item(j)).getAttributeNS(W,"type")))remove.add((Element)br.item(j));for(Element node:remove)node.getParentNode().removeChild(node);}
            Element props=direct(paragraph,"pPr").isEmpty()?document.createElementNS(W,"w:pPr"):direct(paragraph,"pPr").get(0);if(props.getParentNode()==null)paragraph.insertBefore(props,paragraph.getFirstChild());compactSpacing(document,props);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|cleared|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("compact_verified_cleared_spacing","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        for(int i=0;i<captured.keepNext.size();i++){
            Element paragraph=captured.keepNext.get(i);NodeAnchor anchor=captured.plan.keepNextParagraphs.get(i);if(!contains(root,paragraph)){changes.add(new NativeLayoutChange("keep_verified_paragraph_with_next","SKIPPED_REMOVED",anchor,null));continue;}
            boolean clearedBreak=captured.clearedKeepNextBreaks.containsKey(anchor.id);if(clearedBreak&&!clearedKeepNextEligibility.contains(anchor.id)){changes.add(new NativeLayoutChange("keep_verified_paragraph_with_next","SKIPPED_NOT_CLEARED",anchor,MAIN+"#"+path(paragraph)));continue;}
            if(!hash(xml(paragraph)).equals(anchor.sha256)&&!descendantText(paragraph,"t").trim().isEmpty())throw error("LAYOUT_KEEP_NEXT_SCOPE_CHANGED",anchor.id);validateClearedLayoutParagraph(paragraph,clearedBreak,anchor.id);
            if(clearedBreak){List<Element> remove=new ArrayList<>();NodeList br=paragraph.getElementsByTagNameNS(W,"br");for(int j=0;j<br.getLength();j++)if("page".equals(((Element)br.item(j)).getAttributeNS(W,"type")))remove.add((Element)br.item(j));for(Element node:remove)node.getParentNode().removeChild(node);}
            keepWithNext(paragraph);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|keep-next|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange(clearedBreak?"keep_verified_cleared_guidance_with_next":"keep_verified_paragraph_with_next","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        for(int i=0;i<captured.paragraphBoundaries.size();i++){List<Element> chain=captured.paragraphBoundaries.get(i);NativeLayoutParagraphBoundary boundary=captured.plan.paragraphBoundaries.get(i);Element heading=chain.get(0);NodeAnchor anchor=boundary.heading;if(!paragraphBoundaryEligibility.contains(i)){changes.add(new NativeLayoutChange(boundary.compactClearedGaps?"keep_verified_heading_flow":"page_break_before_verified_heading","SKIPPED_NOT_CLEARED",anchor,contains(root,heading)?MAIN+"#"+path(heading):null));continue;}
            if(boundary.compactClearedGaps){
                // Only the exact heading and cleared gaps keep with the next paragraph; optional body lines never extend the list chain.
                for(int j=0;j<chain.size()-1;j++){Element paragraph=chain.get(j);NodeAnchor source=j==0?boundary.heading:boundary.gaps.get(j-1);
                    if(j==0&&boundary.releaseTrailingPageBreak){
                        releaseTrailingLayoutPageBreak(paragraph,source.id);String releasedId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|heading-flow-trailing-break|"+source.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(releasedId))throw error("LAYOUT_BOOKMARK_CONFLICT",source.id);attachBookmark(document,paragraph,releasedId,nextMarker++);changes.add(new NativeLayoutChange("release_verified_trailing_page_break","APPLIED",source,MAIN+"#"+path(paragraph),releasedId));
                    }
                    validateClearedLayoutParagraph(paragraph,false,source.id);
                    if(j>0){List<Element> values=direct(paragraph,"pPr");Element props=values.isEmpty()?document.createElementNS(W,"w:pPr"):values.get(0);if(values.isEmpty())paragraph.insertBefore(props,paragraph.getFirstChild());compactSpacing(document,props);String compactId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|heading-flow-compact|"+source.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(compactId))throw error("LAYOUT_BOOKMARK_CONFLICT",source.id);attachBookmark(document,paragraph,compactId,nextMarker++);changes.add(new NativeLayoutChange("compact_verified_cleared_spacing","APPLIED",source,MAIN+"#"+path(paragraph),compactId));}
                    keepWithNext(paragraph);keepHeadingFlowLines(paragraph);String keepId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|heading-flow-keep|"+source.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(keepId))throw error("LAYOUT_BOOKMARK_CONFLICT",source.id);attachBookmark(document,paragraph,keepId,nextMarker++);changes.add(new NativeLayoutChange("keep_verified_heading_flow","APPLIED",source,MAIN+"#"+path(paragraph),keepId));
                }
                if(boundary.keepBodyLines){Element body=chain.get(chain.size()-1);NodeAnchor source=boundary.body;validateClearedLayoutParagraph(body,false,source.id);if(direct(body,"pPr").isEmpty())body.insertBefore(document.createElementNS(W,"w:pPr"),body.getFirstChild());keepHeadingFlowLines(body);
                    String bodyId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|heading-flow-body-lines|"+source.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(bodyId))throw error("LAYOUT_BOOKMARK_CONFLICT",source.id);attachBookmark(document,body,bodyId,nextMarker++);changes.add(new NativeLayoutChange("keep_verified_boundary_body_lines","APPLIED",source,MAIN+"#"+path(body),bodyId));}
                continue;
            }
            pageBreakBeforeHeading(heading);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|heading-break|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,heading,layoutId,nextMarker++);changes.add(new NativeLayoutChange("page_break_before_verified_heading","APPLIED",anchor,MAIN+"#"+path(heading),layoutId));}
        for(int i=0;i<captured.latinFontSlotParagraphs.size();i++){
            Element paragraph=captured.latinFontSlotParagraphs.get(i);NodeAnchor anchor=captured.plan.latinFontSlotParagraphs.get(i);
            if(!contains(root,paragraph)){changes.add(new NativeLayoutChange("normalize_verified_latin_font_slot","SKIPPED_REMOVED",anchor,null));continue;}
            if(!unchangedParagraphEligibility.contains(paragraph)){changes.add(new NativeLayoutChange("normalize_verified_latin_font_slot","SKIPPED_CHANGED",anchor,MAIN+"#"+path(paragraph)));continue;}
            for(Element fonts:latinFontControls(paragraph,anchor.id,true,true))fonts.removeAttributeNS(W,"eastAsia");
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|latin-font-slot|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("normalize_verified_latin_font_slot","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        // Captured source hashes were checked together; apply hints before row markers change the same paragraph.
        for(int i=0;i<captured.latinFontHintParagraphs.size();i++){
            Element paragraph=captured.latinFontHintParagraphs.get(i);NodeAnchor anchor=captured.plan.latinFontHintParagraphs.get(i);
            if(!contains(root,paragraph)){changes.add(new NativeLayoutChange("normalize_verified_latin_font_hint","SKIPPED_REMOVED",anchor,null));continue;}
            if(!unchangedParagraphEligibility.contains(paragraph)){changes.add(new NativeLayoutChange("normalize_verified_latin_font_hint","SKIPPED_CHANGED",anchor,MAIN+"#"+path(paragraph)));continue;}
            for(Element fonts:latinHintFonts(paragraph,anchor.id,true))fonts.removeAttributeNS(W,"hint");
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|latin-hint|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("normalize_verified_latin_font_hint","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        for(int i=0;i<captured.cohesiveRows.size();i++){
            Element row=captured.cohesiveRows.get(i);NodeAnchor anchor=captured.plan.cohesiveRows.get(i);
            if(!contains(root,row)){changes.add(new NativeLayoutChange("keep_verified_row_together","SKIPPED_REMOVED",anchor,null));continue;}
            validateStructural(row,Edit.keepRowTogether("native-layout-row",anchor));keepRowTogether(row);
            NodeList paragraphs=row.getElementsByTagNameNS(W,"p");if(paragraphs.getLength()==0)throw error("LAYOUT_ROW_SCOPE_CHANGED",anchor.id);Element paragraph=(Element)paragraphs.item(0);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|row|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("keep_verified_row_together","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        for(int i=0;i<captured.compactGapRows.size();i++){
            Element row=captured.compactGapRows.get(i);NodeAnchor anchor=captured.plan.compactGapRows.get(i);
            if(!contains(root,row)){changes.add(new NativeLayoutChange("compact_verified_row_gaps","SKIPPED_REMOVED",anchor,null));continue;}
            validateStructural(row,Edit.keepRowTogether("native-layout-gap",anchor));validateNativeGapRow(row,anchor.id);
            for(Element props:direct(row,"trPr"))for(Element height:direct(props,"trHeight"))props.removeChild(height);
            NodeList paragraphs=row.getElementsByTagNameNS(W,"p");for(int p=0;p<paragraphs.getLength();p++){Element props=direct((Element)paragraphs.item(p),"pPr").get(0),gap=direct(props,"spacing").get(0);for(String attr:Arrays.asList("before","after","beforeLines","afterLines"))gap.setAttributeNS(W,"w:"+attr,"0");}
            Element paragraph=(Element)paragraphs.item(0);String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|gap|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);
            changes.add(new NativeLayoutChange("compact_verified_row_gaps","APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        for(boolean keepNext:new boolean[]{true,false}){List<Element> paragraphs=keepNext?captured.cellKeepNextParagraphs:captured.cellWidowParagraphs;List<NodeAnchor> anchors=keepNext?captured.plan.cellKeepNextParagraphs:captured.plan.cellWidowParagraphs;String action=keepNext?"keep_verified_cell_paragraph_with_next":"keep_verified_cell_paragraph_widows";
            for(int i=0;i<paragraphs.size();i++){Element paragraph=paragraphs.get(i);NodeAnchor anchor=anchors.get(i);if(!contains(root,paragraph)||explicitlyCleared.contains(paragraph)){changes.add(new NativeLayoutChange(action,contains(root,paragraph)?"SKIPPED_CLEARED":"SKIPPED_REMOVED",anchor,null));continue;}validateCellParagraphFlow(paragraph,anchor.id);if(keepNext){keepWithNext(paragraph);controlWidows(paragraph);}else controlWidows(paragraph);
                String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|"+action+"|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);changes.add(new NativeLayoutChange(action,"APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));}}
        for(int i=0;i<captured.bodyWidowParagraphs.size();i++){
            Element paragraph=captured.bodyWidowParagraphs.get(i);NodeAnchor anchor=captured.plan.bodyWidowParagraphs.get(i);String action="keep_verified_body_paragraph_widows";
            if(!contains(root,paragraph)||explicitlyCleared.contains(paragraph)){changes.add(new NativeLayoutChange(action,contains(root,paragraph)?"SKIPPED_CLEARED":"SKIPPED_REMOVED",anchor,null));continue;}
            if(!unchangedParagraphEligibility.contains(paragraph)){changes.add(new NativeLayoutChange(action,"SKIPPED_CHANGED",anchor,MAIN+"#"+path(paragraph)));continue;}
            validateBodyParagraphWidows(paragraph,anchor.id);controlWidows(paragraph);
            String layoutId="CL"+hash((captured.plan.sourceSha256+"|"+captured.plan.profile+"|"+action+"|"+anchor.id).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!markerNames.add(layoutId))throw error("LAYOUT_BOOKMARK_CONFLICT",anchor.id);attachBookmark(document,paragraph,layoutId,nextMarker++);changes.add(new NativeLayoutChange(action,"APPLIED",anchor,MAIN+"#"+path(paragraph),layoutId));
        }
        // Later insertions may shift earlier BODY paths. Resolve IDs only after the complete layout.
        List<NativeLayoutChange> finalChanges=new ArrayList<>();int boundaryIndex=0;
        for(NativeLayoutChange change:changes){if("page_break_before".equals(change.action)){Element table=captured.boundaries.get(boundaryIndex++)[0];String id="APPLIED".equals(change.status)?MAIN+"#"+path((Element)table.getPreviousSibling()):null;finalChanges.add(new NativeLayoutChange(change.action,change.status,change.source,id,change.layoutId));}else finalChanges.add(change);}
        return finalChanges;
    }
    /** Exact registered Latin runs and ordinary typography only; source fonts, sizes and spaces stay unchanged. */
    private static List<Element> latinHintFonts(Element paragraph,String id,boolean requireHint){return latinFontControls(paragraph,id,requireHint,false);}
    /** Slot cleanup keeps paragraph alignment and every other font attribute; only exact registered ASCII source runs qualify. */
    private static List<Element> latinFontControls(Element paragraph,String id,boolean required,boolean fontSlot){
        return latinFontControls(paragraph,id,required,fontSlot,false);
    }
    private static List<Element> latinFontControls(Element paragraph,String id,boolean required,boolean fontSlot,boolean renderingCopy){
        String code=fontSlot?"LAYOUT_LATIN_FONT_SLOT_SCOPE_CHANGED":"LAYOUT_LATIN_HINT_SCOPE_CHANGED";
        String paragraphText=descendantText(paragraph,"t");
        if(!is(paragraph,"p")||!latinHintText(paragraphText,true)||fontSlot&&!printableAscii(paragraphText))throw error(code,id);
        if(fontSlot&&(has(paragraph,"rPrChange")||has(paragraph,"pPrChange")))throw error(code,id);
        List<Element> result=new ArrayList<>();
        for(Node child=paragraph.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element){
            if(is(child,"pPr")||is(child,"bookmarkStart")||is(child,"bookmarkEnd")||is(child,"proofErr"))continue;
            if(!is(child,"r"))throw error(code,id);Element run=(Element)child;
            for(Node item=run.getFirstChild();item!=null;item=item.getNextSibling())if(item instanceof Element&&!is(item,"rPr")&&!is(item,"t")&&!is(item,"tab"))throw error(code,id);
            String text=descendantText(run,"t");if(!latinHintText(text,false)||fontSlot&&!printableAscii(text))throw error(code,id);
            if(text.isEmpty())continue;if(!fontSlot&&!renderingCopy&&!direct(run,"tab").isEmpty())throw error(code,id);
            List<Element> props=direct(run,"rPr");if(props.size()>1)throw error(code,id);if(props.isEmpty())continue;
            List<Element> fonts=direct(props.get(0),"rFonts");if(fonts.size()>1)throw error(code,id);
            if(fonts.size()==1&&(fontSlot?fonts.get(0).hasAttributeNS(W,"eastAsia"):"eastAsia".equals(fonts.get(0).getAttributeNS(W,"hint")))){
                if(fontSlot&&!direct(run,"tab").isEmpty())throw error(code,id);result.add(fonts.get(0));
            }
        }
        if(required&&result.isEmpty())throw error(code,id);return result;
    }
    private static boolean printableAscii(String text){return text.codePoints().allMatch(cp->cp>=0x20&&cp<=0x7e);}
    private static boolean latinHintText(String text,boolean requireLetter){
        boolean latin=false;
        for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);offset+=Character.charCount(cp);boolean letter=Character.isLetter(cp)&&Character.UnicodeScript.of(cp)==Character.UnicodeScript.LATIN;
            if(letter){latin=true;continue;}
            if(cp>=0x20&&cp<=0x7e||Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp>=0x2010&&cp<=0x2015||cp>=0x2018&&cp<=0x201f||cp==0x2026||cp==0xa7)continue;
            return false;
        }
        return !requireLetter||latin;
    }
    /** Only source-verified minimum row heights and explicit paragraph gaps; fonts, line spacing and cells are retained. */
    private static void validateNativeGapRow(Element row,String id){
        List<Element> properties=direct(row,"trPr");if(properties.size()!=1)throw error("LAYOUT_ROW_GAP_SCOPE_CHANGED",id);List<Element> heights=direct(properties.get(0),"trHeight");if(heights.size()!=1||!Arrays.asList("","atLeast").contains(heights.get(0).getAttributeNS(W,"hRule")))throw error("LAYOUT_ROW_GAP_SCOPE_CHANGED",id);
        NodeList paragraphs=row.getElementsByTagNameNS(W,"p");if(paragraphs.getLength()==0)throw error("LAYOUT_ROW_GAP_SCOPE_CHANGED",id);for(int i=0;i<paragraphs.getLength();i++){List<Element> props=direct((Element)paragraphs.item(i),"pPr");if(props.size()!=1||direct(props.get(0),"spacing").size()!=1)throw error("LAYOUT_ROW_GAP_SCOPE_CHANGED",id);Element spacing=direct(props.get(0),"spacing").get(0);boolean explicit=false;for(String attr:Arrays.asList("before","after","beforeLines","afterLines")){String value=spacing.getAttributeNS(W,attr);if(!value.isEmpty()){if(!value.matches("\\d+"))throw error("LAYOUT_ROW_GAP_SCOPE_CHANGED",id);explicit=true;}}if(!explicit)throw error("LAYOUT_ROW_GAP_SCOPE_CHANGED",id);}
    }
    private static boolean hasCompactNativeRowGaps(Element row){
        for(Element props:direct(row,"trPr"))if(!direct(props,"trHeight").isEmpty())return false;NodeList paragraphs=row.getElementsByTagNameNS(W,"p");if(paragraphs.getLength()==0)return false;for(int i=0;i<paragraphs.getLength();i++){List<Element> props=direct((Element)paragraphs.item(i),"pPr");if(props.size()!=1||direct(props.get(0),"spacing").size()!=1)return false;Element spacing=direct(props.get(0),"spacing").get(0);for(String attr:Arrays.asList("before","after","beforeLines","afterLines"))if(!"0".equals(spacing.getAttributeNS(W,attr)))return false;}return true;
    }

    /** CT_PPr is ordered: new spacing precedes ind/jc/rPr and preserves all existing siblings. */
    private static boolean hasPageBreak(Element paragraph){NodeList nodes=paragraph.getElementsByTagNameNS(W,"br");for(int i=0;i<nodes.getLength();i++)if("page".equals(((Element)nodes.item(i)).getAttributeNS(W,"type")))return true;return false;}
    private static void validateClearedLayoutParagraph(Element paragraph,boolean allowPageBreak,String id){
        if(!is(paragraph,"p")||has(paragraph,"sectPr")||has(paragraph,"pageBreakBefore"))throw error("LAYOUT_CLEARED_SCOPE_CHANGED",id);
        NodeList nodes=paragraph.getElementsByTagName("*");for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);boolean properties=false;for(Node parent=node;parent!=paragraph;parent=parent.getParentNode())if(is(parent,"pPr")||is(parent,"rPr")){properties=true;break;}if(properties)continue;
            // Native render metadata and legacy semantic text wrappers remain present.
            if(!W.equals(node.getNamespaceURI())||!Arrays.asList("r","t","tab","bookmarkStart","bookmarkEnd","proofErr","br","lastRenderedPageBreak","smartTag","smartTagPr","attr").contains(node.getLocalName())||is(node,"br")&&(!allowPageBreak||!"page".equals(node.getAttributeNS(W,"type")))||!validLegacyLayoutInline(node))throw error("LAYOUT_CLEARED_INLINE_UNSUPPORTED",id);
        }
    }
    /** The exact bound lead may release one final run break; following text or any second break is rejected. */
    private static Element trailingLayoutPageBreak(Element paragraph,String id){
        validateClearedLayoutParagraph(paragraph,true,id);NodeList breaks=paragraph.getElementsByTagNameNS(W,"br");
        if(breaks.getLength()!=1)throw error("LAYOUT_TRAILING_PAGE_BREAK_SCOPE_CHANGED",id);Element value=(Element)breaks.item(0);
        if(!is(value.getParentNode(),"r")||value.hasChildNodes()||value.getAttributes().getLength()!=1||!"page".equals(value.getAttributeNS(W,"type")))throw error("LAYOUT_TRAILING_PAGE_BREAK_SCOPE_CHANGED",id);
        NodeList nodes=paragraph.getElementsByTagName("*");for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);if((value.compareDocumentPosition(node)&Node.DOCUMENT_POSITION_FOLLOWING)==0)continue;boolean properties=false;for(Node parent=node;parent!=paragraph;parent=parent.getParentNode())if(is(parent,"pPr")||is(parent,"rPr")){properties=true;break;}if(!properties&&(is(node,"tab")||is(node,"br")||is(node,"t")&&!node.getTextContent().isEmpty()))throw error("LAYOUT_TRAILING_PAGE_BREAK_SCOPE_CHANGED",id);}
        return value;
    }
    private static void releaseTrailingLayoutPageBreak(Element paragraph,String id){
        byte[] original=xml(paragraph);Element value=trailingLayoutPageBreak(paragraph,id);Node parent=value.getParentNode(),next=value.getNextSibling();parent.removeChild(value);parent.insertBefore(value,next);
        if(!Arrays.equals(original,xml(paragraph)))throw error("LAYOUT_TRAILING_PAGE_BREAK_NOT_REVERSIBLE",id);parent.removeChild(value);
    }
    private static boolean validLegacyLayoutInline(Element node){
        if(is(node,"lastRenderedPageBreak"))return is(node.getParentNode(),"r")&&!node.hasChildNodes();
        if(is(node,"attr"))return is(node.getParentNode(),"smartTagPr")&&!node.hasChildNodes();
        if(is(node,"smartTagPr")){if(!is(node.getParentNode(),"smartTag"))return false;for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"attr"))return false;}
        if(is(node,"smartTag")){if(!is(node.getParentNode(),"p")&&!is(node.getParentNode(),"smartTag"))return false;for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"smartTagPr")&&!is(child,"smartTag")&&!is(child,"r"))return false;}
        return true;
    }
    private static void pageBreakBeforeHeading(Element heading){Document document=heading.getOwnerDocument();List<Element> values=direct(heading,"pPr");Element props=values.isEmpty()?document.createElementNS(W,"w:pPr"):values.get(0);if(values.isEmpty())heading.insertBefore(props,heading.getFirstChild());Element value=document.createElementNS(W,"w:pageBreakBefore");value.setAttributeNS(W,"w:val","1");Node later=null;Set<String> afterBreak=Set.of("framePr","widowControl","numPr","suppressLineNumbers","pBdr","shd","tabs","suppressAutoHyphens","kinsoku","wordWrap","overflowPunct","topLinePunct","autoSpaceDE","autoSpaceDN","bidi","adjustRightInd","snapToGrid","spacing","ind","contextualSpacing","mirrorIndents","suppressOverlap","jc","textDirection","textAlignment","textboxTightWrap","outlineLvl","divId","cnfStyle","rPr","sectPr","pPrChange");for(Node child=props.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&afterBreak.contains(child.getLocalName())){later=child;break;}props.insertBefore(value,later);}
    private static void keepWithNext(Element paragraph){Document document=paragraph.getOwnerDocument();List<Element> values=direct(paragraph,"pPr");Element props=values.isEmpty()?document.createElementNS(W,"w:pPr"):values.get(0);if(values.isEmpty())paragraph.insertBefore(props,paragraph.getFirstChild());List<Element> existing=direct(props,"keepNext");if(!existing.isEmpty()){existing.get(0).setAttributeNS(W,"w:val","1");return;}Element value=document.createElementNS(W,"w:keepNext");value.setAttributeNS(W,"w:val","1");Node before=props.getFirstChild();if(is(before,"pStyle"))before=before.getNextSibling();props.insertBefore(value,before);}
    /** Keep one verified native paragraph intact without adding any keep-with-next control. */
    private static void keepHeadingFlowLines(Element paragraph){Document document=paragraph.getOwnerDocument();Element props=direct(paragraph,"pPr").get(0);List<Element> existing=direct(props,"keepLines");if(existing.size()>1)throw error("LAYOUT_HEADING_FLOW_CONTROL_CHANGED",path(paragraph));if(existing.size()==1){if(!Arrays.asList("","1","true","on").contains(existing.get(0).getAttributeNS(W,"val")))existing.get(0).setAttributeNS(W,"w:val","1");return;}Element value=document.createElementNS(W,"w:keepLines");value.setAttributeNS(W,"w:val","1");Node before=null;for(Node child=props.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&!Set.of("pStyle","keepNext").contains(child.getLocalName())){before=child;break;}props.insertBefore(value,before);}
    private static void validateCellParagraphFlow(Element paragraph,String id){if(!is(paragraph,"p")||!is(paragraph.getParentNode(),"tc")&&!(is(paragraph.getParentNode(),"body")&&editableText(paragraph).isEmpty()))throw error("LAYOUT_CELL_FLOW_SCOPE_CHANGED",id);validateClearedLayoutParagraph(paragraph,false,id);List<Element> props=direct(paragraph,"pPr");if(props.size()>1||!props.isEmpty()&&(direct(props.get(0),"keepNext").size()>1||direct(props.get(0),"widowControl").size()>1))throw error("LAYOUT_CELL_FLOW_SCOPE_CHANGED",id);}
    /** Explicit paragraph control overrides the template table styles' disabled widow protection. */
    private static void validateBodyParagraphWidows(Element paragraph,String id){
        if(!is(paragraph,"p")||!is(paragraph.getParentNode(),"body")||editableText(paragraph).trim().isEmpty())throw error("LAYOUT_BODY_WIDOW_SCOPE_CHANGED",id);validateClearedLayoutParagraph(paragraph,false,id);List<Element> props=direct(paragraph,"pPr");if(props.size()>1)throw error("LAYOUT_BODY_WIDOW_SCOPE_CHANGED",id);if(!props.isEmpty()){List<Element> values=direct(props.get(0),"widowControl");if(values.size()>1||!values.isEmpty()&&!Arrays.asList("","0","1","true","false","on","off").contains(values.get(0).getAttributeNS(W,"val")))throw error("LAYOUT_BODY_WIDOW_SCOPE_CHANGED",id);}
    }
    private static void controlWidows(Element paragraph){Document document=paragraph.getOwnerDocument();List<Element> values=direct(paragraph,"pPr");Element props=values.isEmpty()?document.createElementNS(W,"w:pPr"):values.get(0);if(values.isEmpty())paragraph.insertBefore(props,paragraph.getFirstChild());List<Element> existing=direct(props,"widowControl");if(!existing.isEmpty()){existing.get(0).setAttributeNS(W,"w:val","1");return;}Element value=document.createElementNS(W,"w:widowControl");value.setAttributeNS(W,"w:val","1");Node before=null;for(Node child=props.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&!Set.of("pStyle","keepNext","keepLines","pageBreakBefore","framePr").contains(child.getLocalName())){before=child;break;}props.insertBefore(value,before);}
    private static void compactSpacing(Document document,Element props){
        Element value=direct(props,"spacing").isEmpty()?document.createElementNS(W,"w:spacing"):direct(props,"spacing").get(0);if(value.getParentNode()==null){Node later=null;Set<String> afterSpacing=Set.of("ind","contextualSpacing","mirrorIndents","suppressOverlap","jc","textDirection","textAlignment","textboxTightWrap","outlineLvl","divId","cnfStyle","rPr","sectPr","pPrChange");for(Node child=props.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&afterSpacing.contains(child.getLocalName())){later=child;break;}props.insertBefore(value,later);}
            value.setAttributeNS(W,"w:before","0");value.setAttributeNS(W,"w:after","0");value.setAttributeNS(W,"w:line","1");value.setAttributeNS(W,"w:lineRule","exact");
    }

    private static void attachParagraphBookmarks(PackageModel model,Map<String,String> bookmarks,boolean markAddedParagraphs) {
        Document document=model.xmlParts.get(MAIN);Element root=document.getDocumentElement();
        Set<String> names=new HashSet<>();long nextId=0;
        NodeList starts=document.getElementsByTagNameNS(W,"bookmarkStart");
        for(int i=0;i<starts.getLength();i++) {
            Element start=(Element)starts.item(i);names.add(start.getAttributeNS(W,"name"));
            try {nextId=Math.max(nextId,Long.parseLong(start.getAttributeNS(W,"id"))+1);}catch(NumberFormatException ignored) { }
        }
        for(Map.Entry<String,String> entry:bookmarks.entrySet()) {
            Element paragraph=model.nodes.get(entry.getKey());String name=entry.getValue();
            if(paragraph==null||!is(paragraph,"p"))throw error("BINDING_PARAGRAPH_INVALID",entry.getKey());
            if(!contains(root,paragraph))continue; // Removed source targets remain explicit in the sidecar.
            if(name==null||!name.matches("CS[A-Za-z0-9]{1,38}")||!names.add(name))throw error("BINDING_BOOKMARK_CONFLICT",entry.getKey());
            attachBookmark(document,paragraph,name,nextId++);
        }
        if(markAddedParagraphs) {
            Set<Element> original=Collections.newSetFromMap(new IdentityHashMap<Element,Boolean>());original.addAll(model.nodes.values());
            NodeList paragraphs=document.getElementsByTagNameNS(W,"p");
            for(int i=0;i<paragraphs.getLength();i++) {Element paragraph=(Element)paragraphs.item(i);if(original.contains(paragraph)||new Paragraph(MAIN,path(paragraph),paragraph,i+1).getCatalogText().trim().isEmpty())continue;
                String name="CS"+hash((model.index.getSourceSha256()+"|added|"+path(paragraph)).getBytes(StandardCharsets.UTF_8)).substring(0,28);if(!names.add(name))throw error("BINDING_BOOKMARK_CONFLICT",path(paragraph));attachBookmark(document,paragraph,name,nextId++);
            }
        }
    }
    private static void attachBookmark(Document document,Element paragraph,String name,long numericId) {
        String id=String.valueOf(numericId);Element start=document.createElementNS(W,"w:bookmarkStart"),end=document.createElementNS(W,"w:bookmarkEnd");
        start.setAttributeNS(W,"w:id",id);start.setAttributeNS(W,"w:name",name);end.setAttributeNS(W,"w:id",id);
        Node first=paragraph.getFirstChild();if(is(first,"pPr"))first=first.getNextSibling();paragraph.insertBefore(start,first);paragraph.appendChild(end);
    }

    public static final class TemplateIndex {
        private final String sourceSha256;
        private final List<Paragraph> mainParagraphs;
        private final Map<String,String> entryHashes;
        private final Map<String,NodeAnchor> anchors;
        private final List<Feature> features;
        private final List<Paragraph> allParagraphs;
        private TemplateIndex(String hash,List<Paragraph> paragraphs,Map<String,String> hashes,Map<String,NodeAnchor> anchors,List<Feature> features,List<Paragraph> all) {
            sourceSha256=hash;mainParagraphs=Collections.unmodifiableList(new ArrayList<>(paragraphs));
            entryHashes=Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
            this.anchors=Collections.unmodifiableMap(new LinkedHashMap<>(anchors));
            this.features=Collections.unmodifiableList(new ArrayList<>(features));allParagraphs=Collections.unmodifiableList(new ArrayList<>(all));
        }
        public String getSourceSha256() { return sourceSha256; }
        public List<Paragraph> getMainParagraphs() { return mainParagraphs; }
        public Map<String,String> getEntryHashes() { return entryHashes; }
        /** One-based descendant w:p ordinal in the main document, matching the verified source catalog. */
        public Paragraph mainParagraph(int ordinal) { return mainParagraphs.get(ordinal-1); }
        public NodeAnchor anchor(String id){NodeAnchor anchor=anchors.get(id);if(anchor==null)throw error("TARGET_NOT_FOUND",id);return anchor;}
        public Collection<NodeAnchor> getAnchors(){return anchors.values();}
        public List<Feature> getFeatures(){return features;}
        public List<Paragraph> getParagraphs(){return allParagraphs;}
        public Paragraph paragraph(String id){for(Paragraph p:allParagraphs)if(p.id.equals(id))return p;throw error("TARGET_NOT_FOUND",id);}
    }
    public static final class Paragraph {
        private final String id,part,path,nativeNodePath,sha256,text,catalogText,cellId,rowId,tableId,sectionId;
        private final int ordinal;
        private final byte[] snapshotXml;
        private final boolean nativeRowCohesion;
        private final boolean compactNativeRowGaps;
        private Paragraph(String part,String path,Element node,int ordinal) {
            this(part,path,node,ordinal,node.getOwnerDocument().getElementsByTagNameNS(W,"sectPr"));
        }
        private Paragraph(String part,String path,Element node,int ordinal,NodeList sections) {
            this.part=part;this.path=path;nativeNodePath=OerNativeDocxObservation.path(node);id=part+"#"+path;this.ordinal=ordinal;
            snapshotXml=xml(node);sha256=hash(snapshotXml);catalogText=descendantText(node,"t");text=editableText(node);
            cellId=ancestorId(part,node,"tc");rowId=ancestorId(part,node,"tr");tableId=ancestorId(part,node,"tbl");
            boolean together=false,gaps=false;for(Node parent=node.getParentNode();parent!=null;parent=parent.getParentNode())if(is(parent,"tr")){List<Element> properties=direct((Element)parent,"trPr");if(properties.size()==1){List<Element> controls=direct(properties.get(0),"cantSplit");together=controls.size()==1&&!Arrays.asList("0","false","off").contains(controls.get(0).getAttributeNS(W,"val"));}gaps=DocxTemplateEditor.hasCompactNativeRowGaps((Element)parent);break;}nativeRowCohesion=together;compactNativeRowGaps=gaps;
            String section=null;
            for(int i=0;i<sections.getLength();i++)if((node.compareDocumentPosition(sections.item(i))&Node.DOCUMENT_POSITION_FOLLOWING)!=0) {section=part+"#"+path((Element)sections.item(i));break;}
            sectionId=section;
        }
        public String getId(){return id;} public String getPart(){return part;} public String getPath(){return path;}
        public String getNativeNodePath(){return nativeNodePath;}
        public String getSha256(){return sha256;} public String getText(){return text;} public String getCatalogText(){return catalogText;}
        public int getOrdinal(){return ordinal;}
        public String getCellId(){return cellId;} public String getRowId(){return rowId;} public String getTableId(){return tableId;}
        public String getSectionId(){return sectionId;}
        /** Verify the native empty control itself, rather than trusting its previous ledger. */
        public boolean hasCompactEmptyLayoutSpacing(){
            try{Element paragraph=parse(snapshotXml).getDocumentElement();for(Node child=paragraph.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"pPr")&&!is(child,"bookmarkStart")&&!is(child,"bookmarkEnd"))return false;
                if(paragraph.getElementsByTagNameNS(W,"pageBreakBefore").getLength()!=0||paragraph.getElementsByTagNameNS(W,"sectPr").getLength()!=0)return false;List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)return false;List<Element> spacing=direct(props.get(0),"spacing");if(spacing.size()!=1)return false;Element value=spacing.get(0);NamedNodeMap attributes=value.getAttributes();int count=0;for(int i=0;i<attributes.getLength();i++){Node attribute=attributes.item(i);if(javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()))continue;if(!W.equals(attribute.getNamespaceURI())||!Set.of("before","after","line","lineRule").contains(attribute.getLocalName()))return false;count++;}if(count!=4)return false;return "0".equals(value.getAttributeNS(W,"before"))&&"0".equals(value.getAttributeNS(W,"after"))&&"1".equals(value.getAttributeNS(W,"line"))&&"exact".equals(value.getAttributeNS(W,"lineRule"));
            }catch(Exception failure){return false;}
        }
        public boolean hasCompactClearedLayoutSpacing(){try{Element paragraph=parse(snapshotXml).getDocumentElement();validateClearedLayoutParagraph(paragraph,false,id);if(!descendantText(paragraph,"t").trim().isEmpty())return false;List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)return false;List<Element> spacing=direct(props.get(0),"spacing");if(spacing.size()!=1)return false;Element value=spacing.get(0);return "0".equals(value.getAttributeNS(W,"before"))&&"0".equals(value.getAttributeNS(W,"after"))&&"1".equals(value.getAttributeNS(W,"line"))&&"exact".equals(value.getAttributeNS(W,"lineRule"));}catch(Exception invalid){return false;}}
        public boolean hasNativeKeepNext(){try{Element paragraph=parse(snapshotXml).getDocumentElement();List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)return false;List<Element> values=direct(props.get(0),"keepNext");return values.size()==1&&"1".equals(values.get(0).getAttributeNS(W,"val"));}catch(Exception invalid){return false;}}
        public boolean hasReleasedNativeTrailingPageBreak(){try{Element paragraph=parse(snapshotXml).getDocumentElement();validateHeadingFlowParagraph(paragraph,true,id);return !has(paragraph,"br")&&hasNativeHeadingFlow();}catch(Exception invalid){return false;}}
        public boolean hasNativeHeadingFlow(){try{if(!hasNativeKeepNext())return false;Element paragraph=parse(snapshotXml).getDocumentElement();List<Element> props=direct(paragraph,"pPr"),values=direct(props.get(0),"keepLines");return values.size()==1&&Arrays.asList("","1","true","on").contains(values.get(0).getAttributeNS(W,"val"));}catch(Exception invalid){return false;}}
        public boolean hasNativeBodyLines(){try{Element paragraph=parse(snapshotXml).getDocumentElement();List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)return false;List<Element> values=direct(props.get(0),"keepLines");return values.size()==1&&Arrays.asList("","1","true","on").contains(values.get(0).getAttributeNS(W,"val"));}catch(Exception invalid){return false;}}
        public boolean hasNativeWidowControl(){try{Element paragraph=parse(snapshotXml).getDocumentElement();List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)return false;List<Element> values=direct(props.get(0),"widowControl");return values.size()==1&&"1".equals(values.get(0).getAttributeNS(W,"val"));}catch(Exception invalid){return false;}}
        public boolean hasNativeCellKeepNext(){return hasNativeKeepNext()&&hasNativeWidowControl();}
        public boolean hasNativeHeadingPageBreak(){try{Element paragraph=parse(snapshotXml).getDocumentElement();List<Element> props=direct(paragraph,"pPr");if(props.size()!=1)return false;List<Element> values=direct(props.get(0),"pageBreakBefore");return values.size()==1&&"1".equals(values.get(0).getAttributeNS(W,"val"));}catch(Exception invalid){return false;}}
        public boolean hasNormalizedNativeLatinFontHints(){try{return latinHintFonts(parse(snapshotXml).getDocumentElement(),id,false).isEmpty();}catch(Exception invalid){return false;}}
        public boolean hasNormalizedNativeLatinFontSlots(){try{return latinFontControls(parse(snapshotXml).getDocumentElement(),id,false,true).isEmpty();}catch(Exception invalid){return false;}}
        public boolean hasClearedNativeKeepNext(){try{Element paragraph=parse(snapshotXml).getDocumentElement();return hasNativeKeepNext()&&descendantText(paragraph,"t").trim().isEmpty()&&!hasPageBreak(paragraph);}catch(Exception invalid){return false;}}
        public boolean hasNativeRowCohesion(){return nativeRowCohesion;}
        public boolean hasCompactNativeRowGaps(){return compactNativeRowGaps;}
        /** Blank plain text may complete an explicitly selected row; native controls and opaque inline nodes may not. */
        public boolean isPlainEmpty() {
            if(!text.trim().isEmpty())return false;
            try {
                for(Segment segment:segments(parse(snapshotXml).getDocumentElement()))if(!segment.safe)return false;
                return true;
            }catch(Exception e){throw error("PARAGRAPH_STRUCTURE_UNAVAILABLE",id);}
        }
        public List<RunInfo> getRuns() {
            try {
                Element p=parse(snapshotXml).getDocumentElement();List<RunInfo> runs=new ArrayList<>();int offset=0,ordinal=0;
                for(Node child=p.getFirstChild();child!=null;child=child.getNextSibling()) {
                    String value=editableText(child);
                    if(is(child,"r"))runs.add(new RunInfo(id+"/"+child.getNodeName()+"["+(++ordinal)+"]",(Element)child,offset,offset+value.length(),value));
                    offset+=value.length();
                }
                return Collections.unmodifiableList(runs);
            }catch(Exception e){throw error("RUN_INDEX_UNAVAILABLE",id);}
        }
    }
    public static final class RunInfo {
        private final String id,sha256,text,propertiesXml,propertiesSha256;
        private final int start,end;
        private RunInfo(String id,Element run,int start,int end,String text) {
            this.id=id;sha256=intrinsicHash(run);this.start=start;this.end=end;this.text=text;
            List<Element> properties=direct(run,"rPr");propertiesXml=properties.isEmpty()?"":new String(xml(properties.get(0)),StandardCharsets.UTF_8);
            propertiesSha256=properties.isEmpty()?hash(new byte[0]):intrinsicHash(properties.get(0));
        }
        public String getId(){return id;} public String getSha256(){return sha256;} public String getText(){return text;}
        public String getPropertiesXml(){return propertiesXml;} public String getPropertiesSha256(){return propertiesSha256;}
        public int getStart(){return start;} public int getEnd(){return end;}
    }
    public static final class NodeAnchor {
        private final String id,part,path,nativeNodePath,sha256,kind;
        private final List<String> paragraphIds;
        private NodeAnchor(String part,Element node) {
            this.part=part;path=path(node);nativeNodePath=OerNativeDocxObservation.path(node);id=part+"#"+path;sha256=hash(xml(node));kind=node.getLocalName();
            List<String> ids=new ArrayList<>();NodeList ps=node.getElementsByTagNameNS(W,"p");
            if(is(node,"p"))ids.add(id);
            for(int i=0;i<ps.getLength();i++)ids.add(part+"#"+path((Element)ps.item(i)));
            paragraphIds=Collections.unmodifiableList(ids);
        }
        public String getId(){return id;} public String getPart(){return part;} public String getPath(){return path;}
        public String getNativeNodePath(){return nativeNodePath;}
        public String getSha256(){return sha256;} public String getKind(){return kind;} public List<String> getParagraphIds(){return paragraphIds;}
    }
    public static final class SourceEditBatch {
        private final String expectedSourceSha256;
        private final List<Edit> edits;
        public SourceEditBatch(String expectedSourceSha256, List<Edit> edits) {
            this.expectedSourceSha256=expectedSourceSha256;
            this.edits=Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(edits,"edits")));
        }
    }
    public enum EditType { REPLACE_SPAN, INSERT_TEXT, CLEAR_EDITORIAL_CELL, CLEAR_PARAGRAPH, CLEAR_PARAGRAPH_AND_NUMBERING, DELETE_PARAGRAPH, DELETE_ROW, DELETE_TABLE, REMOVE_NODES, KEEP_ROW_TOGETHER, INSERT_BEFORE, INSERT_AFTER }
    public static final class Edit {
        private final String operationId,targetId,targetSha256,expectedText,replacement,donorId,donorSha256;
        private final boolean labelOutsideFields,tocFieldLabel;
        private final int start,end;
        private final EditType type;
        private final List<String> lines;
        private final List<NodeAnchor> targets;
        private Edit(String id,Paragraph p,int start,int end,String expected,String replacement) {
            this(id,p,start,end,expected,replacement,false);
        }
        private Edit(String id,Paragraph p,int start,int end,String expected,String replacement,boolean labelOutsideFields) {
            this(id,p,start,end,expected,replacement,labelOutsideFields,false);
        }
        private Edit(String id,Paragraph p,int start,int end,String expected,String replacement,boolean labelOutsideFields,boolean tocFieldLabel) {
            this(id,p,start,end,expected,replacement,labelOutsideFields,tocFieldLabel,EditType.REPLACE_SPAN);
        }
        private Edit(String id,Paragraph p,int start,int end,String expected,String replacement,boolean labelOutsideFields,boolean tocFieldLabel,EditType type) {
            operationId=id;targetId=p.id;targetSha256=p.sha256;this.start=start;this.end=end;
            expectedText=expected;this.replacement=replacement;
            this.labelOutsideFields=labelOutsideFields;this.tocFieldLabel=tocFieldLabel;
            this.type=type;
            donorId=null;donorSha256=null;lines=Collections.emptyList();targets=Collections.emptyList();
        }
        private Edit(String id,String target,String sha,EditType type) {
            labelOutsideFields=false;tocFieldLabel=false;
            operationId=id;targetId=target;targetSha256=sha;this.type=type;start=0;end=0;expectedText=null;replacement=null;
            donorId=null;donorSha256=null;lines=Collections.emptyList();targets=Collections.emptyList();
        }
        private Edit(String id,Paragraph anchor,boolean after,List<String> lines,Paragraph donor) {
            labelOutsideFields=false;tocFieldLabel=false;
            operationId=id;targetId=anchor.id;targetSha256=anchor.sha256;type=after?EditType.INSERT_AFTER:EditType.INSERT_BEFORE;
            start=0;end=0;expectedText=null;replacement=null;donorId=donor.id;donorSha256=donor.sha256;
            this.lines=Collections.unmodifiableList(new ArrayList<>(lines));targets=Collections.emptyList();
        }
        private Edit(String id,List<NodeAnchor> targets) {
            labelOutsideFields=false;tocFieldLabel=false;
            operationId=id;targetId=null;targetSha256=null;type=EditType.REMOVE_NODES;start=0;end=0;expectedText=null;replacement=null;
            donorId=null;donorSha256=null;lines=Collections.emptyList();this.targets=Collections.unmodifiableList(new ArrayList<>(targets));
        }
        public static Edit replaceSpan(String id,Paragraph p,int start,int end,String expected,String replacement) {
            return new Edit(id,p,start,end,expected,replacement);
        }
        public static Edit insertText(String id,Paragraph p,int offset,String text) {
            return new Edit(id,p,offset,offset,"",text,false,false,EditType.INSERT_TEXT);
        }
        /** Catalog offsets count only descendant w:t. Mapping across controls or opaque features is rejected. */
        public static Edit replaceCatalogSpan(String id,Paragraph p,int start,int end,String expected,String replacement) {
            return catalogSpan(id,p,start,end,expected,replacement,false);
        }
        /** Explicit label-only edit. Native paragraph fields and the enclosing hyperlink remain intact. */
        public static Edit replaceCatalogLabelOutsideFields(String id,Paragraph p,int start,int end,String expected,String replacement) {
            return catalogSpan(id,p,start,end,expected,replacement,true);
        }
        /** Exact caller-bound TOC title only; apply verifies the complete outer TOC field in its story. */
        public static Edit replaceCatalogTocLabel(String id,Paragraph p,int start,int end,String expected,String replacement) {
            return catalogSpan(id,p,start,end,expected,replacement,true,true);
        }
        private static Edit catalogSpan(String id,Paragraph p,int start,int end,String expected,String replacement,boolean labelOutsideFields) {
            return catalogSpan(id,p,start,end,expected,replacement,labelOutsideFields,false);
        }
        private static Edit catalogSpan(String id,Paragraph p,int start,int end,String expected,String replacement,boolean labelOutsideFields,boolean tocFieldLabel) {
            if(start<0||end<start||end>p.catalogText.length()||!p.catalogText.substring(start,end).equals(expected))throw error("TEXT_EXPECTATION_MISMATCH",id);
            if(start==end)throw error("EMPTY_SPAN_UNSUPPORTED",id);
            try {
                int catalog=0,actualStart=-1,actualEnd=-1;
                for(Segment segment:segments(parse(p.snapshotXml).getDocumentElement())) {
                    if(!is(segment.node,"t"))continue;
                    int length=segment.node.getTextContent().length();
                    if(actualStart<0&&start>=catalog&&start<catalog+length)actualStart=segment.start+start-catalog;
                    if(end>catalog&&end<=catalog+length)actualEnd=segment.start+end-catalog;
                    catalog+=length;
                }
                if(actualStart<0||actualEnd<actualStart)throw error("CATALOG_MAPPING_UNSUPPORTED",id);
                if(actualEnd-actualStart!=end-start)throw error("UNSUPPORTED_INLINE_FEATURE",id);
                Edit edit=new Edit(id,p,actualStart,actualEnd,p.text.substring(actualStart,actualEnd),replacement,labelOutsideFields,tocFieldLabel);
                validateSpan(parse(p.snapshotXml).getDocumentElement(),edit);
                return edit;
            }catch(EditException e){throw e;}catch(Exception e){throw error("CATALOG_MAPPING_UNSUPPORTED",id);}
        }
        /** The caller supplies an explicitly adopted editorial cell; no text/style heuristic selects it. */
        public static Edit clearEditorialCell(String id,NodeAnchor cell){return new Edit(id,cell.id,cell.sha256,EditType.CLEAR_EDITORIAL_CELL);}
        public static Edit clearParagraph(String id,Paragraph p){return new Edit(id,p.id,p.sha256,EditType.CLEAR_PARAGRAPH);}
        /** Explicit adopted removal of paragraph text and its direct automatic label; other properties remain. */
        public static Edit clearParagraphAndNumbering(String id,Paragraph p){return new Edit(id,p.id,p.sha256,EditType.CLEAR_PARAGRAPH_AND_NUMBERING);}
        public static Edit deleteParagraph(String id,Paragraph p){return new Edit(id,p.id,p.sha256,EditType.DELETE_PARAGRAPH);}
        public static Edit deleteRow(String id,NodeAnchor row){return new Edit(id,row.id,row.sha256,EditType.DELETE_ROW);}
        /** Prevents this source-bound row from splitting without changing its content or design. */
        public static Edit keepRowTogether(String id,NodeAnchor row){return new Edit(id,row.id,row.sha256,EditType.KEEP_ROW_TOGETHER);}
        public static Edit insertParagraphs(String id,Paragraph anchor,boolean after,List<String> lines,Paragraph donor){return new Edit(id,anchor,after,lines,donor);}
        /** Each selected node has its own exact source hash. No numeric range is inferred. */
        public static Edit removeNodes(String id,List<NodeAnchor> nodes){return new Edit(id,nodes);}
        public String getOperationId(){return operationId;} public EditType getType(){return type;}
        public Edit withStyleDonor(RunInfo donor){if(type!=EditType.REPLACE_SPAN||labelOutsideFields)throw error("RUN_STYLE_DONOR_OPERATION_UNSUPPORTED",operationId);return new Edit(this,donor);}
        private Edit(Edit original,RunInfo donor) {
            labelOutsideFields=original.labelOutsideFields;tocFieldLabel=original.tocFieldLabel;
            operationId=original.operationId;targetId=original.targetId;targetSha256=original.targetSha256;expectedText=original.expectedText;replacement=original.replacement;
            start=original.start;end=original.end;type=original.type;lines=original.lines;targets=original.targets;donorId=donor.id;donorSha256=donor.sha256;
        }
    }
    public static final class DocxEditResult {
        private final byte[] docxBytes;
        private final TemplateIndex index;
        private final String originalSourceSha256;
        private final List<FeatureImpact> affectedFeatures;
        private final List<AppliedEdit> ledger;
        private final Map<String,String> preservedEntryHashes;
        private final String nativeLayoutProfile;
        private final List<NativeLayoutChange> nativeLayoutLedger;
        private final List<NativeStoryChange> nativeStoryLedger;
        private DocxEditResult(byte[] bytes,TemplateIndex index){this(bytes,index,index.sourceSha256,Collections.emptyList(),Collections.emptyList(),index.entryHashes);}
        private DocxEditResult(byte[] bytes,TemplateIndex index,String source,List<FeatureImpact> impacts,List<AppliedEdit> ledger,Map<String,String> originalHashes) {
            this(bytes,index,source,impacts,ledger,originalHashes,null,Collections.emptyList());
        }
        private DocxEditResult(byte[] bytes,TemplateIndex index,String source,List<FeatureImpact> impacts,List<AppliedEdit> ledger,Map<String,String> originalHashes,String layoutProfile,List<NativeLayoutChange> layoutLedger) {
            this(bytes,index,source,impacts,ledger,originalHashes,layoutProfile,layoutLedger,Collections.emptyList());
        }
        private DocxEditResult(byte[] bytes,TemplateIndex index,String source,List<FeatureImpact> impacts,List<AppliedEdit> ledger,Map<String,String> originalHashes,String layoutProfile,List<NativeLayoutChange> layoutLedger,List<NativeStoryChange> storyLedger) {
            docxBytes=bytes.clone();this.index=index;originalSourceSha256=source;affectedFeatures=Collections.unmodifiableList(new ArrayList<>(impacts));
            nativeLayoutProfile=layoutProfile;nativeLayoutLedger=Collections.unmodifiableList(new ArrayList<>(layoutLedger));
            nativeStoryLedger=Collections.unmodifiableList(new ArrayList<>(storyLedger));
            this.ledger=Collections.unmodifiableList(new ArrayList<>(ledger));Map<String,String> preserved=new LinkedHashMap<>();
            for(Map.Entry<String,String> entry:originalHashes.entrySet())if(entry.getValue().equals(index.entryHashes.get(entry.getKey())))preserved.put(entry.getKey(),entry.getValue());
            preservedEntryHashes=Collections.unmodifiableMap(preserved);
        }
        public byte[] getDocxBytes() { return docxBytes.clone(); }
        public TemplateIndex getIndex(){return index;}
        public String getDocxSha256(){return index.getSourceSha256();}
        public List<FeatureImpact> getAffectedFeatures(){return affectedFeatures;}
        public String getOriginalSourceSha256(){return originalSourceSha256;}
        public List<AppliedEdit> getLedger(){return ledger;} public Map<String,String> getPreservedEntryHashes(){return preservedEntryHashes;}
        public String getNativeLayoutProfile(){return nativeLayoutProfile;} public List<NativeLayoutChange> getNativeLayoutLedger(){return nativeLayoutLedger;}
        public List<NativeStoryChange> getNativeStoryLedger(){return nativeStoryLedger;}
    }
    public static final class AppliedEdit {
        private final String operationId,sourceSha256,generatedSha256,donorId,donorSha256,expectedText,replacement;
        private final EditType type;private final int start,end;private final List<TargetChange> targets;private final List<String> insertedLines,insertedParagraphIds;
        private AppliedEdit(Edit edit,String source,String generated,List<TargetChange> targets,RunInfo donor,List<String> insertedParagraphIds) {
            operationId=edit.operationId;sourceSha256=source;generatedSha256=generated;type=edit.type;
            donorId=edit.donorId!=null?edit.donorId:donor==null?null:donor.id;donorSha256=edit.donorSha256!=null?edit.donorSha256:donor==null?null:donor.sha256;
            expectedText=edit.expectedText;replacement=edit.replacement;start=edit.start;end=edit.end;
            this.targets=Collections.unmodifiableList(new ArrayList<>(targets));insertedLines=edit.lines;this.insertedParagraphIds=Collections.unmodifiableList(new ArrayList<>(insertedParagraphIds));
        }
        public String getOperationId(){return operationId;} public String getSourceSha256(){return sourceSha256;} public String getGeneratedSha256(){return generatedSha256;}
        public EditType getType(){return type;} public List<TargetChange> getTargets(){return targets;} public String getDonorId(){return donorId;}
        public String getDonorSha256(){return donorSha256;} public String getExpectedText(){return expectedText;} public String getReplacement(){return replacement;}
        public int getStart(){return start;} public int getEnd(){return end;}
        public List<String> getInsertedLines(){return insertedLines;}
        public List<String> getInsertedParagraphIds(){return insertedParagraphIds;}
    }
    public static final class TargetChange {
        private final NodeAnchor source;private final String afterSha256,status,generatedId;
        private TargetChange(NodeAnchor source,Element after,boolean retainedCellParagraph) {
            this.source=source;afterSha256=after.getParentNode()==null?null:hash(xml(after));
            generatedId=afterSha256==null?null:source.part+"#"+path(after);
            status=afterSha256==null?"REMOVED":retainedCellParagraph?"CLEARED_LAST_CELL_PARAGRAPH":"APPLIED";
        }
        public NodeAnchor getSource(){return source;} public String getAfterSha256(){return afterSha256;} public String getStatus(){return status;}
        public String getGeneratedId(){return generatedId;}
    }
    public static final class Feature {
        private final String kind,id,part,path,name,instruction,startId,endId;
        private final boolean complete;
        private Feature(String kind,String part,Element start,Element end,String name,String instruction) {
            this.kind=kind;this.part=part;path=path(start);id=part+"#"+path;this.name=name;this.instruction=instruction;
            startId=id;endId=end==null?null:part+"#"+path(end);complete=end!=null;
        }
        public String getKind(){return kind;} public String getId(){return id;} public String getPart(){return part;}
        public String getPath(){return path;} public String getName(){return name;} public String getInstruction(){return instruction;}
        public boolean isComplete(){return complete;} public String getStartId(){return startId;} public String getEndId(){return endId;}
    }
    public static final class FeatureImpact {
        private final Feature feature;private final String status;private final List<String> operationIds;
        private FeatureImpact(Feature feature,String status,List<String> ids){this.feature=feature;this.status=status;operationIds=Collections.unmodifiableList(new ArrayList<>(ids));}
        public Feature getFeature(){return feature;} public String getStatus(){return status;} public List<String> getOperationIds(){return operationIds;}
    }
    public static final class EditException extends IllegalArgumentException {
        private final String code,operationId;
        private EditException(String code,String operationId){super(code+": "+operationId);this.code=code;this.operationId=operationId;}
        public String getCode(){return code;} public String getOperationId(){return operationId;}
    }
    private static EditException error(String code,String id){return new EditException(code,id);}
    /** Source-bound native-story ledger; a generated part has no fabricated original hash. */
    public static final class NativeStoryChange {
        private final String action,status,generatedPartName,generatedPartSha256,generatedRelationshipId,oldText;
        private final Section section;private final Binding binding;private final Paragraph paragraph;
        private final int sourcePageOrdinal;private final NativeProperty originalProperty;private final Map<String,String> newAttributes;
        private StaticFooterSourceFormat sourceFormatting;
        private NativeStoryChange(String action,String status,Section section,Binding binding,String part,String relationship,Paragraph paragraph,int page,String oldText,NativeProperty originalProperty,Map<String,String> newAttributes,String sha){this.action=action;this.status=status;this.section=section;this.binding=binding;generatedPartName=part;generatedRelationshipId=relationship;this.paragraph=paragraph;sourcePageOrdinal=page;this.oldText=oldText;this.originalProperty=originalProperty;this.newAttributes=Collections.unmodifiableMap(new LinkedHashMap<>(newAttributes));generatedPartSha256=sha;}
        public String getAction(){return action;}public String getStatus(){return status;}
        public NodeAnchor getSourceSection(){return section==null?null:section.sourceSection;}public Position getSourcePosition(){return section==null?null:section.sourcePosition;}
        public Binding getOriginalBinding(){return binding;}public String getGeneratedPartName(){return generatedPartName;}public String getGeneratedPartSha256(){return generatedPartSha256;}public String getGeneratedRelationshipId(){return generatedRelationshipId;}
        public Paragraph getSourceParagraph(){return paragraph;}public int getSourcePageOrdinal(){return sourcePageOrdinal;}public String getOldExactText(){return oldText;}
        public NativeProperty getOriginalProperty(){return originalProperty;}public Map<String,String> getNewAttributes(){return newAttributes;}
        public StaticFooterSourceFormat getSourceFormatting(){return sourceFormatting;}
    }
    private static final String STORY_RELS="word/_rels/document.xml.rels", CONTENT_TYPES="[Content_Types].xml";
    private static final String REL_NS="http://schemas.openxmlformats.org/package/2006/relationships", OFFICE_REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String CT_NS="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final class CapturedStory {
        final Section section;final Element sectionNode,originReference,targetRoot;final Story story;
        final String originalFields;String part,relationship;
        CapturedStory(Section section,Element node,Story story,Element origin,Element target){this.section=section;sectionNode=node;this.story=story;originReference=origin;targetRoot=target;part=story.expectedBinding.targetPartName;relationship=story.expectedBinding.relationshipId;originalFields=target==null?null:storyFields(target);}
    }
    private static final class CapturedStaticFooter {
        final Section section;final StaticFooterParagraph paragraph;final Element node;final int page;
        CapturedStaticFooter(Section section,StaticFooterParagraph paragraph,Element node,int page){this.section=section;this.paragraph=paragraph;this.node=node;this.page=page;}
    }
    private static final class CapturedStories {
        final NativeStoryPlan plan;final List<CapturedStory> stories=new ArrayList<>();final List<CapturedStaticFooter> staticFooters=new ArrayList<>();
        CapturedSctTitles sctTitles;String sctTitleSkip;
        final Map<Section,Element> sections=new LinkedHashMap<>();final Set<String> dirtyParts=new LinkedHashSet<>();final List<NativeStoryChange> changes=new ArrayList<>();
        CapturedStories(NativeStoryPlan plan){this.plan=plan;}
    }
    private static CapturedStories captureStories(PackageModel model,NativeStoryPlan plan) {
        CapturedStories captured=new CapturedStories(plan);if(plan==null)return captured;
        if(!model.index.sourceSha256.equals(plan.sourceSha256))throw error("STORY_SOURCE_HASH_MISMATCH",plan.profile);
        // Revalidate the immutable plain values even for a plan assembled by another server consumer.
        new NativeStoryPlan.Identity(plan.identity.number,plan.identity.title);
        Document main=model.xmlParts.get(MAIN);NodeList originalSections=main.getElementsByTagNameNS(W,"sectPr");
        if(originalSections.getLength()!=plan.sections.size()||plan.sections.isEmpty())throw error("STORY_SECTION_SET_UNSUPPORTED",plan.profile);
        List<Element> relationships=storyRelationships(model,false);Map<String,CapturedStory> previous=new LinkedHashMap<>();Map<String,Story> sharedIntents=new LinkedHashMap<>();Set<String> sectionIds=new HashSet<>();
        Element settings=null;
        for(Element relation:relationships)if((OFFICE_REL+"/settings").equals(relation.getAttribute("Type"))){if(settings!=null)throw error("STORY_SETTINGS_UNSUPPORTED",plan.profile);String mode=optionalAttribute(relation,"TargetMode");if(mode!=null&&!"Internal".equals(mode))throw error("STORY_SETTINGS_UNSUPPORTED",plan.profile);settings=storyXml(model,resolveStoryTarget(relation.getAttribute("Target"))).getDocumentElement();if(!is(settings,"settings"))throw error("STORY_SETTINGS_UNSUPPORTED",plan.profile);}
        validateStoryProperty(model,plan.evenAndOddHeaders,"evenAndOddHeaders",settings);
        int sectionOrdinal=0;String previousSection=null;
        for(Section section:plan.sections) {
            if(!sectionIds.add(section.observedSectionId))throw error("STORY_DUPLICATE_SECTION_ID",section.observedSectionId);
            Element node=model.nodes.get(section.sourceSection.id);
            if(node==null||!MAIN.equals(section.sourceSection.part)||!is(node,"sectPr")||originalSections.item(sectionOrdinal++)!=node||!hash(xml(node)).equals(section.sourceSection.sha256))throw error("STORY_SECTION_ANCHOR_MISMATCH",section.observedSectionId);
            validateStoryPosition(model,section.sourcePosition,node,MAIN);
            if(!section.sourceSection.nativeNodePath.equals(section.sourcePosition.nativeNodePath))throw error("STORY_SECTION_PATH_MISMATCH",section.observedSectionId);
            captured.sections.put(section,node);
            for(NativeProperty property:Arrays.asList(section.titlePg,section.pgNumType,section.pgMar,section.pgSz,section.type))validateStoryProperty(model,property,property.name,node);
            if(!Arrays.asList("titlePg","pgNumType","pgMar","pgSz","type").equals(Arrays.asList(section.titlePg.name,section.pgNumType.name,section.pgMar.name,section.pgSz.name,section.type.name)))throw error("STORY_PROPERTY_NAMES_MISMATCH",section.observedSectionId);
            if(section.stories.size()!=6)throw error("STORY_SIX_SLOTS_REQUIRED",section.observedSectionId);
            Set<String> slots=new LinkedHashSet<>();
            for(Story story:section.stories) {
                Binding binding=story.expectedBinding;String slot=binding.kind+"/"+binding.variant;if(!slots.add(slot))throw error("STORY_DUPLICATE_SLOT",slot);
                String local=binding.kind==Kind.HEADER?"headerReference":"footerReference",variant=binding.variant.name().toLowerCase(Locale.ROOT);Element reference=null;
                for(Element candidate:direct(node,local)){if(!Arrays.asList("default","first","even").contains(candidate.getAttributeNS(W,"type")))throw error("STORY_REFERENCE_TYPE_UNSUPPORTED",slot);if(variant.equals(candidate.getAttributeNS(W,"type"))){if(reference!=null)throw error("STORY_DUPLICATE_REFERENCE",slot);reference=candidate;}}
                CapturedStory inherited=previous.get(slot);Element actualOrigin=reference==null&&inherited!=null?inherited.originReference:reference;Element target=null;
                if(actualOrigin==null) {
                    if(binding.state!=BindingState.NO_TARGET||binding.targetPartName!=null||binding.targetPartSha256!=null||binding.relationshipId!=null||binding.relationshipType!=null||binding.rawTarget!=null||binding.rawTargetMode!=null||binding.originReferencePosition!=null||binding.relationshipPosition!=null||binding.originSectionId!=null||binding.inheritedFromSectionId!=null||binding.targetRootNativePath!=null)throw error("STORY_NO_TARGET_MISMATCH",slot);
                }else {
                    BindingState expected=reference==null?BindingState.INHERITED_TARGET_CANDIDATE:BindingState.EXPLICIT_TARGET_LOADED;
                    String origin=reference==null?inherited.story.expectedBinding.originSectionId:section.observedSectionId;
                    if(binding.state!=expected||!Objects.equals(binding.originSectionId,origin)||!Objects.equals(binding.inheritedFromSectionId,reference==null?previousSection:null))throw error("STORY_INHERITANCE_MISMATCH",slot);
                    if(!Objects.equals(actualOrigin.getAttributeNS(OFFICE_REL,"id"),binding.relationshipId))throw error("STORY_REFERENCE_ID_MISMATCH",slot);
                    validateStoryPosition(model,binding.originReferencePosition,actualOrigin,MAIN);
                    Element relation=null;for(Element candidate:relationships)if(candidate.getAttribute("Id").equals(binding.relationshipId))relation=candidate;
                    if(relation==null||!(OFFICE_REL+"/"+binding.kind.name().toLowerCase(Locale.ROOT)).equals(relation.getAttribute("Type"))||!Objects.equals(binding.relationshipType,relation.getAttribute("Type"))||!Objects.equals(binding.rawTarget,relation.getAttribute("Target"))||!Objects.equals(binding.rawTargetMode,optionalAttribute(relation,"TargetMode"))||binding.rawTargetMode!=null&&!"Internal".equals(binding.rawTargetMode))throw error("STORY_RELATIONSHIP_MISMATCH",slot);
                    validateStoryPosition(model,binding.relationshipPosition,relation,STORY_RELS);
                    String targetName=resolveStoryTarget(relation.getAttribute("Target"));
                    if(!targetName.equals(binding.targetPartName)||!targetName.matches("word/"+(binding.kind==Kind.HEADER?"header":"footer")+"[0-9]+\\.xml")||!Objects.equals(model.index.entryHashes.get(targetName),binding.targetPartSha256))throw error("STORY_TARGET_MISMATCH",slot);
                    target=storyXml(model,targetName).getDocumentElement();
                    if(!is(target,binding.kind==Kind.HEADER?"hdr":"ftr")||!OerNativeDocxObservation.path(target).equals(binding.targetRootNativePath))throw error("STORY_ROOT_MISMATCH",slot);
                    validateStoryContentType(model,targetName,binding.kind);
                }
                if(binding.kind==Kind.HEADER&&story.action!=StoryAction.PRESERVE&&story.action!=StoryAction.APPEND_PROJECT_TITLE||binding.kind==Kind.FOOTER&&story.action==StoryAction.APPEND_PROJECT_TITLE)throw error("STORY_ACTION_KIND_MISMATCH",slot);
                if(story.action!=StoryAction.PRESERVE&&binding.variant==Variant.FIRST&&!storyOn(section.titlePg)&&target==null||story.action!=StoryAction.PRESERVE&&binding.variant==Variant.EVEN&&!storyOn(plan.evenAndOddHeaders)&&target==null)throw error("STORY_INACTIVE_SLOT_CREATION_UNSUPPORTED",slot);
                validateStoryPlainText(story.pagePrefix);validateStoryPlainText(story.pageSuffix);for(String control:story.retainedSourceControlLines)validateStoryPlainText(control);
                boolean page=false;for(Feature feature:model.index.features)if(Objects.equals(feature.part,binding.targetPartName)&&"FIELD".equals(feature.kind)&&feature.instruction!=null&&feature.instruction.trim().matches("(?is)^PAGE\\b.*"))page=true;
                if(target!=null||story.action!=StoryAction.PRESERVE) {
                    if(story.pageAction==PageAction.PRESERVE_NO_PAGE&&page||story.pageAction==PageAction.PRESERVE_EXISTING_PAGE&&!page||story.pageAction==PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE&&page)throw error("STORY_PAGE_POLICY_MISMATCH",slot);
                    if(story.pageAction==PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE&&(binding.kind!=Kind.FOOTER||story.action!=StoryAction.CREATE_NATIVE_FOOTER||plan.staticFooterGroups.isEmpty()||target!=null&&(!editableText(target).trim().isEmpty()||has(target,"drawing")||has(target,"pict")||has(target,"object"))))throw error("STORY_STATIC_MIGRATION_UNSUPPORTED",slot);
                }
                Set<String> labelSpans=new HashSet<>();
                if(target!=null)for(Label label:story.contractLabels) {
                    if(!labelSpans.add(label.paragraph.id+":"+label.start+":"+label.end))throw error("STORY_LABEL_OVERLAP",label.paragraph.id);
                    Element labelNode=model.nodes.get(label.paragraph.id);
                    if(!binding.targetPartName.equals(label.paragraph.part)||labelNode==null||!hash(xml(labelNode)).equals(label.paragraph.sha256))throw error("STORY_LABEL_ANCHOR_MISMATCH",label.paragraph.id);
                    Edit edit=Edit.replaceCatalogLabelOutsideFields("native-contract-label",label.paragraph,label.start,label.end,label.expectedText,storyText(plan.identity.number));
                    validateSpan(model.nodes.get(label.paragraph.id),edit);validateGlobalLabelFields(model.nodes.get(label.paragraph.id),edit);
                    for(Label other:story.contractLabels)if(other!=label&&other.paragraph.id.equals(label.paragraph.id)&&overlappingSpans(label.start,label.end,other.start,other.end))throw error("STORY_LABEL_OVERLAP",label.paragraph.id);
                }
                if(target==null&&!story.contractLabels.isEmpty())throw error("STORY_LABEL_WITHOUT_TARGET",slot);
                if(target!=null){Story old=sharedIntents.putIfAbsent(binding.targetPartName,story);if(old!=null&&!sameStoryIntent(old,story))throw error("SHARED_STORY_INTENT_CONFLICT",binding.targetPartName);}
                CapturedStory item=new CapturedStory(section,node,story,actualOrigin,target);captured.stories.add(item);if(actualOrigin!=null)previous.put(slot,item);
            }
            if(section.numberingChange!=null&&!completeStaticFooterCarriers(section,plan.evenAndOddHeaders))throw error("STORY_STATIC_CARRIER_REQUIRED",section.observedSectionId);
            if(section.numberingChange!=null&&(section.numberingChange.expected!=section.pgNumType||!plan.profile.equals(section.numberingChange.migrationProfileId)||!"1".equals(section.numberingChange.newStart)||!"17".equals(storyPropertyAttributes(section.pgNumType).get("{"+W+"}start"))))throw error("STORY_NUMBER_START_MIGRATION_UNSUPPORTED",section.observedSectionId);
            if(section.marginChange!=null&&(section.marginChange.expected!=section.pgMar||!plan.profile.equals(section.marginChange.verifiedEditionProfileId)))throw error("STORY_MARGIN_AUTHORITY_MISMATCH",section.observedSectionId);
            previousSection=section.observedSectionId;
        }
        Set<String> staticIds=new LinkedHashSet<>();Set<Section> migratedSections=new HashSet<>();int page=0;
        for(StaticFooterGroup group:plan.staticFooterGroups) {
            if(group.sourcePageOrdinal!=++page||group.exactParagraphs.size()!=2)throw error("STORY_STATIC_SEQUENCE_MISMATCH",plan.profile);
            Paragraph first=group.exactParagraphs.get(0).source,second=group.exactParagraphs.get(1).source;
            if(second.ordinal!=first.ordinal+1||!Objects.equals(first.sectionId,second.sectionId))throw error("STORY_STATIC_PAIR_MISMATCH",plan.profile);
            for(StaticFooterParagraph paragraph:group.exactParagraphs) {
                Element node=model.nodes.get(paragraph.source.id);Section owner=null;for(Section section:plan.sections)if(section.sourceSection.id.equals(paragraph.source.sectionId))owner=section;
                if(owner==null||!MAIN.equals(paragraph.source.part)||node==null||!is(node.getParentNode(),"body")||!staticIds.add(paragraph.source.id)||!hash(xml(node)).equals(paragraph.source.sha256)||!paragraph.exactText.equals(descendantText(node,"t")))throw error("STORY_STATIC_PARAGRAPH_MISMATCH",paragraph.source.id);
                for(String control:Arrays.asList("fldChar","instrText","fldSimple","br","cr","sectPr","pageBreakBefore"))if(has(node,control))throw error("STORY_STATIC_CONTROL_UNSUPPORTED",paragraph.source.id);
                if(!paragraph.exactText.isEmpty())validateGlobalLabelFields(node,0,editableText(node).length(),false,"native-static-footer");
                if(!completeStaticFooterCarriers(owner,plan.evenAndOddHeaders))throw error("STORY_STATIC_CARRIER_REQUIRED",paragraph.source.id);
                if(plan.staticFooterSourceFormat!=null){validateClearedLayoutParagraph(node,false,paragraph.source.id);List<Element> props=direct(node,"pPr");if(props.size()>1||props.size()==1&&direct(props.get(0),"spacing").size()>1)throw error("STORY_STATIC_SPACING_SCOPE_UNSUPPORTED",paragraph.source.id);}
                migratedSections.add(owner);
                captured.staticFooters.add(new CapturedStaticFooter(owner,paragraph,node,group.sourcePageOrdinal));
            }
        }
        for(Section section:plan.sections)if((section.numberingChange!=null||section.stories.stream().anyMatch(DocxTemplateEditor::staticFooterCarrier))&&!migratedSections.contains(section))throw error("STORY_STATIC_SECTION_MISMATCH",section.observedSectionId);
        validateStaticFooterSourceFormat(model,captured);
        if(plan.sctTitleMigration!=null)try{captured.sctTitles=captureSctTitles(model,captured);}catch(EditException unsupported){captured.sctTitleSkip=unsupported.getCode();}
        long additions=captured.stories.stream().filter(item->item.story.action!=StoryAction.PRESERVE&&item.part==null).count();
        if(model.entries.size()+additions+(additions>0&&!model.entries.containsKey(STORY_RELS)?1:0)>COUNT_LIMIT)throw error("DOCX_ENTRY_LIMIT",plan.profile);
        return captured;
    }
    private static void validateStaticFooterSourceFormat(PackageModel model,CapturedStories captured) {
        StaticFooterSourceFormat format=captured.plan.staticFooterSourceFormat;if(format==null)return;
        if(captured.staticFooters.isEmpty())throw error("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",captured.plan.profile);
        if(!format.stylesPartSha256.equals(model.index.entryHashes.get("word/styles.xml")))throw error("STORY_SOURCE_STYLE_PART_MISMATCH",captured.plan.profile);
        int styleReferences=0;for(Element relation:storyRelationships(model,false))if((OFFICE_REL+"/styles").equals(relation.getAttribute("Type"))){styleReferences++;String mode=optionalAttribute(relation,"TargetMode");if(mode!=null&&!"Internal".equals(mode)||!"word/styles.xml".equals(resolveStoryTarget(relation.getAttribute("Target"))))throw error("STORY_SOURCE_STYLE_BINDING_UNSUPPORTED",captured.plan.profile);}
        if(styleReferences!=1)throw error("STORY_SOURCE_STYLE_BINDING_UNSUPPORTED",captured.plan.profile);
        int styleTypes=0;NodeList overrides=storyXml(model,CONTENT_TYPES).getDocumentElement().getElementsByTagNameNS(CT_NS,"Override");for(int i=0;i<overrides.getLength();i++){Element entry=(Element)overrides.item(i);if("/word/styles.xml".equals(entry.getAttribute("PartName"))){styleTypes++;if(!"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml".equals(entry.getAttribute("ContentType")))throw error("STORY_SOURCE_STYLE_CONTENT_TYPE_UNSUPPORTED",captured.plan.profile);}}
        if(styleTypes!=1)throw error("STORY_SOURCE_STYLE_CONTENT_TYPE_UNSUPPORTED",captured.plan.profile);
        for(Section section:captured.plan.sections)if(section.marginChange!=null&&(!section.pgMar.present||section.marginChange.newTwips.keySet().stream().anyMatch(name->!Arrays.asList("header","footer").contains(name))))throw error("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",section.observedSectionId);
        Element styles=storyXml(model,"word/styles.xml").getDocumentElement();
        if(!is(styles,"styles"))throw error("STORY_SOURCE_STYLE_ROOT_UNSUPPORTED",captured.plan.profile);
        for(String id:Arrays.asList(format.headerStyleId,format.footerStyleId)) {
            Element found=null;for(Element style:direct(styles,"style"))if(id.equals(style.getAttributeNS(W,"styleId"))){if(found!=null)throw error("STORY_SOURCE_STYLE_AMBIGUOUS",id);found=style;}
            if(found==null||!"paragraph".equals(found.getAttributeNS(W,"type")))throw error("STORY_SOURCE_STYLE_UNSUPPORTED",id);
        }
        boolean emptyCarrierMatched=false;
        for(CapturedStory item:captured.stories)if(item.story.action!=StoryAction.PRESERVE) {
            Story story=item.story;
            boolean newTarget=story.expectedBinding.state==BindingState.NO_TARGET&&item.part==null,emptyTarget=exactSourceEmptyFooter(model,format,item);
            if(emptyTarget)emptyCarrierMatched=true;
            if(!newTarget&&!emptyTarget
                    ||story.expectedBinding.kind==Kind.HEADER&&story.action!=StoryAction.APPEND_PROJECT_TITLE
                    ||story.expectedBinding.kind==Kind.FOOTER&&(!staticFooterCarrier(story)||story.retainedSourceControlLines.size()!=2))
                throw error("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",captured.plan.profile);
        }
        if(format.emptyFooterPartName!=null&&!emptyCarrierMatched)throw error("STORY_SOURCE_STYLE_SCOPE_UNSUPPORTED",captured.plan.profile);
    }
    private static boolean exactSourceEmptyFooter(PackageModel model,StaticFooterSourceFormat format,CapturedStory item) {
        if(format.emptyFooterPartName==null||item.story.expectedBinding.kind!=Kind.FOOTER
                ||item.story.expectedBinding.state!=BindingState.EXPLICIT_TARGET_LOADED||item.targetRoot==null
                ||!format.emptyFooterPartName.equals(item.part)||!format.emptyFooterPartSha256.equals(model.index.entryHashes.get(item.part))
                ||!"".equals(item.originalFields)||direct(item.targetRoot,"p").size()!=1)return false;
        for(Node child=item.targetRoot.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"p"))return false;
        Element paragraph=direct(item.targetRoot,"p").get(0);
        for(Node child=paragraph.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"pPr"))return false;
        return direct(paragraph,"pPr").size()<=1;
    }
    private static void validateStoryBusinessScopes(PackageModel model,CapturedStories captured,SourceEditBatch batch) {
        if(captured.plan==null)return;
        for(Edit edit:batch.edits){Element target=model.nodes.get(edit.targetId);String part=edit.targetId.substring(0,edit.targetId.indexOf('#'));
            if(captured.sctTitles!=null)for(Element title:captured.sctTitles.nodes)if(target==title||contains(target,title)||contains(title,target))captured.sctTitleSkip="SCT_TITLE_BUSINESS_EDIT_OVERLAP";
            for(CapturedStory story:captured.stories)if(story.story.action!=StoryAction.PRESERVE&&Objects.equals(part,story.part))throw error("STORY_BUSINESS_EDIT_OVERLAP",edit.operationId);
            for(CapturedStaticFooter footer:captured.staticFooters)if(target==footer.node||contains(target,footer.node)||contains(footer.node,target))throw error("STORY_STATIC_BUSINESS_OVERLAP",edit.operationId);
        }
    }
    private static void validateStoryProperty(PackageModel model,NativeProperty property,String expectedName,Element owner) {
        if(!expectedName.equals(property.name))throw error("STORY_PROPERTY_NAME_MISMATCH",expectedName);
        List<Element> actual=owner==null?Collections.emptyList():direct(owner,expectedName);
        if(property.present?actual.size()!=1||property.observations.size()!=1||!Arrays.asList("observed","observed_raw_declaration").contains(property.status):!actual.isEmpty()||!property.observations.isEmpty()||!"absent".equals(property.status))throw error("STORY_PROPERTY_CARDINALITY_MISMATCH",expectedName);
        if(property.present){PropertyOccurrence value=property.observations.get(0);Element node=actual.get(0);String part=owner.getOwnerDocument()==model.xmlParts.get(MAIN)?MAIN:partName(model,owner.getOwnerDocument());validateStoryPosition(model,value.sourcePosition,node,part);if(!storyAttributes(node).equals(value.rawAttributes))throw error("STORY_PROPERTY_VALUE_MISMATCH",expectedName);}
    }
    private static void validateStoryPosition(PackageModel model,Position position,Element node,String part) {
        if(position==null||node==null||!model.index.sourceSha256.equals(position.sourceSha256)||!part.equals(position.partName)||!Objects.equals(model.index.entryHashes.get(part),position.partSha256)||!OerNativeDocxObservation.path(node).equals(position.nativeNodePath))throw error("STORY_SOURCE_POSITION_MISMATCH",part);
    }
    private static String partName(PackageModel model,Document document){for(Map.Entry<String,Document> entry:model.xmlParts.entrySet())if(entry.getValue()==document)return entry.getKey();throw error("STORY_PART_UNAVAILABLE","document");}
    private static Map<String,String> storyAttributes(Element node){Map<String,String> values=new LinkedHashMap<>();NamedNodeMap attributes=node.getAttributes();for(int i=0;i<attributes.getLength();i++){Node attribute=attributes.item(i);if(XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()))continue;values.put("{"+(attribute.getNamespaceURI()==null?"":attribute.getNamespaceURI())+"}"+(attribute.getLocalName()==null?attribute.getNodeName():attribute.getLocalName()),attribute.getNodeValue());}return values;}
    private static Map<String,String> storyPropertyAttributes(NativeProperty property){return property.present?property.observations.get(0).rawAttributes:Collections.emptyMap();}
    private static boolean storyOn(NativeProperty property){if(!property.present)return false;String value=storyPropertyAttributes(property).get("{"+W+"}val");if(value!=null&&!Arrays.asList("1","true","on","0","false","off").contains(value))throw error("STORY_ON_OFF_UNSUPPORTED",property.name);return value==null||Arrays.asList("1","true","on").contains(value);}
    private static String optionalAttribute(Element node,String name){return node.hasAttribute(name)?node.getAttribute(name):null;}
    private static Document storyXml(PackageModel model,String part){Document cached=model.xmlParts.get(part);if(cached!=null)return cached;byte[] bytes=model.entries.get(part);if(bytes==null)throw error("STORY_PART_UNAVAILABLE",part);try{Document document=parse(bytes);model.xmlParts.put(part,document);return document;}catch(Exception invalid){throw error("STORY_XML_UNSUPPORTED",part);}}
    private static List<Element> storyRelationships(PackageModel model,boolean create){
        Document document=model.entries.containsKey(STORY_RELS)?storyXml(model,STORY_RELS):model.xmlParts.get(STORY_RELS);
        if(document==null){if(!create)return Collections.emptyList();try{document=parse(("<Relationships xmlns=\""+REL_NS+"\"/>").getBytes(StandardCharsets.UTF_8));model.xmlParts.put(STORY_RELS,document);}catch(Exception invalid){throw error("STORY_RELATIONSHIPS_UNAVAILABLE",STORY_RELS);}}
        Element root=document.getDocumentElement();if(!REL_NS.equals(root.getNamespaceURI())||!"Relationships".equals(root.getLocalName()))throw error("STORY_RELATIONSHIPS_UNSUPPORTED",STORY_RELS);
        List<Element> out=new ArrayList<>();Set<String> ids=new HashSet<>();for(Node child=root.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&REL_NS.equals(child.getNamespaceURI())&&"Relationship".equals(child.getLocalName())){Element relation=(Element)child;if(relation.getAttribute("Id").isEmpty()||!ids.add(relation.getAttribute("Id")))throw error("STORY_RELATIONSHIP_DUPLICATE",STORY_RELS);out.add(relation);}return out;
    }
    private static String resolveStoryTarget(String raw){try{java.net.URI target=new java.net.URI(raw);if(target.isAbsolute()||target.getAuthority()!=null||target.getQuery()!=null||target.getFragment()!=null||raw.contains("\\"))throw new IllegalArgumentException();String path=new java.net.URI("/word/document.xml").resolve(target).normalize().getPath();if(path==null||!path.startsWith("/word/")||path.contains("../"))throw new IllegalArgumentException();return path.substring(1);}catch(Exception invalid){throw error("STORY_TARGET_URI_UNSUPPORTED",raw);}}
    private static String storyContentType(Kind kind){return "application/vnd.openxmlformats-officedocument.wordprocessingml."+(kind==Kind.HEADER?"header":"footer")+"+xml";}
    private static void validateStoryContentType(PackageModel model,String part,Kind kind){Document document=storyXml(model,CONTENT_TYPES);String type=null;int matches=0;NodeList entries=document.getDocumentElement().getElementsByTagNameNS(CT_NS,"Override");for(int i=0;i<entries.getLength();i++){Element entry=(Element)entries.item(i);if(("/"+part).equals(entry.getAttribute("PartName"))){matches++;type=entry.getAttribute("ContentType");}}if(matches!=1||!storyContentType(kind).equals(type))throw error("STORY_CONTENT_TYPE_MISMATCH",part);}
    private static boolean sameStoryIntent(Story a,Story b){if(a.action!=b.action||a.pageAction!=b.pageAction||!Objects.equals(a.pagePrefix,b.pagePrefix)||!Objects.equals(a.pageSuffix,b.pageSuffix)||!a.retainedSourceControlLines.equals(b.retainedSourceControlLines)||a.contractLabels.size()!=b.contractLabels.size())return false;for(int i=0;i<a.contractLabels.size();i++){Label x=a.contractLabels.get(i),y=b.contractLabels.get(i);if(!x.paragraph.id.equals(y.paragraph.id)||x.start!=y.start||x.end!=y.end||!x.expectedText.equals(y.expectedText))return false;}return true;}
    private static String storyText(String text){return text.replace("\r\n","\n").replace('\r','\n');}
    private static boolean activeFooter(Story story,Section section,NativeProperty evenAndOdd){return story.expectedBinding.kind==Kind.FOOTER&&(story.expectedBinding.variant==Variant.DEFAULT||story.expectedBinding.variant==Variant.FIRST&&storyOn(section.titlePg)||story.expectedBinding.variant==Variant.EVEN&&storyOn(evenAndOdd));}
    private static boolean staticFooterCarrier(Story story){return story.expectedBinding.kind==Kind.FOOTER&&story.action==StoryAction.CREATE_NATIVE_FOOTER&&story.pageAction==PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE;}
    private static boolean completeStaticFooterCarriers(Section section,NativeProperty evenAndOdd){int carriers=0;for(Story story:section.stories)if(activeFooter(story,section,evenAndOdd)){if(!staticFooterCarrier(story))return false;carriers++;}return carriers>0;}
    private static void validateAppliedStaticFooterCarriers(PackageModel model,CapturedStories captured,Section section){int carriers=0;for(CapturedStory item:captured.stories)if(item.section==section&&activeFooter(item.story,section,captured.plan.evenAndOddHeaders)){boolean page=false;Document document=item.part==null?null:model.xmlParts.get(item.part);if(document!=null){NodeList fields=document.getElementsByTagNameNS(W,"fldSimple");for(int i=0;i<fields.getLength();i++)if(((Element)fields.item(i)).getAttributeNS(W,"instr").trim().matches("(?is)^PAGE\\b.*"))page=true;}if(!staticFooterCarrier(item.story)||!page)throw error("STORY_STATIC_CARRIER_NOT_APPLIED",section.observedSectionId);carriers++;}if(carriers==0)throw error("STORY_STATIC_CARRIER_NOT_APPLIED",section.observedSectionId);}
    private static void validateStoryPlainText(String value){if(value!=null)for(String fragment:storyText(value).split("[\n\t]",-1))validText(fragment,"native-story-plain-text");}
    private static String storyFields(Element root){StringBuilder snapshot=new StringBuilder();Deque<Boolean> fields=new ArrayDeque<>();NodeList nodes=root.getElementsByTagName("*");for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);if(is(node,"fldChar")){String type=node.getAttributeNS(W,"fldCharType");if("begin".equals(type))fields.push(false);else if("end".equals(type)){if(fields.isEmpty())throw error("STORY_FIELD_SCOPE_UNSUPPORTED","field");fields.pop();}else if("separate".equals(type)&&!fields.isEmpty()&&!fields.peek()){fields.pop();fields.push(true);}else throw error("STORY_FIELD_SCOPE_UNSUPPORTED","field");snapshot.append(hash(xml(node)));}else if(is(node,"fldSimple")){if(node.getAttributeNS(W,"instr").trim().isEmpty())throw error("STORY_FIELD_SCOPE_UNSUPPORTED","field");snapshot.append(hash(xml(node)));}else if(is(node,"instrText")){if(fields.isEmpty())throw error("STORY_FIELD_SCOPE_UNSUPPORTED","field");snapshot.append(hash(xml(node)));}else if(!fields.isEmpty()&&is(node,"t"))snapshot.append(hash(xml(node)));}if(!fields.isEmpty())throw error("STORY_FIELD_SCOPE_UNSUPPORTED","field");return snapshot.toString();}
    private static void validateStoryPackageLimits(PackageModel model){if(model.entries.size()>COUNT_LIMIT)throw error("DOCX_ENTRY_LIMIT","native-stories");long total=0;for(Map.Entry<String,byte[]> entry:model.entries.entrySet()){long size=entry.getValue().length;total+=size;if(size>ENTRY_LIMIT||((entry.getKey().endsWith(".xml")||entry.getKey().endsWith(".rels"))&&size>XML_LIMIT)||total>TOTAL_LIMIT)throw error("DOCX_INFLATION_LIMIT",entry.getKey());}}
    private static void applyStories(PackageModel model,CapturedStories captured) {
        if(captured.plan==null)return;NativeStoryPlan plan=captured.plan;Set<String> applied=new HashSet<>();
        for(CapturedStory item:captured.stories) {
            Story story=item.story;String status="PRESERVED";
            if(story.action!=StoryAction.PRESERVE) {
                if(!contains(model.xmlParts.get(MAIN).getDocumentElement(),item.sectionNode))throw error("STORY_SECTION_REMOVED",item.section.observedSectionId);
                if(item.part==null)createStory(model,captured,item);
                if(applied.add(item.part)) {
                    Element root=storyXml(model,item.part).getDocumentElement();
                    StaticFooterSourceFormat format=plan.staticFooterSourceFormat;Element newPageParagraph=null;
                    if(format!=null) {
                        if(story.action==StoryAction.APPEND_PROJECT_TITLE)identityStoryParagraph(appendStyledStoryParagraph(root,storyText(plan.identity.title),format.headerStyleId),plan.identity.title);
                        else {
                            String control="\t"+story.retainedSourceControlLines.get(0)+"\t"+(story.pagePrefix==null?"":story.pagePrefix);
                            Element paragraph=identityStoryParagraph(appendStyledStoryParagraph(root,storyText(plan.identity.number)+(format.contractNumberHalfPoints==null&&!identityNeedsEastAsiaFont(plan.identity.number)?control:""),format.footerStyleId),plan.identity.number);
                            if(format.contractNumberHalfPoints!=null){Document document=root.getOwnerDocument();Element run=direct(paragraph,"r").get(0),properties=storyRunProperties(run),size=document.createElementNS(W,"w:sz");size.setAttributeNS(W,"w:val",format.contractNumberHalfPoints.toString());properties.appendChild(size);appendStoryRun(paragraph,control);}else if(identityNeedsEastAsiaFont(plan.identity.number))appendStoryRun(paragraph,control);
                            newPageParagraph=paragraph;
                            appendStyledStoryParagraph(root,story.retainedSourceControlLines.get(1),format.footerStyleId);
                        }
                    }else if(story.action==StoryAction.APPEND_PROJECT_TITLE)identityStoryParagraph(appendStoryParagraph(root,storyText(plan.identity.title)),plan.identity.title);
                    else {
                        List<Label> labels=new ArrayList<>(story.contractLabels);labels.sort(Comparator.comparing((Label label)->label.paragraph.id).thenComparing(label->-label.start));
                        for(Label label:labels){Element paragraph=model.nodes.get(label.paragraph.id);Edit edit=Edit.replaceCatalogLabelOutsideFields("native-contract-label",label.paragraph,label.start,label.end,label.expectedText,storyText(plan.identity.number));if(identityNeedsEastAsiaFont(plan.identity.number))identityRunFont(replace(model,paragraph,edit,true),plan.identity.number);else replace(model,paragraph,edit);}
                        if(labels.isEmpty())identityStoryParagraph(appendStoryParagraph(root,storyText(plan.identity.number)),plan.identity.number);
                    }
                    if(item.originalFields!=null&&!item.originalFields.equals(storyFields(root)))throw error("STORY_FIELD_CHANGED",item.part);
                    // Preserve the complete original field/cache snapshot before the separately authorized new PAGE.
                    if(newPageParagraph!=null){appendNativePage(newPageParagraph);appendStoryRun(newPageParagraph,story.pageSuffix==null?"":story.pageSuffix);}
                    if(format==null&&story.pageAction==PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE){for(String control:story.retainedSourceControlLines)appendStoryParagraph(root,control);Element page=appendStoryParagraph(root,story.pagePrefix==null?"":story.pagePrefix);Element field=root.getOwnerDocument().createElementNS(W,"w:fldSimple");field.setAttributeNS(W,"w:instr"," PAGE ");field.setAttributeNS(W,"w:dirty","true");Element run=root.getOwnerDocument().createElementNS(W,"w:r"),text=root.getOwnerDocument().createElementNS(W,"w:t");run.appendChild(text);field.appendChild(run);page.appendChild(field);appendStoryRun(page,story.pageSuffix==null?"":story.pageSuffix);}
                    captured.dirtyParts.add(item.part);status="APPLIED";
                }else status="SHARED_TARGET_ALREADY_APPLIED";
            }
            captured.changes.add(new NativeStoryChange(story.action.name(),status,item.section,story.expectedBinding,item.part,item.relationship,null,0,null,null,Collections.emptyMap(),null));
        }
        Set<Section> verifiedCarriers=new HashSet<>();for(CapturedStaticFooter footer:captured.staticFooters)if(verifiedCarriers.add(footer.section))validateAppliedStaticFooterCarriers(model,captured,footer.section);
        for(CapturedStaticFooter footer:captured.staticFooters){
            if(!contains(model.xmlParts.get(MAIN).getDocumentElement(),footer.node)||!footer.paragraph.exactText.equals(descendantText(footer.node,"t")))throw error("STORY_STATIC_SCOPE_CHANGED",footer.paragraph.source.id);
            String compactSkipCode=null;
            if(plan.staticFooterSourceFormat==null)try{validateClearedLayoutParagraph(footer.node,false,footer.paragraph.source.id);List<Element> props=direct(footer.node,"pPr");if(props.size()>1||props.size()==1&&direct(props.get(0),"spacing").size()>1)throw error("STORY_STATIC_SPACING_SCOPE_UNSUPPORTED",footer.paragraph.source.id);}
            catch(EditException unsupported){if(!Set.of("LAYOUT_CLEARED_INLINE_UNSUPPORTED","STORY_STATIC_SPACING_SCOPE_UNSUPPORTED").contains(unsupported.getCode()))throw unsupported;compactSkipCode=unsupported.getCode();}
            clear(footer.node);captured.dirtyParts.add(MAIN);captured.changes.add(new NativeStoryChange("CLEAR_EXACT_STATIC_FOOTER","APPLIED",footer.section,null,MAIN,null,footer.paragraph.source,footer.page,footer.paragraph.exactText,null,Collections.emptyMap(),null));
            // Migrated footer marks otherwise retain a full empty line before continuation breaks.
            if(compactSkipCode==null){Document document=footer.node.getOwnerDocument();List<Element> values=direct(footer.node,"pPr");Element props=values.isEmpty()?document.createElementNS(W,"w:pPr"):values.get(0);if(values.isEmpty())footer.node.insertBefore(props,footer.node.getFirstChild());compactSpacing(document,props);Map<String,String> spacing=new LinkedHashMap<>();spacing.put("before","0");spacing.put("after","0");spacing.put("line","1");spacing.put("lineRule","exact");captured.changes.add(new NativeStoryChange("COMPACT_EXACT_CLEARED_STATIC_FOOTER_SPACING","APPLIED",footer.section,null,MAIN,null,footer.paragraph.source,footer.page,footer.paragraph.exactText,null,spacing,null));}else captured.changes.add(new NativeStoryChange("COMPACT_EXACT_CLEARED_STATIC_FOOTER_SPACING","SKIPPED_"+compactSkipCode,footer.section,null,MAIN,null,footer.paragraph.source,footer.page,footer.paragraph.exactText,null,Collections.emptyMap(),null));
        }
        for(Map.Entry<Section,Element> entry:captured.sections.entrySet()){Section section=entry.getKey();Element node=entry.getValue();if(section.numberingChange!=null){direct(node,"pgNumType").get(0).setAttributeNS(W,"w:start",section.numberingChange.newStart);captured.dirtyParts.add(MAIN);captured.changes.add(new NativeStoryChange("MIGRATE_STATIC_NUMBER_START","APPLIED",section,null,MAIN,null,null,0,null,section.pgNumType,Collections.singletonMap("start",section.numberingChange.newStart),null));}if(section.marginChange!=null){List<Element> margins=direct(node,"pgMar");Element margin=margins.isEmpty()?node.getOwnerDocument().createElementNS(W,"w:pgMar"):margins.get(0);if(margins.isEmpty()){Node before=null;for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&Arrays.asList("paperSrc","pgBorders","lnNumType","pgNumType","cols","formProt","vAlign","noEndnote","titlePg","textDirection","bidi","rtlGutter","docGrid","printerSettings","sectPrChange").contains(child.getLocalName())){before=child;break;}node.insertBefore(margin,before);}Map<String,String> values=new LinkedHashMap<>();for(Map.Entry<String,Integer> value:section.marginChange.newTwips.entrySet()){margin.setAttributeNS(W,"w:"+value.getKey(),value.getValue().toString());values.put(value.getKey(),value.getValue().toString());}captured.dirtyParts.add(MAIN);captured.changes.add(new NativeStoryChange("EXPLICIT_SOURCE_BOUND_MARGIN_CHANGE","APPLIED",section,null,MAIN,null,null,0,null,section.pgMar,values,null));}}
    }
    private static final class CapturedSctTitles {
        final SctTitleMigration policy;final List<Element> nodes=new ArrayList<>();
        CapturedSctTitles(SctTitleMigration policy){this.policy=policy;}
    }
    private static CapturedStory sctSlot(CapturedStories captured,Section section,Kind kind,Variant variant) {
        for(CapturedStory item:captured.stories)if(item.section==section&&item.story.expectedBinding.kind==kind&&item.story.expectedBinding.variant==variant)return item;
        throw error("SCT_TITLE_STORY_SLOT_UNAVAILABLE",section.observedSectionId);
    }
    private static CapturedSctTitles captureSctTitles(PackageModel model,CapturedStories captured) {
        SctTitleMigration policy=captured.plan.sctTitleMigration;Section section=policy.section;
        if(!captured.sections.containsKey(section)||section.titlePg.present||captured.plan.evenAndOddHeaders.present
                ||policy.bodyTitles.isEmpty()||!"a3".equals(policy.headerStyleId))throw error("SCT_TITLE_SCOPE_UNSUPPORTED",captured.plan.profile);
        for(Kind kind:Kind.values())for(Variant variant:Arrays.asList(Variant.DEFAULT,Variant.FIRST)) {
            CapturedStory item=sctSlot(captured,section,kind,variant);
            if((kind==Kind.HEADER||variant==Variant.FIRST)&&item.story.expectedBinding.state!=BindingState.NO_TARGET
                    ||variant==Variant.FIRST&&item.story.action!=StoryAction.PRESERVE
                    ||kind==Kind.HEADER&&variant==Variant.DEFAULT&&item.story.action!=StoryAction.APPEND_PROJECT_TITLE)
                throw error("SCT_TITLE_ORIGINAL_BINDING_UNSUPPORTED",section.observedSectionId);
        }
        CapturedStory footer=sctSlot(captured,section,Kind.FOOTER,Variant.DEFAULT);
        if(footer.story.action==StoryAction.PRESERVE||!Arrays.asList(PageAction.PRESERVE_EXISTING_PAGE,PageAction.CREATE_PAGE_FROM_STATIC_SEQUENCE).contains(footer.story.pageAction))
            throw error("SCT_TITLE_DEFAULT_FOOTER_UNVERIFIED",section.observedSectionId);
        Map<String,String> margins=storyPropertyAttributes(section.pgMar);
        if(!"720".equals(margins.get("{"+W+"}top"))||!"720".equals(margins.get("{"+W+"}header")))throw error("SCT_TITLE_ORIGINAL_FRAME_UNSUPPORTED",section.observedSectionId);
        if(!Objects.equals(policy.stylesPartSha256,model.index.entryHashes.get("word/styles.xml")))throw error("SCT_TITLE_STYLE_PART_MISMATCH",section.observedSectionId);
        int styleRelations=0;for(Element relation:storyRelationships(model,false))if((OFFICE_REL+"/styles").equals(relation.getAttribute("Type"))){styleRelations++;String mode=optionalAttribute(relation,"TargetMode");if(mode!=null&&!"Internal".equals(mode)||!"word/styles.xml".equals(resolveStoryTarget(relation.getAttribute("Target"))))throw error("SCT_TITLE_STYLE_BINDING_UNSUPPORTED",section.observedSectionId);}
        if(styleRelations!=1)throw error("SCT_TITLE_STYLE_BINDING_UNSUPPORTED",section.observedSectionId);
        int styleTypes=0;for(Element entry:elementsIn(storyXml(model,CONTENT_TYPES).getDocumentElement(),CT_NS,"Override"))if("/word/styles.xml".equals(entry.getAttribute("PartName"))){styleTypes++;if(!"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml".equals(entry.getAttribute("ContentType")))throw error("SCT_TITLE_STYLE_TYPE_UNSUPPORTED",section.observedSectionId);}
        if(styleTypes!=1)throw error("SCT_TITLE_STYLE_TYPE_UNSUPPORTED",section.observedSectionId);
        Element style=null;for(Element candidate:direct(storyXml(model,"word/styles.xml").getDocumentElement(),"style"))if(policy.headerStyleId.equals(candidate.getAttributeNS(W,"styleId"))){if(style!=null)throw error("SCT_TITLE_STYLE_AMBIGUOUS",policy.headerStyleId);style=candidate;}
        if(style==null||!"paragraph".equals(style.getAttributeNS(W,"type"))||!policy.headerStyleNodeSha256.equals(hash(xml(style))))throw error("SCT_TITLE_STYLE_NODE_MISMATCH",policy.headerStyleId);
        CapturedSctTitles result=new CapturedSctTitles(policy);Set<String> seen=new HashSet<>();int plain=0,continued=0;
        for(SctBodyTitle title:policy.bodyTitles) {
            Paragraph source=title.source;Element node=model.nodes.get(source.id);
            if(!MAIN.equals(source.part)||!section.sourceSection.id.equals(source.sectionId)||node==null||!is(node,"p")
                    ||!seen.add(source.id)||!hash(xml(node)).equals(source.sha256)||!title.exactText.equals(descendantText(node,"t")))throw error("SCT_TITLE_ANCHOR_MISMATCH",source.id);
            if(source.ordinal==policy.plainDonorOrdinal){plain++;if(!"SPECIAL CONDITIONS OF TENDER".equals(title.exactText))throw error("SCT_TITLE_PLAIN_DONOR_UNSUPPORTED",source.id);}
            else if(!"SPECIAL CONDITIONS OF TENDER  (Cont’d)".equals(title.exactText))throw error("SCT_TITLE_CONTINUED_DONOR_UNSUPPORTED",source.id);
            if(source.ordinal==policy.continuedDonorOrdinal)continued++;
            validateSctBodyTitle(model,title,node);result.nodes.add(node);
        }
        if(plain!=1||continued!=1||policy.plainDonorOrdinal==policy.continuedDonorOrdinal)throw error("SCT_TITLE_DONOR_SET_UNSUPPORTED",section.observedSectionId);
        return result;
    }
    private static List<Element> elementsIn(Element root,String namespace,String local){List<Element> out=new ArrayList<>();NodeList nodes=root.getElementsByTagNameNS(namespace,local);for(int i=0;i<nodes.getLength();i++)out.add((Element)nodes.item(i));return out;}
    private static void validateSctBodyTitle(PackageModel model,SctBodyTitle title,Element node) {
        Element checked=node;
        if(title.preserveSourceColumnControl) {
            if(!"7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4".equals(model.index.sourceSha256)
                    ||title.source.ordinal!=422||!"f75717d5b9d58a7ec2fafbcbe8af8704b1c944c23c71282e62f18af3d5be38e3".equals(title.source.sha256)
                    ||!"word/document.xml#/w:document[1]/w:body[1]/w:tbl[11]/w:tr[1]/w:tc[1]/w:p[1]".equals(title.source.id))throw error("SCT_TITLE_COLUMN_SCOPE_UNSUPPORTED",title.source.id);
            checked=(Element)node.cloneNode(true);List<Element> breaks=elementsIn(checked,W,"br");
            if(breaks.size()!=1||!"column".equals(breaks.get(0).getAttributeNS(W,"type"))||!is(breaks.get(0).getParentNode(),"r"))throw error("SCT_TITLE_COLUMN_SCOPE_UNSUPPORTED",title.source.id);
            breaks.get(0).getParentNode().removeChild(breaks.get(0));
        }
        validateClearedLayoutParagraph(checked,false,title.source.id);
        for(Element run:direct(checked,"r"))if(!direct(run,"tab").isEmpty())throw error("SCT_TITLE_INLINE_UNSUPPORTED",title.source.id);
        if(!storyFields(checked).isEmpty())throw error("SCT_TITLE_INLINE_UNSUPPORTED",title.source.id);
        Map<String,Element> starts=new HashMap<>();Set<String> ends=new HashSet<>();for(Element start:elementsIn(node,W,"bookmarkStart")){String id=start.getAttributeNS(W,"id");if(!id.matches("[0-9]+")||start.getAttributeNS(W,"name").isEmpty()||starts.put(id,start)!=null)throw error("SCT_TITLE_BOOKMARK_SCOPE_UNSUPPORTED",title.source.id);}
        for(Element end:elementsIn(node,W,"bookmarkEnd")){String id=end.getAttributeNS(W,"id");Element start=starts.get(id);if(start==null||!ends.add(id)||(start.compareDocumentPosition(end)&Node.DOCUMENT_POSITION_FOLLOWING)==0)throw error("SCT_TITLE_BOOKMARK_SCOPE_UNSUPPORTED",title.source.id);}
        if(!starts.keySet().equals(ends))throw error("SCT_TITLE_BOOKMARK_SCOPE_UNSUPPORTED",title.source.id);
        List<Element> props=direct(node,"pPr");if(props.size()>1||props.size()==1&&direct(props.get(0),"spacing").size()>1)throw error("SCT_TITLE_SPACING_UNSUPPORTED",title.source.id);
        StringBuilder directText=new StringBuilder();for(Element run:direct(node,"r"))for(Element text:direct(run,"t"))directText.append(text.getTextContent());
        if(!directText.toString().equals(title.exactText))throw error("SCT_TITLE_RUN_SCOPE_UNSUPPORTED",title.source.id);
    }
    private static void skipSctTitles(CapturedStories captured,String code) {
        captured.changes.add(new NativeStoryChange("MIGRATE_EXACT_SCT_TITLES","SKIPPED_"+code,captured.plan.sctTitleMigration.section,null,null,null,null,0,null,null,Collections.emptyMap(),null));
    }
    /** Stage this optional enhancement before clearing source titles; preserve the base usable package on failure. */
    private static void applySctTitles(PackageModel model,CapturedStories captured) {
        if(captured.plan==null||captured.plan.sctTitleMigration==null)return;
        if(captured.sctTitleSkip!=null){skipSctTitles(captured,captured.sctTitleSkip);return;}
        CapturedSctTitles titles=captured.sctTitles;SctTitleMigration policy=titles.policy;Section section=policy.section;
        PackageModel staged=new PackageModel();CapturedStories stagedChanges=new CapturedStories(captured.plan);
        Map<Element,List<Node>> replacements=new LinkedHashMap<>();
        try {
            staged.index=model.index;staged.entries.putAll(model.entries);
            for(Map.Entry<String,Document> entry:model.xmlParts.entrySet())staged.xmlParts.put(entry.getKey(),(Document)entry.getValue().cloneNode(true));
            Map<String,Element> paths=new HashMap<>();NodeList current=staged.xmlParts.get(MAIN).getElementsByTagName("*");for(int i=0;i<current.getLength();i++){Element node=(Element)current.item(i);paths.put(path(node),node);}
            Element actualSection=captured.sections.get(section),stageSection=paths.get(path(actualSection));
            if(stageSection==null||!contains(model.xmlParts.get(MAIN).getDocumentElement(),actualSection))throw error("SCT_TITLE_SECTION_REMOVED",section.observedSectionId);
            List<Element> stageTitles=new ArrayList<>();Element plain=null,continued=null;SctBodyTitle plainDonor=null,continuedDonor=null;
            for(int i=0;i<titles.nodes.size();i++) {
                Element actual=titles.nodes.get(i);SctBodyTitle title=policy.bodyTitles.get(i);
                if(!contains(model.xmlParts.get(MAIN).getDocumentElement(),actual)||!hash(xml(actual)).equals(title.source.sha256))throw error("SCT_TITLE_SOURCE_CHANGED",title.source.id);
                Element node=paths.get(path(actual));if(node==null)throw error("SCT_TITLE_SOURCE_CHANGED",title.source.id);stageTitles.add(node);
                if(title.source.ordinal==policy.plainDonorOrdinal){plain=node;plainDonor=title;}if(title.source.ordinal==policy.continuedDonorOrdinal){continued=node;continuedDonor=title;}
            }
            CapturedStory defaultHeader=sctSlot(captured,section,Kind.HEADER,Variant.DEFAULT),defaultFooter=sctSlot(captured,section,Kind.FOOTER,Variant.DEFAULT);
            if(defaultHeader.part==null||defaultFooter.part==null||defaultFooter.relationship==null)throw error("SCT_TITLE_CARRIER_NOT_APPLIED",section.observedSectionId);
            Element footer=storyXml(staged,defaultFooter.part).getDocumentElement();String footerFields=storyFields(footer);
            if(!hasSctPage(footer)||!editableText(footer).contains(storyText(captured.plan.identity.number)))throw error("SCT_TITLE_DEFAULT_FOOTER_INCOMPLETE",section.observedSectionId);
            for(String line:defaultFooter.story.retainedSourceControlLines)if(!editableText(footer).contains(storyText(line)))throw error("SCT_TITLE_DEFAULT_FOOTER_INCOMPLETE",section.observedSectionId);
            Element defaultRoot=storyXml(staged,defaultHeader.part).getDocumentElement();
            if(direct(defaultRoot,"p").size()!=1||!editableText(defaultRoot).equals(storyText(captured.plan.identity.title))||!storyFields(defaultRoot).isEmpty())throw error("SCT_TITLE_HEADER_CARRIER_CHANGED",section.observedSectionId);
            installSctHeader(defaultRoot,policy,captured.plan.identity,continued);stagedChanges.dirtyParts.add(defaultHeader.part);
            CapturedStory originalFirst=sctSlot(captured,section,Kind.HEADER,Variant.FIRST);
            CapturedStory first=new CapturedStory(section,stageSection,originalFirst.story,null,null);createStory(staged,stagedChanges,first);
            installSctHeader(storyXml(staged,first.part).getDocumentElement(),policy,captured.plan.identity,plain);
            addSctFooterReference(stageSection,defaultFooter.relationship);
            Element titlePg=stageSection.getOwnerDocument().createElementNS(W,"w:titlePg");titlePg.setAttributeNS(W,"w:val","true");Node before=null;
            for(Node child=stageSection.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&Set.of("textDirection","bidi","rtlGutter","docGrid","printerSettings","sectPrChange").contains(child.getLocalName())){before=child;break;}
            stageSection.insertBefore(titlePg,before);
            Element margins=direct(stageSection,"pgMar").get(0);margins.setAttributeNS(W,"w:top","1080");margins.setAttributeNS(W,"w:header","360");
            validateSctCarrier(staged,stageSection,Kind.HEADER,Variant.DEFAULT,defaultHeader.part,defaultHeader.relationship);
            validateSctCarrier(staged,stageSection,Kind.HEADER,Variant.FIRST,first.part,first.relationship);
            validateSctCarrier(staged,stageSection,Kind.FOOTER,Variant.FIRST,defaultFooter.part,defaultFooter.relationship);
            validateSctCarrier(staged,stageSection,Kind.FOOTER,Variant.DEFAULT,defaultFooter.part,defaultFooter.relationship);
            if(direct(stageSection,"titlePg").size()!=1||!storyFields(footer).equals(footerFields))throw error("SCT_TITLE_INSTALLATION_INCOMPLETE",section.observedSectionId);
            validateSctHeader(storyXml(staged,first.part).getDocumentElement(),captured.plan.identity,policy,plain);
            validateSctHeader(defaultRoot,captured.plan.identity,policy,continued);
            int sectionIndex=captured.plan.sections.indexOf(section);
            if(sectionIndex+1<captured.plan.sections.size()) {
                CapturedStory next=sctSlot(captured,captured.plan.sections.get(sectionIndex+1),Kind.HEADER,Variant.DEFAULT);
                if(next.part==null||next.part.equals(defaultHeader.part))throw error("SCT_TITLE_NEXT_SECTION_INHERITANCE_UNSAFE",section.observedSectionId);
            }
            // Installations are verified before any exact body-title text is cleared, including P422.
            for(int i=0;i<stageTitles.size();i++) {
                Element node=stageTitles.get(i);SctBodyTitle title=policy.bodyTitles.get(i);clear(node);
                stagedChanges.changes.add(new NativeStoryChange("CLEAR_EXACT_SCT_BODY_TITLE","APPLIED",section,null,MAIN,null,title.source,0,title.exactText,null,Collections.emptyMap(),null));
                if(title.preserveSourceColumnControl)stagedChanges.changes.add(new NativeStoryChange("PRESERVED_SOURCE_COLUMN_CONTROL","PRESERVED",section,null,MAIN,null,title.source,0,title.exactText,null,Collections.emptyMap(),null));
                else {List<Element> props=direct(node,"pPr");Element values=props.isEmpty()?node.getOwnerDocument().createElementNS(W,"w:pPr"):props.get(0);if(props.isEmpty())node.insertBefore(values,node.getFirstChild());compactSpacing(node.getOwnerDocument(),values);stagedChanges.changes.add(new NativeStoryChange("COMPACT_EXACT_CLEARED_SCT_TITLE_SPACING","APPLIED",section,null,MAIN,null,title.source,0,title.exactText,null,Map.of("before","0","after","0","line","1","lineRule","exact"),null));}
            }
            stagedChanges.dirtyParts.add(MAIN);
            stagedChanges.changes.add(new NativeStoryChange("INSTALL_SCT_DEFAULT_TITLE_HEADER","APPLIED",section,defaultHeader.story.expectedBinding,defaultHeader.part,defaultHeader.relationship,continuedDonor.source,0,continuedDonor.exactText,null,Map.of("headerStyleId",policy.headerStyleId,"stylesPartSha256",policy.stylesPartSha256,"styleNodeSha256",policy.headerStyleNodeSha256),null));
            stagedChanges.changes.add(new NativeStoryChange("INSTALL_SCT_FIRST_TITLE_HEADER","APPLIED",section,originalFirst.story.expectedBinding,first.part,first.relationship,plainDonor.source,0,plainDonor.exactText,null,Map.of("headerStyleId",policy.headerStyleId,"stylesPartSha256",policy.stylesPartSha256,"styleNodeSha256",policy.headerStyleNodeSha256),null));
            stagedChanges.changes.add(new NativeStoryChange("LINK_SCT_FIRST_FOOTER_TO_VERIFIED_DEFAULT","APPLIED",section,sctSlot(captured,section,Kind.FOOTER,Variant.FIRST).story.expectedBinding,defaultFooter.part,defaultFooter.relationship,null,0,null,null,Collections.emptyMap(),null));
            stagedChanges.changes.add(new NativeStoryChange("ENABLE_SCT_MAIN_FIRST_PAGE","APPLIED",section,null,MAIN,null,null,0,null,section.titlePg,Map.of("val","true"),null));
            stagedChanges.changes.add(new NativeStoryChange("SCT_TWO_LINE_HEADER_FRAME","APPLIED",section,null,MAIN,null,null,0,null,section.pgMar,Map.of("top","1080","header","360"),null));
            for(String part:stagedChanges.dirtyParts)staged.entries.put(part,xml(staged.xmlParts.get(part)));validateStoryPackageLimits(staged);
            replacements.put(actualSection,importedSctChildren(actualSection,stageSection));
            for(int i=0;i<titles.nodes.size();i++)replacements.put(titles.nodes.get(i),importedSctChildren(titles.nodes.get(i),stageTitles.get(i)));
        }catch(RuntimeException unsupported){skipSctTitles(captured,unsupported instanceof EditException?((EditException)unsupported).getCode():"SCT_TITLE_STAGING_UNSUPPORTED");return;}
        // Preserve every original paragraph/section Element referenced by later CS marker attachment.
        for(Map.Entry<Element,List<Node>> change:replacements.entrySet()){Element node=change.getKey();while(node.hasChildNodes())node.removeChild(node.getFirstChild());for(Node child:change.getValue())node.appendChild(child);}
        for(String part:stagedChanges.dirtyParts)if(!MAIN.equals(part)){model.xmlParts.put(part,staged.xmlParts.get(part));model.entries.put(part,staged.entries.get(part));}
        captured.dirtyParts.addAll(stagedChanges.dirtyParts);captured.changes.addAll(stagedChanges.changes);
    }
    private static List<Node> importedSctChildren(Element target,Element staged){List<Node> nodes=new ArrayList<>();for(Node child=staged.getFirstChild();child!=null;child=child.getNextSibling())nodes.add(target.getOwnerDocument().importNode(child,true));return nodes;}
    private static boolean hasSctPage(Element footer){for(Element field:elementsIn(footer,W,"fldSimple"))if(field.getAttributeNS(W,"instr").trim().matches("(?is)^PAGE\\b.*"))return true;for(Element instruction:elementsIn(footer,W,"instrText"))if(instruction.getTextContent().trim().matches("(?is)^PAGE\\b.*"))return true;return false;}
    private static void installSctHeader(Element root,SctTitleMigration policy,Identity identity,Element donor) {
        while(root.hasChildNodes())root.removeChild(root.getFirstChild());Element project=identityStoryParagraph(appendStyledStoryParagraph(root,storyText(identity.title),policy.headerStyleId),identity.title);centerSctHeader(project);
        Element heading=appendStyledStoryParagraph(root,"",policy.headerStyleId);for(Element run:new ArrayList<>(direct(heading,"r")))heading.removeChild(run);centerSctHeader(heading);
        Document document=root.getOwnerDocument();for(Element sourceRun:direct(donor,"r")){List<Element> texts=direct(sourceRun,"t");if(texts.isEmpty())continue;Element run=document.createElementNS(W,"w:r");for(Element props:direct(sourceRun,"rPr"))run.appendChild(document.importNode(props,true));for(Element text:texts)run.appendChild(document.importNode(text,true));heading.appendChild(run);}
    }
    private static void centerSctHeader(Element paragraph){Element props=direct(paragraph,"pPr").get(0);Element align=paragraph.getOwnerDocument().createElementNS(W,"w:jc");align.setAttributeNS(W,"w:val","center");props.appendChild(align);}
    private static void validateSctHeader(Element root,Identity identity,SctTitleMigration policy,Element donor) {
        List<Element> paragraphs=direct(root,"p");if(paragraphs.size()!=2||!editableText(paragraphs.get(0)).equals(storyText(identity.title))
                ||!descendantText(paragraphs.get(1),"t").equals(descendantText(donor,"t"))||!storyFields(root).isEmpty()
                ||has(root,"lastRenderedPageBreak")||has(root,"bookmarkStart")||has(root,"bookmarkEnd")||has(paragraphs.get(1),"br")||has(root,"drawing"))throw error("SCT_TITLE_HEADER_NOT_INSTALLED",policy.section.observedSectionId);
        for(Element paragraph:paragraphs)if(direct(paragraph,"pPr").size()!=1||direct(direct(paragraph,"pPr").get(0),"pStyle").size()!=1
                ||!policy.headerStyleId.equals(direct(direct(paragraph,"pPr").get(0),"pStyle").get(0).getAttributeNS(W,"val")))throw error("SCT_TITLE_HEADER_STYLE_NOT_INSTALLED",policy.section.observedSectionId);
    }
    private static void addSctFooterReference(Element section,String relationship) {
        for(Element reference:direct(section,"footerReference"))if("first".equals(reference.getAttributeNS(W,"type")))throw error("SCT_TITLE_FIRST_FOOTER_ALREADY_PRESENT","first");
        Element reference=section.getOwnerDocument().createElementNS(W,"w:footerReference");reference.setAttributeNS(W,"w:type","first");reference.setAttributeNS(OFFICE_REL,"r:id",relationship);Node before=null;
        for(Node child=section.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!is(child,"headerReference")&&!is(child,"footerReference")){before=child;break;}section.insertBefore(reference,before);
    }
    private static void validateSctCarrier(PackageModel model,Element section,Kind kind,Variant variant,String part,String id) {
        String local=kind==Kind.HEADER?"headerReference":"footerReference";Element found=null;for(Element reference:direct(section,local))if(variant.name().toLowerCase(Locale.ROOT).equals(reference.getAttributeNS(W,"type"))){if(found!=null)throw error("SCT_TITLE_DUPLICATE_CARRIER",part);found=reference;}
        if(found==null||!id.equals(found.getAttributeNS(OFFICE_REL,"id")))throw error("SCT_TITLE_REFERENCE_NOT_INSTALLED",part);
        Element relation=null;for(Element value:storyRelationships(model,false))if(id.equals(value.getAttribute("Id")))relation=value;
        if(relation==null||!(OFFICE_REL+"/"+kind.name().toLowerCase(Locale.ROOT)).equals(relation.getAttribute("Type"))
                ||!part.equals(resolveStoryTarget(relation.getAttribute("Target")))||optionalAttribute(relation,"TargetMode")!=null&&!"Internal".equals(optionalAttribute(relation,"TargetMode")))throw error("SCT_TITLE_RELATIONSHIP_NOT_INSTALLED",part);
        Element root=storyXml(model,part).getDocumentElement();if(!is(root,kind==Kind.HEADER?"hdr":"ftr"))throw error("SCT_TITLE_PART_NOT_INSTALLED",part);validateStoryContentType(model,part,kind);
    }

    private static boolean identityNeedsEastAsiaFont(String value){return value.codePoints().anyMatch(cp->(cp>=0x3000&&cp<=0xd7af)||(cp>=0xf900&&cp<=0xfaff)||(cp>=0xff00&&cp<=0xffef)||(cp>=0x20000&&cp<=0x323af));}
    // Only newly generated identity text uses the same East Asian face as product reports.
    private static Element identityStoryParagraph(Element paragraph,String value){if(identityNeedsEastAsiaFont(value))for(Element run:direct(paragraph,"r"))identityRunFont(run,value);return paragraph;}
    private static Element storyRunProperties(Element run){List<Element> values=direct(run,"rPr");if(!values.isEmpty())return values.get(0);Element properties=run.getOwnerDocument().createElementNS(W,"w:rPr");run.insertBefore(properties,run.getFirstChild());return properties;}
    private static void identityRunFont(Element run,String value){
        if(!identityNeedsEastAsiaFont(value))return;Element properties=storyRunProperties(run);List<Element> values=direct(properties,"rFonts");Element fonts=values.isEmpty()?run.getOwnerDocument().createElementNS(W,"w:rFonts"):values.get(0);
        if(values.isEmpty()){List<Element> styles=direct(properties,"rStyle");properties.insertBefore(fonts,styles.isEmpty()?properties.getFirstChild():styles.get(styles.size()-1).getNextSibling());}fonts.removeAttributeNS(W,"eastAsiaTheme");fonts.setAttributeNS(W,"w:eastAsia","Microsoft YaHei");
    }
    private static Element appendStyledStoryParagraph(Element root,String text,String styleId){Element paragraph=appendStoryParagraph(root,text);Document document=root.getOwnerDocument();Element properties=document.createElementNS(W,"w:pPr"),style=document.createElementNS(W,"w:pStyle");style.setAttributeNS(W,"w:val",styleId);properties.appendChild(style);paragraph.insertBefore(properties,paragraph.getFirstChild());return paragraph;}
    private static void appendNativePage(Element paragraph){Document document=paragraph.getOwnerDocument();Element field=document.createElementNS(W,"w:fldSimple");field.setAttributeNS(W,"w:instr"," PAGE ");field.setAttributeNS(W,"w:dirty","true");Element run=document.createElementNS(W,"w:r"),text=document.createElementNS(W,"w:t");run.appendChild(text);field.appendChild(run);paragraph.appendChild(field);}
    private static Element appendStoryParagraph(Element root,String text){Element paragraph=root.getOwnerDocument().createElementNS(W,"w:p");root.appendChild(paragraph);appendStoryRun(paragraph,text);return paragraph;}
    private static void appendStoryRun(Element paragraph,String value){String text=storyText(value);for(String fragment:text.split("[\n\t]",-1))validText(fragment,"native-story-plain-text");Element run=paragraph.getOwnerDocument().createElementNS(W,"w:r"),node=paragraph.getOwnerDocument().createElementNS(W,"w:t");run.appendChild(node);paragraph.appendChild(run);setReplacementText(node,text);}
    private static void createStory(PackageModel model,CapturedStories captured,CapturedStory item){
        Kind kind=item.story.expectedBinding.kind;String stem=kind==Kind.HEADER?"header":"footer";Document types=storyXml(model,CONTENT_TYPES);List<Element> relations=storyRelationships(model,true);Set<String> reserved=new HashSet<>(model.entries.keySet());reserved.addAll(model.xmlParts.keySet());for(Element relation:relations)if((OFFICE_REL+"/header").equals(relation.getAttribute("Type"))||(OFFICE_REL+"/footer").equals(relation.getAttribute("Type"))){String mode=optionalAttribute(relation,"TargetMode");if(mode==null||"Internal".equals(mode))reserved.add(resolveStoryTarget(relation.getAttribute("Target")));}NodeList overrides=types.getDocumentElement().getElementsByTagNameNS(CT_NS,"Override");for(int i=0;i<overrides.getLength();i++){String part=((Element)overrides.item(i)).getAttribute("PartName");if(part.startsWith("/"))reserved.add(part.substring(1));}int next=1;while(reserved.contains("word/"+stem+next+".xml"))next++;String part="word/"+stem+next+".xml";
        Set<String> ids=new HashSet<>();for(Element relation:relations)ids.add(relation.getAttribute("Id"));int rid=1;while(ids.contains("rId"+rid))rid++;String id="rId"+rid;
        try{Document document=parse(("<w:"+(kind==Kind.HEADER?"hdr":"ftr")+" xmlns:w=\""+W+"\"/>").getBytes(StandardCharsets.UTF_8));model.xmlParts.put(part,document);}catch(Exception failure){throw error("STORY_PART_CREATION_UNSUPPORTED",part);}
        Document relationDocument=model.xmlParts.get(STORY_RELS);Element relation=relationDocument.createElementNS(REL_NS,"Relationship");relation.setAttribute("Id",id);relation.setAttribute("Type",OFFICE_REL+"/"+stem);relation.setAttribute("Target",part.substring("word/".length()));relationDocument.getDocumentElement().appendChild(relation);
        Element type=types.createElementNS(CT_NS,"Override");type.setAttribute("PartName","/"+part);type.setAttribute("ContentType",storyContentType(kind));types.getDocumentElement().appendChild(type);
        Element reference=item.sectionNode.getOwnerDocument().createElementNS(W,"w:"+stem+"Reference");reference.setAttributeNS(W,"w:type",item.story.expectedBinding.variant.name().toLowerCase(Locale.ROOT));reference.setAttributeNS(OFFICE_REL,"r:id",id);Node before=null;for(Node child=item.sectionNode.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&(kind==Kind.HEADER&&!is(child,"headerReference")||kind==Kind.FOOTER&&!is(child,"headerReference")&&!is(child,"footerReference"))){before=child;break;}item.sectionNode.insertBefore(reference,before);
        item.part=part;item.relationship=id;captured.dirtyParts.addAll(Arrays.asList(MAIN,STORY_RELS,CONTENT_TYPES,part));
    }
    private static List<NativeStoryChange> storyLedger(PackageModel model,CapturedStories captured){List<NativeStoryChange> rows=new ArrayList<>();for(NativeStoryChange row:captured.changes){NativeStoryChange bound=new NativeStoryChange(row.action,row.status,row.section,row.binding,row.generatedPartName,row.generatedRelationshipId,row.paragraph,row.sourcePageOrdinal,row.oldText,row.originalProperty,row.newAttributes,row.generatedPartName==null?null:hash(model.entries.get(row.generatedPartName)));if(row.binding!=null&&!"PRESERVED".equals(row.status)||"COMPACT_EXACT_CLEARED_STATIC_FOOTER_SPACING".equals(row.action))bound.sourceFormatting=captured.plan.staticFooterSourceFormat;rows.add(bound);}return rows;}

    private static final class PackageModel {
        final LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();
        final Map<String,Document> xmlParts=new LinkedHashMap<>();
        final Map<String,Element> nodes=new LinkedHashMap<>();
        final Set<Element> retainedCellParagraphs=Collections.newSetFromMap(new IdentityHashMap<Element,Boolean>());
        final Map<String,List<Element>> insertedParagraphs=new LinkedHashMap<>();
        TemplateIndex index;
    }
    private static PackageModel load(byte[] bytes) {
        if(bytes==null||bytes.length<4||bytes[0]!='P'||bytes[1]!='K')throw error("INVALID_DOCX_PACKAGE","source");
        PackageModel model=new PackageModel();long total=0;byte[] buffer=new byte[8192];
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while((entry=in.getNextEntry())!=null) {
                String name=entry.getName();
                if(model.entries.size()>=COUNT_LIMIT||name.startsWith("/")||name.contains("\\")||name.contains("../")||name.contains(":"))throw error("UNSAFE_ZIP_ENTRY",name);
                if(model.entries.containsKey(name))throw error("DUPLICATE_ZIP_ENTRY",name);
                ByteArrayOutputStream out=new ByteArrayOutputStream();long expanded=0;int n;
                boolean xml=name.endsWith(".xml")||name.endsWith(".rels");
                while((n=in.read(buffer))!=-1) {
                    total+=n;expanded+=n;
                    if(expanded>ENTRY_LIMIT||(xml&&expanded>XML_LIMIT)||total>TOTAL_LIMIT)throw error("DOCX_INFLATION_LIMIT",name);
                    out.write(buffer,0,n);
                }
                model.entries.put(name,out.toByteArray());
            }
            if(!model.entries.containsKey(MAIN)||!model.entries.containsKey("[Content_Types].xml"))throw error("INVALID_DOCX_PACKAGE","source");
            model.xmlParts.put(CONTENT_TYPES,validateContentType(model.entries.get(CONTENT_TYPES)));
            List<Paragraph> paragraphs=new ArrayList<>(),all=new ArrayList<>();Map<String,NodeAnchor> anchors=new LinkedHashMap<>();List<Feature> features=new ArrayList<>();
            for(Map.Entry<String,byte[]> part:model.entries.entrySet()) {
                if(!part.getKey().matches("word/(?:document|header[0-9]+|footer[0-9]+|footnotes|endnotes)\\.xml"))continue;
                Document document=parse(part.getValue());
                if(!W.equals(document.getDocumentElement().getNamespaceURI()))throw error("UNSUPPORTED_DOCX_CODEC",part.getKey());
                if(MAIN.equals(part.getKey())&&(!is(document.getDocumentElement(),"document")||direct(document.getDocumentElement(),"body").size()!=1))throw error("UNSUPPORTED_DOCX_CODEC",part.getKey());
                model.xmlParts.put(part.getKey(),document);
                NodeList sections=document.getElementsByTagNameNS(W,"sectPr"),nodes=document.getElementsByTagNameNS(W,"p");
                for(int i=0;i<nodes.getLength();i++) {
                    Element p=(Element)nodes.item(i);Paragraph paragraph=new Paragraph(part.getKey(),path(p),p,i+1,sections);
                    all.add(paragraph);if(MAIN.equals(part.getKey()))paragraphs.add(paragraph);model.nodes.put(paragraph.id,p);
                }
                for(String local:Arrays.asList("tc","tr","tbl","sectPr","p")) {
                    NodeList structural=document.getElementsByTagNameNS(W,local);
                    for(int i=0;i<structural.getLength();i++) {
                        Element node=(Element)structural.item(i);NodeAnchor anchor=new NodeAnchor(part.getKey(),node);
                        anchors.put(anchor.id,anchor);model.nodes.put(anchor.id,node);
                    }
                }
                features.addAll(features(part.getKey(),document,model.nodes));
            }
            Map<String,String> hashes=new LinkedHashMap<>();for(Map.Entry<String,byte[]> e:model.entries.entrySet())hashes.put(e.getKey(),hash(e.getValue()));
            model.index=new TemplateIndex(hash(bytes),paragraphs,hashes,anchors,features,all);return model;
        } catch(EditException e){throw e;} catch(Exception e){throw error("INVALID_DOCX_PACKAGE","source");}
    }
    private static Document validateContentType(byte[] bytes) throws Exception {
        Document document=parse(bytes);String namespace="http://schemas.openxmlformats.org/package/2006/content-types";
        if(!namespace.equals(document.getDocumentElement().getNamespaceURI())||!"Types".equals(document.getDocumentElement().getLocalName()))throw error("UNSUPPORTED_DOCX_CODEC","[Content_Types].xml");
        NodeList overrides=document.getElementsByTagNameNS(namespace,"Override");int matches=0;
        for(int i=0;i<overrides.getLength();i++) {
            Element type=(Element)overrides.item(i);
            if("/word/document.xml".equals(type.getAttribute("PartName"))) {
                matches++;
                if(!"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml".equals(type.getAttribute("ContentType")))throw error("UNSUPPORTED_DOCX_CODEC","word/document.xml");
            }
        }
        if(matches!=1)throw error("UNSUPPORTED_DOCX_CODEC","word/document.xml");
        return document;
    }
    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
    private static byte[] xml(Node node) {
        try {
            TransformerFactory factory=TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
            Transformer transformer=factory.newTransformer();transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,node instanceof Document?"no":"yes");
            transformer.setOutputProperty(OutputKeys.INDENT,"no");ByteArrayOutputStream out=new ByteArrayOutputStream();
            transformer.transform(new DOMSource(node),new StreamResult(out));return out.toByteArray();
        }catch(Exception e){throw error("XML_CODEC_UNAVAILABLE","source");}
    }
    private static String path(Element element) {
        List<String> parts=new ArrayList<>();Node current=element;
        while(current instanceof Element) {
            int n=1;for(Node prev=current.getPreviousSibling();prev!=null;prev=prev.getPreviousSibling())
                if(prev instanceof Element&&Objects.equals(prev.getNamespaceURI(),current.getNamespaceURI())&&Objects.equals(prev.getLocalName(),current.getLocalName()))n++;
            parts.add(current.getNodeName()+"["+n+"]");current=current.getParentNode();
        }
        Collections.reverse(parts);return "/"+String.join("/",parts);
    }
    private static String descendantText(Element node,String local) {
        StringBuilder text=new StringBuilder();NodeList list=node.getElementsByTagNameNS(W,local);
        for(int i=0;i<list.getLength();i++)text.append(list.item(i).getTextContent());return text.toString();
    }
    private static String editableText(Node node) {
        if(node instanceof Element&&W.equals(node.getNamespaceURI())) {
            if("pPr".equals(node.getLocalName())||"rPr".equals(node.getLocalName()))return "";
            if("t".equals(node.getLocalName()))return node.getTextContent();
            if("tab".equals(node.getLocalName()))return "\t";
            if("br".equals(node.getLocalName())||"cr".equals(node.getLocalName()))return "\n";
        }
        StringBuilder out=new StringBuilder();for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())out.append(editableText(child));
        return out.toString();
    }
    private static String hash(byte[] bytes) {
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();
            for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static final class Segment {
        final Element node;final int start,end;final boolean safe;
        Segment(Element node,int start,int end,boolean safe){this.node=node;this.start=start;this.end=end;this.safe=safe;}
    }
    private static List<Segment> segments(Element p) {
        List<Segment> result=new ArrayList<>();collectSegments(p,p,result,new int[]{0});return result;
    }
    private static void collectSegments(Node node,Element p,List<Segment> out,int[] offset) {
        if(node instanceof Element&&W.equals(node.getNamespaceURI())) {
            String local=node.getLocalName();
            if("pPr".equals(local)||"rPr".equals(local))return;
            // Source spelling/grammar markers carry no contractual anchor or visible control.
            // Keep them in place while permitting a text span across split proofing runs.
            if("proofErr".equals(local))return;
            if("t".equals(local)||"tab".equals(local)||"br".equals(local)||"cr".equals(local)) {
                int length="t".equals(local)?node.getTextContent().length():1;
                boolean safe="t".equals(local)&&is(node.getParentNode(),"r")&&node.getParentNode().getParentNode()==p;
                out.add(new Segment((Element)node,offset[0],offset[0]+length,safe));offset[0]+=length;return;
            }
            if(!"p".equals(local)&&!"r".equals(local))out.add(new Segment((Element)node,offset[0],offset[0],false));
        }else if(node instanceof Element) {
            out.add(new Segment((Element)node,offset[0],offset[0],false));
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())collectSegments(child,p,out,offset);
    }
    private static boolean is(Node node,String local){return node instanceof Element&&W.equals(node.getNamespaceURI())&&local.equals(node.getLocalName());}
    private static boolean has(Element node,String local){return node.getElementsByTagNameNS(W,local).getLength()>0;}
    private static String ancestorId(String part,Element node,String local) {
        for(Node parent=node.getParentNode();parent instanceof Element;parent=parent.getParentNode())if(is(parent,local))return part+"#"+path((Element)parent);
        return null;
    }
    private static List<Element> direct(Node node,String local) {
        List<Element> out=new ArrayList<>();for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(is(child,local))out.add((Element)child);return out;
    }
    private static boolean contains(Node ancestor,Node child){for(Node n=child.getParentNode();n!=null;n=n.getParentNode())if(n==ancestor)return true;return false;}
    private static boolean plainNativeTab(Element node,Element paragraph){return is(node,"tab")&&!node.hasChildNodes()&&node.getAttributes().getLength()==0&&is(node.getParentNode(),"r")&&node.getParentNode().getParentNode()==paragraph;}
    private static void validateSpan(Element target,Edit edit) {
        if(!is(target,"p"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
        String original=editableText(target);
        if(edit.start<0||edit.end<edit.start||edit.end>original.length()||!original.substring(edit.start,edit.end).equals(edit.expectedText))throw error("TEXT_EXPECTATION_MISMATCH",edit.operationId);
        if(edit.type==EditType.INSERT_TEXT) {
            if(edit.start!=edit.end||!"".equals(edit.expectedText))throw error("INVALID_INSERTION_POINT",edit.operationId);
        }else if(edit.start==edit.end)throw error("EMPTY_SPAN_UNSUPPORTED",edit.operationId);
        for(int offset:new int[]{edit.start,edit.end})if(offset>0&&offset<original.length()&&Character.isHighSurrogate(original.charAt(offset-1))&&Character.isLowSurrogate(original.charAt(offset)))throw error("INVALID_TEXT_BOUNDARY",edit.operationId);
        if(edit.replacement==null)throw error("INVALID_REPLACEMENT",edit.operationId);
        for(String fragment:edit.replacement.split("[\n\t]",-1))validText(fragment,edit.operationId);
        if(edit.labelOutsideFields)validateLabelOutsideFields(target,edit);
        else {
            for(Segment segment:segments(target))if(!segment.safe&&!(edit.type!=EditType.INSERT_TEXT&&plainNativeTab(segment.node,target))&&(edit.type==EditType.INSERT_TEXT?segment.start<=edit.start&&edit.start<=segment.end:segment.start<edit.end&&segment.end>edit.start||segment.start==segment.end&&edit.start<segment.start&&segment.start<edit.end))throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
            if(has(target,"fldChar")||has(target,"instrText")||has(target,"fldSimple"))throw error("FIELD_TEXT_EDIT_UNSUPPORTED",edit.operationId);
        }
    }
    private static void validateLabelOutsideFields(Element target,Edit edit) {
        Node container=null;Deque<Integer> fieldStarts=new ArrayDeque<>();Deque<Boolean> separated=new ArrayDeque<>();
        for(Segment segment:segments(target)) {
            if(is(segment.node,"fldChar")) {
                String kind=segment.node.getAttributeNS(W,"fldCharType");
                if("begin".equals(kind)){fieldStarts.push(segment.start);separated.push(false);}
                else if("separate".equals(kind)&&!fieldStarts.isEmpty()&&!separated.peek()){separated.pop();separated.push(true);}
                else if("end".equals(kind)&&!fieldStarts.isEmpty()) {
                    int first=fieldStarts.pop();separated.pop();
                    if(edit.start<segment.start&&edit.end>first)throw error("FIELD_TEXT_EDIT_UNSUPPORTED",edit.operationId);
                }else throw error("INCOMPLETE_FIELD_SCOPE",edit.operationId);
            }else if(is(segment.node,"instrText")&&fieldStarts.isEmpty())throw error("INCOMPLETE_FIELD_SCOPE",edit.operationId);
            boolean intersects=segment.start<edit.end&&segment.end>edit.start;
            boolean internalControl=segment.start==segment.end&&edit.start<segment.start&&segment.start<edit.end;
            if(intersects) {
                if(!is(segment.node,"t")||!is(segment.node.getParentNode(),"r"))throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
                Node actual=segment.node.getParentNode().getParentNode();
                if(actual!=target&&!(is(actual,"hyperlink")&&actual.getParentNode()==target))throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
                if(!fieldStarts.isEmpty())throw error("FIELD_TEXT_EDIT_UNSUPPORTED",edit.operationId);
                if(container==null)container=actual;
                else if(container!=actual)throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
            }else if(internalControl)throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
        }
        if(!fieldStarts.isEmpty())throw error("INCOMPLETE_FIELD_SCOPE",edit.operationId);
        if(edit.tocFieldLabel&&(container==null||!is(container,"hyperlink")))throw error("TOC_LABEL_HYPERLINK_REQUIRED",edit.operationId);
    }
    private static final class LabelFieldScope {
        final StringBuilder instruction=new StringBuilder();boolean separated,complete,invalid;
    }
    /** Detached paragraph snapshots cannot see a field that starts in an earlier paragraph. */
    private static void validateGlobalLabelFields(Element target,Edit edit) {
        validateGlobalLabelFields(target,edit.start,edit.end,edit.tocFieldLabel,edit.operationId);
    }
    /** Full static text clears share the field guard without authorizing a local label replacement. */
    private static void validateGlobalLabelFields(Element target,int start,int end,boolean tocFieldLabel,String operationId) {
        Set<Element> selected=new HashSet<>();
        for(Segment segment:segments(target))if(is(segment.node,"t")&&segment.start<end&&segment.end>start)selected.add(segment.node);
        Deque<LabelFieldScope> active=new ArrayDeque<>();Set<LabelFieldScope> touched=new LinkedHashSet<>();
        NodeList nodes=target.getOwnerDocument().getElementsByTagName("*");
        for(int i=0;i<nodes.getLength();i++) {
            Element node=(Element)nodes.item(i);
            if(is(node,"fldChar")) {
                String kind=node.getAttributeNS(W,"fldCharType");
                if("begin".equals(kind))active.push(new LabelFieldScope());
                else if("separate".equals(kind)&&!active.isEmpty()) {LabelFieldScope scope=active.peek();if(scope.separated)scope.invalid=true;scope.separated=true;}
                else if("end".equals(kind)&&!active.isEmpty())active.pop().complete=true;
                else if(!active.isEmpty())active.peek().invalid=true;
            }else if(is(node,"instrText")&&!active.isEmpty()) {LabelFieldScope scope=active.peek();if(scope.separated)scope.invalid=true;scope.instruction.append(node.getTextContent());}
            if(selected.contains(node))for(LabelFieldScope scope:active){if(!scope.separated)scope.invalid=true;touched.add(scope);}
        }
        if(tocFieldLabel) {
            if(touched.size()!=1)throw error("FIELD_TEXT_EDIT_UNSUPPORTED",operationId);
            LabelFieldScope scope=touched.iterator().next();
            if(!scope.complete||!scope.separated||scope.invalid)throw error("INCOMPLETE_FIELD_SCOPE",operationId);
            if(!scope.instruction.toString().trim().matches("(?is)^TOC(?:\\s.*)?$"))throw error("FIELD_TEXT_EDIT_UNSUPPORTED",operationId);
        }else if(!touched.isEmpty())throw error("FIELD_TEXT_EDIT_UNSUPPORTED",operationId);
    }
    private static void validateStructural(Element target,Edit edit) {
        if(edit.type==EditType.CLEAR_EDITORIAL_CELL) {
            if(!is(target,"tc"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
            if(has(target,"tbl"))throw error("NESTED_EDITORIAL_CELL_UNSUPPORTED",edit.operationId);
        }else if(edit.type==EditType.DELETE_ROW) {
            if(!is(target,"tr"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
            if(direct(target.getParentNode(),"tr").size()==1)throw error("LAST_TABLE_ROW_UNSUPPORTED",edit.operationId);
            // An unrelated merge elsewhere in the table does not bind this row.
            // Rows participating in a merge, or immediately before a continuation, remain guarded.
            if(has(target,"vMerge"))throw error("MERGED_ROW_DELETION_UNSUPPORTED",edit.operationId);
            Node next=target.getNextSibling();while(next!=null&&!is(next,"tr"))next=next.getNextSibling();
            if(next!=null)for(Element cell:direct(next,"tc"))for(Element properties:direct(cell,"tcPr"))for(Element merge:direct(properties,"vMerge"))if(!"restart".equals(merge.getAttributeNS(W,"val")))throw error("MERGED_ROW_DELETION_UNSUPPORTED",edit.operationId);
        }else if(edit.type==EditType.KEEP_ROW_TOGETHER) {
            if(!is(target,"tr")||!is(target.getParentNode(),"tbl"))throw error("TARGET_KIND_MISMATCH",edit.operationId);
            if(has(target,"sectPr")||has(target,"pageBreakBefore"))throw error("ROW_COHESION_CONTROL_UNSUPPORTED",edit.operationId);
            NodeList breaks=target.getElementsByTagNameNS(W,"br");
            for(int i=0;i<breaks.getLength();i++)if("page".equals(((Element)breaks.item(i)).getAttributeNS(W,"type")))throw error("ROW_COHESION_CONTROL_UNSUPPORTED",edit.operationId);
        }else if(edit.type==EditType.DELETE_TABLE) {
            if(!is(target,"tbl")||!is(target.getParentNode(),"body")&&!is(target.getParentNode(),"tc"))throw error("TABLE_LOCATION_UNSUPPORTED",edit.operationId);
        }else {
            if(!is(target,"p")||!is(target.getParentNode(),"tc")&&!is(target.getParentNode(),"body"))throw error("PARAGRAPH_LOCATION_UNSUPPORTED",edit.operationId);
        }
        if(edit.type==EditType.CLEAR_EDITORIAL_CELL||edit.type==EditType.CLEAR_PARAGRAPH||edit.type==EditType.CLEAR_PARAGRAPH_AND_NUMBERING)validateClear(target,edit.operationId);
        else if(has(target,"sectPr"))throw error("SECTION_DELETION_UNSUPPORTED",edit.operationId);
    }
    private static void validateClear(Element target,String operationId) {
        if(has(target,"fldChar")||has(target,"instrText")||has(target,"fldSimple"))throw error("FIELD_TEXT_CLEAR_UNSUPPORTED",operationId);
    }
    private static void clear(Element p) {
        NodeList texts=p.getElementsByTagNameNS(W,"t");for(int i=0;i<texts.getLength();i++)texts.item(i).setTextContent("");
    }
    private static void clearNumbering(Element p) {
        for(Element properties:direct(p,"pPr"))for(Element numbering:direct(properties,"numPr"))properties.removeChild(numbering);
    }
    private static void keepRowTogether(Element row) {
        List<Element> properties=direct(row,"trPr");
        Element trPr=properties.isEmpty()?row.getOwnerDocument().createElementNS(W,"w:trPr"):properties.get(0);
        if(properties.isEmpty())row.insertBefore(trPr,row.getFirstChild());
        List<Element> existing=direct(trPr,"cantSplit");
        if(!existing.isEmpty()){existing.get(0).setAttributeNS(W,"w:val","1");return;}
        Element property=row.getOwnerDocument().createElementNS(W,"w:cantSplit");property.setAttributeNS(W,"w:val","1");
        Node before=null;List<String> following=Arrays.asList("trHeight","tblHeader","tblCellSpacing","jc","hidden","ins","del","trPrChange");
        for(Node child=trPr.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&W.equals(child.getNamespaceURI())&&following.contains(child.getLocalName())){before=child;break;}
        trPr.insertBefore(property,before);
    }
    private static void validText(String text,String id) {
        if(text==null)throw error("INVALID_REPLACEMENT",id);
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i);
            if(cp<0x20||cp==0xfffe||cp==0xffff||(cp>=0xd800&&cp<=0xdfff))throw error("UNSUPPORTED_REPLACEMENT_CHARACTER",id);
            i+=Character.charCount(cp);
        }
    }
    private static void setText(Element text,String value) {
        text.setTextContent(value);
        if(!value.isEmpty()&&(Character.isWhitespace(value.charAt(0))||Character.isWhitespace(value.charAt(value.length()-1))))
            text.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve");
    }
    /** New body line breaks/tabs stay inside the selected run and preserve its native properties. */
    private static void setReplacementText(Element text,String value) {
        Node parent=text.getParentNode(),before=text.getNextSibling();Element current=text;int start=0;
        for(int i=0;i<value.length();i++)if(value.charAt(i)=='\n'||value.charAt(i)=='\t') {
            setText(current,value.substring(start,i));
            parent.insertBefore(text.getOwnerDocument().createElementNS(W,value.charAt(i)=='\t'?"w:tab":"w:br"),before);
            current=(Element)text.cloneNode(false);parent.insertBefore(current,before);start=i+1;
        }
        setText(current,value.substring(start));
    }
    private static void replace(PackageModel model,Element p,Edit edit) {replace(model,p,edit,false);}
    private static Element replace(PackageModel model,Element p,Edit edit,boolean isolateReplacement) {
        // Materialize only selected tabs so the existing run-preserving replacement owns their exact offsets.
        for(Segment segment:segments(p))if(plainNativeTab(segment.node,p)&&segment.start<edit.end&&segment.end>edit.start){Element text=p.getOwnerDocument().createElementNS(W,"w:t");setText(text,"\t");segment.node.getParentNode().replaceChild(text,segment.node);}
        List<Segment> segments=segments(p);Segment firstSegment=null;
        for(Segment segment:segments)if((segment.safe||edit.labelOutsideFields&&is(segment.node,"t"))&&segment.start<edit.end&&segment.end>edit.start){firstSegment=segment;break;}
        if(firstSegment==null)throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
        Element firstRun=(Element)firstSegment.node.getParentNode(),donor=edit.donorId==null?firstRun:model.nodes.get(edit.donorId);
        List<Element> firstProperties=direct(firstRun,"rPr"),donorProperties=direct(donor,"rPr");
        String originalStyle=firstProperties.isEmpty()?"":intrinsicHash(firstProperties.get(0)),donorStyle=donorProperties.isEmpty()?"":intrinsicHash(donorProperties.get(0));
        boolean split=isolateReplacement||!originalStyle.equals(donorStyle),first=true;Element result=firstRun;
        for(Segment segment:segments) {
            if(segment.start>=edit.end||segment.end<=edit.start)continue;
            String old=segment.node.getTextContent();
            int from=Math.max(0,edit.start-segment.start),to=Math.min(old.length(),edit.end-segment.start);
            String replacement=old.substring(0,from)+(first&&!split?edit.replacement:"")+old.substring(to);
            setReplacementText(segment.node,replacement);first=false;
        }
        if(split) {
            Element text=firstSegment.node;int from=edit.start-firstSegment.start;String remaining=text.getTextContent();
            Element right=(Element)firstRun.cloneNode(false);if(!firstProperties.isEmpty())right.appendChild(firstProperties.get(0).cloneNode(true));
            Element suffix=(Element)text.cloneNode(true);setText(suffix,remaining.substring(from));right.appendChild(suffix);setText(text,remaining.substring(0,from));
            while(text.getNextSibling()!=null)right.appendChild(text.getNextSibling());
            Element replacement=p.getOwnerDocument().createElementNS(W,"w:r");if(!donorProperties.isEmpty())replacement.appendChild(donorProperties.get(0).cloneNode(true));
            Element inserted=p.getOwnerDocument().createElementNS(W,"w:t");replacement.appendChild(inserted);setReplacementText(inserted,edit.replacement);
            Node container=firstRun.getParentNode();container.insertBefore(replacement,firstRun.getNextSibling());container.insertBefore(right,replacement.getNextSibling());result=replacement;
        }
        return result;
    }
    private static void insertText(Element paragraph,Edit edit) {
        for(Segment segment:segments(paragraph))if(segment.safe&&segment.start<=edit.start&&edit.start<=segment.end) {
            String old=segment.node.getTextContent();int offset=edit.start-segment.start;
            setReplacementText(segment.node,old.substring(0,offset)+edit.replacement+old.substring(offset));return;
        }
        if(!editableText(paragraph).isEmpty())throw error("UNSUPPORTED_INLINE_FEATURE",edit.operationId);
        Element run=paragraph.getOwnerDocument().createElementNS(W,"w:r"),text=paragraph.getOwnerDocument().createElementNS(W,"w:t");
        run.appendChild(text);paragraph.appendChild(run);setReplacementText(text,edit.replacement);
    }
    private static byte[] write(LinkedHashMap<String,byte[]> entries) {
        try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)) {
            for(Map.Entry<String,byte[]> entry:entries.entrySet()) {
                ZipEntry target=new ZipEntry(entry.getKey());target.setTime(0);zip.putNextEntry(target);zip.write(entry.getValue());zip.closeEntry();
            }
            zip.finish();return out.toByteArray();
        }catch(IOException e){throw error("DOCX_WRITE_FAILED","batch");}
    }
    private static final class FieldBuilder {
        final Element start;final StringBuilder instruction=new StringBuilder();
        FieldBuilder(Element start){this.start=start;}
    }
    private static List<Feature> features(String part,Document doc,Map<String,Element> nodes) {
        List<Feature> result=new ArrayList<>();Map<String,Element> bookmarkEnds=new HashMap<>();
        NodeList ends=doc.getElementsByTagNameNS(W,"bookmarkEnd");for(int i=0;i<ends.getLength();i++) {Element end=(Element)ends.item(i);bookmarkEnds.put(end.getAttributeNS(W,"id"),end);}
        Deque<FieldBuilder> fields=new ArrayDeque<>();NodeList all=doc.getElementsByTagName("*");
        for(int i=0;i<all.getLength();i++) {
            Element node=(Element)all.item(i);nodes.put(part+"#"+path(node),node);String local=node.getLocalName();
            if(W.equals(node.getNamespaceURI())) {
                if("bookmarkStart".equals(local))result.add(new Feature("BOOKMARK",part,node,bookmarkEnds.get(node.getAttributeNS(W,"id")),node.getAttributeNS(W,"name"),null));
                else if("sectPr".equals(local))result.add(new Feature("SECTION",part,node,node,null,null));
                else if("fldSimple".equals(local))result.add(new Feature("FIELD",part,node,node,null,node.getAttributeNS(W,"instr")));
                else if("fldChar".equals(local)) {
                    String type=node.getAttributeNS(W,"fldCharType");
                    if("begin".equals(type))fields.push(new FieldBuilder(node));
                    else if("end".equals(type)&&!fields.isEmpty()) {FieldBuilder field=fields.pop();result.add(new Feature("FIELD",part,field.start,node,null,field.instruction.toString()));}
                }else if("instrText".equals(local)&&!fields.isEmpty())fields.peek().instruction.append(node.getTextContent());
            }else if(M.equals(node.getNamespaceURI())&&("oMath".equals(local)||"oMathPara".equals(local)))result.add(new Feature("OMML",part,node,node,null,null));
        }
        for(FieldBuilder field:fields)result.add(new Feature("FIELD",part,field.start,null,null,field.instruction.toString()));
        return result;
    }
    private static boolean inScope(Element scope,Element feature){return feature!=null&&(scope==feature||contains(scope,feature));}
    private static boolean insertion(Edit edit){return edit.type==EditType.INSERT_BEFORE||edit.type==EditType.INSERT_AFTER;}
    private static boolean textEdit(Edit edit){return edit.type==EditType.REPLACE_SPAN||edit.type==EditType.INSERT_TEXT;}
    /** Insertion points touching another edit's boundary also conflict: their order must never decide the result. */
    public static boolean overlappingSpans(int start,int end,int otherStart,int otherEnd){return start==end?otherStart<=start&&start<=otherEnd:otherStart==otherEnd?start<=otherStart&&otherStart<=end:start<otherEnd&&otherStart<end;}
    private static boolean destructive(Edit edit){return !textEdit(edit)&&edit.type!=EditType.KEEP_ROW_TOGETHER&&!insertion(edit);}
    private static boolean removesNodes(PackageModel model,Edit edit) {
        return (edit.type==EditType.DELETE_PARAGRAPH||edit.type==EditType.DELETE_ROW||edit.type==EditType.DELETE_TABLE)&&!model.retainedCellParagraphs.contains(model.nodes.get(edit.targetId));
    }
    private static void validateFeatureScopes(PackageModel model,SourceEditBatch batch) {
        for(Feature feature:model.index.features) {
            if(!"BOOKMARK".equals(feature.kind)&&!"FIELD".equals(feature.kind))continue;
            boolean start=false,end=false;String operation="batch";
            for(Edit edit:batch.edits)if(removesNodes(model,edit)) {
                Element scope=model.nodes.get(edit.targetId);
                if(inScope(scope,model.nodes.get(feature.startId))){start=true;operation=edit.operationId;}
                if(inScope(scope,model.nodes.get(feature.endId)))end=true;
            }
            if(start!=end)throw error("PARTIAL_"+feature.kind+"_SCOPE",operation);
        }
    }
    private static List<FeatureImpact> featureImpacts(PackageModel model,SourceEditBatch batch) {
        List<FeatureImpact> impacts=new ArrayList<>();Set<String> removedBookmarks=new HashSet<>();List<String> allIds=new ArrayList<>();
        for(Edit edit:batch.edits)allIds.add(edit.operationId);
        for(Feature feature:model.index.features)if("BOOKMARK".equals(feature.kind))for(Edit edit:batch.edits)
            if(removesNodes(model,edit)&&inScope(model.nodes.get(edit.targetId),model.nodes.get(feature.startId)))removedBookmarks.add(feature.name);
        for(Feature feature:model.index.features) {
            List<String> touching=new ArrayList<>();boolean removed=false,cleared=false;
            for(Edit edit:batch.edits)if(affectsFeature(model,edit,feature)) {
                touching.add(edit.operationId);
                removed|=removesNodes(model,edit)&&inScope(model.nodes.get(edit.targetId),model.nodes.get(feature.startId));
                cleared|=destructive(edit)&&!removesNodes(model,edit);
            }
            if(!touching.isEmpty())impacts.add(new FeatureImpact(feature,removed?"REMOVED_WITH_ADOPTED_SCOPE":cleared?"TEXT_CLEARED_ANCHOR_PRESERVED":"TEXT_CHANGED_ANCHOR_PRESERVED",touching));
            else if("FIELD".equals(feature.kind)&&feature.instruction!=null) {
                String status=null;for(String name:removedBookmarks)if(name!=null&&!name.isEmpty()&&feature.instruction.contains(name)){status="REFERENCE_TARGET_REMOVED_UNREFRESHED";break;}
                if(status==null&&feature.instruction.trim().matches("(?is)^(?:TOC|PAGE|PAGEREF|NUMPAGES)\\b.*"))status="PAGINATION_REVIEW_REQUIRED";
                if(status!=null)impacts.add(new FeatureImpact(feature,status,allIds));
            }
        }
        return impacts;
    }
    private static boolean affectsFeature(PackageModel model,Edit edit,Feature feature) {
        if(edit.type==EditType.KEEP_ROW_TOGETHER)return false;
        Element scope=model.nodes.get(edit.targetId),start=model.nodes.get(feature.startId),end=model.nodes.get(feature.endId);
        if(removesNodes(model,edit)&&(inScope(scope,start)||inScope(scope,end)))return true;
        if(!"BOOKMARK".equals(feature.kind)&&!"FIELD".equals(feature.kind))return false;
        if(start==null||end==null||!scope.getOwnerDocument().equals(start.getOwnerDocument()))return false;
        if(insertion(edit)) {
            boolean startsBefore=(start.compareDocumentPosition(scope)&Node.DOCUMENT_POSITION_FOLLOWING)!=0;
            boolean endsAfter=(end.compareDocumentPosition(scope)&Node.DOCUMENT_POSITION_PRECEDING)!=0;
            if(edit.type==EditType.INSERT_AFTER)return (startsBefore||inScope(scope,start))&&endsAfter&&!inScope(scope,end);
            return startsBefore&&!inScope(scope,start)&&(endsAfter||inScope(scope,end));
        }
        if(textEdit(edit)) {
            for(Segment segment:segments(scope))if(segment.safe&&(edit.type==EditType.INSERT_TEXT?segment.start<=edit.start&&edit.start<=segment.end:segment.start<edit.end&&segment.end>edit.start)&&insideFeature(segment.node,start,end))return true;
        }else {
            NodeList texts=scope.getElementsByTagNameNS(W,"t");
            for(int i=0;i<texts.getLength();i++)if(!texts.item(i).getTextContent().isEmpty()&&insideFeature(texts.item(i),start,end))return true;
        }
        return false;
    }
    private static boolean insideFeature(Node node,Element start,Element end) {
        if(start==end)return inScope(start,(Element)node);
        return (start.compareDocumentPosition(node)&Node.DOCUMENT_POSITION_FOLLOWING)!=0&&(end.compareDocumentPosition(node)&Node.DOCUMENT_POSITION_PRECEDING)!=0;
    }
    private static SourceEditBatch expand(SourceEditBatch batch) {
        List<Edit> expanded=new ArrayList<>();
        for(Edit edit:batch.edits) {
            if(edit.type!=EditType.REMOVE_NODES){expanded.add(edit);continue;}
            if(edit.targets.isEmpty())throw error("EMPTY_NODE_SCOPE",edit.operationId);
            for(NodeAnchor node:edit.targets) {
                EditType type="p".equals(node.kind)?EditType.DELETE_PARAGRAPH:"tr".equals(node.kind)?EditType.DELETE_ROW:"tbl".equals(node.kind)?EditType.DELETE_TABLE:null;
                if(type==null)throw error("TARGET_KIND_MISMATCH",edit.operationId);
                expanded.add(new Edit(edit.operationId,node.id,node.sha256,type));
            }
        }
        return new SourceEditBatch(batch.expectedSourceSha256,expanded);
    }
    private static void validateRemainingRows(PackageModel model,SourceEditBatch batch) {
        Map<Node,Integer> deleted=new IdentityHashMap<>();
        for(Edit edit:batch.edits)if(edit.type==EditType.DELETE_ROW) {Node table=model.nodes.get(edit.targetId).getParentNode();deleted.put(table,deleted.getOrDefault(table,0)+1);}
        for(Map.Entry<Node,Integer> e:deleted.entrySet())if(e.getValue()>=direct(e.getKey(),"tr").size())throw error("LAST_TABLE_ROW_UNSUPPORTED","batch");
    }
    private static void retainRequiredCellParagraphs(PackageModel model,SourceEditBatch batch) {
        Map<Node,List<Element>> deleted=new IdentityHashMap<>();Map<Element,String> operationIds=new IdentityHashMap<>();
        for(Edit edit:batch.edits)if(edit.type==EditType.DELETE_PARAGRAPH) {
            Element p=model.nodes.get(edit.targetId);Node parent=p.getParentNode();
            if(is(parent,"tc")){deleted.computeIfAbsent(parent,key->new ArrayList<>()).add(p);operationIds.put(p,edit.operationId);}
        }
        for(Map.Entry<Node,List<Element>> entry:deleted.entrySet()) {
            List<Element> all=direct(entry.getKey(),"p");
            if(entry.getValue().size()==all.size()) {
                Element retained=all.get(all.size()-1);validateClear(retained,operationIds.get(retained));model.retainedCellParagraphs.add(retained);
            }
        }
    }
    private static void validateInsertion(PackageModel model,Element target,Edit edit) {
        if(!is(target,"p")||!is(target.getParentNode(),"tc")&&!is(target.getParentNode(),"body"))throw error("PARAGRAPH_LOCATION_UNSUPPORTED",edit.operationId);
        Element donor=model.nodes.get(edit.donorId);
        if(donor==null||!is(donor,"p")||!hash(xml(donor)).equals(edit.donorSha256))throw error("STYLE_DONOR_MISMATCH",edit.operationId);
        if(has(donor,"sectPr"))throw error("SECTION_STYLE_DONOR_UNSUPPORTED",edit.operationId);
        if(edit.lines.isEmpty())throw error("EMPTY_INSERTION",edit.operationId);
        for(String line:edit.lines)validText(line==null?null:line.replace("\t",""),edit.operationId);
    }
    private static void insert(PackageModel model,Element anchor,Edit edit) {
        Element donor=model.nodes.get(edit.donorId);Document doc=anchor.getOwnerDocument();
        Node before=edit.type==EditType.INSERT_AFTER?anchor.getNextSibling():anchor;
        for(String line:edit.lines) {
            Element p=doc.createElementNS(W,"w:p");List<Element> props=direct(donor,"pPr");if(!props.isEmpty())p.appendChild(props.get(0).cloneNode(true));
            Element r=doc.createElementNS(W,"w:r");List<Element> runs=direct(donor,"r");
            if(!runs.isEmpty()){List<Element> rProps=direct(runs.get(0),"rPr");if(!rProps.isEmpty())r.appendChild(rProps.get(0).cloneNode(true));}
            String[] fragments=line.split("\t",-1);for(int i=0;i<fragments.length;i++) {
                if(i>0)r.appendChild(doc.createElementNS(W,"w:tab"));Element t=doc.createElementNS(W,"w:t");setText(t,fragments[i]);r.appendChild(t);
            }
            p.appendChild(r);anchor.getParentNode().insertBefore(p,before);model.insertedParagraphs.computeIfAbsent(edit.operationId,id->new ArrayList<>()).add(p);
        }
    }
    private static List<AppliedEdit> ledger(PackageModel model,SourceEditBatch original,String generatedHash) {
        List<AppliedEdit> ledger=new ArrayList<>();
        for(Edit edit:original.edits) {
            List<TargetChange> targets=new ArrayList<>();
            if(edit.type==EditType.REMOVE_NODES)for(NodeAnchor node:edit.targets)targets.add(new TargetChange(node,model.nodes.get(node.id),model.retainedCellParagraphs.contains(model.nodes.get(node.id))));
            else targets.add(new TargetChange(model.index.anchor(edit.targetId),model.nodes.get(edit.targetId),model.retainedCellParagraphs.contains(model.nodes.get(edit.targetId))));
            RunInfo donor=null;
            if(textEdit(edit)&&edit.donorId==null)for(RunInfo run:model.index.paragraph(edit.targetId).getRuns())if(edit.type==EditType.INSERT_TEXT?run.start<=edit.start&&edit.start<=run.end:run.start<edit.end&&run.end>edit.start){donor=run;break;}
            List<String> added=new ArrayList<>();for(Element paragraph:model.insertedParagraphs.getOrDefault(edit.operationId,Collections.emptyList()))if(paragraph.getParentNode()!=null)added.add(MAIN+"#"+path(paragraph));
            ledger.add(new AppliedEdit(edit,model.index.sourceSha256,generatedHash,targets,donor,added));
        }
        return ledger;
    }
    /** Namespace-aware node fingerprint ignores inherited xmlns declarations but includes all content/properties. */
    private static String intrinsicHash(Node node) {StringBuilder out=new StringBuilder();fingerprint(node,out);return hash(out.toString().getBytes(StandardCharsets.UTF_8));}
    private static void fingerprint(Node node,StringBuilder out) {
        out.append(node.getNodeType()).append(':');
        if(node instanceof Element) {
            out.append('{').append(node.getNamespaceURI()).append('}').append(node.getLocalName());List<String> attributes=new ArrayList<>();NamedNodeMap map=node.getAttributes();
            for(int i=0;i<map.getLength();i++){Node attr=map.item(i);if(!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI()))attributes.add("{"+attr.getNamespaceURI()+"}"+attr.getLocalName()+"="+attr.getNodeValue());}
            Collections.sort(attributes);for(String attr:attributes)out.append('|').append(attr.length()).append(':').append(attr);
            out.append('[');for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())fingerprint(child,out);out.append(']');
        }else {String value=node.getNodeValue();out.append(value==null?0:value.length()).append(':').append(value);}
    }
}
