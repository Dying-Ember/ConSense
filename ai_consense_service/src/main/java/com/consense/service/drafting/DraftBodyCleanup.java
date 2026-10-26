package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.web.dto.DraftingDtos.BodyScopePatch;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.helpers.DefaultHandler;

/** Explicit current-artifact empty native scopes; paragraph clearing keeps its original semantics. */
final class DraftBodyCleanup {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main", MAIN="word/document.xml";
    private static final Set<String> SAFE=new HashSet<>(Arrays.asList(("p pPr pStyle r rPr rStyle rFonts b bCs i iCs caps smallCaps strike dstrike outline shadow emboss imprint noProof snapToGrid vanish webHidden color spacing w kern position sz szCs highlight u effect bdr shd fitText vertAlign rtl cs em lang eastAsianLayout specVanish numPr ilvl numId tabs tab ind jc textDirection textAlignment contextualSpacing mirrorIndents adjustRightInd autoSpaceDE autoSpaceDN bidi kinsoku wordWrap overflowPunct topLinePunct suppressAutoHyphens suppressLineNumbers framePr outlineLvl divId cnfStyle pBdr widowControl keepNext keepLines pageBreakBefore tbl tblPr tblPrEx tblStyle tblpPr tblOverlap bidiVisual tblW tblInd tblBorders top left bottom right start end insideH insideV tblCellMar tblLayout tblLook tblCaption tblDescription tblGrid gridCol tblStyleRowBandSize tblStyleColBandSize tr trPr cantSplit tblHeader trHeight gridBefore gridAfter wBefore wAfter tc tcPr tcW gridSpan hMerge vMerge tcBorders tcMar vAlign tcFitText noWrap hideMark t br cr ptab lastRenderedPageBreak bookmarkStart bookmarkEnd proofErr smartTag smartTagPr attr").split(" ")));
    private DraftBodyCleanup() { }

