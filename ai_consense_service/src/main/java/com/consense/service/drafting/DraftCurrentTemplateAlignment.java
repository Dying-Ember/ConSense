package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Current instruction references only. Neither a definition confirmation nor permission to edit. */
final class DraftCurrentTemplateAlignment {
    private DraftCurrentTemplateAlignment() { }
    static final String REGISTERED_INCOMPLETE_NTT17_REFERENCE="REGISTERED_INCOMPLETE_NTT17_DEFINITION_REFERENCE";

    /** Keep one index per saved reading in the discovery plan, not in a process-wide source cache. */
    static Index index(JsonNode reading) { return new Index(reading); }

    static final class Index {
        private final JsonNode reading;
        private final Map<String,JsonNode> notes=new LinkedHashMap<>(),members=new LinkedHashMap<>();
        private final Set<String> duplicateIds=new HashSet<>();
        private final Map<String,Integer> instructionOccurrences=new HashMap<>();
        private final List<Reference> references;

        private Index(JsonNode reading) {
            this.reading=reading;
            for(JsonNode note:reading.path("guidanceNotes")) {
                add(notes,note);String instruction=normalize(note.path("text").asText());
                instructionOccurrences.merge(instruction,1,Integer::sum);
            }
            for(JsonNode member:reading.path("sourceMembers"))add(members,member);
            Set<String> seen=new HashSet<>();
            for(String collection:Arrays.asList("guidanceNotes","markerCandidates","groups","conditions","sourceMembers"))
                for(JsonNode observation:reading.path(collection))if(!seen.add(observation.path("id").asText()))duplicateIds.add(observation.path("id").asText());
            references=References.BY_DOCUMENT.getOrDefault(reading.path("fileKey").asText(),Collections.emptyList());
        }

