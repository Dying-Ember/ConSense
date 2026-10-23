package com.consense.service.drafting;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real isolated job persistence and worker orchestration, mocked inference/source projection. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class DraftAutomationServiceTest {
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:mem:auto_graph_jobs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");}
    @Autowired DraftAutomationService service;
    @Autowired DraftAutomationRunRepository runs;
    @Autowired DraftAutomationItemRepository items;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @MockBean DraftTemplateGraphService graphs;
    @MockBean AiGateway ai;
    @MockBean LlmProfiles profiles;
    LlmOperation operation;
    final String sha=String.join("",Collections.nCopies(64,"a"));

    @BeforeEach void setup(){
        com.consense.config.ConsenseProperties settings=new com.consense.config.ConsenseProperties();settings.getLlm().setEnabled(true);settings.getLlm().setBaseUrl("http://synthetic.invalid");settings.getLlm().setChatModel("synthetic");
        operation=new LlmProfiles(settings,mock(LlmClient.class),null).resolve("local");
        when(profiles.capture()).thenReturn(operation);when(profiles.resolve("local")).thenReturn(operation);
        when(ai.complete(anyString(),anyString())).thenReturn("{\"requirements\":[],\"nodes\":[]}");
        when(graphs.automationInput(anyString(),isNull(),isNull(),anyList())).thenAnswer(call->{List<JsonNode> readings=call.getArgument(3);readings.add(reading("1"));readings.add(reading("2"));return graph();});
        when(graphs.save(anyString(),anyMap(),isNull(),isNull(),eq(true))).thenReturn(Collections.singletonMap("id","result-test"));
    }
    String project(){Project p=new Project();p.setId("auto-"+UUID.randomUUID());p.setNameZhHans("Synthetic job test");projects.save(p);return p.getId();}
    ObjectNode reading(String id){ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceSha256",sha).put("evidenceStatus","bound").put("fileName","template-"+id+".docx");ObjectNode member=reading.putArray("sourceMembers").addObject().put("id","m"+id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",sha).put("partName","word/document.xml").put("nodePath","/p"+id);member.putObject("textObservation").put("text","Provide a project duration.");ObjectNode note=reading.putArray("guidanceNotes").addObject().put("id","guidance-"+id).put("text","Provide a project duration.").put("captureComplete",true);note.putArray("orderedMemberIds").add("m"+id);note.putArray("memberRanges").addObject().put("memberId","m"+id).put("channel","text").put("start",0).put("end",26);return reading;}
    Map<String,Object> graph(){Map<String,Object> state=new LinkedHashMap<>();state.put("variables",new ArrayList<>());state.put("sourceReviews",new LinkedHashMap<>());state.put("events",new ArrayList<>());Map<String,Object> result=new LinkedHashMap<>();result.put("kind","consense-template-graph-v1");result.put("graph",state);result.put("sources",new ArrayList<>());result.put("selectedDocuments",new LinkedHashMap<>());return result;}
    Map<String,Object> await(String project,String id)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);Map<String,Object> result;do{result=service.get(project,id);if(!Arrays.asList("QUEUED","RUNNING").contains(result.get("status")))return result;Thread.sleep(20);}while(System.nanoTime()<deadline);throw new AssertionError("job did not finish: "+result);}
    String start(String project){return (String)service.start(project,null,null).get("id");}

    @Test void oneStartScansAllPacketsPublishesOnceAndPollingNeverCallsModel()throws Exception{
        String project=project(),id=start(project);Map<String,Object> result=await(project,id);
        assertEquals("COMPLETED",result.get("status"));assertEquals(2,result.get("completedItems"));assertEquals("result-test",result.get("resultRevisionId"));
        verify(ai,times(2)).complete(anyString(),anyString());verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
        for(int n=0;n<4;n++){service.latest(project);service.get(project,id);}verify(ai,times(2)).complete(anyString(),anyString());
        assertEquals(2,items.findByRunIdOrderByTaskOrderAsc(id).size());assertFalse(runs.findById(id).get().getManifestJson().contains("project duration"));
    }
    @Test void repeatedClickSharesJobAndCancelSavesCompletedPartialResults()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);when(ai.complete(anyString(),anyString())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "{\"requirements\":[],\"nodes\":[]}";});
        String project=project(),id=start(project);assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{assertEquals(id,start(project));service.cancel(project,id);}finally{release.countDown();}
        Map<String,Object> result=await(project,id);assertEquals("CANCELLED",result.get("status"));assertEquals("result-test",result.get("resultRevisionId"));assertEquals(1,result.get("completedItems"));verify(ai,times(1)).complete(anyString(),anyString());verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
    }
    @Test void changedSourceStopsBeforePublishingOverNewerInputs()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);when(ai.complete(anyString(),anyString())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "{\"requirements\":[],\"nodes\":[]}";});
        String project=project(),id=start(project);assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory("PROJECT_INPUT");source.setFileName("new-minutes.txt");source.setParseStatus("PARSED");sources.saveAndFlush(source);}finally{release.countDown();}
        Map<String,Object> result=await(project,id);assertEquals("FAILED",result.get("status"));assertNull(result.get("resultRevisionId"));verify(graphs,never()).save(eq(project),anyMap(),any(),any(),anyBoolean());assertEquals(1,items.findByRunIdOrderByTaskOrderAsc(id).stream().filter(item->item.getReceiptJson()!=null).count());
    }
    @Test void failedPacketDoesNotDiscardOtherPacketOrPreventPartialGraph()throws Exception{
        when(ai.complete(anyString(),anyString())).thenThrow(new IllegalStateException("synthetic provider failure")).thenReturn("{\"requirements\":[],\"nodes\":[]}");
        String project=project(),id=start(project);Map<String,Object> result=await(project,id);assertEquals("PARTIAL",result.get("status"));assertEquals(1,result.get("failedItems"));assertEquals(2,result.get("completedItems"));assertEquals("result-test",result.get("resultRevisionId"));verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
    }
    @Test void restartMarksInflightUnknownAndResumeOnlySendsPendingPacket()throws Exception{
        String project=project();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Arrays.asList(reading("1"),reading("2")));
        DraftAutomationRun run=new DraftAutomationRun();run.setId(UUID.randomUUID().toString());run.setProjectId(project);run.setProfileId("local");run.setConfigurationSha256(operation.getIdentity().getConfigurationSha256());run.setStatus("RUNNING");run.setPhase("DISCOVERING_VARIABLES");run.setCreatedAt(java.time.Instant.now());run.setUpdatedAt(run.getCreatedAt());run.setSourceStampJson("[]");run.setManifestJson(JsonUtils.write(plan.descriptor()));runs.saveAndFlush(run);
        DraftAutomationItem item=new DraftAutomationItem();item.setId(UUID.randomUUID().toString());item.setRunId(run.getId());item.setTaskId((String)plan.tasks().get(0).get("taskId"));item.setTaskOrder(0);item.setStatus("IN_FLIGHT");item.setTaskJson(JsonUtils.write(plan.tasks().get(0)));items.saveAndFlush(item);
        service.recoverInterrupted();assertEquals("INTERRUPTED",service.get(project,run.getId()).get("status"));assertEquals("UNKNOWN",items.findById(item.getId()).get().getStatus());
        service.resume(project,run.getId(),null,null);Map<String,Object> result=await(project,run.getId());assertEquals("PARTIAL",result.get("status"));assertEquals(2,result.get("completedItems"));verify(ai,times(1)).complete(anyString(),anyString());verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
    }
    @Test void restartKeepsAnExplicitlyCancelledSavedGraphStopped() {
        String project=project();DraftAutomationRun run=new DraftAutomationRun();run.setId(UUID.randomUUID().toString());run.setProjectId(project);run.setProfileId("local");run.setConfigurationSha256(operation.getIdentity().getConfigurationSha256());run.setStatus("RUNNING");run.setPhase("EXTRACTING_CANDIDATES");run.setCreatedAt(java.time.Instant.now());run.setUpdatedAt(run.getCreatedAt());run.setSourceStampJson("[]");run.setResultRevisionId("saved-result");run.setCancelRequested(true);runs.saveAndFlush(run);
        service.recoverInterrupted();Map<String,Object> result=service.get(project,run.getId());assertEquals("CANCELLED",result.get("status"));assertEquals("saved-result",result.get("resultRevisionId"));verifyNoInteractions(ai);
    }
    @Test void taskOwnershipAndUnavailableModelAreRejectedWithoutInference(){String project=project();assertThrows(BizException.class,()->service.get(project,"foreign-run"));when(profiles.capture()).thenReturn(null);assertThrows(BizException.class,()->start(project));verifyNoInteractions(ai);}
}
