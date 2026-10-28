package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Small read-only views of saved current-source evidence, never edit authorization. */
final class DraftDiscoveryEvidence {
    private static final int MAX_REQUESTS=4,MAX_CONTEXT_CHARS=24000,MAX_HITS=8;
    private static final Map<String,String> ARRAYS=new LinkedHashMap<>();
    static {ARRAYS.put("guidance","guidanceNotes");ARRAYS.put("condition","conditions");ARRAYS.put("scope","groups");ARRAYS.put("member","sourceMembers");}
    private DraftDiscoveryEvidence() {}

    /** This parser bookkeeping label is not a statement about the business condition's logical role. */
    static boolean internalConditionStatus(String field,Object value){return "status".equals(field)&&"literal_source_condition_not_project_boolean".equals(value instanceof JsonNode?((JsonNode)value).asText():text(value));}

    static Map<String,Object> context(JsonNode reading,JsonNode requests) {
        Map<String,Object> result=envelope(reading);List<Object> items=new ArrayList<>(),issues=new ArrayList<>();result.put("items",items);result.put("issues",issues);
        if(!available(reading)){issues.add(obj("reason","source_context_unavailable"));return result;}
        if(!requests.isArray()){if(!requests.isMissingNode()&&!requests.isNull())issues.add(obj("reason","source_context_requests_array_required"));return result;}
        Set<String> seen=new HashSet<>();int requested=0,chars=0;
        for(JsonNode request:requests){
            if(requested++>=MAX_REQUESTS){issues.add(obj("reason","source_context_request_limit_reached"));break;}
            String kind=request.path("kind").asText(),id=request.path("id").asText();
            if(!ARRAYS.containsKey(kind)||id.isEmpty()||request.hasNonNull("sourceId")&&!reading.path("id").asText().equals(request.path("sourceId").asText())){issues.add(obj("kind",kind,"id",id,"reason","source_context_request_invalid"));continue;}
            if(!seen.add(kind+"|"+id))continue;
            JsonNode observation=unique(reading.path(ARRAYS.get(kind)),id);
            if(observation==null){issues.add(obj("kind",kind,"id",id,"reason","source_context_identity_missing_or_duplicated"));continue;}
            Map<String,Object> item=project(reading,kind,observation);
            if(item==null){issues.add(obj("kind",kind,"id",id,"reason","source_context_text_or_range_unavailable"));continue;}
            long textChars=text(item.get("text")).length();for(Object raw:list(item.get("adjacentMembers")))textChars+=text(map(raw).get("text")).length();
            if(textChars>MAX_CONTEXT_CHARS){issues.add(obj("kind",kind,"id",id,"reason","source_context_budget_exceeded","itemChars",textChars));continue;}
            int size=JsonUtils.write(item).length();
            if(chars+size>MAX_CONTEXT_CHARS){issues.add(obj("kind",kind,"id",id,"reason","source_context_budget_exceeded","itemChars",size));continue;}
            items.add(item);chars+=size;
        }
        return result;
    }

    static Map<String,Object> search(JsonNode reading,JsonNode queries) {
        Map<String,Object> result=envelope(reading);List<Object> hits=new ArrayList<>(),issues=new ArrayList<>();result.put("results",hits);result.put("issues",issues);result.put("navigationOnly",true);
        if(!available(reading)){issues.add(obj("reason","source_context_unavailable"));return result;}
        List<String> terms=new ArrayList<>();
        if(queries.isArray())for(JsonNode query:queries){String value=query.isTextual()?query.asText().trim():"";if(value.isEmpty()||value.length()>160){issues.add(obj("reason","source_search_query_invalid"));continue;}if(terms.size()>=MAX_REQUESTS){issues.add(obj("reason","source_search_query_limit_reached"));break;}terms.add(value.toLowerCase(Locale.ROOT));}
        else if(!queries.isMissingNode()&&!queries.isNull())issues.add(obj("reason","source_search_queries_array_required"));
        if(terms.isEmpty())return result;
        for(Map.Entry<String,String> kind:ARRAYS.entrySet())for(JsonNode observation:reading.path(kind.getValue())){
            String id=observation.path("id").asText(),body="member".equals(kind.getKey())?memberText(observation):observation.path("text").asText();if(id.isEmpty()||body==null||body.trim().isEmpty())continue;String lower=body.toLowerCase(Locale.ROOT);if(terms.stream().noneMatch(lower::contains))continue;
            int at=body.length();for(String term:terms){int found=lower.indexOf(term);if(found>=0)at=Math.min(at,found);}int start=Math.max(0,at-60),end=Math.min(body.length(),start+240);
            hits.add(obj("kind",kind.getKey(),"id",id,"preview",body.substring(start,end),"previewStart",start,"previewEnd",end,"previewOnly",true));
            if(hits.size()>=MAX_HITS)return result;
        }
        return result;
    }

