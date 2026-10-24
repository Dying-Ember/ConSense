package com.consense.service.drafting;

import java.io.*;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;

/** Delivery notice is added to a fresh generated copy, never to the source template. */
final class DraftIncompleteNotice {
    static final String TITLE="INCOMPLETE DRAFT";
    private DraftIncompleteNotice() { }

    static List<String> outstanding(Map<String,Object> graph,Map<String,Object> plan) {
        return outstanding(graph,plan,null);
    }
    static List<String> outstanding(Map<String,Object> graph,Map<String,Object> plan,String fileKey) {
        Map<String,String> labels=new LinkedHashMap<>();labels.put("contractTitle","Project name and contract number");
        Map<String,String> documents=new HashMap<>();
        for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);documents.put(text(source.get("docId")),text(source.get("fileName")));}
        for(Map<String,Object> node:nodes(graph)){
            String name=text(node.get("nameEn"));if(name.isEmpty())name=text(node.get("nameZh"));if(name.isEmpty())name="Question requiring review";
            if("source_seed".equals(text(node.get("definitionKind")))){
                Set<String> descriptions=new LinkedHashSet<>();
                for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);String file=documents.getOrDefault(text(anchor.get("docId")),"");String quote=text(anchor.get("text")).replaceAll("\\s+"," ").trim();
                    if(quote.length()>240)quote=quote.substring(0,quote.offsetByCodePoints(0,Math.min(240,quote.codePointCount(0,quote.length()))))+"…";
                    if(!file.isEmpty()||!quote.isEmpty())descriptions.add(file+(quote.isEmpty()?"":" — “"+quote+"”"));
                }
                name="Review source guidance"+(descriptions.isEmpty()?"":" — "+String.join("; ",descriptions));
            }
            labels.put(text(node.get("id")),name);
            for(Object raw:list(node.get("editTargets"))){Map<String,Object> target=map(raw);String file=documents.getOrDefault(text(target.get("docId")),"");String quote=text(target.get("text")).replaceAll("\\s+"," ").trim();
                labels.put(text(target.get("id")),name+(file.isEmpty()?"":" — "+file)+(quote.isEmpty()?"":" — “"+quote+"”"));
            }
        }
        Map<String,String> headings=new LinkedHashMap<>();
        Map<String,Set<String>> reasons=new LinkedHashMap<>();
        int unidentified=0;Map<String,Integer> otherDocuments=new LinkedHashMap<>();
        for(Object raw:list(plan.get("unresolved"))){Map<String,Object> issue=map(raw);Set<String> subjects=new LinkedHashSet<>();
            String document=text(issue.get("document"));
            if(fileKey!=null&&DraftBlueprint.draftFileKeys().contains(document)&&!fileKey.equals(document)){
                otherDocuments.merge(document,1,Integer::sum);continue;
            }
            Set<String> identities=new TreeSet<>();
            for(Object key:list(issue.get("inputKeys"))){String id=text(key);identities.add(id);if(labels.containsKey(id))subjects.add(labels.get(id));}
            String kind=text(issue.get("kind"));int colon=kind.indexOf(':');if(subjects.isEmpty()&&colon>=0&&labels.containsKey(kind.substring(colon+1))){String id=kind.substring(colon+1);identities.add(id);subjects.add(labels.get(id));}
            // Group only the same identified subjects; equal display names do not prove equivalence.
            String group=!subjects.isEmpty()&&!identities.isEmpty()?com.consense.common.JsonUtils.write(identities):"unidentified:"+(++unidentified);
            headings.putIfAbsent(group,subjects.isEmpty()?(DraftBlueprint.draftFileKeys().contains(document)?document+" document":"Project draft"):String.join("; ",subjects));
            reasons.computeIfAbsent(group,key->new LinkedHashSet<>()).add(reason(colon<0?kind:kind.substring(0,colon)));
        }
        List<String> result=new ArrayList<>();
        reasons.forEach((key,value)->result.add(headings.get(key)+": "+String.join("\n",value)));
        otherDocuments.forEach((key,count)->result.add(count+" outstanding records remain in "+key+". See that document's list; the package remains incomplete."));
        return result;
    }
    private static String reason(String kind){
        switch(kind){
            case "SourceFormat":return "Review the source layout, headers, footers and project identity; existing content is retained pending review.";
            case "NodeReviewRequired":return "Review the guidance and modification scope.";
            case "InputMissing":case "AnswerUnknown":case "DerivedUnknown":return "Review the sources and adopt the required answer.";
            case "InputInvalid":return "Correct the answer format and review it again.";
            case "OverlappingEdits":return "Overlapping treatments retain their source wording; choose one consistent treatment.";
            case "ConditionUnknown":case "BranchConflict":return "Review the unresolved or conflicting applicability conditions.";
            case "SourceBindingRequired":case "ReplacementPlacementRequired":case "ItemPlacementRequired":case "UnknownTargetSource":return "Open the original and identify the exact modification range and placement.";
            case "GraphCycle":case "SchemaNeedsReview":return "Review the rule definitions and dependencies before applying changes.";
            case "source_review":case "graph_source_changed":case "input_review":return "Recheck the current source and adopted answer.";
            case "ManualCheck":return "Check the source and confirm the intended treatment.";
            default:return "This treatment requires review and retains its source wording; review it in the adoption and modification plan.";
        }
    }

    static byte[] apply(byte[] generated,List<String> outstanding) {
        return applyWithReceipt(generated,outstanding).bytes;
    }
    static final class Delivery {
        final byte[] bytes;final Map<String,String> annotations;
        Delivery(byte[] bytes,Map<String,String> annotations){this.bytes=bytes;this.annotations=annotations;}
    }
    static Delivery applyWithReceipt(byte[] generated,List<String> outstanding) {
        if(outstanding.isEmpty())return new Delivery(generated,Collections.emptyMap());
        try(XWPFDocument document=new XWPFDocument(new ByteArrayInputStream(generated));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Map<String,String> annotations=new LinkedHashMap<>();
            java.math.BigInteger bookmarkId=java.math.BigInteger.ZERO;
            org.w3c.dom.NodeList starts=((org.w3c.dom.Element)document.getDocument().getBody().getDomNode()).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","bookmarkStart");
            for(int i=0;i<starts.getLength();i++)bookmarkId=bookmarkId.max(new java.math.BigInteger(((org.w3c.dom.Element)starts.item(i)).getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","id")));
            XWPFParagraph cover;
            try(XmlCursor cursor=document.getDocument().getBody().newCursor()) {
                cursor.toFirstChild();cover=document.insertNewParagraph(cursor);
            }
            if(cover==null)throw new IllegalStateException("Cannot insert incomplete-draft notice");
            cover.setSpacingAfter(240);XWPFRun title=noticeRun(cover);title.setText(TITLE);title.setBold(true);title.setColor("B91C1C");title.setFontSize(20);title.addBreak();
            XWPFRun explanation=noticeRun(cover);explanation.setFontSize(12);
            explanation.setText("Only reviewed and adopted changes are applied. Unresolved passages retain their source wording. See the outstanding-items list at the end. This document is not complete.");explanation.addBreak(BreakType.PAGE);
            annotation(cover,bookmarkId=bookmarkId.add(java.math.BigInteger.ONE),"before",annotations);
            XWPFParagraph heading=document.createParagraph();heading.setPageBreak(true);XWPFRun headingRun=noticeRun(heading);headingRun.setText("Outstanding items");headingRun.setBold(true);headingRun.setFontSize(16);
            annotation(heading,bookmarkId=bookmarkId.add(java.math.BigInteger.ONE),"after",annotations);
            int number=0;
            for(String item:outstanding) {
                if(item==null||item.trim().isEmpty())throw new IllegalArgumentException("Outstanding item needs a readable explanation");
                XWPFParagraph paragraph=document.createParagraph();paragraph.setSpacingAfter(120);XWPFRun run=noticeRun(paragraph);run.setFontSize(11);run.setText((++number)+". "+item);
                annotation(paragraph,bookmarkId=bookmarkId.add(java.math.BigInteger.ONE),"after",annotations);
            }
            document.write(out);return new Delivery(out.toByteArray(),annotations);
        } catch(IOException error) {throw new IllegalStateException("Cannot write incomplete-draft notice",error);}
    }
    private static void annotation(XWPFParagraph paragraph,java.math.BigInteger id,String position,Map<String,String> receipt){
        String name="CS"+UUID.randomUUID().toString().replace("-","");
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark start=paragraph.getCTP().addNewBookmarkStart();start.setId(id);start.setName(name);
        org.w3c.dom.Node root=paragraph.getCTP().getDomNode(),first=root.getFirstChild();
        if(first!=null&&"pPr".equals(first.getLocalName()))first=first.getNextSibling();
        root.insertBefore(start.getDomNode(),first);
        paragraph.getCTP().addNewBookmarkEnd().setId(id);receipt.put(name,position);
    }
    static void bindDelivery(List<Map<String,Object>> bindings,com.consense.document.DocxTemplateEditor.TemplateIndex index,Delivery delivery){
        Map<String,List<com.consense.document.DocxTemplateEditor.Paragraph>> markers=DraftDocumentBindings.markers(index);
        for(Map.Entry<String,String> entry:delivery.annotations.entrySet()){
            List<com.consense.document.DocxTemplateEditor.Paragraph> matches=markers.getOrDefault(entry.getKey(),Collections.emptyList());
            if(matches.size()!=1)throw new IllegalStateException("Delivery notice bookmark is missing or ambiguous");
            com.consense.document.DocxTemplateEditor.Paragraph paragraph=matches.get(0);
            bindings.add(DraftBusinessRules.map("bindingId",entry.getKey(),"bookmarkName",entry.getKey(),"sourceParagraphId",null,"sourceText",null,
                "resultParagraphId",paragraph.getId(),"resultParagraphOrdinal",paragraph.getOrdinal(),"text",paragraph.getText(),"textHash",DraftAdoption.hash(paragraph.getText()),
                "fieldKeys",Collections.emptyList(),"actionIds",Collections.emptyList(),"operationIds",Collections.emptyList(),"applicationStatus","delivery_notice",
                "deliveryPosition",entry.getValue(),"documentEditStatus","applied","locationStatus","exact","geometry",null));
        }
    }
    private static XWPFRun noticeRun(XWPFParagraph paragraph) {
        XWPFRun run=paragraph.createRun();run.setFontFamily("Arial");run.setFontFamily("Microsoft YaHei",XWPFRun.FontCharRange.eastAsia);return run;
    }
}
