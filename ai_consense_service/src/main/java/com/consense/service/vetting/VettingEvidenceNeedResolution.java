package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Effective decisions only. Original packet assessments and failed supplements remain unchanged. */
final class VettingEvidenceNeedResolution {
    private VettingEvidenceNeedResolution() { }
    static Result resolve(List<SemanticPacketVO> packets) {
        ArrayNode projected=JsonUtils.mapper().createArrayNode();
        for(SemanticPacketVO packet:packets)projected.add(project(packet));
        return resolve(projected,null);
    }
    static Result resolve(JsonNode packets,String policy) {
        Result result=new Result();Set<String> duplicates=new HashSet<>();
        for(JsonNode packet:packets) {
            String id=packet.path("packetId").asText();
            if(id.isBlank()||result.packets.putIfAbsent(id,packet)!=null)duplicates.add(id);
        }
        for(String id:duplicates)result.packets.remove(id);
        for(JsonNode parent:result.packets.values()) {
            Set<String> seen=new HashSet<>();
            for(JsonNode need:parent.path("evidenceNeeds")) {
                String id=need.path("needId").asText(),key=key(parent.path("packetId").asText(),id);
                if(!seen.add(id)){result.resolved.remove(key);continue;}
                result.needs++;
                if(resolved(parent,need,result.packets.values(),policy))result.resolved.add(key);
            }
        }
        return result;
    }
    static final class Result {
        private final Map<String,JsonNode> packets=new LinkedHashMap<>();
        private final Set<String> resolved=new HashSet<>();private int needs;
        int unresolvedNeeds(){return needs-resolved.size();}
        boolean resolvedNeed(String packetId,String needId){return resolved.contains(key(packetId,needId));}
        boolean supersedesVerified(String packetId,int index) {
            JsonNode packet=packets.get(packetId);if(packet==null)return false;boolean found=false;
            for(JsonNode need:packet.path("evidenceNeeds"))if(indexes(need.path("verifiedAssessmentIndexes")).contains(index)) {
                found=true;if(!resolvedNeed(packetId,need.path("needId").asText()))return false;
            }
            for(JsonNode row:packet.path("checklistAssessments"))if("insufficient_evidence".equals(row.path("status").asText())
                    &&row.path("verifiedAssessmentIndex").isIntegralNumber()&&row.path("verifiedAssessmentIndex").intValue()==index
                    &&!supersedesChecklist(packetId,row))return false;
            return found;
        }
        boolean supersedesChecklist(String packetId,String itemId,String reason,Collection<String> evidenceIds) {
            JsonNode packet=packets.get(packetId);if(packet==null)return false;boolean found=false;
            for(JsonNode row:packet.path("checklistAssessments"))if(itemId.equals(row.path("itemId").asText())
                    &&"insufficient_evidence".equals(row.path("status").asText())&&normalized(reason).equals(normalized(row.path("reason").asText()))
                    &&strings(row.path("evidenceChunkIds")).equals(new LinkedHashSet<>(evidenceIds))) {
                found=true;if(!supersedesChecklist(packetId,row))return false;
            }
            return found;
        }
        private boolean supersedesChecklist(String packetId,JsonNode row) {
            JsonNode packet=packets.get(packetId);if(packet==null)return false;boolean found=false;
            for(JsonNode need:packet.path("evidenceNeeds"))if(strings(need.path("checklistItemIds")).contains(row.path("itemId").asText())
                    &&namesOriginalRow(packet,need,row)
                    &&!Collections.disjoint(strings(need.path("affectedChunkIds")),strings(row.path("evidenceChunkIds")))) {
                found=true;if(!resolvedNeed(packetId,need.path("needId").asText()))return false;
            }
            return found;
        }
        String effectiveCheckState(SemanticPacketVO packet) {
            if(!"insufficient_evidence".equals(packet.getCheckState()))return packet.getCheckState();
            int count=0;
            for(int i=0;i<packet.getVerifiedAssessments().size();i++)if("insufficient_context".equals(packet.getVerifiedAssessments().get(i).getAssessment())) {
                count++;if(!supersedesVerified(packet.getPacketId(),i))return packet.getCheckState();
            }
            if(count==0||count!=packet.getVerifiedInsufficientContextAssessments()||packet.getRejectedRecords()>0)return packet.getCheckState();
            return "superseded";
        }
    }
    private static boolean resolved(JsonNode parent,JsonNode need,Collection<JsonNode> packets,String policy) {
        return resolved(parent,need,packets,policy,new HashSet<>(),0);
    }
    private static boolean resolved(JsonNode parent,JsonNode need,Collection<JsonNode> packets,String policy,Set<String> path,int depth) {
        String id=need.path("needId").asText();Set<String> affected=strings(need.path("affectedChunkIds")),items=strings(need.path("checklistItemIds"));
        Set<Integer> indexes=indexes(need.path("verifiedAssessmentIndexes"));
        if(!validNeed(need)||!completed(parent)||id.isBlank()||need.path("query").asText().isBlank()||affected.isEmpty()
                ||!strings(parent.path("submittedChunkIds")).containsAll(affected)||(items.isEmpty()&&indexes.isEmpty()))return false;
        if(depth>1||!path.add(key(parent.path("packetId").asText(),id))||!originalQueriesBound(parent,need))return false;
        List<JsonNode> children=new ArrayList<>();
        for(JsonNode child:packets)if(parent.path("packetId").asText().equals(child.path("parentPacketId").asText())
                &&id.equals(child.path("requestedEvidenceNeedId").asText()))children.add(child);
        if(children.size()!=1)return false;
        JsonNode child=children.get(0);Set<String> added=strings(child.path("submittedChunkIds"));added.removeAll(strings(parent.path("submittedChunkIds")));
        if(!completed(child)||!strings(child.path("submittedChunkIds")).containsAll(affected)||added.isEmpty())return false;
        List<JsonNode> continuations=new ArrayList<>();
        for(JsonNode next:child.path("evidenceNeeds"))if(strings(next.path("affectedChunkIds")).containsAll(affected)){
            if(!items.containsAll(strings(next.path("checklistItemIds")))||!resolved(child,next,packets,policy,path,depth+1))return false;
            List<JsonNode> finals=new ArrayList<>();for(JsonNode candidate:packets)if(child.path("packetId").asText().equals(candidate.path("parentPacketId").asText())&&next.path("needId").asText().equals(candidate.path("requestedEvidenceNeedId").asText()))finals.add(candidate);
            if(finals.size()!=1)return false;JsonNode terminal=finals.get(0);
            // Uncited background may be deferred; all earlier quoted authority must still be retained.
            if(!retainsQuotedAuthority(child,terminal,affected,indexes(next.path("verifiedAssessmentIndexes"))))return false;
            continuations.add(terminal);
        }
        List<JsonNode> outcomes=continuations.isEmpty()?Collections.singletonList(child):continuations;
        for(String item:items) {
            List<JsonNode> originals=new ArrayList<>();
            for(JsonNode record:parent.path("checklistAssessments"))if(item.equals(record.path("itemId").asText())
                    &&namesOriginalRow(parent,need,record)
                    &&"insufficient_evidence".equals(record.path("status").asText())&&!Collections.disjoint(strings(record.path("evidenceChunkIds")),affected))originals.add(record);
            if(originals.isEmpty())return false;
            for(JsonNode original:originals) {
                String expected=policy==null?original.path("policySha256").asText():policy;
                if(!VettingChecklistAutomaticResults.valid(parent,original,expected))return false;
                boolean answered=false;String agreedStatus=null;
                for(JsonNode outcome:outcomes){
                    List<JsonNode> candidates=new ArrayList<>();for(JsonNode record:outcome.path("checklistAssessments"))if(item.equals(record.path("itemId").asText())){
                        if(!VettingChecklistAutomaticResults.valid(outcome,record,expected))return false;candidates.add(record);
                    }
                    if(candidates.isEmpty())continue;Set<String> extra=strings(outcome.path("submittedChunkIds"));extra.removeAll(strings(parent.path("submittedChunkIds")));
                    String disposition=answerStatus(parent,original,outcome,candidates,affected,extra,true);
                    if(disposition==null||agreedStatus!=null&&!agreedStatus.equals(disposition))return false;agreedStatus=disposition;answered=true;
                }
                if(!answered)return false;
            }
        }
        for(Integer index:indexes) {
            JsonNode original=parent.path("verifiedAssessments").path(index);
            if(!"insufficient_context".equals(original.path("assessment").asText()))return false;
            String agreedStatus=null;
            for(JsonNode outcome:outcomes){Set<String> extra=strings(outcome.path("submittedChunkIds"));extra.removeAll(strings(parent.path("submittedChunkIds")));
                String disposition=answerStatus(parent,original,outcome,iterable(outcome.path("verifiedAssessments")),affected,extra,false);
                if(disposition==null||agreedStatus!=null&&!agreedStatus.equals(disposition))return false;agreedStatus=disposition;
            }
        }
        return true;
    }
    private static boolean retainsQuotedAuthority(JsonNode prior,JsonNode terminal,Set<String> affected,Set<Integer> priorIndexes){
        Map<String,Quotation> used=new LinkedHashMap<>(),finalQuotes=new LinkedHashMap<>();
        for(int index=0;index<prior.path("verifiedAssessments").size();index++)if(priorIndexes.isEmpty()||priorIndexes.contains(index)){Map<String,Quotation> quotes=quotes(prior,prior.path("verifiedAssessments").get(index),false);if(!Collections.disjoint(quotes.keySet(),affected))used.putAll(quotes);}
        for(JsonNode record:terminal.path("verifiedAssessments")){Map<String,Quotation> quotes=quotes(terminal,record,false);if(!Collections.disjoint(quotes.keySet(),affected))finalQuotes.putAll(quotes);}
        for(Map.Entry<String,Quotation> quote:used.entrySet())if(!finalQuotes.containsKey(quote.getKey())||!quote.getValue().authority.equals(finalQuotes.get(quote.getKey()).authority))return false;
        return !used.isEmpty();
    }
    private static boolean namesOriginalRow(JsonNode parent,JsonNode need,JsonNode row) {
        Set<String> queries=originalQueries(need);if(queries.contains(normalized(row.path("reason").asText())))return true;
        JsonNode index=row.path("verifiedAssessmentIndex");
        if(!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<0||!indexes(need.path("verifiedAssessmentIndexes")).contains(index.intValue()))return false;
        JsonNode original=parent.path("verifiedAssessments").path(index.intValue());if(!"insufficient_context".equals(original.path("assessment").asText()))return false;
        for(JsonNode fact:original.path("pendingFacts"))if(fact.isTextual()&&queries.contains(normalized(fact.asText())))return true;
        return false;
    }
    private static Set<String> originalQueries(JsonNode need){Set<String> queries=new LinkedHashSet<>();for(String query:strings(need.path("originalQueries")))queries.add(normalized(query));if(queries.isEmpty())queries.add(normalized(need.path("query").asText()));return queries;}
    private static boolean originalQueriesBound(JsonNode parent,JsonNode need){
        if(!need.has("originalQueries")||need.path("originalQueries").isEmpty())return true;
        if(!strictStrings(need.path("originalQueries"))||!originalQueries(need).contains(normalized(need.path("query").asText())))return false;
        Set<String> known=new HashSet<>();for(Integer index:indexes(need.path("verifiedAssessmentIndexes"))){JsonNode record=parent.path("verifiedAssessments").path(index);known.add(normalized(record.path("comment").asText()));for(JsonNode fact:record.path("pendingFacts"))known.add(normalized(fact.asText()));}
        for(JsonNode row:parent.path("checklistAssessments"))if(strings(need.path("checklistItemIds")).contains(row.path("itemId").asText()))known.add(normalized(row.path("reason").asText()));
        return known.containsAll(originalQueries(need));
    }
    private static String answerStatus(JsonNode parent,JsonNode original,JsonNode child,List<JsonNode> candidates,Set<String> affected,Set<String> added,boolean checklist) {
        Map<String,Quotation> before=quotes(parent,original,checklist);
        before.entrySet().removeIf(entry->!affected.contains(entry.getKey())||!entry.getValue().sourceTender);
        if(before.isEmpty())return null;
        String status=null;boolean found=false;
        for(JsonNode candidate:candidates) {
            Map<String,Quotation> after=quotes(child,candidate,checklist);
            Set<String> shared=new HashSet<>(before.keySet());shared.retainAll(after.keySet());
            if(shared.isEmpty())continue;
            for(String chunk:shared)if(!before.get(chunk).authority.equals(after.get(chunk).authority))return null;
            String next=candidate.path(checklist?"status":"assessment").asText();
            if(!(checklist?Arrays.asList("fulfilled","not_fulfilled","not_applicable"):Arrays.asList("consistent","issue","not_applicable")).contains(next)
                    ||Collections.disjoint(after.keySet(),added)||status!=null&&!status.equals(next))return null;
            found=true;status=next;
        }
        return found?status:null;
    }
    private static Map<String,Quotation> quotes(JsonNode packet,JsonNode record,boolean checklist) {
        Map<String,Quotation> result=new LinkedHashMap<>();JsonNode evidence=record.path("evidence"),ids=record.path("evidenceChunkIds");
        if(!evidence.isArray()||evidence.isEmpty()||checklist&&(!ids.isArray()||ids.size()!=evidence.size()))return result;
        Set<String> submitted=strings(packet.path("submittedChunkIds"));
        for(int i=0;i<evidence.size();i++) {
            JsonNode quote=evidence.get(i);String id=checklist?ids.get(i).asText():quote.path("canonicalEvidence").path("canonicalChunkId").asText(quote.path("fixedBaselineEvidence").path("parentId").asText());
            if(!submitted.contains(id)||!quote.path("located").asBoolean()||quote.path("quote").asText().isBlank()
                    ||!packet.path("packetId").asText().equals(quote.path("packetId").asText())
                    ||!packet.path("sourceSnapshotSha256").asText().equals(quote.path("packetSourceSnapshotSha256").asText())
                    ||!Arrays.asList("tender","standard","project_fact","package_manifest").contains(quote.path("sourceRole").asText())
                    ||!Arrays.asList("source","reference").contains(quote.path("side").asText())
                    ||quote.path("documentId").asText().isBlank()||!sha(quote.path("sourceHash").asText())||!sha(quote.path("originalSourceSha256").asText()))return Collections.emptyMap();
            Quotation current=new Quotation(quote),prior=result.putIfAbsent(id,current);
            if(prior!=null&&!prior.authority.equals(current.authority))return Collections.emptyMap();
        }
        return result;
    }
    private static final class Quotation {
        final String authority;final boolean sourceTender;
        Quotation(JsonNode quote){
            authority=quote.path("documentId").asText()+"|"+quote.path("sourceHash").asText()+"|"+quote.path("originalSourceSha256").asText()+"|"+quote.path("side").asText()+"|"+quote.path("sourceRole").asText();
            sourceTender="source".equals(quote.path("side").asText())&&"tender".equals(quote.path("sourceRole").asText());
        }
    }
    private static boolean validNeed(JsonNode need){
        return strictStrings(need.path("checklistItemIds"))&&strictStrings(need.path("affectedChunkIds"))
                &&need.path("verifiedAssessmentIndexes").isArray()&&indexes(need.path("verifiedAssessmentIndexes")).size()==need.path("verifiedAssessmentIndexes").size();
    }
    private static boolean strictStrings(JsonNode values){return values.isArray()&&strings(values).size()==values.size();}
    private static String normalized(String value){return value==null?"":value.trim().replaceAll("\\s+"," ");}
    private static boolean completed(JsonNode packet){return packet.path("actualGatewayCallStarted").asBoolean()&&sha(packet.path("sourceSnapshotSha256").asText())
            &&Arrays.asList("completed","completed_empty","completed_with_rejections").contains(packet.path("status").asText());}
    private static String key(String packet,String need){return packet+"\n"+need;}
    private static boolean sha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static Set<String> strings(JsonNode values){Set<String> result=new LinkedHashSet<>();if(values.isArray())for(JsonNode value:values)if(value.isTextual()&&!value.asText().isBlank())result.add(value.asText());return result;}
    private static Set<Integer> indexes(JsonNode values){Set<Integer> result=new LinkedHashSet<>();if(values.isArray())for(JsonNode value:values)if(value.isIntegralNumber()&&value.canConvertToInt()&&value.intValue()>=0)result.add(value.intValue());return result;}
    private static List<JsonNode> iterable(JsonNode values){List<JsonNode> result=new ArrayList<>();values.forEach(result::add);return result;}
    /** Only decision and quotation identity fields are projected, never attempts or native source trees. */
    private static ObjectNode project(SemanticPacketVO packet) {
        ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("packetId",packet.getPacketId()).put("parentPacketId",packet.getParentPacketId()).put("requestedEvidenceNeedId",packet.getRequestedEvidenceNeedId())
                .put("sourceSnapshotSha256",packet.getSourceSnapshotSha256()).put("status",packet.getStatus()).put("actualGatewayCallStarted",packet.isActualGatewayCallStarted());
        out.set("submittedChunkIds",JsonUtils.mapper().valueToTree(packet.getSubmittedChunkIds()));out.set("evidenceNeeds",JsonUtils.mapper().valueToTree(packet.getEvidenceNeeds()));
        ArrayNode verified=out.putArray("verifiedAssessments"),checks=out.putArray("checklistAssessments");
        for(VerifiedAssessmentVO record:packet.getVerifiedAssessments()) {
            ObjectNode row=verified.addObject().put("assessment",record.getAssessment()).put("comment",record.getComment());row.set("evidence",evidence(record.getEvidence()));
            row.set("pendingFacts",JsonUtils.mapper().valueToTree(record.getPendingFacts()));
        }
        for(ChecklistAssessmentVO record:packet.getChecklistAssessments()) {
            ObjectNode row=checks.addObject();row.put("producerVersion",record.getProducerVersion()).put("itemId",record.getItemId()).put("status",record.getStatus()).put("reason",record.getReason())
                    .put("policySha256",record.getPolicySha256()).put("packetId",record.getPacketId()).put("sourceSnapshotSha256",record.getSourceSnapshotSha256()).put("issueTypeCode",record.getIssueTypeCode());
            row.put("verifiedAssessmentIndex",record.getVerifiedAssessmentIndex());
            row.set("evidenceChunkIds",JsonUtils.mapper().valueToTree(record.getEvidenceChunkIds()));row.set("evidence",evidence(record.getEvidence()));
        }
        return out;
    }
    private static ArrayNode evidence(List<FindingEvidence> quotes) {
        ArrayNode out=JsonUtils.mapper().createArrayNode();if(quotes==null)return out;
        for(FindingEvidence quote:quotes) {
            ObjectNode row=out.addObject();row.put("documentId",quote.getDocumentId()).put("sourceHash",quote.getSourceHash()).put("originalSourceSha256",quote.getOriginalSourceSha256())
                    .put("side",quote.getSide()).put("sourceRole",quote.getSourceRole()).put("located",quote.isLocated()).put("quote",quote.getQuote())
                    .put("packetId",quote.getPacketId()).put("packetSourceSnapshotSha256",quote.getPacketSourceSnapshotSha256());
            if(quote.getCanonicalEvidence()!=null)row.putObject("canonicalEvidence").put("canonicalChunkId",quote.getCanonicalEvidence().getCanonicalChunkId());
            if(quote.getFixedBaselineEvidence()!=null)row.putObject("fixedBaselineEvidence").put("parentId",quote.getFixedBaselineEvidence().path("parentId").asText());
        }
        return out;
    }
}