    static List<Object> mergeIssues(Object existing,Object incoming) {
        Map<String,Object> retained=new LinkedHashMap<>();
        for(Object source:Arrays.asList(existing,incoming))for(Object raw:list(source)){
            retained.putIfAbsent(JsonUtils.write(raw),raw);
        }
        return new ArrayList<>(retained.values());
    }

    static JsonNode draft(JsonNode response){ObjectNode result=JsonUtils.mapper().createObjectNode();for(String key:Arrays.asList("nodes","requirements","notes"))if(response.has(key))result.set(key,response.get(key));return result;}

    /** Requirement IDs have no incoming model references; only cross-owner group collisions may get a new local name. */
    static JsonNode mergeGroupResponse(JsonNode previous,JsonNode next,List<Object> issues,List<Object> normalizations) {
        Map<String,JsonNode> prior=new LinkedHashMap<>();Set<String> ambiguous=new HashSet<>(),incomingIds=new HashSet<>();Map<String,Integer> incomingCounts=new HashMap<>();
        for(JsonNode row:previous.path("requirements")){String id=row.path("id").asText();if(prior.putIfAbsent(id,row)!=null)ambiguous.add(id);}
        for(JsonNode row:next.path("requirements")){String id=row.path("id").asText();incomingIds.add(id);incomingCounts.put(id,incomingCounts.getOrDefault(id,0)+1);}
        ObjectNode normalized=null;JsonNode requirements=next.path("requirements");
        for(int index=0;requirements.isArray()&&index<requirements.size();index++) {
            JsonNode row=requirements.path(index);String id=row.path("id").asText(),owner=row.path("sourceTaskId").asText();JsonNode old=prior.get(id);
            if(!row.isObject()||id.isEmpty()||owner.isEmpty()||old==null||ambiguous.contains(id)||incomingCounts.get(id)!=1)continue;
            String oldOwner=old.path("sourceTaskId").asText();if(oldOwner.isEmpty()||oldOwner.equals(owner))continue;
            String replacement="requirement-"+DraftAdoption.hash(owner+"\n"+id).substring(0,24);boolean known=false;
            for(Object raw:normalizations){Map<String,Object> mapping=map(raw);if("requirement_id_owner_collision".equals(mapping.get("kind"))&&id.equals(mapping.get("originalId"))&&owner.equals(mapping.get("sourceTaskId"))&&replacement.equals(mapping.get("normalizedId")))known=true;}
            JsonNode existing=prior.get(replacement);
            // Never repurpose a model's own ID or an unrelated previously retained requirement.
            if(incomingIds.contains(replacement)||existing!=null&&(!known||!owner.equals(existing.path("sourceTaskId").asText())))continue;
            if(normalized==null)normalized=next.deepCopy();((ObjectNode)normalized.path("requirements").path(index)).put("id",replacement);incomingIds.add(replacement);
            if(!known)normalizations.add(obj("kind","requirement_id_owner_collision","originalId",id,"normalizedId",replacement,"sourceTaskId",owner,"priorSourceTaskId",oldOwner));
        }
        return mergeResponse(previous,normalized==null?next:normalized,issues);
    }