        /** Consume the existing task payload; do not copy its text, source members or old coordinates. */
        Map<String,Object> align(Map<String,Object> task,Map<String,Object> payload) {
            String source=reading.path("id").asText(),id=text(task.get("sourceObservationId"));
            Map<String,Object> result=obj("sourceTaskId",task.get("taskId"),"sourceId",source,
                    "sourceSha256",reading.path("sourceSha256").asText(),"ruleVersion",DraftBusinessRules.RULE_VERSION,
                    "definitionStatus","unmatched","conditionStatus","unknown","scopeStatus","requires_review",
                    "referenceKind","current_instruction_reference","editableAuthority",false,"modelRequired",true,"fixedRootKeys",new ArrayList<>(),"fixedActionReferences",new ArrayList<>(),
                    "currentEvidenceRefs",new ArrayList<>(),"currentScopeIds",new ArrayList<>(),"reasonCodes",new ArrayList<>());
            List<Object> reasons=list(result.get("reasonCodes"));
            if(!"bound".equals(reading.path("evidenceStatus").asText())||source.isEmpty()
                    ||!source.equals(text(task.get("sourceId")))||reading.path("sourceSha256").asText().isEmpty()
                    ||!reading.path("sourceSha256").asText().equals(text(task.get("sourceSha256")))) {
                reasons.add("CURRENT_SOURCE_IDENTITY_UNPROVEN");return result;
            }
            JsonNode note=notes.get(id),instruction=JsonUtils.mapper().valueToTree(payload.get("instruction"));
            if(!"guidance".equals(task.get("itemKind"))||note==null||duplicateIds.contains(id)
                    ||!id.equals(instruction.path("id").asText())||!note.path("text").equals(instruction.path("text"))) {
                reasons.add("COMPLETE_CURRENT_GUIDANCE_REQUIRED");return result;
            }
            boolean registeredIncomplete=registeredIncompleteNtt17(reading,note);
            if(!note.path("captureComplete").asBoolean(false)&&!registeredIncomplete){reasons.add("COMPLETE_CURRENT_GUIDANCE_REQUIRED");return result;}
            String normalized=normalize(note.path("text").asText());List<Reference> matched=new ArrayList<>();
            for(Reference reference:references)if(normalized.equals(reference.instruction))matched.add(reference);
            if(!currentObservation(note,note.path("text").asText())) {
                reasons.add("CURRENT_INSTRUCTION_MEMBERS_UNPROVEN");return result;
            }
            if(matched.isEmpty()) {
                String business=businessDefinition(normalized,payload);
                if(business.isEmpty()){reasons.add("NO_EXACT_REGISTERED_INSTRUCTION");return result;}
                list(result.get("fixedRootKeys")).add(business);list(result.get("currentEvidenceRefs")).add(source+"|guidance|"+id);
                result.put("definitionStatus","definition_reference_only");
                result.put("fixedBusinessDefinitionReferences",Collections.singletonList(obj("baselineKey",business,"referenceKind","fixed_definition_reference","editableAuthority",false)));
                reasons.add("REGISTERED_CURRENT_BUSINESS_DEFINITION");reasons.add("CURRENT_SCOPE_REVIEW_REQUIRED");reasons.add("REFERENCE_REQUIRES_MODEL_AND_HUMAN_REVIEW");
                result.put("notice","The complete current instruction and its own relevant source context supply a business definition reference. Project answers are expected to be unknown. Current conditions and edit ranges remain unconfirmed.");return result;
            }
            List<Object> actions=list(result.get("fixedActionReferences")),roots=list(result.get("fixedRootKeys")),refs=list(result.get("currentEvidenceRefs"));
            refs.add(source+"|guidance|"+id);
            if(registeredIncomplete){
                Set<String> common=new LinkedHashSet<>(matched.get(0).keys);for(Reference reference:matched)common.retainAll(reference.keys);roots.addAll(common);
                result.put("definitionStatus","definition_reference_only");reasons.add("COMPLETE_CURRENT_GUIDANCE_REQUIRED");reasons.add(REGISTERED_INCOMPLETE_NTT17_REFERENCE);reasons.add("REFERENCE_REQUIRES_MODEL_AND_HUMAN_REVIEW");
                result.put("notice","The registered original NTT17 guidance is itself unclosed. Its complete observed text supplies a pending definition reference only; the incomplete capture and all scope/condition review remain unresolved.");return result;
            }
            List<Reference> scoped=new ArrayList<>();for(Reference reference:matched)if(actionScopeMatches(text(reference.action.get("actionId")),payload))scoped.add(reference);
            for(Reference reference:scoped) {
                Map<String,Object> action=new LinkedHashMap<>(reference.action);action.put("inputKeys",new ArrayList<>(reference.keys));
                actions.add(action);for(String key:reference.keys)if(!roots.contains(key))roots.add(key);
            }
            if(scoped.isEmpty()){
                Set<String> common=new LinkedHashSet<>(matched.get(0).keys);for(Reference reference:matched)common.retainAll(reference.keys);roots.addAll(common);
                reasons.add("CURRENT_ACTION_TARGET_UNPROVEN");
                // A proven instruction may explain its business action without lending the old target's edit authority.
                if(matched.size()==1){
                    Map<String,Object> semantic=new LinkedHashMap<>(matched.get(0).action);
                    semantic.put("referenceKind","fixed_action_semantic_reference");semantic.put("editableAuthority",false);semantic.put("scopeStatus","requires_review");
                    semantic.put("notice","Business meaning only: the current action target is unproven. An instruction to amend does not by itself authorize deletion. Do not bind replacement wording for a whole provision to a partial current scope. Request current parent/member evidence through the existing source tools; if the amendment range or wording cannot be reconciled, retain check rather than inventing a treatment.");
                    result.put("fixedActionSemanticReferences",Collections.singletonList(semantic));
                }
            }
            boolean repeated=instructionOccurrences.getOrDefault(normalized,0)!=1,ambiguous=scoped.size()>1;
            result.put("definitionStatus",scoped.isEmpty()?"definition_reference_only":ambiguous?"ambiguous_instruction_reference":"exact_instruction_reference");
            if(repeated)reasons.add("REPEATED_CURRENT_INSTRUCTION");if(matched.size()>1)reasons.add("MULTIPLE_FIXED_REFERENCES");
            if(scoped.size()==1&&(repeated||matched.size()>1))reasons.add("CURRENT_TARGET_DISAMBIGUATES_INSTRUCTION");

            List<Object> scopes=list(payload.get("scopeCandidates"));boolean completeScopes=!scopes.isEmpty();
            for(Object raw:scopes) {
                JsonNode scope=JsonUtils.mapper().valueToTree(raw);String scopeId=scope.path("id").asText();
                if(scopeId.isEmpty()){completeScopes=false;continue;}
                list(result.get("currentScopeIds")).add(scopeId);refs.add(source+"|scope|"+scopeId);
                completeScopes &= scope.path("scopeComplete").asBoolean(false)
                        && currentObservation(scope.path("observation"),scope.path("text").asText());
            }
            // Complete OER observations still need the native binder and human scope/condition review.
            if(completeScopes&&!ambiguous&&!scoped.isEmpty())result.put("scopeStatus","current_observation_complete");
            else reasons.add("CURRENT_SCOPE_REVIEW_REQUIRED");

            List<Object> conditions=list(payload.get("sourceConditions"));boolean completeConditions=!conditions.isEmpty()
                    &&list(payload.get("unresolvedConditionIds")).isEmpty();
            for(Object raw:conditions) {
                JsonNode condition=JsonUtils.mapper().valueToTree(raw);String conditionId=condition.path("id").asText();
                if(!conditionId.isEmpty())refs.add(source+"|condition|"+conditionId);
                completeConditions &= !conditionId.isEmpty()&&condition.path("sourceSyntaxComplete").asBoolean(false)
                        &&currentObservation(condition,condition.path("text").asText());
            }
            result.put("conditionStatus",completeConditions?"current_evidence_complete":"unknown");
            result.put("notice","The same complete instruction supplies a definition reference only. Conditions and current scopes still require model and human review.");
            reasons.add("REFERENCE_REQUIRES_MODEL_AND_HUMAN_REVIEW");
            return result;
        }

