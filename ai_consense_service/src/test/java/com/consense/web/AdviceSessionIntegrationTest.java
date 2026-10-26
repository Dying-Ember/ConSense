package com.consense.web;

import com.consense.ai.IncompleteModelResponseException;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.repository.ChatMessageRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.StorageService;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.vetting.VettingInputBudget;
import com.consense.service.vetting.VettingTokenBudgetCliObserver;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public HTTP plus isolated SQL persistence. Model and token observations are fixtures, never live acceptance. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.ocr.enabled=false","consense.vector.provider=memory","consense.llm.enabled=true","consense.llm.chat-model=session-test-chat",
        "consense.llm.num-ctx=262144",
        "consense.llm.structured-thinking=false",
        "consense.advice.history-max-exchanges=2","consense.advice.history-max-messages=4","consense.advice.history-max-tokens=8192",
        "consense.minimax-cn.enabled=true","consense.minimax-cn.base-url=https://session-test.invalid",
        "consense.minimax-cn.api-key=session-test-only"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceSessionIntegrationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:advice_sessions_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("consense.storage-root",()->java.nio.file.Paths.get("target","advice-session-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired SourceDocumentRepository sources;
    @Autowired ChatMessageRepository messages;
    @Autowired ConsenseProperties properties;
    @Autowired com.consense.service.advice.AdviceModelProperties adviceSettings;
    @Autowired StorageService storage;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;
    @MockBean VettingTokenBudgetCliObserver counter;
    private final List<String> prompts=Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong overrideTokens=new AtomicLong();
    private String answer="{\"grounded\":true,\"answer\":\"TEST ONLY supported session answer\",\"citations\":[\"[1]\"]}";

    @BeforeEach void adapters() {
        prompts.clear();overrideTokens.set(0);
        answer="{\"grounded\":true,\"answer\":\"TEST ONLY supported session answer\",\"citations\":[\"[1]\"]}";
        when(local.available()).thenReturn(true);when(miniMax.available()).thenReturn(true);
        when(local.chatModel()).thenReturn("session-test-chat");when(local.embedModel()).thenReturn("session-test-embedding");
        doAnswer(call->{List<float[]> out=new ArrayList<>();for(Object ignored:call.<List<?>>getArgument(0))out.add(new float[]{1,0});return out;}).when(local).embed(anyList());
        doAnswer(call->answer).when(local).chat(anyList());
        doAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return com.consense.service.advice.AdviceBudgetFixture.contract(answer);}).when(local).chatStructured(anyList(),any());
        doAnswer(call->{prompts.add(JsonUtils.write(call.getArgument(0)));return com.consense.service.advice.AdviceBudgetFixture.contract(answer);}).when(miniMax).chatStructured(anyList(),any());
        doAnswer(call->observation(call.getArgument(0))).when(counter).observe(any());
    }

    @Test void sessionsPersistProjectAndImmutableScopeAndRejectOtherProjectsBeforeRetrieval() throws Exception {
        String project=project(),other=project(),session=session(project,"contract","project_contract");
        JsonNode listed=data(get("/api/advice/{id}/sessions",project));assertEquals(1,listed.size());
        assertEquals(project,listed.get(0).path("projectId").asText());assertEquals("contract",listed.get(0).path("scope").asText());assertFalse(listed.get(0).path("createdAt").asText().isEmpty());
        assertTrue(data(get("/api/advice/{id}/sessions",other)).isEmpty());
        mvc.perform(get("/api/advice/{id}/messages",other).param("sessionId",session)).andExpect(jsonPath("$.code").value(4202));
        mvc.perform(delete("/api/advice/{id}/messages",other).param("sessionId",session)).andExpect(jsonPath("$.code").value(4202));
        mvc.perform(askRequest(project,session,"fullset","project_contract","TEST ONLY mismatch")).andExpect(jsonPath("$.code").value(4202));
        mvc.perform(askRequest(project,session,"contract","reference_only","TEST ONLY mismatch")).andExpect(jsonPath("$.code").value(4202));
        verify(local,never()).chatStructured(anyList(),any());verifyNoInteractions(counter);
    }

    @Test void reloadKeepsPerAnswerSourcesAndClearingOneSessionDoesNotDeleteAnotherOrLegacy() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL SOURCE TEST ONLY");rebuild(project);
        String first=session(project,"contract","project_contract"),second=session(project,"contract","project_contract");
        JsonNode response=ask(project,first,"FIRST QUESTION TEST ONLY");ask(project,second,"SECOND QUESTION TEST ONLY");
        data(post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"LEGACY QUESTION TEST ONLY\",\"scope\":\"contract\"}"));
        original.setTextContent("NEW SOURCE TEXT TEST ONLY");sources.saveAndFlush(original);
        JsonNode history=history(project,first);assertEquals(2,history.size());assertEquals(first,history.get(1).path("sessionId").asText());
        assertEquals(response.path("sources"),history.get(1).path("sources"));assertTrue(history.get(1).path("sources").toString().contains("ORIGINAL SOURCE"));
        assertEquals(response.path("modelIdentity"),history.get(1).path("modelIdentity"));
        data(delete("/api/advice/{id}/messages",project).param("sessionId",first));assertTrue(history(project,first).isEmpty());assertEquals(2,history(project,second).size());
        assertEquals(2,data(get("/api/advice/{id}/messages",project)).size());
        data(delete("/api/advice/{id}/messages",project));assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());assertEquals(2,history(project,second).size());
    }

    @Test void historyContainsOnlyWholeRecentExchangesFromTheSameSession() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);
        String first=session(project,"contract","project_contract"),other=session(project,"contract","project_contract");
        ask(project,other,"OTHER SESSION SECRET TEST ONLY");
        ask(project,first,"OLD FIRST EXCHANGE TEST ONLY");ask(project,first,"RECENT SECOND EXCHANGE TEST ONLY");ask(project,first,"RECENT THIRD EXCHANGE TEST ONLY");
        prompts.clear();ask(project,first,"FOLLOW UP FOURTH TEST ONLY");String prompt=prompts.get(0);
        assertTrue(prompt.contains("RECENT SECOND EXCHANGE"));assertTrue(prompt.contains("RECENT THIRD EXCHANGE"));
        assertFalse(prompt.contains("OLD FIRST EXCHANGE"));assertFalse(prompt.contains("OTHER SESSION SECRET"));assertTrue(prompt.contains("prior answers are not contractual evidence"));
    }

    @Test void aSourceOrModelChangeExcludesPreviousHistoryWithoutRelinkingOldCitations() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL SOURCE TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        JsonNode old=ask(project,session,"OLD HISTORY SECRET TEST ONLY");original.setTextContent("REPLACED SOURCE TEST ONLY");sources.saveAndFlush(original);rebuild(project);
        prompts.clear();ask(project,session,"CURRENT SOURCE QUESTION TEST ONLY");assertFalse(prompts.get(0).contains("OLD HISTORY SECRET"));
        assertEquals(old.path("sources"),history(project,session).get(1).path("sources"));
        prompts.clear();data(askRequest(project,session,"contract","project_contract","NEW MODEL QUESTION TEST ONLY").header("X-ConSense-Llm-Profile","minimax-cn"));
        assertFalse(prompts.get(0).contains("CURRENT SOURCE QUESTION"));
    }

    @Test void clarificationTaskFollowsUnchangedRecentHistoryAndTheCompletePromptIsCounted() throws Exception {
        String project=project();source(project,"AUTHORED complete current source TEST ONLY\nUnicode source qualifier: \u6761\u6b3e \"A\".");rebuild(project);
        String session=session(project,"contract","project_contract");
        String original="ORIGINAL CURRENT QUESTION TEST ONLY",priorAssumption="EARLIER USER ASSUMPTION TEST ONLY",currentAssumption="CURRENT USER ASSUMPTION TEST ONLY";
        answer="{\"grounded\":false,\"answer\":\"EARLIER SUPPORTED ANALYSIS TEST ONLY [1]\",\"citations\":[\"[1]\"],\"clarifications\":[\"What contract stage?\"]}";
        long first=ask(project,session,original).path("messageId").asLong();
        Map<String,Object> reply=new LinkedHashMap<>();reply.put("scope","contract");reply.put("sessionId",session);reply.put("replyToMessageId",first);reply.put("question",priorAssumption);
        long parent=data(post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(reply))).path("messageId").asLong();
        answer="{\"grounded\":false,\"answer\":\"LATER ERRONEOUS ASSISTANT CONTEXT TEST ONLY [1]\",\"citations\":[\"[1]\"],\"clarifications\":[\"What contract stage?\"]}";
        long sibling=ask(project,session,"LATER SIBLING QUESTION TEST ONLY").path("messageId").asLong();
        JsonNode before=history(project,session);assertEquals(6,before.size());assertTrue(sibling>parent);
        reply.put("replyToMessageId",parent);reply.put("question",currentAssumption);String body=JsonUtils.write(reply);
        List<VettingInputBudget.Input> counted=new ArrayList<>();List<List<LlmClient.ChatTurn>> dispatched=new ArrayList<>();
        doAnswer(call->{VettingInputBudget.Input input=call.getArgument(0);counted.add(input);return observation(input);}).when(counter).observe(any());
        int originalLimit=adviceSettings.getHistoryMaxExchanges();
        try {
            adviceSettings.setHistoryMaxExchanges(0);overrideTokens.set(262144);clearInvocations(local);
            mvc.perform(post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(4205));
            assertEquals(1,counted.size());verify(local,never()).chatStructured(anyList(),any());assertEquals(before,history(project,session));
            String currentTask=counted.get(0).getMessages().get(1).getContent();
            assertTrue(currentTask.contains(original));assertTrue(currentTask.contains(priorAssumption));assertTrue(currentTask.contains(currentAssumption));
            assertTrue(currentTask.contains("AUTHORED complete current source TEST ONLY"));assertFalse(currentTask.contains("LATER ERRONEOUS ASSISTANT CONTEXT TEST ONLY"));
            adviceSettings.setHistoryMaxExchanges(originalLimit);overrideTokens.set(0);counted.clear();
            answer="{\"grounded\":true,\"answer\":\"AUTHORED final supported analysis TEST ONLY [1]\",\"citations\":[\"[1]\"]}";
            doAnswer(call->{dispatched.add(call.getArgument(0));return com.consense.service.advice.AdviceBudgetFixture.contract(answer);}).when(local).chatStructured(anyList(),any());
            assertTrue(data(post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content(body)).path("grounded").asBoolean());
            assertEquals(1,counted.size(),"Only the final complete prompt is counted");assertEquals(1,dispatched.size());
            String fullPrompt=dispatched.get(0).get(1).getContent();
            String historyMaterial=fullPrompt.substring(0,fullPrompt.length()-currentTask.length());
            assertTrue(historyMaterial.startsWith("\n\nConversation context only"));
            JsonNode actualHistory=JsonUtils.parse(historyMaterial.substring(historyMaterial.indexOf('\n',2)+1));
            List<LlmClient.ChatTurn> expected=new ArrayList<>();
            for(long assistantId:new long[]{parent,sibling}) {
                com.consense.domain.ChatMessage assistant=messages.findById(assistantId).get(),user=messages.findById(assistant.getQuestionMessageId()).get();
                expected.add(LlmClient.ChatTurn.user(user.getContent()));expected.add(LlmClient.ChatTurn.assistant(assistant.getContent()));
            }
            assertEquals(JsonUtils.parse(JsonUtils.write(expected)),actualHistory,"Keep the same whole recent exchanges, including the sibling after the clarification target");
            assertEquals(historyMaterial+currentTask,fullPrompt,"The exact current source/task/background block follows the exact counted history block");
            assertTrue(historyMaterial.contains("Answer only from the current evidence IDs in the current task."));
            assertEquals(JsonUtils.write(counted.get(0).getMessages()),JsonUtils.write(dispatched.get(0)),"Count the complete input actually dispatched");
            JsonNode after=history(project,session);assertEquals(8,after.size());
            for(int index=0;index<before.size();index++)assertEquals(before.get(index),after.get(index),"Historical messages and citations stay unchanged");
        } finally {adviceSettings.setHistoryMaxExchanges(originalLimit);overrideTokens.set(0);}
    }

    @Test void clearDuringModelGenerationRejectsLatePublicationAndLeavesHistoryEmpty() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        when(local.chatStructured(anyList(),any())).thenAnswer(call->{data(delete("/api/advice/{id}/messages",project).param("sessionId",session));return com.consense.service.advice.AdviceBudgetFixture.contract(answer);});
        mvc.perform(askRequest(project,session,"contract","project_contract","LATE ANSWER TEST ONLY")).andExpect(jsonPath("$.code").value(4202));
        assertTrue(history(project,session).isEmpty());
    }

    @Test void concurrentQuestionsCannotPublishAgainstTheSameSessionRevision() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        when(local.chatStructured(anyList(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return com.consense.service.advice.AdviceBudgetFixture.contract(answer);});
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call=()->JsonUtils.parse(mvc.perform(askRequest(project,session,"contract","project_contract","CONCURRENT QUESTION TEST ONLY")).andReturn().getResponse().getContentAsString()).path("code").asInt();
            Future<Integer> first=executor.submit(call),second=executor.submit(call);assertTrue(entered.await(10,TimeUnit.SECONDS));release.countDown();
            List<Integer> codes=Arrays.asList(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(Arrays.asList(0,4202),codes);
            assertEquals(2,history(project,session).size());
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void changedSourcesDuringGenerationRejectTheNewExchange() throws Exception {
        String project=project();SourceDocument source=source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        when(local.chatStructured(anyList(),any())).thenAnswer(call->{source.setTextContent("SOURCE CHANGED WHILE CALLING TEST ONLY");sources.saveAndFlush(source);return com.consense.service.advice.AdviceBudgetFixture.contract(answer);});
        mvc.perform(askRequest(project,session,"contract","project_contract","SOURCE CHANGE TEST ONLY")).andExpect(jsonPath("$.code").value(4204));assertTrue(history(project,session).isEmpty());
    }

    @Test void unknownOrExhaustedBudgetsNeverDispatchAndIncompleteOutputsNeverPublish() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        doReturn(null).when(counter).observe(any());
        mvc.perform(askRequest(project,session,"contract","project_contract","UNKNOWN BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205)).andExpect(jsonPath("$.message").exists());
        verify(local,never()).chatStructured(anyList(),any());assertTrue(history(project,session).isEmpty());
        adapters();overrideTokens.set(262144);
        mvc.perform(askRequest(project,session,"contract","project_contract","OVER BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205));verify(local,never()).chatStructured(anyList(),any());
        overrideTokens.set(0);when(local.chatStructured(anyList(),any())).thenThrow(new IncompleteModelResponseException("TEST ONLY incomplete",null,null,IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,null));
        mvc.perform(askRequest(project,session,"contract","project_contract","OUTPUT INCOMPLETE TEST ONLY")).andExpect(jsonPath("$.code").value(4205));assertTrue(history(project,session).isEmpty());
    }

    @Test void clarificationParentsMustRetainSessionScopeAndSourceVersion() throws Exception {
        String project=project();SourceDocument original=source(project,"CURRENT BASIS TEST ONLY");rebuild(project);
        String session=session(project,"contract","project_contract"),other=session(project,"contract","project_contract");
        answer="{\"grounded\":false,\"clarifications\":[\"What contract stage?\"]}";JsonNode pending=ask(project,session,"ORIGINAL CLARIFICATION QUESTION TEST ONLY");
        long parent=pending.path("messageId").asLong();
        mvc.perform(reply(project,other,parent)).andExpect(jsonPath("$.code").value(4202));
        answer="{\"grounded\":true,\"answer\":\"TEST ONLY supported answer\",\"citations\":[\"[1]\"]}";
        prompts.clear();JsonNode response=data(reply(project,session,parent));assertTrue(response.path("grounded").asBoolean());assertTrue(prompts.get(0).contains("user-supplied-background"));
        assertFalse(response.path("sources").toString().contains("USER ASSUMPTION"));
        original.setTextContent("NEW REVISION TEST ONLY");sources.saveAndFlush(original);rebuild(project);
        mvc.perform(reply(project,session,parent)).andExpect(jsonPath("$.code").value(4204));
    }

    @Test void conservativeHistoryByteCapDropsWholeOldExchangesAndNeverCutsAStoredAnswer() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        answer="{\"grounded\":true,\"answer\":\""+String.join("",Collections.nCopies(9000,"X"))+" END OF COMPLETE ANSWER TEST ONLY\",\"citations\":[\"[1]\"]}";
        JsonNode old=ask(project,session,"LONG OLD EXCHANGE TEST ONLY");
        answer="{\"grounded\":true,\"answer\":\"TEST ONLY fresh answer\",\"citations\":[\"[1]\"]}";prompts.clear();ask(project,session,"NEXT QUESTION TEST ONLY");
        assertFalse(prompts.get(0).contains("LONG OLD EXCHANGE"));assertTrue(history(project,session).get(1).path("content").path("en").asText().endsWith("END OF COMPLETE ANSWER TEST ONLY"));
        assertEquals(old.path("content"),history(project,session).get(1).path("content"));
    }

    @Test void retryIsCountedWithTheExactMessagesSubmittedToTheCapturedClient() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        List<List<LlmClient.ChatTurn>> dispatched=new ArrayList<>();List<VettingInputBudget.Input> counted=new ArrayList<>();
        doAnswer(call->{VettingInputBudget.Input input=call.getArgument(0);counted.add(input);return observation(input);}).when(counter).observe(any());
        doAnswer(call->{dispatched.add(call.getArgument(0));return dispatched.size()==1?"TEST ONLY invalid JSON":com.consense.service.advice.AdviceBudgetFixture.contract(answer);}).when(local).chatStructured(anyList(),any());
        assertTrue(ask(project,session,"RETRY QUESTION TEST ONLY").path("grounded").asBoolean());assertEquals(2,counted.size());assertEquals(2,dispatched.size());
        for(int index=0;index<2;index++)assertEquals(JsonUtils.write(counted.get(index).getMessages()),JsonUtils.write(dispatched.get(index)));
        assertFalse(dispatched.get(0).get(1).getContent().equals(dispatched.get(1).get(1).getContent()));
        assertFalse(counted.get(0).getSerializedInputSha256().equals(counted.get(1).getSerializedInputSha256()));assertEquals(2,history(project,session).size());
    }
    @Test void invalidRepairNeverCreatesACompletedHistoryExchange() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        com.fasterxml.jackson.databind.node.ObjectNode first=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(com.consense.service.advice.AdviceBudgetFixture.contract(answer));first.put("basisScope","reference_only");
        when(local.chatStructured(anyList(),any())).thenReturn(JsonUtils.write(first),"{\"sections\":[invalid]}");
        mvc.perform(askRequest(project,session,"contract","project_contract","AUTHORED invalid repair")).andExpect(jsonPath("$.code").value(4205));
        verify(local,times(2)).chatStructured(anyList(),any());assertTrue(history(project,session).isEmpty());
    }
    @Test void synchronousEnvelopeRepairRequiresAndCommitsTheFullAnswerWithoutAPublishedPrefix() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        String full=com.consense.service.advice.AdviceBudgetFixture.contract(answer);com.fasterxml.jackson.databind.node.ObjectNode first=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(full);first.put("basisScope","reference_only");List<List<LlmClient.ChatTurn>> sent=new ArrayList<>();
        when(local.chatStructured(anyList(),any())).thenAnswer(call->{List<LlmClient.ChatTurn> turns=call.getArgument(0);sent.add(turns);if(sent.size()==1)return JsonUtils.write(first);
            String repair=turns.get(1).getContent();assertTrue(repair.contains("No units were published"));assertTrue(repair.contains("ALL sections beginning with kind=limits"));assertFalse(repair.contains("or sections=[]"));return full;});
        JsonNode result=ask(project,session,"AUTHORED synchronous envelope repair");assertTrue(result.path("grounded").asBoolean());assertEquals("project_contract",result.path("basisScope").asText());assertEquals(2,sent.size());assertEquals(sent.get(0).get(0).getContent(),sent.get(1).get(0).getContent());
        JsonNode history=history(project,session);assertEquals(2,history.size());assertEquals(result.path("content"),history.get(1).path("content"));
        JsonNode checks=JsonUtils.parse(messages.findById(result.path("messageId").asLong()).get().getAdviceChecksJson());assertEquals(2,checks.path("attempts").size());assertEquals(JsonUtils.parse(full).path("sections"),checks.path("sections"));
    }

    @Test void sourceMutationInsideTokenCountingPreventsModelDispatch() throws Exception {
        String project=project();SourceDocument original=source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        doAnswer(call->{original.setTextContent("MUTATED DURING COUNT TEST ONLY");sources.saveAndFlush(original);return observation(call.getArgument(0));}).when(counter).observe(any());
        mvc.perform(askRequest(project,session,"contract","project_contract","COUNTER MUTATION TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,never()).chatStructured(anyList(),any());assertTrue(history(project,session).isEmpty());
    }

    @Test void changingTheModelProfileDuringCountingCannotDispatchAgainstTheOldIdentity() throws Exception {
        String project=project();source(project,"CURRENT BASIS TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        int outputReserve=properties.getLlm().getStructuredMaxTokens();
        for(String selectedSession:new String[]{session,null})try {
            doAnswer(call->{properties.getLlm().setStructuredMaxTokens(outputReserve+1);return observation(call.getArgument(0));}).when(counter).observe(any());
            mvc.perform(askRequest(project,selectedSession,"contract","project_contract","MODEL CHANGE DURING COUNT TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
            verify(local,never()).chatStructured(anyList(),any());assertTrue(history(project,session).isEmpty());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
        } finally {properties.getLlm().setStructuredMaxTokens(outputReserve);}
    }

    @Test void originalSourceIdentityChangeRejectsPublicationEvenWhenParsedTextIsUnchanged() throws Exception {
        String project=project();SourceDocument original=source(project,"UNCHANGED PARSED TEXT TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        doAnswer(call->{original.setSourceSha256(hash('d'));sources.saveAndFlush(original);return com.consense.service.advice.AdviceBudgetFixture.contract(answer);}).when(local).chatStructured(anyList(),any());
        mvc.perform(askRequest(project,session,"contract","project_contract","CHANGED ORIGINAL IDENTITY TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        assertTrue(history(project,session).isEmpty());
    }

    @Test void sameSizeAndRestoredTimestampByteMutationRejectsSessionAndLegacyAnswersWithoutChangingHistory() throws Exception {
        for(boolean explicit:new boolean[]{true,false}) {
            adapters();String project=project();SourceDocument original=source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);
            String session=explicit?session(project,"contract","project_contract"):null;
            data(askRequest(project,session,"contract","project_contract","FIRST ACCEPTED ANSWER TEST ONLY"));
            JsonNode before=explicit?history(project,session):data(get("/api/advice/{id}/messages",project));clearInvocations(local);
            java.nio.file.Path path=java.nio.file.Paths.get(original.getStoragePath());long size=java.nio.file.Files.size(path);java.nio.file.attribute.FileTime timestamp=java.nio.file.Files.getLastModifiedTime(path);
            org.mockito.stubbing.Answer<String> changed=call->{byte[] bytes=java.nio.file.Files.readAllBytes(path);bytes[0]^=1;java.nio.file.Files.write(path,bytes);java.nio.file.Files.setLastModifiedTime(path,timestamp);return com.consense.service.advice.AdviceBudgetFixture.contract(answer);};
            doAnswer(changed).when(local).chatStructured(anyList(),any());
            mvc.perform(askRequest(project,session,"contract","project_contract","MUTATION DURING ANSWER TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
            assertEquals(size,java.nio.file.Files.size(path));assertEquals(timestamp,java.nio.file.Files.getLastModifiedTime(path));
            assertEquals(original.getSourceSha256(),sources.findById(original.getId()).get().getSourceSha256(),"The database identity stays unchanged; actual bytes must be rechecked");
            assertEquals(before,explicit?history(project,session):data(get("/api/advice/{id}/messages",project)));
            verify(local,times(1)).chatStructured(anyList(),any());verify(local,never()).chat(anyList());
        }
    }

    @Test void changedBytesBeforeSendingAreRejectedWithoutDispatchAndAnInvalidAnswerCannotCauseAnUnsafeRetry() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);String session=session(project,"contract","project_contract");
        java.nio.file.Path path=java.nio.file.Paths.get(original.getStoragePath());byte[] bytes=java.nio.file.Files.readAllBytes(path),changed=bytes.clone();changed[0]^=1;
        java.nio.file.Files.write(path,changed);
        mvc.perform(askRequest(project,session,"contract","project_contract","CHANGED BEFORE SENDING TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        mvc.perform(askRequest(project,null,"contract","project_contract","LEGACY CHANGED BEFORE SENDING TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,never()).chatStructured(anyList(),any());verify(local,never()).chat(anyList());assertTrue(history(project,session).isEmpty());
        java.nio.file.Files.write(path,bytes);
        doAnswer(call->{java.nio.file.Files.write(path,changed);return "TEST ONLY invalid JSON";}).when(local).chatStructured(anyList(),any());
        mvc.perform(askRequest(project,null,"contract","project_contract","INVALID JSON WITH CHANGED BYTES TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,times(1)).chatStructured(anyList(),any());verify(local,never()).chat(anyList());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
    }

    @Test void unchangedLegacySourcesAllowOneJsonRetryWithTheOriginalPromptLiterals() throws Exception {
        String project=project();source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);List<List<LlmClient.ChatTurn>> sent=new ArrayList<>();
        doAnswer(call->{sent.add(call.getArgument(0));return sent.size()==1?"TEST ONLY invalid JSON":com.consense.service.advice.AdviceBudgetFixture.contract(answer);}).when(local).chatStructured(anyList(),any());
        assertTrue(data(askRequest(project,null,"contract","project_contract","LEGACY RETRY TEST ONLY")).path("grounded").asBoolean());
        assertEquals(2,sent.size());assertEquals(sent.get(0).get(0).getContent(),sent.get(1).get(0).getContent());
        assertTrue(sent.get(0).get(0).getContent().endsWith("\n\n【输出要求】只输出一个合法的 JSON，不要 Markdown 代码块，不要任何解释文字。"));
        assertTrue(sent.get(1).get(1).getContent().startsWith(sent.get(0).get(1).getContent()));assertTrue(sent.get(1).get(1).getContent().contains("one admitted repair"));
        assertEquals(2,data(get("/api/advice/{id}/messages",project)).size());
    }

    @Test void bytesChangedByTheLegacyAvailabilityProbePreventActualDispatch() throws Exception {
        String project=project();SourceDocument original=source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);
        doAnswer(call->{java.nio.file.Path path=java.nio.file.Paths.get(original.getStoragePath());byte[] bytes=java.nio.file.Files.readAllBytes(path);bytes[0]^=1;java.nio.file.Files.write(path,bytes);return true;}).when(local).available();
        mvc.perform(askRequest(project,null,"contract","project_contract","AVAILABILITY MUTATION TEST ONLY")).andExpect(jsonPath("$.code").value(4204));
        verify(local,never()).chat(anyList());verify(local,never()).chatStructured(anyList(),any());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
    }

    @Test void bareAskCannotBypassUnknownOverBudgetOrSub256KAdmissionAndBoundRequestsKeepSingleQuestionSemantics() throws Exception {
        String project=project();source(project,"ORIGINAL BYTES TEST ONLY");rebuild(project);
        doReturn(null).when(counter).observe(any());
        mvc.perform(askRequest(project,null,"contract","project_contract","BARE UNKNOWN BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
        adapters();overrideTokens.set(262144);
        mvc.perform(askRequest(project,null,"contract","project_contract","BARE OVER BUDGET TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
        doAnswer(call->{VettingInputBudget.Observation result=observation(call.getArgument(0));result.setInputTokens(1000);result.setEffectiveContextTokens(131072);return result;}).when(counter).observe(any());
        mvc.perform(askRequest(project,null,"contract","project_contract","BARE SHORT CONTEXT TEST ONLY")).andExpect(jsonPath("$.code").value(4205));
        verify(local,never()).chatStructured(anyList(),any());verify(local,never()).chat(anyList());assertTrue(data(get("/api/advice/{id}/messages",project)).isEmpty());
        adapters();List<VettingInputBudget.Input> counted=new ArrayList<>();List<List<LlmClient.ChatTurn>> sent=new ArrayList<>();
        doAnswer(call->{VettingInputBudget.Input input=call.getArgument(0);counted.add(input);return observation(input);}).when(counter).observe(any());
        doAnswer(call->{sent.add(call.getArgument(0));return com.consense.service.advice.AdviceBudgetFixture.contract(answer);}).when(local).chatStructured(anyList(),any());
        assertTrue(data(askRequest(project,null,"contract","project_contract","BARE BOUND 256K TEST ONLY")).path("grounded").asBoolean());
        assertEquals(1,counted.size());assertEquals(1,sent.size());assertEquals(JsonUtils.write(counted.get(0).getMessages()),JsonUtils.write(sent.get(0)));
        assertTrue(counted.get(0).getSourceSnapshotSha256().matches("[a-f0-9]{64}"));assertEquals(2,data(get("/api/advice/{id}/messages",project)).size());
        assertTrue(data(get("/api/advice/{id}/sessions",project)).isEmpty(),"Admission must not require silently creating a session");
    }

    @MockBean com.consense.service.vetting.FixedBaselineRetrievalClient fixedScopeReferences;

    @Test void relationScopeStreamsOnceAndMatchesCompleteHistoryAndSqlUsingOnlyCitedFiles() throws Exception {
        String project=project();SourceDocument cited=source(project,"1. A notice must be recorded. AUTHORED TEST ONLY");
        SourceDocument other=source(project,"2. An uncited record must be retained. AUTHORED TEST ONLY");other.setFileName("SCC independent second scope fixture.txt");sources.saveAndFlush(other);rebuild(project);
        String session=session(project,"contract","project_contract");
        com.fasterxml.jackson.databind.node.ObjectNode raw=e1Answer("T3","project_contract","en","The cited source contains the selected record [1].");
        java.util.concurrent.atomic.AtomicInteger generations=e1Provider(JsonUtils.write(raw));
        List<JsonNode> events=e1Events(project,session,"contract","project_contract","Explain the relationship of the selected requirements in English.");
        JsonNode response=e1Complete(events),checks=e1AssertExchange(project,session,events,"en");
        assertEquals(1,generations.get());assertEquals(1,response.path("sources").size(),"Only the published citation enters the completion event and saved history");
        assertEquals(1,prompts.size());assertTrue(prompts.get(0).contains(cited.getTextContent()));assertTrue(prompts.get(0).contains(other.getTextContent()),"Both retrieved sources were available to the captured model request");
        String content=response.path("content").path("en").asText();
        assertTrue(content.contains("selected project sources"));assertTrue(content.contains("cites provisions from 1 file."));
        assertFalse(content.contains("cites provisions from 2 files."));
        e1AssertScopeEvent(events,"Evidence scope for this explanation");
        JsonNode scope=checks.path("applicationEvidenceScope");
        assertEquals("T3",scope.path("questionType").asText());assertEquals("project_contract",scope.path("requestedBasisScope").asText());
        assertEquals("contract",scope.path("sourceScope").asText());assertFalse(scope.path("independentFullHierarchyVerified").asBoolean(true));
        JsonNode actualCited=null;for(JsonNode source:response.path("sources"))if("[1]".equals(source.path("evidenceId").asText()))actualCited=source;
        assertNotNull(actualCited);assertTrue(Arrays.asList(String.valueOf(cited.getId()),String.valueOf(other.getId())).contains(actualCited.path("documentId").asText()));
        assertEquals(1,scope.path("documentIds").size());assertEquals(actualCited.path("documentId"),scope.path("documentIds").get(0));
        assertEquals(actualCited.path("sourceHash"),scope.path("sourceHashes").get(0));
        assertEquals(1,scope.path("sourceHashes").size());assertEquals(JsonUtils.parse("[null]"),scope.path("fixedOriginalSourceSha256"));
        assertEquals(JsonUtils.parse("[\"[1]\"]"),scope.path("citedAliases"));e1AssertModelSections(raw.path("sections"),checks);
        verify(local,never()).chatStructured(anyList(),any());
    }

    @Test void unrelatedTemplateAndLimitsOnlyDoNotPublishAnApplicationRelationScope() throws Exception {
        for(boolean limitsOnly:new boolean[]{false,true}) {
            String project=project();source(project,"1. A notice must be recorded. AUTHORED NEGATIVE CONTROL");rebuild(project);
            String session=session(project,"contract","project_contract");
            com.fasterxml.jackson.databind.node.ObjectNode raw=e1Answer(limitsOnly?"T3":"T1","project_contract","en",limitsOnly?"":"The source requires a notice record [1].");
            if(limitsOnly)raw.put("grounded",false);
            java.util.concurrent.atomic.AtomicInteger generations=e1Provider(JsonUtils.write(raw));
            List<JsonNode> events=e1Events(project,session,"contract","project_contract","Explain the selected source in English.");
            JsonNode response=e1Complete(events),checks=e1AssertExchange(project,session,events,"en");
            assertEquals(1,generations.get());assertFalse(checks.has("applicationEvidenceScope"));
            assertFalse(response.path("content").path("en").asText().contains("Evidence scope for this explanation"));
            for(JsonNode event:events)if("section".equals(event.path("event").asText()))assertFalse(event.path("data").path("content").asText().contains("Evidence scope for this explanation"));
            e1AssertModelSections(raw.path("sections"),checks);
        }
    }

    @Test void referenceOnlyRelationScopeUsesTheFixedOriginalIdentityWithoutInventingProjectAdoption() throws Exception {
        String text="AUTHORED complete reference wording: a notice must be recorded, subject to the stated exception.";
        String sourceHash=com.consense.service.vetting.VettingCorpus.hash(text),originalHash=com.consense.service.vetting.VettingCorpus.hash("AUTHORED original reference bytes\n"+text);
        com.fasterxml.jackson.databind.node.ObjectNode payload=JsonUtils.mapper().createObjectNode();
        payload.put("id","fixed:authored-scope");payload.put("content",text);payload.put("fileLabel","GCC authored reference");payload.put("fileKey","GCC");
        payload.put("pageNo",42);payload.putArray("originalPageNos").add(42);payload.put("anchor","AUTHORED record");payload.put("sourceHash",sourceHash);
        payload.put("originalSourceSha256",originalHash);payload.put("sourceRole","standard");payload.putObject("sourceQuality").put("family","GCC").put("finalQualityAccepted",false);
        payload.put("fixedDevelopmentBaseline",true);payload.put("fixedBaselineManifestSha256",com.consense.service.vetting.VettingCorpus.hash("AUTHORED scope manifest"));payload.put("fixedBaselineVersion","AUTHORED");
        JsonNode unit=JsonUtils.parse("{\"status\":\"complete_observed_unit\",\"requiredMemberIds\":[\"fixed:authored-scope\"],\"presentMemberIds\":[\"fixed:authored-scope\"],\"missingMemberIds\":[],\"qualifiersComplete\":\"unknown\"}");
        java.lang.reflect.Constructor<com.consense.service.vetting.FixedReferenceEvidence> constructor=com.consense.service.vetting.FixedReferenceEvidence.class.getDeclaredConstructor(String.class,double.class,JsonNode.class,JsonNode.class,JsonNode.class);
        constructor.setAccessible(true);com.consense.service.vetting.FixedReferenceEvidence evidence=constructor.newInstance("fixed:authored-scope",0.9,payload,null,unit);
        when(fixedScopeReferences.enabled()).thenReturn(true);when(fixedScopeReferences.frozenManifest()).thenReturn(JsonUtils.parse("{\"version\":\"AUTHORED\",\"developmentBaseline\":true}"));
        when(fixedScopeReferences.currentReadGuard(anyList(),any())).thenAnswer(call->{List<com.consense.vector.VectorStore.SearchHit> selected=call.getArgument(0);java.util.function.Consumer<JsonNode> capture=call.getArgument(1);capture.accept(fixedScopeReferences.frozenManifest());return (Runnable)()->fixedScopeReferences.assertCurrent(selected);});
        when(fixedScopeReferences.searchForProject(anyString(),eq("advisory"),anyString(),eq(10))).thenReturn(Collections.singletonList(evidence.asHit()));
        when(fixedScopeReferences.evidence(any())).thenReturn(evidence);when(fixedScopeReferences.toModelMaterial(anyList(),eq(200000))).thenReturn(JsonUtils.write(payload));
        String project=project(),session=session(project,"fullset","reference_only");
        com.fasterxml.jackson.databind.node.ObjectNode raw=e1Answer("T3","reference_only","en","The reference source requires a record subject to its stated exception [1].");
        java.util.concurrent.atomic.AtomicInteger generations=e1Provider(JsonUtils.write(raw));
        List<JsonNode> events=e1Events(project,session,"fullset","reference_only","Explain the reference requirements and their relationship in English.");
        JsonNode response=e1Complete(events),checks=e1AssertExchange(project,session,events,"en");
        assertEquals(1,generations.get());assertEquals("reference_only",response.path("basisScope").asText());assertEquals(1,response.path("sources").size());
        JsonNode source=response.path("sources").get(0);assertFalse(source.hasNonNull("documentId"),"The null fixed-reference document ID may be omitted by NON_NULL serialization; no project ID is invented");assertEquals("standard",source.path("sourceRole").asText());
        assertEquals("fixed_development_baseline",source.path("fixedBaselineEvidence").path("evidenceBackend").asText());
        assertEquals("unconfirmed",source.path("fixedBaselineEvidence").path("projectApplicability").asText());
        assertFalse(source.path("fixedBaselineEvidence").path("sourceQualityAccepted").asBoolean(true));
        String content=response.path("content").path("en").asText();assertTrue(content.contains("selected reference sources"));assertFalse(content.contains("selected project sources"));
        assertTrue(content.contains("cites provisions from 1 file."));e1AssertScopeEvent(events,"Evidence scope for this explanation");
        JsonNode scope=checks.path("applicationEvidenceScope");assertEquals("reference_only",scope.path("requestedBasisScope").asText());
        assertEquals(JsonUtils.parse("[null]"),scope.path("documentIds"));assertEquals(sourceHash,scope.path("sourceHashes").get(0).asText());
        assertEquals(originalHash,scope.path("fixedOriginalSourceSha256").get(0).asText());assertFalse(scope.path("independentFullHierarchyVerified").asBoolean(true));
        e1AssertModelSections(raw.path("sections"),checks);
    }

    @Test void streamedRepairKeepsItsAcceptedPrefixAndAddsTheApplicationScopeOnlyAfterTheFinalUnits() throws Exception {
        String project=project();source(project,"1. A notice must be recorded. AUTHORED REPAIR SOURCE");rebuild(project);String session=session(project,"contract","project_contract");
        com.fasterxml.jackson.databind.node.ObjectNode initial=e1Answer("T3","project_contract","zhHans","所引资料要求保留通知记录 [1]。");
        ((com.fasterxml.jackson.databind.node.ObjectNode)initial.path("sections").get(1)).put("id","accepted-analysis");
        com.fasterxml.jackson.databind.node.ObjectNode rejected=((com.fasterxml.jackson.databind.node.ObjectNode)initial.path("sections").get(1)).deepCopy();
        rejected.put("id","rejected-analysis");rejected.put("content","根据第 7 条，通知必须记录 [1]。");((com.fasterxml.jackson.databind.node.ArrayNode)initial.path("sections")).add(rejected);
        com.fasterxml.jackson.databind.node.ObjectNode repair=e1Answer("T3","project_contract","zhHans","此处仅说明所引资料中的记录要求 [1]。");
        com.fasterxml.jackson.databind.node.ObjectNode replacement=((com.fasterxml.jackson.databind.node.ObjectNode)repair.path("sections").get(1)).deepCopy();replacement.put("id","replacement-analysis");
        repair.putArray("sections").add(replacement);
        java.util.concurrent.atomic.AtomicInteger generations=e1Provider(JsonUtils.write(initial),JsonUtils.write(repair));
        List<JsonNode> events=e1Events(project,session,"contract","project_contract","请用简体中文解释这些要求之间的关系。");
        JsonNode response=e1Complete(events),checks=e1AssertExchange(project,session,events,"zhHans");
        assertEquals(2,generations.get());e1AssertScopeEvent(events,"本次说明的证据范围");
        assertFalse(response.path("content").path("zhHans").asText().contains("第 7 条"));
        com.fasterxml.jackson.databind.node.ArrayNode expected=JsonUtils.mapper().createArrayNode();expected.add(initial.path("sections").get(0));expected.add(initial.path("sections").get(1));expected.add(replacement);
        e1AssertModelSections(expected,checks);assertEquals(2,checks.path("attempts").size());
        assertTrue(checks.path("attempts").get(0).path("rejections").toString().contains("unsupported_formal_clause_attribution"));
        assertTrue(checks.path("attempts").get(1).path("rejections").isEmpty());
        assertEquals(3,checks.path("attempts").get(0).path("sections").size());assertEquals(1,checks.path("attempts").get(1).path("sections").size());
        assertEquals("replacement-analysis",checks.path("attempts").get(1).path("sections").get(0).path("id").asText());
        List<JsonNode> sections=new ArrayList<>();for(JsonNode event:events)if("section".equals(event.path("event").asText()))sections.add(event.path("data"));
        assertEquals(4,sections.size());assertEquals("\n\n"+initial.path("sections").get(1).path("content").asText(),sections.get(1).path("content").asText());
        assertEquals("\n\n"+replacement.path("content").asText(),sections.get(2).path("content").asText());
        verify(local,never()).chatStructured(anyList(),any());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode e1Answer(String type,String basis,String language,String content) {
        Map<String,Object> raw=new LinkedHashMap<>();raw.put("grounded",true);raw.put("basisScope",basis);raw.put("answer",content);raw.put("citations",Collections.singletonList("[1]"));
        com.fasterxml.jackson.databind.node.ObjectNode envelope=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(com.consense.service.advice.AdviceBudgetFixture.contract(JsonUtils.write(raw)));
        envelope.put("questionType",type);for(JsonNode section:envelope.path("sections"))((com.fasterxml.jackson.databind.node.ObjectNode)section).put("language",language);return envelope;
    }
    private java.util.concurrent.atomic.AtomicInteger e1Provider(String... rawResponses) {
        java.util.concurrent.atomic.AtomicInteger generations=new java.util.concurrent.atomic.AtomicInteger();when(local.supportsStreaming()).thenReturn(true);
        doAnswer(call->{int index=generations.getAndIncrement();assertTrue(index<rawResponses.length,"No unplanned third provider generation");
            String raw=rawResponses[index];prompts.add(JsonUtils.write(call.getArgument(0)));java.util.function.Consumer<String> onDelta=call.getArgument(2);onDelta.accept(raw);return raw;
        }).when(local).chatStructuredStream(anyList(),any(),any(),any());return generations;
    }
    private List<JsonNode> e1Events(String project,String session,String scope,String basis,String question) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();body.put("sessionId",session);body.put("scope",scope);body.put("basisScope",basis);body.put("question",question);
        org.springframework.test.web.servlet.MvcResult pending=mvc.perform(post("/api/advice/{id}/ask/stream",project)
                .header("X-ConSense-Advice-Request",UUID.randomUUID().toString().replace("-",""))
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body))).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(30000);String wire=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        List<JsonNode> events=new ArrayList<>();for(String frame:wire.replace("\r\n","\n").split("\n\n")) {
            String event=null;StringBuilder data=new StringBuilder();for(String line:frame.split("\n")) {
                if(line.startsWith("event:"))event=line.substring(6).trim();else if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).trim());}
            }
            if(event!=null&&data.length()>0){com.fasterxml.jackson.databind.node.ObjectNode observed=JsonUtils.mapper().createObjectNode();observed.put("event",event);observed.set("data",JsonUtils.parse(data.toString()));events.add(observed);}
        }
        assertFalse(events.isEmpty(),wire);for(JsonNode event:events)assertNotEquals("error",event.path("event").asText(),wire);return events;
    }
    private JsonNode e1Complete(List<JsonNode> events) {
        JsonNode complete=null;int count=0;for(JsonNode event:events)if("complete".equals(event.path("event").asText())){complete=event.path("data").path("response");count++;}
        assertEquals(1,count);assertNotNull(complete);return complete;
    }
    private JsonNode e1AssertExchange(String project,String session,List<JsonNode> events,String language) throws Exception {
        JsonNode response=e1Complete(events);String content=response.path("content").path(language).asText();StringBuilder streamed=new StringBuilder();
        for(JsonNode event:events)if("section".equals(event.path("event").asText()))streamed.append(event.path("data").path("content").asText());
        assertEquals(content,streamed.toString());JsonNode history=history(project,session);assertEquals(2,history.size());assertEquals(content,history.get(1).path("content").path(language).asText());
        com.consense.domain.ChatMessage saved=messages.findById(response.path("messageId").asLong()).get();assertEquals(content,saved.getContent());
        assertEquals(response.path("sources"),history.get(1).path("sources"));return JsonUtils.parse(saved.getAdviceChecksJson());
    }
    private void e1AssertScopeEvent(List<JsonNode> events,String title) {
        int scopeIndex=-1,completeIndex=-1,count=0;StringBuilder body=new StringBuilder();
        for(int index=0;index<events.size();index++){JsonNode event=events.get(index);if("complete".equals(event.path("event").asText()))completeIndex=index;
            if("section".equals(event.path("event").asText())){JsonNode data=event.path("data");String content=data.path("content").asText();body.append(content);
                if(content.contains(title)){count++;scopeIndex=index;assertTrue(content.startsWith("\n\n"+title));assertTrue(data.path("citations").isEmpty());assertTrue(data.path("sources").isEmpty());}
            }
        }
        assertEquals(1,count);assertTrue(scopeIndex>=0&&scopeIndex<completeIndex);assertEquals(body.indexOf(title),body.lastIndexOf(title));
        for(String internal:new String[]{"project_contract","reference_only","canonical","aliases"})assertFalse(events.get(scopeIndex).path("data").path("content").asText().contains(internal));
    }
    private void e1AssertModelSections(JsonNode expected,JsonNode checks) {
        JsonNode actual=checks.path("sections");assertEquals(expected.size(),actual.size());assertEquals(expected.size(),checks.path("publishedSectionIds").size());
        for(int index=0;index<expected.size();index++){JsonNode raw=expected.get(index),saved=actual.get(index);Iterator<Map.Entry<String,JsonNode>> fields=raw.fields();
            while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();assertEquals(field.getValue(),saved.path(field.getKey()),"Original model field "+index+"."+field.getKey());}
            assertEquals(raw.path("id"),checks.path("publishedSectionIds").get(index));
        }
    }

    private VettingInputBudget.Observation observation(VettingInputBudget.Input input) {
        VettingInputBudget.Observation result=new VettingInputBudget.Observation();result.setModel(input.getModel());result.setSerializedInputSha256(input.getSerializedInputSha256());
        result.setTokenizerIdentitySha256(hash('a'));result.setChatTemplateIdentitySha256(hash('b'));result.setEffectiveContextIdentitySha256(hash('c'));
        result.setCompleteChatTemplateAndSchemaObserved(true);result.setOutputReserveTokens(input.getOutputReserveTokens());result.setEffectiveContextTokens(262144);
        result.setInputTokens(overrideTokens.get()>0?overrideTokens.get():Math.max(1,input.getMessages().stream().mapToLong(t->t.getContent().length()).sum()/8));return result;
    }

    private MockHttpServletRequestBuilder reply(String project,String session,long parent) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("question","USER ASSUMPTION TEST ONLY post-contract");body.put("scope","contract");body.put("sessionId",session);body.put("replyToMessageId",parent);
        return post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body));
    }
    private MockHttpServletRequestBuilder askRequest(String project,String session,String scope,String basis,String question) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("question",question);body.put("scope",scope);body.put("basisScope",basis);body.put("sessionId",session);
        return post("/api/advice/{id}/ask",project).contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(body));
    }
    private JsonNode ask(String project,String session,String question)throws Exception{return data(askRequest(project,session,"contract","project_contract",question));}
    private JsonNode history(String project,String session)throws Exception{return data(get("/api/advice/{id}/messages",project).param("sessionId",session));}
    private String session(String project,String scope,String basis)throws Exception{return data(post("/api/advice/{id}/sessions",project).contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\""+scope+"\",\"basisScope\":\""+basis+"\"}")).path("id").asText();}
    private JsonNode data(MockHttpServletRequestBuilder request)throws Exception{return JsonUtils.parse(mvc.perform(request).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    private String project()throws Exception{String id="session-"+UUID.randomUUID();data(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"SESSION TEST ONLY\"}"));return id;}
    private void rebuild(String project)throws Exception{data(post("/api/advice/{id}/index/rebuild",project));}
    private SourceDocument source(String project,String text){SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setFileKey("SCC");source.setFileName("SCC session fixture.txt");source.setReviewRole("tender");source.setTextContent(text);source.setParseStatus("PARSED");
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);StorageService.StoredFile original=storage.store(project,SourceDocument.CATEGORY_VETTING_PACKAGE,new org.springframework.mock.web.MockMultipartFile("files",source.getFileName(),"text/plain",bytes));
        source.setStoragePath(original.getPath());com.consense.service.advice.AdviceSourceFixture.bind(source,bytes);return sources.saveAndFlush(source);}
    private static String hash(char value){char[] chars=new char[64];Arrays.fill(chars,value);return new String(chars);}
}
