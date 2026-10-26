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
        verify(f.ledger).reserve(anyString(),anyString(),eq("draft-preflight-0"),anyString(),eq(false));
        verify(f.ledger).reserve(anyString(),anyString(),eq("draft-answer-0"),anyString(),eq(true));
        verify(f.ledger).reserve(anyString(),anyString(),eq("supplement-preflight-0"),anyString(),eq(false));
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

    @Test void actualSealedEntitiesExistBeforeDispatchAndExactlyMatchBothLedgerRequestHashes() throws Exception {
        for(boolean streaming:Arrays.asList(false,true)){
            Fixture f=new Fixture(Files.createDirectory(root.resolve("enabled-"+streaming)),true,streaming);f.complete("AUTHORED system",USER);
            assertEquals(2,f.bodies.size());Path attempt=attempts(f.directory).get(0);JsonNode receipt=json(attempt,"request-receipt.json");
            VettingInputBudget.Input input=boundInput(attempt);assertTrue(VettingInputBudget.validBinding(input));
            assertEquals(USER,input.getMessages().get(1).getContent());assertEquals(USER,json(attempt,"generation-request.utf8.json").path("messages").get(1).path("content").asText());
            assertEquals(f.bodies,Arrays.asList(text(attempt,"preflight-request.utf8.json"),text(attempt,"generation-request.utf8.json")));
            assertEquals(1,json(attempt,"preflight-request.utf8.json").path("max_tokens").asInt());assertFalse(json(attempt,"preflight-request.utf8.json").path("stream").asBoolean());
            assertEquals(streaming,json(attempt,"generation-request.utf8.json").path("stream").asBoolean());assertEquals(16384,json(attempt,"generation-request.utf8.json").path("max_tokens").asInt());
            ArgumentCaptor<Map> receipts=ArgumentCaptor.forClass(Map.class);verify(f.ledger,times(2)).complete(any(),receipts.capture());
            assertEquals(receipts.getAllValues().get(0).get("requestSha256"),receipt.path("preflightRequest").path("sha256").asText());
            assertEquals(receipts.getAllValues().get(1).get("requestSha256"),receipt.path("generationRequest").path("sha256").asText());
            ArgumentCaptor<String> bindings=ArgumentCaptor.forClass(String.class);verify(f.ledger,times(2)).reserve(anyString(),anyString(),anyString(),bindings.capture(),anyBoolean());
            assertEquals(Arrays.asList(input.getSerializedInputSha256(),input.getSerializedInputSha256()),bindings.getAllValues());assertEquals(input.getSerializedInputSha256(),receipt.path("serializedInputSha256").asText());
            for(String[] entity:new String[][]{{"boundInput","bound-input.utf8.json"},{"preflightRequest","preflight-request.utf8.json"},{"generationRequest","generation-request.utf8.json"}}){byte[] bytes=Files.readAllBytes(attempt.resolve(entity[1]));assertEquals(AdviceWireObservation.sha(bytes),receipt.path(entity[0]).path("sha256").asText());assertEquals(bytes.length,receipt.path(entity[0]).path("utf8Bytes").asInt());}
            assertEquals(SOURCE,receipt.path("sourceSnapshotSha256").asText());assertEquals(0,receipt.path("attempt").asInt());assertFalse(receipt.path("dispatchObservedByThisRecorder").asBoolean());assertTrue(receipt.path("httpEntityScope").asText().contains("TCP/TLS/framing bytes are unobserved"));
        }
    }

    @Test void disabledObservationWritesNothingAndLeavesTheSameQwenWireEntities() throws Exception {
        Fixture enabled=new Fixture(Files.createDirectory(root.resolve("enabled")),true,false),disabled=new Fixture(Files.createDirectory(root.resolve("disabled")),false,false);
        enabled.complete("AUTHORED system",USER);disabled.complete("AUTHORED system",USER);
        assertEquals(enabled.bodies,disabled.bodies);assertTrue(attempts(disabled.directory).isEmpty());assertNull(disabled.settings.getWireObservationRoot());
    }

    @Test void streamedAnswerRepairUsesACompleteNonstreamingQwenRequest() throws Exception {
        Fixture f=new Fixture(Files.createDirectory(root.resolve("full-repair")),true,true);
        ObjectNode first=(ObjectNode)JsonUtils.parse(f.contract());first.put("basisScope","reference_only");
        f.answers.add(JsonUtils.write(first));f.answers.add(f.contract());f.complete("AUTHORED system",USER);
        assertEquals(4,f.bodies.size());assertTrue(JsonUtils.parse(f.bodies.get(1)).path("stream").asBoolean());
        assertFalse(JsonUtils.parse(f.bodies.get(3)).path("stream").asBoolean());
        assertTrue(JsonUtils.parse(f.bodies.get(3)).path("messages").get(1).path("content").asText().contains("full complete answer"));
        assertEquals(2,attempts(f.directory).size());
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
            same.complete("safe",USER);assertEquals(2,same.bodies.size());assertEquals(1,attempts(same.directory).size());
            assertEquals(Arrays.asList("Bearer "+SECRET,"Bearer "+SECRET),same.authorizations);
            Fixture disabled=new Fixture(Files.createDirectory(root.resolve("copied-disabled-"+profile)),false,false,profile,true,false);
            LlmProfiles.configuration(disabled.props,profile).setApiKey("AUTHORED-LIVE-CREDENTIAL-B");
            disabled.complete("safe",USER+SECRET);assertEquals(2,disabled.bodies.size());assertTrue(attempts(disabled.directory).isEmpty());
            assertEquals(Arrays.asList("Bearer "+SECRET,"Bearer "+SECRET),disabled.authorizations);
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
        final HttpSupport http=mock(HttpSupport.class);final QwenGenerationLedger ledger=mock(QwenGenerationLedger.class);final List<String> bodies=new ArrayList<>(),authorizations=new ArrayList<>();final Queue<String> answers=new ArrayDeque<>();
        final QwenChatUsagePreflight.Operation operation;final AdviceService service;final Object publication;final List<String> sections=new ArrayList<>();
        Fixture(Path path,boolean observe,boolean stream)throws Exception{this(path,observe,stream,"qwen",false,true);}
        Fixture(Path path,boolean observe,boolean stream,String profile,boolean productionCopy,boolean requireSaved)throws Exception{
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
            doAnswer(call->{String body=call.getArgument(1);authorizations.add(call.getArgument(3));beforeDispatch(body);return envelope(JsonUtils.parse(body).path("max_tokens").asInt()==1?"{":nextAnswer());})
                    .when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
            doAnswer(call->{String body=call.getArgument(1);authorizations.add(call.getArgument(3));beforeDispatch(body);Consumer<String> events=call.getArgument(6);String event="{\"model\":\"qwen3.8-27b\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":"+JsonUtils.write(nextAnswer())+"},\"finish_reason\":\"stop\"}]}";events.accept(event);events.accept("[DONE]");return "data: "+event+"\n\ndata: [DONE]\n\n";})
                    .when(http).postJsonStream(anyString(),anyString(),anyLong(),anyString(),anyInt(),eq(true),any(),any());
        }
        String nextAnswer(){return answers.isEmpty()?contract():answers.remove();}
        void beforeDispatch(String body)throws Exception{if(enabled&&requireSavedBeforeDispatch){List<Path> saved=attempts(directory);assertFalse(saved.isEmpty(),"All sealed entities must be saved before the first paid dispatch");for(Path attempt:saved)for(String name:Arrays.asList("bound-input.utf8.json","preflight-request.utf8.json","generation-request.utf8.json","request-receipt.json"))assertTrue(Files.isRegularFile(attempt.resolve(name)));}bodies.add(body);}
        void complete(String system,String user){try(QwenChatUsagePreflight.Operation.ActionScope action=operation.openAction("test:advice-wire",()->{})){AdviceService.AnswerRecord answer=ReflectionTestUtils.invokeMethod(service,"completeAdvice",system,user,null,null,operation,SOURCE,(Runnable)()->{},publication,"Explain the uploaded clause");assertNotNull(answer);}}
        String contract(){return AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported wording [1].\",\"citations\":[\"[1]\"]}");}
        String envelope(String content){ObjectNode root=JsonUtils.mapper().createObjectNode();root.put("object","chat.completion");root.put("model",operation.getIdentity().getModel());ObjectNode choice=root.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason","stop");choice.putObject("message").put("role","assistant").put("content",content);root.putObject("usage").put("prompt_tokens",42).put("completion_tokens",1).put("total_tokens",43);return JsonUtils.write(root);}
    }
}
