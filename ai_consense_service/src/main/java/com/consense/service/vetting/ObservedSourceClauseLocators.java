package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Exact selected native paragraph locators. Source syntax does not establish legal applicability. */
final class ObservedSourceClauseLocators {
    static final String VERSION="exact-selected-native-paragraph-locator-v1";
    private ObservedSourceClauseLocators() { }

    static void requireBinding(EvidencePointer p,JsonNode word) {
        if(!("docx-source-clause-scopes-v1".equals(word.path("version").asText())
                &&Objects.equals(p.getOriginalSourceSha256(),word.path("sourceSha256").asText())
                &&Objects.equals(p.getOriginalSourceSha256(),word.path("originalSourceSha256").asText())
                &&Objects.equals(p.getSourceRevisionSha256(),word.path("sourceRevisionSha256").asText())
                &&Objects.equals(p.getProjectionSha256(),word.path("projectionSha256").asText())
                &&Objects.equals(p.getProjectId(),word.path("source").path("projectId").asText())
                &&Objects.equals(String.valueOf(p.getDocumentId()),word.path("source").path("documentId").asText())
                &&Objects.equals(p.getJavaParsedSourceHash(),word.path("source").path("parsedSourceHash").asText())
                &&word.path("generationSha256").asText().matches("[a-f0-9]{64}")
                &&Objects.equals(p.getCanonicalChunkId(),word.path("canonicalChunkId").asText())
                &&Objects.equals(VettingCorpus.hash(JsonUtils.write(p.getParts())),word.path("selectedPartsSha256").asText())))
            throw new IllegalArgumentException("normal_word_source_scope_context_binding_differs");
    }

    /** Called with the current canonical pointer and raw context, before semantic proof fields are removed. */
    static Bound capture(EvidencePointer p,JsonNode canonical,String role,JsonNode word) {
        if(word==null||word.isNull())return null;
        requireBinding(p,word);
        if(!NormalObservedContract.PRODUCER.equals(p.getProducerKind())||!Arrays.asList("tender","standard").contains(role)
                ||canonical==null||!NormalObservedContract.isNormal(canonical)
                ||!Objects.equals(p.getCanonicalChunkId(),canonical.path("id").asText())
                ||!Objects.equals(String.valueOf(p.getDocumentId()),canonical.path("documentId").asText())
                ||!Objects.equals(role,canonical.path("role").asText())
                ||!Objects.equals(p.getParts(),canonical.path("parts"))
                ||!word.path("issues").isArray()||!word.path("issues").isEmpty()
                ||!p.getParts().isArray()||p.getParts().size()!=1)return null;
        JsonNode part=p.getParts().get(0),position=part.path("sourcePosition");String text=part.path("text").asText();
        if(!part.path("text").isTextual()||!Objects.equals(text,canonical.path("content").asText())
                ||!whole(part,text)||!"docx_native".equals(position.path("kind").asText())
                ||!position.path("nodePath").asText().matches(".*/\\{http://schemas.openxmlformats.org/wordprocessingml/2006/main\\}p\\[\\d+\\]$")
                ||position.path("nodePath").asText().contains("}tbl[")||position.path("nodePath").asText().contains("}tc[")
                ||!Objects.equals(p.getOriginalSourceSha256(),position.path("sourceSha256").asText())
                ||!position.path("partSha256").asText().matches("[a-f0-9]{64}"))return null;
        List<JsonNode> members=new ArrayList<>();
        for(JsonNode member:word.path("members"))if(position.equals(member.path("sourcePosition"))
                &&member.path("retainedBlockIds").isArray()&&member.path("retainedBlockIds").size()==1
                &&part.path("blockId").equals(member.path("retainedBlockIds").get(0))
                &&"source_bound_native_member".equals(member.path("sourceClauseClassification").asText())
                &&member.path("textObservation").path("status").asText().startsWith("observed_")
                &&Objects.equals(text,member.path("textObservation").path("text").asText()))members.add(member);
        if(members.size()!=1)return null;String memberId=members.get(0).path("id").asText();
        List<JsonNode> ranges=new ArrayList<>();
        for(JsonNode range:word.path("ranges"))for(JsonNode id:range.path("orderedMemberIds"))if(memberId.equals(id.asText())){ranges.add(range);break;}
        // shortcut: only one complete source range and one whole paragraph are supported here; nested/table carriers stay unknown.
        if(ranges.size()!=1||word.path("ranges").size()!=1)return null;JsonNode range=ranges.get(0);
        Set<String> body=new HashSet<>();for(JsonNode id:range.path("orderedMemberIds"))if(!id.isTextual()||!body.add(id.asText()))return null;
        for(JsonNode id:range.path("contextMemberIds"))if(body.contains(id.asText()))return null;
        if(!range.path("id").asText().matches("source-clause:[a-f0-9]{64}")
                ||!range.path("id").asText().equals("source-clause:"+VettingCorpus.hash(p.getOriginalSourceSha256()+"|"+range.path("startBoundary").path("memberId").asText()+"|"+range.path("reference").asText()))
                ||!range.path("reference").asText().matches("[A-Z]{2,4}\\d+(?:\\.\\d+)*(?:\\([a-zA-Z0-9]+\\))*")
                ||!truth(range,"scopeComplete",true)||!truth(range,"automaticEditEligible",false)
                ||!truth(range,"semanticApplicabilityVerified",false)||!"source_syntax_complete".equals(range.path("status").asText())
                ||!"explicit_source_clause_or_subitem_label".equals(range.path("startBoundary").path("kind").asText())
                ||!Arrays.asList("next_explicit_source_peer_or_owner_boundary","native_container_end").contains(range.path("endBoundary").path("kind").asText())
                ||range.hasNonNull("parentId"))return null;
        for(String name:Arrays.asList("startBoundary","endBoundary"))for(String key:Arrays.asList("sourceSha256","partName","partSha256"))
            if(!position.path(key).equals(range.path(name).path("sourcePosition").path(key)))return null;
        return new Bound(p,role,part,text,range.path("reference").asText());
    }

