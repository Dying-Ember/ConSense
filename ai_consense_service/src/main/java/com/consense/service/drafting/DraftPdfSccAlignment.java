package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Human-confirmed PDF reference identities remain bound to the reviewed node and source. */
public final class DraftPdfSccAlignment {
    private DraftPdfSccAlignment() { }
    static boolean current(Map<String,Object> graph,Map<String,Object> node,DraftPdfSccReference reference){Map<String,Object> alignment=map(node.get("pdfReferenceAlignment"));return Boolean.TRUE.equals(alignment.get("confirmed"))&&problem(graph,node,alignment,reference)==null&&Objects.equals(alignment.get("basis"),basis(node,alignment));}
    public static void reconcile(Map<String,Object> graph,Map<String,Object> priorGraph,DraftPdfSccReference reference) {
        Map<String,Map<String,Object>> previous=new HashMap<>();for(Map<String,Object> node:nodes(priorGraph))previous.put(text(node.get("id")),map(node.get("pdfReferenceAlignment")));
        for(Map<String,Object> node:nodes(graph)){
            Map<String,Object> alignment=map(node.get("pdfReferenceAlignment"));if(!Boolean.TRUE.equals(alignment.get("confirmed")))continue;
            String id=text(node.get("id")),request=text(alignment.get("requestId"));Map<String,Object> old=previous.getOrDefault(id,Collections.emptyMap());
            boolean sameRequest=!request.isEmpty()&&request.equals(text(old.get("requestId")));String invalid=problem(graph,node,alignment,reference);String basis=basis(node,alignment);
            if(sameRequest){if(!Boolean.TRUE.equals(old.get("confirmed"))||!Objects.equals(old.get("basis"),basis)||!sameIdentity(old,alignment)||invalid!=null){stale(node,alignment,invalid==null?"definition_or_confirmation_changed":invalid);continue;}alignment.put("basis",old.get("basis"));continue;}
            if(invalid!=null)throw new BizException(4007,"PDF_REFERENCE_CONFIRMATION_INVALID: "+invalid);
            try{UUID.fromString(request);}catch(RuntimeException malformed){throw new BizException(4007,"PDF_REFERENCE_CONFIRMATION_REQUEST_INVALID");}
            alignment.put("basis",basis);alignment.put("status","confirmed");alignment.remove("staleReason");
        }
    }
    private static String problem(Map<String,Object> graph,Map<String,Object> node,Map<String,Object> alignment,DraftPdfSccReference reference) {
        if(!"confirmed".equals(alignment.get("status"))||text(alignment.get("reason")).trim().isEmpty()||text(alignment.get("reason")).length()>1000)return "reason_or_status";
        String sourceId=text(alignment.get("sourceId")),sha=text(alignment.get("sourceSha256")),ref=text(alignment.get("inputRef")),quote=text(alignment.get("sourceQuote"));
        if(!DraftPdfSccReference.SOURCE_SHA.equals(sha)||DraftPdfSccReference.denial(obj("sourceStale",false,"graph",graph),sourceId)!=null)return "source_not_selected_or_changed";
        List<?> documents=list(node.get("docIds"));if(documents.size()!=1||!sourceId.equals(text(documents.get(0))))return "node_source_mismatch";
        if(!Arrays.asList("input","decision","content").contains(text(map(node.get("role")).get("kind"))))return "not_business_input";
        String sourceText=text(node.get("semanticSourceQuote"));boolean manual="added".equals(node.get("definitionKind"))&&Boolean.TRUE.equals(node.get("manual"));if(quote.trim().isEmpty()||!sourceText.contains(quote)&&!(manual&&sourceText.isEmpty()))return "quote_not_in_node";
        boolean anchored=false;for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);if(sourceId.equals(text(anchor.get("docId")))&&sha.equals(text(anchor.get("sourceSha256")))&&"bound".equals(map(anchor.get("sourceObservationBinding")).get("status"))&&text(anchor.get("text")).contains(quote))anchored=true;}if(!anchored)return "quote_not_source_bound";
        String type=reference==null?null:reference.effectiveFactType(ref);if(type==null)return "reference_unavailable_or_not_business_fact";
        String kind=text(map(node.get("type")).get("kind"));Map<String,String> kinds=objKinds();if(!kind.equals(kinds.get(type)))return "type_mismatch";
        return null;
    }
    private static Map<String,String> objKinds(){Map<String,String> result=new HashMap<>();result.put("V1","boolean");result.put("V2","number");result.put("V3","text");result.put("V4","date");result.put("V5","choice");result.put("V6","multiselect");result.put("V7","record");result.put("V8","list");return result;}
    private static String basis(Map<String,Object> node,Map<String,Object> alignment){Map<String,Object> meaning=new LinkedHashMap<>();for(String key:Arrays.asList("id","parentId","type","role","nameZh","nameEn","questionZh","questionEn","options","optional","definitionScope","semanticSourceQuote","docIds"))meaning.put(key,node.get(key));
        List<Object> anchors=new ArrayList<>();for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);if(!Objects.equals(anchor.get("docId"),alignment.get("sourceId")))continue;Map<String,Object> bound=map(anchor.get("sourceObservationBinding"));anchors.add(obj("docId",anchor.get("docId"),"sourceSha256",anchor.get("sourceSha256"),"text",anchor.get("text"),"analysisRevision",bound.get("analysisRevision"),"analysisInputSha256",bound.get("analysisInputSha256"),"analysisSha256",bound.get("analysisSha256"),"observedRanges",bound.get("observedRanges")));}meaning.put("anchors",anchors);
        for(String key:Arrays.asList("sourceId","sourceSha256","inputRef","sourceQuote","reason","requestId"))meaning.put(key,alignment.get(key));return DraftAdoption.hash(JsonUtils.write(meaning));}
    private static boolean sameIdentity(Map<String,Object> old,Map<String,Object> current){for(String key:Arrays.asList("sourceId","sourceSha256","inputRef","sourceQuote","reason","requestId"))if(!Objects.equals(old.get(key),current.get(key)))return false;return true;}
    private static void stale(Map<String,Object> node,Map<String,Object> alignment,String reason){Map<String,Object> changed=new LinkedHashMap<>(alignment);changed.put("confirmed",false);changed.put("status","stale");changed.put("staleReason",reason);node.put("pdfReferenceAlignment",changed);}
}