    private static final class Candidate {
        final List<NodeAnchor> targets;final Map<String,Object> info;final boolean numbering;
        Candidate(List<NodeAnchor> targets,Map<String,Object> info,boolean numbering){this.targets=targets;this.info=info;this.numbering=numbering;}
    }
    private static final class Context {
        final TemplateIndex index;final Map<String,Element> parts;final Element document,body;
        final Map<Element,NodeAnchor> anchors=new IdentityHashMap<>();final Map<Element,int[]> bounds=new IdentityHashMap<>();
        final Map<String,List<Map<String,Object>>> registered=new HashMap<>();final Map<String,List<Paragraph>> markers;
        final Map<String,List<Element>> starts=new LinkedHashMap<>(),ends=new LinkedHashMap<>(),names=new HashMap<>();
        final boolean valid;
        Context(byte[] docx,TemplateIndex index,List<Map<String,Object>> bindings){
            if(!DraftPdfConverter.sha256(docx).equals(index.getSourceSha256()))throw fail("BODY_SCOPE_SOURCE_CONFLICT");
            this.index=index;parts=parseParts(docx);document=parts.get(MAIN);body=document==null?null:first(document,"body");valid=body!=null&&!parts.containsValue(null);
            markers=DraftDocumentBindings.markers(index);for(Map<String,Object> row:bindings)registered.computeIfAbsent(String.valueOf(row.get("bindingId")),key->new ArrayList<>()).add(row);
            if(!valid)return;
            mark(document,new int[]{0});for(NodeAnchor anchor:index.getAnchors())if(MAIN.equals(anchor.getPart())){Element node=resolve(document,anchor.getPath());if(node!=null)anchors.put(node,anchor);}
            for(Element part:parts.values())for(Element start:descendants(part,"bookmarkStart"))names.computeIfAbsent(start.getAttributeNS(W,"name"),key->new ArrayList<>()).add(start);
            for(Element start:descendants(document,"bookmarkStart"))starts.computeIfAbsent(start.getAttributeNS(W,"id"),key->new ArrayList<>()).add(start);
            for(Element end:descendants(document,"bookmarkEnd"))ends.computeIfAbsent(end.getAttributeNS(W,"id"),key->new ArrayList<>()).add(end);
        }
        void mark(Element node,int[] position){int from=position[0]++;for(Node n=node.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element)mark((Element)n,position);bounds.put(node,new int[]{from,position[0]-1});}
        int[] interval(List<Element> nodes){return new int[]{bounds.get(nodes.get(0))[0],bounds.get(nodes.get(nodes.size()-1))[1]};}
        Element featureNode(String id){return id!=null&&id.startsWith(MAIN+"#")?resolve(document,id.substring((MAIN+"#").length())):null;}
        boolean overlaps(int[] selected,Element start,Element end){return start!=null&&bounds.get(start)[0]<=selected[1]&&(end==null||bounds.get(end)[0]>=selected[0]);}
        boolean nativeSafe(List<Element> nodes){
            for(Element node:nodes)if(!safeEmpty(node))return false;
            int[] selected=interval(nodes);
            for(Feature feature:index.getFeatures())if(MAIN.equals(feature.getPart())&&"FIELD".equals(feature.getKind())&&overlaps(selected,featureNode(feature.getStartId()),featureNode(feature.getEndId())))return false;
            // Range/permission carriers can enclose a scope even when neither endpoint is inside it.
            for(Element start:all(document))if(rangeStart(start)){String local=start.getLocalName(),endName=local.substring(0,local.length()-5)+"End",id=start.getAttributeNS(W,"id");List<Element> matching=new ArrayList<>();for(Element end:descendants(document,endName))if(id.equals(end.getAttributeNS(W,"id")))matching.add(end);if(matching.size()!=1||overlaps(selected,start,matching.get(0)))return false;}
            return true;
        }
        boolean validBookmark(String id){List<Element> a=starts.getOrDefault(id,Collections.emptyList()),z=ends.getOrDefault(id,Collections.emptyList());return !id.isEmpty()&&a.size()==1&&z.size()==1&&bounds.get(a.get(0))[0]<bounds.get(z.get(0))[0]&&names.getOrDefault(a.get(0).getAttributeNS(W,"name"),Collections.emptyList()).size()==1;}
        Map<String,Object> bookmarks(List<Element> nodes,boolean formatting){
            Set<Element> selected=Collections.newSetFromMap(new IdentityHashMap<Element,Boolean>());for(Element node:nodes)selected.addAll(all(node));
            for(Element node:selected)if(is(node,"bookmarkStart")||is(node,"bookmarkEnd"))if(!validBookmark(node.getAttributeNS(W,"id")))return null;
            List<String> members=new ArrayList<>(),removed=new ArrayList<>(),touched=new ArrayList<>();List<Object> enclosing=new ArrayList<>(),crossing=new ArrayList<>();int[] span=interval(nodes);
            for(Map.Entry<String,List<Element>> entry:starts.entrySet()){
                Element start=entry.getValue().get(0);List<Element> finish=ends.getOrDefault(entry.getKey(),Collections.emptyList());Element end=finish.size()==1?finish.get(0):null;
                if(!overlaps(span,start,end))continue;if(!validBookmark(entry.getKey()))return null;
                String name=start.getAttributeNS(W,"name");if(!(name.matches("CS[A-Za-z0-9]{1,38}")||name.matches("CL[A-Za-z0-9]{1,38}")||name.startsWith("_Toc")))return null;
                boolean a=selected.contains(start),z=selected.contains(end);if(!formatting&&a!=z)return null;touched.add(name);
                if(!a&&!z)enclosing.add(DraftBusinessRules.map("id",entry.getKey(),"name",name,"startRetained",true,"endRetained",true));
                if(a!=z)crossing.add(DraftBusinessRules.map("id",entry.getKey(),"name",name,"startInScope",a,"endInScope",z,"endpointsRetained",true));
                if(!formatting&&a&&z)removed.add(name);
                if(name.startsWith("CS")&&(a||z)){
                    List<Map<String,Object>> rows=registered.getOrDefault(name,Collections.emptyList());List<Paragraph> actual=markers.getOrDefault(name,Collections.emptyList());
                    if(actual.size()!=1)return null;
                    if(rows.isEmpty()&&formatting)continue;
                    if(rows.size()!=1||!Objects.equals(actual.get(0).getId(),rows.get(0).get("resultParagraphId"))||!Objects.equals(DraftAdoption.hash(actual.get(0).getText()),rows.get(0).get("textHash")))return null;
                    members.add(name);
                }
            }
            Collections.sort(members);if(references(formatting?touched:removed))return null;
            return DraftBusinessRules.map("memberBindingIds",members,"enclosingRetainedBookmarks",enclosing,"retainedCrossingBookmarks",crossing,"touchedBookmarkNames",touched);
        }
        List<String> proof(List<Element> nodes,boolean directOnly){
            Set<String> ids=new HashSet<>();for(Element node:nodes)if(!directOnly||is(node,"p")){NodeAnchor anchor=anchors.get(node);if(anchor!=null)ids.addAll(anchor.getParagraphIds());}
            List<String> result=new ArrayList<>();for(Map.Entry<String,List<Map<String,Object>>> entry:registered.entrySet())if(entry.getValue().size()==1){Map<String,Object> row=entry.getValue().get(0);String id=String.valueOf(row.get("resultParagraphId"));List<Paragraph> actual=markers.getOrDefault(entry.getKey(),Collections.emptyList());if(ids.contains(id)&&actual.size()==1&&id.equals(actual.get(0).getId())&&blank(actual.get(0).getText())&&Objects.equals(DraftAdoption.hash(actual.get(0).getText()),row.get("textHash"))&&!blank(row.get("sourceText"))&&"body_edited".equals(row.get("applicationStatus"))&&!DraftBusinessRules.list(row.get("bodyOperationIds")).isEmpty())result.add(entry.getKey());}Collections.sort(result);return result;
        }
        boolean references(List<String> targets){
            for(String name:targets){Pattern token=Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(name)+"(?![A-Za-z0-9_])");
                for(Element part:parts.values()){
                    StringBuilder instructions=new StringBuilder();for(Element instruction:descendants(part,"instrText"))instructions.append(instruction.getTextContent());if(token.matcher(instructions).find())return true;
                    for(Element field:descendants(part,"fldSimple"))if(token.matcher(field.getAttributeNS(W,"instr")).find())return true;
                    for(Element link:descendants(part,"hyperlink"))if(name.equals(link.getAttributeNS(W,"anchor")))return true;
                    for(Element element:all(part))if("Relationship".equals(element.getLocalName())){String target=element.getAttribute("Target");int hash=target.lastIndexOf('#');if(hash>=0){String fragment=target.substring(hash+1);try{fragment=java.net.URLDecoder.decode(fragment,"UTF-8");}catch(Exception invalid){return true;}if(name.equals(fragment))return true;}}
                }
            }return false;
        }
        boolean noInheritedNumbering(Element paragraph){
            List<Element> properties=direct(paragraph,"pPr");if(properties.size()>1)return false;List<Element> selected=properties.isEmpty()?Collections.emptyList():direct(properties.get(0),"pStyle");if(selected.size()>1)return false;String styleId=selected.isEmpty()?null:selected.get(0).getAttributeNS(W,"val");if(styleId!=null&&styleId.isEmpty())return false;
            Element styles=null;for(Element part:parts.values())if(is(part,"styles")){if(styles!=null)return false;styles=part;}
            for(Element part:parts.values())for(Element element:all(part))if("Relationship".equals(element.getLocalName())&&element.getAttribute("Type").endsWith("/styles")){if("External".equals(element.getAttribute("TargetMode")))return false;try{java.net.URI target=java.net.URI.create(MAIN).resolve(element.getAttribute("Target")).normalize();String path=target.getPath();if(target.isAbsolute()||target.getFragment()!=null||target.getQuery()!=null||path==null||styles==null||parts.get(path.startsWith("/")?path.substring(1):path)!=styles)return false;}catch(IllegalArgumentException invalid){return false;}}
            if(styles==null)return styleId==null;
            List<Element> defaults=direct(styles,"docDefaults");if(defaults.size()>1||!defaults.isEmpty()&&count(defaults.get(0),"numPr")>0)return false;
            Map<String,List<Element>> byId=new HashMap<>();List<String> defaultIds=new ArrayList<>();for(Element style:direct(styles,"style")){String id=style.getAttributeNS(W,"styleId");byId.computeIfAbsent(id,key->new ArrayList<>()).add(style);String flag=style.getAttributeNS(W,"default");if("paragraph".equals(style.getAttributeNS(W,"type"))&&Arrays.asList("1","true","on").contains(flag))defaultIds.add(id);}
            if(styleId==null){if(defaultIds.size()>1)return false;styleId=defaultIds.isEmpty()?(byId.containsKey("Normal")?"Normal":null):defaultIds.get(0);}if(styleId!=null&&styleId.isEmpty())return false;
            Set<String> visited=new HashSet<>();while(styleId!=null){if(!visited.add(styleId))return false;List<Element> matches=byId.getOrDefault(styleId,Collections.emptyList());if(matches.size()!=1)return false;Element style=matches.get(0);if(!"paragraph".equals(style.getAttributeNS(W,"type"))||count(style,"numPr")>0)return false;List<Element> parent=direct(style,"basedOn");if(parent.size()>1)return false;styleId=parent.isEmpty()?null:parent.get(0).getAttributeNS(W,"val");if(styleId!=null&&styleId.isEmpty())return false;}
            return true;
        }
        List<Element> closedPrefix(List<Element> nodes){
            int remaining=nodes.size(),runEnd=interval(nodes)[1];while(remaining>0){Element tail=nodes.get(remaining-1);if(!is(tail,"p"))break;boolean outbound=false;for(Element start:descendants(tail,"bookmarkStart")){String id=start.getAttributeNS(W,"id");if(start.getAttributeNS(W,"name").startsWith("_Toc")&&validBookmark(id)&&bounds.get(ends.get(id).get(0))[0]>runEnd)outbound=true;}if(!outbound)break;remaining--;}
            return remaining>0&&remaining<nodes.size()?new ArrayList<>(nodes.subList(0,remaining)):Collections.emptyList();
        }
        List<Object> retainedBoundaryBookmarks(List<Element> nodes){List<Object> result=new ArrayList<>();for(Element node:nodes)for(Element start:descendants(node,"bookmarkStart")){String id=start.getAttributeNS(W,"id");if(validBookmark(id))result.add(DraftBusinessRules.map("id",id,"name",start.getAttributeNS(W,"name"),"startRetained",true,"endRetained",true));}return result;}
    }

    static List<Map<String,Object>> candidates(byte[] docx,TemplateIndex index,List<Map<String,Object>> bindings){List<Map<String,Object>> result=new ArrayList<>();for(Candidate candidate:available(new Context(docx,index,bindings)))result.add(candidate.info);return result;}
    /** Read-only editorial evidence; a cleared anchor and an explicitly removed scope remain different states. */
    static Map<String,Map<String,Object>> headingEndpointEvidence(byte[] docx,String originalSourceSha,TemplateIndex index,List<Map<String,Object>> bindings,List<Object> ledger,Set<String> wanted){
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();Context context=new Context(docx,index,bindings);if(!context.valid)return result;
        for(String name:wanted){List<Map<String,Object>> rows=context.registered.getOrDefault(name,Collections.emptyList());if(rows.size()!=1)continue;Map<String,Object> binding=rows.get(0);Object sourceId=binding.get("sourceParagraphId"),sourceText=binding.get("sourceText");
            if(!name.equals(binding.get("bookmarkName"))||!name.equals(binding.get("bindingId"))||!(sourceId instanceof String)||!String.valueOf(sourceId).matches("word/document.xml#/w:document\\[1\\]/w:body\\[1\\]/w:p\\[\\d+\\]")||!(sourceText instanceof String)||String.valueOf(sourceText).isEmpty()||!Objects.equals(binding.get("sourceTextHash"),DraftAdoption.hash(String.valueOf(sourceText)))||!name.equals("CS"+DraftAdoption.hash("SCC|"+originalSourceSha+"|"+sourceId).substring(0,28))||!"removed".equals(binding.get("locationStatus")))continue;
            Map<String,Object> proof=DraftBusinessRules.map("bookmarkName",name,"sourceSha256",originalSourceSha,"sourceParagraphId",sourceId,"sourceTextHash",binding.get("sourceTextHash"),"currentDocxSha256",index.getSourceSha256(),"bodyOperationIds",new ArrayList<>(strings(binding.get("bodyOperationIds"))));
            if("body_edited".equals(binding.get("applicationStatus"))&&Arrays.asList("unchanged","applied").contains(binding.get("documentEditStatus"))){
                List<String> operations=strings(binding.get("bodyOperationIds"));if(operations.isEmpty()||operations.size()!=new HashSet<>(operations).size()||operations.stream().anyMatch(id->!id.matches("body-[1-9][0-9]*")))continue;
                List<Paragraph> present=context.markers.getOrDefault(name,Collections.emptyList());if(present.size()!=1||context.names.getOrDefault(name,Collections.emptyList()).size()!=1)continue;Paragraph paragraph=present.get(0);String text=paragraph.getText();if(!text.matches("\\t{0,2}")||!Objects.equals(paragraph.getId(),binding.get("resultParagraphId"))||!Objects.equals(text,binding.get("text"))||!Objects.equals(DraftAdoption.hash(text),binding.get("textHash")))continue;
                Element node=context.featureNode(paragraph.getId()),start=context.names.get(name).get(0);String bookmarkId=start.getAttributeNS(W,"id");if(node==null||!is(node,"p")||node.getParentNode()!=context.body||!context.validBookmark(bookmarkId)||!context.noInheritedNumbering(node)||!context.nativeSafe(Collections.singletonList(node)))continue;Element end=context.ends.get(bookmarkId).get(0);Set<Element> inside=Collections.newSetFromMap(new IdentityHashMap<Element,Boolean>());inside.addAll(all(node));if(!inside.contains(start)||!inside.contains(end))continue;
                boolean safe=true;for(String unsupported:Arrays.asList("numPr","br","cr","ptab","sectPr","pageBreakBefore","pBdr"))if(count(node,unsupported)>0)safe=false;for(Element value:descendants(node,"t"))if(!value.getTextContent().isEmpty())safe=false;int tabs=0;for(Element tab:descendants(node,"tab"))if(is(tab.getParentNode(),"r"))tabs++;else if(!is(tab.getParentNode(),"tabs"))safe=false;if(!safe||tabs!=text.length())continue;
                proof.put("state","manual_text_cleared_anchor_retained");proof.put("physicalRemoved",false);proof.put("wordTextEmpty",true);proof.put("nativeTextKind",tabs==0?"empty":tabs==1?"single_native_tab":"double_native_tab");proof.put("nativeTabCount",tabs);proof.put("resultParagraphId",paragraph.getId());proof.put("resultParagraphSha256",paragraph.getSha256());proof.put("textHash",binding.get("textHash"));proof.put("nativeSafetyChecked",true);result.put(name,proof);
            }else if("body_deleted".equals(binding.get("applicationStatus"))&&"applied".equals(binding.get("documentEditStatus"))&&binding.get("resultParagraphId")==null&&context.names.getOrDefault(name,Collections.emptyList()).isEmpty()){
                Map<String,Object> removal=verifiedHeadingScopeRemoval(name,binding,index.getSourceSha256(),ledger);if(removal!=null){proof.putAll(removal);proof.put("state","explicit_body_scope_removed");proof.put("physicalRemoved",true);proof.put("nativeBookmarkAbsent",true);result.put(name,proof);}
            }
        }return result;
    }
    private static Map<String,Object> verifiedHeadingScopeRemoval(String name,Map<String,Object> binding,String currentDocxSha,List<Object> ledger){
        Map<String,Object> verified=null;List<String> bodyOperations=strings(binding.get("bodyOperationIds"));if(bodyOperations.size()!=1)return null;
        for(Object raw:ledger){Map<String,Object> receipt=DraftBusinessRules.asMap(raw);if(!"body_scope_cleanup".equals(receipt.get("kind"))||!Boolean.FALSE.equals(receipt.get("graphOrValuesChanged")))continue;String parent=String.valueOf(receipt.get("parentDocxSha256")),compiled=String.valueOf(receipt.get("compiledDocxSha256")),saved=String.valueOf(receipt.get("savedDocxSha256"));if(!parent.matches("[a-f0-9]{64}")||!compiled.matches("[a-f0-9]{64}")||!saved.matches("[a-f0-9]{64}")||!compiled.equals(receipt.get("generatedDocxSha256"))||blank(receipt.get("savedRevisionId"))||!verifiedTocSavedTransition(compiled,saved,ledger)||!verifiedTocSavedTransition(saved,currentDocxSha,ledger))continue;
            for(Object scopeRaw:DraftBusinessRules.list(receipt.get("scopes"))){Map<String,Object> scope=DraftBusinessRules.asMap(scopeRaw);List<String> members=strings(scope.get("memberBindingIds"));if(!members.contains(name))continue;if(members.size()!=new HashSet<>(members).size()||!"EXPLICIT_STRUCTURAL_SCOPE_REMOVED".equals(scope.get("status"))||!Boolean.TRUE.equals(scope.get("nativeSafetyChecked"))||!Boolean.TRUE.equals(scope.get("beforeAllWordTextBlank")))return null;
                List<Object> targets=DraftBusinessRules.list(scope.get("nativeTargets"));if(targets.isEmpty())return null;String kind=String.valueOf(scope.get("kind")),hash=String.valueOf(scope.get("nodeSha256")),scopeId=String.valueOf(scope.get("scopeId"));String expectedOperation="body_range".equals(kind)?"delete_empty_body_range":"table".equals(kind)?"delete_empty_table":"row".equals(kind)?"delete_empty_row":null;if(expectedOperation==null||!expectedOperation.equals(scope.get("operation"))||!hash.matches("[a-f0-9]{64}"))return null;
                List<String> paragraphs=new ArrayList<>();Set<String> targetIds=new HashSet<>();for(Object targetRaw:targets){Map<String,Object> target=DraftBusinessRules.asMap(targetRaw);String id=String.valueOf(target.get("id"));if(!targetIds.add(id)||!String.valueOf(target.get("sha256")).matches("[a-f0-9]{64}")||strings(target.get("paragraphIds")).isEmpty())return null;paragraphs.addAll(strings(target.get("paragraphIds")));}if(!paragraphs.equals(strings(scope.get("paragraphIds")))||paragraphs.size()!=new HashSet<>(paragraphs).size()||!(scope.get("paragraphCount") instanceof Number)||paragraphs.size()!=((Number)scope.get("paragraphCount")).intValue()||strings(scope.get("clearProofBindings")).isEmpty()||!members.containsAll(strings(scope.get("clearProofBindings"))))return null;
                if("body_range".equals(kind)){if(!hash.equals(scopeHash(targets))||!scopeId.equals("body-range:"+hash))return null;}else if(targets.size()!=1||!hash.equals(DraftBusinessRules.asMap(targets.get(0)).get("sha256"))||!scopeId.equals(DraftBusinessRules.asMap(targets.get(0)).get("id")))return null;
                String operation="body-cleanup-"+DraftAdoption.hash(parent+"|"+scopeId).substring(0,24);if(!bodyOperations.get(0).equals(operation))return null;List<Object> embedded=DraftBusinessRules.list(scope.get("appliedNativeEdits"));if(embedded.size()!=1)return null;Map<String,Object> edit=DraftBusinessRules.asMap(embedded.get(0));if(!operation.equals(edit.get("operationId"))||!"REMOVE_NODES".equals(edit.get("type"))||!parent.equals(edit.get("sourceSha256"))||!compiled.equals(edit.get("generatedSha256")))return null;
                List<Object> changes=DraftBusinessRules.list(edit.get("targets"));if(changes.size()!=targets.size())return null;for(int i=0;i<targets.size();i++){Map<String,Object> expected=DraftBusinessRules.asMap(targets.get(i)),change=DraftBusinessRules.asMap(changes.get(i)),anchor=DraftBusinessRules.asMap(change.get("source"));if(!Objects.equals(expected.get("id"),anchor.get("id"))||!Objects.equals(expected.get("sha256"),anchor.get("sha256"))||!Objects.equals(expected.get("kind"),anchor.get("kind"))||!strings(expected.get("paragraphIds")).equals(strings(anchor.get("paragraphIds")))||!"REMOVED".equals(change.get("status"))||change.get("generatedId")!=null||change.get("afterSha256")!=null)return null;}
                int operations=0;for(Object entry:ledger){Map<String,Object> row=DraftBusinessRules.asMap(entry);if(operation.equals(row.get("operationId"))){operations++;if(!JsonUtils.mapper().valueToTree(row).equals(JsonUtils.mapper().valueToTree(edit)))return null;}}if(operations!=1||verified!=null)return null;
                verified=DraftBusinessRules.map("scopeId",scopeId,"scopeNodeSha256",hash,"parentDocxSha256",parent,"compiledDocxSha256",compiled,"savedDocxSha256",saved,"savedRevisionId",receipt.get("savedRevisionId"),"operationIds",Collections.singletonList(operation),"nativeSafetyChecked",true);
            }
        }return verified;
    }
    private static boolean verifiedTocSavedTransition(String compiled,String saved,List<Object> ledger){Set<String> reachable=new HashSet<>();reachable.add(compiled);for(int pass=0;pass<=ledger.size();pass++){if(reachable.contains(saved))return true;int previous=reachable.size();for(Object raw:ledger){Map<String,Object> row=DraftBusinessRules.asMap(raw);if("toc_finalization".equals(row.get("kind"))&&"PASS".equals(row.get("status"))&&reachable.contains(row.get("compiledDocxSha256"))&&String.valueOf(row.get("finalDocxSha256")).matches("[a-f0-9]{64}")&&row.get("headingFlowRepairCount") instanceof Number&&((Number)row.get("headingFlowRepairCount")).intValue()==0&&row.get("headingMutationCount") instanceof Number&&((Number)row.get("headingMutationCount")).intValue()==0)reachable.add(String.valueOf(row.get("finalDocxSha256")));}if(previous==reachable.size())break;}return false;}
    private static List<Candidate> available(Context context){
        List<Candidate> result=new ArrayList<>();if(!context.valid)return result;Set<String> covered=new HashSet<>();List<List<Element>> runs=new ArrayList<>();List<Element> run=new ArrayList<>();
        for(Node child=context.body.getFirstChild();child!=null;child=child.getNextSibling()){if(!(child instanceof Element))continue;Element element=(Element)child;if((is(element,"p")||is(element,"tbl"))&&blank(wordText(element)))run.add(element);else if(!run.isEmpty()){runs.add(run);run=new ArrayList<>();}}if(!run.isEmpty())runs.add(run);
        for(List<Element> nodes:runs){List<String> proof=context.proof(nodes,true);if(proof.isEmpty())continue;
            Candidate range=make(context,nodes,"body_range","delete_empty_body_range",proof,false);if(range!=null){result.add(range);covered.addAll(strings(range.info.get("paragraphIds")));continue;}
            List<Element> prefix=context.closedPrefix(nodes);if(!prefix.isEmpty()){List<String> prefixProof=context.proof(prefix,true);Candidate closed=prefixProof.isEmpty()?null:make(context,prefix,"body_range","delete_empty_body_range",prefixProof,false);if(closed!=null){List<Element> retained=nodes.subList(prefix.size(),nodes.size());closed.info.put("originalRunScopeHash",scopeHash(targets(context,nodes)));closed.info.put("retainedBoundaryNativeTargets",targets(context,retained));closed.info.put("retainedBoundaryBookmarks",context.retainedBoundaryBookmarks(retained));result.add(closed);covered.addAll(strings(closed.info.get("paragraphIds")));continue;}}
            for(Element node:nodes)if(is(node,"p")&&directNumbering(node)>0){Candidate number=make(context,Collections.singletonList(node),"paragraph_numbering","clear_empty_numbering",proof,true);if(number!=null){number.info.put("enclosingRunNativeTargets",targets(context,nodes));number.info.put("enclosingRunScopeHash",scopeHash(targets(context,nodes)));result.add(number);covered.addAll(strings(number.info.get("paragraphIds")));}}
        }
        Set<String> wholeTables=new HashSet<>();
        for(String kind:Arrays.asList("tbl","tr"))for(NodeAnchor anchor:context.index.getAnchors()){
            if(!MAIN.equals(anchor.getPart())||!kind.equals(anchor.getKind()))continue;Element node=resolve(context.document,anchor.getPath());if(node==null)continue;
            if("tbl".equals(kind)&&node.getParentNode()!=context.body||"tr".equals(kind)&&(!is(node.getParentNode(),"tbl")||node.getParentNode().getParentNode()!=context.body))continue;
            if("tr".equals(kind)&&wholeTables.contains(anchor.getId().substring(0,anchor.getId().lastIndexOf('/'))))continue;
            if(intersects(anchor.getParagraphIds(),covered)||count(node,"vMerge")>0||"tr".equals(kind)&&!safeRow(node))continue;
            List<Element> one=Collections.singletonList(node);List<String> proof=context.proof(one,false);if(proof.isEmpty())continue;
            Candidate candidate=make(context,one,"tbl".equals(kind)?"table":"row","tbl".equals(kind)?"delete_empty_table":"delete_empty_row",proof,false);if(candidate!=null){result.add(candidate);if("tbl".equals(kind))wholeTables.add(anchor.getId());}
        }return result;
    }
    private static Candidate make(Context context,List<Element> nodes,String kind,String operation,List<String> proof,boolean formatting){
        if(formatting&&!context.noInheritedNumbering(nodes.get(0))||!context.nativeSafe(nodes))return null;Map<String,Object> bookmarks=context.bookmarks(nodes,formatting);if(bookmarks==null)return null;
        List<NodeAnchor> anchors=new ArrayList<>();List<String> paragraphs=new ArrayList<>();List<Integer> ordinals=new ArrayList<>();for(Element node:nodes){NodeAnchor anchor=context.anchors.get(node);if(anchor==null)return null;anchors.add(anchor);paragraphs.addAll(anchor.getParagraphIds());}for(String id:paragraphs)ordinals.add(context.index.paragraph(id).getOrdinal());
        List<String> members=strings(bookmarks.get("memberBindingIds"));if(!formatting&&members.isEmpty())return null;
        List<Object> nativeTargets=targets(context,nodes);String hash="body_range".equals(kind)?scopeHash(nativeTargets):anchors.get(0).getSha256(),id="body_range".equals(kind)?"body-range:"+hash:anchors.get(0).getId();
        int numbering=0,tabs=0,heights=0;for(Element node:nodes){numbering+=count(node,"numPr");tabs+=count(node,"tab");heights+=count(node,"trHeight");}
        Map<String,Object> info=DraftBusinessRules.map("scopeId",id,"kind",kind,"operation",operation,"nodeSha256",hash,"nativeTargets",nativeTargets,"memberBindingIds",members,"clearProofBindings",new ArrayList<>(proof),"paragraphCount",paragraphs.size(),"paragraphIds",paragraphs,"paragraphOrdinals",ordinals,"nativeNumberingCount",numbering,"nativeTabCount",tabs,"nativeRowHeightCount",heights,"previousText",neighbor(context.index,paragraphs,false),"nextText",neighbor(context.index,paragraphs,true),"enclosingRetainedBookmarks",bookmarks.get("enclosingRetainedBookmarks"),"retainedCrossingBookmarks",bookmarks.get("retainedCrossingBookmarks"),"packageXmlPartsChecked",context.parts.size());
        return new Candidate(anchors,info,formatting);
    }

    static List<Edit> compile(byte[] docx,TemplateIndex index,List<Map<String,Object>> bindings,List<BodyScopePatch> patches){
        if(patches==null||patches.isEmpty())return Collections.emptyList();
        List<Candidate> selected=select(new Context(docx,index,bindings),patches);List<Edit> edits=new ArrayList<>();for(Candidate candidate:selected){String operation="body-cleanup-"+DraftAdoption.hash(index.getSourceSha256()+"|"+candidate.info.get("scopeId")).substring(0,24);edits.add(candidate.numbering?Edit.clearParagraphAndNumbering(operation,index.paragraph(candidate.targets.get(0).getId())):Edit.removeNodes(operation,candidate.targets));}return edits;
    }
    private static List<Candidate> select(Context context,List<BodyScopePatch> patches){
        if(patches==null||patches.isEmpty())return Collections.emptyList();Map<String,Candidate> available=new HashMap<>();for(Candidate candidate:available(context))available.put(String.valueOf(candidate.info.get("scopeId")),candidate);
        List<Candidate> selected=new ArrayList<>();Set<String> ids=new HashSet<>(),paragraphs=new HashSet<>();
        for(BodyScopePatch patch:patches){if(patch==null||patch.getScopeId()==null||!ids.add(patch.getScopeId()))throw fail("BODY_SCOPE_DUPLICATE_OR_INVALID");Candidate candidate=available.get(patch.getScopeId());if(candidate==null)throw fail("BODY_SCOPE_NOT_AVAILABLE");
            if(!Objects.equals(candidate.info.get("operation"),patch.getOperation()))throw fail("BODY_SCOPE_OPERATION_UNSUPPORTED");if(!Objects.equals(candidate.info.get("nodeSha256"),patch.getExpectedScopeHash()))throw new BizException(4090,"BODY_SCOPE_HASH_CONFLICT");
            List<String> members=patch.getMemberBindingIds();if(members==null||members.size()!=new HashSet<>(members).size()||!new HashSet<>(members).equals(new HashSet<>(strings(candidate.info.get("memberBindingIds")))))throw fail("BODY_SCOPE_BINDINGS_CONFLICT");
            List<String> targetParagraphs=strings(candidate.info.get("paragraphIds"));if(intersects(targetParagraphs,paragraphs))throw fail("BODY_SCOPE_OVERLAP");paragraphs.addAll(targetParagraphs);selected.add(candidate);
        }return selected;
    }
    static void requireDisjoint(byte[] source,TemplateIndex index,List<Map<String,Object>> bindings,List<BodyScopePatch> scopes,Set<String> changedParagraphs){if(scopes==null||scopes.isEmpty())return;for(Candidate candidate:select(new Context(source,index,bindings),scopes))if(intersects(strings(candidate.info.get("paragraphIds")),changedParagraphs))throw fail("BODY_SCOPE_BLOCK_CONFLICT");}
    static void requireDisjoint(TemplateIndex index,List<BodyScopePatch> scopes,Set<String> changedParagraphs){if(scopes==null)return;for(BodyScopePatch scope:scopes){if(scope.getScopeId().startsWith("body-range:"))throw fail("BODY_SCOPE_SOURCE_REQUIRED");if(intersects(index.anchor(scope.getScopeId()).getParagraphIds(),changedParagraphs))throw fail("BODY_SCOPE_BLOCK_CONFLICT");}}

    static Map<String,Object> receipt(String revisionId,byte[] source,TemplateIndex index,List<Map<String,Object>> bindings,List<BodyScopePatch> patches,DocxEditResult result){
        if(patches==null||patches.isEmpty())return null;Context before=new Context(source,index,bindings);List<Candidate> selected=select(before,patches);Map<String,Element> afterParts=parseParts(result.getDocxBytes());if(afterParts.containsValue(null)||!afterParts.containsKey(MAIN))throw fail("BODY_SCOPE_RESULT_UNVERIFIABLE");List<Object> proofs=new ArrayList<>();
        for(Candidate candidate:selected){Map<String,Object> proof=new LinkedHashMap<>(candidate.info);List<Object> applied=new ArrayList<>();AppliedEdit match=null;
            for(AppliedEdit edit:result.getLedger())if(edit.getOperationId().startsWith("body-cleanup-")&&sameTargets(candidate.targets,edit.getTargets())){if(match!=null)throw fail("BODY_SCOPE_REMOVAL_UNVERIFIED");match=edit;applied.add(edit);}if(match==null)throw fail("BODY_SCOPE_REMOVAL_UNVERIFIED");
            boolean allTextEmpty=true;for(NodeAnchor anchor:candidate.targets)if(!wordText(resolve(before.document,anchor.getPath())).isEmpty())allTextEmpty=false;
            proof.put("beforeAllWordTextEmpty",allTextEmpty);proof.put("beforeAllWordTextBlank",true);proof.put("nativeSafetyChecked",true);proof.put("appliedNativeEdits",applied);
            if(candidate.numbering){TargetChange change=match.getTargets().get(0);if(match.getType()!=EditType.CLEAR_PARAGRAPH_AND_NUMBERING||!"APPLIED".equals(change.getStatus())||change.getGeneratedId()==null)throw fail("BODY_NUMBERING_CLEAR_UNVERIFIED");
                Element old=resolve(before.document,candidate.targets.get(0).getPath()),now=resolve(afterParts.get(MAIN),change.getGeneratedId().substring((MAIN+"#").length()));if(now==null||directNumbering(now)!=0||!sameAfterAllowedNumberingChange(old,now))throw fail("BODY_NUMBERING_CLEAR_UNVERIFIED");
                proof.put("status","EXPLICIT_EMPTY_NUMBERING_CLEARED");proof.put("retainedParagraphId",change.getGeneratedId());proof.put("beforeWordText",wordText(old));proof.put("afterWordText",wordText(now));proof.put("otherNativeTreeEqualAfterAllowedChanges",true);proof.put("inheritedNumberingExcluded",true);proof.put("allowedChanges",Arrays.asList("direct w:pPr/w:numPr removal","w:t values set to empty by CLEAR_PARAGRAPH_AND_NUMBERING"));
            }else{if(match.getType()!=EditType.REMOVE_NODES)throw fail("BODY_SCOPE_REMOVAL_UNVERIFIED");for(TargetChange change:match.getTargets())if(!"REMOVED".equals(change.getStatus())||change.getGeneratedId()!=null||change.getAfterSha256()!=null)throw fail("BODY_SCOPE_REMOVAL_UNVERIFIED");proof.put("status","EXPLICIT_STRUCTURAL_SCOPE_REMOVED");}
            proofs.add(proof);
        }return DraftBusinessRules.map("kind","body_scope_cleanup","parentRevisionId",revisionId,"parentDocxSha256",index.getSourceSha256(),"generatedDocxSha256",result.getDocxSha256(),"compiledDocxSha256",result.getDocxSha256(),"scopes",proofs,"graphOrValuesChanged",false);
    }
    private static boolean sameTargets(List<NodeAnchor> expected,List<TargetChange> actual){if(expected.size()!=actual.size())return false;for(int i=0;i<expected.size();i++)if(!expected.get(i).getId().equals(actual.get(i).getSource().getId())||!expected.get(i).getSha256().equals(actual.get(i).getSource().getSha256()))return false;return true;}
    private static boolean sameAfterAllowedNumberingChange(Element before,Element after){Element expected=(Element)before.cloneNode(true),actual=(Element)after.cloneNode(true);for(Element props:direct(expected,"pPr"))for(Element number:direct(props,"numPr"))props.removeChild(number);for(Element text:descendants(expected,"t"))text.setTextContent("");stripXmlns(expected);stripXmlns(actual);return expected.isEqualNode(actual);}
    private static void stripXmlns(Element node){List<String> remove=new ArrayList<>();NamedNodeMap attrs=node.getAttributes();for(int i=0;i<attrs.getLength();i++)if(XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attrs.item(i).getNamespaceURI()))remove.add(attrs.item(i).getNodeName());for(String name:remove)node.removeAttribute(name);for(Element child:direct(node,null))stripXmlns(child);}

    static List<Map<String,Object>> receipts(String ledgerJson){List<Map<String,Object>> rows=new ArrayList<>();for(Object raw:JsonUtils.readList(ledgerJson,Object.class)){Map<String,Object> row=DraftBusinessRules.asMap(raw);if("body_scope_cleanup".equals(row.get("kind")))rows.add(row);}return rows;}
    static List<String> removedLayoutOperations(DocxEditResult result,Map<String,Object> action){if("REMOVED_WITH_BODY_SCOPE".equals(action.get("status"))&&!DraftBusinessRules.list(action.get("bodyOperationIds")).isEmpty())return strings(action.get("bodyOperationIds"));List<String> operations=new ArrayList<>();if(action.get("generatedId")==null)return operations;for(AppliedEdit edit:result.getLedger())if(edit.getOperationId().startsWith("body-cleanup-")&&edit.getType()==EditType.REMOVE_NODES)for(TargetChange target:edit.getTargets())if("REMOVED".equals(target.getStatus())&&target.getSource().getParagraphIds().contains(String.valueOf(action.get("generatedId"))))operations.add(edit.getOperationId());return operations;}
    static List<Object> preserveHistory(String previousJson,List<Object> current){List<Object> result=new ArrayList<>(current);Set<String> keys=new HashSet<>();for(Object raw:result)keys.add(historyKey(DraftBusinessRules.asMap(raw)));for(Object raw:JsonUtils.readList(previousJson,Object.class)){Map<String,Object> row=DraftBusinessRules.asMap(raw);if(("body_scope_cleanup".equals(row.get("kind"))||String.valueOf(row.get("operationId")).startsWith("body-cleanup-"))&&keys.add(historyKey(row)))result.add(raw);}return result;}
    private static String historyKey(Map<String,Object> row){return row.get("kind")+"|"+row.get("operationId")+"|"+row.get("sourceSha256")+"|"+row.get("generatedSha256")+"|"+row.get("parentRevisionId")+"|"+row.get("generatedDocxSha256");}

    private static boolean safeEmpty(Element node){if(node==null||!blank(wordText(node))||count(node,"tbl")>0)return false;for(Element element:all(node)){if(!W.equals(element.getNamespaceURI())||!SAFE.contains(element.getLocalName()))return false;NamedNodeMap attrs=element.getAttributes();for(int i=0;i<attrs.getLength();i++)if("http://schemas.openxmlformats.org/officeDocument/2006/relationships".equals(attrs.item(i).getNamespaceURI()))return false;}return true;}
    private static boolean safeRow(Element row){if(count(row,"gridSpan")>0||count(row,"vMerge")>0||direct((Element)row.getParentNode(),"tr").size()<=1)return false;Node next=row.getNextSibling();while(next!=null&&!is(next,"tr"))next=next.getNextSibling();if(next instanceof Element)for(Element merge:descendants((Element)next,"vMerge"))if(!"restart".equals(merge.getAttributeNS(W,"val")))return false;return true;}
    private static boolean rangeStart(Element element){return W.equals(element.getNamespaceURI())&&(element.getLocalName().endsWith("RangeStart")||"permStart".equals(element.getLocalName()));}
    private static boolean intersects(Collection<String> a,Set<String> b){for(String id:a)if(b.contains(id))return true;return false;}
    private static List<Object> targets(Context context,List<Element> nodes){List<Object> targets=new ArrayList<>();for(Element node:nodes){NodeAnchor anchor=context.anchors.get(node);if(anchor==null)return Collections.emptyList();targets.add(DraftBusinessRules.map("id",anchor.getId(),"sha256",anchor.getSha256(),"kind",anchor.getKind(),"paragraphIds",anchor.getParagraphIds()));}return targets;}
    private static String scopeHash(List<Object> targets){StringBuilder canonical=new StringBuilder();for(Object raw:targets){Map<String,Object> target=DraftBusinessRules.asMap(raw);canonical.append(target.get("id")).append('|').append(target.get("sha256")).append('\n');}return DraftAdoption.hash(canonical.toString());}
    private static int directNumbering(Element p){int count=0;for(Element props:direct(p,"pPr"))count+=direct(props,"numPr").size();return count;}
    private static int count(Element node,String local){return descendants(node,local).size();}
    private static String wordText(Element node){StringBuilder text=new StringBuilder();for(Element t:descendants(node,"t"))text.append(t.getTextContent());return text.toString();}
    private static boolean blank(Object value){return value==null||String.valueOf(value).replaceAll("(?U)\\s+","").isEmpty();}
    private static boolean is(Node node,String local){return node instanceof Element&&W.equals(node.getNamespaceURI())&&local.equals(node.getLocalName());}
    private static List<String> strings(Object raw){List<String> result=new ArrayList<>();for(Object value:DraftBusinessRules.list(raw))result.add(String.valueOf(value));return result;}
    private static String neighbor(TemplateIndex index,List<String> paragraphs,boolean after){List<Paragraph> all=index.getMainParagraphs();int from=index.paragraph(paragraphs.get(after?paragraphs.size()-1:0)).getOrdinal()-1;for(int i=from+(after?1:-1);i>=0&&i<all.size();i+=after?1:-1)if(!blank(all.get(i).getCatalogText()))return all.get(i).getText();return "";}
    private static List<Element> all(Element node){List<Element> result=new ArrayList<>();result.add(node);NodeList children=node.getElementsByTagName("*");for(int i=0;i<children.getLength();i++)result.add((Element)children.item(i));return result;}
    private static List<Element> descendants(Element node,String local){List<Element> result=new ArrayList<>();NodeList list=node.getElementsByTagNameNS(W,local);for(int i=0;i<list.getLength();i++)result.add((Element)list.item(i));return result;}
    private static List<Element> direct(Element parent,String local){List<Element> result=new ArrayList<>();for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&W.equals(n.getNamespaceURI())&&(local==null||local.equals(n.getLocalName())))result.add((Element)n);return result;}
    private static Element first(Element parent,String local){List<Element> result=direct(parent,local);return result.isEmpty()?null:result.get(0);}
    private static Element resolve(Element document,String path){Element current=document;String[] parts=path.substring(1).split("/");for(int i=1;i<parts.length;i++){Matcher m=Pattern.compile("[^:]+:([A-Za-z0-9]+)\\[(\\d+)\\]").matcher(parts[i]);if(!m.matches())return null;List<Element> children=direct(current,m.group(1));int n=Integer.parseInt(m.group(2));if(n<1||n>children.size())return null;current=children.get(n-1);}return current;}
    private static Map<String,Element> parseParts(byte[] docx){Map<String,Element> parts=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(docx))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(entry.isDirectory()||!(name.endsWith(".xml")||name.endsWith(".rels")))continue;if(parts.containsKey(name)){parts.put(name,null);continue;}ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;while((read=zip.read(buffer))!=-1)bytes.write(buffer,0,read);try{DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");javax.xml.parsers.DocumentBuilder builder=factory.newDocumentBuilder();builder.setErrorHandler(new DefaultHandler());parts.put(name,builder.parse(new ByteArrayInputStream(bytes.toByteArray())).getDocumentElement());}catch(Exception unknown){parts.put(name,null);}}}catch(IOException invalid){parts.put("unverifiable",null);}return parts;}
    private static BizException fail(String code){return new BizException(4012,code);}
}
