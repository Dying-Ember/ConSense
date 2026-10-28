package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Per-group wire labels only. Labels never grant source access or change the retained source text. */
final class DraftDiscoveryReferences {
    private static final String VERSION="draft-source-references-v1";
    private final Map<String,Reference> identities=new LinkedHashMap<>(),aliases=new LinkedHashMap<>();
    private final Map<String,String> taskSources=new LinkedHashMap<>();
    private final Map<String,Integer> counts=new HashMap<>();
    private DraftDiscoveryReferences(){}

    static DraftDiscoveryReferences group(Map<String,Object> initialPayload){DraftDiscoveryReferences refs=new DraftDiscoveryReferences();refs.registerGroup(JsonUtils.mapper().valueToTree(initialPayload));return refs;}
    static String promptInstruction(){return " Current-source task and observation references use short wire labels supplied in this packet. Copy these labels exactly; do not invent or reconstruct long IDs. Use sourceTaskId from the owning instruction. sourceObservationId, when needed, uses that task's supplied observation label; sourceContextRequests.id uses the label of its declared kind. effects.scopeId uses a supplied source scope label, with effect.sourceTaskId identifying its authorizing task. A branch scopeId, clauses.inputId, parentId, nodeIds and valueSourceId remain local node IDs, never source labels. Labels do not expand an owner's evidenceRefs. Fixed definition keys, action IDs, quoted source text and project values are unchanged.";}

    /** Encode an initial group or a follow-up {group,priorDraft,supplement}; reuse this instance across rounds. */
    JsonNode encode(Object payload){
        JsonNode copy=JsonUtils.mapper().valueToTree(payload);if(!copy.isObject())return copy;
        if(copy.has("group")){registerGroup(copy.path("group"));registerSupplement(copy.path("supplement"));groupFields(copy.path("group"));draftFields(copy.path("priorDraft"),true);supplementFields(copy.path("supplement"));}
        else {registerGroup(copy);groupFields(copy);}return copy;
    }
    /** Unknown labels stay unchanged; known labels in the wrong namespace/source remain explicitly invalid. */
    JsonNode decode(JsonNode response){JsonNode copy=response.deepCopy();draftFields(copy,false);return copy;}
    Map<String,Object> receipt(){List<Object> rows=new ArrayList<>();for(Reference ref:aliases.values())rows.add(obj("alias",ref.alias,"kind",ref.kind,"sourceId",ref.source,"id",ref.id));return obj("version",VERSION,"scope","one_group_across_evidence_rounds","aliases",rows);}

    private void registerGroup(JsonNode group){
        for(JsonNode row:group.path("instructions")){
            String source=row.path("sourceId").asText(),task=row.path("sourceTaskId").asText();if(!task.isEmpty()){
                String old=taskSources.putIfAbsent(task,source);if(old!=null&&!old.equals(source))throw new IllegalArgumentException("Task source reference changed");register("task",source,task);
            }
            register(instructionKind(row.path("kind").asText()),source,row.path("sourceObservationId").asText());
            for(JsonNode ref:row.path("evidenceRefs"))registerRef(ref.asText());
            registerArray(row,"scopeObligationIds","scope",source);registerArray(row,"unresolvedConditionIds","condition",source);registerAlignment(row.path("currentTemplateAlignment"),source);
        }
        for(JsonNode row:group.path("sharedEvidence"))registerEvidence(row,row.path("sourceId").asText(),row.path("kind").asText());
    }
    private void registerAlignment(JsonNode row,String source){for(JsonNode ref:row.path("currentEvidenceRefs"))registerRef(ref.asText());registerArray(row,"currentScopeIds","scope",source);}
    private void registerSupplement(JsonNode supplement){
        for(JsonNode row:supplement.path("currentSourceContext")){String source=sourceOf(row.path("sourceTaskId").asText());registerContext(row.path("context"),source);}
        for(JsonNode row:supplement.path("sourceSearchResults")){String source=sourceOf(row.path("sourceTaskId").asText());registerContext(row.path("search"),source);}
    }
    private void registerContext(JsonNode context,String fallback){String source=context.path("sourceId").asText(fallback);for(String field:Arrays.asList("items","results"))for(JsonNode row:context.path(field))registerEvidence(row,source,row.path("kind").asText());}
    private void registerEvidence(JsonNode row,String source,String kind){
        register(kind,source,row.path("id").asText());registerRef(row.path("ref").asText());
        for(String field:Arrays.asList("guidanceNoteId","parentGuidanceNoteId","childGuidanceNoteId"))register("guidance",source,row.path(field).asText());registerArray(row,"guidanceNoteIds","guidance",source);
        for(String field:Arrays.asList("conditionId","parentConditionId","childConditionId"))register("condition",source,row.path(field).asText());registerArray(row,"conditionIds","condition",source);
        for(String field:Arrays.asList("parentGroupId","childGroupId"))register("scope",source,row.path(field).asText());registerArray(row,"groupIds","scope",source);
        register("member",source,row.path("memberId").asText());registerArray(row,"orderedMemberIds","member",source);for(JsonNode range:row.path("ranges"))register("member",source,range.path("memberId").asText());
        for(JsonNode adjacent:row.path("adjacentMembers"))register("member",source,adjacent.path("id").asText());
    }
    private void registerArray(JsonNode row,String field,String kind,String source){for(JsonNode id:row.path(field))register(kind,source,id.asText());}
    private void registerRef(String value){String[] parts=value.split("\\|",3);if(parts.length==3)register(parts[1],parts[0],parts[2]);}
    private void register(String kind,String source,String id){
        String prefix=prefix(kind);if(prefix.isEmpty()||source.isEmpty()||id.isEmpty())return;String key=kind+"|"+source+"|"+id;if(identities.containsKey(key))return;
        int next=counts.getOrDefault(kind,0)+1;counts.put(kind,next);Reference ref=new Reference(prefix+next,kind,source,id);identities.put(key,ref);aliases.put(ref.alias,ref);
    }