    static JsonNode mergeResponse(JsonNode previous,JsonNode next,List<Object> issues) {
        ObjectNode result=JsonUtils.mapper().createObjectNode();if(next.isObject())next.fields().forEachRemaining(field->result.set(field.getKey(),field.getValue()));else issues.add(obj("reason","source_context_response_object_required"));
        Set<String> limits=new HashSet<>();for(String kind:Arrays.asList("nodes","requirements"))if(previous.path(kind).isArray()||next.path(kind).isArray()){
            Map<String,JsonNode> retained=new LinkedHashMap<>();
            for(JsonNode response:Arrays.asList(previous,next))for(JsonNode row:response.path(kind)){
                if(!row.isObject()){issues.add(obj("reason","source_context_candidate_object_required","kind",kind));continue;}
                String id=row.path("id").asText(),key=id.isEmpty()?JsonUtils.write(row):id;JsonNode prior=retained.get(key);
                if(prior==null){if(retained.size()>=100){if(limits.add(kind))issues.add(obj("reason","source_context_candidate_limit_reached","kind",kind));continue;}retained.put(key,row);continue;}
                List<String> changed=new ArrayList<>();for(String field:"nodes".equals(kind)?Arrays.asList("sourceTaskId","type","role","parentId","nameZh","nameEn","questionZh","questionEn","options","sourceQuote","sourceObservationId","optional","unit","fixedValue","definitionScope"):Arrays.asList("sourceTaskId","kind","sourceQuote","sourceObservationId","nodeIds"))if(hasValue(prior.get(field))&&hasValue(row.get(field))&&!prior.get(field).equals(row.get(field)))changed.add(field);
                if(!changed.isEmpty()){issues.add(obj("reason","source_context_candidate_conflict","kind",kind,"id",id,"fields",changed,"retained","previous_candidate_requires_review"));continue;}
                ObjectNode merged=shallow(prior);row.fields().forEachRemaining(field->{if(!merged.has(field.getKey())||!hasValue(merged.get(field.getKey()))&&hasValue(field.getValue()))merged.set(field.getKey(),field.getValue());});
                if("nodes".equals(kind))for(String field:Arrays.asList("branches","effects"))if(prior.path(field).isArray()||row.path(field).isArray())merged.set(field,mergeRules(prior.path(field),row.path(field),field,id,merged.path("sourceTaskId").asText(),issues));
                retained.put(key,merged);
            }
            ArrayNode rows=JsonUtils.mapper().createArrayNode();retained.values().forEach(rows::add);result.set(kind,rows);
        }
        if(previous.has("notes")||next.has("notes")){
            Map<String,JsonNode> retained=new LinkedHashMap<>();for(JsonNode response:Arrays.asList(previous,next)){JsonNode notes=response.path("notes");if(notes.isArray())for(JsonNode note:notes)retained.putIfAbsent(JsonUtils.write(note),note);else if(!notes.isMissingNode()&&!notes.isNull())retained.putIfAbsent(JsonUtils.write(notes),notes);}ArrayNode notes=JsonUtils.mapper().createArrayNode();retained.values().forEach(notes::add);result.set("notes",notes);
        }
        return result;
    }

    private static ArrayNode mergeRules(JsonNode previous,JsonNode next,String kind,String nodeId,String ownerTask,List<Object> issues){
        Map<String,JsonNode> retained=new LinkedHashMap<>();for(JsonNode rows:Arrays.asList(previous,next))for(JsonNode row:rows){
            String key=row.path("id").asText();if(key.isEmpty())key="effects".equals(kind)?JsonUtils.write(obj("scopeId",row.path("scopeId"),"branchId",row.path("branchId"),"quoteSupplied",row.has("quote"),"quote",row.path("quote"),"valueSourceId",row.path("valueSourceId"))):JsonUtils.write(row);if("effects".equals(kind))key=JsonUtils.write(obj("sourceTaskId",row.has("sourceTaskId")?row.path("sourceTaskId").asText():ownerTask,"effect",key));JsonNode prior=retained.get(key);
            if(prior==null){retained.put(key,row);continue;}List<String> changed=new ArrayList<>();if(prior.isObject()&&row.isObject())row.fields().forEachRemaining(field->{if(ruleValue(kind,field.getKey(),prior)&&ruleValue(kind,field.getKey(),row)&&!prior.get(field.getKey()).equals(field.getValue()))changed.add(field.getKey());});else if(!prior.equals(row))changed.add("value");
            if(!changed.isEmpty()){issues.add(obj("reason","source_context_rule_conflict","nodeId",nodeId,"kind",kind,"id",key,"fields",changed,"retained","previous_rule_requires_review"));continue;}
            if(!prior.isObject()){retained.put(key,prior);continue;}
            ObjectNode merged=shallow(prior);if(row.isObject())row.fields().forEachRemaining(field->{if(!merged.has(field.getKey())||!ruleValue(kind,field.getKey(),merged)&&ruleValue(kind,field.getKey(),row))merged.set(field.getKey(),field.getValue());});retained.put(key,merged);
        }ArrayNode result=JsonUtils.mapper().createArrayNode();retained.values().forEach(result::add);return result;
    }
    private static boolean ruleValue(String kind,String field,JsonNode row){return "effects".equals(kind)&&"quote".equals(field)?row.has(field):hasValue(row.get(field));}
    private static boolean hasValue(JsonNode value){return value!=null&&!value.isMissingNode()&&!value.isNull()&&!(value.isTextual()&&value.asText().trim().isEmpty())&&!(value.isContainerNode()&&value.isEmpty());}
    private static ObjectNode shallow(JsonNode value){ObjectNode result=JsonUtils.mapper().createObjectNode();if(value.isObject())value.fields().forEachRemaining(field->result.set(field.getKey(),field.getValue()));return result;}

