package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.service.vetting.VettingSemanticReview.ChecklistAssessment;
import com.consense.service.vetting.VettingSemanticReview.ChecklistFactRef;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.web.dto.VettingDtos.ChecklistAssessmentVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/** Binds model references to current native intervals. Location is never a requirement verdict. */
final class VettingChecklistRequirementRelations {
    static final String VERSION="checklist-native-requirement-relation-v1";
    private static final List<JsonNode> REQUIREMENTS=load();
    private VettingChecklistRequirementRelations() { }

    /** Exact catalogue semantics select the declared boundary; IDs and keyword guesses do not. */
    static JsonNode requirement(VettingChecklistCatalog.Item item) {
        if(item!=null)for(JsonNode row:REQUIREMENTS)if(item.getSection().equals(row.path("section").asText())
                &&item.getClause().equals(row.path("clause").asText())&&item.getDescription().equals(row.path("description").asText()))return row;
        return null;
    }

    static boolean automaticEligible(VettingChecklistCatalog.Item item,String claimStatus) {
        // These explicit predicates still lack independently established field roles/object scope.
        // A model/server-looking status or a matching receipt hash cannot fill that gap.
        // Other requirements retain their existing local gates; old runs are never mass rewritten.
        return requirement(item)==null||"insufficient_evidence".equals(claimStatus);
    }

    static boolean automaticEligible(String id,String status) {
        return automaticEligible(VettingChecklistCatalog.items().stream().filter(item->item.getId().equals(id)).findFirst().orElse(null),status);
    }

    static JsonNode bind(ChecklistAssessment assessment,ChecklistAssessmentVO saved,List<Quote> quotes,
                         Map<String,Chunk> byId,List<SourceDocument> sources) {
        VettingChecklistCatalog.Item item=VettingChecklistCatalog.items().stream().filter(row->row.getId().equals(assessment.getItemId())).findFirst().orElse(null);
        JsonNode definition=requirement(item);if(definition==null)return null;
        ObjectNode relation=object();relation.put("gateVersion",VERSION);relation.put("requirementSha256",VettingChecklistDependencies.requirementHash(item));
        relation.put("predicate",definition.path("kind").asText());relation.put("status","unknown");
        relation.put("policySha256",saved.getPolicySha256());relation.put("packetId",saved.getPacketId());relation.put("sourceSnapshotSha256",saved.getSourceSnapshotSha256());
        relation.put("reasonCode","independent_field_roles_same_object_and_complete_scope_unavailable");
        relation.put("automaticConclusionAllowed",false);relation.put("claimPreserved",true);
        ArrayNode bindings=relation.putArray("sourceBindings"),roles=relation.putArray("requiredSourceRoles");
        for(JsonNode role:definition.path("requiredSourceRoles"))roles.addObject().put("role",role.asText()).put("status","unknown")
                .put("reasonCode","native_location_does_not_establish_requirement_field_role_or_legal_intent");
        if(assessment.getSupport()==null){relation.put("supportStatus","missing_model_fact_references");return relation;}
        if(!definition.path("kind").asText().equals(assessment.getSupport().getKind())){
            relation.put("supportStatus","fact_reference_kind_does_not_match_requirement_predicate");return relation;
        }
        List<ChecklistFactRef> facts=assessment.getSupport().getFacts();
        if(facts==null||facts.isEmpty()||facts.size()>20){relation.put("supportStatus","missing_or_excessive_fact_references");return relation;}
        Set<String> slots=new HashSet<>();boolean bound=true;
        for(ChecklistFactRef fact:facts){ObjectNode binding;
            try{binding=bindFact(fact,quotes,saved,byId,sources);}catch(RuntimeException unavailable){binding=unknown(object().put("bindingStatus","unknown"),"source_metadata_could_not_be_rebound");}
            boolean declared=false;for(JsonNode role:definition.path("requiredSourceRoles"))declared|=fact!=null&&role.asText().equals(fact.getSlot());
            if(!declared||!slots.add(fact==null?null:fact.getSlot()))binding.put("bindingStatus","unknown").put("reasonCode","undeclared_or_duplicate_fact_role");
            bound&="bound_current_native_interval".equals(binding.path("bindingStatus").asText());bindings.add(binding);
        }
        relation.put("supportStatus",bound?"current_native_intervals_bound_roles_and_scope_unverified":"one_or_more_fact_references_unbound");
        return relation;
    }

