package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.domain.DraftAutomationItem;
import com.consense.domain.DraftAutomationRun;
import com.consense.repository.DraftAutomationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.consense.service.drafting.DraftReviewGraph.obj;

/** Stored receipt ordering and the actual stop seam; no product API, model or database calls. */
class DraftAutomationFirstProbeFailureTest {
    private static final String MANIFEST="{\"execution\":\"fixed-root-groups-v1\",\"plan\":{\"revision\":\"test\"},\"groups\":[],\"concurrency\":2}";

    @Test void laterFailureCannotReplaceTheFirstStopAndBothRemainInDiagnostics() throws Exception {
        DraftAutomationRunRepository runs=mock(DraftAutomationRunRepository.class);PlatformTransactionManager manager=manager();AiGateway ai=mock(AiGateway.class);
        DraftAutomationRun run=run();when(runs.lockRun("run")).thenReturn(Optional.of(run));
        DraftAutomationService service=new DraftAutomationService(runs,null,null,null,null,null,null,ai,null,null,null,manager);service.acceptanceProbeStopOnDefect(true);
        Map<String,Object> wto=receipt("group-wto",Arrays.asList("SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED","unsupported_requirement_quote_kind_or_node_reference","group_identified_without_supported_requirement","response_parts_not_retained"));
        Map<String,Object> bq=receipt("group-bq",Collections.singletonList("scope_rule_condition_reference_unresolved"));String wtoBefore=JsonUtils.write(wto),bqBefore=JsonUtils.write(bq);
        try {
            assertTrue(stop(service,wto));assertTrue(run.isCancelRequested());JsonNode first=JsonUtils.parse(run.getManifestJson()).path("firstProbeFailure");
            assertEquals("group-wto",first.path("taskId").asText());assertEquals("GROUP_DISCOVERY",first.path("phase").asText());assertEquals(4,first.path("reasons").size());assertDoesNotThrow(()->Instant.parse(first.path("at").asText()));
            assertTrue(stop(service,bq));assertEquals(first,JsonUtils.parse(run.getManifestJson()).path("firstProbeFailure"));
            String fallback="scope_rule_condition_reference_unresolved",selected=DraftAutomationService.firstProbeReason(run.getManifestJson(),fallback);
            assertTrue(selected.startsWith("SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED"));assertFalse(selected.contains(fallback));assertFalse(selected.contains("response_parts_not_retained"),"The existing three-reason display limit remains");assertTrue(run.getMessage().endsWith(selected));
            run.setStatus("PARTIAL");run.setPhase("READY_FOR_REVIEW");List<DraftAutomationItem> ordered=Arrays.asList(item("group-bq",0,bq),item("group-wto",1,wto));
            JsonNode exported=JsonUtils.parse(new String(DraftAutomationDiagnostics.encode(run,ordered,null),StandardCharsets.UTF_8));
            assertEquals(first,exported.path("manifest").path("firstProbeFailure"));assertEquals(JsonUtils.parse(bqBefore),exported.path("items").path(0).path("receipt"));assertEquals(JsonUtils.parse(wtoBefore),exported.path("items").path(1).path("receipt"));
            assertEquals(wtoBefore,JsonUtils.write(wto));assertEquals(bqBefore,JsonUtils.write(bq));verify(runs,times(2)).lockRun("run");verify(runs,times(2)).save(run);verify(manager,times(2)).commit(any());verifyNoInteractions(ai);
        } finally {service.shutdown();}
    }

