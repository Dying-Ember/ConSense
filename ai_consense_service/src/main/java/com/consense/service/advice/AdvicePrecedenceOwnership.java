package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Source-syntax containment over already admitted pointers, never legal applicability or formal Clause ownership. */
final class AdvicePrecedenceOwnership {
    static final class Condition {
        final String alias,reference;
        Condition(String alias,String reference){this.alias=alias;this.reference=reference;}
    }
    static final class Ownership {
        boolean bound,unresolvedConditionAlias;
        final List<Condition> conditions=new ArrayList<>();
    }
    private AdvicePrecedenceOwnership(){}
    static boolean hasContext(CitationVO source){
        if(source.getCanonicalEvidence()!=null)for(EvidencePointer p:source.getCanonicalEvidence())
            if(p!=null&&p.getNativeWordSourceScopesContext()!=null&&!p.getNativeWordSourceScopesContext().isNull())return true;
        return false;
    }
    static Ownership resolve(List<CitationVO> selected,List<CitationVO> all,Pattern leadingCondition){
        Ownership result=new Ownership();Set<String> seen=new HashSet<>();
        for(CitationVO source:selected)if(source.getCanonicalEvidence()!=null)for(EvidencePointer p:source.getCanonicalEvidence()){
            JsonNode context=p==null?null:p.getNativeWordSourceScopesContext();
            if(!boundContext(source,p,context))continue;
            Map<String,JsonNode> members=new LinkedHashMap<>();boolean unique=true;
            for(JsonNode member:context.path("members"))if(!member.path("id").isTextual()||members.put(member.path("id").asText(),member)!=null)unique=false;
            if(!unique)continue;
            for(JsonNode range:context.path("ranges")){
                if(!"source_syntax_complete".equals(range.path("status").asText())||!range.path("scopeComplete").asBoolean()
                        ||!"source_interval_containment_only".equals(range.path("hierarchyStatus").asText())||!range.path("parentId").isNull())continue;
                List<JsonNode> ordered=new ArrayList<>();Set<String> ids=new HashSet<>();boolean valid=true;
                for(JsonNode id:range.path("orderedMemberIds")){JsonNode member=members.get(id.asText());if(member==null||!ids.add(id.asText())){valid=false;break;}ordered.add(member);}
                if(!valid||ordered.isEmpty()||!range.path("startBoundary").path("memberId").asText().equals(ordered.get(0).path("id").asText()))continue;
                JsonNode position=ordered.get(0).path("sourcePosition");
                if(!Objects.equals(p.getOriginalSourceSha256(),position.path("sourceSha256").asText()))continue;
                if(!samePart(position,range.path("endBoundary").path("sourcePosition")))continue;
                for(JsonNode member:ordered)if(!samePart(position,member.path("sourcePosition")))valid=false;
                if(!valid)continue;
                int selectedAt=-1;
                for(int i=0;i<ordered.size();i++)if(matches(p,ordered.get(i))){if(selectedAt>=0){selectedAt=-2;break;}selectedAt=i;}
                if(selectedAt<0)continue;
                result.bound=true;
                // The selected paragraph can itself contain the leading condition and its operative effect.
                for(int i=0;i<=selectedAt;i++){
                    JsonNode member=ordered.get(i);String literal=member.path("textObservation").path("text").asText();
                    if(!leadingCondition.matcher(literal).find())continue;
                    List<CitationVO> aliases=new ArrayList<>();
                    for(CitationVO candidate:all)if(Objects.equals(source.getDocumentId(),candidate.getDocumentId())
                            &&Objects.equals(source.getSourceHash(),candidate.getSourceHash())&&Objects.equals(source.getSourceRole(),candidate.getSourceRole())
                            &&candidate.getCanonicalEvidence()!=null)for(EvidencePointer cp:candidate.getCanonicalEvidence())
                        if(boundPointer(candidate,cp)&&sameSource(p,cp)&&matches(cp,member)&&candidate.getContent()!=null&&candidate.getContent().trim().equals(literal.trim())){aliases.add(candidate);break;}
                    // An ambiguous or absent public alias cannot be invented from a paragraph ordinal.
                    if(aliases.size()==1){String alias=aliases.get(0).getEvidenceId(),reference=range.path("reference").asText();
                        if(seen.add(alias+"|"+reference))result.conditions.add(new Condition(alias,reference));}
                    else result.unresolvedConditionAlias=true;
                }
            }
        }
        return result;
    }
    private static boolean boundContext(CitationVO source,EvidencePointer p,JsonNode c){
        return boundPointer(source,p)&&c!=null&&"docx-source-clause-scopes-v1".equals(c.path("version").asText())
                &&Objects.equals(p.getOriginalSourceSha256(),c.path("sourceSha256").asText())
                &&Objects.equals(p.getOriginalSourceSha256(),c.path("originalSourceSha256").asText())
                &&Objects.equals(p.getSourceRevisionSha256(),c.path("sourceRevisionSha256").asText())
                &&Objects.equals(p.getProjectionSha256(),c.path("projectionSha256").asText())
                &&Objects.equals(p.getProjectId(),c.path("source").path("projectId").asText())
                &&Objects.equals(String.valueOf(p.getDocumentId()),c.path("source").path("documentId").asText())
                &&Objects.equals(source.getSourceRole(),c.path("source").path("role").asText())
                &&Objects.equals(p.getJavaParsedSourceHash(),c.path("source").path("parsedSourceHash").asText())
                &&Objects.equals(p.getCanonicalChunkId(),c.path("canonicalChunkId").asText())
                &&Objects.equals(com.consense.service.vetting.VettingCorpus.hash(JsonUtils.write(p.getParts())),c.path("selectedPartsSha256").asText())
                &&c.path("generationSha256").asText().matches("[a-f0-9]{64}")&&c.path("issues").isArray()&&c.path("issues").isEmpty()
                &&c.path("members").isArray()&&c.path("ranges").isArray();
    }
    private static boolean boundPointer(CitationVO source,EvidencePointer p){
        if(p==null||p.getParts()==null||!p.getParts().isArray()||p.getParts().isEmpty()||p.getOriginalSourceSha256()==null
                ||!p.getOriginalSourceSha256().matches("[a-f0-9]{64}")||!Objects.equals(source.getDocumentId(),String.valueOf(p.getDocumentId()))
                ||!Objects.equals(source.getSourceHash(),p.getJavaParsedSourceHash()))return false;
        JsonNode unit=source.getCanonicalSourceUnit();if(unit==null||!"complete_observed_unit".equals(unit.path("status").asText()))return false;
        int matches=0;
        for(JsonNode member:unit.path("members"))if(Objects.equals(p.getCanonicalChunkId(),member.path("id").asText())
                &&Objects.equals(p.getUnitId(),member.path("unitId").asText())&&Objects.equals(source.getDocumentId(),member.path("documentId").asText())
                &&Objects.equals(source.getSourceRole(),member.path("role").asText())&&Objects.equals(p.getCanonicalSourceHash(),member.path("sourceHash").asText())
                &&Objects.equals(p.getSourceRevisionSha256(),member.path("sourceRevisionSha256").asText())&&p.getParts().equals(member.path("parts")))matches++;
        return matches==1;
    }
    private static boolean sameSource(EvidencePointer a,EvidencePointer b){
        return Objects.equals(a.getProjectId(),b.getProjectId())&&Objects.equals(a.getDocumentId(),b.getDocumentId())
                &&Objects.equals(a.getOriginalSourceSha256(),b.getOriginalSourceSha256())&&Objects.equals(a.getJavaParsedSourceHash(),b.getJavaParsedSourceHash())
                &&Objects.equals(a.getCanonicalSourceHash(),b.getCanonicalSourceHash())&&Objects.equals(a.getSourceRevisionSha256(),b.getSourceRevisionSha256())
                &&Objects.equals(a.getProjectionSha256(),b.getProjectionSha256())&&Objects.equals(a.getProjectionRevisionSha256(),b.getProjectionRevisionSha256());
    }
    private static boolean matches(EvidencePointer pointer,JsonNode member){
        int count=0;
        for(JsonNode part:pointer.getParts())for(JsonNode id:member.path("retainedBlockIds"))
            if(id.asText().equals(part.path("blockId").asText())&&member.path("sourcePosition").equals(part.path("sourcePosition"))
                    &&member.path("textObservation").path("text").asText().equals(part.path("text").asText())&&part.path("startOffset").asInt(-1)==0
                    &&part.path("endOffset").asInt(-1)==part.path("text").asText().length())count++;
        return count==1;
    }
    private static boolean samePart(JsonNode a,JsonNode b){
        return a.path("sourceSha256").asText().matches("[a-f0-9]{64}")&&a.path("partSha256").asText().matches("[a-f0-9]{64}")
                &&!a.path("partName").asText().isEmpty()&&a.path("sourceSha256").equals(b.path("sourceSha256"))
                &&a.path("partSha256").equals(b.path("partSha256"))&&a.path("partName").equals(b.path("partName"));
    }
}