    /** Exact source re-read, never a model-supplied value, role or relevance flag. */
    private static ObjectNode bindFact(ChecklistFactRef fact,List<Quote> quotes,ChecklistAssessmentVO saved,
                                       Map<String,Chunk> byId,List<SourceDocument> sources) {
        ObjectNode out=object();out.put("bindingStatus","unknown");
        if(fact==null)return unknown(out,"null_fact_reference");out.put("requestedRole",fact.getSlot());
        if(fact.getEvidenceIndex()==null||fact.getNativePositionIndex()==null||fact.getStartUtf16()==null||fact.getEndUtf16()==null
                ||fact.getEvidenceIndex()<0||fact.getEvidenceIndex()>=quotes.size()||saved.getEvidence()==null
                ||fact.getEvidenceIndex()>=saved.getEvidence().size())return unknown(out,"fact_reference_index_unavailable");
        int index=fact.getEvidenceIndex();Quote quote=quotes.get(index);Chunk chunk=byId.get(quote.getChunkId());
        com.consense.web.dto.VettingDtos.FindingEvidence evidence=saved.getEvidence().get(index);
        if(chunk==null||quote.getQuote()==null||saved.getEvidenceChunkIds()==null||saved.getEvidenceChunkIds().size()<=index
                ||!Objects.equals(quote.getChunkId(),saved.getEvidenceChunkIds().get(index))||!Objects.equals(quote.getSide(),evidence.getSide())||!Objects.equals(quote.getQuote(),evidence.getQuote())
                ||!Objects.equals(chunk.getDocumentId(),evidence.getDocumentId())||!Objects.equals(chunk.getRole(),evidence.getSourceRole())
                ||!Objects.equals(chunk.getOriginalSourceSha256(),evidence.getOriginalSourceSha256())||!sha(chunk.getOriginalSourceSha256()))return unknown(out,"quote_or_source_identity_mismatch");
        if(chunk.getCanonicalPayload()!=null&&OerBusinessEvidence.canonicalViewStatus(chunk)!=null)return unknown(out,"canonical_view_changed");
        List<SourceDocument> documents=sources==null?Collections.emptyList():sources.stream().filter(source->Objects.equals(String.valueOf(source.getId()),chunk.getDocumentId())).collect(Collectors.toList());
        if(documents.size()!=1)return unknown(out,"current_source_document_missing_or_ambiguous");SourceDocument source=documents.get(0);
        if(source.getId()==null||JsonUtils.isBlankText(source.getProjectId())||!Objects.equals(source.getSourceSha256(),chunk.getOriginalSourceSha256())||!Objects.equals(VettingCorpus.sourceHash(source),evidence.getSourceHash())
                ||!Objects.equals(VettingCorpus.sourceRole(source),chunk.getRole()))return unknown(out,"current_original_parsed_revision_or_source_role_changed");
        if(chunk.getCanonicalEvidence()!=null&&(!Objects.equals(source.getProjectId(),chunk.getCanonicalEvidence().getProjectId())
                ||!sha(chunk.getCanonicalEvidence().getSourceRevisionSha256())))return unknown(out,"canonical_project_or_source_revision_unavailable");
        int start=fact.getStartUtf16(),end=fact.getEndUtf16();String quoted=quote.getQuote();
        if(start<0||end<=start||end>quoted.length()||splitSurrogate(quoted,start)||splitSurrogate(quoted,end))return unknown(out,"invalid_quote_utf16_interval");
        List<Part> parts=chunk.getParts()==null?Collections.emptyList():chunk.getParts().stream().filter(part->part.getText()!=null
                &&part.getText().indexOf(quoted)>=0&&part.getText().indexOf(quoted)==part.getText().lastIndexOf(quoted)).collect(Collectors.toList());
        if(parts.size()!=1)return unknown(out,"quote_occurrence_missing_or_ambiguous_in_native_parts");Part part=parts.get(0);
        JsonNode position=VettingCorpus.nativePosition(part.getSourcePosition(),source.getSourceSha256());
        if(position==null||!sha(position.path("partSha256").asText())||JsonUtils.isBlankText(position.path("partName").asText())||JsonUtils.isBlankText(position.path("nodePath").asText())
                ||evidence.getNativePositions()==null||fact.getNativePositionIndex()<0||fact.getNativePositionIndex()>=evidence.getNativePositions().size()
                ||!position.equals(evidence.getNativePositions().get(fact.getNativePositionIndex())))return unknown(out,"native_position_reference_unavailable_or_changed");
        List<DocumentBlock> blocks=VettingCorpus.blocks(source).stream().filter(block->Objects.equals(block.getId(),part.getBlockId())
                &&Objects.equals(position,block.getSourcePosition())).collect(Collectors.toList());
        if(blocks.size()!=1)return unknown(out,"current_native_block_missing_or_ambiguous");DocumentBlock block=blocks.get(0);String full=block.getEffectiveText();
        if(part.getStartOffset()<0||part.getEndOffset()>full.length()||part.getEndOffset()<part.getStartOffset()
                ||!full.substring(part.getStartOffset(),part.getEndOffset()).equals(part.getText()))return unknown(out,"native_block_range_or_text_changed");
        int absolute=part.getStartOffset()+part.getText().indexOf(quoted)+start;
        out.put("bindingStatus","bound_current_native_interval");out.put("chunkId",chunk.getId());out.put("documentId",chunk.getDocumentId());out.put("projectId",source.getProjectId());
        out.put("originalSourceSha256",source.getSourceSha256());out.put("javaParsedSourceHash",evidence.getSourceHash());
        out.put("sourceRevisionSha256",chunk.getCanonicalEvidence()==null?evidence.getSourceHash():chunk.getCanonicalEvidence().getSourceRevisionSha256());
        out.set("sourcePosition",position.deepCopy());out.put("nativeBlockId",block.getId());out.put("startUtf16",absolute);out.put("endUtf16",absolute+end-start);
        out.put("sourceText",full.substring(absolute,absolute+end-start));out.put("sourceRole",chunk.getRole());out.put("observedNativeKind",block.getKind());
        if(block.getPageNo()==null)out.putNull("physicalPageNo");else out.put("physicalPageNo",block.getPageNo());
        out.put("completeNativeBlockSelected",part.getStartOffset()==0&&part.getEndOffset()==full.length());
        out.put("requirementFieldRole","unknown");out.put("sameRequirementObjectAndCompleteRange","unknown");
        out.put("originalBytesVerification","existing_source_guard_required_not_inferred_from_hash_equality");return out;
    }