    private void groupFields(JsonNode group){
        for(JsonNode row:group.path("instructions")){
            String source=row.path("sourceId").asText();field(row,"sourceTaskId","task",source,true);field(row,"sourceObservationId",instructionKind(row.path("kind").asText()),source,true);refArray(row,"evidenceRefs");array(row,"scopeObligationIds","scope",source);array(row,"unresolvedConditionIds","condition",source);
            JsonNode alignment=row.path("currentTemplateAlignment");field(alignment,"sourceTaskId","task",source,true);refArray(alignment,"currentEvidenceRefs");array(alignment,"currentScopeIds","scope",source);
        }
        for(JsonNode row:group.path("sharedEvidence"))evidenceFields(row,row.path("sourceId").asText(),row.path("kind").asText());
        for(JsonNode row:group.path("knownRoots")){JsonNode ids=row.path("sourceTaskIds");if(ids.isArray())for(int i=0;i<ids.size();i++){String id=ids.path(i).asText();((ArrayNode)ids).set(i,JsonUtils.mapper().getNodeFactory().textNode(translate("task",sourceOf(id),id,true)));}}
    }
    private void supplementFields(JsonNode supplement){
        for(JsonNode row:supplement.path("currentSourceContext")){String source=sourceOf(row.path("sourceTaskId").asText());field(row,"sourceTaskId","task",source,true);contextFields(row.path("context"),source);}
        for(JsonNode row:supplement.path("sourceSearchResults")){String source=sourceOf(row.path("sourceTaskId").asText());field(row,"sourceTaskId","task",source,true);contextFields(row.path("search"),source);}
        for(JsonNode row:supplement.path("repairRequests")){String source=sourceOf(row.path("sourceTaskId").asText());field(row,"sourceTaskId","task",source,true);array(row,"sourceTaskIds","task",source);array(row,"scopeIds","scope",source);}
    }
    private void contextFields(JsonNode context,String fallback){String source=context.path("sourceId").asText(fallback);for(String field:Arrays.asList("items","results"))for(JsonNode row:context.path(field))evidenceFields(row,source,row.path("kind").asText());}
    private void evidenceFields(JsonNode row,String source,String kind){
        field(row,"id",kind,source,true);if(row.path("ref").isTextual())((ObjectNode)row).put("ref",ref(row.path("ref").asText()));
        for(String key:Arrays.asList("guidanceNoteId","parentGuidanceNoteId","childGuidanceNoteId"))field(row,key,"guidance",source,true);array(row,"guidanceNoteIds","guidance",source);
        for(String key:Arrays.asList("conditionId","parentConditionId","childConditionId"))field(row,key,"condition",source,true);array(row,"conditionIds","condition",source);
        for(String key:Arrays.asList("parentGroupId","childGroupId"))field(row,key,"scope",source,true);array(row,"groupIds","scope",source);
        field(row,"memberId","member",source,true);array(row,"orderedMemberIds","member",source);for(JsonNode range:row.path("ranges"))field(range,"memberId","member",source,true);for(JsonNode adjacent:row.path("adjacentMembers"))field(adjacent,"id","member",source,true);
    }
    private void draftFields(JsonNode draft,boolean encode){
        for(JsonNode rule:draft.path("scopeRules")){String owner=actualTask(rule.path("sourceTaskId").asText(),encode),source=sourceOf(owner);field(rule,"sourceTaskId","task",source,encode);field(rule,"scopeId","scope",source,encode);}
        for(String part:Arrays.asList("nodes","requirements"))for(JsonNode row:draft.path(part)){
            String owner=actualTask(row.path("sourceTaskId").asText(),encode),source=sourceOf(owner);field(row,"sourceTaskId","task",source,encode);observationField(row,"sourceObservationId",source,encode);
            if("nodes".equals(part))for(JsonNode effect:row.path("effects")){String effectOwner=effect.hasNonNull("sourceTaskId")?actualTask(effect.path("sourceTaskId").asText(),encode):owner,effectSource=sourceOf(effectOwner);field(effect,"sourceTaskId","task",effectSource,encode);field(effect,"scopeId","scope",effectSource,encode);}
        }
        for(JsonNode row:draft.path("taskOutcomes")){String owner=actualTask(row.path("sourceTaskId").asText(),encode);field(row,"sourceTaskId","task",sourceOf(owner),encode);}
        for(String part:Arrays.asList("sourceContextRequests","sourceSearchRequests"))for(JsonNode row:draft.path(part)){String owner=actualTask(row.path("sourceTaskId").asText(),encode),source=sourceOf(owner);field(row,"sourceTaskId","task",source,encode);if("sourceContextRequests".equals(part))field(row,"id",row.path("kind").asText(),source,encode);}
    }
    private String actualTask(String value,boolean encode){if(encode)return value;Reference ref=aliases.get(value);return ref!=null&&"task".equals(ref.kind)?ref.id:value;}
    private String sourceOf(String task){return taskSources.getOrDefault(task,"");}
    private void field(JsonNode row,String key,String kind,String source,boolean encode){if(row.isObject()&&row.path(key).isTextual())((ObjectNode)row).put(key,translate(kind,source,row.path(key).asText(),encode));}
    private void observationField(JsonNode row,String key,String source,boolean encode){
        if(!row.isObject()||!row.path(key).isTextual())return;String value=row.path(key).asText();Reference found=null;
        if(encode){for(Reference ref:identities.values())if(!"task".equals(ref.kind)&&ref.source.equals(source)&&ref.id.equals(value)){if(found!=null)return;found=ref;}}
        else {Reference ref=aliases.get(value);if(ref!=null){if("task".equals(ref.kind)||!ref.source.equals(source)){((ObjectNode)row).put(key,invalidAlias(value));return;}found=ref;}}
        if(found!=null)((ObjectNode)row).put(key,encode?found.alias:found.id);
    }
    private String translate(String kind,String source,String value,boolean encode){Reference ref=encode?identities.get(kind+"|"+source+"|"+value):aliases.get(value);if(ref==null)return value;if(!ref.kind.equals(kind)||!ref.source.equals(source))return encode?value:invalidAlias(value);return encode?ref.alias:ref.id;}
    private String invalidAlias(String alias){String value="unresolved-source-alias:"+alias;boolean collision;do{collision=false;for(Reference ref:identities.values())if(ref.id.equals(value)){value="_"+value;collision=true;break;}}while(collision);return value;}
    private void array(JsonNode row,String key,String kind,String source){JsonNode values=row.path(key);if(values.isArray())for(int i=0;i<values.size();i++)if(values.path(i).isTextual())((ArrayNode)values).set(i,JsonUtils.mapper().getNodeFactory().textNode(translate(kind,source,values.path(i).asText(),true)));}
    private String ref(String value){String[] parts=value.split("\\|",3);return parts.length==3?translate(parts[1],parts[0],parts[2],true):value;}
    private void refArray(JsonNode row,String key){JsonNode values=row.path(key);if(values.isArray())for(int i=0;i<values.size();i++)if(values.path(i).isTextual())((ArrayNode)values).set(i,JsonUtils.mapper().getNodeFactory().textNode(ref(values.path(i).asText())));}
    private static String instructionKind(String kind){return "field".equals(kind)?"scope":kind;}
    private static String prefix(String kind){switch(kind){case "task":return "t";case "scope":return "s";case "guidance":return "g";case "condition":return "c";case "relationship":return "r";case "member":return "m";case "marker":return "o";default:return "";}}
    private static final class Reference {final String alias,kind,source,id;Reference(String alias,String kind,String source,String id){this.alias=alias;this.kind=kind;this.source=source;this.id=id;}}
}
