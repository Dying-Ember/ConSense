package com.consense.service.drafting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Isolated persistence with synthetic model replies; no live model or HTTP calls. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class DraftSavedGroupResumeTest {
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:mem:saved_group_resume;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");}
    @Autowired DraftAutomationService service;
    @Autowired DraftAutomationRunRepository runs;
    @Autowired DraftAutomationItemRepository items;
    @Autowired DraftTemplateGraphHeadRepository heads;
    @Autowired ProjectRepository projects;
    @MockBean DraftTemplateGraphService graphs;
    @MockBean AiGateway ai;
    @MockBean LlmProfiles profiles;
    LlmOperation operation;LlmProfiles localProfiles;String project,id,oldRaw;DraftAutomationItem oldGroup;

    @BeforeEach void setup(){
        com.consense.config.ConsenseProperties settings=new com.consense.config.ConsenseProperties();settings.getLlm().setEnabled(true);settings.getLlm().setBaseUrl("http://synthetic.invalid");settings.getLlm().setChatModel("synthetic");localProfiles=new LlmProfiles(settings,mock(LlmClient.class),null);operation=localProfiles.resolve("local");when(profiles.resolve("local")).thenReturn(operation);when(profiles.bind(any())).thenAnswer(call->localProfiles.bind(call.getArgument(0)));service.groupConcurrency(1);service.acceptanceProbeStopOnDefect(true);
        project="resume-"+UUID.randomUUID();Project p=new Project();p.setId(project);p.setNameZhHans("Synthetic continuation");projects.save(p);DraftTemplateGraphHead head=new DraftTemplateGraphHead();head.setProjectId(project);head.setDraftRevisionId("saved-result");heads.saveAndFlush(head);
        List<JsonNode> readings=Arrays.asList(source("1"),source("2"));DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings);List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);Map<String,Object> manifest=obj("execution","fixed-root-groups-v1","plan",plan.descriptor(),"groups",groups,"concurrency",1,"firstProbeFailure",obj("taskId",groups.get(0).get("taskId"),"reasons",Collections.singletonList("historic-stop")));
        DraftAutomationRun run=new DraftAutomationRun();id=UUID.randomUUID().toString();run.setId(id);run.setProjectId(project);run.setProfileId("local");run.setConfigurationSha256(operation.getIdentity().getConfigurationSha256());run.setStatus("PARTIAL");run.setPhase("READY_FOR_REVIEW");run.setResultRevisionId("saved-result");run.setSourceStampJson("[]");run.setManifestJson(JsonUtils.write(manifest));run.setCreatedAt(Instant.now());run.setUpdatedAt(run.getCreatedAt());run.setCancelRequested(true);runs.saveAndFlush(run);
        Map<String,Object> child=new LinkedHashMap<>(plan.tasks().get(0));child.putAll(obj("status","partial","nodes",Collections.emptyList(),"requirements",Collections.emptyList(),"issues",Collections.singletonList(obj("reason","historic-review")),"modelCalls",0));item(plan.tasks().get(0),0,"DONE",child);item(plan.tasks().get(1),1,"PENDING",null);
        Map<String,Object> receipt=new LinkedHashMap<>(groups.get(0));receipt.putAll(obj("status","partial","rawResponse","ORIGINAL_RAW_MUST_NOT_CHANGE","modelCalls",1,"stopReasons",Collections.singletonList("historic-stop")));oldGroup=item(groups.get(0),2,"DONE",receipt);oldRaw=oldGroup.getReceiptJson();item(groups.get(1),3,"PENDING",null);
        when(graphs.automationInput(eq(project),isNull(),eq("saved-result"),isNull(),anyList())).thenAnswer(call->{((List<JsonNode>)call.getArgument(4)).addAll(readings);return obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());});when(graphs.save(eq(project),anyMap(),isNull(),eq("saved-result"),eq(true))).thenReturn(Collections.singletonMap("id","continued-result"));
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{JsonNode packet=JsonUtils.parse(call.getArgument(1));List<Object> outcomes=new ArrayList<>();for(JsonNode instruction:packet.path("instructions"))outcomes.add(obj("sourceTaskId",instruction.path("sourceTaskId").asText(),"status","no_requirement","reason","Synthetic source reviewed"));return JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",Collections.emptyList(),"scopeRules",Collections.emptyList(),"taskOutcomes",outcomes,"notes",Collections.emptyList()));});
    }

    @Test void savedPartialDispatchesOnlyPendingGroupAndKeepsHistoricFailure(){
        assertEquals(true,service.get(project,id).get("canContinueSavedGroups"));service.resume(project,id,null,"saved-result");Map<String,Object> result=await();assertEquals("continued-result",result.get("resultRevisionId"));assertEquals("PARTIAL",result.get("status"));assertFalse(String.valueOf(result.get("message")).contains("historic-stop"));verify(ai,times(1)).complete(anyString(),anyString());assertEquals(oldRaw,items.findById(oldGroup.getId()).get().getReceiptJson());
        JsonNode manifest=JsonUtils.parse(runs.findById(id).get().getManifestJson());assertEquals("historic-stop",manifest.path("firstProbeFailure").path("reasons").path(0).asText());assertFalse(manifest.has("continuation"));assertEquals("continued-result",manifest.path("lastContinuation").path("resultRevisionId").asText());
    }

    @Test void returnedButUnsavedDeltaIsSavedOnResumeWithoutAnotherCall(){
        Map<String,Object> charged=JsonUtils.readMap(oldRaw);charged.put("modelCalls",767);oldGroup.setReceiptJson(JsonUtils.write(charged));items.saveAndFlush(oldGroup);oldRaw=oldGroup.getReceiptJson();
        when(graphs.save(eq(project),anyMap(),isNull(),eq("saved-result"),eq(true))).thenThrow(new IllegalStateException("synthetic save interruption")).thenReturn(Collections.singletonMap("id","continued-result"));service.resume(project,id,null,"saved-result");assertEquals("saved-result",await().get("resultRevisionId"));assertFalse(DraftAutomationService.continuationTasks(runs.findById(id).get().getManifestJson()).isEmpty());assertEquals(true,service.get(project,id).get("canContinueSavedGroups"));
        service.resume(project,id,null,"saved-result");assertEquals("continued-result",await().get("resultRevisionId"));verify(ai,times(1)).complete(anyString(),anyString());assertEquals(oldRaw,items.findById(oldGroup.getId()).get().getReceiptJson());
    }

    @Test void committedProbeStopRecoversAsPartialButAnOlderStopCannotOverrideUserCancellation(){
        DraftAutomationRun row=runs.findById(id).get();Map<String,Object> manifest=JsonUtils.readMap(row.getManifestJson());manifest.put("lastContinuation",obj("resultRevisionId","saved-result","stop",obj("reasons",Collections.singletonList("new-probe"))));row.setManifestJson(JsonUtils.write(manifest));assertTrue(DraftAutomationService.continuationProbeStop(row));
        manifest.put("continuation",obj("baseResultRevisionId","saved-result","taskIds",Collections.singletonList("new-pending")));row.setManifestJson(JsonUtils.write(manifest));assertFalse(DraftAutomationService.continuationProbeStop(row),"A live cycle without a probe stop may have been explicitly cancelled");
        manifest.remove("continuation");row.setResultRevisionId("different-result");row.setManifestJson(JsonUtils.write(manifest));assertFalse(DraftAutomationService.continuationProbeStop(row));
    }

    @Test void originalLifetimeAndChangedHeadRejectBeforeModelDispatch(){
        assertThrows(RuntimeException.class,()->service.resume(project,id,null,"newer-head"));DraftAutomationRun row=runs.findById(id).get();row.setCreatedAt(Instant.now().minusSeconds(21601));runs.saveAndFlush(row);assertEquals(false,service.get(project,id).get("canContinueSavedGroups"));assertThrows(RuntimeException.class,()->service.resume(project,id,null,"saved-result"));verifyNoInteractions(ai);
    }

    @Test void unknownGroupIsNotReplayedAndItsReservedCallsStillConsumeTheBudget(){
        Map<String,Object> receipt=JsonUtils.readMap(oldRaw);receipt.put("status","unknown");receipt.put("modelCalls",null);receipt.put("reservedModelCalls",768);oldGroup.setStatus("UNKNOWN");oldGroup.setReceiptJson(JsonUtils.write(receipt));items.saveAndFlush(oldGroup);
        assertThrows(RuntimeException.class,()->service.resume(project,id,null,"saved-result"));verifyNoInteractions(ai);assertEquals("UNKNOWN",items.findById(oldGroup.getId()).get().getStatus());
        receipt.put("reservedModelCalls",3);oldGroup.setReceiptJson(JsonUtils.write(receipt));items.saveAndFlush(oldGroup);String unknown=oldGroup.getReceiptJson();service.resume(project,id,null,"saved-result");await();verify(ai,times(1)).complete(anyString(),anyString());assertEquals(unknown,items.findById(oldGroup.getId()).get().getReceiptJson());
    }

    private DraftAutomationItem item(Map<String,Object> task,int order,String status,Map<String,Object> receipt){DraftAutomationItem row=new DraftAutomationItem();row.setId(UUID.randomUUID().toString());row.setRunId(id);row.setTaskId(text(task.get("taskId")));row.setTaskOrder(order);row.setTaskJson(JsonUtils.write(task));row.setStatus(status);if(receipt!=null)row.setReceiptJson(JsonUtils.write(receipt));return items.saveAndFlush(row);}
    private Map<String,Object> await(){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);Map<String,Object> result;do{result=service.get(project,id);if(!Arrays.asList("QUEUED","RUNNING").contains(result.get("status")))return result;try{Thread.sleep(10);}catch(InterruptedException e){throw new AssertionError(e);}}while(System.nanoTime()<deadline);throw new AssertionError("Synthetic worker did not finish");}
    private static ObjectNode source(String id){String quote="A synthetic source reminder.",sha=String.join("",Collections.nCopies(64,"a"));ObjectNode source=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceSha256",sha).put("evidenceStatus","bound").put("format","docx");ObjectNode member=source.putArray("sourceMembers").addObject().put("id","m"+id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",sha).put("partName","word/document.xml").put("nodePath","/p"+id);member.putObject("textObservation").put("text",quote);ObjectNode note=source.putArray("guidanceNotes").addObject().put("id","g"+id).put("text",quote).put("captureComplete",true);note.putArray("orderedMemberIds").add("m"+id);note.putArray("memberRanges").addObject().put("memberId","m"+id).put("channel","text").put("start",0).put("end",quote.length());return source;}
}