    @Test void manifestRewritesPreserveObservationWithoutRelaxingExecutionIdentity() {
        Map<String,Object> first=obj("taskId","group-wto","phase","GROUP_DISCOVERY","reasons",Collections.singletonList("FIRST"),"at",Instant.EPOCH.toString());Map<String,Object> recorded=JsonUtils.readMap(MANIFEST);recorded.put("firstProbeFailure",first);String saved=JsonUtils.write(recorded);
        assertEquals(MANIFEST,DraftAutomationService.executionManifest(saved));assertEquals(MANIFEST,DraftAutomationService.executionManifest(MANIFEST));assertNull(DraftAutomationService.executionManifest(null));
        String rewritten=DraftAutomationService.preserveFirstProbeFailure(MANIFEST,saved);assertEquals(first,JsonUtils.readMap(rewritten).get("firstProbeFailure"));assertEquals(MANIFEST,DraftAutomationService.executionManifest(rewritten));
        Map<String,Object> changed=JsonUtils.readMap(MANIFEST);changed.put("concurrency",1);String changedJson=JsonUtils.write(changed);
        String changedWithObservation=DraftAutomationService.preserveFirstProbeFailure(changedJson,saved);assertEquals(first,JsonUtils.readMap(changedWithObservation).get("firstProbeFailure"));assertEquals(changedJson,DraftAutomationService.executionManifest(changedWithObservation));assertNotEquals(MANIFEST,DraftAutomationService.executionManifest(changedWithObservation));
        assertEquals(MANIFEST,DraftAutomationService.preserveFirstProbeFailure(MANIFEST,null));assertEquals("LEGACY_REASON",DraftAutomationService.firstProbeReason(MANIFEST,"LEGACY_REASON"));assertEquals("LEGACY_REASON",DraftAutomationService.firstProbeReason(null,"LEGACY_REASON"));
    }

    @Test void disabledProbeAndCleanReceiptsDoNotAddAnObservationOrRequestStop() throws Exception {
        DraftAutomationRunRepository runs=mock(DraftAutomationRunRepository.class);AiGateway ai=mock(AiGateway.class);DraftAutomationService service=new DraftAutomationService(runs,null,null,null,null,null,null,ai,null,null,null,manager());
        try {
            assertFalse(stop(service,receipt("group",Collections.singletonList("DEFECT"))));service.acceptanceProbeStopOnDefect(true);assertFalse(stop(service,receipt("group",Collections.emptyList())));
            verifyNoInteractions(runs,ai);
        } finally {service.shutdown();}
    }

    @Test void unknownProviderResultStillStopsWithTheExistingFallbackReason() throws Exception {
        DraftAutomationRunRepository runs=mock(DraftAutomationRunRepository.class);DraftAutomationRun run=run();when(runs.lockRun("run")).thenReturn(Optional.of(run));DraftAutomationService service=new DraftAutomationService(runs,null,null,null,null,null,null,null,null,null,null,manager());service.acceptanceProbeStopOnDefect(true);
        try {
            Map<String,Object> receipt=receipt("group-unknown",Collections.emptyList());receipt.put("status","unknown");assertTrue(stop(service,receipt));assertTrue(run.isCancelRequested());assertEquals("PROVIDER_RESULT_UNKNOWN",DraftAutomationService.firstProbeReason(run.getManifestJson(),""));
        } finally {service.shutdown();}
    }

    private static boolean stop(DraftAutomationService service,Map<String,Object> receipt) throws Exception {Method method=DraftAutomationService.class.getDeclaredMethod("stopForProbe",String.class,Map.class);method.setAccessible(true);return (Boolean)method.invoke(service,"run",receipt);}
    private static PlatformTransactionManager manager(){PlatformTransactionManager manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));return manager;}
    private static Map<String,Object> receipt(String id,List<String> reasons){return obj("taskId",id,"phase","GROUP_DISCOVERY","status","partial","stopReasons",reasons,"rawResponse","unchanged original "+id);}
    private static DraftAutomationRun run(){DraftAutomationRun run=new DraftAutomationRun();run.setId("run");run.setProjectId("project");run.setStatus("RUNNING");run.setPhase("DISCOVERING_VARIABLES");run.setProfileId("local");run.setConfigurationSha256("configuration");run.setSourceStampJson("[]");run.setCreatedAt(Instant.EPOCH);run.setUpdatedAt(Instant.EPOCH);run.setManifestJson(MANIFEST);return run;}
    private static DraftAutomationItem item(String id,int order,Map<String,Object> receipt){DraftAutomationItem item=new DraftAutomationItem();item.setId(id);item.setRunId("run");item.setTaskId(id);item.setTaskOrder(order);item.setStatus("DONE");item.setTaskJson(JsonUtils.write(obj("taskId",id)));item.setReceiptJson(JsonUtils.write(receipt));return item;}
}
