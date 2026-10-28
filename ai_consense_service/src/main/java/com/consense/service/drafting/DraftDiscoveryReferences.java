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
    private static final Set<String> CONTEXT_KINDS=new LinkedHashSet<>(Arrays.asList("member","scope","guidance","condition"));
    private static final int MAX_REQUEST_FEEDBACK=4,MAX_REQUEST_CHOICES=4;
    private final Map<String,Reference> identities=new LinkedHashMap<>(),aliases=new LinkedHashMap<>();
    private final Map<String,String> taskSources=new LinkedHashMap<>();
    // Read-navigation labels already supplied to each owner, never evidence or edit authority.
    private final Map<String,Set<String>> taskReferences=new LinkedHashMap<>();
    private final List<Object> feedback=new ArrayList<>();
    private final Map<String,Integer> counts=new HashMap<>();
    private DraftDiscoveryReferences(){}

    static DraftDiscoveryReferences group(Map<String,Object> initialPayload){DraftDiscoveryReferences refs=new DraftDiscoveryReferences();refs.registerGroup(JsonUtils.mapper().valueToTree(initialPayload));return refs;}
    static String promptInstruction(){return " Current-source task and observation references use short wire labels supplied in this packet. Copy these labels exactly; do not invent or reconstruct long IDs. Use sourceTaskId from the owning instruction. sourceObservationId, when needed, uses that task's supplied observation label; sourceContextRequests.id uses the label of its declared kind. sourceRequestFeedback identifies rejected requests by zero-based requestIndex and offers owner-local {kind,id} choices; submit an explicit corrected request, never treat feedback as evidence. effects.scopeId uses a supplied source scope label, with effect.sourceTaskId identifying its authorizing task. A branch scopeId, clauses.inputId, parentId, nodeIds and valueSourceId remain local node IDs, never source labels. Labels do not expand an owner's evidenceRefs. Fixed definition keys, action IDs, quoted source text and project values are unchanged.";}

    /** Encode an initial group or a follow-up {group,priorDraft,supplement}; reuse this instance across rounds. */
    JsonNode encode(Object payload){
        JsonNode copy=JsonUtils.mapper().valueToTree(payload);if(!copy.isObject())return copy;
        if(copy.has("group")){registerGroup(copy.path("group"));registerSupplement(copy.path("supplement"));groupFields(copy.path("group"));draftFields(copy.path("priorDraft"),true);supplementFields(copy.path("supplement"));}
        else {registerGroup(copy);groupFields(copy);}return copy;
    }
    /** Unknown labels stay unchanged; known labels in the wrong namespace/source remain explicitly invalid. */
    JsonNode decode(JsonNode response){feedback.clear();JsonNode copy=response.deepCopy();draftFields(copy,false);return copy;}
    /** Diagnostics for the most recent response only; rows and choices are immutable wire metadata. */
    List<Object> requestFeedback(){return new ArrayList<>(feedback);}
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
        for(JsonNode row:group.path("instructions")){
            String source=row.path("sourceId").asText(),owner=row.path("sourceTaskId").asText();
            allow(owner,instructionKind(row.path("kind").asText()),source,row.path("sourceObservationId").asText());
            for(JsonNode ref:row.path("evidenceRefs"))allowRef(owner,ref.asText());
            for(JsonNode id:row.path("scopeObligationIds"))allow(owner,"scope",source,id.asText());
            for(JsonNode id:row.path("unresolvedConditionIds"))allow(owner,"condition",source,id.asText());
            JsonNode alignment=row.path("currentTemplateAlignment");for(JsonNode ref:alignment.path("currentEvidenceRefs"))allowRef(owner,ref.asText());for(JsonNode id:alignment.path("currentScopeIds"))allow(owner,"scope",source,id.asText());
            for(JsonNode evidence:group.path("sharedEvidence"))if(owns(owner,identities.get(evidence.path("kind").asText()+"|"+evidence.path("sourceId").asText()+"|"+evidence.path("id").asText())))registerEvidence(evidence,source,evidence.path("kind").asText(),owner);
        }
    }
    private void registerAlignment(JsonNode row,String source){for(JsonNode ref:row.path("currentEvidenceRefs"))registerRef(ref.asText());registerArray(row,"currentScopeIds","scope",source);}
    private void registerSupplement(JsonNode supplement){
        for(JsonNode row:supplement.path("currentSourceContext"))registerContext(row.path("context"),row.path("sourceTaskId").asText());
        for(JsonNode row:supplement.path("sourceSearchResults"))registerContext(row.path("search"),row.path("sourceTaskId").asText());
    }
    private void registerContext(JsonNode context,String owner){String source=sourceOf(owner);if(source.isEmpty()||!source.equals(context.path("sourceId").asText(source)))return;for(String field:Arrays.asList("items","results"))for(JsonNode row:context.path(field))registerEvidence(row,source,row.path("kind").asText(),owner);}
    private void registerEvidence(JsonNode row,String source,String kind){registerEvidence(row,source,kind,"");}
    private void registerEvidence(JsonNode row,String source,String kind,String owner){
        register(kind,source,row.path("id").asText(),owner);registerRef(row.path("ref").asText());allowRef(owner,row.path("ref").asText());
        for(String field:Arrays.asList("guidanceNoteId","parentGuidanceNoteId","childGuidanceNoteId"))register("guidance",source,row.path(field).asText(),owner);registerArray(row,"guidanceNoteIds","guidance",source,owner);
        for(String field:Arrays.asList("conditionId","parentConditionId","childConditionId"))register("condition",source,row.path(field).asText(),owner);registerArray(row,"conditionIds","condition",source,owner);
        for(String field:Arrays.asList("parentGroupId","childGroupId"))register("scope",source,row.path(field).asText(),owner);registerArray(row,"groupIds","scope",source,owner);
        register("member",source,row.path("memberId").asText(),owner);registerArray(row,"orderedMemberIds","member",source,owner);for(JsonNode range:row.path("ranges"))register("member",source,range.path("memberId").asText(),owner);
        for(JsonNode adjacent:row.path("adjacentMembers"))register("member",source,adjacent.path("id").asText(),owner);
    }
    private void registerArray(JsonNode row,String field,String kind,String source,String owner){for(JsonNode id:row.path(field))register(kind,source,id.asText(),owner);}
    private void register(String kind,String source,String id,String owner){register(kind,source,id);allow(owner,kind,source,id);}
    private void allowRef(String owner,String value){String[] parts=value.split("\\|",3);if(parts.length==3)allow(owner,parts[1],parts[0],parts[2]);}
    private void allow(String owner,String kind,String source,String id){Reference ref=identities.get(kind+"|"+source+"|"+id);if(ref!=null&&!owner.isEmpty()&&source.equals(sourceOf(owner))&&!"task".equals(kind))taskReferences.computeIfAbsent(owner,key->new LinkedHashSet<>()).add(ref.alias);}
    private boolean owns(String owner,Reference ref){return ref!=null&&ref.source.equals(sourceOf(owner))&&taskReferences.getOrDefault(owner,Collections.emptySet()).contains(ref.alias);}
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
        for(String field:Arrays.asList("localEffectsExample","knownScopeRuleExample"))draftFields(supplement.path("scopeRepairProtocol").path(field),true);
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
        for(String part:Arrays.asList("sourceContextRequests","sourceSearchRequests")){int index=0;for(JsonNode row:draft.path(part)){String owner=actualTask(row.path("sourceTaskId").asText(),encode),source=sourceOf(owner);field(row,"sourceTaskId","task",source,encode);if("sourceContextRequests".equals(part)){if(encode)field(row,"id",row.path("kind").asText(),source,true);else decodeRequest(row,owner,index);}index++;}}
    }
    private void decodeRequest(JsonNode row,String owner,int index){
        String kind=row.path("kind").asText(),value=row.path("id").asText(),source=sourceOf(owner);Reference ref=aliases.get(value);if(ref==null)ref=identities.get(kind+"|"+source+"|"+value);
        String error="";
        if(source.isEmpty())error="source_request_owner_unavailable";
        else if(!CONTEXT_KINDS.contains(kind)||value.isEmpty())error="source_request_arguments_invalid";
        else if(ref==null)error="source_request_reference_unknown";
        else if(!owns(owner,ref))error="source_request_reference_unavailable_for_owner";
        else if(!CONTEXT_KINDS.contains(ref.kind))error="source_request_reference_kind_unsupported";
        else if(!kind.equals(ref.kind))error="source_request_kind_mismatch";
        if(!error.isEmpty()){
            if(row.isObject()&&ref!=null)((ObjectNode)row).put("id",invalidAlias(value));
            if(index<MAX_REQUEST_FEEDBACK&&feedback.size()<MAX_REQUEST_FEEDBACK){List<Object> choices=new ArrayList<>();if("source_request_kind_mismatch".equals(error))choices.add(choice(ref));else if(!source.isEmpty())for(int pass=0;pass<2;pass++)for(String alias:taskReferences.getOrDefault(owner,Collections.emptySet())){Reference candidate=aliases.get(alias);if(choices.size()<MAX_REQUEST_CHOICES&&CONTEXT_KINDS.contains(candidate.kind)&&(pass==0)==kind.equals(candidate.kind))choices.add(choice(candidate));}
                feedback.add(Collections.unmodifiableMap(obj("requestIndex",index,"sourceTaskId",source.isEmpty()?"":translate("task",source,owner,true),"reason",error,"choices",Collections.unmodifiableList(choices))));}
            return;
        }
        if(row.isObject())((ObjectNode)row).put("id",ref.id);
    }
    private Map<String,Object> choice(Reference ref){return Collections.unmodifiableMap(obj("kind",ref.kind,"id",ref.alias));}
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
