package com.consense.service.drafting;

import com.consense.ai.ModelIdentity;
import com.consense.common.JsonUtils;
import com.consense.domain.DraftAutomationItem;
import com.consense.domain.DraftAutomationRun;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Click-only export of stored evidence; never replays or repairs a model call. */
final class DraftAutomationDiagnostics {
    static final String SCHEMA="consense-draft-automation-diagnostics-v1";
    private DraftAutomationDiagnostics(){}

    static byte[] encode(DraftAutomationRun run,List<DraftAutomationItem> items,ModelIdentity configured) {
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream();JsonGenerator out=JsonUtils.mapper().getFactory().createGenerator(bytes)){
            out.writeStartObject();out.writeStringField("schema",SCHEMA);out.writeStringField("exportedAt",Instant.now().toString());
            out.writeStringField("projectId",run.getProjectId());out.writeStringField("runId",run.getId());
            out.writeStringField("evidenceScope","persisted_only");out.writeBooleanField("replayed",false);
            out.writeStringField("limitations","Only persisted receipts are exported. Missing or UNKNOWN responses remain unknown. Historical follow-up inputs may not be reconstructable; an input hash alone is not the original input.");
            out.writeObjectFieldStart("run");out.writeStringField("status",run.getStatus());out.writeStringField("phase",run.getPhase());
            out.writeStringField("message",run.getMessage());out.writeStringField("currentFile",run.getCurrentFile());
            out.writeStringField("expectedCurrentRevisionId",run.getExpectedCurrentRevisionId());out.writeStringField("expectedDraftRevisionId",run.getExpectedDraftRevisionId());out.writeStringField("resultRevisionId",run.getResultRevisionId());
            out.writeObjectField("createdAt",run.getCreatedAt());out.writeObjectField("updatedAt",run.getUpdatedAt());
            out.writeNumberField("completedItems",run.getCompletedItems());out.writeNumberField("totalItems",run.getTotalItems());out.writeNumberField("failedItems",run.getFailedItems());out.writeNumberField("unresolvedItems",run.getUnresolvedItems());out.writeNumberField("requirementsCount",run.getRequirementsCount());out.writeNumberField("nodesCount",run.getNodesCount());out.writeEndObject();
            out.writeObjectFieldStart("modelIdentity");out.writeStringField("profileId",run.getProfileId());out.writeStringField("configurationSha256",run.getConfigurationSha256());
            boolean matches=configured!=null&&Objects.equals(run.getProfileId(),configured.getProfileId())&&Objects.equals(run.getConfigurationSha256(),configured.getConfigurationSha256());
            out.writeBooleanField("matchesCurrentConfiguration",matches);out.writeStringField("identityScope",matches?"matching_configured_profile":"stored_profile_and_configuration_hash_only");
            if(matches){out.writeStringField("provider",configured.getProvider());out.writeStringField("model",configured.getModel());}out.writeEndObject();
            // The persisted dispatch stamp also contains storage paths. Project only source identity fields.
            out.writeArrayFieldStart("sources");if(run.getSourceStampJson()!=null)for(JsonNode stamp:JsonUtils.parse(run.getSourceStampJson())){
                if(!stamp.isArray()||stamp.size()<5)continue;out.writeStartObject();String[] keys={"sourceId","category","fileKey","sourceSha256","parseStatus"};for(int i=0;i<keys.length;i++)out.writeObjectField(keys[i],stamp.get(i));out.writeEndObject();
            }out.writeEndArray();
            raw(out,"manifest",run.getManifestJson());out.writeArrayFieldStart("items");
            for(DraftAutomationItem item:items){out.writeStartObject();out.writeStringField("id",item.getId());out.writeStringField("taskId",item.getTaskId());out.writeNumberField("taskOrder",item.getTaskOrder());out.writeStringField("status",item.getStatus());raw(out,"task",item.getTaskJson());raw(out,"receipt",item.getReceiptJson());out.writeEndObject();}
            out.writeEndArray();out.writeEndObject();out.flush();return bytes.toByteArray();
        }catch(IOException error){throw new IllegalStateException("Cannot export saved automation diagnostics",error);}
    }
    /** Stored JSON is already validated on persistence; avoid another large parse/copy during export. */
    private static void raw(JsonGenerator out,String key,String json)throws IOException{out.writeFieldName(key);if(json==null)out.writeNull();else out.writeRawValue(json);}
}