        private String businessDefinition(String instruction,Map<String,Object> payload) {
            if(!"SCT".equals(reading.path("fileKey").asText()))return "";
            if(instruction.equals(standardInstruction(189,190)))return "billNos";
            // Match the complete registered wording, not the current paragraph number or a shared topic.
            // These notes ask the same domestic-block fact; their clauses and edit scopes remain separate.
            if(instruction.equals(standardInstruction(547))||instruction.equals(standardInstruction(859))||instruction.equals(standardInstruction(895)))return "domesticBlocks";
            boolean electronic=false,paper=false;
            for(Object raw:list(payload.get("scopeCandidates"))) {
                JsonNode scope=JsonUtils.mapper().valueToTree(raw);String full=scope.path("text").asText();
                if(!currentObservation(scope.path("observation"),full))continue;
                String body=normalize(full);if(!body.contains("Bills of Quantities"))continue;
                electronic|=body.contains("L10Pro");paper|=body.contains("issued in hard copy");
            }
            // A generic a/b selection needs both BQ alternatives in this task's own current context.
            if((instruction.equals(standardInstruction(55))||instruction.equals(standardInstruction(476)))&&electronic&&paper)return "electronicTendering";
            if(instruction.equals(standardInstruction(60,65,66))&&electronic)return "electronicTendering";
            if((instruction.equals(standardInstruction(231,236))||instruction.equals(standardInstruction(518,523)))&&paper)return "electronicTendering";
            return "";
        }

        /** A note or historical paragraph topic alone cannot identify an action's present edit object. */
        boolean actionScopeMatches(String actionId,Map<String,Object> payload){
            if(!"bound".equals(reading.path("evidenceStatus").asText()))return false;
            List<String> expected=References.TARGETS.getOrDefault(reading.path("fileKey").asText()+"|"+actionId,Collections.emptyList());
            List<Object> scopes=list(payload.get("scopeCandidates"));if(expected.isEmpty()||scopes.isEmpty())return false;
            for(Object raw:scopes){JsonNode scope=JsonUtils.mapper().valueToTree(raw);String full=scope.path("text").asText();
                if(!scope.path("scopeComplete").asBoolean(false)||!currentObservation(scope.path("observation"),full)||!expected.contains(normalizeTarget(full)))return false;
            }return true;
        }

        private void add(Map<String,JsonNode> index,JsonNode value) {
            String id=value.path("id").asText();if(id.isEmpty()||index.putIfAbsent(id,value)!=null)duplicateIds.add(id);
        }

