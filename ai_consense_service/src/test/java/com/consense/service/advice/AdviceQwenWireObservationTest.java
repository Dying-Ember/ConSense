package com.consense.service.advice;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.config.AiConfig;
import com.consense.service.llm.QwenChatUsagePreflight;
import com.consense.service.llm.QwenGenerationLedger;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingInputBudget;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual sealed adapter/service paths with authored HttpSupport/ledger doubles; no real network or SQL. */
class AdviceQwenWireObservationTest {
    @TempDir Path root;
    private static final String SECRET="AUTHORED-QWEN-SECRET",SOURCE=VettingCorpus.hash("AUTHORED SOURCE");
    private static final String USER="完整原题☃\nDeclared ownership for this alias (source observations): {\"clauseId\":null,\"ownerStatus\":\"unknown\"}\nComplete source with  two spaces.\n";

    @Test void compactDrawingUsesASealedDirectQwenRequestAndPublishesAnExplicitUnverifiedDraft() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("compact-drawing")),true,true);
        f.answers.add("{\"tool\":\"draw_flowchart\",\"nodes\":[{\"id\":\"start\",\"label\":\"Written request\"},{\"id\":\"end\",\"label\":\"Decision issued\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\",\"label\":\"Within 14 days of receipt\"}],\"citations\":[\"[1]\"]}");
        AdviceService.AnswerRecord answer;
        try(QwenChatUsagePreflight.Operation.ActionScope action=f.operation.openAction("test:drawing",()->{})){
            answer=ReflectionTestUtils.invokeMethod(f.service,"completeAdvice",AdviceFlowchartTool.INSTRUCTION,USER,null,null,f.operation,SOURCE,(Runnable)()->{},f.publication,"Please draw a flowchart","draft",false);
            assertNotNull(answer);assertFalse(answer.isGrounded());assertEquals("T6",answer.getQuestionType());assertTrue(f.sections.isEmpty());
            ReflectionTestUtils.invokeMethod(f.publication,"publishValidated");
        }
        assertEquals(1,f.sections.size());assertTrue(f.sections.get(0).contains("```mermaid\nflowchart TD"));
        assertTrue(f.sections.get(0).contains("not independently verified"));assertTrue(f.sections.get(0).contains("Sources consulted (not independent validation): [1]"));
        assertEquals(1,f.bodies.size());assertFalse(JsonUtils.parse(f.bodies.get(0)).path("stream").asBoolean());
        assertEquals("not_performed",json(attempts(f.directory).get(0),"request-receipt.json").path("inputTokenPreflight").asText());
        VettingInputBudget.Input input=boundInput(attempts(f.directory).get(0));
        assertTrue(input.getSchema().path("properties").has("tool"));assertFalse(input.getSchema().path("properties").has("sections"));
        verify(f.ledger,never()).reserve(anyString(),anyString(),anyString(),anyString(),eq(false));
        verify(f.ledger).reserve(anyString(),anyString(),eq("draft-answer-0"),anyString(),eq(true));
    }

    @Test void drawingRejectsUnknownAliasesAndUsesOneCompactRepairWithoutPublishingBadOutput() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("drawing-repair")),false,true);
        String call="{\"tool\":\"draw_flowchart\",\"nodes\":[{\"id\":\"start\",\"label\":\"Start\"},{\"id\":\"end\",\"label\":\"End\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\",\"label\":\"\"}],\"citations\":[\"[1]\"]}";
        f.answers.add(call.replace("[1]","[99]"));f.answers.add(call);
        try(QwenChatUsagePreflight.Operation.ActionScope action=f.operation.openAction("test:drawing-repair",()->{})){
            AdviceService.AnswerRecord answer=ReflectionTestUtils.invokeMethod(f.service,"completeAdvice",AdviceFlowchartTool.INSTRUCTION,USER,null,null,f.operation,SOURCE,(Runnable)()->{},f.publication,"Draw a flowchart","draft",false);
            assertNotNull(answer);assertFalse(answer.isGrounded());assertTrue(f.sections.isEmpty());
            ReflectionTestUtils.invokeMethod(f.publication,"publishValidated");
        }
        assertEquals(2,f.bodies.size());assertEquals(1,f.sections.size());assertFalse(f.sections.get(0).contains("[99]"));
        String repair=JsonUtils.parse(f.bodies.get(1)).path("messages").get(1).path("content").asText();
        assertTrue(repair.contains("Correct the draw_flowchart JSON"));assertFalse(repair.contains("full complete answer"));
    }

    @Test void drawingStillRejectsSourceChangeAfterGenerationBeforePublication() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("drawing-stale-source")),false,false);
        Runnable guard=()->{if(f.bodies.size()>=1)throw new com.consense.common.BizException(4204,"AUTHORED source changed");};
        try(QwenChatUsagePreflight.Operation.ActionScope action=f.operation.openAction("test:drawing-stale",guard)){
            com.consense.common.BizException rejected=assertThrows(com.consense.common.BizException.class,()->ReflectionTestUtils.invokeMethod(f.service,"completeAdvice",AdviceFlowchartTool.INSTRUCTION,USER,null,null,f.operation,SOURCE,guard,f.publication,"Draw a flowchart","draft",false));
            assertEquals(4204,rejected.getCode());assertTrue(f.sections.isEmpty());assertNull(ReflectionTestUtils.getField(f.publication,"draftFlowchart"));
        }
    }

    @Test void draftAndSupplementUseDistinctPaidPhasesAndPublishOnlyValidatedSections() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("unpublished-draft")),false,true);
        try(QwenChatUsagePreflight.Operation.ActionScope action=f.operation.openAction("test:advice-wire",()->{})){
            AdviceService.AnswerRecord draft=ReflectionTestUtils.invokeMethod(f.service,"completeAdvice","AUTHORED system",USER,null,null,f.operation,SOURCE,(Runnable)()->{},f.publication,"Explain the uploaded clause","draft",false);
            assertNotNull(draft);assertTrue(f.sections.isEmpty());
            AdviceService.AnswerRecord finalAnswer=ReflectionTestUtils.invokeMethod(f.service,"completeAdvice","AUTHORED system",USER+"\nAdditional current source [2]",null,null,f.operation,SOURCE,(Runnable)()->{},f.publication,"Explain the uploaded clause","supplement",false);
            assertNotNull(finalAnswer);assertTrue(f.sections.isEmpty());
            ReflectionTestUtils.invokeMethod(f.publication,"publishValidated");
            assertFalse(f.sections.isEmpty());
        }
        verify(f.ledger).reserve(anyString(),anyString(),eq("draft-answer-0"),anyString(),eq(true));
        verify(f.ledger,never()).reserve(anyString(),anyString(),anyString(),anyString(),eq(false));
        verify(f.ledger).reserve(anyString(),anyString(),eq("supplement-answer-0"),anyString(),eq(true));
    }
    @Test void malformedCompleteDraftKeepsOutputInvalidAndPublishesNoSections() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("invalid-unpublished-draft")),false,true);
        f.answers.add("{");f.answers.add("{");
        try(QwenChatUsagePreflight.Operation.ActionScope action=f.operation.openAction("test:advice-wire",()->{})){
            com.consense.common.BizException failure=assertThrows(com.consense.common.BizException.class,()->ReflectionTestUtils.invokeMethod(f.service,"completeAdvice","AUTHORED system",USER,null,null,f.operation,SOURCE,(Runnable)()->{},f.publication,"Explain the uploaded clause","draft",false));
            assertEquals(4205,failure.getCode());assertTrue(failure.getMessage().contains("output_invalid"));assertTrue(f.sections.isEmpty());
        }
    }

    @Test void actualSealedEntitiesShowOnlyGenerationWasDispatched() throws Exception {
        for(boolean streaming:Arrays.asList(false,true)){
            Fixture f=new Fixture(Files.createDirectory(root.resolve("enabled-"+streaming)),true,streaming);f.complete("AUTHORED system",USER);
            assertEquals(1,f.bodies.size());Path attempt=attempts(f.directory).get(0);JsonNode receipt=json(attempt,"request-receipt.json");
            VettingInputBudget.Input input=boundInput(attempt);assertTrue(VettingInputBudget.validBinding(input));
            assertEquals(USER,input.getMessages().get(1).getContent());assertEquals(USER,json(attempt,"generation-request.utf8.json").path("messages").get(1).path("content").asText());
            assertEquals(f.bodies,Collections.singletonList(text(attempt,"generation-request.utf8.json")));
            assertEquals("not_performed",receipt.path("inputTokenPreflight").asText());
            assertFalse(receipt.path("preflightRequestDispatched").asBoolean(true));assertFalse(receipt.path("inputTokensObserved").asBoolean(true));
            assertEquals(1,json(attempt,"preflight-request.utf8.json").path("max_tokens").asInt());assertFalse(json(attempt,"preflight-request.utf8.json").path("stream").asBoolean());
            assertEquals(streaming,json(attempt,"generation-request.utf8.json").path("stream").asBoolean());assertEquals(16384,json(attempt,"generation-request.utf8.json").path("max_tokens").asInt());
            ArgumentCaptor<Map> receipts=ArgumentCaptor.forClass(Map.class);verify(f.ledger).complete(any(),receipts.capture());
            assertEquals(receipts.getValue().get("requestSha256"),receipt.path("generationRequest").path("sha256").asText());
            ArgumentCaptor<String> bindings=ArgumentCaptor.forClass(String.class);verify(f.ledger).reserve(anyString(),anyString(),anyString(),bindings.capture(),eq(true));
            assertEquals(input.getSerializedInputSha256(),bindings.getValue());assertEquals(input.getSerializedInputSha256(),receipt.path("serializedInputSha256").asText());
            for(String[] entity:new String[][]{{"boundInput","bound-input.utf8.json"},{"preflightRequest","preflight-request.utf8.json"},{"generationRequest","generation-request.utf8.json"}}){byte[] bytes=Files.readAllBytes(attempt.resolve(entity[1]));assertEquals(AdviceWireObservation.sha(bytes),receipt.path(entity[0]).path("sha256").asText());assertEquals(bytes.length,receipt.path(entity[0]).path("utf8Bytes").asInt());}
            assertEquals(SOURCE,receipt.path("sourceSnapshotSha256").asText());assertEquals(0,receipt.path("attempt").asInt());assertFalse(receipt.path("dispatchObservedByThisRecorder").asBoolean());assertTrue(receipt.path("httpEntityScope").asText().contains("TCP/TLS/framing bytes are unobserved"));
        }
    }

    @Test void validatedSectionsReachTheCallerBeforeProviderCompletion() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("incremental-qwen")),false,true);
        String answer=f.contract();
        int split=answer.indexOf("\"content\":\"Supported wording");
        assertTrue(split>0,"The fixture must contain separate limits and analysis sections");
        doAnswer(call->{
            String body=call.getArgument(1);f.beforeDispatch(body);
            Consumer<String> events=call.getArgument(6);
            events.accept(delta(answer.substring(0,split),null));
            assertEquals(1,f.sections.size(),"The validated limits section arrives before the remaining answer");
            events.accept(delta(answer.substring(split)+"                        ",null));
            assertEquals(2,f.sections.size(),"Both validated sections arrive before provider completion");
            events.accept(delta("","stop"));events.accept("[DONE]");
            return "authored stream receipt";
        }).when(f.http).postJsonStream(anyString(),anyString(),anyLong(),anyString(),anyInt(),eq(true),any(),any());
        f.complete("AUTHORED system",USER);
        assertTrue(JsonUtils.parse(f.bodies.get(0)).path("stream").asBoolean());
        assertEquals(2,f.sections.size());
    }

    @Test void provisionalDraftCanBeFullyRepairedWithoutAppendingASecondAnswer() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("provisional-draft-repair")),false,true);
        ObjectNode invalid=(ObjectNode)JsonUtils.parse(f.contract());
        invalid.put("basisScope","reference_only");
        f.answers.add(JsonUtils.write(invalid));
        f.answers.add(f.contract().replace("Supported wording", "Repaired wording"));
        AdviceService.AnswerRecord repaired;
        try(QwenChatUsagePreflight.Operation.ActionScope action=f.operation.openAction("test:provisional-draft",()->{})){
            repaired=ReflectionTestUtils.invokeMethod(f.service,"completeAdvice","AUTHORED system",USER,null,null,f.operation,SOURCE,(Runnable)()->{},f.publication,"Explain the uploaded clause","draft",true);
        }
        assertNotNull(repaired);
        assertTrue((Boolean)ReflectionTestUtils.getField(f.publication,"published"));
        assertEquals(2,f.sections.size(),"Only the provisional first attempt is delivered as sections");
        assertTrue(f.sections.get(1).contains("Supported wording"));
        assertTrue(JsonUtils.write(repaired).contains("Repaired wording"));
        assertTrue(JsonUtils.parse(f.bodies.get(0)).path("stream").asBoolean());
        assertFalse(JsonUtils.parse(f.bodies.get(1)).path("stream").asBoolean());
        assertTrue(JsonUtils.parse(f.bodies.get(1)).path("messages").get(1).path("content").asText().contains("full complete answer"));
    }

    @Test void actualHttpContextRejectionFailsWithoutAnswerOrPreflight() throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<JsonNode> input=new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();input.set(JsonUtils.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] body="{\"error\":{\"message\":\"maximum context length exceeded\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            Fixture f=new Fixture(Files.createDirectory(root.resolve("context-http")),false,false,"qwen",false,true,"http://127.0.0.1:"+server.getAddress().getPort());
            assertThrows(com.consense.common.BizException.class,()->f.complete("AUTHORED system",USER));
            assertEquals(1,calls.get());assertEquals(16384,input.get().path("max_tokens").asInt());
            assertTrue(f.sections.isEmpty());verify(f.ledger).unknown(any(),anyMap());verify(f.ledger,never()).complete(any(),anyMap());
            verify(f.ledger,never()).reserve(anyString(),anyString(),anyString(),anyString(),eq(false));
        } finally {server.stop(0);}
    }

    private static String delta(String content,String finishReason) {
        ObjectNode event=JsonUtils.mapper().createObjectNode();event.put("model","qwen3.8-27b");
        ObjectNode choice=event.putArray("choices").addObject();choice.put("index",0);
        choice.putObject("delta").put("content",content);
        if(finishReason==null)choice.putNull("finish_reason");else choice.put("finish_reason",finishReason);
        return JsonUtils.write(event);
    }

    @Test void disabledObservationWritesNothingAndLeavesTheSameQwenWireEntities() throws Exception {
        Fixture enabled=new Fixture(Files.createDirectory(root.resolve("enabled")),true,false),disabled=new Fixture(Files.createDirectory(root.resolve("disabled")),false,false);
        enabled.complete("AUTHORED system",USER);disabled.complete("AUTHORED system",USER);
        assertEquals(enabled.bodies,disabled.bodies);assertTrue(attempts(disabled.directory).isEmpty());assertNull(disabled.settings.getWireObservationRoot());
    }

    @Test void streamedAnswerRepairUsesACompleteNonstreamingQwenRequest() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("full-repair")),true,true);
        f.answers.add("{\"basisScope\":}");f.answers.add(f.contract());f.complete("AUTHORED system",USER);
        assertEquals(2,f.bodies.size());assertTrue(JsonUtils.parse(f.bodies.get(0)).path("stream").asBoolean());
        assertFalse(JsonUtils.parse(f.bodies.get(1)).path("stream").asBoolean());
        assertTrue(JsonUtils.parse(f.bodies.get(1)).path("messages").get(1).path("content").asText().contains("full complete answer"));
        assertEquals(2,attempts(f.directory).size());
    }
    @Test void lateGlobalBasisRejectionCannotResubmitAnAlreadyPublishedPrefix() throws Exception{
        Fixture f=new Fixture(Files.createDirectory(root.resolve("late-basis-prefix")),true,true);
        ObjectNode first=(ObjectNode)JsonUtils.parse(f.contract());first.put("basisScope","reference_only");f.answers.add(JsonUtils.write(first));f.answers.add(f.contract());
        com.consense.common.BizException rejected=assertThrows(com.consense.common.BizException.class,()->f.complete("AUTHORED system",USER));
        assertEquals(4205,rejected.getCode());assertTrue(rejected.getMessage().contains("application-owned accepted section ID"));assertFalse(f.sections.isEmpty(),"The earlier validated prefix remains visible; no completed answer may be returned");
        assertEquals(2,f.bodies.size(),"Two admitted attempts use exactly two answers and no preflight");assertEquals(2,attempts(f.directory).size());assertTrue(f.answers.isEmpty());
        assertTrue(JsonUtils.parse(f.bodies.get(1)).path("stream").asBoolean(),"A published prefix retains its existing streaming repair and immutable-ID guard");
    }

    @Test void knownCredentialReflectionIsRejectedBeforeAnyEntityOrPaidDispatch() throws Exception {
        for(boolean inSystem:Arrays.asList(false,true)){
            Fixture f=new Fixture(Files.createDirectory(root.resolve("secret-"+inSystem)),true,false);
            AdviceResponsesTransport.RequestFailure rejected=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->f.complete(inSystem?SECRET:"safe",inSystem?"safe":USER+SECRET));
            assertEquals("credential_reflection_refused_no_raw_body_saved",rejected.getReason());assertTrue(attempts(f.directory).isEmpty());assertTrue(f.bodies.isEmpty());
            verify(f.ledger,never()).reserve(anyString(),anyString(),anyString(),anyString(),anyBoolean());verifyNoInteractions(f.http);
        }
    }

    @Test void invalidOwnedRootsAreRejectedBeforeAnyPaidDispatch() throws Exception {
        Path file=root.resolve("ordinary-file");Files.write(file,new byte[]{1},StandardOpenOption.CREATE_NEW);
        for(String selected:Arrays.asList("relative-observation-root",root.resolve("absent-root").toString(),file.toString())){
            Fixture f=new Fixture(Files.createTempDirectory(root,"invalid-"),true,false);f.settings.setWireObservationRoot(selected);
            AdviceResponsesTransport.RequestFailure rejected=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->f.complete("safe",USER));
            assertEquals("owned_absolute_observation_root_required",rejected.getReason());assertTrue(f.bodies.isEmpty());assertTrue(attempts(f.directory).isEmpty());verifyNoInteractions(f.http);
        }
    }

    @Test void existingCreateOnceStorageAndImmutablePreparedInputCopyRemainEnforced() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("create-once")),true,true);
        QwenChatUsagePreflight.PreparedRequest request=f.operation.prepareChat(Arrays.asList(LlmClient.ChatTurn.system("safe"),LlmClient.ChatTurn.user(USER)),JsonUtils.mapper().createObjectNode(),SOURCE,true);
        String original=JsonUtils.write(request.getInput());VettingInputBudget.Input returned=request.getInput();returned.getMessages().set(1,LlmClient.ChatTurn.user("changed copy"));
        AdviceWireObservation.Attempt attempt=ReflectionTestUtils.invokeMethod(new AdviceWireObservation(f.directory),"beginQwen",request,1,SECRET);assertNotNull(attempt);
        assertEquals(original,text(attempt.directory,"bound-input.utf8.json"));assertEquals(1,json(attempt.directory,"request-receipt.json").path("attempt").asInt());
        byte[] before=Files.readAllBytes(attempt.directory.resolve("bound-input.utf8.json"));
        AdviceResponsesTransport.RequestFailure collision=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->attempt.entity("bound-input.utf8.json","changed".getBytes(StandardCharsets.UTF_8)));
        assertEquals("observation_entity_seal_failed",collision.getReason());assertArrayEquals(before,Files.readAllBytes(attempt.directory.resolve("bound-input.utf8.json")));assertEquals(original,JsonUtils.write(request.getInput()));verifyNoInteractions(f.http,f.ledger);
    }

    @Test void actualCopiedAdaptersRejectChangedLiveCredentialsBeforeAnyRawWriteOrPaidDispatch() throws Exception {
        for(String profile:Arrays.asList("qwen","qwen-flash")) {
            Fixture mismatch=new Fixture(Files.createDirectory(root.resolve("copied-mismatch-"+profile)),true,false,profile,true,false);
            LlmProfiles.configuration(mismatch.props,profile).setApiKey("AUTHORED-LIVE-CREDENTIAL-B");
            IllegalStateException rejected=assertThrows(IllegalStateException.class,()->mismatch.complete("safe",USER+SECRET),
                    "Actual copied adapter credential must match before raw observation or paid dispatch");
            assertEquals("qwen_observation_credential_differs",rejected.getMessage());assertTrue(attempts(mismatch.directory).isEmpty());assertTrue(mismatch.bodies.isEmpty());
            verifyNoInteractions(mismatch.http);verify(mismatch.ledger,never()).reserve(anyString(),anyString(),anyString(),anyString(),anyBoolean());
            Fixture same=new Fixture(Files.createDirectory(root.resolve("copied-same-"+profile)),true,false,profile,true,true);
            same.complete("safe",USER);assertEquals(1,same.bodies.size());assertEquals(1,attempts(same.directory).size());
            assertEquals(Collections.singletonList("Bearer "+SECRET),same.authorizations);
            Fixture disabled=new Fixture(Files.createDirectory(root.resolve("copied-disabled-"+profile)),false,false,profile,true,false);
            LlmProfiles.configuration(disabled.props,profile).setApiKey("AUTHORED-LIVE-CREDENTIAL-B");
            disabled.complete("safe",USER+SECRET);assertEquals(1,disabled.bodies.size());assertTrue(attempts(disabled.directory).isEmpty());
            assertEquals(Collections.singletonList("Bearer "+SECRET),disabled.authorizations);
        }
    }

    private List<Path> attempts(Path path)throws Exception{try(java.util.stream.Stream<Path> stream=Files.list(path)){return stream.filter(Files::isDirectory).sorted().collect(Collectors.toList());}}
    private String text(Path directory,String file)throws Exception{return Files.readString(directory.resolve(file),StandardCharsets.UTF_8);}
    private JsonNode json(Path directory,String file)throws Exception{return JsonUtils.parse(text(directory,file));}
    private VettingInputBudget.Input boundInput(Path directory)throws Exception{
        JsonNode saved=json(directory,"bound-input.utf8.json");List<LlmClient.ChatTurn> turns=new ArrayList<>();
        for(JsonNode message:saved.path("messages"))turns.add(new LlmClient.ChatTurn(message.path("role").asText(),message.path("content").asText()));
        VettingInputBudget.Input input=VettingInputBudget.chatInput(saved.path("provider").asText(),saved.path("model").asText(),saved.path("providerConfigurationSha256").asText(),turns,saved.path("outputReserveTokens").asInt(),saved.path("sourceSnapshotSha256").asText());
        input.setSchema(saved.path("schema").deepCopy());VettingInputBudget.bindQwenWire(input,JsonUtils.mapper().convertValue(saved.path("qwenWire"),QwenChatUsagePreflight.WireIdentity.class));
        assertEquals(saved.path("serializedInputSha256").asText(),input.getSerializedInputSha256());return input;
    }

    private final class Fixture {
        final Path directory;final boolean enabled,streaming,requireSavedBeforeDispatch;final ConsenseProperties props=new ConsenseProperties();final AdviceModelProperties settings=new AdviceModelProperties();
        final HttpSupport http;final QwenGenerationLedger ledger=mock(QwenGenerationLedger.class);final List<String> bodies=new ArrayList<>(),authorizations=new ArrayList<>();final Queue<String> answers=new ArrayDeque<>();
        final QwenChatUsagePreflight.Operation operation;final AdviceService service;final Object publication;final List<String> sections=new ArrayList<>();
        Fixture(Path path,boolean observe,boolean stream)throws Exception{this(path,observe,stream,"qwen",false,true);}
        Fixture(Path path,boolean observe,boolean stream,String profile,boolean productionCopy,boolean requireSaved)throws Exception{
            this(path,observe,stream,profile,productionCopy,requireSaved,null);
        }
        Fixture(Path path,boolean observe,boolean stream,String profile,boolean productionCopy,boolean requireSaved,String endpoint)throws Exception{
            http=endpoint==null?mock(HttpSupport.class):new HttpSupport();
            if(endpoint!=null)LlmProfiles.configuration(props,profile).setBaseUrl(endpoint);
            directory=path.toAbsolutePath();enabled=observe;streaming=stream;requireSavedBeforeDispatch=requireSaved;if(observe)settings.setWireObservationRoot(directory.toString());
            LlmProfiles.configuration(props,profile).setEnabled(true);LlmProfiles.configuration(props,profile).setApiKey(SECRET);
            LlmClient adapter=productionCopy?("qwen".equals(profile)?new AiConfig().qwenChatClient(props,http):new AiConfig().qwenFlashChatClient(props,http)):new QwenChatClient(LlmProfiles.configuration(props,profile),http);
            LlmOperation selected=new LlmProfiles(props,mock(LlmClient.class),mock(LlmClient.class),"qwen".equals(profile)?adapter:mock(LlmClient.class),"qwen-flash".equals(profile)?adapter:mock(LlmClient.class)).resolve(profile);
            operation=new QwenChatUsagePreflight(props,ledger).capture(selected,"advice",0);service=new AdviceService(null,null,null,null,props,null,null,null,null,null,null);ReflectionTestUtils.setField(service,"adviceSettings",settings);
            Class<?> context=Class.forName(AdviceService.class.getName()+"$QuestionContext");Constructor<?> qc=context.getDeclaredConstructor(String.class,List.class);qc.setAccessible(true);
            Class<?> type=Class.forName(AdviceService.class.getName()+"$Publication");Constructor<?> pc=type.getDeclaredConstructor(AdviceService.class,List.class,boolean.class,AdviceProgress.class,Runnable.class,context);pc.setAccessible(true);
            AdviceProgress progress=new AdviceProgress(){public boolean streaming(){return streaming;}public void section(String content,List<String> aliases,List<CitationVO> sources){sections.add(content);}};
            CitationVO source=new CitationVO("Authored source",null,"body/authored","Keep a record.",1,"[1]","1",SOURCE,"tender",true);
            publication=pc.newInstance(service,Collections.singletonList(source),false,progress,(Runnable)()->{},qc.newInstance("Explain the uploaded clause",Collections.emptyList()));
            if(endpoint==null) {
            doAnswer(call->{String body=call.getArgument(1);authorizations.add(call.getArgument(3));beforeDispatch(body);return envelope(JsonUtils.parse(body).path("max_tokens").asInt()==1?"{":nextAnswer());})
                    .when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
            doAnswer(call->{String body=call.getArgument(1);authorizations.add(call.getArgument(3));beforeDispatch(body);Consumer<String> events=call.getArgument(6);String event="{\"model\":\"qwen3.8-27b\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":"+JsonUtils.write(nextAnswer())+"},\"finish_reason\":\"stop\"}]}";events.accept(event);events.accept("[DONE]");return "data: "+event+"\n\ndata: [DONE]\n\n";})
                    .when(http).postJsonStream(anyString(),anyString(),anyLong(),anyString(),anyInt(),eq(true),any(),any());
            }
        }
        String nextAnswer(){return answers.isEmpty()?contract():answers.remove();}
        void beforeDispatch(String body)throws Exception{if(enabled&&requireSavedBeforeDispatch){List<Path> saved=attempts(directory);assertFalse(saved.isEmpty(),"All sealed entities must be saved before the first paid dispatch");for(Path attempt:saved)for(String name:Arrays.asList("bound-input.utf8.json","preflight-request.utf8.json","generation-request.utf8.json","request-receipt.json"))assertTrue(Files.isRegularFile(attempt.resolve(name)));}bodies.add(body);}
        void complete(String system,String user){try(QwenChatUsagePreflight.Operation.ActionScope action=operation.openAction("test:advice-wire",()->{})){AdviceService.AnswerRecord answer=ReflectionTestUtils.invokeMethod(service,"completeAdvice",system,user,null,null,operation,SOURCE,(Runnable)()->{},publication,"Explain the uploaded clause");assertNotNull(answer);}}
        String contract(){return AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported wording [1].\",\"citations\":[\"[1]\"]}");}
        String envelope(String content){ObjectNode root=JsonUtils.mapper().createObjectNode();root.put("object","chat.completion");root.put("model",operation.getIdentity().getModel());ObjectNode choice=root.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason","stop");choice.putObject("message").put("role","assistant").put("content",content);root.putObject("usage").put("prompt_tokens",42).put("completion_tokens",1).put("total_tokens",43);return JsonUtils.write(root);}
    }
}
