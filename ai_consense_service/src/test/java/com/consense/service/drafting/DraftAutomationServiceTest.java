package com.consense.service.drafting;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    LlmProfiles threadProfiles;
    final String sha=String.join("",Collections.nCopies(64,"a"));

    @BeforeEach void setup(){
        com.consense.config.ConsenseProperties settings=new com.consense.config.ConsenseProperties();settings.getLlm().setEnabled(true);settings.getLlm().setBaseUrl("http://synthetic.invalid");settings.getLlm().setChatModel("synthetic");
        threadProfiles=new LlmProfiles(settings,mock(LlmClient.class),null);operation=threadProfiles.resolve("local");service.groupConcurrency(1);service.acceptanceProbeStopOnDefect(false);
        when(profiles.capture()).thenReturn(operation);when(profiles.resolve("local")).thenReturn(operation);
        when(profiles.bind(any())).thenAnswer(call->threadProfiles.bind(call.getArgument(0)));
        when(ai.complete(anyString(),anyString())).thenAnswer(call->noRequirements(call.getArgument(1)));
        when(graphs.automationInput(anyString(),isNull(),isNull(),anyList())).thenAnswer(call->{List<JsonNode> readings=call.getArgument(3);readings.add(reading("1"));readings.add(reading("2"));return graph();});
        when(graphs.save(anyString(),anyMap(),isNull(),isNull(),eq(true))).thenReturn(Collections.singletonMap("id","result-test"));
    }
    String project(){Project p=new Project();p.setId("auto-"+UUID.randomUUID());p.setNameZhHans("Synthetic job test");projects.save(p);return p.getId();}
    ObjectNode reading(String id){ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceSha256",sha).put("evidenceStatus","bound").put("fileName","template-"+id+".docx");ObjectNode member=reading.putArray("sourceMembers").addObject().put("id","m"+id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",sha).put("partName","word/document.xml").put("nodePath","/p"+id);member.putObject("textObservation").put("text","Provide a project duration.");ObjectNode note=reading.putArray("guidanceNotes").addObject().put("id","guidance-"+id).put("text","Provide a project duration.").put("captureComplete",true);note.putArray("orderedMemberIds").add("m"+id);note.putArray("memberRanges").addObject().put("memberId","m"+id).put("channel","text").put("start",0).put("end",26);return reading;}
    Map<String,Object> graph(){Map<String,Object> state=new LinkedHashMap<>();state.put("variables",new ArrayList<>());state.put("sourceReviews",new LinkedHashMap<>());state.put("events",new ArrayList<>());Map<String,Object> result=new LinkedHashMap<>();result.put("kind","consense-template-graph-v1");result.put("graph",state);result.put("sources",new ArrayList<>());result.put("selectedDocuments",new LinkedHashMap<>());return result;}
    Map<String,Object> await(String project,String id)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);Map<String,Object> result;do{result=service.get(project,id);if(!Arrays.asList("QUEUED","RUNNING").contains(result.get("status")))return result;Thread.sleep(20);}while(System.nanoTime()<deadline);throw new AssertionError("job did not finish: "+result);}
    String start(String project){return (String)service.start(project,null,null).get("id");}
    String noRequirements(String user){ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("requirements");result.putArray("nodes");for(JsonNode instruction:JsonUtils.parse(user).path("instructions"))result.withArray("taskOutcomes").addObject().put("sourceTaskId",instruction.path("sourceTaskId").asText()).put("status","no_requirement").put("reason","Synthetic empty review");return result.toString();}

    @Test void oneStartScansAllPacketsPublishesOnceAndPollingNeverCallsModel()throws Exception{
        String project=project(),id=start(project);Map<String,Object> result=await(project,id);
        assertEquals("COMPLETED",result.get("status"));assertEquals(2,result.get("completedItems"));assertEquals("result-test",result.get("resultRevisionId"));
        verify(ai,times(2)).complete(anyString(),anyString());verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
        verify(graphs,never()).extract(anyString(),anyString(),any());
        for(int n=0;n<4;n++){service.latest(project);service.get(project,id);}verify(ai,times(2)).complete(anyString(),anyString());
        assertEquals(4,items.findByRunIdOrderByTaskOrderAsc(id).size());assertFalse(runs.findById(id).get().getManifestJson().contains("project duration"));
        assertEquals(2,((Map<?,?>)result.get("groupProgress")).get("completed"));assertEquals(1,((Map<?,?>)result.get("groupProgress")).get("concurrency"));
    }
    @Test void acceptanceProbePersistsTheDefectAndStopsBeforeDispatchingAnotherGroup()throws Exception{
        service.acceptanceProbeStopOnDefect(true);
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{
            JsonNode instruction=JsonUtils.parse(call.getArgument(1,String.class)).path("instructions").get(0);String task=instruction.path("sourceTaskId").asText(),quote=instruction.path("text").asText();
            Map<String,Object> node=DraftReviewGraph.obj("id","d","sourceTaskId",task,"type","boolean","role","derived","nameEn","Derived flag","sourceQuote",quote,"options",new ArrayList<>(),"effects",new ArrayList<>(),"branches",Collections.singletonList(DraftReviewGraph.obj("id","b","mode","all","scopeId","foreign-document-scope","scopeMode","delete","clauses",Collections.singletonList(DraftReviewGraph.obj("inputId","d","operator","yes")),"resultValue",true)));
            return JsonUtils.write(DraftReviewGraph.obj("nodes",Collections.singletonList(node),"requirements",Collections.singletonList(DraftReviewGraph.obj("id","r","sourceTaskId",task,"sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("d"))),"taskOutcomes",Collections.singletonList(DraftReviewGraph.obj("sourceTaskId",task,"status","identified"))));
        });
        String project=project(),id=start(project);Map<String,Object> result=await(project,id);
        assertEquals("PARTIAL",result.get("status"));assertEquals(1,result.get("completedItems"));assertTrue(String.valueOf(result.get("message")).contains("验收探针"));
        verify(ai,times(1)).complete(anyString(),anyString());verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
        assertEquals(2,items.findByRunIdOrderByTaskOrderAsc(id).stream().filter(item->"PENDING".equals(item.getStatus())).count());
    }
    @Test void freshCompleteEditionRestoresWithoutCallingTheModel()throws Exception{
        when(graphs.automationInput(anyString(),isNull(),isNull(),anyList())).thenAnswer(call->{Map<String,Object> result=graph();DraftReviewGraph.state(result).put("baselineRestoration",DraftReviewGraph.obj("status","complete_requires_review","baselineId","verified-baseline"));return result;});
        String project=project(),id=start(project);Map<String,Object> result=await(project,id);assertEquals("COMPLETED",result.get("status"));assertEquals("result-test",result.get("resultRevisionId"));assertEquals(0,result.get("totalItems"));verify(ai,never()).complete(anyString(),anyString());verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
    }
    @Test void partialDefinitionAndCleanReceiptMetadataCannotSkipUnprovenScopes()throws Exception{
        when(graphs.automationInput(anyString(),isNull(),isNull(),anyList())).thenAnswer(call->{
            ObjectNode first=reading("1").put("fileKey","NTT").put("analysisRevision","v1").put("analysisInputSha256",sha).put("analysisSha256",sha);
            List<JsonNode> readings=call.getArgument(3);readings.add(first);readings.add(reading("2"));Map<String,Object> graph=graph(),state=DraftReviewGraph.state(graph);Map<String,Object> task=DraftAutomaticDiscovery.plan(Collections.singletonList(first)).tasks().get(0);
            graph.put("sources",Collections.singletonList(JsonUtils.mapper().convertValue(first,Map.class)));graph.put("selectedDocuments",Collections.singletonMap("NTT","1"));
            state.put("variables",Collections.singletonList(DraftReviewGraph.obj("id","restored-duration","nameEn","Reviewed duration","type",DraftReviewGraph.obj("kind","number"),"role",DraftReviewGraph.obj("kind","input"),"status","pending","definitionReview",DraftReviewGraph.obj("confirmed",true),"editTargets",new ArrayList<>(),"docIds",Collections.singletonList("1"))));
            state.put("requirements",Collections.singletonList(DraftReviewGraph.obj("id","old-req","sourceId","1","sourceSha256",sha,"sourceQuote","Provide a project duration.","kind","input","nodeIds",Collections.singletonList("restored-duration"))));
            state.put("baselineRestoration",DraftReviewGraph.obj("status","partial_requires_review","baselineId","verified-subset"));
            state.put("sourceTaskCoverage",Collections.singletonList(DraftReviewGraph.obj("fileKey","NTT","sourceSha256",sha,"analysisRevision","v1","analysisInputSha256",sha,"analysisSha256",sha,"itemKind",task.get("itemKind"),"sourceObservationId",task.get("sourceObservationId"),"nodeIds",Collections.singletonList("restored-duration"),"requirementIds",Collections.singletonList("old-req"))));return graph;
        });
        String project=project(),id=start(project);Map<String,Object> result=await(project,id);assertEquals("COMPLETED",result.get("status"));assertEquals(2,result.get("completedItems"));verify(ai,times(2)).complete(anyString(),anyString());
        List<DraftAutomationItem> completed=items.findByRunIdOrderByTaskOrderAsc(id);Map<String,Object> first=JsonUtils.readMap(completed.get(0).getReceiptJson());assertFalse(first.containsKey("coverageOrigin"));assertEquals(0,first.get("modelCalls"));assertNotNull(first.get("groupReceiptId"));
    }
    @Test void repeatedClickSharesJobAndCancelSavesCompletedPartialResults()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);when(ai.complete(anyString(),anyString())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return noRequirements(call.getArgument(1));});
        String project=project(),id=start(project);assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{assertEquals(id,start(project));service.cancel(project,id);}finally{release.countDown();}
        Map<String,Object> result=await(project,id);assertEquals("CANCELLED",result.get("status"));assertEquals("result-test",result.get("resultRevisionId"));assertEquals(1,result.get("completedItems"));verify(ai,times(1)).complete(anyString(),anyString());verify(graphs,times(1)).save(eq(project),anyMap(),isNull(),isNull(),eq(true));
    }
    @Test void changedSourceStopsBeforePublishingOverNewerInputs()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);when(ai.complete(anyString(),anyString())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return noRequirements(call.getArgument(1));});
        String project=project(),id=start(project);assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory("PROJECT_INPUT");source.setFileName("new-minutes.txt");source.setParseStatus("PARSED");sources.saveAndFlush(source);}finally{release.countDown();}
        Map<String,Object> result=await(project,id);assertEquals("FAILED",result.get("status"));assertNull(result.get("resultRevisionId"));verify(graphs,never()).save(eq(project),anyMap(),any(),any(),anyBoolean());assertEquals(2,items.findByRunIdOrderByTaskOrderAsc(id).stream().filter(item->item.getReceiptJson()!=null).count());
    }
    @Test void failedPacketDoesNotDiscardOtherPacketOrPreventPartialGraph()throws Exception{
        AtomicInteger attempts=new AtomicInteger();when(ai.complete(anyString(),anyString())).thenAnswer(call->{if(attempts.getAndIncrement()==0)throw new IllegalStateException("synthetic provider failure");return noRequirements(call.getArgument(1));});
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
    void threeReadings(){when(graphs.automationInput(anyString(),isNull(),isNull(),anyList())).thenAnswer(call->{List<JsonNode> readings=call.getArgument(3);for(String id:Arrays.asList("1","2","3"))readings.add(reading(id));return graph();});}
    String manifest(DraftAutomaticDiscovery.Plan plan,List<Map<String,Object>> groups){Map<String,Object> result=new LinkedHashMap<>();result.put("execution","fixed-root-groups-v1");result.put("plan",plan.descriptor());result.put("groups",groups);result.put("concurrency",1);return JsonUtils.write(result);}
    DraftAutomationRun savedRun(String project,String manifest){DraftAutomationRun run=new DraftAutomationRun();run.setId(UUID.randomUUID().toString());run.setProjectId(project);run.setProfileId("local");run.setConfigurationSha256(operation.getIdentity().getConfigurationSha256());run.setStatus("RUNNING");run.setPhase("DISCOVERING_VARIABLES");run.setCreatedAt(java.time.Instant.now());run.setUpdatedAt(run.getCreatedAt());run.setSourceStampJson("[]");run.setManifestJson(manifest);return runs.saveAndFlush(run);}
    DraftAutomationItem savedItem(String runId,Map<String,Object> task,int order,String status,Map<String,Object> receipt){DraftAutomationItem item=new DraftAutomationItem();item.setId(UUID.randomUUID().toString());item.setRunId(runId);item.setTaskId((String)task.get("taskId"));item.setTaskOrder(order);item.setStatus(status);item.setTaskJson(JsonUtils.write(task));if(receipt!=null)item.setReceiptJson(JsonUtils.write(receipt));return items.saveAndFlush(item);}

    @Test void twoGroupsBindTheCapturedProfileAndSaveTheFastResponseBeforeTheSlowGroupReturns()throws Exception{
        service.groupConcurrency(2);threeReadings();CountDownLatch bothEntered=new CountDownLatch(2),secondReturned=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{String user=call.getArgument(1),source=JsonUtils.parse(user).path("instructions").get(0).path("sourceId").asText();assertSame(operation,threadProfiles.capture());maximum.accumulateAndGet(active.incrementAndGet(),Math::max);try{bothEntered.countDown();assertTrue(bothEntered.await(5,TimeUnit.SECONDS));if("1".equals(source))assertTrue(releaseFirst.await(5,TimeUnit.SECONDS));if("2".equals(source))secondReturned.countDown();return noRequirements(user);}finally{active.decrementAndGet();}});
        String project=project(),id=start(project);
        try{assertTrue(bothEntered.await(5,TimeUnit.SECONDS));assertTrue(secondReturned.await(5,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);List<DraftAutomationItem> pending;do{pending=items.findByRunIdOrderByTaskOrderAsc(id);if("DONE".equals(pending.get(1).getStatus()))break;Thread.sleep(10);}while(System.nanoTime()<deadline);assertEquals("DONE",pending.get(1).getStatus());assertEquals("DONE",pending.get(4).getStatus());assertNotNull(JsonUtils.readMap(pending.get(4).getReceiptJson()).get("rawResponse"));assertEquals("IN_FLIGHT",pending.get(0).getStatus());assertFalse(pending.get(1).getReceiptJson().contains("rawResponse"));}finally{releaseFirst.countDown();}
        Map<String,Object> result=await(project,id);assertEquals("COMPLETED",result.get("status"));assertEquals(3,result.get("completedItems"));assertEquals(3,result.get("totalItems"));assertEquals(2,maximum.get());assertEquals(3,((Map<?,?>)result.get("groupProgress")).get("completed"));assertEquals(2,((Map<?,?>)result.get("groupProgress")).get("concurrency"));
        List<DraftAutomationItem> completed=items.findByRunIdOrderByTaskOrderAsc(id);assertEquals(6,completed.size());for(int i=0;i<completed.size();i++){Map<String,Object> receipt=JsonUtils.readMap(completed.get(i).getReceiptJson());assertFalse(receipt.containsKey("reservedModelCalls"));if(i<3){assertEquals(0,receipt.get("modelCalls"));assertFalse(receipt.containsKey("rawResponse"));assertFalse(receipt.containsKey("userPrompt"));}else{assertEquals(1,receipt.get("modelCalls"));assertNotNull(receipt.get("rawResponse"));assertFalse(receipt.containsKey("taskReceipts"));}}verify(ai,times(3)).complete(anyString(),anyString());
    }

    @Test void cancellingCollectsBothSentGroupsButStopsSupplementalCallsAndNextGroup()throws Exception{
        service.groupConcurrency(2);threeReadings();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{String user=call.getArgument(1);entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));ObjectNode response=(ObjectNode)JsonUtils.parse(noRequirements(user));response.putArray("sourceSearchRequests").addObject().put("sourceTaskId",JsonUtils.parse(user).path("instructions").get(0).path("sourceTaskId").asText()).put("query","duration");return response.toString();});
        String project=project(),id=start(project);try{assertTrue(entered.await(5,TimeUnit.SECONDS));service.cancel(project,id);}finally{release.countDown();}
        Map<String,Object> result=await(project,id);assertEquals("CANCELLED",result.get("status"));assertEquals(2,result.get("completedItems"));assertEquals("result-test",result.get("resultRevisionId"));verify(ai,times(2)).complete(anyString(),anyString());List<DraftAutomationItem> saved=items.findByRunIdOrderByTaskOrderAsc(id);assertEquals("PENDING",saved.get(2).getStatus());assertEquals("PENDING",saved.get(5).getStatus());for(int index:Arrays.asList(3,4)){Map<String,Object> receipt=JsonUtils.readMap(saved.get(index).getReceiptJson());assertNotNull(receipt.get("rawResponse"));assertEquals(1,receipt.get("modelCalls"));}
    }

    @Test void changedSourceCollectsBothSentGroupsWithoutPublishingOrStartingTheThird()throws Exception{
        service.groupConcurrency(2);threeReadings();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);when(ai.complete(anyString(),anyString())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return noRequirements(call.getArgument(1));});
        String project=project(),id=start(project);try{assertTrue(entered.await(5,TimeUnit.SECONDS));SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory("PROJECT_INPUT");source.setFileName("new-evidence.txt");source.setParseStatus("PARSED");sources.saveAndFlush(source);}finally{release.countDown();}
        Map<String,Object> result=await(project,id);assertEquals("FAILED",result.get("status"));assertNull(result.get("resultRevisionId"));assertEquals(2,result.get("completedItems"));verify(ai,times(2)).complete(anyString(),anyString());verify(graphs,never()).save(eq(project),anyMap(),any(),any(),anyBoolean());List<DraftAutomationItem> saved=items.findByRunIdOrderByTaskOrderAsc(id);assertEquals("PENDING",saved.get(2).getStatus());for(int index:Arrays.asList(3,4))assertNotNull(JsonUtils.readMap(saved.get(index).getReceiptJson()).get("rawResponse"));
    }

    @Test void unknownGroupKeepsBudgetReservationWithoutClaimingThreeCallsAndNeverReplays()throws Exception{
        String project=project();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Arrays.asList(reading("1"),reading("2")));List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(2,groups.size());DraftAutomationRun run=savedRun(project,manifest(plan,groups));
        Map<String,Object> childPending=DraftReviewGraph.obj("taskId",plan.tasks().get(0).get("taskId"),"groupReceiptId",groups.get(0).get("taskId"),"modelCalls",0);DraftAutomationItem child=savedItem(run.getId(),plan.tasks().get(0),0,"IN_FLIGHT",childPending);savedItem(run.getId(),plan.tasks().get(1),1,"PENDING",null);Map<String,Object> groupPending=new LinkedHashMap<>(groups.get(0));groupPending.put("reservedModelCalls",3);groupPending.put("modelCalls",0);DraftAutomationItem owner=savedItem(run.getId(),groups.get(0),2,"IN_FLIGHT",groupPending);savedItem(run.getId(),groups.get(1),3,"PENDING",null);
        service.recoverInterrupted();Map<String,Object> unknown=JsonUtils.readMap(items.findById(owner.getId()).get().getReceiptJson());assertEquals("unknown",unknown.get("status"));assertEquals(3,unknown.get("reservedModelCalls"));assertNull(unknown.get("modelCalls"));assertEquals(0,JsonUtils.readMap(items.findById(child.getId()).get().getReceiptJson()).get("modelCalls"));
        service.resume(project,run.getId(),null,null);Map<String,Object> result=await(project,run.getId());assertEquals("PARTIAL",result.get("status"));assertEquals(2,result.get("completedItems"));assertEquals(2,((Map<?,?>)result.get("groupProgress")).get("completed"));verify(ai,times(1)).complete(anyString(),anyString());assertEquals("UNKNOWN",items.findById(owner.getId()).get().getStatus());
    }

    @Test void changedManifestFailsExplicitlyWithoutExecutingExistingTasks()throws Exception{
        String project=project();DraftAutomationRun run=savedRun(project,"{\"execution\":\"unrecognized-group-version\"}");service.recoverInterrupted();service.resume(project,run.getId(),null,null);Map<String,Object> result=await(project,run.getId());assertEquals("FAILED",result.get("status"));assertTrue(String.valueOf(result.get("message")).contains("不能续用旧计划"));verifyNoInteractions(ai);assertEquals("{\"execution\":\"unrecognized-group-version\"}",runs.findById(run.getId()).get().getManifestJson());
    }

    @Test void concurrentBudgetReservationsCannotExceedTheRemainingNonQwenAllowance()throws Exception{
        java.lang.reflect.Method reserve=DraftAutomationService.class.getDeclaredMethod("reserveGroupCalls",AtomicInteger.class);reserve.setAccessible(true);AtomicInteger charged=new AtomicInteger(766);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{Callable<Integer> request=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return (Integer)reserve.invoke(null,charged);};Future<Integer> first=pool.submit(request),second=pool.submit(request);start.countDown();assertEquals(2,first.get(5,TimeUnit.SECONDS)+second.get(5,TimeUnit.SECONDS));assertEquals(768,charged.get());assertEquals(0,reserve.invoke(null,charged));}finally{pool.shutdownNow();}
    }
    @Test void concurrencyConfigurationRejectsUnboundedValues(){assertThrows(IllegalArgumentException.class,()->service.groupConcurrency(0));assertThrows(IllegalArgumentException.class,()->service.groupConcurrency(3));}
    @Test void taskOwnershipAndUnavailableModelAreRejectedWithoutInference(){String project=project();assertThrows(BizException.class,()->service.get(project,"foreign-run"));when(profiles.capture()).thenReturn(null);assertThrows(BizException.class,()->start(project));verifyNoInteractions(ai);}
}
