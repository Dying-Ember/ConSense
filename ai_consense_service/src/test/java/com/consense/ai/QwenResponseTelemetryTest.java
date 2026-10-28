package com.consense.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QwenResponseTelemetryTest {
    private static final String SOURCE="SOURCE_SENTINEL private contract",ANSWER="MODEL_SENTINEL answer",THOUGHT="REASONING_SENTINEL private reasoning",KEY="KEY_SENTINEL-secret";
    private final ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();
    private final HttpSupport http=mock(HttpSupport.class);
    private final JsonNode schema=JsonUtils.mapper().createObjectNode();
    private QwenChatClient client(){cfg.setApiKey(KEY);cfg.setStructuredMaxTokens(16384);return new QwenChatClient(cfg,http);}
    private ObjectNode envelope(String reason){ObjectNode root=JsonUtils.mapper().createObjectNode();root.put("model",cfg.getChatModel());ObjectNode c=root.putArray("choices").addObject();c.put("finish_reason",reason);c.putObject("message").put("content",ANSWER).put("reasoning_content",THOUGHT);root.putObject("usage").put("prompt_tokens",345).put("completion_tokens",89).putObject("completion_tokens_details").put("reasoning_tokens",80);return root;}
    @Test void completeAndTruncatedResponsesReportOnlyScalarsAndKeepExactResults(){
        try(Capture trace=new Capture(OpenAiLlmClient.class,AiGateway.class)){
            QwenChatClient client=client();ObjectNode response=envelope("stop");when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(JsonUtils.write(response));
            assertEquals(ANSWER,client.chat(List.of(LlmClient.ChatTurn.user(SOURCE))));
            ((ObjectNode)response.path("choices").get(0)).put("finish_reason","length");when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(JsonUtils.write(response));
            IncompleteModelResponseException failure=assertThrows(IncompleteModelResponseException.class,()->client.chat(List.of(LlmClient.ChatTurn.user(SOURCE))));assertEquals(IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,failure.getFailureKind());assertEquals(ANSWER,failure.getModelContent());
            String logs=trace.messages();assertTrue(logs.contains("requestedMaxTokens=16384"));assertTrue(logs.contains("finishReason=stop"));assertTrue(logs.contains("finishReason=length"));assertTrue(logs.contains("reportedPromptTokens=345"));assertTrue(logs.contains("reportedCompletionTokens=89"));assertTrue(logs.contains("reportedReasoningTokens=80"));assertTrue(logs.contains("contentCharacters="+ANSWER.length()));assertTrue(logs.contains("reasoningCharacters="+THOUGHT.length()));trace.assertNoBodies();
        }
    }
    @Test void preparedRequestReportsItsCapturedLimitAndDoesNotChangeProviderIdentityGate(){
        try(Capture trace=new Capture(OpenAiLlmClient.class)){
            QwenChatClient client=client();QwenChatClient.PreparedRequest prepared=client.prepare(List.of(LlmClient.ChatTurn.user(SOURCE)),schema,false);cfg.setStructuredMaxTokens(7);
            when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenReturn(JsonUtils.write(envelope("stop")));
            assertEquals(ANSWER,client.generate(prepared,null,null));assertTrue(trace.messages().contains("requestedMaxTokens=16384"));assertFalse(trace.messages().contains("requestedMaxTokens=7"));trace.assertNoBodies();
        }
    }
    @Test void streamedReasoningAndFinalUsageOnlyEventAreCountedWithoutBeingLogged(){
        try(Capture trace=new Capture(OpenAiLlmClient.class)){
            QwenChatClient client=client();doAnswer(call->{Consumer<String> events=call.getArgument(6);ObjectNode first=JsonUtils.mapper().createObjectNode();ObjectNode c=first.putArray("choices").addObject();c.put("index",0);c.putObject("delta").put("content",ANSWER).put("reasoning_content",THOUGHT);events.accept(JsonUtils.write(first));ObjectNode last=JsonUtils.mapper().createObjectNode();ObjectNode ending=last.putArray("choices").addObject();ending.put("index",0).put("finish_reason","stop");ending.putObject("delta");events.accept(JsonUtils.write(last));ObjectNode usage=JsonUtils.mapper().createObjectNode();usage.putArray("choices");usage.putObject("usage").put("prompt_tokens",500).put("completion_tokens",100).put("reasoning_tokens",90);events.accept(JsonUtils.write(usage));events.accept("[DONE]");return "";}).when(http).postJsonStream(anyString(),anyString(),anyLong(),anyString(),anyInt(),anyBoolean(),any(),any());
            assertEquals(ANSWER,client.chatStructuredStream(List.of(LlmClient.ChatTurn.user(SOURCE)),schema,value->{},null));String logs=trace.messages();assertTrue(logs.contains("stream=true"));assertTrue(logs.contains("reportedPromptTokens=500"));assertTrue(logs.contains("reportedReasoningTokens=90"));assertTrue(logs.contains("reasoningCharacters="+THOUGHT.length()));trace.assertNoBodies();
        }
    }
    @Test void malformedMetadataStaysUnknownAndCapOneIsExplicit(){
        try(Capture trace=new Capture(OpenAiLlmClient.class)){
            OpenAiLlmClient.QwenResponseTelemetry telemetry=new OpenAiLlmClient.QwenResponseTelemetry("preflight",1,false);
            telemetry.observe(JsonUtils.parse("{\"choices\":[{\"finish_reason\":\"KEY_SENTINEL-secret\",\"message\":{\"content\":null}}],\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":\"SOURCE_SENTINEL private contract\",\"reasoning_tokens\":999999999999999999999999999}}"),false);telemetry.finish(false);
            String logs=trace.messages();assertTrue(logs.contains("phase=preflight"));assertTrue(logs.contains("requestedMaxTokens=1"));assertTrue(logs.contains("finishReason=unknown"));assertTrue(logs.contains("contentCharacters=null"));assertTrue(logs.contains("reportedPromptTokens=null"));assertTrue(logs.contains("reportedCompletionTokens=null"));assertTrue(logs.contains("reportedReasoningTokens=null"));trace.assertNoBodies();
        }
    }
    @Test void gatewayPromptResponseAndRetryLogsDoNotEchoUntrustedValues(){
        try(Capture trace=new Capture(AiGateway.class)){
            LlmClient client=mock(LlmClient.class);when(client.available()).thenReturn(true);when(client.chat(anyList())).thenReturn(ANSWER);AiGateway gateway=new AiGateway(client);assertEquals(ANSWER,gateway.complete(SOURCE,THOUGHT+KEY));
            when(client.chat(anyList())).thenReturn("{broken:"+SOURCE+THOUGHT+KEY,"{}");assertEquals(Collections.emptyMap(),gateway.completeJson(SOURCE,THOUGHT,Map.class));
            String logs=trace.messages();assertTrue(logs.contains("stage=request_ready"));assertTrue(logs.contains("stage=response_received"));assertTrue(logs.contains("stage=json_retry"));trace.assertNoBodies();
        }
    }
    @Test void brokenLoggingSinkCannotReplaceSuccessOrTheOriginalFailure(){
        Logger logger=(Logger)LoggerFactory.getLogger(OpenAiLlmClient.class);ch.qos.logback.core.AppenderBase<ILoggingEvent> broken=new ch.qos.logback.core.AppenderBase<ILoggingEvent>(){@Override protected void append(ILoggingEvent e){throw new AssertionError("Logging sink failed");}};broken.start();logger.addAppender(broken);
        try{QwenChatClient client=client();when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(JsonUtils.write(envelope("stop")));assertEquals(ANSWER,client.chat(List.of(LlmClient.ChatTurn.user(SOURCE))));when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(JsonUtils.write(envelope("length")));assertEquals(IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,assertThrows(IncompleteModelResponseException.class,()->client.chat(List.of(LlmClient.ChatTurn.user(SOURCE)))).getFailureKind());}finally{logger.detachAppender(broken);}
    }
    @Test void incompleteGatewayKeepsExplicitRawCollectorWithoutLoggingTheEnvelope(){
        try(Capture trace=new Capture(AiGateway.class)){
            LlmClient client=mock(LlmClient.class);when(client.available()).thenReturn(true);ObjectNode response=envelope("length");
            IncompleteModelResponseException original=new IncompleteModelResponseException("incomplete",JsonUtils.write(response),JsonUtils.mapper().getNodeFactory().textNode(ANSWER),IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,response);
            when(client.chatStructured(anyList(),any())).thenThrow(original);List<String> retained=new ArrayList<>();
            assertSame(original,assertThrows(IncompleteModelResponseException.class,()->new AiGateway(client).completeStructuredJsonList(SOURCE,THOUGHT,Map.class,JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}"),retained)));
            assertEquals(List.of(ANSWER),retained);trace.assertNoBodies();
        }
    }
    private static final class Capture implements AutoCloseable {
        final List<Logger> loggers=new ArrayList<>();final ListAppender<ILoggingEvent> trace=new ListAppender<>();
        Capture(Class<?>... types){trace.start();for(Class<?> type:types){Logger logger=(Logger)LoggerFactory.getLogger(type);logger.addAppender(trace);loggers.add(logger);}}
        String messages(){StringBuilder value=new StringBuilder();for(ILoggingEvent e:trace.list)value.append(e.getFormattedMessage()).append('\n');return value.toString();}
        void assertNoBodies(){String logs=messages();for(String secret:List.of(SOURCE,ANSWER,THOUGHT,KEY))assertFalse(logs.contains(secret));for(ILoggingEvent e:trace.list)assertTrue(e.getFormattedMessage().length()<2000);}
        @Override public void close(){for(Logger logger:loggers)logger.detachAppender(trace);}
    }
}