    private static ObjectNode unknown(ObjectNode out,String reason){return out.put("reasonCode",reason);}
    private static boolean splitSurrogate(String text,int at){return at>0&&at<text.length()&&Character.isHighSurrogate(text.charAt(at-1))&&Character.isLowSurrogate(text.charAt(at));}
    private static boolean sha(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static List<JsonNode> load(){
        try(InputStream input=VettingChecklistRequirementRelations.class.getResourceAsStream("/vetting/checklist-requirement-relations-v1.json")){
            if(input==null)throw new IllegalStateException("Checklist requirement relation definitions unavailable");JsonNode root=JsonUtils.mapper().readTree(input);
            if(!"checklist-requirement-relations-v1".equals(root.path("version").asText())||!root.path("requirements").isArray()||root.path("requirements").isEmpty())throw new IllegalStateException("Checklist requirement relation definitions invalid");
            List<JsonNode> rows=new ArrayList<>();Set<String> semantics=new HashSet<>();
            for(JsonNode row:root.path("requirements")){
                String key=JsonUtils.write(Arrays.asList(row.path("section"),row.path("clause"),row.path("description")));
                long matches=VettingChecklistCatalog.items().stream().filter(item->item.getSection().equals(row.path("section").asText())&&item.getClause().equals(row.path("clause").asText())&&item.getDescription().equals(row.path("description").asText())).count();
                if(matches!=1||!semantics.add(key)||!row.path("requiredSourceRoles").isArray()||row.path("requiredSourceRoles").isEmpty())throw new IllegalStateException("Checklist relation definition no longer matches exactly one frozen requirement");rows.add(row.deepCopy());
            }return Collections.unmodifiableList(rows);
        }catch(Exception error){throw new IllegalStateException("Cannot load checklist requirement relation definitions",error);}
    }
}