    private static boolean whole(JsonNode part,String text){return part.path("startOffset").isIntegralNumber()&&part.path("startOffset").asInt()==0&&part.path("endOffset").isIntegralNumber()&&part.path("endOffset").asInt()==text.length();}
    private static boolean truth(JsonNode value,String key,boolean expected){return value.path(key).isBoolean()&&value.path(key).asBoolean()==expected;}
    private static String identity(EvidencePointer p){return JsonUtils.write(Arrays.asList(p.getProjectId(),p.getDocumentId(),p.getOriginalSourceSha256(),p.getJavaParsedSourceHash(),p.getCanonicalSourceHash(),p.getSourceRevisionSha256(),p.getProjectionSha256(),p.getProjectionRevisionSha256(),p.getCanonicalChunkId()));}

    static final class Bound {
        private final String binding,role,blockId,text,reference;
        private final JsonNode position;
        private Bound(EvidencePointer p,String role,JsonNode part,String text,String reference){this.binding=identity(p);this.role=role;this.blockId=part.path("blockId").asText();this.text=text;this.reference=reference;this.position=part.path("sourcePosition").deepCopy();}
        private boolean matches(Chunk c){
            if(c==null||c.getCanonicalEvidence()==null||!binding.equals(identity(c.getCanonicalEvidence()))||!role.equals(c.getRole())
                    ||!Objects.equals(c.getId(),c.getCanonicalEvidence().getCanonicalChunkId())||!text.equals(c.getContent())
                    ||!Objects.equals(c.getDocumentId(),String.valueOf(c.getCanonicalEvidence().getDocumentId()))
                    ||!Objects.equals(c.getSourceHash(),c.getCanonicalEvidence().getCanonicalSourceHash())
                    ||!Objects.equals(c.getOriginalSourceSha256(),c.getCanonicalEvidence().getOriginalSourceSha256())
                    ||c.getParts()==null||c.getParts().size()!=1)return false;
            JsonNode source=c.getCanonicalEvidence().getParts();if(source==null||!source.isArray()||source.size()!=1)return false;
            JsonNode raw=source.get(0);if(!blockId.equals(raw.path("blockId").asText())||!text.equals(raw.path("text").asText())||!whole(raw,text)
                    ||raw.has("sourcePosition")&&!position.equals(raw.path("sourcePosition")))return false;
            Part p=c.getParts().get(0);return p!=null&&blockId.equals(p.getBlockId())&&text.equals(p.getText())&&p.getStartOffset()==0&&p.getEndOffset()==text.length()
                    &&(p.getSourcePosition()==null||position.equals(p.getSourcePosition()));
        }
        Set<String> references(Chunk c,String quote){return matches(c)&&quote!=null&&!VettingCorpus.normalize(quote).isEmpty()&&text.indexOf(quote)>=0&&text.indexOf(quote)==text.lastIndexOf(quote)?Collections.singleton(reference):Collections.emptySet();}
        List<Map<String,Object>> model(Chunk c){return matches(c)?Collections.singletonList(VettingReviewProbe.map("version",VERSION,"reference",reference,"anchor",c.getAnchor(),"scope","selected_whole_native_paragraph_source_syntax_only","automaticEditEligible",false,"semanticApplicabilityVerified",false,"meaning","The exact selected paragraph belongs to this observed source clause range. Legal applicability, adoption and edit authority remain unknown.")):Collections.emptyList();}
    }
}
