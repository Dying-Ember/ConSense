package com.consense.service.vetting;

import com.consense.ai.LlmClient;
import com.consense.common.*;
import com.consense.config.*;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.advice.AdviceService;
import com.consense.service.sources.*;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic authored actual-Java/frozen-Python producer fixtures. Real Spring/JPA/source gates and
 * local fake registry/retrieval HTTP; all neural consumers are explicit doubles. No online services. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "spring.jpa.show-sql=false","consense.ocr.enabled=false","consense.vector.provider=memory",
        "consense.llm.enabled=true","consense.llm.chat-model=synthetic-recheck-chat","consense.llm.structured-thinking=false",
        "consense.llm.num-ctx=524288","logging.level.org.hibernate.SQL=OFF",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.consense.service.vetting.CanonicalRecheckAdviceRealSeamTest$NormalSourceSql"})
@ActiveProfiles("h2")
class CanonicalRecheckAdviceRealSeamTest {
    public static class NormalSourceSql implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static volatile boolean capture;
        static final List<String> reads=new CopyOnWriteArrayList<>();
        static final List<String> bindingReads=new CopyOnWriteArrayList<>();
        @Override public String inspect(String raw){
            if(capture){String sql=raw.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
                if(sql.startsWith("select ")&&sql.contains(" from source_document ")
                        &&sql.substring(0,sql.indexOf(" from ")).contains("normal_candidate_json"))reads.add(sql);}
            if(capture){String sql=raw.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
                if(sql.startsWith("select ")&&sql.contains(" from canonical_source_binding ")
                        &&sql.substring(0,sql.indexOf(" from ")).contains("snapshot_json"))bindingReads.add(sql);}
            return raw;
        }
    }
    static final String PROJECT="authored-recheck-project", POLICY=repeat('a'), INPUT="canonical-source-chunks-upstream-scope-and-parser-projection-v1";
    static final String SIGNATURE=VettingCorpus.hash("SYNTHETIC RECHECK INDEX FIXTURE");
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:recheck_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get(System.getProperty("maven.multiModuleProjectDirectory",System.getProperty("user.dir")),"target","authored-recheck-storage",DB).toAbsolutePath().toString());}
    @Autowired AdviceService advice;
    @Autowired VettingService vetting;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    @Autowired ConsenseProperties props;
    @Autowired CanonicalEvidenceProperties canonicalProps;
    @SpyBean OerCanonicalRetrievalClient canonicalRetrieval;
    @Autowired SourceDocumentRepository documents;
    @Autowired CanonicalSourceBindingRepository bindings;
    @Autowired ProjectRepository projects;
    @SpyBean ChatMessageRepository history;
    @Autowired JdbcTemplate jdbc;
    @SpyBean CanonicalEvidenceService evidence;
    @SpyBean CanonicalCurrentSourceReader reader;
    @SpyBean OerBusinessEvidence business;
    @Autowired com.consense.service.llm.QwenGenerationLedger generationLedger;
    @MockBean(name="llmClient") LlmClient llm;
    @MockBean VettingTokenBudgetCliObserver tokenObserver;
    @TempDir Path temporary;
    HttpServer server,normalServer;ExecutorService httpWorkers;
    final Map<Long,Path> normalCasRoots=new LinkedHashMap<>();
    final Map<Long,ObjectNode> snapshots=new LinkedHashMap<>();
    final Map<Long,String> rawSnapshots=new LinkedHashMap<>();
    final Map<Long,Path> originals=new LinkedHashMap<>();
    final Map<Long,Path> casTamperTargets=new LinkedHashMap<>();
    final Map<Long,JsonNode> casLedgers=new LinkedHashMap<>();
    final List<String> events=Collections.synchronizedList(new ArrayList<>());
    final List<String> conditionalSnapshotTags=Collections.synchronizedList(new ArrayList<>());
    final Map<Long,AtomicInteger> perDocumentSnapshotReads=new ConcurrentHashMap<>();
    final AtomicReference<Runnable> healthMutation=new AtomicReference<>();
    final AtomicReference<Runnable> normalMutation=new AtomicReference<>();
    final AtomicInteger healthReads=new AtomicInteger();int healthMutationOrdinal=1;
    final AtomicInteger snapshotReads=new AtomicInteger(),indexCalls=new AtomicInteger(),retrieveCalls=new AtomicInteger(),chatCalls=new AtomicInteger(),sourceReadCalls=new AtomicInteger();
    final AtomicInteger unchangedSnapshotReads=new AtomicInteger(),snapshotResponseBytes=new AtomicInteger();
    final AtomicReference<Runnable> indexMutation=new AtomicReference<>(),retrieveMutation=new AtomicReference<>(),snapshotMutation=new AtomicReference<>(),chatMutation=new AtomicReference<>();
    final AtomicReference<Runnable> availabilityMutation=new AtomicReference<>();
    final List<JsonNode> availabilityRequests=new CopyOnWriteArrayList<>();
    final AtomicReference<java.util.function.Consumer<ObjectNode>> availabilityResponseMutation=new AtomicReference<>();
    final AtomicReference<Runnable> semanticPrepareMutation=new AtomicReference<>();
    final AtomicReference<Runnable> canonicalCatalogMutation=new AtomicReference<>();
    final AtomicReference<Runnable> statusMutation=new AtomicReference<>();
    final AtomicInteger semanticPrepareCalls=new AtomicInteger(),semanticCatalogCalls=new AtomicInteger(),semanticScanCalls=new AtomicInteger(),semanticEntryReads=new AtomicInteger(),normalPublications=new AtomicInteger();
    final List<JsonNode> compactAdvicePrepareRequests=new CopyOnWriteArrayList<>();
    final AtomicInteger compactAdvicePrepareCasReads=new AtomicInteger();final AtomicLong compactAdvicePrepareCasBytes=new AtomicLong();
    final AtomicReference<java.util.function.Consumer<ObjectNode>> retrieveResponseMutation=new AtomicReference<>();
    final AtomicReference<JsonNode> indexRequest=new AtomicReference<>();
    final Map<String,ObjectNode> remoteIndexes=new ConcurrentHashMap<>();
    ObjectNode health;

    @BeforeEach void setup() throws Exception {
        when(tokenObserver.observe(any())).thenAnswer(call->VettingTokenFixture.observe(call.getArgument(0)));
        snapshots.clear();rawSnapshots.clear();originals.clear();casTamperTargets.clear();casLedgers.clear();normalCasRoots.clear();events.clear();conditionalSnapshotTags.clear();perDocumentSnapshotReads.clear();healthMutation.set(null);
        indexMutation.set(null);retrieveMutation.set(null);snapshotMutation.set(null);chatMutation.set(null);normalMutation.set(null);
        availabilityMutation.set(null);availabilityResponseMutation.set(null);availabilityRequests.clear();retrieveResponseMutation.set(null);
        semanticPrepareMutation.set(null);canonicalCatalogMutation.set(null);semanticPrepareCalls.set(0);semanticCatalogCalls.set(0);semanticScanCalls.set(0);semanticEntryReads.set(0);normalPublications.set(0);
        statusMutation.set(null);NormalSourceSql.bindingReads.clear();
        indexRequest.set(null);remoteIndexes.clear();canonicalProps.getSourcePageRenderEvidenceIdentities().clear();canonicalProps.getRetrievalBaseUrls().remove("vetting");
        jdbc.update("DELETE FROM chat_message");jdbc.update("DELETE FROM canonical_source_binding");jdbc.update("DELETE FROM source_document");jdbc.update("DELETE FROM project");
        if(jdbc.queryForObject("select count(*) from information_schema.triggers where trigger_name in ('source_document_content_digests','canonical_source_binding_content_digests')",Integer.class)!=2)
            try(java.sql.Connection connection=jdbc.getDataSource().getConnection()){SourceContentDigestMigration.install(connection);}
        jdbc.execute("ALTER TABLE source_document ALTER COLUMN id RESTART WITH 42");jdbc.execute("ALTER TABLE chat_message ALTER COLUMN id RESTART WITH 1");
        props.setStorageRoot(temporary.toString());props.getVetting().setRequireHybridRetrieval(true);
        VettingService vettingTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(vetting);
        org.springframework.test.util.ReflectionTestUtils.setField((Object)vettingTarget,"storageService",new com.consense.service.StorageService(props));
        props.getVetting().setExpectedRetrievalContract(VettingRetrievalHealth.STRUCTURAL_CONTRACT);
        props.getVetting().setExpectedRetrievalSignatureVersion(4);props.getVetting().setExpectedRetrievalGranularity("dual-structure-v1");
        props.getVetting().setTopK(1);props.getVetting().setCandidateLimit(10);
        Project project=new Project();project.setId(PROJECT);project.setNameZhHans("AUTHORED synthetic source checks");projects.saveAndFlush(project);
        addFixture(42L,"pdf","GCC");addFixture(43L,"docx","PRE");
        health=healthFixture();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);httpWorkers=Executors.newFixedThreadPool(4);server.setExecutor(httpWorkers);
        com.sun.net.httpserver.HttpHandler handler=exchange->{
            byte[] response;int status=200;String path=exchange.getRequestURI().getPath();
            try {
                if("/health".equals(path)){events.add("health:start");if(healthReads.incrementAndGet()==healthMutationOrdinal)runOnce(healthMutation);response=JsonUtils.write(health).getBytes(StandardCharsets.UTF_8);events.add("health:end");}
                else if("/normal-observed".equals(path)){
                    normalPublications.incrementAndGet();
                    if(exchange.getLocalAddress().getPort()==server.getAddress().getPort())throw new IOException("Normal publication is isolated from the snapshot base state");
                    JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));
                    Long id=Long.valueOf(request.path("candidateGraph").path("source").path("documentId").asText());
                    String raw=produceNormal(request);ObjectNode projection=(ObjectNode)JsonUtils.parse(raw);
                    runOnce(normalMutation);
                    snapshots.put(id,projection);rawSnapshots.put(id,raw);casLedgers.put(id,JsonUtils.mapper().createArrayNode());
                    ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("projectionSha256",projection.path("projectionSha256").asText());receipt.put("chunks",projection.path("chunks").size());receipt.put("assets",0);receipt.put("immutable",true);receipt.put("qualityVerifiedByRegistry",false);receipt.put("sourceQualityChangedByRegistry",false);receipt.set("projection",projection);
                    // Preserve Python float lexemes in the real receipt transport.
                    response=("{\"projection\":"+raw+",\"projectionSha256\":\""+projection.path("projectionSha256").asText()+"\",\"chunks\":"+projection.path("chunks").size()+",\"assets\":0,\"immutable\":true,\"qualityVerifiedByRegistry\":false,\"sourceQualityChangedByRegistry\":false}").getBytes(StandardCharsets.UTF_8);
                }
                else if("/sources/read".equals(path)){
                    sourceReadCalls.incrementAndGet();events.add("sources:start");
                    JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));
                    response=JsonUtils.write(sourceReadFixture(request)).getBytes(StandardCharsets.UTF_8);
                    events.add("sources:end");
                }
                else if(path.startsWith("/canonical/")){
                    snapshotReads.incrementAndGet();conditionalSnapshotTags.add(exchange.getRequestHeaders().getFirst("If-None-Match"));runOnce(snapshotMutation);
                    Long id=idForProjection(path.substring("/canonical/".length()));
                    if(NormalObservedContract.isNormal(snapshots.get(id))&&exchange.getLocalAddress().getPort()!=normalServer.getAddress().getPort())throw new IOException("Normal projection belongs to the other independent registry state");
                    perDocumentSnapshotReads.computeIfAbsent(id,key->new AtomicInteger()).incrementAndGet();events.add("cas:"+id+":start");
                    verifyActualCas(id);response=rawSnapshots.get(id).getBytes(StandardCharsets.UTF_8);events.add("cas:"+id+":end");
                    String etag="\""+shaBytes(response)+"\"";exchange.getResponseHeaders().set("ETag",etag);
                    exchange.getResponseHeaders().set("X-Projection-Sha256",snapshots.get(id).path("projectionSha256").asText());
                    if(etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))){status=304;response=new byte[0];unchangedSnapshotReads.incrementAndGet();}
                    snapshotResponseBytes.addAndGet(response.length);
                }else if("/index-canonical".equals(path)){
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());indexCalls.incrementAndGet();
                    JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));
                    indexRequest.set(request.deepCopy());
                    events.add("index:start");runOnce(indexMutation);events.add("index:end");ObjectNode result=JsonUtils.mapper().createObjectNode();
                    result.put("projectId",request.path("projectId").asText());result.put("indexed",snapshots.values().stream().mapToInt(s->s.path("chunks").size()).sum());result.put("signature",SIGNATURE);
                    result.put("contract",VettingRetrievalHealth.STRUCTURAL_CONTRACT);result.put("targetModule","advisory");
                    result.set("canonicalProjections",request.path("projectionSha256").deepCopy());result.set("competitionScope",scope());
                    result.set("sourcePageEvidence",request.has("sourcePageEvidence")?request.path("sourcePageEvidence").deepCopy():JsonUtils.mapper().createObjectNode());
                    remoteIndexes.put(request.path("projectId").asText(),result.deepCopy());
                    response=JsonUtils.write(result).getBytes(StandardCharsets.UTF_8);
                }else if("/index-canonical/availability".equals(path)){
                    JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));events.add("availability:start");
                    availabilityRequests.add(request.deepCopy());boolean bootstrap=request.path("coldBootstrap").asBoolean();
                    ObjectNode stored=remoteIndexes.get(request.path("projectId").asText());
                    assertEquals("advisory",request.path("module").asText());
                    if(bootstrap){assertEquals("source-verified-cold-canonical-availability-v1",health.path("oer").path("indexAvailabilityBootstrapContract").asText());assertFalse(request.has("expectedSignature"));assertFalse(request.has("expectedGeneration"));}
                    if(stored!=null){if(!bootstrap)assertEquals(stored.path("signature"),request.path("expectedSignature"));assertEquals(stored.path("canonicalProjections"),request.path("projectionSha256"));}
                    if(stored==null&&health.path("oer").has("indexAvailabilityBootstrapContract")){
                        status=404;response="{\"detail\":\"Project is not indexed\"}".getBytes(StandardCharsets.UTF_8);events.add("availability:end");
                    }else{
                    ObjectNode result=stored==null?JsonUtils.mapper().createObjectNode():stored.deepCopy();
                    result.put("ready",stored!=null);result.put("readOnly",true);result.put("fullVectorIntegrityChecked",false);
                    result.put("coldBootstrap",bootstrap);
                    result.put("indexAvailabilityContract","live-canonical-index-availability-v1");result.put("vectorPoints",stored==null?0:stored.path("indexed").asInt());
                    result.put("vectorDimension",stored==null?0:2);result.put("collection","authored-availability");result.putNull("indexGeneration");
                    java.util.function.Consumer<ObjectNode> change=availabilityResponseMutation.getAndSet(null);if(change!=null)change.accept(result);
                    response=JsonUtils.write(result).getBytes(StandardCharsets.UTF_8);runOnce(availabilityMutation);events.add("availability:end");
                    }
                }else if("/index-canonical/status".equals(path)){
                    JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));events.add("status:start");
                    runOnce(statusMutation);
                    ObjectNode stored=remoteIndexes.get(request.path("projectId").asText());
                    ObjectNode result=stored==null?JsonUtils.mapper().createObjectNode():stored.deepCopy();
                    result.put("ready",stored!=null);result.put("readOnly",true);result.put("vectorPoints",stored==null?0:stored.path("indexed").asInt());
                    result.put("indexStatusContract","read-only-existing-canonical-index-v1");
                    response=JsonUtils.write(result).getBytes(StandardCharsets.UTF_8);events.add("status:end");
                }else if("/retrieve".equals(path)){
                    retrieveCalls.incrementAndGet();JsonNode request=JsonUtils.parse(new String(readAll(exchange.getRequestBody()),StandardCharsets.UTF_8));
                    events.add("retrieve:start");runOnce(retrieveMutation);events.add("retrieve:end");ObjectNode result=retrieveFixture(request.path("projectId").asText());
                    java.util.function.Consumer<ObjectNode> change=retrieveResponseMutation.getAndSet(null);if(change!=null)change.accept(result);
                    response=JsonUtils.write(result).getBytes(StandardCharsets.UTF_8);
                }else{status=404;response="{\"synthetic\":\"unknown path\"}".getBytes(StandardCharsets.UTF_8);}
            }catch(Throwable failed){status=502;response=("{\"syntheticError\":\""+failed.getClass().getSimpleName()+"\"}").getBytes(StandardCharsets.UTF_8);}
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,status==304?-1:response.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(response);}
        };server.createContext("/",handler);server.start();normalServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);normalServer.setExecutor(httpWorkers);normalServer.createContext("/",handler);normalServer.start();String url="http://127.0.0.1:"+server.getAddress().getPort();canonicalProps.setBaseUrl(url);canonicalProps.setTimeoutMs(30000);canonicalProps.getRetrievalBaseUrls().put("advisory","http://127.0.0.1:"+normalServer.getAddress().getPort());
        for(Long id:snapshots.keySet())register(id);
        assertDoesNotThrow(()->evidence.currentDisplayBindings(PROJECT,"advisory",documents.findScopeSourcesByProjectId(PROJECT)),"Authored fixture must begin with current scalar source pins");
        assertTrue(AopUtils.isAopProxy(evidence));assertTrue(AopUtils.isAopProxy(reader));
        when(llm.available()).thenReturn(true);when(llm.chatModel()).thenReturn("synthetic-recheck-chat");
        doAnswer(call->{CanonicalSourceBinding binding=call.getArgument(0);events.add("source:"+binding.getDocumentId()+":start");Object result=call.callRealMethod();events.add("source:"+binding.getDocumentId()+":end");return result;}).when(reader).assertCurrent(any(),anyString());
        doAnswer(call->{CanonicalSourceBinding binding=call.getArgument(0);events.add("source:"+binding.getDocumentId()+":start");Object result=call.callRealMethod();events.add("source:"+binding.getDocumentId()+":end");return result;}).when(reader).assertSourceCurrent(any(),anyString());
        doAnswer(call->{events.add("selected-scope:start");Object result=call.callRealMethod();events.add("selected-scope:end");return result;}).when(business).assertSelected(any(),anyList());
        doAnswer(call->{events.add("scalar:start");Object result=call.callRealMethod();events.add("scalar:end");return result;}).when(evidence).currentDisplayBindings(anyString(),anyString(),anyList());
        org.mockito.stubbing.Answer<?> repositoryDelegate=mockingDetails(history).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call->{ChatMessage message=call.getArgument(0);String event="persist-"+message.getRole();events.add(event+":start");Object result=repositoryDelegate.answer(call);events.add(event+":end");return result;}).when(history).save(any(ChatMessage.class));
        when(llm.chat(anyList())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());chatCalls.incrementAndGet();events.add("llm:start");runOnce(chatMutation);events.add("llm:end");return "{\"grounded\":true,\"answer\":\"AUTHORED supported literal\",\"citations\":[\"[1]\"]}";});
        when(llm.chatStructured(anyList(),any())).thenAnswer(call->com.consense.service.advice.AdviceBudgetFixture.contract(llm.chat(call.getArgument(0))));
        resetCounters();
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);if(normalServer!=null)normalServer.stop(0);if(httpWorkers!=null)httpWorkers.shutdownNow();}

    @Test void successfulRebuildRecordsFullEquivalentResponseAndActualBoundaryCounts() throws Exception {
        long started=System.nanoTime();IndexStatusVO result=advice.rebuildIndex(PROJECT,"fullset");long wall=System.nanoTime()-started;
        assertTrue(result.isVectorReady());assertEquals(4,result.getIndexed());
        int rounds=(int)mockingDetails(evidence).getInvocations().stream().filter(i->i.getMethod().getName().equals("currentBindings")).count();
        int gates=(int)mockingDetails(reader).getInvocations().stream().filter(i->Arrays.asList("assertCurrent","assertSourceCurrent").contains(i.getMethod().getName())).count();
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("sourceKind","synthetic_authored_actual_java_frozen_python_producer");
        receipt.put("currentBindingsRounds",rounds);receipt.put("snapshotGetCount",snapshotReads.get());receipt.put("freshSourceGateCount",gates);
        receipt.put("indexCalls",indexCalls.get());receipt.put("retrieveCalls",retrieveCalls.get());receipt.put("neuralCalls",0);receipt.put("llmDoubleCalls",chatCalls.get());receipt.put("wallNanos",wall);
        receipt.put("latencyIsSynthetic",true);receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));
        receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));
        receipt.put("verifiedFrameRounds",mockingDetails(evidence).getInvocations().stream().filter(i->i.getMethod().getName().equals("verifiedBindings")).count());write("successful-rebuild.json",receipt);
        boolean optimized=Boolean.parseBoolean(System.getProperty("consense.recheck.expectOptimized","true"));
        boolean staged=Boolean.parseBoolean(System.getProperty("consense.recheck.expectStage","true"));
        assertEquals(staged?0:optimized?7:10,rounds,"real successful Advice chain currentBindings budget");
        assertEquals(staged?18:optimized?18:24,snapshotReads.get(),"service + client real local fake registry GET count");
        assertEquals(staged?66:optimized?32:66,gates,"actual fresh proxied reader source gate count");
        if(staged){assertEquals(9,snapshotCounts().get("42"));assertEquals(9,snapshotCounts().get("43"));assertEquals(3,receipt.path("verifiedFrameRounds").asInt());assertWaitGuards("health");assertWaitGuards("index");assertWaitGuards("status");}
        assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        assertFalse(indexRequest.get().has("sourcePageEvidence"),"legacy unpinned request remains unchanged");
        String baseline=System.getProperty("consense.recheck.baselineResponse");
        if(baseline!=null){JsonNode old=JsonUtils.parse(new String(Files.readAllBytes(Paths.get(baseline)),StandardCharsets.UTF_8));
            assertEquals(old.path("completeStatusResponse"),receipt.path("completeStatusResponse"),"all JSON fields remain identical to old freeze");}
    }

    @Test void firstNormalConditionalReadReusesHeaderFirstStoredTreeWithoutTransferringBody() throws Exception {
        CanonicalSourceBinding normal=prepareNormalConditionalFixture();resetCounters();
        String etag="\""+shaBytes(rawSnapshots.get(43L).getBytes(StandardCharsets.UTF_8))+"\"";
        assertNotEquals(etag,"\""+normal.getSnapshotBytesSha256()+"\"","Stored header-first bytes have a different digest from sorted registry wire bytes");
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Collections.singletonList(43L))){
            assertEquals(snapshots.get(43L),frame.normalSnapshot(43L,normal.getProjectionSha256(),"advisory"));
            assertEquals(1,snapshotReads.get());assertEquals(1,unchangedSnapshotReads.get());assertEquals(0,snapshotResponseBytes.get());
            frame.revalidate();
        }
        assertEquals(3,snapshotReads.get());assertEquals(3,unchangedSnapshotReads.get());assertEquals(0,snapshotResponseBytes.get());
        assertEquals(Collections.nCopies(3,etag),conditionalSnapshotTags);
        resetCounters();assertEquals(snapshots.get(43L).path("chunks"),evidence.chunks(normal,"advisory"));
        assertEquals(Collections.singletonList(etag),conditionalSnapshotTags);assertEquals(1,unchangedSnapshotReads.get());assertEquals(0,snapshotResponseBytes.get());
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void borrowedCheckedIndexReturnDoesNotRepeatTheCompleteCasPass() throws Exception {
        checkedIndexReturn("unchanged");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"content","binding"})
    void borrowedCheckedIndexCloseStillRejectsCommittedSourceAndBindingDrift(String change) throws Exception {
        checkedIndexReturn(change);
    }

    private void checkedIndexReturn(String change) throws Exception {
        prepareNormalConditionalFixture();
        OerBusinessEvidence.Session session=business.openReadSession(PROJECT,"advisory",documents.findScopeSourcesByProjectId(PROJECT));
        AtomicInteger checkedSnapshots=new AtomicInteger(-1);resetCounters();
        doAnswer(call->{Object result=call.callRealMethod();checkedSnapshots.set(snapshotReads.get());
            if("content".equals(change))jdbc.update("update source_document set text_content=? where id=43","AUTHORED changed after checked index return");
            if("binding".equals(change))mutate(42L,"binding_revision");
            return result;}).when(canonicalRetrieval).indexAdvisory(anyString(),any(),eq(false),any());
        try{
            if("unchanged".equals(change)){
                business.index(session);
                assertTrue(checkedSnapshots.get()>0,"The client must complete its full post-index CAS proof");
                assertEquals(checkedSnapshots.get(),snapshotReads.get(),"Borrowed close must not repeat the same complete source CAS pass without another wait");
                int beforeOwnerClose=snapshotReads.get();session.close();
                assertEquals(beforeOwnerClose+2,snapshotReads.get(),"The actual initial-session owner must still close with a complete two-source CAS proof");
            }else{
                assertThrows(RuntimeException.class,()->business.index(session));
                assertTrue(checkedSnapshots.get()>0,"Drift must be injected after the checked client return");
                Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(canonicalRetrieval);
                Map<?,?> states=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(target,"receiptStates");
                Object state=states.get(session.indexKey),receipt=org.springframework.test.util.ReflectionTestUtils.getField(state,"receipt");
                assertNotNull(receipt);assertNull(org.springframework.test.util.ReflectionTestUtils.getField(receipt,"verifiedSourceGenerationSha256"),"Rejected lightweight close cannot attest a source generation");
            }
            assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        }finally{
            if("unchanged".equals(change))session.close();else assertThrows(RuntimeException.class,session::close);
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"status,unchanged","status,content","status,binding","retrieve,unchanged","retrieve,content","retrieve,binding"})
    void borrowedCheckedStatusAndRetrieveReturnUsesOnlyFreshSourceGuards(String operation,String change) throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");
        OerBusinessEvidence.Session session=business.openReadSession(PROJECT,"advisory",documents.findScopeSourcesByProjectId(PROJECT));
        AtomicInteger checkedSnapshots=new AtomicInteger(-1);resetCounters();
        org.mockito.stubbing.Answer<Object> checkedReturn=call->{Object result=call.callRealMethod();checkedSnapshots.set(snapshotReads.get());
            if("content".equals(change))jdbc.update("update source_document set text_content=? where id=43","AUTHORED changed after checked "+operation+" return");
            if("binding".equals(change))mutate(42L,"binding_revision");
            return result;};
        if("status".equals(operation))doAnswer(checkedReturn).when(canonicalRetrieval).indexStatus(anyString(),eq("advisory"),any(CanonicalEvidenceService.VerifiedBindingFrame.class));
        else doAnswer(checkedReturn).when(canonicalRetrieval).retrieve(anyString(),eq("advisory"),anyString(),isNull(),any(CanonicalEvidenceService.VerifiedBindingFrame.class));
        org.junit.jupiter.api.function.Executable checked=()->{
            if("status".equals(operation))assertTrue(business.indexStatus(session).ready);
            else assertFalse(business.response(session,"AUTHORED borrowed checked query",null).path("hits").isEmpty());
        };
        try{
            if("unchanged".equals(change)){
                assertDoesNotThrow(checked);assertTrue(checkedSnapshots.get()>0,"The client must finish its full post-wait CAS proof");
                assertEquals(checkedSnapshots.get(),snapshotReads.get(),"Borrowed "+operation+" close must not repeat the checked complete source CAS pass");
                int beforeOwnerClose=snapshotReads.get();session.close();
                assertEquals(beforeOwnerClose+2,snapshotReads.get(),"The actual owner must retain its complete two-source CAS close");
            }else{
                assertThrows(RuntimeException.class,checked);assertTrue(checkedSnapshots.get()>0,"Drift must follow the successful checked client return");
            }
            assertEquals(0,indexCalls.get());assertEquals("retrieve".equals(operation)?1:0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        }finally{
            if("unchanged".equals(change))session.close();else assertThrows(RuntimeException.class,session::close);
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"no_hit","exception"})
    void initialQwenOwnerClosesBeforeNoHitSavingOrRetrievalFailure(String outcome) throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");
        Object client=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(canonicalRetrieval);
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(client,"receiptStates")).clear();resetCounters();
        List<CanonicalEvidenceService.VerifiedBindingFrame> frames=new ArrayList<>();AtomicInteger ownerCloses=new AtomicInteger(),savingChecks=new AtomicInteger(),paid=new AtomicInteger();
        doAnswer(call->{List<Long> ids=call.getArgument(2);assertEquals(1,ids.size(),"A cold query must own one source at a time");if(!frames.isEmpty())assertThrows(RuntimeException.class,frames.get(frames.size()-1)::bindings);
            CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)call.callRealMethod();frames.add(frame);return frame;})
                .when(evidence).verifiedBindings(eq(PROJECT),eq("advisory"),anyList());
        doAnswer(call->{boolean closing=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(stack->stack.getClassName().endsWith("$VerifiedBindingFrame")&&"close".equals(stack.getMethodName()));
            int before=snapshotReads.get();Object result=call.callRealMethod();if(closing){assertEquals(before+1,snapshotReads.get(),"Every singleton owner exit must verify its actual source CAS");ownerCloses.incrementAndGet();events.add("owner-close:complete");}return result;
        }).when(evidence).revalidateFrame(any());
        if("no_hit".equals(outcome))retrieveResponseMutation.set(result->{result.putArray("hits");ObjectNode units=(ObjectNode)result.path("sourceUnits");units.putArray("originalHitIds");units.putArray("units");});
        else retrieveMutation.set(()->{throw new IllegalStateException("AUTHORED retrieval transport failure");});
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            @Override public void phase(String phase){if("saving".equals(phase)){
                assertEquals(0,ownerCloses.get(),"No-hit saving must not hydrate unranked Java source bodies");assertEquals(2,sourceReadCalls.get(),"No-hit saving follows complete native catalog entry and exit proofs");for(CanonicalEvidenceService.VerifiedBindingFrame frame:frames)assertThrows(RuntimeException.class,frame::bindings);
                savingChecks.incrementAndGet();events.add("saving:checked");throw new CancellationException("AUTHORED stop before unknown-answer persistence");
            }}
        };
        assertThrows(RuntimeException.class,()->askUntilPaidBoundary(paid,progress));
        int expectedCatalogs="no_hit".equals(outcome)?2:1;
        assertEquals(0,frames.size(),"An empty or failed ranking must not hydrate Java source bodies");
        for(CanonicalEvidenceService.VerifiedBindingFrame frame:frames)assertThrows(RuntimeException.class,frame::bindings);
        assertEquals(0,ownerCloses.get());assertEquals("no_hit".equals(outcome)?1:0,savingChecks.get());
        assertEquals(0,snapshotReads.get());assertEquals(expectedCatalogs,sourceReadCalls.get(),"Native catalog reads retain complete actual CAS proof before a no-hit save");assertEquals(expectedCatalogs,events.stream().filter("sources:end"::equals).count());
        assertEquals(1,retrieveCalls.get());assertEquals(0,indexCalls.get());assertEquals(0,paid.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"unchanged","source_after_build","source_during_build","missing_receipt","changed_receipt"})
    void rebuildReplyUsesFreshAvailabilityWithoutSecondFullSourceEpoch(String change) throws Exception {
        prepareNormalConditionalFixture();resetCounters();NormalSourceSql.reads.clear();NormalSourceSql.capture=false;AtomicInteger afterBuildSnapshots=new AtomicInteger(-1);
        doAnswer(call->{Object result=call.callRealMethod();afterBuildSnapshots.set(snapshotReads.get());
            NormalSourceSql.reads.clear();NormalSourceSql.capture=true;
            if("source_after_build".equals(change))jdbc.update("update source_document set text_content=? where id=43","AUTHORED changed after checked build");
            if("missing_receipt".equals(change)){Object client=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(canonicalRetrieval);
                ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(client,"receiptStates")).clear();}
            if("changed_receipt".equals(change))remoteIndexes.values().forEach(index->index.put("signature",VettingCorpus.hash("AUTHORED changed index receipt")));
            return result;}).when(canonicalRetrieval).advisoryCompactIndex(anyString(),anyList(),anyInt(),anySet(),anyString(),any(),any(),eq(true),eq(true));
        if("source_during_build".equals(change))indexMutation.set(()->jdbc.update("update source_document set text_content=? where id=43","AUTHORED changed during the actual index operation"));
        try{
            if(change.startsWith("source_")||"missing_receipt".equals(change))assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"),"A changed source or removed local attestation must not retain the just-built ready reply");
            else {IndexStatusVO result=advice.rebuildIndex(PROJECT,"fullset");assertTrue(result.isVectorReady(),"The public rebuild returns its checked operation receipt");
                if("changed_receipt".equals(change))assertFalse(advice.status(PROJECT,"fullset").isVectorReady(),"A later remote-only change must reject at the next actual availability read");}
        }finally{NormalSourceSql.capture=false;}
        if("source_during_build".equals(change)){assertEquals(-1,afterBuildSnapshots.get(),"Source drift must reject inside the actual build before it returns");assertEquals(4,snapshotReads.get());}
        else {assertEquals(8,afterBuildSnapshots.get(),"The checked build must verify both sources at entry and after its real HTTP response");
            assertEquals(afterBuildSnapshots.get(),snapshotReads.get(),"Returning a checked build receipt and reading later availability must not reopen complete snapshots");}
        assertTrue(NormalSourceSql.reads.isEmpty(),"The post-build status must not hydrate a second source CLOB graph");
        assertEquals(1,indexCalls.get());assertTrue(indexRequest.get().path("rebuild").asBoolean(),"Explicit R07 rebuild must remain true");
        assertTrue(events.contains("index:end"));assertFalse(events.contains("status:start"));assertEquals("changed_receipt".equals(change),events.contains("availability:start"));assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void linkedInitialAskRanksBeforeHydratingOnlyTheReturnedDocument() throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        List<List<Long>> hydrated=new ArrayList<>();
        doAnswer(call->{hydrated.add(new ArrayList<>(call.<List<Long>>getArgument(2)));return call.callRealMethod();})
                .when(evidence).verifiedBindings(eq(PROJECT),eq("advisory"),anyList());
        AtomicInteger paid=new AtomicInteger();NormalSourceSql.reads.clear();NormalSourceSql.capture=true;
        try{assertThrows(CancellationException.class,()->askUntilPaidBoundary(paid,null));}
        finally{NormalSourceSql.capture=false;}
        assertEquals(Collections.singletonList(Collections.singletonList(42L)),hydrated,"Initial ranking must not hydrate unreturned source 43");
        assertTrue(NormalSourceSql.reads.isEmpty(),"Unreturned normal source CLOBs must remain unopened");
        assertEquals(1,retrieveCalls.get());assertFalse(snapshotCounts().containsKey("43"));assertEquals(1,paid.get());
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"42,text_content","43,text_content","42,structured_content_json","43,structured_content_json","42,parse_coverage_json","43,normal_candidate_json"})
    void linkedAskRejectsPreAskContentDriftInReturnedAndUnreturnedSources(Long id,String column) throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        jdbc.update("update source_document set "+column+"=? where id=?","AUTHORED changed before Ask",id);
        AtomicInteger paid=new AtomicInteger();assertThrows(RuntimeException.class,()->askUntilPaidBoundary(paid,null));
        assertEquals(0,retrieveCalls.get());assertEquals(0,snapshotReads.get());assertEquals(0,paid.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(longs={42L,43L})
    void linkedAskRejectsReturnedAndUnreturnedSourceDriftDuringRanking(Long id) throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();retrieveMutation.set(()->mutate(id,"db_parsed"));
        AtomicInteger paid=new AtomicInteger();assertThrows(RuntimeException.class,()->askUntilPaidBoundary(paid,null));
        assertEquals(1,retrieveCalls.get());assertEquals(0,snapshotReads.get());assertEquals(0,paid.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void linkedAskRejectsUnreturnedSourceDriftBeforePaidWork() throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();AtomicInteger paid=new AtomicInteger();
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            @Override public void phase(String phase){if("context".equals(phase))jdbc.update("update source_document set parse_coverage_json=? where id=43","{\"complete\":false}");}
        };
        assertThrows(RuntimeException.class,()->askUntilPaidBoundary(paid,progress));
        assertEquals(0,paid.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void linkedZeroHitQueryKeepsAllSnapshotsUnopened() throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        retrieveResponseMutation.set(result->{result.putArray("hits");ObjectNode units=(ObjectNode)result.path("sourceUnits");units.putArray("originalHitIds");units.putArray("units");});
        try(OerBusinessEvidence.Session session=linkedQuerySession()){
            assertNotNull(session);assertTrue(business.adviceTextHits(session,"AUTHORED empty result").isEmpty());assertTrue(session.chunks().isEmpty());
        }
        assertEquals(1,retrieveCalls.get());assertEquals(0,snapshotReads.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"document","origin"})
    void linkedAskRejectsForgedRankedSourcesBeforePaidWork(String kind) throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        retrieveResponseMutation.set(result->{ObjectNode hit=(ObjectNode)result.path("hits").get(0);
            if("document".equals(kind))((ObjectNode)hit.path("payload")).put("documentId","9999");else hit.put("id","AUTHORED-forged-origin");});
        AtomicInteger paid=new AtomicInteger();assertThrows(RuntimeException.class,()->askUntilPaidBoundary(paid,null));
        assertEquals(0,paid.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void oldUnlinkedReceiptKeepsTheEagerCompleteProofFallback() throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");
        List<CanonicalSourceBinding> selected=evidence.currentBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        OerBusinessEvidence.Session eager=business.load(PROJECT,"advisory",documents.findScopeSourcesByProjectId(PROJECT));
        canonicalRetrieval.index(eager.indexKey,"advisory",selected);assertUnlinkedSourceGeneration(eager.indexKey);resetCounters();
        List<List<Long>> hydrated=new ArrayList<>();doAnswer(call->{hydrated.add(new ArrayList<>(call.<List<Long>>getArgument(2)));return call.callRealMethod();})
                .when(evidence).verifiedBindings(eq(PROJECT),eq("advisory"),anyList());
        AtomicInteger paid=new AtomicInteger();assertThrows(CancellationException.class,()->askUntilPaidBoundary(paid,null));
        assertTrue(hydrated.stream().allMatch(ids->ids.size()==1),"An old unlinked receipt must hydrate only checked ranked sources");
        assertEquals(Collections.singleton(42L),hydrated.stream().flatMap(Collection::stream).collect(java.util.stream.Collectors.toSet()));assertEquals(2,snapshotCounts().get("42"));assertEquals(2,snapshotReads.get());assertTrue(sourceReadCalls.get()>=2,"Complete catalog entry and exit proofs must surround the actual ready-index status");assertTrue(events.contains("status:end"));assertEquals(1,paid.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        String generation=org.springframework.test.util.ReflectionTestUtils.invokeMethod(business,"sourceGeneration",documents.findScopeSourcesByProjectId(PROJECT));
        assertTrue(canonicalRetrieval.sourceGenerationLinked("advisory",eager.indexKey,generation),"The unchanged legacy receipt must gain its exact source-generation attestation before paid work");
        assertEquals(0,indexCalls.get(),"Attesting an existing unchanged ready index must not rebuild it");
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("fixture","authoredExistingUnattestedReceiptUpgrade");receipt.put("sourceGenerationLinked",true);receipt.put("canonicalReads",snapshotReads.get());receipt.put("sourceReadHttpCalls",sourceReadCalls.get());receipt.put("indexCalls",indexCalls.get());receipt.put("paidBoundaries",paid.get());receipt.put("modelCalls",chatCalls.get());receipt.put("historyRows",history.count());receipt.put("realModelOrSourceAcceptance",false);write("legacy-receipt-source-generation-upgrade.json",receipt);
    }

    @Test void missingReceiptKeepsTheEagerCompleteProofFallback() throws Exception {
        prepareNormalConditionalFixture();
        java.util.concurrent.atomic.AtomicBoolean failClose=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call->{boolean closing=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(stack->stack.getClassName().endsWith("$VerifiedBindingFrame")&&"close".equals(stack.getMethodName()));
            if(indexCalls.get()>0&&closing&&failClose.compareAndSet(true,false))throw new IllegalStateException("AUTHORED final build frame close revalidation failure");
            return call.callRealMethod();}).when(evidence).revalidateFrame(any());
        assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertFalse(failClose.get(),"The existing complete build frame close must be exercised");
        String key=remoteIndexes.keySet().iterator().next();assertUnlinkedSourceGeneration(key);
        Object client=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(canonicalRetrieval);
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(client,"receiptStates")).clear();assertUnlinkedSourceGeneration(key);resetCounters();
        doAnswer(call->{JsonNode demand=call.getArgument(1);if("catalog".equals(demand.path("operation").asText()))throw new IllegalStateException("source catalog policy unknown");return call.callRealMethod();})
                .when(canonicalRetrieval).readSources(eq("advisory"),any(JsonNode.class),anyList());
        List<List<Long>> hydrated=new ArrayList<>();doAnswer(call->{hydrated.add(new ArrayList<>(call.<List<Long>>getArgument(2)));return call.callRealMethod();})
                .when(evidence).verifiedBindings(eq(PROJECT),eq("advisory"),anyList());
        AtomicInteger paid=new AtomicInteger();assertThrows(CancellationException.class,()->askUntilPaidBoundary(paid,null));
        assertTrue(hydrated.stream().allMatch(ids->ids.size()==1),"A missing receipt must inspect every source through singleton owned frames");
        assertEquals(new HashSet<>(Arrays.asList(42L,43L)),hydrated.stream().flatMap(Collection::stream).collect(java.util.stream.Collectors.toSet()));assertEquals(6,snapshotCounts().get("42"));assertEquals(4,snapshotCounts().get("43"));assertEquals(1,paid.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    private void assertUnlinkedSourceGeneration(String indexKey){
        String generation=org.springframework.test.util.ReflectionTestUtils.invokeMethod(business,"sourceGeneration",documents.findScopeSourcesByProjectId(PROJECT));
        assertFalse(canonicalRetrieval.sourceGenerationLinked("advisory",indexKey,generation),"An incomplete or legacy build must not publish a checked source-generation attestation");
    }

    private OerBusinessEvidence.Session linkedQuerySession(){
        java.util.function.Supplier<List<SourceDocument>> current=()->documents.findScopeSourcesByProjectId(PROJECT);
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(business,"openQuerySession",PROJECT,current);
    }
    private void askUntilPaidBoundary(AtomicInteger paid,com.consense.service.advice.AdviceProgress progress) throws Exception {
        com.consense.service.llm.QwenGenerationLedger ledger=mock(com.consense.service.llm.QwenGenerationLedger.class);
        doAnswer(call->{paid.incrementAndGet();throw new CancellationException("AUTHORED stop before model dispatch");}).when(ledger).open(anyString(),anyString(),any());
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(authoredQwen(ledger))){
            if(progress==null)advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset"));
            else advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset"),progress);
        }
    }

    @Test void idOnlyNormalFrameReadsCompleteSourceOnceThenUsesScalarStampGuards() throws Exception {
        prepareNormalConditionalFixture();resetCounters();NormalSourceSql.reads.clear();NormalSourceSql.capture=true;
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Collections.singletonList(43L))){
            assertEquals(1,NormalSourceSql.reads.size(),"Cold normal frame must read all four source fields once: "+NormalSourceSql.reads);
            NormalSourceSql.reads.clear();frame.beforeWait();frame.revalidate();frame.close();
            assertTrue(NormalSourceSql.reads.isEmpty(),"Owned normal frame guards must not reload source CLOBs: "+NormalSourceSql.reads);
        }finally{NormalSourceSql.capture=false;}
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"selected","single","word_context"})
    void selectedNormalReadersReuseVerifiedSourceStamp(String operation) throws Exception {
        CanonicalSourceBinding normal=prepareNormalConditionalFixture();resetCounters();
        JsonNode chunk=snapshots.get(43L).path("chunks").get(0);
        NormalSourceSql.reads.clear();NormalSourceSql.capture=true;
        try {
            if("selected".equals(operation))assertEquals(1,evidence.selectedPointers(PROJECT,43L,normal.getProjectionSha256(),Collections.singletonList(chunk.path("unitId").asText()),"advisory").size());
            else if("single".equals(operation))assertNotNull(evidence.pointer(PROJECT,43L,normal.getProjectionSha256(),chunk.path("unitId").asText(),"advisory"));
            else evidence.wordSourceScopeContext(PROJECT,43L,normal.getProjectionSha256(),chunk.path("id").asText(),"advisory");
            assertEquals(1,NormalSourceSql.reads.size(),"One selected-source operation must load complete normal content once, then retain its verified stamp");
        } finally {NormalSourceSql.capture=false;}
        verify(reader,never()).assertSourceCurrent(any(),eq("advisory"));
        assertEquals(1,snapshotReads.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"db_parsed","original_same_length_restored_mtime","superseded_binding","routing"})
    void selectedNormalPointersKeepFinalFreshnessGate(String change) throws Exception {
        CanonicalSourceBinding normal=prepareNormalConditionalFixture();resetCounters();AtomicInteger checks=new AtomicInteger();
        doAnswer(call->{
            if(checks.incrementAndGet()==4){
                if("superseded_binding".equals(change))jdbc.update("UPDATE canonical_source_binding SET projection_sha256=? WHERE id=?",VettingCorpus.hash("AUTHORED superseding projection"),normal.getId());
                else if("routing".equals(change))jdbc.update("UPDATE source_document SET review_role='standard' WHERE id=43");
                else mutate(43L,change);
            }
            return call.callRealMethod();
        }).when(reader).assertSourceStampCurrent(any(),eq("advisory"),any());
        String unit=snapshots.get(43L).path("chunks").get(0).path("unitId").asText();
        assertThrows(RuntimeException.class,()->evidence.selectedPointers(PROJECT,43L,normal.getProjectionSha256(),Collections.singletonList(unit),"advisory"));
        assertEquals(4,checks.get(),"Mutation must occur at the selected-pointer final check, after the initial view was verified");
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void selectedNormalPointersKeepCompleteReadFallbackWithoutSourceStamp() throws Exception {
        CanonicalSourceBinding normal=prepareNormalConditionalFixture();
        jdbc.execute("drop trigger if exists source_document_content_digests");
        jdbc.update("update source_document set text_content_sha256=null,structured_content_sha256=null,parse_coverage_sha256=null,normal_candidate_sha256=null,business_content_sha256=null where id=43");
        try(java.sql.Connection connection=jdbc.getDataSource().getConnection()){SourceContentDigestMigration.install(connection);}
        resetCounters();NormalSourceSql.reads.clear();NormalSourceSql.capture=true;
        String unit=snapshots.get(43L).path("chunks").get(0).path("unitId").asText();
        try {assertEquals(1,evidence.selectedPointers(PROJECT,43L,normal.getProjectionSha256(),Collections.singletonList(unit),"advisory").size());}
        finally {NormalSourceSql.capture=false;}
        assertTrue(NormalSourceSql.reads.size()>1,"Missing content stamps must retain full source-validation fallback");
        verify(reader,atLeastOnce()).assertSourceCurrent(any(),eq("advisory"));
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void idOnlyNormalFrameKeepsFullReadFallbackWhenTheSourceStampIsUnavailable() throws Exception {
        prepareNormalConditionalFixture();
        jdbc.execute("drop trigger if exists source_document_content_digests");
        jdbc.update("update source_document set text_content_sha256=null,structured_content_sha256=null,parse_coverage_sha256=null,normal_candidate_sha256=null,business_content_sha256=null where id=43");
        try(java.sql.Connection connection=jdbc.getDataSource().getConnection()){SourceContentDigestMigration.install(connection);}
        resetCounters();
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Collections.singletonList(43L))){
            NormalSourceSql.reads.clear();NormalSourceSql.capture=true;
            try{frame.revalidate();frame.close();assertFalse(NormalSourceSql.reads.isEmpty(),"Unavailable stamps must retain complete source verification reads");}
            finally{NormalSourceSql.capture=false;}
        }
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void idOnlyNormalFrameRejectsColdSourceGraphDriftBeforeAnyRegistryRead() throws Exception {
        prepareNormalConditionalFixture();resetCounters();mutate(43L,"db_parsed");
        assertThrows(RuntimeException.class,()->evidence.verifiedBindings(PROJECT,"advisory",Collections.singletonList(43L)));
        assertEquals(0,snapshotReads.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void firstNormalConditionalReadValidatesEquivalent200Fallback() throws Exception {
        CanonicalSourceBinding normal=prepareNormalConditionalFixture();String upstream=" \n"+rawSnapshots.get(43L);
        rawSnapshots.put(43L,upstream);resetCounters();
        assertEquals(normal.getSnapshotJson(),evidence.snapshot(PROJECT,43L,normal.getProjectionSha256(),"advisory"));
        assertEquals(1,snapshotReads.get());assertEquals(0,unchangedSnapshotReads.get());assertEquals(upstream.getBytes(StandardCharsets.UTF_8).length,snapshotResponseBytes.get());
        assertNotNull(conditionalSnapshotTags.get(0));assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void firstNormalConditionalReadRejectsChanged200Fallback() throws Exception {
        CanonicalSourceBinding normal=prepareNormalConditionalFixture();String upstream=rawSnapshots.get(43L);
        String changed=upstream.replace("candidate_unverified","changed_authored_authority");assertNotEquals(upstream,changed);
        rawSnapshots.put(43L,changed);resetCounters();
        assertThrows(CanonicalEvidenceException.class,()->evidence.snapshot(PROJECT,43L,normal.getProjectionSha256(),"advisory"));
        assertEquals(1,snapshotReads.get());assertEquals(0,unchangedSnapshotReads.get());assertTrue(snapshotResponseBytes.get()>0);
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void firstNormalConditionalReadStillVerifiesActualCasBytes() throws Exception {
        CanonicalSourceBinding normal=prepareNormalConditionalFixture();resetCounters();
        Path cas=normalCasRoots.get(43L).resolve(snapshots.get(43L).path("observationManifestRef").path("relativePath").asText());
        snapshotMutation.set(()->{try{changeExactLengthRestoredMtime(cas);}catch(IOException error){throw new IllegalStateException(error);}});
        assertThrows(CanonicalEvidenceException.class,()->evidence.snapshot(PROJECT,43L,normal.getProjectionSha256(),"advisory"));
        assertEquals(1,snapshotReads.get());assertEquals(0,unchangedSnapshotReads.get());assertNotNull(conditionalSnapshotTags.get(0));
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"original_same_length_restored_mtime","db_parsed","binding_revision"})
    void firstNormalConditionalReadRefusesSourceAndBindingChangesDuring304(String change) throws Exception {
        prepareNormalConditionalFixture();resetCounters();snapshotMutation.set(()->mutate(43L,change));
        assertThrows(RuntimeException.class,()->evidence.verifiedBindings(PROJECT,"advisory",Collections.singletonList(43L)));
        assertEquals(1,snapshotReads.get());assertEquals(1,unchangedSnapshotReads.get());assertEquals(0,snapshotResponseBytes.get());
        assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void normalParserSourcesHaveAnExplicitProductPreparationPathBeforeAdvisory() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("consense.test.python")!=null,"requires configured offline Python producer, no model");
        CanonicalSourceBinding old=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0);bindings.delete(old);bindings.flush();
        SourceDocument source=documents.findById(43L).get();
        com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("normal:body:0");block.setSource("native");block.setLocation("body/0/paragraph");block.setText("AUTHORED normal observed obligation");
        com.consense.document.DocumentBlock.WordNumbering numbering=new com.consense.document.DocumentBlock.WordNumbering();numbering.setValue(1L);numbering.setLabel("1.");block.setWordNumbering(Collections.singletonList(numbering));
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));source.setParseCoverageJson("{\"complete\":true,\"failedPages\":[],\"limitations\":[]}");source.setNativePdfEvidenceJson("AUTHORED untouched retained archive marker");documents.saveAndFlush(source);
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/advice/{project}/sources/prepare",PROJECT).param("scope","fullset"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.complete").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.selectedDocuments").value(2));
        SourceDocument prepared=documents.findById(43L).get();JsonNode graph=NormalSourceCandidateGraph.requireBound(prepared);
        assertEquals("AUTHORED untouched retained archive marker",prepared.getNativePdfEvidenceJson());
        assertFalse(graph.path("humanConfirmed").asBoolean());assertFalse(graph.path("sourceQualityAccepted").asBoolean());
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());
        CanonicalSourceBinding normal=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0);
        assertNull(normal.getAdoptionManifestSha256());assertNull(normal.getReviewReferenceSha256());assertNull(normal.getFamilyCompositionSha256());assertNull(normal.getFamilyComponentSetSha256());
        assertEquals("normal_observed",JsonUtils.parse(normal.getSnapshotJson()).path("producerKind").asText());
        CanonicalEvidenceService.VerifiedBindingFrame normalFrame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        try(CanonicalEvidenceService.VerifiedBindingFrame held=normalFrame){
            assertNull(held.normalSnapshot(42L,snapshots.get(42L).path("projectionSha256").asText(),"advisory"));
            ObjectNode detached=(ObjectNode)held.normalSnapshot(43L,normal.getProjectionSha256(),"advisory");String before=JsonUtils.write(detached);
            detached.put("humanConfirmed",true);((ArrayNode)detached.path("chunks")).removeAll();
            assertEquals(before,JsonUtils.write(held.normalSnapshot(43L,normal.getProjectionSha256(),"advisory")));
            assertEquals(before,JsonUtils.write(held.snapshot(43L,normal.getProjectionSha256(),"advisory")));
        }
        assertThrows(RuntimeException.class,()->normalFrame.normalSnapshot(43L,normal.getProjectionSha256(),"advisory"));
        assertEquals(2,evidence.currentBindings(PROJECT,"advisory",Arrays.asList(42L,43L)).size());
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=evidence.pointers(PROJECT,43L,normal.getProjectionSha256(),"advisory").get(0);
        assertEquals("normal_observed_unit",pointer.getAddressKind());assertTrue(pointer.getPhysicalPages().isEmpty());assertFalse(pointer.isHumanConfirmed());assertEquals("normal:body:0",pointer.getParts().get(0).path("blockId").asText());
        assertEquals("NORMAL_OBSERVED_MODULE_STATE_UNAVAILABLE",assertThrows(CanonicalEvidenceException.class,()->evidence.snapshot(PROJECT,43L,normal.getProjectionSha256(),"vetting")).getMessage());
        assertTrue(evidence.normalPreparable(PROJECT,Collections.singletonList(43L),"vetting"),"The other module needs its own normal observation; it must not consume this advisory snapshot");
        OerBusinessEvidence.Session normalSession=assertDoesNotThrow(()->business.load(PROJECT,"advisory",documents.findBusinessSourcesByProjectId(PROJECT)));
        IndexStatusVO indexed=advice.rebuildIndex(PROJECT,"fullset");assertTrue(indexed.isVectorReady());assertEquals(snapshots.values().stream().mapToInt(s->s.path("chunks").size()).sum(),indexed.getIndexed());assertEquals(1,indexCalls.get());
        assertFalse(business.retrieve(normalSession,"AUTHORED obligation",null).isEmpty());assertEquals(1,retrieveCalls.get());assertEquals(0,chatCalls.get());
        jdbc.update("UPDATE source_document SET parse_coverage_json=? WHERE id=?","{\"complete\":false,\"failedPages\":[1]}",43L);
        CanonicalEvidenceException stale=assertThrows(CanonicalEvidenceException.class,()->evidence.currentBindings(PROJECT,"advisory",Arrays.asList(42L,43L)));assertEquals("CANONICAL_SOURCE_STALE",stale.getMessage());
    }

    @Test void explicitPreparationRetainsCompleteDenominatorWhenBackendIsUnavailable(){
        server.stop(0);normalServer.stop(0);long before=bindings.count();SourcePreparationVO result=advice.prepareSources(PROJECT,"fullset");
        assertEquals(2,result.getSelectedDocuments());assertEquals(0,result.getPreparedDocuments());assertFalse(result.isComplete());assertFalse(result.isHumanConfirmed());assertFalse(result.isSourceQualityAccepted());
        assertEquals(2,result.getResults().size());assertEquals("failed",result.getResults().get(0).getStatus());assertEquals("evidence_backend_unavailable",result.getResults().get(0).getReason());assertEquals("unexecuted",result.getResults().get(1).getStatus());
        assertEquals(before,bindings.count());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void staleReviewedSourceNeverBecomesAnAutomaticNormalBinding(){
        long before=bindings.count();jdbc.update("UPDATE source_document SET text_content=? WHERE id=?","changed synthetic source",42L);
        SourcePreparationVO result=advice.prepareSources(PROJECT,"fullset");
        assertFalse(result.isComplete());assertEquals(2,result.getSelectedDocuments());assertEquals("failed",result.getResults().get(0).getStatus());assertEquals("source_stale",result.getResults().get(0).getReason());assertEquals("unexecuted",result.getResults().get(1).getStatus());
        assertEquals(before,bindings.count());assertNull(documents.findById(42L).get().getNormalCandidateJson());assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());
    }

    @Test void missingNormalSourceStructureFailsWithoutSilentlyReducingSelectedScope(){
        bindings.delete(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0));bindings.flush();long before=bindings.count();
        SourcePreparationVO result=advice.prepareSources(PROJECT,"fullset");
        assertEquals(2,result.getSelectedDocuments());assertEquals(1,result.getPreparedDocuments());assertFalse(result.isComplete());assertEquals("already_registered",result.getResults().get(0).getStatus());assertEquals("failed",result.getResults().get(1).getStatus());
        assertEquals(before,bindings.count());assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertNull(documents.findById(43L).get().getNormalCandidateJson());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"coverage","structure","routing","original"})
    void normalPreparationRejectsChangesDuringPublicationBeforeSavingAnyBinding(String change)throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("consense.test.python")!=null,"requires configured offline Python producer, no model");
        bindings.delete(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0));bindings.flush();
        SourceDocument source=documents.findById(43L).get();com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("normal:body:0");block.setSource("native");block.setLocation("body/0/paragraph");block.setText("AUTHORED source observation");
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));source.setParseCoverageJson("{\"complete\":true}");documents.saveAndFlush(source);
        normalMutation.set(()->{switch(change){
            case "coverage":jdbc.update("UPDATE source_document SET parse_coverage_json=? WHERE id=?","{\"complete\":false}",43L);break;
            case "structure":jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=?","[]",43L);break;
            case "routing":jdbc.update("UPDATE source_document SET review_role=? WHERE id=?","standard",43L);break;
            default:try{changeExactLengthRestoredMtime(originals.get(43L));}catch(IOException e){throw new IllegalStateException(e);}
        }});
        SourcePreparationVO result=advice.prepareSources(PROJECT,"fullset");assertFalse(result.isComplete());assertEquals(2,result.getSelectedDocuments());assertEquals(1,result.getPreparedDocuments());assertEquals("failed",result.getResults().get(1).getStatus());assertEquals("source_stale",result.getResults().get(1).getReason());
        assertTrue(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).isEmpty());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void explicitCompositeCrossesTheActualLocalHttpClientWithoutEnteringSealedSources(){
        String projection=snapshots.get(42L).path("projectionSha256").asText(),reviewed=VettingCorpus.hash("AUTHORED transport-only composite");
        canonicalProps.getSourcePageRenderEvidenceIdentities().put(projection,reviewed);
        List<CanonicalSourceBinding> selected=evidence.currentBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        List<String> original=new ArrayList<>();selected.forEach(binding->original.add(binding.getSnapshotJson()));
        canonicalRetrieval.index("authored-http-explicit-pins","advisory",selected);
        assertEquals(reviewed,indexRequest.get().path("sourcePageEvidence").path(projection).asText());
        assertEquals(1,indexRequest.get().path("sourcePageEvidence").size());assertFalse(indexRequest.get().has("chunks"));
        for(int i=0;i<selected.size();i++)assertEquals(original.get(i),selected.get(i).getSnapshotJson());
        assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        canonicalProps.getSourcePageRenderEvidenceIdentities().clear();
        assertFalse(canonicalRetrieval.indexStatus("authored-http-explicit-pins","advisory",selected).ready);
    }

    static Stream<Arguments> sourceMutations(){return Stream.of(42L,43L).flatMap(id->Stream.of("original_same_length_restored_mtime","db_parsed","binding_revision","cas_same_length_restored_mtime").map(kind->Arguments.of(id,kind)));}
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringIndexRefusesReadyAndSavesZeroHistory(Long id,String kind){
        indexMutation.set(()->mutate(id,kind));assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("index-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftBeforePublicSourceReadRefusesWithoutIndexOrHistory(Long id,String kind){
        mutate(id,kind);assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("before-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringRetrievalPreventsLlmAndHistory(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();retrieveMutation.set(()->mutate(id,kind));
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("retrieve-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringLlmDoublePreventsHistory(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();chatMutation.set(()->mutate(id,kind));
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,chatCalls.get());assertEquals(0,history.count());writeNegative("chat-"+id+"-"+kind);
    }
    @Test void newSelectedScopeMemberDuringIndexIsDetectedAfterRemoteBoundary(){
        indexMutation.set(this::newScopeMember);assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(1,indexCalls.get());assertEquals(0,history.count());writeNegative("index-new-selected-member");
    }
    @Test void newSelectedScopeMemberDuringLlmDoublePreventsHistory(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();chatMutation.set(this::newScopeMember);
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,chatCalls.get());assertEquals(0,history.count());writeNegative("chat-new-selected-member");
    }
    @Test void newSelectedScopeMemberDuringRetrievalPreventsLlmAndHistory(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();retrieveMutation.set(this::newScopeMember);
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertEquals(1,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("retrieve-new-selected-member");
    }
    @Test void dbRevisionImmediatelyBeforeHistoryGatePreventsAtomicHistoryWrites(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();AtomicBoolean changed=new AtomicBoolean();
        doAnswer(call->{if(chatCalls.get()==1&&changed.compareAndSet(false,true))mutate(43L,"db_parsed");return call.callRealMethod();}).when(business).assertSelected(any(),anyList());
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        assertTrue(changed.get());assertEquals(1,chatCalls.get());assertEquals(0,history.count());writeNegative("before-history-gate-db-revision");
    }
    @Test void publicSnapshotRechecksDbRevisionAfterItsRemoteResponse(){
        snapshotMutation.set(()->mutate(43L,"db_parsed"));
        assertThrows(RuntimeException.class,()->evidence.snapshot(PROJECT,43L,snapshots.get(43L).path("projectionSha256").asText(),"advisory"));
        assertEquals(1,snapshotReads.get());assertEquals(0,history.count());writeNegative("snapshot-post-db-revision");
    }
    @Test void directPdfChunksRetainTheirIndependentPreSourceGate(){
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,42L).get(0);mutate(42L,"original_same_length_restored_mtime");
        assertThrows(RuntimeException.class,()->evidence.chunks(binding,"advisory"));assertEquals(0,snapshotReads.get());assertEquals(0,history.count());writeNegative("direct-pdf-chunks-pre-original");
    }
    @Test void savingUsesScopeCatalogWhileKeepingCompleteCurrentSourceAndCasProof()throws Exception{
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        doAnswer(call->{CanonicalSourceBinding binding=call.getArgument(0);Object result=call.callRealMethod();events.add("source:"+binding.getDocumentId()+":end");return result;})
                .when(reader).captureNormalSourceStamp(any(),eq("advisory"),any());
        AtomicInteger saving=new AtomicInteger();
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            @Override public void phase(String phase){if("saving".equals(phase)){saving.incrementAndGet();NormalSourceSql.reads.clear();NormalSourceSql.capture=true;events.add("saving:entered");}}
        };
        AskResponseVO result;
        try{result=advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset"),progress);}
        finally{NormalSourceSql.capture=false;}
        List<String> catalogs=NormalSourceSql.reads.stream().filter(sql->sql.matches("(?s).*\\bwhere\\s+\\w+\\.project_id\\s*=\\s*\\?.*")).collect(java.util.stream.Collectors.toList());
        assertTrue(catalogs.isEmpty(),"Saving needs scope identities before the separate complete source proof; redundant full project catalog SQL: "+catalogs);
        assertFalse(NormalSourceSql.reads.isEmpty(),"The separate per-source complete content proof must remain active during saving");
        assertEquals(1,saving.get());assertTrue(result.isGrounded());assertEquals(1,chatCalls.get());assertEquals(2,history.count());
        List<String> sequence=new ArrayList<>(events);int start=sequence.indexOf("saving:entered"),firstSave=sequence.indexOf("persist-user:start");
        assertTrue(start>sequence.lastIndexOf("llm:end")&&firstSave>start);
        for(long id:new long[]{42,43}){assertTrue(sequence.subList(start+1,firstSave).contains("source:"+id+":end"));assertTrue(sequence.subList(start+1,firstSave).contains("cas:"+id+":end"));}
        assertTrue(sequence.contains("persist-assistant:end"));
    }

    @Test void successfulAskKeepsLiteralWholeUnitsUnknownAndTwoAtomicHistoryRows(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();AskResponseVO result=advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset"));
        assertTrue(result.isGrounded());assertEquals(1,chatCalls.get());assertEquals(2,history.count());
        assertTrue(mockingDetails(business).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("currentDisplayIdentity")));
        assertTrue(snapshotReads.get()<=80,"A two-source Ask must avoid repeated full canonical GETs at every model preflight guard");
        JsonNode source=JsonUtils.mapper().valueToTree(result).path("sources").get(0);
        assertFalse(source.path("canonicalSourceUnit").path("semanticScopeVerified").asBoolean());
        assertEquals("unknown",source.path("canonicalSourceUnit").path("qualifiersComplete").asText());
        assertEquals(snapshots.get(42L).path("chunks").get(0).path("content").asText(),source.path("content").asText());
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.set("completeResponse",JsonUtils.mapper().valueToTree(result));receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));
        receipt.put("historyRows",history.count());receipt.put("llmIsDouble",true);receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));write("successful-ask.json",receipt);
        assertScalarAvailabilityGuards();assertWaitGuards("health");assertWaitGuards("retrieve");assertWaitGuards("llm");
        List<String> sequence=new ArrayList<>(events);int lastModel=sequence.lastIndexOf("llm:end"),firstSave=sequence.indexOf("persist-user:start");
        assertTrue(lastModel>=0&&firstSave>lastModel);for(long id:new long[]{42,43}){assertTrue(sequence.subList(lastModel+1,firstSave).contains("source:"+id+":end"));assertTrue(sequence.subList(lastModel+1,firstSave).contains("cas:"+id+":end"));}
        assertTrue(sequence.contains("persist-assistant:end"));
    }


    @Test void independentStatusCallRecordsPerDocumentReadsAndUnchangedFullResponse(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();long start=System.nanoTime();IndexStatusVO result=advice.status(PROJECT,"fullset");
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("sourceKind","synthetic_actual_spring_h2_local_http");receipt.put("latencyIsSynthetic",true);receipt.put("wallNanos",System.nanoTime()-start);
        receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));receipt.put("snapshotGetCount",snapshotReads.get());receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));
        receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));write("independent-status.json",receipt);
        assertEquals(JsonUtils.parse("{\"chunks\":4,\"indexed\":4,\"vectorProvider\":\"canonical-oer\",\"collection\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"embedModel\":\"BAAI/bge-m3\",\"llmModel\":\"synthetic-recheck-chat\",\"llmReady\":true,\"vectorReady\":true,\"ocrReady\":false,\"message\":{\"zhHans\":\"当前范围的规范证据索引就绪，共 4 个来源切片。\",\"zhHant\":\"當前範圍的規範證據索引就緒，共 4 個來源切片。\",\"en\":\"Canonical evidence index ready for the selected scope: 4 chunks.\"},\"stale\":false,\"eligibleDocuments\":2,\"indexedDocuments\":2,\"embeddingReady\":true,\"evidenceBackend\":\"canonical\",\"sourceScope\":\"fullset\",\"indexKey\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"indexSignature\":\"48f27e00d69a2c9b34799ae48a0b32896d8ad42228df3f883d5f5d2e2d71d35d\"}"),JsonUtils.mapper().valueToTree(result),"complete stage02 ready response remains identical");
        assertTrue(result.isVectorReady());assertEquals(0,snapshotReads.get());assertTrue(snapshotCounts().isEmpty());assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());assertScalarAvailabilityGuards();
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringHealthPreventsIndexAndHistory(Long id,String kind){
        healthMutation.set(()->mutate(id,kind));assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("health-before-index-"+id+"-"+kind);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringAvailabilityResponseOrExplicitIntegrityRefusesReady(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        if("cas_same_length_restored_mtime".equals(kind)){healthMutation.set(()->mutate(id,kind));assertThrows(RuntimeException.class,()->business.currentIndexStatus(PROJECT,"advisory",()->documents.findScopeSourcesByProjectId(PROJECT)));}
        else {availabilityMutation.set(()->mutate(id,kind));IndexStatusVO result=advice.status(PROJECT,"fullset");assertFalse(result.isVectorReady());assertEquals(0,snapshotReads.get());assertTrue(events.contains("availability:end"));}
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("health-status-"+id+"-"+kind);
    }
    @Test void changedCasDuringFinalIndexHealthCannotPublishAReceiptOrModelAnswer(){
        healthMutationOrdinal=2;healthMutation.set(()->mutate(42L,"cas_same_length_restored_mtime"));
        assertThrows(RuntimeException.class,()->advice.rebuildIndex(PROJECT,"fullset"));
        assertEquals(1,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        assertTrue(snapshotReads.get()>2,"the final health wait must be followed by an actual source read");
    }
    @Test void newEligibleSelectedScopeMemberDuringStatusHealthRefusesOldReadyResponse(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();healthMutation.set(this::newScopeMember);IndexStatusVO result=advice.status(PROJECT,"fullset");
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("sourceKind","synthetic_actual_spring_h2_local_http");receipt.put("eligibleCatalogDocumentsAfterWait",documents.count());
        receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));receipt.put("indexCalls",indexCalls.get());receipt.put("llmDoubleCalls",chatCalls.get());receipt.put("historyRows",history.count());
        write("new-selected-scope-during-status-health.json",receipt);
        assertEquals(3,documents.count());assertFalse(result.isVectorReady(),"health wait introduced a new eligible different-name PRE; old selected-source receipt cannot be ready");
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @ParameterizedTest @MethodSource("sourceMutations") void persistentSourceDriftDuringRetrieveHealthPreventsRetrievalLlmAndHistory(Long id,String kind){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();healthMutationOrdinal=2;healthMutation.set(()->mutate(id,kind));
        assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED full native unit","fullset")));
        assertEquals(2,healthReads.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());writeNegative("health-before-retrieve-"+id+"-"+kind);
    }
    @Test void frameReturnsDetachedBindingsSnapshotsAndPointersAndPerformsFreshClose(){
        String projection=snapshots.get(42L).path("projectionSha256").asText();CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        evidence.assertFrameOwner(frame,PROJECT,"advisory");String before=JsonUtils.write(frame.snapshot(42L,projection,"advisory"));
        assertNull(frame.normalSnapshot(42L,projection,"advisory"));
        assertThrows(UnsupportedOperationException.class,()->frame.bindings().clear());frame.bindings().get(0).setOriginalSourceSha256(repeat('f'));
        ((ObjectNode)frame.snapshot(42L,projection,"advisory")).put("humanConfirmed",true);
        String pointerBefore=JsonUtils.write(frame.pointers(42L,projection,"advisory"));com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=frame.pointers(42L,projection,"advisory").get(0);
        pointer.setHumanConfirmed(true);((ArrayNode)pointer.getParts()).removeAll();pointer.getPhysicalPages().clear();
        assertEquals(before,JsonUtils.write(frame.snapshot(42L,projection,"advisory")));assertEquals(pointerBefore,JsonUtils.write(frame.pointers(42L,projection,"advisory")));
        assertEquals(snapshots.get(42L).path("source").path("sourceSha256").asText(),frame.bindings().get(0).getOriginalSourceSha256());
        assertEquals(2,snapshotReads.get());frame.close();assertEquals(4,snapshotReads.get());frame.close();assertEquals(4,snapshotReads.get());
        assertThrows(RuntimeException.class,frame::bindings);assertThrows(RuntimeException.class,()->frame.snapshot(42L,projection,"advisory"));assertThrows(RuntimeException.class,()->frame.normalSnapshot(42L,projection,"advisory"));assertEquals(0,history.count());
    }
    @Test void unchangedFrameRechecksCasWithoutTransferringTheCompleteSnapshotsAgain(){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        int completeBytes=rawSnapshots.values().stream().mapToInt(raw->raw.getBytes(StandardCharsets.UTF_8).length).sum();
        assertEquals(completeBytes,snapshotResponseBytes.get());assertEquals(0,unchangedSnapshotReads.get());
        frame.revalidate();frame.close();
        assertEquals(6,snapshotReads.get());assertEquals(4,unchangedSnapshotReads.get());assertEquals(completeBytes,snapshotResponseBytes.get());
        System.out.println("CONDITIONAL_FRAME_TRANSPORT fullSnapshotBytes="+completeBytes+" repeatedChecks=4 repeatedSnapshotBodyBytes=0");
    }
    @Test void conditionalFrameStillRejectsBorrowedSnapshotMutation(){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        ((ObjectNode)frame.snapshotForRead(42L,snapshots.get(42L).path("projectionSha256").asText(),"advisory")).put("callerMutation",true);
        assertEquals("CANONICAL_FRAME_SNAPSHOT_CHANGED",assertThrows(CanonicalEvidenceException.class,frame::close).getMessage());
        assertEquals(1,unchangedSnapshotReads.get());assertThrows(RuntimeException.class,frame::bindings);
    }
    @ParameterizedTest @MethodSource("sourceMutations") void frameCloseRefusesActualSourceCasAndCommittedBindingDriftAndAlwaysClears(Long id,String kind){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));mutate(id,kind);
        assertThrows(RuntimeException.class,frame::close);assertThrows(RuntimeException.class,frame::bindings);frame.close();assertEquals(0,history.count());
    }
    @Test void frameRejectsWrongThreadProjectModuleProjectionDocumentAndServiceOwner()throws Exception{
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));String projection=snapshots.get(42L).path("projectionSha256").asText();
        ExecutorService other=Executors.newSingleThreadExecutor();try{Future<Boolean> future=other.submit(()->{
            assertThrows(RuntimeException.class,frame::bindings);assertThrows(RuntimeException.class,frame::close);assertThrows(RuntimeException.class,frame::revalidate);assertThrows(RuntimeException.class,()->frame.normalSnapshot(42L,projection,"advisory"));return true;});assertTrue(future.get(10,TimeUnit.SECONDS));}finally{other.shutdownNow();}
        assertThrows(RuntimeException.class,()->frame.snapshot(42L,projection,"vetting"));assertThrows(RuntimeException.class,()->frame.snapshot(42L,repeat('b'),"advisory"));
        assertThrows(RuntimeException.class,()->frame.normalSnapshot(42L,projection,"vetting"));assertThrows(RuntimeException.class,()->frame.normalSnapshot(42L,repeat('b'),"advisory"));assertThrows(RuntimeException.class,()->frame.normalSnapshot(999L,projection,"advisory"));
        assertThrows(RuntimeException.class,()->frame.snapshot(999L,projection,"advisory"));assertThrows(RuntimeException.class,()->evidence.assertFrameOwner(frame,"another","advisory"));
        CanonicalEvidenceService another=new CanonicalEvidenceService(documents,bindings,new CanonicalEvidenceBackend(canonicalProps),props);
        assertThrows(RuntimeException.class,()->another.assertFrameOwner(frame,PROJECT,"advisory"));frame.close();assertEquals(4,snapshotReads.get());assertEquals(0,history.count());
        for(java.lang.reflect.Constructor<?> constructor:CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructors())if(!constructor.isSynthetic())assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        for(java.lang.reflect.Field field:OerBusinessEvidence.Session.class.getDeclaredFields())assertNotEquals(CanonicalEvidenceService.VerifiedBindingFrame.class,field.getType());
    }
    @Test void exceptionalStageClosesAndSubsequentStageReadsFreshCas(){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        assertThrows(IllegalArgumentException.class,()->{try(CanonicalEvidenceService.VerifiedBindingFrame retained=frame){throw new IllegalArgumentException("synthetic callback failed");}});
        assertThrows(RuntimeException.class,frame::bindings);assertEquals(4,snapshotReads.get());
        try(CanonicalEvidenceService.VerifiedBindingFrame fresh=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L))){assertEquals(6,snapshotReads.get());assertEquals(2,fresh.bindings().size());}
        assertEquals(8,snapshotReads.get());assertEquals(0,history.count());
    }
    @Test void bindingRevisionDuringFirstFrameCasCannotHideInFactoryPersistenceContext(){
        snapshotMutation.set(()->mutate(42L,"binding_revision"));
        assertThrows(RuntimeException.class,()->evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L)));
        assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @Test void bindingRevisionDuringCloseCasCannotHideInClosePersistenceContext(){
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(PROJECT,"advisory",Arrays.asList(42L,43L));
        snapshotMutation.set(()->mutate(42L,"binding_revision"));assertThrows(RuntimeException.class,frame::close);
        assertThrows(RuntimeException.class,frame::bindings);assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @Test void pointerTableGroupDeepCopyIsPureAndPreservesExplicitNull()throws Exception{
        java.lang.reflect.Method method=CanonicalEvidenceService.class.getDeclaredMethod("pointer",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class,String.class);method.setAccessible(true);
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,42L).get(0);ObjectNode source=snapshots.get(42L).deepCopy();ObjectNode chunk=(ObjectNode)source.path("chunks").get(0);
        // This authored unsealed alias probe exercises only DTO detachment; never registry/source acceptance.
        chunk.putObject("tableGroup").put("authoredAliasProbe","complete");com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer)method.invoke(null,binding,source,chunk,"advisory");
        ((ObjectNode)pointer.getTableGroup()).put("authoredAliasProbe","changed");assertEquals("complete",chunk.path("tableGroup").path("authoredAliasProbe").asText());
        chunk.putNull("tableGroup");pointer=(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer)method.invoke(null,binding,source,chunk,"advisory");assertTrue(pointer.getTableGroup().isNull());
        chunk.remove("tableGroup");pointer=(com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer)method.invoke(null,binding,source,chunk,"advisory");assertNull(pointer.getTableGroup());assertEquals(0,snapshotReads.get());
    }

    void addFixture(Long id,String kind,String key)throws Exception{
        String stem="authored-"+kind+id;String raw=new String(resource(stem+"-public.json"),StandardCharsets.UTF_8);ObjectNode s=(ObjectNode)JsonUtils.parse(raw);
        assertEquals(String.valueOf(id),s.path("projectionIdentity").path("documentId").asText());
        CanonicalEvidenceService.validateSnapshot(raw,s.path("projectionSha256").asText());snapshots.put(id,s);rawSnapshots.put(id,raw);
        Path original=temporary.resolve(stem+("pdf".equals(kind)?".pdf":".docx"));Files.write(original,resource(stem+"-source."+("pdf".equals(kind)?"pdf":"docx")));originals.put(id,original);
        assertEquals(s.path("source").path("sourceSha256").asText(),shaBytes(Files.readAllBytes(original)));
        JsonNode ledger=JsonUtils.parse(new String(resource(kind+"-cas-ledger.json"),StandardCharsets.UTF_8));casLedgers.put(id,ledger);
        for(JsonNode descriptor:ledger){String relative=descriptor.path("relativePath").asText();Path destination=temporary.resolve(relative);Files.createDirectories(destination.getParent());Files.write(destination,resource(relative));}
        casTamperTargets.put(id,temporary.resolve(ledger.get(0).path("relativePath").asText()));
        SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey(key);d.setFileName(stem+("pdf".equals(kind)?".pdf":".docx"));
        d.setReviewRole("tender");d.setParseStatus("PARSED");d.setStoragePath(original.toString());d.setSourceSha256(s.path("source").path("sourceSha256").asText());
        d.setTextContent("AUTHORED synthetic Java parsed revision "+id);d.setStructuredContentJson("[]");
        OerDocumentIdentity route=new OerDocumentIdentity();route.setSourceSha256(d.getSourceSha256());route.setKind(key);route.setSourcePurpose("project_document");route.setRole("tender");
        route.setIdentityStatus("content_supported");route.setEligibleModules(Arrays.asList("vetting","advisory"));d.setOerIdentityJson(JsonUtils.write(route));
        assertEquals(id,documents.saveAndFlush(d).getId());
    }
    void register(Long id){JsonNode s=snapshots.get(id);SourceDocument d=documents.findById(id).get();
        evidence.registerReviewed(PROJECT,id,new CanonicalEvidenceService.ReviewedRegistration(s.path("projectionSha256").asText(),d.getSourceSha256(),VettingCorpus.sourceHash(d),
            s.path("source").path("sourceRevisionSha256").asText(),POLICY,s.path("projectionIdentity").path(id==42L?"adoptionManifestSha256":"manifestSha256").asText(),s.path("manifest").path("reference").path("evidenceSha256").asText(),"advisory"));}
    ObjectNode healthFixture()throws Exception{
        ObjectNode h=VettingDualStructureHealthTest.health();OerCudaHealthFixtures.apply(h);ObjectNode scope=JsonUtils.mapper().createObjectNode();scope.put("manifestSha256",POLICY);ObjectNode oer=h.putObject("oer");
        oer.put("inputContract",INPUT);oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");oer.put("recipe","scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1");
        oer.put("targetModule","advisory");oer.put("sourceQualityVerifiedByHealth",false);oer.put("parserCandidatesAcceptedByRetrieval",false);oer.put("canonicalProjectionEndpoint","/project");oer.set("scopePolicy",scope);
        oer.put("indexStatusContract","read-only-existing-canonical-index-v1");
        oer.put("indexAvailabilityContract","live-canonical-index-availability-v1");
        oer.putObject("sourceRead").put("contract",OerCanonicalRetrievalClient.SOURCE_READ).put("endpoint","/sources/read");
        oer.set("normalObserved",JsonUtils.parse(new String(getClass().getResourceAsStream("/canonical/normal-observed/authored-current-v3-capability.json").readAllBytes(),StandardCharsets.UTF_8)).path("oer").path("normalObserved"));
        oer.set("nativeCanonical",NativeCanonicalFixture.capability());oer.set("nativePdfCanonical",NativePdfCanonicalFixture.capability());
        ObjectNode windows=(ObjectNode)h.path("retrieval").path("sourceWindows");windows.set("competitionScope",scope.deepCopy());windows.put("oerInputContract",INPUT);windows.put("oerTargetModule","advisory");
        ObjectNode algorithms=(ObjectNode)windows.path("algorithms");
        for(String name:Arrays.asList("oer_server.py","competition_scope.py","artifact_store.py","structured_parser_evidence.py","canonical_projection.py","oer_source_registry.py","oer_request_evidence.py"))algorithms.put(name,VettingCorpus.hash("AUTHORED health "+name));
        for(String name:Arrays.asList("normal_observed_projection.py","normal_observed_server.py","unified_source_registry.py"))algorithms.put(name,VettingCorpus.hash("AUTHORED health "+name));
        for(String name:NativeCanonicalContract.ALGORITHMS)algorithms.put(name,VettingCorpus.hash("AUTHORED health "+name));
        algorithms.setAll(NativePdfCanonicalFixture.algorithms(snapshots.get(42L)));oer.put("sourcePageIndexContract","explicit-composite-index-pins-v1");return h;
    }
    ObjectNode sourceReadFixture(JsonNode request)throws Exception{
        if(("vetting".equals(request.path("module").asText())||request.has("normalProofVersion"))&&"semantic_units".equals(request.path("view").asText())){
            if(!"prepare".equals(request.path("operation").asText())){
                if("catalog".equals(request.path("operation").asText()))semanticCatalogCalls.incrementAndGet();
                if("scan".equals(request.path("operation").asText()))semanticScanCalls.incrementAndGet();
                throw new IOException("AUTHORED stop after successful semantic frame close");}
            semanticPrepareCalls.incrementAndGet();semanticEntryReads.set(snapshotReads.get());
            if(request.has("normalProofVersion"))compactAdvicePrepareRequests.add(request.deepCopy());
            ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("contract",OerCanonicalRetrievalClient.SOURCE_READ);result.put("module",request.path("module").asText());result.put("operation","prepare");result.put("view","semantic_units");
            result.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);result.put("sourceUnitPolicy",VettingRetrievalSourceUnits.POLICY);result.put("sourceQualityVerified",false);
            result.set("projectionSha256",request.path("projectionSha256").deepCopy());result.putArray("origins");result.putArray("units");result.put("readManifestToken",VettingCorpus.hash("AUTHORED semantic preparation token"));
            ArrayNode sources=result.putArray("sources"),manifests=result.putArray("readManifestSha256");int total=0;
            Map<Long,Path> preparedOriginalPaths=new LinkedHashMap<>();Map<Long,String> preparedOriginalShas=new LinkedHashMap<>();
            for(JsonNode address:request.path("projectionSha256")){
                Long id=idForProjection(address.asText());JsonNode snapshot=snapshots.get(id),identity=snapshot.path("projectionIdentity");
                preparedOriginalPaths.put(id,Paths.get(SourceDocumentRepository.scopeSnapshot(documents.findScopeMetadataById(id).orElseThrow()).getStoragePath()));preparedOriginalShas.put(id,snapshot.path("source").path("sourceSha256").asText());
                ObjectNode row=sources.addObject();row.put("projectionSha256",address.asText());row.put("documentId",String.valueOf(id));row.set("originalSourceSha256",snapshot.path("source").path("sourceSha256"));row.set("sourceHash",snapshot.path("sourceHash"));row.set("javaParsedSourceHash",snapshot.path("source").path("parsedSourceHash"));row.set("sourceRevisionSha256",snapshot.path("source").path("sourceRevisionSha256"));
                int count=snapshots.get(id).path("chunks").size();row.put("chunkCount",count);total+=count;manifests.add(VettingCorpus.hash("AUTHORED semantic manifest "+id));
            }
            if(request.has("normalProofVersion")){result.set("normalProofVersion",request.path("normalProofVersion"));ArrayNode proofs=result.putArray("normalProofs");for(JsonNode address:request.path("projectionSha256")){
                Long id=idForProjection(address.asText());JsonNode snapshot=snapshots.get(id);ObjectNode proof=proofs.addObject();for(String field:Arrays.asList("projectionSha256","producerKind","projectionIdentity","projectionRevisionSha256","sourceHash","source"))proof.set(field,snapshot.path(field).deepCopy());proof.set("sourceBinding",snapshot.path("manifest").path("sourceBinding").deepCopy());proof.put("snapshotSha256",shaBytes(rawSnapshots.get(id).getBytes(StandardCharsets.UTF_8)));proof.put("parentSha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(snapshot.path("chunks")))));
            }}
            result.putObject("coverage").put("status","prepared").put("totalOrigins",total);runOnce(semanticPrepareMutation);
            // The native prepare owner verifies actual dependencies on exit before returning its receipt.
            for(JsonNode address:request.path("projectionSha256")){
                Long id=idForProjection(address.asText());verifyActualCas(id);
                if(request.has("normalProofVersion")){compactAdvicePrepareCasReads.incrementAndGet();compactAdvicePrepareCasBytes.addAndGet(Files.size(normalCasRoots.get(id).resolve(snapshots.get(id).path("observationManifestRef").path("relativePath").asText())));}
                if(!preparedOriginalShas.get(id).equals(shaBytes(Files.readAllBytes(preparedOriginalPaths.get(id)))))throw new IOException("Authored native prepare original bytes changed");
            }
            return result;
        }
        String operation=request.path("operation").asText();assertTrue(Arrays.asList("catalog","ids").contains(operation));
        if("catalog".equals(operation))runOnce(canonicalCatalogMutation);
        assertEquals("advisory",request.path("module").asText());assertEquals("canonical_units",request.path("view").asText());
        Map<String,JsonNode> parents=new LinkedHashMap<>();ArrayNode sources=JsonUtils.mapper().createArrayNode();
        for(JsonNode address:request.path("projectionSha256")){
            Long id=idForProjection(address.asText());verifyActualCas(id);JsonNode snapshot=snapshots.get(id),source=snapshot.path("source");
            if(NormalObservedContract.isNormal(snapshot)&&!request.path("module").equals(snapshot.path("projectionIdentity").path("module")))throw new IOException("Authored native catalog module differs");
            ObjectNode row=sources.addObject();row.put("projectionSha256",address.asText());row.put("documentId",String.valueOf(id));row.set("originalSourceSha256",source.path("sourceSha256"));row.set("sourceHash",snapshot.path("sourceHash"));row.set("sourceRevisionSha256",source.path("sourceRevisionSha256"));row.put("chunkCount",snapshot.path("chunks").size());
            for(JsonNode chunk:snapshot.path("chunks"))parents.put(chunk.path("id").asText(),chunk);
        }
        ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("contract",OerCanonicalRetrievalClient.SOURCE_READ);result.put("module","advisory");result.put("operation",operation);result.put("view","canonical_units");result.set("projectionSha256",request.path("projectionSha256").deepCopy());result.put("sourceUnitPolicy",VettingRetrievalSourceUnits.POLICY);result.put("sourceQualityVerified",false);result.set("sources",sources);result.putNull("nextCursor");
        ObjectNode scope=result.putObject("scopeIdentity");scope.set("sources",sources.deepCopy());scope.put("module","advisory");scope.putObject("policyIdentity").put("manifestSha256",POLICY);scope.put("sourceUnitPolicy",VettingRetrievalSourceUnits.POLICY);scope.put("view","canonical_units");scope.put("softwareIdentitySha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(health.path("retrieval").path("sourceWindows").path("algorithms")))));scope.put("originOrderSha256",VettingCorpus.hash(JsonUtils.write(new ArrayList<>(parents.keySet()))));scope.put("scopeSha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(scope))));
        ArrayNode origins=result.putArray("origins"),units=result.putArray("units"),returned=JsonUtils.mapper().createArrayNode();
        if("ids".equals(operation))for(JsonNode id:request.path("originIds")){JsonNode origin=parents.get(id.asText());assertNotNull(origin);origins.add(origin.deepCopy());units.add(OerCanonicalSourceUnits.resolve(parents,origin));returned.add(id.asText());}
        ObjectNode coverage=result.putObject("coverage");coverage.put("status","catalog".equals(operation)?"catalog":"complete");coverage.put("totalOrigins",parents.size());coverage.put("returnedOrigins",origins.size());coverage.set("requestedOriginIds",returned.deepCopy());coverage.set("returnedOriginIds",returned);return result;
    }
    CanonicalSourceBinding prepareNormalConditionalFixture() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("consense.test.python")!=null,"requires configured offline Python producer, no model");
        bindings.delete(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0));bindings.flush();
        SourceDocument source=documents.findById(43L).get();com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();
        block.setId("normal:body:0");block.setSource("native");block.setLocation("body/0/paragraph");block.setText("AUTHORED first conditional normal observation");
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));source.setParseCoverageJson("{\"complete\":true}");documents.saveAndFlush(source);
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());
        CanonicalSourceBinding normal=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0);
        String raw=rawSnapshots.get(43L),schema="\"schema\":"+JsonUtils.write(snapshots.get(43L).path("schema"));int at=raw.indexOf(schema);
        assertTrue(at>1);assertEquals(',',raw.charAt(at-1));assertEquals(',',raw.charAt(at+schema.length()));
        String headerFirst="{"+schema+","+raw.substring(1,at-1)+raw.substring(at+schema.length());
        NormalObservedContract.validate(headerFirst,normal.getProjectionSha256());
        normal.setSnapshotJson(headerFirst);normal.setSnapshotBytesSha256(shaBytes(headerFirst.getBytes(StandardCharsets.UTF_8)));return bindings.saveAndFlush(normal);
    }

    String produceNormal(JsonNode request)throws Exception{
        Path directory=Files.createTempDirectory(temporary,"normal-producer-"),input=directory.resolve("request.json"),output=directory.resolve("projection.json"),script=directory.resolve("produce.py");
        Files.write(input,JsonUtils.write(request).getBytes(StandardCharsets.UTF_8));Files.copy(getClass().getResourceAsStream("/canonical/normal-observed/produce.py"),script);
        String configuredTools=System.getProperty("consense.test.oerTools");
        Path tools=configuredTools==null?Paths.get(System.getProperty("consense.test.wireFixture")).getParent():Paths.get(configuredTools);
        Process process=new ProcessBuilder(System.getProperty("consense.test.python"),script.toString(),tools.toString(),input.toString(),directory.resolve("cas").toString(),output.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("producer.log").toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Authored offline normal producer timed out");}
        if(process.exitValue()!=0)throw new IOException("Authored offline normal producer failed: "+Files.readString(directory.resolve("producer.log")));
        normalCasRoots.put(Long.valueOf(request.path("candidateGraph").path("source").path("documentId").asText()),directory.resolve("cas"));
        return Files.readString(output,StandardCharsets.UTF_8);
    }
    ObjectNode scope(){ObjectNode s=JsonUtils.mapper().createObjectNode();s.put("targetModule","advisory");s.put("inputContract",INPUT);s.put("sourceQualityChanged",false);s.putObject("policyIdentity").put("manifestSha256",POLICY);return s;}
    ObjectNode retrieveFixture(String key)throws Exception{
        ObjectNode result=(ObjectNode)VettingDualStructureHealthTest.resource("authored_canonical_wire.json").path("retrieve").deepCopy();result.put("projectId",key);result.put("indexSignature",SIGNATURE);
        result.put("mode",VettingRetrievalHealth.STRUCTURAL_CONTRACT);result.set("competitionScope",scope());JsonNode seed=snapshots.get(42L).path("chunks").get(0);
        ObjectNode hit=result.putArray("hits").addObject();hit.put("id",seed.path("id").asText());hit.put("score",0.9);hit.set("payload",seed.deepCopy());
        Map<String,JsonNode> parents=new LinkedHashMap<>();for(JsonNode s:snapshots.values())for(JsonNode c:s.path("chunks"))parents.put(c.path("id").asText(),c);
        ObjectNode unit=OerCanonicalSourceUnits.resolve(parents,seed);unit.put("originalRerankOrdinal",1);unit.put("seedScore",0.9);
        ObjectNode episode=(ObjectNode)result.path("sourceUnits");episode.putArray("originalHitIds").add(seed.path("id").asText());episode.putArray("units").add(unit);return result;
    }
    void mutate(Long id,String kind){
        try{
            if("original_same_length_restored_mtime".equals(kind))changeExactLengthRestoredMtime(originals.get(id));
            else if("cas_same_length_restored_mtime".equals(kind))changeExactLengthRestoredMtime(casTamperTargets.get(id));
            else if("db_parsed".equals(kind))jdbc.update("UPDATE source_document SET structured_content_json=? WHERE id=?","[\"COMMITTED synthetic changed parsed bytes\"]",id);
            else if("binding_revision".equals(kind))jdbc.update("UPDATE canonical_source_binding SET projection_revision_sha256=? WHERE document_id=?",VettingCorpus.hash("changed binding revision "+id),id);
            else throw new IllegalArgumentException(kind);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    void newScopeMember(){SourceDocument d=new SourceDocument();d.setProjectId(PROJECT);d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("PRE");d.setFileName("new-current-project-PRE.txt");d.setParseStatus("PARSED");d.setReviewRole("tender");d.setTextContent("NEW scope source");documents.saveAndFlush(d);}
    void verifyActualCas(Long id)throws IOException{
        if(normalCasRoots.containsKey(id)){JsonNode descriptor=snapshots.get(id).path("observationManifestRef");byte[] bytes=Files.readAllBytes(normalCasRoots.get(id).resolve(descriptor.path("relativePath").asText()));if(bytes.length!=descriptor.path("byteCount").asLong()||!shaBytes(bytes).equals(descriptor.path("sha256").asText()))throw new IOException("Actual normal observation CAS bytes changed");return;}
        for(JsonNode record:casLedgers.get(id)){byte[] actual=Files.readAllBytes(temporary.resolve(record.path("relativePath").asText()));
        if(actual.length!=record.path("byteCount").asInt()||!shaBytes(actual).equals(record.path("sha256").asText()))throw new IOException("SYNTHETIC actual CAS bytes changed");}}
    Long idForProjection(String projection){return snapshots.entrySet().stream().filter(e->e.getValue().path("projectionSha256").asText().equals(projection)).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown projection"));}
    Map<String,Integer> snapshotCounts(){Map<String,Integer> result=new TreeMap<>();perDocumentSnapshotReads.forEach((id,count)->result.put(String.valueOf(id),count.get()));return result;}
    void resetCounters(){snapshotReads.set(0);unchangedSnapshotReads.set(0);snapshotResponseBytes.set(0);indexCalls.set(0);retrieveCalls.set(0);chatCalls.set(0);sourceReadCalls.set(0);healthReads.set(0);healthMutationOrdinal=1;perDocumentSnapshotReads.clear();events.clear();conditionalSnapshotTags.clear();compactAdvicePrepareRequests.clear();compactAdvicePrepareCasReads.set(0);compactAdvicePrepareCasBytes.set(0);clearInvocations(evidence,reader,business,llm,history);}
    void assertScalarAvailabilityGuards(){List<String> sequence=new ArrayList<>(events);int start=sequence.indexOf("availability:start"),end=sequence.indexOf("availability:end");assertTrue(start>=0&&end>start);
        int firstHealth=sequence.subList(0,start).lastIndexOf("health:start"),lastHealth=sequence.subList(end+1,sequence.size()).indexOf("health:end")+end+1;
        assertTrue(firstHealth>=0&&lastHealth>end);assertTrue(sequence.subList(0,firstHealth).contains("scalar:end"));assertTrue(sequence.subList(lastHealth+1,sequence.size()).contains("scalar:start"));
    }
    void assertWaitGuards(String operation){List<String> sequence=new ArrayList<>(events);boolean seen=false;
        for(int i=0;i<sequence.size();i++)if(sequence.get(i).equals(operation+":start")){
            int available=sequence.indexOf("availability:start"),availableEnd=sequence.indexOf("availability:end");
            if("health".equals(operation)&&available>=0&&(sequence.subList(0,available).lastIndexOf("health:start")==i
                    ||availableEnd>=0&&sequence.subList(availableEnd+1,sequence.size()).indexOf("health:start")+availableEnd+1==i))continue;
            seen=true;int end=sequence.subList(i+1,sequence.size()).indexOf(operation+":end");assertTrue(end>=0);end+=i+1;
            int previous=-1;for(int j=i-1;j>=0;j--)if(sequence.get(j).equals("health:end")||sequence.get(j).equals("index:end")||sequence.get(j).equals("status:end")||sequence.get(j).equals("retrieve:end")||sequence.get(j).equals("llm:end")){previous=j;break;}
            int next=sequence.size();for(int j=end+1;j<sequence.size();j++)if(sequence.get(j).equals("health:start")||sequence.get(j).equals("index:start")||sequence.get(j).equals("status:start")||sequence.get(j).equals("retrieve:start")||sequence.get(j).equals("llm:start")){next=j;break;}
            int casEnd=next;
            // A status response and its final health read share one complete source check.
            if("health".equals(operation)&&next<sequence.size()&&sequence.get(next).equals("status:start")){
                int statusEnd=sequence.subList(next+1,sequence.size()).indexOf("status:end");assertTrue(statusEnd>=0);statusEnd+=next+1;casEnd=sequence.size();
                for(int j=statusEnd+1;j<sequence.size();j++)if(sequence.get(j).equals("health:start")||sequence.get(j).equals("index:start")||sequence.get(j).equals("status:start")||sequence.get(j).equals("retrieve:start")||sequence.get(j).equals("llm:start")){casEnd=j;break;}
            }
            if(casEnd<sequence.size()&&sequence.get(casEnd).equals("health:start")){
                int healthEnd=sequence.subList(casEnd+1,sequence.size()).indexOf("health:end");assertTrue(healthEnd>=0);healthEnd+=casEnd+1;
                casEnd=sequence.size();for(int j=healthEnd+1;j<sequence.size();j++)if(sequence.get(j).equals("health:start")||sequence.get(j).equals("index:start")||sequence.get(j).equals("status:start")||sequence.get(j).equals("retrieve:start")||sequence.get(j).equals("llm:start")){casEnd=j;break;}
            }
            for(long id:new long[]{42,43}){assertTrue(sequence.subList(previous+1,i).contains("source:"+id+":end"),operation+" missing fresh original/DB pre guard for "+id);
                assertTrue(sequence.subList(end+1,next).contains("source:"+id+":end"),operation+" missing fresh original/DB post guard for "+id);
                assertTrue(sequence.subList(end+1,casEnd).contains("cas:"+id+":end"),operation+" missing post full CAS for "+id);}}
        assertTrue(seen,"missing actual external "+operation+" wait");}

    void writeNegative(String label){ObjectNode r=JsonUtils.mapper().createObjectNode();r.put("label",label);r.put("synthetic",true);r.put("indexCalls",indexCalls.get());r.put("retrieveCalls",retrieveCalls.get());r.put("llmDoubleCalls",chatCalls.get());r.put("neuralCalls",0);r.put("historyRows",history.count());r.put("result","rejected");write(label+".json",r);}
    void write(String name,JsonNode json){String root=System.getProperty("consense.recheck.output");if(root==null)return;try{Path path=Paths.get(root).resolve(name);Files.createDirectories(path.getParent());Files.write(path,JsonUtils.write(json).getBytes(StandardCharsets.UTF_8));}catch(IOException e){throw new IllegalStateException(e);}}
    static void runOnce(AtomicReference<Runnable> action){Runnable work=action.getAndSet(null);if(work!=null)work.run();}
    static void changeExactLengthRestoredMtime(Path path)throws IOException{FileTime modified=Files.getLastModifiedTime(path);byte[] bytes=Files.readAllBytes(path);long size=bytes.length;bytes[bytes.length/2]^=1;Files.write(path,bytes);Files.setLastModifiedTime(path,modified);assertEquals(size,Files.size(path));assertEquals(modified,Files.getLastModifiedTime(path));}
    static byte[] resource(String name)throws IOException{try(InputStream in=CanonicalRecheckAdviceRealSeamTest.class.getResourceAsStream("/oer/recheck/"+name)){if(in==null)throw new IOException(name);return readAll(in);}}
    static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);return out.toByteArray();}
    static String repeat(char c){char[] values=new char[64];Arrays.fill(values,c);return new String(values);}
    static String shaBytes(byte[] bytes){try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();}catch(Exception error){throw new IllegalStateException(error);}}

    void configureVettingSemanticPreparation(){
        health.with("oer").put("targetModule","vetting");health.with("retrieval").with("sourceWindows").put("oerTargetModule","vetting");
        ObjectNode read=(ObjectNode)health.path("oer").path("sourceRead");read.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);read.putArray("views").add("semantic_units");read.putArray("operations").add("prepare");
        String url="http://127.0.0.1:"+normalServer.getAddress().getPort();canonicalProps.getRetrievalBaseUrls().put("vetting",url);
        resetCounters();semanticPrepareCalls.set(0);semanticCatalogCalls.set(0);semanticScanCalls.set(0);semanticEntryReads.set(0);normalPublications.set(0);
    }
    com.consense.web.dto.VettingDtos.VettingProgressVO awaitVettingTerminal(String id)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){com.consense.web.dto.VettingDtos.VettingProgressVO job=vetting.getProgress(PROJECT,id);
            if(Arrays.asList("COMPLETED","FAILED").contains(job.getStatus()))return job;Thread.sleep(10);}
        throw new AssertionError("Authored Vetting source preparation did not terminate");
    }
    @Test void vettingWarmRegistrationUsesOneEntryPassAndRetainsActualOwnerClose()throws Exception{
        configureVettingSemanticPreparation();com.consense.web.dto.VettingDtos.VettingJobVO queued=vetting.startRun(PROJECT,"en");
        assertEquals("FAILED",awaitVettingTerminal(queued.getId()).getStatus());assertEquals(1,semanticPrepareCalls.get());assertEquals(0,semanticCatalogCalls.get());assertEquals(1,semanticScanCalls.get());
        int entry=semanticEntryReads.get(),closed=snapshotReads.get();ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("selectedSources",2);receipt.put("prePrepareCanonicalReads",entry);receipt.put("totalCanonicalReads",closed);receipt.put("ownerCloseCanonicalReads",closed-entry);receipt.put("semanticPrepareCalls",semanticPrepareCalls.get());receipt.put("semanticCatalogCalls",semanticCatalogCalls.get());receipt.put("semanticScanCalls",semanticScanCalls.get());receipt.put("llmDoubleCalls",chatCalls.get());write("vetting-warm-registration-entry.json",receipt);
        assertEquals(0,closed-entry,"The completed native prepare already owns the actual-byte exit proof");assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        assertEquals(4,entry,"Each of the two owned document frames enters and closes before shared preparation");
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"none","db_parsed","binding_revision"})
    void complete33SourceVettingPrepareReusesNativeExitProofAndRejectsScalarDrift(String change)throws Exception{
        replaceReviewedDocxWithUnregisteredNormal();configureVettingSemanticPreparation();assertTrue(evidence.prepareNormalObserved(PROJECT,43L,"vetting"));
        SourceDocument template=documents.findById(43L).orElseThrow();
        for(int ordinal=3;ordinal<=33;ordinal++){
            SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);next.setId(null);
            next.setFileName("Authored prepare source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);
            documents.saveAndFlush(next);assertTrue(evidence.prepareNormalObserved(PROJECT,next.getId(),"vetting"));
        }
        configureVettingSemanticPreparation();List<SourceDocument> selected=documents.findScopeSourcesByProjectId(PROJECT);assertEquals(33,selected.size());
        if(!"none".equals(change))semanticPrepareMutation.set(()->mutate(42L,change));
        if("none".equals(change)){
            OerBusinessEvidence.Session session=business.loadSemantic(PROJECT,"vetting",selected);assertEquals(33,session.documentIds.size());
            assertEquals(66,snapshotReads.get(),"Exactly two entry/owned-close reads per source; remove the redundant 33-source post-prepare pass");
            verify(evidence,never()).revalidateCompactRegistry(any());
        }else{
            CanonicalEvidenceException rejected=assertThrows(CanonicalEvidenceException.class,()->business.loadSemantic(PROJECT,"vetting",selected));
            assertTrue(rejected.getMessage().contains("STALE")||rejected.getMessage().contains("CHANGED"),rejected.getMessage());
            assertEquals(66,snapshotReads.get(),"Fresh scalar source/binding drift must fail without another canonical pass");
        }
        assertEquals(66,semanticEntryReads.get());assertEquals(1,semanticPrepareCalls.get());
        assertEquals(0,semanticCatalogCalls.get());assertEquals(0,semanticScanCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        verify(canonicalRetrieval).prepareSemanticSources(eq("vetting"),argThat(current->current.size()==33));
    }
    void replaceReviewedDocxWithUnregisteredNormal()throws Exception{
        bindings.deleteAll(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L));bindings.flush();
        SourceDocument source=documents.findById(43L).orElseThrow();com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();
        block.setId("normal:body:0");block.setSource("native");block.setLocation("body/0/paragraph");block.setText("AUTHORED selected normal source for Vetting registration");
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));source.setParseCoverageJson("{\"complete\":true}");documents.saveAndFlush(source);
    }
    @Test void vettingSelectedNormalTextUsesTheRealServiceEntryWithoutWholeSourceMaterialization()throws Exception{
        replaceReviewedDocxWithUnregisteredNormal();configureVettingSemanticPreparation();assertTrue(evidence.prepareNormalObserved(PROJECT,43L,"vetting"));
        SourceDocument captured=documents.findById(43L).orElseThrow();List<SourceDocument> selected=Collections.singletonList(captured);
        List<CanonicalSourceBinding> candidates=evidence.compactNormalCandidates(PROJECT,"vetting",selected);assertNotNull(candidates);
        JsonNode original=snapshots.get(43L).path("chunks").get(0);String id=original.path("id").asText();resetCounters();NormalSourceSql.bindingReads.clear();NormalSourceSql.capture=true;
        CanonicalEvidenceService.SelectedNormalTextSources text;
        try{text=evidence.selectedNormalTextSources(PROJECT,"vetting",selected,candidates,Collections.singleton(id));}finally{NormalSourceSql.capture=false;}
        assertNotNull(text);assertEquals(Collections.singleton(id),text.chunks().keySet());assertEquals(original,text.chunks().get(id));
        assertEquals("vetting",text.scope().bindings().get(0).getDeclaredModule());
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=CanonicalEvidenceService.compactNormalPointer(text.scope().bindings().get(0),text.headers().get(43L),text.chunks().get(id),"vetting");
        assertEquals("vetting",pointer.getModule());assertEquals(captured.getSourceSha256(),pointer.getOriginalSourceSha256());assertEquals(id,pointer.getCanonicalChunkId());
        assertTrue(NormalSourceSql.bindingReads.isEmpty(),"The actual selected-text entry must not load binding snapshot CLOBs through ORM");assertEquals(0,chatCalls.get());assertEquals(0,retrieveCalls.get());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"none","member_text","member_offset","source_stamp","binding","original_bytes","cas_bytes","raw_snapshot"})
    void vettingTextFirstQuoteBindingUsesOnlySelectedRegisteredParents(String change)throws Exception{
        replaceReviewedDocxWithUnregisteredNormal();configureVettingSemanticPreparation();assertTrue(evidence.prepareNormalObserved(PROJECT,43L,"vetting"));
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0);
        List<SourceDocument> selected=Collections.singletonList(documents.findScopeSourcesByProjectId(PROJECT).stream().filter(source->source.getId()==43L).findFirst().orElseThrow());
        JsonNode raw=snapshots.get(43L).path("chunks").get(0);String id=raw.path("id").asText();
        ((ObjectNode)health.path("oer").path("sourceRead")).putObject("semanticPreparation").put("normalProofVersion",OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);
        OerBusinessEvidence.Session session=business.loadSemantic(PROJECT,"vetting",selected);
        verify(evidence).attestCompactNormalSources(any(),any());assertEquals(1,compactAdvicePrepareCasReads.get());
        OerCanonicalRetrievalClient.SemanticSources sources=org.springframework.test.util.ReflectionTestUtils.invokeMethod(OerSemanticBusinessEvidenceTest.class,"semanticSources",binding,1,false,256,true);
        ObjectNode member=org.springframework.test.util.ReflectionTestUtils.invokeMethod(OerSemanticBusinessEvidenceTest.class,"semanticOrigin",raw,binding);
        member.put("producerKind",NormalObservedContract.PRODUCER);
        if("member_text".equals(change)){member.put("text","AUTHORED altered selected text");((ObjectNode)member.path("parts").get(0)).put("text",member.path("text").asText()).put("endOffset",member.path("text").asText().length());}
        if("member_offset".equals(change))((ObjectNode)member.path("parts").get(0)).put("startOffset",1).put("endOffset",member.path("parts").get(0).path("text").asText().length()+1);
        doAnswer(call->{ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("origins").add(member.deepCopy());result.putNull("nextCursor");
            ObjectNode unit=result.putArray("units").addObject();unit.put("status","complete_observed_unit").put("unitId",VettingCorpus.hash("AUTHORED selected quote unit"));unit.putArray("requiredMemberIds").add(id);unit.putArray("members").add(member.deepCopy());
            result.putObject("coverage").put("status","complete").put("processedOrigins",1);return result;
        }).when(canonicalRetrieval).readSemanticSources(eq(sources),any());
        // The real preparation owns the registry pins; this transport fixture supplies the selected text-first scan.
        org.springframework.test.util.ReflectionTestUtils.setField(session,"semanticSources",sources);
        VettingCorpus.Chunk chunk=business.scan(session).get(0);
        com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(chunk,"source",chunk.getContent());assertTrue(quote.isLocated());
        if("source_stamp".equals(change))mutate(43L,"db_parsed");
        if("binding".equals(change))mutate(43L,"binding_revision");
        if("original_bytes".equals(change))mutate(43L,"original_same_length_restored_mtime");
        if("cas_bytes".equals(change))changeExactLengthRestoredMtime(normalCasRoots.get(43L).resolve(snapshots.get(43L).path("observationManifestRef").path("relativePath").asText()));
        if("raw_snapshot".equals(change))jdbc.update("UPDATE canonical_source_binding SET snapshot_json=? WHERE document_id=?",binding.getSnapshotJson()+" ",43L);
        resetCounters();NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();NormalSourceSql.capture=true;
        try {
            if("none".equals(change)){
                business.bindReviewEvidence(session,Collections.singletonList(quote));assertTrue(quote.isLocated());
                assertEquals(id,quote.getCanonicalEvidence().getCanonicalChunkId());assertEquals(raw.path("parts"),quote.getCanonicalEvidence().getParts());
                assertNull(quote.getCanonicalEvidence().getNativeWordSourceScopesContext());
                verify(evidence).selectedNormalTextSources(eq(PROJECT),eq("vetting"),anyList(),anyList(),eq(Collections.singleton(id)));
                assertTrue(NormalSourceSql.reads.isEmpty(),"Exact text quotation binding must not reload complete source graphs");
                assertTrue(NormalSourceSql.bindingReads.isEmpty(),"Only selected registered parents may be materialized");
                assertEquals(1,unchangedSnapshotReads.get(),"The selected native CAS is still rechecked against its preparation receipt");
            }else assertThrows(RuntimeException.class,()->business.bindReviewEvidence(session,Collections.singletonList(quote)));
        }finally{NormalSourceSql.capture=false;}
        verify(evidence,never()).selectedPointers(anyString(),anyLong(),anyString(),anyList(),anyString());
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"wrong_module","ineligible_module","source_revision"})
    void vettingSelectedNormalTextRealServiceStillRejectsForeignOrChangedProof(String change)throws Exception{
        replaceReviewedDocxWithUnregisteredNormal();configureVettingSemanticPreparation();assertTrue(evidence.prepareNormalObserved(PROJECT,43L,"vetting"));
        SourceDocument captured=documents.findById(43L).orElseThrow();List<SourceDocument> selected=Collections.singletonList(captured);
        List<CanonicalSourceBinding> candidates=evidence.compactNormalCandidates(PROJECT,"vetting",selected);assertNotNull(candidates);
        String id=snapshots.get(43L).path("chunks").get(0).path("id").asText();
        if("ineligible_module".equals(change))candidates.get(0).setEligibleModulesJson("[\"advisory\"]");
        if("source_revision".equals(change))jdbc.update("update source_document set text_content=? where id=43","AUTHORED changed after selected-text capture");
        String requested="wrong_module".equals(change)?"advisory":"vetting";
        CanonicalEvidenceException failure=assertThrows(CanonicalEvidenceException.class,()->evidence.selectedNormalTextSources(PROJECT,requested,selected,candidates,Collections.singleton(id)));
        assertTrue(failure.getMessage().contains("STALE")||failure.getMessage().contains("ELIGIBLE"),failure.getMessage());assertEquals(0,chatCalls.get());assertEquals(0,retrieveCalls.get());
    }
    @Test void vettingWarmNormalRegistrationUsesConditionalEntryAndOwnerCloseWithoutPublication()throws Exception{
        replaceReviewedDocxWithUnregisteredNormal();configureVettingSemanticPreparation();assertTrue(evidence.prepareNormalObserved(PROJECT,43L,"vetting"));
        configureVettingSemanticPreparation();com.consense.web.dto.VettingDtos.VettingJobVO queued=vetting.startRun(PROJECT,"en");
        assertEquals("FAILED",awaitVettingTerminal(queued.getId()).getStatus());assertEquals(1,semanticPrepareCalls.get());assertEquals(0,semanticCatalogCalls.get());
        assertEquals(4,semanticEntryReads.get());assertEquals(4,snapshotReads.get());assertEquals(0,normalPublications.get());assertTrue(unchangedSnapshotReads.get()>=2,"Normal source entry and owner close retain their conditional actual-CAS checks");assertEquals(0,chatCalls.get());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void vettingMissingOrForeignNormalRegistrationStillPublishesForItsOwnModule(boolean foreign)throws Exception{
        replaceReviewedDocxWithUnregisteredNormal();
        if(foreign){assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());assertEquals("advisory",bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0).getDeclaredModule());}
        configureVettingSemanticPreparation();com.consense.web.dto.VettingDtos.VettingJobVO queued=vetting.startRun(PROJECT,"en");
        assertEquals("FAILED",awaitVettingTerminal(queued.getId()).getStatus());assertEquals(1,normalPublications.get());assertEquals(1,semanticPrepareCalls.get());assertEquals(0,semanticCatalogCalls.get());
        List<CanonicalSourceBinding> revisions=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L);assertEquals("vetting",revisions.get(0).getDeclaredModule());assertEquals(foreign?2:1,revisions.size());assertEquals(0,chatCalls.get());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void ownedVettingFrameReusesOriginalPinButExitStillHashesActualOriginal(boolean changed)throws Exception{
        configureVettingSemanticPreparation();SourceDocument captured=reader.normalDocument(PROJECT,42L,"vetting");
        CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedSemanticBindings(PROJECT,"vetting",Collections.singletonList(captured));
        clearInvocations(reader);for(int i=0;i<3;i++)frame.beforeWait();
        verify(reader,never()).requireOriginalBytes(any());
        if(changed){mutate(42L,"original_same_length_restored_mtime");assertDoesNotThrow(frame::beforeWait);assertThrows(RuntimeException.class,frame::close);}
        else assertDoesNotThrow(frame::close);
        verify(reader,times(1)).requireOriginalBytes(any());assertThrows(RuntimeException.class,frame::bindings);
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"original_same_length_restored_mtime","cas_same_length_restored_mtime","binding_revision"})
    void vettingRegistrationNeverSubstitutesForActualSemanticEntryProof(String mutation)throws Exception{
        configureVettingSemanticPreparation();snapshotMutation.set(()->mutate(42L,mutation));
        com.consense.web.dto.VettingDtos.VettingJobVO queued=vetting.startRun(PROJECT,"en");assertEquals("FAILED",awaitVettingTerminal(queued.getId()).getStatus());
        assertEquals(0,semanticPrepareCalls.get());assertEquals(0,semanticCatalogCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"original_same_length_restored_mtime","cas_same_length_restored_mtime","binding_revision"})
    void vettingSemanticOwnerCloseRejectsChangesBeforeAnyCatalogOrPaidWork(String mutation)throws Exception{
        configureVettingSemanticPreparation();List<CanonicalEvidenceService.VerifiedBindingFrame> frames=new ArrayList<>();
        doAnswer(call->{CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)call.callRealMethod();frames.add(frame);return frame;})
                .when(evidence).verifiedSemanticBindings(eq(PROJECT),eq("vetting"),anyList());
        semanticPrepareMutation.set(()->mutate(42L,mutation));com.consense.web.dto.VettingDtos.VettingJobVO queued=vetting.startRun(PROJECT,"en");
        assertEquals("FAILED",awaitVettingTerminal(queued.getId()).getStatus());assertEquals(1,semanticPrepareCalls.get());assertEquals(0,semanticCatalogCalls.get());
        assertEquals(2,frames.size());for(CanonicalEvidenceService.VerifiedBindingFrame frame:frames)assertThrows(RuntimeException.class,frame::bindings);assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @Test void compactVettingQueueAndInitialProofKeepOnlyOneCompleteDocumentFrameAlive()throws Exception{
        configureVettingSemanticPreparation();List<CanonicalEvidenceService.VerifiedBindingFrame> frames=new ArrayList<>();
        doAnswer(call->{List<SourceDocument> selected=call.getArgument(2);assertEquals(1,selected.size());assertNotNull(selected.get(0).getTextContent());
            for(CanonicalEvidenceService.VerifiedBindingFrame previous:frames)assertThrows(RuntimeException.class,previous::bindings);
            CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)call.callRealMethod();frames.add(frame);return frame;
        }).when(evidence).verifiedSemanticBindings(eq(PROJECT),eq("vetting"),anyList());
        doAnswer(call->{assertEquals(2,frames.size());for(CanonicalEvidenceService.VerifiedBindingFrame frame:frames)assertThrows(RuntimeException.class,frame::bindings);return call.callRealMethod();})
                .when(canonicalRetrieval).prepareSemanticSources(eq("vetting"),anyList());
        NormalSourceSql.reads.clear();NormalSourceSql.capture=true;
        try{com.consense.web.dto.VettingDtos.VettingJobVO queued=vetting.startRun(PROJECT,"en");assertEquals("FAILED",awaitVettingTerminal(queued.getId()).getStatus());
            assertEquals(2,frames.size());assertEquals(2,NormalSourceSql.reads.size(),"One actual source read per document; queue and guards must not hydrate complete sources");
            assertEquals(4,semanticEntryReads.get());assertEquals(4,snapshotReads.get());assertEquals(0,chatCalls.get());
        }finally{NormalSourceSql.capture=false;}
    }
    @Test @SuppressWarnings("unchecked")
    void semanticWordContextAndLocatorsHydrateOnlyTheirRequestedDocument()throws Exception{
        replaceReviewedDocxWithUnregisteredNormal();configureVettingSemanticPreparation();assertTrue(evidence.prepareNormalObserved(PROJECT,43L,"vetting"));
        configureVettingSemanticPreparation();
        doReturn(JsonUtils.mapper().createObjectNode()).when(canonicalRetrieval).readSemanticSources(any(OerCanonicalRetrievalClient.SemanticSources.class),argThat(demand->"catalog".equals(demand.path("operation").asText())));
        OerBusinessEvidence.Session session=business.loadSemantic(PROJECT,"vetting",documents.findScopeSourcesByProjectId(PROJECT));
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0);
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=evidence.pointers(PROJECT,43L,binding.getProjectionSha256(),"vetting").get(0);
        JsonNode raw=snapshots.get(43L).path("chunks").get(0);SourceDocument source=reader.normalDocument(PROJECT,43L,"vetting");
        VettingCorpus.Chunk view=org.springframework.test.util.ReflectionTestUtils.invokeMethod(OerBusinessEvidence.class,"view",raw,pointer,source);
        ((Map<String,VettingCorpus.Chunk>)org.springframework.test.util.ReflectionTestUtils.getField(session,"byId")).put(view.getId(),view);
        ((List<VettingCorpus.Chunk>)org.springframework.test.util.ReflectionTestUtils.getField(session.chunks(),"owned")).add(view);
        String stamp=org.springframework.test.util.ReflectionTestUtils.invokeMethod(OerBusinessEvidence.class,"viewPayload",session,view);
        ((Map<String,String>)org.springframework.test.util.ReflectionTestUtils.getField(session,"viewPayloads")).put(view.getId(),stamp);
        clearInvocations(reader,evidence);Map<String,JsonNode> contexts=business.wordSourceScopeContexts(session,Collections.singletonList(view));
        business.sourceClauseLocators(session,Collections.singletonList(view),contexts);
        verify(reader,never()).normalDocument(PROJECT,42L,"vetting");verify(reader,times(2)).normalDocument(PROJECT,43L,"vetting");
        for(org.mockito.invocation.Invocation invocation:mockingDetails(evidence).getInvocations())if("verifiedSemanticBindings".equals(invocation.getMethod().getName())){
            List<SourceDocument> selected=(List<SourceDocument>)invocation.getArgument(2);assertEquals(1,selected.size());assertEquals(43L,selected.get(0).getId());}
        Map<Long,SourceDocument> retained=(Map<Long,SourceDocument>)org.springframework.test.util.ReflectionTestUtils.getField(session,"semanticDocuments");
        for(SourceDocument metadata:retained.values()){assertNull(metadata.getTextContent());assertNull(metadata.getStructuredContentJson());assertNull(metadata.getParseCoverageJson());assertNull(metadata.getNormalCandidateJson());}
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
    @Test void advisoryExplicitPreparationKeepsItsIndependentRegisteredSourceVerification(){
        resetCounters();assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());assertEquals(6,snapshotReads.get());
        assertEquals(3,snapshotCounts().get("42"));assertEquals(3,snapshotCounts().get("43"));assertEquals(0,semanticPrepareCalls.get());assertEquals(0,chatCalls.get());
    }

    @Test @DirtiesContext(methodMode=DirtiesContext.MethodMode.BEFORE_METHOD)
    void singleFrameMissingRemoteIndexStatusRefusesWithFreshBoundaryCounts(){
        resetCounters();long begin=System.nanoTime();OerBusinessEvidence.CurrentIndexStatus current=business.currentIndexStatus(PROJECT,"advisory",()->documents.findScopeSourcesByProjectId(PROJECT));
        AdviceService target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(advice);
        IndexStatusVO result=org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"canonicalStatusResult","fullset",current.indexKey,current.chunks,current.documentCount,current.state);
        assertFalse(result.isVectorReady());assertEquals("canonical_index_unavailable",result.getIndexReason());
        assertEquals(JsonUtils.parse("{\"chunks\":4,\"indexed\":0,\"vectorProvider\":\"canonical-oer\",\"collection\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"embedModel\":\"BAAI/bge-m3\",\"llmModel\":\"synthetic-recheck-chat\",\"llmReady\":true,\"vectorReady\":false,\"ocrReady\":false,\"message\":{\"zhHans\":\"当前范围的规范证据索引未就绪，请刷新索引并核对来源及检索服务。\",\"zhHant\":\"當前範圍的規範證據索引未就緒，請刷新索引並核對來源及檢索服務。\",\"en\":\"Canonical evidence index is not ready for the selected scope. Refresh it and check current source bindings and the retrieval service.\"},\"stale\":true,\"eligibleDocuments\":2,\"indexedDocuments\":0,\"embeddingReady\":false,\"evidenceBackend\":\"canonical\",\"sourceScope\":\"fullset\",\"indexKey\":\"authored-recheck-project::canonical::advisory::f48ae8581a939125f62e205e448c47322e921d442cd354cca04721356af0304d\",\"indexReason\":\"canonical_index_unavailable\"}"),JsonUtils.mapper().valueToTree(result),"complete missing remote-index response refuses readiness");
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("schema","consense.single-frame-status-author.v1");
        receipt.put("sourceKind","synthetic_actual_spring_h2_local_http");receipt.put("latencyIsSynthetic",true);receipt.put("wallNanos",System.nanoTime()-begin);
        receipt.set("perDocumentSnapshotReads",JsonUtils.mapper().valueToTree(snapshotCounts()));receipt.put("snapshotGetCount",snapshotReads.get());
        Map<String,Long> sourceCounts=new LinkedHashMap<>();for(long id:new long[]{42,43})sourceCounts.put(String.valueOf(id),events.stream().filter(e->e.equals("source:"+id+":start")).count());
        receipt.set("perDocumentSourceReaderCalls",JsonUtils.mapper().valueToTree(sourceCounts));receipt.put("healthCalls",healthReads.get());
        receipt.put("indexCalls",indexCalls.get());receipt.put("retrieveCalls",retrieveCalls.get());receipt.put("llmDoubleCalls",chatCalls.get());receipt.put("historyRows",history.count());
        receipt.put("verifiedFrameRounds",mockingDetails(evidence).getInvocations().stream().filter(i->i.getMethod().getName().equals("verifiedBindings")).count());
        receipt.set("events",JsonUtils.mapper().valueToTree(new ArrayList<>(events)));receipt.set("completeStatusResponse",JsonUtils.mapper().valueToTree(result));write("single-frame-missing-status.json",receipt);
        assertEquals(4,snapshotReads.get());assertEquals(Integer.valueOf(2),snapshotCounts().get("42"));assertEquals(Integer.valueOf(2),snapshotCounts().get("43"));
        assertEquals(1,healthReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        assertEquals(1,receipt.path("verifiedFrameRounds").asInt());for(long count:sourceCounts.values())assertEquals(8L,count);assertWaitGuards("health");assertWaitGuards("status");
    }
    @Test void newEligibleSelectedScopeMemberDuringAvailabilityResponseRefusesOldReadyResponse(){
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();availabilityMutation.set(this::newScopeMember);
        IndexStatusVO result=advice.status(PROJECT,"fullset");assertEquals(3,documents.count());assertFalse(result.isVectorReady());
        assertEquals("current_sources_unavailable",result.getIndexReason());assertEquals(0,snapshotReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        writeNegative("status-new-scope-during-exit-cas");
    }
    @Test void newEligibleUnregisteredSelectedScopeMemberRefusesBeforeAvailabilityHealth(){
        newScopeMember();IndexStatusVO result=advice.status(PROJECT,"fullset");
        assertEquals(3,documents.count());assertFalse(result.isVectorReady());assertEquals("current_sources_unavailable",result.getIndexReason());
        assertEquals(0,healthReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        writeNegative("status-new-scope-during-entry-cas");
    }
    @Test void statusScalarResultCannotCarryAFrameAndExplicitIntegrityRechecksActualCas(){
        for(java.lang.reflect.Field field:OerBusinessEvidence.CurrentIndexStatus.class.getDeclaredFields()){
            assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
            assertTrue(field.getType()==String.class||field.getType()==int.class||field.getType()==OerCanonicalRetrievalClient.IndexState.class);
        }
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();assertTrue(advice.status(PROJECT,"fullset").isVectorReady());assertEquals(0,snapshotReads.get());assertScalarAvailabilityGuards();
        resetCounters();mutate(42L,"cas_same_length_restored_mtime");
        assertThrows(RuntimeException.class,()->business.currentIndexStatus(PROJECT,"advisory",()->documents.findScopeSourcesByProjectId(PROJECT)));assertTrue(snapshotReads.get()>0,"explicit integrity request must read actual CAS again");
        assertEquals(0,healthReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        writeNegative("status-new-request-fresh-cas");
    }



    @Test void initialQwenAskReusesOneEpochForRetrieveAndTextContextThenClosesBeforePaidWork() throws Exception {
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        List<CanonicalEvidenceService.VerifiedBindingFrame> frames=new ArrayList<>();
        doAnswer(call->{CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)call.callRealMethod();frames.add(frame);return frame;})
                .when(evidence).verifiedBindings(eq(PROJECT),eq("advisory"),anyList());
        AtomicInteger contexts=new AtomicInteger(),paidOpens=new AtomicInteger();
        doAnswer(call->{OerBusinessEvidence.Session selected=call.getArgument(0);contexts.incrementAndGet();
            business.sourceTextHits(selected,Collections.singletonList(selected.chunks().get(0).getId()));return call.callRealMethod();})
                .when(business).advisoryTextContext(any(),anyList());
        com.consense.service.llm.QwenGenerationLedger ledger=mock(com.consense.service.llm.QwenGenerationLedger.class);
        doAnswer(call->{paidOpens.incrementAndGet();for(CanonicalEvidenceService.VerifiedBindingFrame frame:frames)
                assertThrows(RuntimeException.class,frame::bindings,"The initial source epoch must close before a paid action opens");
            throw new CancellationException("AUTHORED stop before any model dispatch");})
                .when(ledger).open(anyString(),anyString(),any());
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(authoredQwen(ledger))){
            assertThrows(CancellationException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset")));
        }
        assertEquals(1,retrieveCalls.get());assertEquals(1,contexts.get());assertEquals(1,paidOpens.get());
        assertEquals(1,frames.size(),"Initial Ask load, retrieval and declared text context must share one cold source epoch");
        for(CanonicalEvidenceService.VerifiedBindingFrame frame:frames)assertThrows(RuntimeException.class,frame::bindings);
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());assertTrue(unchangedSnapshotReads.get()>0,"Remote-boundary CAS checks remain active");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"none","original_bytes","cas_bytes","raw_snapshot","source_revision","wrong_module","rank_payload","member_missing","member_forged","rank_score","preflight_original","preflight_cas","save_original","save_cas","preflight_unselected","save_unselected"})
    void qwenRankedNormalTextDoesNotHydrateTheWholeBindingBeforeGateway(String change)throws Exception {
        rankedNormalTextCase(change);
    }
    @Test void qwenRankedNormalAdmissionLogRecordsOnlyVerifiedSelectedScope()throws Exception {rankedNormalTextCase("none");}
    private void rankedNormalTextCase(String change)throws Exception {
        ObjectNode read=(ObjectNode)health.path("oer").path("sourceRead");read.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);read.putArray("views").add("semantic_units");read.putArray("operations").add("prepare");read.putObject("semanticPreparation").put("normalProofVersion",OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);
        bindings.delete(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0));bindings.flush();
        SourceDocument source=documents.findById(43L).orElseThrow();List<com.consense.document.DocumentBlock> blocks=new ArrayList<>();
        for(int ordinal=0;ordinal<512;ordinal++){
            com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("authored:normal:"+ordinal);block.setSource("native");block.setLocation("body/"+ordinal+"/paragraph");
            block.setText("AUTHORED clause "+ordinal+": The contractor shall retain this complete distinct paragraph and its stated qualification for the corresponding work item. No other authored clause changes this requirement.");blocks.add(block);
        }
        source.setTextContent(blocks.stream().map(com.consense.document.DocumentBlock::getText).collect(java.util.stream.Collectors.joining("\n")));
        source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson("{\"complete\":true}");source.setNormalCandidateJson(null);documents.saveAndFlush(source);
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());assertTrue(snapshots.get(43L).path("chunks").size()>1,"Fixture must contain unrelated source units");
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        JsonNode seed=snapshots.get(43L).path("chunks").get(0);Map<String,JsonNode> parents=new LinkedHashMap<>();
        for(JsonNode snapshot:snapshots.values())for(JsonNode chunk:snapshot.path("chunks"))parents.put(chunk.path("id").asText(),chunk);
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer legacyPointer=evidence.pointer(PROJECT,43L,snapshots.get(43L).path("projectionSha256").asText(),seed.path("unitId").asText(),"advisory");
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer unselectedPointer=change.endsWith("_unselected")?evidence.pointer(PROJECT,42L,snapshots.get(42L).path("projectionSha256").asText(),snapshots.get(42L).path("chunks").get(0).path("unitId").asText(),"advisory"):null;resetCounters();
        retrieveResponseMutation.set(result->{
            ObjectNode hit=result.putArray("hits").addObject();hit.put("id",seed.path("id").asText());hit.put("score",0.9);hit.set("payload",seed.deepCopy());
            ObjectNode unit=OerCanonicalSourceUnits.resolve(parents,seed);unit.put("originalRerankOrdinal",1);unit.put("seedScore",0.9);
            ObjectNode units=(ObjectNode)result.path("sourceUnits");units.putArray("originalHitIds").add(seed.path("id").asText());units.putArray("units").add(unit);
            if("rank_payload".equals(change))((ObjectNode)hit.path("payload")).put("content","AUTHORED forged ranked text");
            if("member_missing".equals(change))unit.putArray("requiredMemberIds");
            if("member_forged".equals(change))((ObjectNode)unit.path("members").get(0)).put("content","AUTHORED forged closure member");
            if("rank_score".equals(change))hit.put("score","0.9");
        });
        if("original_bytes".equals(change))canonicalCatalogMutation.set(()->mutate(43L,"original_same_length_restored_mtime"));
        if("cas_bytes".equals(change))canonicalCatalogMutation.set(()->{try{changeExactLengthRestoredMtime(normalCasRoots.get(43L).resolve(snapshots.get(43L).path("observationManifestRef").path("relativePath").asText()));}catch(IOException failed){throw new UncheckedIOException(failed);}});
        if("raw_snapshot".equals(change))retrieveMutation.set(()->jdbc.update("update canonical_source_binding set snapshot_json=? where document_id=?"," "+bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0).getSnapshotJson(),43L));
        if("source_revision".equals(change))retrieveMutation.set(()->mutate(43L,"binding_revision"));
        if("wrong_module".equals(change))retrieveMutation.set(()->((ObjectNode)snapshots.get(43L).path("projectionIdentity")).put("module","vetting"));
        List<Integer> bindingReadsAtGateway=new ArrayList<>(),loadedViewsBeforePack=new ArrayList<>();List<Long> rawProofReadsAtGateway=new ArrayList<>();List<String> prompts=new ArrayList<>();
        AtomicReference<OerBusinessEvidence.Session> admittedSession=new AtomicReference<>();
        doAnswer(call->{OerBusinessEvidence.Session selected=call.getArgument(0);admittedSession.set(selected);loadedViewsBeforePack.add(selected.chunks().size());return call.callRealMethod();}).when(business).advisoryTextContext(any(),anyList());
        com.consense.ai.HttpSupport transport=authoredQwenTransport(new ArrayList<>(),new ArrayList<>());
        doAnswer(call->{bindingReadsAtGateway.add(NormalSourceSql.bindingReads.size());rawProofReadsAtGateway.add(jdbc.queryForObject("select coalesce(sum(execution_count),0) from information_schema.query_statistics where lower(sql_statement) like 'select snapshot_json from canonical_source_binding where id=%'",Long.class));prompts.add(call.getArgument(1));
            boolean probe=JsonUtils.parse(call.<String>getArgument(1)).path("max_tokens").asInt()==1;
            if(probe&&"preflight_original".equals(change)||!probe&&"save_original".equals(change))mutate(43L,"original_same_length_restored_mtime");
            if(probe&&"preflight_cas".equals(change)||!probe&&"save_cas".equals(change))changeExactLengthRestoredMtime(normalCasRoots.get(43L).resolve(snapshots.get(43L).path("observationManifestRef").path("relativePath").asText()));
            if(probe&&"preflight_unselected".equals(change)||!probe&&"save_unselected".equals(change))mutate(42L,"original_same_length_restored_mtime");
            String content=probe?"{":com.consense.service.advice.AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported wording [1].\",\"citations\":[\"[1]\"]}");
            ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion");envelope.put("model","qwen3.8-27b");ObjectNode choice=envelope.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason","stop");choice.putObject("message").put("role","assistant").put("content",content);
            envelope.putObject("usage").put("prompt_tokens",1000).put("completion_tokens",1).put("total_tokens",1001);return JsonUtils.write(envelope);
        }).when(transport).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
        ch.qos.logback.classic.Logger routeLogger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OerBusinessEvidence.class);ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> routeTrace=new ch.qos.logback.core.read.ListAppender<>();routeTrace.start();routeLogger.addAppender(routeTrace);
        NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();jdbc.execute("SET QUERY_STATISTICS TRUE");NormalSourceSql.capture=true;AskResponseVO response;
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(authoredQwen(generationLedger,transport))){
            if(!"none".equals(change)&&!change.endsWith("_unselected")){assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("Explain the first authored obligation","fullset")));assertEquals(change.startsWith("preflight_")?1:change.startsWith("save_")?2:0,prompts.size(),"Changed source must fail at its next owned byte boundary");if(!change.startsWith("preflight_")&&!change.startsWith("save_"))assertTrue(routeTrace.list.stream().noneMatch(event->event.getFormattedMessage().contains("reason=accepted_after_rank_validation")),"Rejected rank or proof must never log final admission");assertEquals(0,history.count());return;}
            response=advice.ask(PROJECT,new AskRequest("Explain the first authored obligation","fullset"));}
        finally{NormalSourceSql.capture=false;routeLogger.detachAppender(routeTrace);routeTrace.stop();}
        assertFalse(prompts.isEmpty());assertTrue(prompts.stream().allMatch(prompt->prompt.contains(seed.path("content").asText())));
        assertEquals(1,routeTrace.list.stream().filter(event->event.getFormattedMessage().contains("Advisory selected-proof admission selectedSources=1 selectedProjections=1 rankedHits=1 retainedViews=1 reason=accepted_after_rank_validation")).count());
        assertEquals(1,response.getSources().size());assertFalse(response.getSources().get(0).getCanonicalEvidence().isEmpty());
        assertTrue(mockingDetails(reader).getInvocations().stream().noneMatch(call->call.getMethod().getName().equals("normalDocument")),"Initial selected text must not load complete source graphs");
        assertTrue(mockingDetails(evidence).getInvocations().stream().noneMatch(call->call.getMethod().getName().equals("prepareCompactNormalSources")),"Initial selected text must not rebuild whole expected parents");
        ObjectNode expectedPointer=JsonUtils.mapper().valueToTree(legacyPointer);expectedPointer.set("unknowns",seed.path("unknowns"));expectedPointer.remove("nativePdfSourceGroupsContext");expectedPointer.remove("nativeWordSourceScopesContext");
        assertEquals(expectedPointer,JsonUtils.mapper().valueToTree(response.getSources().get(0).getCanonicalEvidence().get(0)),"Selected text pointer keeps exact origin/revision and selected unknowns while deferring full context");
        assertEquals("unknown",response.getSources().get(0).getCanonicalSourceUnit().path("status").asText());
        assertEquals("additional_source_context_deferred",response.getSources().get(0).getCanonicalSourceUnit().path("reason").asText());
        assertEquals(0,compactAdvicePrepareRequests.size(),"Initial selected text must not prepare whole source semantics");assertEquals(0,compactAdvicePrepareCasReads.get());assertEquals(0,compactAdvicePrepareCasBytes.get());
        ObjectNode counts=JsonUtils.mapper().createObjectNode();counts.set("wholeBindingOrmReadsAtGateway",JsonUtils.mapper().valueToTree(bindingReadsAtGateway));counts.set("registeredRawStreamProofReadsAtGateway",JsonUtils.mapper().valueToTree(rawProofReadsAtGateway));counts.set("materializedViewsBeforePack",JsonUtils.mapper().valueToTree(loadedViewsBeforePack));counts.put("sourceUnits",snapshots.get(43L).path("chunks").size());counts.put("compactNativePrepareCalls",compactAdvicePrepareRequests.size());counts.set("compactNativePrepareProjections",JsonUtils.mapper().createArrayNode());counts.put("compactNativePrepareActualManifestChecks",compactAdvicePrepareCasReads.get());counts.put("compactNativePrepareActualManifestBytes",compactAdvicePrepareCasBytes.get());System.out.println("AUTHORED_ADVISORY_LOADER_BOUNDARY "+JsonUtils.write(counts));
        assertEquals(Arrays.asList(0,0),bindingReadsAtGateway,"The text-first Qwen gateway must precede whole ranked-document binding CLOB reads");
        assertTrue(rawProofReadsAtGateway.stream().allMatch(count->count>0),"Avoiding ORM materialization must retain the registered raw snapshot stream proof");
        assertTrue(loadedViewsBeforePack.stream().allMatch(count->count<=1),"Only selected source units may be materialized before text packing");
        if(change.endsWith("_unselected")){
            assertFalse(response.getSources().get(0).getCanonicalSourceUnit().path("semanticScopeVerified").asBoolean());assertEquals(2,history.count());
            Map<String,Object> requestedPayload=new LinkedHashMap<>();requestedPayload.put("canonicalEvidence",Collections.singletonList(unselectedPointer));
            assertThrows(RuntimeException.class,()->business.prepareAdviceSupplement(admittedSession.get(),Collections.singletonList(new com.consense.vector.VectorStore.SearchHit("authored-explicit-supplement",0.0,requestedPayload))));
            assertEquals(2,history.count(),"The changed unselected original must fail complete proof when explicitly requested, without a new history write");
        }
    }

    @Test void complete33SourceColdAskRecoversReadyIndexAndOnlyHydratesRankedSourcesBeforePaidAndSaveGuards()throws Exception {
        prepareNormalConditionalFixture();SourceDocument template=documents.findById(43L).get();
        for(int ordinal=3;ordinal<=33;ordinal++){
            SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);
            next.setId(null);next.setFileName("Authored complete source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);
            documents.saveAndFlush(next);
        }
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());assertEquals(33,documents.findScopeSourcesByProjectId(PROJECT).size());
        advice.rebuildIndex(PROJECT,"fullset");clearLocalIndexReceipts();resetCounters();exerciseActualTextContext();
        AtomicInteger maxOwnedDocuments=new AtomicInteger();assertSequentialOwners(maxOwnedDocuments);
        java.util.concurrent.atomic.AtomicReference<OerBusinessEvidence.Session> retainedSession=new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call->{call.callRealMethod();OerBusinessEvidence.Session selected=call.getArgument(0);retainedSession.set(selected);return null;})
                .when(business).retainAdviceSources(any(),anyList());
        Map<String,Integer> phases=new LinkedHashMap<>();List<Integer> dispatchReads=new ArrayList<>();List<Integer> dispatchNormalReads=new ArrayList<>();
        com.consense.ai.HttpSupport transport=authoredQwenTransport(dispatchReads,dispatchNormalReads);
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            @Override public void phase(String phase){phases.put(phase,snapshotReads.get());}
        };
        NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();NormalSourceSql.capture=true;
        try{assertTrue(advice.status(PROJECT,"fullset").isVectorReady());assertEquals(0,snapshotReads.get());assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());assertEquals(2,sourceReadCalls.get(),"Cold frontend status must close the complete native catalog proof");}
        finally{NormalSourceSql.capture=false;}
        retrieveMutation.set(()->{assertEquals(0,snapshotReads.get(),"The frontend status poll and following Ask must rank before Java body reads");assertTrue(NormalSourceSql.bindingReads.isEmpty());});
        NormalSourceSql.capture=true;
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(authoredQwen(generationLedger,transport))){
            assertNotNull(advice.ask(PROJECT,new AskRequest("Explain the uploaded clause","fullset"),progress));
        }finally{NormalSourceSql.capture=false;}
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("fixture","authored33SourceOneAskActualHttpCounters");receipt.put("sourceCount",33);
        receipt.set("canonicalReadsBySource",JsonUtils.mapper().valueToTree(snapshotCounts()));receipt.set("phaseCanonicalReads",JsonUtils.mapper().valueToTree(phases));
        receipt.set("paidDispatchCanonicalReads",JsonUtils.mapper().valueToTree(dispatchReads));receipt.set("paidDispatchNormalSourceReads",JsonUtils.mapper().valueToTree(dispatchNormalReads));
        receipt.put("canonicalReads",snapshotReads.get());receipt.put("sourceReadHttpCalls",sourceReadCalls.get());receipt.put("indexCalls",indexCalls.get());receipt.put("retrieveCalls",retrieveCalls.get());receipt.put("savedMessages",history.count());receipt.put("realModelOrSourceAcceptance",false);
        write("initial-ask33-owned-epoch-http-counts.json",receipt);System.out.println(JsonUtils.write(receipt));
        assertEquals(1,snapshotCounts().size());assertEquals(2,snapshotCounts().get("42"));
        assertEquals(Arrays.asList(2,2),dispatchReads,"Only the ranked source is hydrated; native catalog proofs retain the complete scope");assertEquals(1,maxOwnedDocuments.get());
        assertEquals(2,dispatchNormalReads.size());assertEquals(dispatchNormalReads.get(0),dispatchNormalReads.get(1),"Generation guards must not reload normal source CLOBs");
        assertEquals(dispatchNormalReads.get(1).intValue(),NormalSourceSql.reads.size(),"Save guards must not reload complete source graphs after the verified owner close");
        assertEquals(2,snapshotReads.get());assertTrue(sourceReadCalls.get()>0,"The actual HTTP text-context path must be exercised");assertEquals(1,retrieveCalls.get());assertEquals(0,indexCalls.get());assertEquals(2,history.count());
        OerBusinessEvidence.Session retained=retainedSession.get();assertNotNull(retained);assertEquals(33,retained.documentIds.size());
        assertNotNull(org.springframework.test.util.ReflectionTestUtils.getField(retained,"queryScope"),"A complete owned read links the existing in-memory receipt after restart");
        assertTrue(retained.chunks().size()<retained.chunkCount(),"Unreturned document chunk graphs must be released before generation and saving");
        @SuppressWarnings("unchecked") List<CanonicalSourceBinding> scope=(List<CanonicalSourceBinding>)org.springframework.test.util.ReflectionTestUtils.getField(retained,"queryScope");
        assertTrue(scope.stream().allMatch(binding->binding.getSnapshotJson()==null),"The full selected scope retains compact identities, never the original snapshot strings");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"context_cas","budget_content","generating_content","saving_content"})
    void unlinkedAskRetainsCloseAndPostCloseSourceGuardsWithoutRepeatedWholeCas(String change)throws Exception{
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");clearLocalIndexReceipts();resetCounters();exerciseActualTextContext();
        List<Integer> dispatchReads=new ArrayList<>();com.consense.ai.HttpSupport transport=authoredQwenTransport(dispatchReads,new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            @Override public void phase(String phase){
                if(("context_cas".equals(change)&&"context".equals(phase)||change.startsWith(phase+"_"))&&changed.compareAndSet(false,true)){
                    if(change.endsWith("_cas"))try{changeExactLengthRestoredMtime(normalCasRoots.get(43L).resolve(snapshots.get(43L).path("observationManifestRef").path("relativePath").asText()));}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
                    else jdbc.update("update source_document set parse_coverage_json=? where id=43","{\"complete\":false,\"authoredChanged\":true}");
                }
            }
        };
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(authoredQwen(generationLedger,transport))){
            assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("Explain the uploaded clause","fullset"),progress));
        }
        assertTrue(changed.get());assertEquals("saving_content".equals(change)?2:"generating_content".equals(change)?1:0,dispatchReads.size());
        assertEquals(0,history.count());assertEquals(0,indexCalls.get());
        if(!"context_cas".equals(change))assertEquals(2,snapshotReads.get(),"After the native catalog exit proof, scalar source drift must fail without another full snapshot pass");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"none","missing_index","db_parsed","binding_revision"})
    void complete33SourceColdReceiptRecoveryRanksBeforeBodiesAndKeepsFallbackAndDriftGuards(String change)throws Exception {
        prepareNormalConditionalFixture();SourceDocument template=documents.findById(43L).orElseThrow();
        for(int ordinal=3;ordinal<=33;ordinal++){
            SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);next.setId(null);
            next.setFileName("Authored receipt source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);documents.saveAndFlush(next);
        }
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());advice.rebuildIndex(PROJECT,"fullset");
        clearLocalIndexReceipts();resetCounters();NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();
        if("missing_index".equals(change))remoteIndexes.clear();
        statusMutation.set(()->{
            assertEquals(0,snapshotReads.get(),"Cold status must precede every canonical body read");
            assertTrue(NormalSourceSql.reads.isEmpty(),"Cold status must not hydrate complete source fields");
            assertTrue(NormalSourceSql.bindingReads.isEmpty(),"Cold status must not hydrate stored snapshot CLOBs");
            if("db_parsed".equals(change)||"binding_revision".equals(change))mutate(43L,change);
        });
        NormalSourceSql.capture=true;
        try {
            if("db_parsed".equals(change)||"binding_revision".equals(change)){
                assertThrows(RuntimeException.class,()->business.openQuerySession(PROJECT,()->documents.findScopeSourcesByProjectId(PROJECT)));
                assertEquals(0,snapshotReads.get());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());
            }else try(OerBusinessEvidence.Session session=business.openQuerySession(PROJECT,()->documents.findScopeSourcesByProjectId(PROJECT))){
                assertEquals(33,session.documentIds.size());assertTrue(session.chunkCount()>0);
                if("missing_index".equals(change)){
                    assertEquals(1,indexCalls.get(),"A genuinely missing index must still use the existing full builder");
                    assertTrue(snapshotReads.get()>=66);assertFalse(NormalSourceSql.bindingReads.isEmpty());
                }else{
                    assertEquals(0,snapshotReads.get());assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());
                    retrieveMutation.set(()->{assertEquals(0,snapshotReads.get());assertTrue(NormalSourceSql.bindingReads.isEmpty());});
                    assertFalse(business.adviceTextHits(session,"Explain the uploaded clause").isEmpty());
                    assertEquals(1,retrieveCalls.get());assertEquals(Collections.singleton("42"),snapshotCounts().keySet());
                }
                session.completeInitialAsk();
            }
        }finally{NormalSourceSql.capture=false;}
        assertNull(statusMutation.get(),"The actual local HTTP status boundary must be exercised");
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"none","missing_index","unsupported_catalog","db_parsed","binding_revision","cas_exit"})
    void complete33SourceColdFrontendStatusUsesCatalogProofAndNeverBuilds(String change)throws Exception {
        prepareNormalConditionalFixture();SourceDocument template=documents.findById(43L).orElseThrow();
        for(int ordinal=3;ordinal<=33;ordinal++){
            SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);next.setId(null);
            next.setFileName("Authored frontend poll source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);documents.saveAndFlush(next);
        }
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());advice.rebuildIndex(PROJECT,"fullset");
        clearLocalIndexReceipts();resetCounters();NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();
        if("missing_index".equals(change))remoteIndexes.clear();
        if("unsupported_catalog".equals(change))doAnswer(call->{JsonNode demand=call.getArgument(1);if("catalog".equals(demand.path("operation").asText()))throw new IllegalStateException("source catalog policy unknown");return call.callRealMethod();})
                .when(canonicalRetrieval).readSources(eq("advisory"),any(JsonNode.class),anyList());
        if(!"unsupported_catalog".equals(change))statusMutation.set(()->{
            assertEquals(0,snapshotReads.get(),"Cold frontend status must reach native status before Java source body reads");
            assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());
            if("db_parsed".equals(change)||"binding_revision".equals(change))mutate(43L,change);
            if("cas_exit".equals(change))try{changeExactLengthRestoredMtime(normalCasRoots.get(43L).resolve(snapshots.get(43L).path("observationManifestRef").path("relativePath").asText()));}catch(IOException failed){throw new java.io.UncheckedIOException(failed);}
        });
        NormalSourceSql.capture=true;
        try{
            IndexStatusVO result=advice.status(PROJECT,"fullset");
            assertEquals("none".equals(change)||"unsupported_catalog".equals(change),result.isVectorReady());
            if("missing_index".equals(change)||"unsupported_catalog".equals(change)){
                assertTrue(snapshotReads.get()>=66,"A genuinely missing index or unsupported catalog retains the sequential full proof fallback");
                assertFalse(NormalSourceSql.bindingReads.isEmpty());
            }else{
                assertEquals(0,snapshotReads.get());assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());
                assertEquals("none".equals(change)||"cas_exit".equals(change)?2:1,sourceReadCalls.get(),"Ready status must complete catalog exit proof; drift must reject before publication");
            }
        }finally{NormalSourceSql.capture=false;}
        if(!"unsupported_catalog".equals(change))assertNull(statusMutation.get(),"The actual native status boundary must be exercised");
        assertEquals(0,indexCalls.get(),"A frontend status poll must never build an index");assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"cold","unlinked","known_unlinked","parsed_drift","binding_drift","runtime_drift","generation_drift","malformed_mode","missing_index","capability_absent","invalidated"})
    void complete33SourceReadinessBootstrapUsesLiveAvailabilityAndPreservesPinnedRecovery(String change)throws Exception {
        if(!"capability_absent".equals(change))((ObjectNode)health.path("oer")).put("indexAvailabilityBootstrapContract","source-verified-cold-canonical-availability-v1");
        prepareNormalConditionalFixture();SourceDocument template=documents.findById(43L).orElseThrow();
        for(int ordinal=3;ordinal<=33;ordinal++){
            SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);next.setId(null);
            next.setFileName("Authored readiness bootstrap source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);documents.saveAndFlush(next);
        }
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());advice.rebuildIndex(PROJECT,"fullset");
        List<SourceDocument> selected=documents.findScopeSourcesByProjectId(PROJECT);
        boolean unattested=Arrays.asList("unlinked","known_unlinked","generation_drift").contains(change);String key;
        if(unattested){
            List<CanonicalSourceBinding> complete=evidence.currentBindings(PROJECT,"advisory",selected.stream().map(SourceDocument::getId).collect(java.util.stream.Collectors.toList()));
            try(OerBusinessEvidence.Session eager=business.load(PROJECT,"advisory",selected)){key=eager.indexKey;canonicalRetrieval.index(key,"advisory",complete);}
            assertUnlinkedSourceGeneration(key);
            if(!"unlinked".equals(change))assertTrue(canonicalRetrieval.indexAvailable(key,"advisory",evidence.currentDisplayBindings(PROJECT,"advisory",selected)).ready);
        }else{key=remoteIndexes.keySet().iterator().next();clearLocalIndexReceipts();}
        if("invalidated".equals(change)){
            String recipe=health.path("oer").path("recipe").asText();((ObjectNode)health.path("oer")).put("recipe","AUTHORED invalid recipe");
            assertFalse(canonicalRetrieval.indexStatus(key,"advisory",evidence.currentBindings(PROJECT,"advisory",selected.stream().map(SourceDocument::getId).collect(java.util.stream.Collectors.toList()))).ready);
            ((ObjectNode)health.path("oer")).put("recipe",recipe);
        }
        resetCounters();availabilityRequests.clear();NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();
        if("missing_index".equals(change))remoteIndexes.clear();
        if("parsed_drift".equals(change)||"binding_drift".equals(change))availabilityMutation.set(()->mutate(43L,"parsed_drift".equals(change)?"db_parsed":"binding_revision"));
        if("runtime_drift".equals(change))availabilityMutation.set(()->((ObjectNode)health.path("oer")).put("recipe","AUTHORED changed during readiness"));
        if("generation_drift".equals(change))availabilityResponseMutation.set(result->result.put("indexGeneration","1".repeat(32)));
        if("malformed_mode".equals(change))availabilityResponseMutation.set(result->result.put("coldBootstrap",false));
        boolean ready=Arrays.asList("cold","unlinked","known_unlinked","capability_absent").contains(change);NormalSourceSql.capture=true;
        try{
            IndexStatusVO result=advice.status(PROJECT,"fullset");assertEquals(ready,result.isVectorReady());
            if("missing_index".equals(change))assertTrue(snapshotReads.get()>=66,"A genuinely missing index retains the prior complete proof fallback");
            else{assertEquals(0,snapshotReads.get());assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());}
            int expectedAvailability="capability_absent".equals(change)||"invalidated".equals(change)?0:1;
            assertEquals(expectedAvailability,availabilityRequests.size());
            assertEquals("capability_absent".equals(change)?1:0,events.stream().filter("status:start"::equals).count(),"Supported readiness must never enter full vector-integrity status");
            if(expectedAvailability==1){
                JsonNode request=availabilityRequests.get(0);
                assertEquals(!unattested,request.path("coldBootstrap").asBoolean());
                if(unattested){assertEquals(SIGNATURE,request.path("expectedSignature").asText());assertEquals(!"unlinked".equals(change),request.has("expectedGeneration"));if(request.has("expectedGeneration"))assertTrue(request.path("expectedGeneration").isNull());}
                else{assertFalse(request.has("expectedSignature"));assertFalse(request.has("expectedGeneration"));}
            }
            ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("fixture","authored33SourceReadinessBootstrapActualHttp");receipt.put("change",change);receipt.put("ready",result.isVectorReady());receipt.put("canonicalReadsAtReady",snapshotReads.get());receipt.put("availabilityCallsAtReady",availabilityRequests.size());receipt.put("fullStatusCallsAtReady",events.stream().filter("status:start"::equals).count());
            AtomicInteger paid=new AtomicInteger();
            if(ready&&!"capability_absent".equals(change)){
                assertTrue(currentAdviceReceiptGenerationKnown(key));
                String generation=org.springframework.test.util.ReflectionTestUtils.invokeMethod(business,"sourceGeneration",documents.findScopeSourcesByProjectId(PROJECT));
                assertTrue(canonicalRetrieval.sourceGenerationLinked("advisory",key,generation));
                assertThrows(CancellationException.class,()->askUntilPaidBoundary(paid,null));assertEquals(1,paid.get());assertEquals(Collections.singleton("42"),snapshotCounts().keySet());
            }
            receipt.put("paidBoundaries",paid.get());receipt.put("canonicalReadsAfterAsk",snapshotReads.get());receipt.put("indexCalls",indexCalls.get());receipt.put("modelCalls",chatCalls.get());receipt.put("historyRows",history.count());receipt.put("realModelOrSourceAcceptance",false);write("readiness-bootstrap33-"+change+".json",receipt);
            assertEquals(0,indexCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        }finally{NormalSourceSql.capture=false;}
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"same_generation","same_null","different_generation","delayed_null","current_null"})
    void concurrent33SourceRecoveryPreservesTheNewerKnownGeneration(String change)throws Exception {
        ((ObjectNode)health.path("oer")).put("indexAvailabilityBootstrapContract","source-verified-cold-canonical-availability-v1");
        prepareNormalConditionalFixture();SourceDocument template=documents.findById(43L).orElseThrow();
        for(int ordinal=3;ordinal<=33;ordinal++){
            SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);next.setId(null);
            next.setFileName("Authored concurrent recovery source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);documents.saveAndFlush(next);
        }
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());advice.rebuildIndex(PROJECT,"fullset");
        List<SourceDocument> selected=documents.findScopeSourcesByProjectId(PROJECT);
        List<CanonicalSourceBinding> complete=evidence.currentBindings(PROJECT,"advisory",selected.stream().map(SourceDocument::getId).collect(java.util.stream.Collectors.toList()));
        String key;try(OerBusinessEvidence.Session eager=business.load(PROJECT,"advisory",selected)){key=eager.indexKey;canonicalRetrieval.index(key,"advisory",complete);}
        assertUnlinkedSourceGeneration(key);assertFalse(currentAdviceReceiptGenerationKnown(key));
        List<CanonicalSourceBinding> compact=evidence.currentDisplayBindings(PROJECT,"advisory",selected);
        String sourceGeneration=org.springframework.test.util.ReflectionTestUtils.invokeMethod(business,"sourceGeneration",selected);
        String delayedGeneration=Arrays.asList("same_null","delayed_null").contains(change)?null:"a".repeat(32);
        String currentGeneration=Arrays.asList("same_null","current_null").contains(change)?null:"same_generation".equals(change)?delayedGeneration:"b".repeat(32);
        boolean matching=Objects.equals(delayedGeneration,currentGeneration);
        CountDownLatch firstResponseCaptured=new CountDownLatch(1),releaseFirstResponse=new CountDownLatch(1);
        resetCounters();availabilityRequests.clear();NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();
        availabilityResponseMutation.set(result->{if(delayedGeneration==null)result.putNull("indexGeneration");else result.put("indexGeneration",delayedGeneration);});
        availabilityMutation.set(()->{
            firstResponseCaptured.countDown();
            try{assertTrue(releaseFirstResponse.await(60,TimeUnit.SECONDS),"The delayed response must be released after the concurrent generation is published");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Authored recovery wait interrupted",interrupted);}
        });
        ExecutorService caller=Executors.newSingleThreadExecutor();NormalSourceSql.capture=true;
        try{
            Future<IndexStatusVO> delayed=caller.submit(()->advice.status(PROJECT,"fullset"));
            assertTrue(firstResponseCaptured.await(30,TimeUnit.SECONDS),"Recovery must capture the unknown-generation receipt and its response before availability publishes");
            assertFalse(availabilityRequests.get(0).has("expectedGeneration"));assertEquals(SIGNATURE,availabilityRequests.get(0).path("expectedSignature").asText());
            availabilityResponseMutation.set(result->{if(currentGeneration==null)result.putNull("indexGeneration");else result.put("indexGeneration",currentGeneration);});
            assertTrue(canonicalRetrieval.indexAvailable(key,"advisory",compact).ready);
            assertTrue(currentAdviceReceiptGenerationKnown(key));assertEquals(currentGeneration,currentAdviceReceiptIndexGeneration(key));assertUnlinkedSourceGeneration(key);
            releaseFirstResponse.countDown();IndexStatusVO resumed=delayed.get(60,TimeUnit.SECONDS);
            ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("fixture","authored33SourceConcurrentRecoveryActualHttp");receipt.put("change",change);
            receipt.put("matching",matching);receipt.put("resumedReady",resumed.isVectorReady());receipt.put("delayedGeneration",delayedGeneration);receipt.put("publishedGeneration",currentGeneration);receipt.put("retainedGeneration",currentAdviceReceiptIndexGeneration(key));
            receipt.put("canonicalReads",snapshotReads.get());receipt.put("availabilityCalls",availabilityRequests.size());receipt.put("fullStatusCalls",events.stream().filter("status:start"::equals).count());
            receipt.put("indexCalls",indexCalls.get());receipt.put("retrieveCalls",retrieveCalls.get());receipt.put("modelCalls",chatCalls.get());receipt.put("historyRows",history.count());receipt.put("realModelOrSourceAcceptance",false);write("concurrent-recovery33-"+change+".json",receipt);
            assertEquals(currentGeneration,currentAdviceReceiptIndexGeneration(key),"A delayed response must retain the concurrently published known generation, including known null");
            assertTrue(currentAdviceReceiptGenerationKnown(key));assertEquals(matching,resumed.isVectorReady(),"Only a generation matching the newer receipt may gain source attestation");
            if(!matching){
                assertUnlinkedSourceGeneration(key);
                availabilityResponseMutation.set(result->{if(currentGeneration==null)result.putNull("indexGeneration");else result.put("indexGeneration",currentGeneration);});
                assertTrue(advice.status(PROJECT,"fullset").isVectorReady(),"A fresh recovery must attest the preserved generation without rebuilding or hydrating the corpus");
                JsonNode fresh=availabilityRequests.get(availabilityRequests.size()-1);assertTrue(fresh.has("expectedGeneration"));
                if(currentGeneration==null)assertTrue(fresh.path("expectedGeneration").isNull());else assertEquals(currentGeneration,fresh.path("expectedGeneration").asText());
            }
            assertTrue(canonicalRetrieval.sourceGenerationLinked("advisory",key,sourceGeneration));assertEquals(currentGeneration,currentAdviceReceiptIndexGeneration(key));
            assertEquals(0,snapshotReads.get());assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());
            assertEquals(0,events.stream().filter("status:start"::equals).count());assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        }finally{releaseFirstResponse.countDown();NormalSourceSql.capture=false;caller.shutdownNow();assertTrue(caller.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private String currentAdviceReceiptIndexGeneration(String key)throws Exception{
        Object client=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(canonicalRetrieval);
        Map<?,?> states=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(client,"receiptStates");
        Object receipt=org.springframework.test.util.ReflectionTestUtils.getField(states.get(key),"receipt");
        return receipt==null?"<missing>":(String)org.springframework.test.util.ReflectionTestUtils.getField(receipt,"indexGeneration");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"none","known_generation","db_parsed","binding_revision"})
    void concurrent33SourceColdStatusRetainsMatchingReceiptAndRejectsSourceDrift(String change)throws Exception {
        prepareNormalConditionalFixture();SourceDocument template=documents.findById(43L).orElseThrow();
        for(int ordinal=3;ordinal<=33;ordinal++){
            SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);next.setId(null);
            next.setFileName("Authored concurrent status source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);documents.saveAndFlush(next);
        }
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());advice.rebuildIndex(PROJECT,"fullset");
        clearLocalIndexReceipts();resetCounters();NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();
        CountDownLatch firstStatusEntered=new CountDownLatch(1),releaseFirstStatus=new CountDownLatch(1);
        statusMutation.set(()->{
            firstStatusEntered.countDown();
            try{assertTrue(releaseFirstStatus.await(60,TimeUnit.SECONDS),"The first cold native status must be released by the authored overlap test");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Authored status wait interrupted",interrupted);}
        });
        ExecutorService callers=Executors.newFixedThreadPool(2);NormalSourceSql.capture=true;
        try{
            Future<IndexStatusVO> first=callers.submit(()->advice.status(PROJECT,"fullset"));
            assertTrue(firstStatusEntered.await(30,TimeUnit.SECONDS),"The first operation must capture its empty receipt before the second starts");
            Future<IndexStatusVO> second=callers.submit(()->advice.status(PROJECT,"fullset"));
            IndexStatusVO published=second.get(60,TimeUnit.SECONDS);
            assertTrue(published.isVectorReady(),"The second operation must publish a complete checked receipt before the first response resumes");
            assertEquals(0,snapshotReads.get());assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());
            assertEquals(3,sourceReadCalls.get(),"Both catalog entries and the second operation's complete catalog exit must precede release");
            if("known_generation".equals(change)){
                assertTrue(advice.status(PROJECT,"fullset").isVectorReady(),"Availability must upgrade the concurrent receipt before the older status resumes");
                assertTrue(currentAdviceReceiptGenerationKnown(published.getIndexKey()));
            }else if(!"none".equals(change))mutate(43L,change);
            releaseFirstStatus.countDown();IndexStatusVO resumed=first.get(60,TimeUnit.SECONDS);
            ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("fixture","authored33SourceConcurrentColdStatusActualHttp");receipt.put("change",change);
            receipt.put("firstReady",resumed.isVectorReady());receipt.put("secondReady",published.isVectorReady());receipt.put("canonicalReads",snapshotReads.get());receipt.put("sourceReadHttpCalls",sourceReadCalls.get());
            receipt.put("generationKnownAfter",resumed.isVectorReady()&&currentAdviceReceiptGenerationKnown(published.getIndexKey()));
            receipt.set("canonicalReadsBySource",JsonUtils.mapper().valueToTree(snapshotCounts()));receipt.put("indexCalls",indexCalls.get());receipt.put("retrieveCalls",retrieveCalls.get());receipt.put("modelCalls",chatCalls.get());receipt.put("historyRows",history.count());receipt.put("realModelOrSourceAcceptance",false);
            write("concurrent-cold-status33-"+change+".json",receipt);
            boolean unchanged="none".equals(change)||"known_generation".equals(change);
            assertEquals(unchanged,resumed.isVectorReady(),"A matching concurrent cold receipt must survive; committed source or binding drift must reject");
            assertEquals(0,snapshotReads.get(),"A matching publication or scalar source rejection must not fall back to complete Java source bodies");
            if(unchanged){
                assertTrue(NormalSourceSql.reads.isEmpty());assertTrue(NormalSourceSql.bindingReads.isEmpty());assertEquals(4,sourceReadCalls.get());
                String generation=org.springframework.test.util.ReflectionTestUtils.invokeMethod(business,"sourceGeneration",documents.findScopeSourcesByProjectId(PROJECT));
                assertTrue(canonicalRetrieval.sourceGenerationLinked("advisory",published.getIndexKey(),generation),"A later equivalent response must retain the healthy shared receipt");
                if("known_generation".equals(change))assertTrue(currentAdviceReceiptGenerationKnown(published.getIndexKey()),"A delayed cold status must not downgrade an established generation pin");
            }
            assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
        }finally{releaseFirstStatus.countDown();NormalSourceSql.capture=false;callers.shutdownNow();assertTrue(callers.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private boolean currentAdviceReceiptGenerationKnown(String key)throws Exception{
        Object client=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(canonicalRetrieval);
        Map<?,?> states=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(client,"receiptStates");
        Object receipt=org.springframework.test.util.ReflectionTestUtils.getField(states.get(key),"receipt");
        return Boolean.TRUE.equals(org.springframework.test.util.ReflectionTestUtils.getField(receipt,"generationKnown"));
    }

    @Test void cold33SourceStatusAndExplicitRebuildReleaseEverySourceBeforeOpeningTheNext()throws Exception {
        prepareNormalConditionalFixture();SourceDocument template=documents.findById(43L).get();
        for(int ordinal=3;ordinal<=33;ordinal++){SourceDocument next=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(template,next);next.setId(null);next.setFileName("Authored cold source "+ordinal+".docx");next.setNormalCandidateJson(null);next.setVerifiedBusinessContentSha256(null);documents.saveAndFlush(next);}
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());AtomicInteger maxOwnedDocuments=new AtomicInteger();assertSequentialOwners(maxOwnedDocuments);
        resetCounters();assertTrue(advice.rebuildIndex(PROJECT,"fullset").isVectorReady());assertEquals(1,indexCalls.get());assertEquals(132,snapshotReads.get());assertEquals(1,maxOwnedDocuments.get());
        clearLocalIndexReceipts();resetCounters();OerBusinessEvidence.CurrentIndexStatus cold=business.currentIndexStatus(PROJECT,"advisory",()->documents.findScopeSourcesByProjectId(PROJECT));
        assertTrue(cold.state.ready);assertEquals(33,cold.documentCount);assertEquals(0,snapshotReads.get());assertEquals(2,sourceReadCalls.get(),"Cold status reuses the actual ready native index with complete catalog entry/exit proof");assertEquals(0,indexCalls.get());assertEquals(0,retrieveCalls.get());assertEquals(0,chatCalls.get());
        resetCounters();assertTrue(advice.status(PROJECT,"fullset").isVectorReady());assertEquals(0,snapshotReads.get(),"The next routine status reuses the checked compact receipt");assertEquals(1,maxOwnedDocuments.get());
    }
    private void assertSequentialOwners(AtomicInteger maximum){
        AtomicReference<CanonicalEvidenceService.VerifiedBindingFrame> previous=new AtomicReference<>();
        doAnswer(call->{List<Long> ids=call.getArgument(2);maximum.accumulateAndGet(ids.size(),Math::max);assertEquals(1,ids.size(),"Cold status/index and this one-ranked-source Ask must never open a whole-scope owner");
            if(previous.get()!=null)assertThrows(RuntimeException.class,()->previous.get().bindings(),"Release the preceding owned source before opening another");
            CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)call.callRealMethod();previous.set(frame);return frame;
        }).when(evidence).verifiedBindings(eq(PROJECT),eq("advisory"),anyList());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"none","saving_original","budget_routing","budget_catalog"})
    void warmAskChecksUnreturnedOriginalBytesAtEntryAndFinalSaveOnlyWhileScalarDriftStillRejects(String change)throws Exception {
        prepareNormalConditionalFixture();advice.rebuildIndex(PROJECT,"fullset");resetCounters();exerciseActualTextContext();clearInvocations(reader);
        AtomicInteger originalReads=new AtomicInteger();
        doAnswer(call->{SourceDocument source=call.getArgument(0);if(Long.valueOf(43).equals(source.getId()))originalReads.incrementAndGet();return call.callRealMethod();}).when(reader).requireOriginalBytes(any());
        com.consense.ai.HttpSupport transport=authoredQwenTransport(new ArrayList<>(),new ArrayList<>());
        AtomicBoolean changed=new AtomicBoolean();
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            @Override public void phase(String phase){
                if(!change.startsWith(phase+"_")||!changed.compareAndSet(false,true))return;
                if("saving_original".equals(change))mutate(43L,"original_same_length_restored_mtime");
                else if("budget_routing".equals(change))jdbc.update("update source_document set review_role=? where id=43","standard");
                else newScopeMember();
            }
        };
        List<Integer> readsAtPaidBoundaries=new ArrayList<>();
        com.consense.ai.HttpSupport counted=mock(com.consense.ai.HttpSupport.class);
        doAnswer(call->{readsAtPaidBoundaries.add(originalReads.get());return transport.postJsonBoundedWithoutReplay(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5));})
                .when(counted).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(authoredQwen(generationLedger,counted))){
            if("none".equals(change))assertNotNull(advice.ask(PROJECT,new AskRequest("Explain the uploaded clause","fullset"),progress));
            else assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("Explain the uploaded clause","fullset"),progress));
        }
        if(change.startsWith("budget_")){assertTrue(changed.get());assertTrue(readsAtPaidBoundaries.isEmpty());assertEquals(0,history.count());}
        else{assertEquals(Arrays.asList(1,1),readsAtPaidBoundaries,"Model preflight/generation must not hash every unreturned original again");assertEquals(2,originalReads.get(),"The final actual original-byte proof is mandatory, even when size/time are restored");assertEquals("none".equals(change)?2:0,history.count());}
        verify(reader,never()).currentStamp(PROJECT,43L,"advisory");assertEquals(0,indexCalls.get());
    }
    private void clearLocalIndexReceipts()throws Exception{
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(canonicalRetrieval);
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(target,"receiptStates")).clear();
    }
    private void exerciseActualTextContext(){
        doAnswer(call->{OerBusinessEvidence.Session selected=call.getArgument(0);
            business.sourceTextHits(selected,Collections.singletonList(selected.chunks().get(0).getId()));return call.callRealMethod();
        }).when(business).advisoryTextContext(any(),anyList());
    }
    private com.consense.ai.HttpSupport authoredQwenTransport(List<Integer> dispatchReads,List<Integer> normalReads){
        com.consense.ai.HttpSupport transport=mock(com.consense.ai.HttpSupport.class);
        doAnswer(call->{dispatchReads.add(snapshotReads.get());normalReads.add(NormalSourceSql.reads.size());
            boolean probe=JsonUtils.parse(call.<String>getArgument(1)).path("max_tokens").asInt()==1;
            String content=probe?"{":com.consense.service.advice.AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported wording [1].\",\"citations\":[\"[1]\"]}");
            ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion");envelope.put("model","qwen3.8-27b");
            ObjectNode choice=envelope.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason","stop");choice.putObject("message").put("role","assistant").put("content",content);
            envelope.putObject("usage").put("prompt_tokens",1000).put("completion_tokens",1).put("total_tokens",1001);return JsonUtils.write(envelope);
        }).when(transport).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
        return transport;
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"availability","retrieve","owner_close"})
    void initialQwenEpochRejectsSourceChangesBeforeAnyPaidAction(String boundary) throws Exception {
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        if("availability".equals(boundary))availabilityMutation.set(()->mutate(42L,"original_same_length_restored_mtime"));
        if("retrieve".equals(boundary))retrieveMutation.set(()->mutate(42L,"cas_same_length_restored_mtime"));
        AtomicInteger paidOpens=new AtomicInteger();
        com.consense.service.llm.QwenGenerationLedger ledger=mock(com.consense.service.llm.QwenGenerationLedger.class);
        doAnswer(call->{paidOpens.incrementAndGet();throw new CancellationException("AUTHORED unexpected paid action");}).when(ledger).open(anyString(),anyString(),any());
        com.consense.service.advice.AdviceProgress progress=new com.consense.service.advice.AdviceProgress(){
            @Override public void phase(String phase){if("owner_close".equals(boundary)&&"context".equals(phase))mutate(42L,"cas_same_length_restored_mtime");}
        };
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(authoredQwen(ledger))){
            assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("AUTHORED complete native unit","fullset"),progress));
        }
        assertEquals(0,paidOpens.get(),"Changed source must reject before a paid action opens");assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    private AdviceService.QwenOperation authoredQwen(com.consense.service.llm.QwenGenerationLedger ledger)throws Exception{
        return authoredQwen(ledger,mock(com.consense.ai.HttpSupport.class));
    }
    private AdviceService.QwenOperation authoredQwen(com.consense.service.llm.QwenGenerationLedger ledger,com.consense.ai.HttpSupport transport)throws Exception{
        ConsenseProperties configured=new ConsenseProperties();com.consense.ai.LlmProfiles.configuration(configured,"qwen").setEnabled(true);
        com.consense.ai.LlmProfiles.configuration(configured,"qwen").setApiKey("AUTHORED NO REAL CREDENTIAL");
        com.consense.ai.QwenChatClient client=new com.consense.ai.QwenChatClient(com.consense.ai.LlmProfiles.configuration(configured,"qwen"),transport);
        com.consense.ai.LlmOperation model=new com.consense.ai.LlmProfiles(configured,mock(LlmClient.class),mock(LlmClient.class),client,mock(LlmClient.class)).resolve("qwen");
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(advice);
        Integer historyLimit=org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"historyCandidateLimit");
        com.consense.service.llm.QwenChatUsagePreflight.Operation operation=new com.consense.service.llm.QwenChatUsagePreflight(configured,ledger).capture(model,"advice",historyLimit);
        java.lang.reflect.Constructor<AdviceService.QwenOperation> constructor=AdviceService.QwenOperation.class.getDeclaredConstructor(com.consense.service.llm.QwenChatUsagePreflight.Operation.class,String.class);constructor.setAccessible(true);
        return constructor.newInstance(operation,org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"historyConfiguration"));
    }



    @Test void ownedInitialSessionRejectsWrongThreadCloseAndLaterContextStartsFreshEpoch()throws Exception{
        advice.rebuildIndex(PROJECT,"fullset");resetCounters();
        List<CanonicalEvidenceService.VerifiedBindingFrame> frames=new ArrayList<>();
        doAnswer(call->{CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)call.callRealMethod();frames.add(frame);return frame;})
                .when(evidence).verifiedBindings(eq(PROJECT),eq("advisory"),anyList());
        try(OerBusinessEvidence.Session selected=business.openReadSession(PROJECT,"advisory",documents.findScopeSourcesByProjectId(PROJECT))){
            ExecutorService other=Executors.newSingleThreadExecutor();
            try{assertTrue(other.submit(()->{assertThrows(RuntimeException.class,selected::close);return true;}).get(10,TimeUnit.SECONDS));}
            finally{other.shutdownNow();}
            String origin=selected.chunks().get(0).getId();business.sourceTextHits(selected,Collections.singletonList(origin));
            assertEquals(1,frames.size());assertDoesNotThrow(frames.get(0)::bindings);
            selected.close();selected.close();assertThrows(RuntimeException.class,frames.get(0)::bindings);
            assertFalse(business.sourceTextHits(selected,Collections.singletonList(origin)).isEmpty());
            assertEquals(2,frames.size(),"After initial closure, optional context owns a new source epoch");
            for(CanonicalEvidenceService.VerifiedBindingFrame frame:frames)assertThrows(RuntimeException.class,frame::bindings);
        }
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

}