        private boolean currentObservation(JsonNode observation,String full) {
            if(full.isEmpty()||observation.path("id").asText().isEmpty()||duplicateIds.contains(observation.path("id").asText()))return false;
            JsonNode ranges=DraftReviewSources.observationRanges(observation);
            List<String> pieces=new ArrayList<>();
            if(ranges.isEmpty()) {
                for(JsonNode memberId:observation.path("orderedMemberIds")) {
                    JsonNode member=members.get(memberId.asText());if(!currentMember(member))return false;
                    String part=memberText(member,"text");if(part==null)return false;pieces.add(part);
                }
            } else for(JsonNode range:ranges) {
                JsonNode member=members.get(range.path("memberId").asText());if(!currentMember(member))return false;
                String channel=range.path("channel").asText("text"),part=memberText(member,channel);
                if(part==null||!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber()
                        ||!range.path("start").canConvertToInt()||!range.path("end").canConvertToInt())return false;
                int start=range.path("start").asInt(),end=range.path("end").asInt();
                if(start<0||end<start||end>part.length())return false;
                String selected=part.substring(start,end);
                if(range.has("observedText")&&!selected.equals(range.path("observedText").asText()))return false;
                pieces.add(selected);
            }
            return !pieces.isEmpty()&&full.equals(String.join("\n",pieces));
        }

        private boolean currentMember(JsonNode member) {
            if(member==null||duplicateIds.contains(member.path("id").asText()))return false;
            // These explicit registrations are from native Word sources; PDF references need their own evidence mapping.
            JsonNode position=member.path("sourcePosition");return "docx".equals(reading.path("format").asText())
                    &&reading.path("sourceSha256").equals(position.path("sourceSha256"))
                    &&!position.path("partName").asText().isEmpty()&&!position.path("nodePath").asText().isEmpty();
        }
    }

    /** Only the exact registered native NTT17 source defect can supply an incomplete definition reference. */
    static boolean registeredIncompleteNtt17(JsonNode reading,JsonNode note){
        if(!"NTT".equals(reading.path("fileKey").asText())||!"docx".equals(reading.path("format").asText())||!note.path("captureComplete").isBoolean()||note.path("captureComplete").asBoolean()
                ||!note.path("sourceSyntaxKnown").isBoolean()||!note.path("sourceSyntaxKnown").asBoolean()||!note.path("sourceSyntaxComplete").isBoolean()||note.path("sourceSyntaxComplete").asBoolean())return false;
        String observed=normalize(note.path("text").asText());for(Reference reference:References.BY_DOCUMENT.getOrDefault("NTT",Collections.emptyList()))if("NTT-17-RAILWAY".equals(reference.action.get("actionId"))&&observed.equals(reference.instruction))return true;return false;
    }

    private static String memberText(JsonNode member,String channel) {
        String prefix="preNormalizedTextObservation.",key=channel.startsWith(prefix)?channel.substring(prefix.length()):channel;
        if(!Arrays.asList("text","originalText","deletedText","strikeText").contains(key))return null;
        JsonNode value=member.path(channel.startsWith(prefix)?"preNormalizedTextObservation":"textObservation").path(key);
        return value.isTextual()?value.asText():null;
    }

