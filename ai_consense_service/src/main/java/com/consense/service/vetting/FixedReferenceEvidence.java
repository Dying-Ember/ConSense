package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;

/** Lossless, source-bound fixed reference. It carries no canonical/project-adoption claim. */
public final class FixedReferenceEvidence {
    private static final java.util.regex.Pattern DOCUMENT_TITLE=java.util.regex.Pattern.compile("(?i)General\\s+Conditions\\s+of\\s+(?:Contract|Tender)\\s+for\\s+Building\\s+Works\\s+\\d{4}\\s+Edition\\s*\\(\\s*version\\s+[0-9.]+\\s*\\)");
    // shortcut: only a complete direct adoption statement is recognized; upgrade after source-bound semantic review.
    private static final java.util.regex.Pattern DIRECT_ADOPTION=java.util.regex.Pattern.compile("(?i)\\s*(?:the|this)\\s+contract\\s+(?:shall\\s+)?(?:comprises?|consists?\\s+of|includes?|incorporates?)\\s+(?:the\\s+)?("+DOCUMENT_TITLE.pattern()+")\\s*\\.?\\s*");
    private static final java.util.regex.Pattern TITLE_ADOPTION=java.util.regex.Pattern.compile("(?i)\\s*("+DOCUMENT_TITLE.pattern()+")\\s+(?:is|are|shall\\s+be)\\s+(?:(?:a\\s+)?part\\s+of|incorporated\\s+(?:in|into))\\s+(?:the|this)\\s+contract\\s*\\.?\\s*");
    private final String id;
    private final double score;
    private final JsonNode payload, tableArtifact, sourceUnit;
    FixedReferenceEvidence(String id, double score, JsonNode payload, JsonNode tableArtifact, JsonNode sourceUnit) {
        this.id = id; this.score = score; this.payload = payload.deepCopy();
        this.tableArtifact = tableArtifact == null ? null : tableArtifact.deepCopy();
        this.sourceUnit = sourceUnit == null ? null : sourceUnit.deepCopy();
    }
    public String getId() { return id; }
    public double getScore() { return score; }
    public String getContent() { return payload.path("content").asText(); }
    public String getFileLabel() { return payload.path("fileLabel").asText(payload.path("fileKey").asText()); }
    public int getPageNo() { return payload.path("pageNo").asInt(); }
    public String getSourceFamily() { return payload.path("sourceQuality").path("family").asText(); }
    public String getDocumentTitle() { return payload.path("fixedReferenceDocumentTitle").asText(); }
    public static String observedDocumentTitle(String content) {
        return observedDocumentTitle(content,null);
    }
    public static String observedDocumentTitle(String content,String family) {
        java.util.regex.Matcher title=DOCUMENT_TITLE.matcher(content==null?"":content);String observed="";
        while(title.find()){String candidate=title.group().replaceAll("\\s+"," ").trim();if(family!=null&&!family.equals(titleFamily(candidate)))continue;if(!observed.isEmpty()&&!identityWords(observed).equals(identityWords(candidate)))return "";observed=candidate;}
        return observed;
    }
    public static boolean adoptionMatches(String file,String content,String title) {
        String name=(file==null?"":file).toLowerCase(java.util.Locale.ROOT).replace('_',' ').replace('-',' ');
        if(!(name.matches(".*\\bcoc\\b.*")||name.contains("conditions of contract")&&!name.contains("special")))return false;
        String text=content==null?"":content;
        String expected=observedDocumentTitle(title);if(expected.isEmpty())return false;
        String observed=observedDocumentTitle(text,titleFamily(expected));if(observed.isEmpty()||!identityWords(observed).equals(identityWords(expected)))return false;
        for(java.util.regex.Pattern pattern:Arrays.asList(DIRECT_ADOPTION,TITLE_ADOPTION)){
            java.util.regex.Matcher match=pattern.matcher(text);if(match.matches()&&identityWords(match.group(1)).equals(identityWords(expected)))return true;
        }
        return false;
    }
    private static String titleFamily(String title){return title.toLowerCase(java.util.Locale.ROOT).contains("of tender")?"GCT":"GCC";}
    private static String identityWords(String value){return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();}
    /** Current-question clause labels only; document version numbers are not requested provisions. */
    public static Set<String> requestedClauseLabels(String question){return new LinkedHashSet<>(requestedClauses(question).keySet());}
    public static boolean matchesRequestedClause(VectorStore.SearchHit hit,String question){return matchesRequestedClause(hit,requestedClauses(question));}
    private static boolean matchesRequestedClause(VectorStore.SearchHit hit,Map<String,Set<String>> requested){
        Map<String,Object> payload=hit.getPayload();Object observed=payload.get("clauseId");if(observed==null||String.valueOf(observed).isBlank())observed=payload.get("observedLabelRaw");
        Set<String> families=requested.get(String.valueOf(observed));String family=String.valueOf(payload.getOrDefault("fileKey",""));
        return "clause".equals(payload.get("observedNodeKind"))&&"body".equals(payload.get("observedSourceRole"))&&families!=null&&(families.contains("")||families.contains(family.toUpperCase(Locale.ROOT)));
    }
    private static Map<String,Set<String>> requestedClauses(String question){
        String request=question==null?"":question;Map<String,Set<String>> requested=new LinkedHashMap<>();
        java.util.regex.Matcher reference=java.util.regex.Pattern.compile("(?i)(?<![A-Z0-9.])(?:(GCC|GCT|SL)\\s*(?:clause\\s*)?)?(\\d{1,2}(?:\\.\\d{1,4})+)(?![A-Z0-9]|\\.[A-Z0-9])").matcher(request);
        while(reference.find()&&requested.size()<4){
            if(reference.group(1)==null&&request.substring(0,reference.start()).matches("(?is).*\\b(?:version|revision|ver|v)\\s*"))continue;
            requested.computeIfAbsent(reference.group(2),ignored->new LinkedHashSet<>()).add(reference.group(1)==null?"":reference.group(1).toUpperCase(Locale.ROOT));
        }return requested;
    }
    /** Preserve whole observed requested clauses/groups; defer unrelated index and clause parents. */
    public static List<VectorStore.SearchHit> focusRequestedClauses(List<VectorStore.SearchHit> hits,String question){
        String request=question==null?"":question;
        if(request.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:contents|index|table of contents)\\b.*"))return hits;
        Map<String,Set<String>> requested=requestedClauses(request);List<VectorStore.SearchHit> operative=new ArrayList<>(),matching=new ArrayList<>();
        for(VectorStore.SearchHit hit:hits){Map<String,Object> payload=hit.getPayload();
            if("clause".equals(payload.get("observedNodeKind"))&&"body".equals(payload.get("observedSourceRole"))){operative.add(hit);if(matchesRequestedClause(hit,requested))matching.add(hit);}}
        // Older carriers without observed clause identity keep their existing evidence contract.
        if(operative.isEmpty())return hits;
        List<VectorStore.SearchHit> primary=matching.isEmpty()?operative.subList(0,Math.min(3,operative.size())):matching;
        Set<String> selected=new LinkedHashSet<>();for(VectorStore.SearchHit hit:primary)selected.add(hit.getId());
        for(VectorStore.SearchHit hit:primary){Object raw=hit.getPayload().get("fixedReferenceSourceUnit");JsonNode group=raw instanceof JsonNode?(JsonNode)raw:JsonUtils.mapper().valueToTree(raw);for(JsonNode id:group.path("requiredMemberIds"))selected.add(id.asText());}
        List<VectorStore.SearchHit> result=new ArrayList<>();for(VectorStore.SearchHit hit:hits)if(selected.contains(hit.getId()))result.add(hit);
        if(result.size()!=selected.size())throw new IllegalStateException("fixed_reference_required_group_member_missing");return result;
    }
    public JsonNode getPayload() { return payload.deepCopy(); }
    public JsonNode getTableArtifact() { return tableArtifact == null ? null : tableArtifact.deepCopy(); }
    public JsonNode getSourceUnit() { return sourceUnit == null ? null : sourceUnit.deepCopy(); }
    public JsonNode pointer() {
        com.fasterxml.jackson.databind.node.ObjectNode pointer=JsonUtils.mapper().createObjectNode();
        pointer.put("evidenceBackend","fixed_development_baseline");pointer.put("parentId",id);
        pointer.put("manifestSha256",payload.path("fixedBaselineManifestSha256").asText());pointer.put("baselineVersion",payload.path("fixedBaselineVersion").asText());
        pointer.put("sourceFamily",getSourceFamily());pointer.put("sourceRole","standard");pointer.put("originalSourceSha256",payload.path("originalSourceSha256").asText(payload.path("sourceHash").asText()));
        if(payload.has("fixedBaselineEditionId"))pointer.put("editionId",payload.path("fixedBaselineEditionId").asText());
        if(payload.has("originalPageNos"))pointer.set("originalPageNos",payload.get("originalPageNos").deepCopy());pointer.put("pageNo",getPageNo());
        pointer.put("fullObservedParentPreserved",true);pointer.put("sourceQualityAccepted",false);pointer.put("projectApplicability","unconfirmed");pointer.put("qualifiersComplete","unknown");pointer.put("legalOwnershipAccepted",false);
        if(sourceUnit!=null){com.fasterxml.jackson.databind.node.ObjectNode group=pointer.putObject("sourceUnit");for(String key:java.util.Arrays.asList("unitId","kind","status","sourceIdentity","requiredMemberIds","presentMemberIds","missingMemberIds","originIds","required","present","missing","unknown","qualifiersComplete","semanticScopeVerified"))if(sourceUnit.has(key))group.set(key,sourceUnit.get(key).deepCopy());}
        pointer.put("evidenceUrl","/api/oer/fixed-baseline/evidence?id="+query(id));pointer.put("originalUrl","/api/oer/fixed-baseline/original?sourceSha256="+query(pointer.path("originalSourceSha256").asText()));
        if(tableArtifact!=null)pointer.put("tableUrl","/api/oer/fixed-baseline/table?id="+query(id));return pointer;
    }
    private static String query(String value){try{return java.net.URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
    public VectorStore.SearchHit asHit() {
        Map<String,Object> map = JsonUtils.mapper().convertValue(payload, new TypeReference<Map<String,Object>>() {});
        return new VectorStore.SearchHit(id, score, map);
    }
}
