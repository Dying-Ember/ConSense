package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic HTTP contracts only; no environment, network or provider calls. */
class QwenProfileAdapterTest {
    @Test void bothModelsUseBoundedOrdinaryAndStructuredChatWithoutMiniMaxExtensions() {
        ConsenseProperties props=new ConsenseProperties();
        for(ConsenseProperties.Llm cfg:Arrays.asList(props.getQwen(),props.getQwenFlash())) {
            cfg.setApiKey("synthetic-qwen-key");cfg.setStructuredMaxTokens(1234);HttpSupport http=mock(HttpSupport.class);
            when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
                assertEquals("https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",call.getArgument(0));
                JsonNode request=JsonUtils.parse(call.getArgument(1));assertEquals(cfg.getChatModel(),request.path("model").asText());
                assertEquals(1,request.path("n").asInt());assertEquals(1234,request.path("max_tokens").asInt());
                assertFalse(request.has("thinking"));assertFalse(request.has("reasoning_split"));assertTrue(request.path("enable_thinking").isBoolean());assertFalse(request.path("enable_thinking").booleanValue());
                assertFalse(request.toString().contains(cfg.getApiKey()));
                return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\",\"reasoning_content\":\"PRIVATE THOUGHT\"}}]}";
            });
            QwenChatClient client=new QwenChatClient(cfg,http);
            assertEquals("[]",client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
            assertEquals("[]",client.chatStructured(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY")),JsonUtils.mapper().createObjectNode()));
            verify(http,times(2)).postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString());
            verify(http,never()).postJsonWithOverloadBackoff(anyString(),anyString(),anyLong(),anyString());
            verify(http,never()).postJson(anyString(),anyString(),anyLong(),anyString(),anyInt());
            cfg.setStructuredOpenAiThinkingMode("disabled");
            assertThrows(IllegalArgumentException.class,()->new QwenChatClient(cfg,http));
        }
    }

    @Test void ordinaryChatRejectsTruncationMultipleChoicesAndTools() {
        ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();cfg.setApiKey("synthetic-key");HttpSupport http=mock(HttpSupport.class);QwenChatClient client=new QwenChatClient(cfg,http);
        String[] rejected={"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"[\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}},{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"[]\",\"tool_calls\":[{}]}}]}"};
        IncompleteModelResponseException.FailureKind[] kinds={IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,IncompleteModelResponseException.FailureKind.INVALID_CHOICE_COUNT,IncompleteModelResponseException.FailureKind.TOOL_CALLS};
        for(int n=0;n<rejected.length;n++) {
            String response=rejected[n];
            when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(response);
            IncompleteModelResponseException failure=assertThrows(IncompleteModelResponseException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
            assertEquals(kinds[n],failure.getFailureKind());assertEquals(response,failure.getRawResponse());
        }
        verify(http,times(3)).postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString());
    }

    @Test void providerFailuresCannotPersistAnEchoedSyntheticKey() {
        ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();cfg.setApiKey("synthetic-secret");HttpSupport http=mock(HttpSupport.class);QwenChatClient client=new QwenChatClient(cfg,http);
        for(String echoed:Arrays.asList("synthetic-secret","synthetic-"+"\\u0073"+"ecret")) {
            String response="{\"error\":{\"message\":\""+echoed+"\"},\"choices\":[]}";
            when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(response);
            when(http.postJson(anyString(),anyString(),anyLong(),anyString(),anyInt())).thenReturn(response);
            IncompleteModelResponseException failure=assertThrows(IncompleteModelResponseException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
            assertEquals(IncompleteModelResponseException.FailureKind.PROVIDER_ERROR,failure.getFailureKind());
            assertFalse(failure.getRawResponse().contains(cfg.getApiKey()));assertFalse(failure.getRawResponse().contains("\\u0073"));
            assertTrue(failure.getRawResponse().contains("[REDACTED]"));
        }
        assertAll("Every untrusted error branch hides encoded credentials",()->{
            for(String message:Arrays.asList("HTTP401 Authorization: synthetic-secret","HTTP400 {\"error\":\"synthetic-"+"\\u0073"+"ecret\"}")) {
                reset(http);when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenThrow(new BizException(message));
                BizException transport=assertThrows(BizException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
                assertFalse(transport.getMessage().contains(cfg.getApiKey()));assertFalse(transport.getMessage().contains("\\u0073"));
                assertFalse(transport.getMessage().contains("error"));assertTrue(transport.getMessage().contains(message.substring(0,7)));
            }
        },()->{
            for(String invalid:Arrays.asList("{\"error\":\"synthetic-"+"\\u0073"+"ecret\"},", "{\"error\":\"synthetic-"+"\\u0073"+"ecret\",\"error\":\"later\"}")) {
                reset(http);when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(invalid);
                IncompleteModelResponseException failure=assertThrows(IncompleteModelResponseException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
                assertFalse(failure.getRawResponse().contains("\\u0073"));assertFalse(failure.getRawResponse().contains(cfg.getApiKey()));
                assertEquals("[UNTRUSTED_PROVIDER_RESPONSE_REDACTED]",failure.getRawResponse());
            }
        });
        String business="{\"value\":\"synthetic-"+"\\u0073"+"ecret\"}";
        com.fasterxml.jackson.databind.node.ObjectNode envelope=JsonUtils.mapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode choice=envelope.putArray("choices").addObject();choice.put("finish_reason","stop");choice.putObject("message").put("content",business);
        reset(http);when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(JsonUtils.write(envelope));
        String completed=client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY")));
        assertEquals("[REDACTED]",JsonUtils.parse(completed).path("value").asText());
        choice.put("finish_reason","length");
        reset(http);when(http.postJsonWithoutReplay(anyString(),anyString(),anyLong(),anyString())).thenReturn(JsonUtils.write(envelope));
        IncompleteModelResponseException partial=assertThrows(IncompleteModelResponseException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
        assertEquals(IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,partial.getFailureKind());
        assertEquals("[REDACTED]",JsonUtils.parse(partial.getModelContent()).path("value").asText());
        assertFalse(partial.getRawResponse().contains("\\u0073"));
    }

    @Test void centralConfigurationLookupAndChatSelectionPreserveDeploymentEmbeddings() {
        ConsenseProperties props=new ConsenseProperties();LlmClient local=mock(LlmClient.class),miniMax=mock(LlmClient.class),qwen=mock(LlmClient.class),flash=mock(LlmClient.class);
        LlmProfiles profiles=new LlmProfiles(props,local,miniMax,qwen,flash);
        assertSame(props.getQwen(),LlmProfiles.configuration(props,"qwen"));assertSame(props.getQwenFlash(),LlmProfiles.configuration(props,"qwen-flash"));
        assertThrows(IllegalArgumentException.class,()->LlmProfiles.configuration(props,"foreign"));
        when(local.available()).thenReturn(true);List<String> input=Collections.singletonList("TEST ONLY");List<float[]> vectors=Collections.singletonList(new float[]{1});when(local.embed(input)).thenReturn(vectors);
        AiGateway gateway=new AiGateway(local,profiles);
        for(String id:Arrays.asList("qwen","qwen-flash"))try(LlmProfiles.Scope ignored=profiles.bind(profiles.resolve(id))){assertSame(vectors,gateway.embed(input));}
        verify(local,times(2)).embed(input);verifyNoInteractions(qwen,flash,miniMax);
        assertThrows(BizException.class,()->new QwenChatClient(props.getQwen(),mock(HttpSupport.class)).embed(input));
    }
}