    private static Map<String,Object> envelope(JsonNode reading){Map<String,Object> out=obj("sourceId",reading.path("id").asText(),"sourceSha256",reading.path("sourceSha256").asText(),"editableAuthority",false);for(String key:Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"))if(reading.path(key).isTextual())out.put(key,reading.path(key).asText());return out;}
    private static boolean available(JsonNode reading){return "bound".equals(reading.path("evidenceStatus").asText())&&!reading.path("id").asText().isEmpty()&&reading.path("sourceSha256").asText().matches("(?i)[a-f0-9]{64}");}
    private static JsonNode unique(JsonNode rows,String id){JsonNode found=null;for(JsonNode row:rows)if(id.equals(row.path("id").asText())){if(found!=null)return null;found=row;}return found;}
    private static String memberText(JsonNode member){JsonNode value=member.path("preNormalizedTextObservation").path("text");if(!value.isTextual())value=member.path("textObservation").path("text");return value.isTextual()?value.asText():null;}

    private static Map<String,Object> project(JsonNode reading,String kind,JsonNode observation){
        String body="member".equals(kind)?memberText(observation):observation.path("text").isTextual()?observation.path("text").asText():null;
        Map<String,Object> item=obj("kind",kind,"id",observation.path("id").asText());item.putAll(envelope(reading));
        if("scope".equals(kind)&&(observation.path("sourceInsertionAnchorComplete").asBoolean(false)||!observation.path("insertionAnchors").isEmpty())){
            Map<String,Object> point=DraftReviewDiscovery.insertionPointContext(reading,observation);if(point.isEmpty())return null;
            item.putAll(point);item.put("text","");item.put("scopeComplete",false);item.put("sourceInsertionAnchorComplete",true);return item;
        }
        if("member".equals(kind)){
            if(body==null)return null;List<Object> adjacent=new ArrayList<>();JsonNode members=reading.path("sourceMembers");int at=-1;for(int i=0;i<members.size();i++)if(members.path(i)==observation){at=i;break;}
            String channel=observation.path("preNormalizedTextObservation").path("text").isTextual()?"preNormalizedTextObservation.text":"text";item.put("ranges",Collections.singletonList(obj("memberId",observation.path("id").asText(),"channel",channel,"start",0,"end",body.length(),"offsetUnit","utf16")));
            for(int i:Arrays.asList(at-1,at+1))if(at>=0&&i>=0&&i<members.size()){JsonNode member=members.path(i);String text=memberText(member);if(text!=null&&sameStory(observation,member))adjacent.add(obj("id",member.path("id").asText(),"text",text,"navigationOnly",true,"editableAuthority",false));}
            item.put("adjacentMembers",adjacent);
        }else{
            JsonNode ranges=DraftReviewSources.observationRanges(observation);List<Object> projected=new ArrayList<>();List<String> pieces=new ArrayList<>();
            if(ranges.isArray())for(JsonNode range:ranges){JsonNode member=unique(reading.path("sourceMembers"),range.path("memberId").asText());String channel=range.path("channel").asText("text");boolean before=channel.startsWith("preNormalizedTextObservation.");String name=before?channel.substring("preNormalizedTextObservation.".length()):channel;JsonNode text=member==null?null:member.path(before?"preNormalizedTextObservation":"textObservation").path(name);if(!Arrays.asList("text","originalText","deletedText","strikeText").contains(name)||text==null||!text.isTextual()||!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber()||!range.path("start").canConvertToInt()||!range.path("end").canConvertToInt())return null;
                int start=range.path("start").asInt(),end=range.path("end").asInt();if(start<0||end<start||end>text.asText().length())return null;pieces.add(text.asText().substring(start,end));Map<String,Object> position=obj("memberId",range.path("memberId").asText(),"channel",channel,"start",start,"end",end);if(range.path("offsetUnit").isTextual())position.put("offsetUnit",range.path("offsetUnit").asText());projected.add(position);
            }
            if(body==null&&!pieces.isEmpty())body=String.join("\n",pieces);if(body==null)return null;if(!projected.isEmpty())item.put("ranges",projected);
            for(String key:Arrays.asList("status","scopeComplete","captureComplete","sourceInsertionAnchorComplete","sourceSyntaxKnown","sourceSyntaxComplete","semanticStatus","targetScopeStatus"))if(observation.has(key)&&!internalConditionStatus(key,observation.get(key)))item.put(key,JsonUtils.mapper().convertValue(observation.get(key),Object.class));
        }
        item.put("text",body);return item;
    }
    private static boolean sameStory(JsonNode a,JsonNode b){String left=a.path("sourcePosition").path("partName").asText(),right=b.path("sourcePosition").path("partName").asText();if(!left.isEmpty()&&!right.isEmpty())return left.equals(right);left=a.path("storyKind").asText();right=b.path("storyKind").asText();return left.isEmpty()||right.isEmpty()||left.equals(right);}
}