    // Layout whitespace alone may differ. Negation, numbers, object wording and punctuation remain significant.
    private static String normalize(String value) { return value.replaceAll("[\\s\\u00a0]+"," ").trim(); }
    private static String standardInstruction(int... paragraphs){List<String> parts=new ArrayList<>();for(int paragraph:paragraphs){String value=DraftBusinessRules.standardParagraph("SCT",paragraph);if(value.isEmpty())return "";parts.add(value);}return normalize(String.join("\n",parts));}
    private static String normalizeTarget(String value){return normalize(value).replaceAll("\\) (?=\\p{L})",")");}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return value instanceof List?(List<Object>)value:Collections.emptyList(); }

    private static final class References {
        static final Map<String,List<String>> TARGETS=targets();
        static final Map<String,List<Reference>> BY_DOCUMENT=load();
        private static Map<String,List<Reference>> load() {
            Map<String,List<Reference>> result=new LinkedHashMap<>();Map<String,Map<String,Object>> definitions=DraftCatalogAlignment.definitions();
            for(Object raw:list(DraftBusinessRules.plan(Collections.emptyMap()).get("actions"))) {
                Map<String,Object> action=map(raw);String actionId=text(action.get("id")),document=text(action.get("document"));
                DraftSourceAnchors.Target target=DraftSourceAnchors.target(actionId);int[] guide=target==null?new int[0]:target.guidance();
                if("NTT-2-L10PRO".equals(actionId))guide=new int[]{107,108,109};if("NTT-2-PAPER".equals(actionId))guide=new int[]{135};
                if("NTT-9-WTO".equals(actionId))guide=new int[]{439};if("NTT-17-RAILWAY".equals(actionId))guide=new int[]{614,617,620};
                if(guide.length==0||"SourceCheck".equals(action.get("pendingKind")))continue;
                List<String> guidance=new ArrayList<>();boolean complete=true;
                for(int ordinal:guide){String value=DraftBusinessRules.standardParagraph(document,ordinal);complete &= !value.isEmpty();guidance.add(value);}
                Set<String> keys=new LinkedHashSet<>();for(Object rawKey:list(action.get("inputKeys"))) {
                    String key=text(rawKey);if(key.contains("."))key=key.substring(0,key.indexOf('.'));
                    Map<String,Object> definition=definitions.get(key);if(definition!=null&&!Boolean.TRUE.equals(definition.get("hidden")))keys.add(key);
                }
                if(!complete||keys.isEmpty())continue;
                Map<String,Object> reference=obj("actionId",actionId,"document",document,"historicalClause",action.get("clause"),
                        "inputKeys",new ArrayList<>(keys),"detailEn",map(action.get("detail")).get("en"));
                result.computeIfAbsent(document,ignored->new ArrayList<>()).add(new Reference(normalize(String.join("\n",guidance)),keys,reference));
            }
            return result;
        }
        private static Map<String,List<String>> targets(){
            Map<String,List<String>> result=new LinkedHashMap<>();for(Object raw:list(DraftBusinessRules.plan(Collections.emptyMap()).get("actions"))){
                Map<String,Object> action=map(raw);String id=text(action.get("id")),document=text(action.get("document"));List<int[]> blocks=new ArrayList<>();DraftSourceAnchors.Target target=DraftSourceAnchors.target(id);
                if(target!=null){blocks.add(target.substantive());for(int ordinal:target.substantive())blocks.add(new int[]{ordinal});}
                // These complete dissemination alternatives are distinct from the similarly labelled NTT 3(c) return paragraphs.
                if("NTT-2-L10PRO".equals(id)){blocks.add(new int[]{97,98,99,100,101,102,103,104,105,106});blocks.add(new int[]{117,118,119,120});blocks.add(new int[]{97,98,99,100,101,102,103,104,105,106,117,118,119,120});}
                if("NTT-2-PAPER".equals(id))blocks.add(new int[]{127,128,129,130,131,132,133,134});
                if("NTT-9-WTO".equals(id))blocks.add(new int[]{435,436,438,445,453,461,469});
                if("NTT-17-RAILWAY".equals(id))blocks.add(new int[]{612,613,619});
                List<String> texts=new ArrayList<>();for(int[] block:blocks){StringBuilder text=new StringBuilder();for(int ordinal:block){
                    // NTT P469 uses native numId35 / abstract7: start=5, lowerLetter, (%1).
                    if("NTT-9-WTO".equals(id)&&ordinal==469)text.append("(e)");
                    text.append(DraftBusinessRules.standardParagraph(document,ordinal)).append('\n');
                }String normalized=normalizeTarget(text.toString());if(!normalized.isEmpty()&&!texts.contains(normalized))texts.add(normalized);}
                if(!texts.isEmpty())result.put(document+"|"+id,Collections.unmodifiableList(texts));
            }return Collections.unmodifiableMap(result);
        }
    }

    private static final class Reference {
        final String instruction;final Set<String> keys;final Map<String,Object> action;
        Reference(String instruction,Set<String> keys,Map<String,Object> action){this.instruction=instruction;this.keys=keys;this.action=action;}
    }
}
