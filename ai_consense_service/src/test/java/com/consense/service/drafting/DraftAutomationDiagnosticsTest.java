package com.consense.service.drafting;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DraftAutomationDiagnosticsTest {
    private DraftAutomationRun run(){DraftAutomationRun run=new DraftAutomationRun();run.setId("run-A");run.setProjectId("project-A");run.setStatus("PARTIAL");run.setPhase("READY_FOR_REVIEW");run.setProfileId("local");run.setConfigurationSha256("config-A");run.setCreatedAt(Instant.EPOCH);run.setUpdatedAt(Instant.EPOCH);run.setSourceStampJson("[[42,\"STANDARD_TEMPLATE\",\"SCC\",\"source-hash\",\"PARSED\",\"D:/private/source.docx\"]]");return run;}
    private DraftAutomationItem item(String id,String status,String receipt){DraftAutomationItem item=new DraftAutomationItem();item.setId(id);item.setRunId("run-A");item.setTaskId(id);item.setStatus(status);item.setTaskJson("{\"taskId\":\""+id+"\"}");item.setReceiptJson(receipt);return item;}
    @Test void exportsStoredReceiptsOnceAndOnlySafeIdentityFields(){
        String receipt="{\"systemPrompt\":\"initial system\",\"userPrompt\":\"initial source\",\"rawResponse\":\"model raw\",\"diagnostics\":{\"rounds\":[]},\"stopReasons\":[\"WRONG_OWNER\"],\"evidenceRounds\":[{\"inputSha256\":\"follow-hash\",\"rawResponse\":\"follow raw\"}]}";
        byte[] bytes=DraftAutomationDiagnostics.encode(run(),Arrays.asList(item("group","DONE",receipt),item("child","UNKNOWN","{\"groupReceiptId\":\"group\",\"modelCalls\":0}")),new ModelIdentity("local","openai-compatible","27b","config-A","configured_profile"));
        JsonNode report=JsonUtils.parse(new String(bytes,StandardCharsets.UTF_8));assertEquals(DraftAutomationDiagnostics.SCHEMA,report.path("schema").asText());assertFalse(report.path("replayed").asBoolean());assertEquals("PARTIAL",report.path("run").path("status").asText());
        assertEquals(JsonUtils.parse(receipt),report.path("items").get(0).path("receipt"));assertEquals("UNKNOWN",report.path("items").get(1).path("status").asText());assertFalse(report.path("items").get(1).path("receipt").has("rawResponse"));assertEquals("27b",report.path("modelIdentity").path("model").asText());
        assertEquals("source-hash",report.path("sources").get(0).path("sourceSha256").asText());assertFalse(new String(bytes,StandardCharsets.UTF_8).contains("D:/private"));assertFalse(report.has("sourceStampJson"));assertFalse(report.path("modelIdentity").has("baseUrl"));
    }
    @Test void aChangedConfigurationCannotInventTheHistoricalModelName(){
        JsonNode report=JsonUtils.parse(new String(DraftAutomationDiagnostics.encode(run(),Collections.emptyList(),new ModelIdentity("local","provider","different-model","config-B","configured_profile")),StandardCharsets.UTF_8));
        assertEquals("config-A",report.path("modelIdentity").path("configurationSha256").asText());assertFalse(report.path("modelIdentity").path("matchesCurrentConfiguration").asBoolean());assertFalse(report.path("modelIdentity").has("model"));
    }
    @Test void ownershipAndActiveRunGuardsApplyBeforeReadingAnyReceipt(){
        DraftAutomationRunRepository runs=mock(DraftAutomationRunRepository.class);DraftAutomationItemRepository items=mock(DraftAutomationItemRepository.class);ProjectService projects=mock(ProjectService.class);LlmProfiles profiles=mock(LlmProfiles.class);AiGateway ai=mock(AiGateway.class);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        DraftAutomationService service=new DraftAutomationService(runs,items,null,null,null,projects,profiles,ai,null,null,null,manager);
        DraftAutomationRun row=run();when(runs.findById("run-A")).thenReturn(Optional.of(row));when(runs.lockRun("run-A")).thenReturn(Optional.of(row));
        assertThrows(BizException.class,()->service.diagnostics("other-project","run-A"));verifyNoInteractions(items,profiles,ai);verify(runs,never()).lockRun(anyString());
        row.setStatus("RUNNING");assertThrows(BizException.class,()->service.diagnostics("project-A","run-A"));verifyNoInteractions(items,profiles,ai);
        row.setStatus("INTERRUPTED");when(items.findByRunIdOrderByTaskOrderAsc("run-A")).thenReturn(Collections.singletonList(item("unknown","UNKNOWN",null)));
        assertNotNull(service.diagnostics("project-A","run-A"));verify(items,times(1)).findByRunIdOrderByTaskOrderAsc("run-A");verifyNoInteractions(ai);verify(items,never()).save(any());verify(runs,never()).save(any());
        service.shutdown();
    }
}
