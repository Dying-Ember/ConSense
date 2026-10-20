package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;

/**
 * OpenAI 兼容协议（vLLM / LM Studio / One-API / Xinference 等）。
 * 对话 POST {base}/v1/chat/completions
 * 向量 POST {base}/v1/embeddings
 */
@Slf4j
public class OpenAiLlmClient implements LlmClient {

    private final ConsenseProperties.Llm cfg;
    private final HttpSupport http;
    private final boolean miniMax;
    private final boolean qwen;

    public OpenAiLlmClient(ConsenseProperties.Llm cfg, HttpSupport http) {
        this(cfg, http, false);
    }

    /** MiniMax framing and overload policy opt-in; ordinary deployments retain their policy. */
    OpenAiLlmClient(ConsenseProperties.Llm cfg, HttpSupport http, boolean miniMax) {
        this(cfg,http,miniMax,false);
    }

    /** Qwen uses standard framing, explicit non-thinking and one-shot HTTP generations. */
    static OpenAiLlmClient qwenChat(ConsenseProperties.Llm cfg,HttpSupport http) {
        if(!Boolean.FALSE.equals(cfg.getStructuredEnableThinking()))throw new IllegalArgumentException("Qwen chat requires explicit enable_thinking=false");
        return new OpenAiLlmClient(cfg,http,false,true);
    }

    private OpenAiLlmClient(ConsenseProperties.Llm cfg,HttpSupport http,boolean miniMax,boolean qwen) {
        this.cfg=cfg;this.http=http;this.miniMax=miniMax;this.qwen=qwen;
    }

    @Override
    public String chat(List<ChatTurn> turns) {
        return chat(turns, false);
    }

    /** Prompt schema is supplied by AiGateway; this client does not assume native JSON grammar support. */
    @Override
    public String chatStructured(List<ChatTurn> turns, JsonNode schema) {
        validateStructured(schema);
        return chat(turns, true);
    }

    private void validateStructured(JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("A JSON schema object is required");
        if (cfg.getStructuredMaxTokens() <= 0) throw new IllegalArgumentException("Structured output budget must be positive");
        String thinkingMode = cfg.getStructuredOpenAiThinkingMode();
        if (thinkingMode != null && !"adaptive".equals(thinkingMode) && !"disabled".equals(thinkingMode)) {
            throw new IllegalArgumentException("Structured OpenAI thinking mode must be adaptive, disabled, or null");
        }
    }

    private ObjectNode request(List<ChatTurn> turns, boolean structured, boolean stream) {
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", cfg.getChatModel());
        body.put("temperature", cfg.getTemperature());
        body.put("stream", stream);
        if(qwen)body.put("enable_thinking",false);
        if (structured) {
            body.put("n", 1);
            body.put("max_tokens", cfg.getStructuredMaxTokens());
            if (cfg.getStructuredOpenAiThinkingMode() != null) {
                body.putObject("thinking").put("type", cfg.getStructuredOpenAiThinkingMode());
            }
            if (cfg.getStructuredOpenAiReasoningSplit() != null) {
                body.put("reasoning_split", cfg.getStructuredOpenAiReasoningSplit());
            }
        }
        ArrayNode messages = body.putArray("messages");
        for (ChatTurn turn : turns) {
            ObjectNode node = messages.addObject();
            node.put("role", turn.getRole());
            node.put("content", turn.getContent());
        }
        return body;
    }

    private String chat(List<ChatTurn> turns, boolean structured) {
        ObjectNode body = request(turns, structured, false);
        String url = trim(cfg.getBaseUrl()) + "/v1/chat/completions";
        String requestJson = JsonUtils.write(body);
        String raw = miniMax
                ? (cfg.isOverloadRetryEnabled()
                    ? http.postJsonWithOverloadBackoff(url, requestJson, cfg.getTimeoutMs(), authHeader())
                    : http.postJsonWithoutReplay(url, requestJson, cfg.getTimeoutMs(), authHeader()))
                : qwen ? http.postJsonWithoutReplay(url,requestJson,cfg.getTimeoutMs(),authHeader())
                : http.postJson(url, requestJson, cfg.getTimeoutMs(), authHeader(), structured ? 0 : cfg.getMaxRetry());
        return decodeResponse(raw,structured);
    }

    private String decodeResponse(String raw,boolean structured) {
        JsonNode root;
        try {
            root = JsonUtils.parse(raw);
        } catch (BizException invalidEnvelope) {
            if (!structured) throw invalidEnvelope;
            throw new IncompleteModelResponseException("Structured provider response is not a JSON envelope", raw, null,
                    IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE, null);
        }
        JsonNode choices = root.path("choices");
        JsonNode choice = choices.path(0);
        JsonNode content = choice.path("message").path("content");
        if (structured) {
            if (!root.isObject()) {
                throw incomplete("Structured provider response must be an object", raw, content,
                        IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE, root);
            }
            JsonNode error = root.path("error");
            JsonNode provider = root.path("base_resp");
            JsonNode providerCode = provider.path("status_code");
            if ((!error.isMissingNode() && !error.isNull())
                    || (!provider.isMissingNode() && !provider.isNull() && !provider.isObject())
                    || (!providerCode.isMissingNode() && (!providerCode.isIntegralNumber()
                        || !providerCode.canConvertToLong() || providerCode.longValue() != 0))) {
                throw incomplete("Structured provider response reports an error", raw, content,
                        IncompleteModelResponseException.FailureKind.PROVIDER_ERROR, root);
            }
            if (!choices.isArray() || choices.size() != 1 || !choice.isObject()) {
                throw incomplete("Structured model response must contain exactly one completed choice", raw, content,
                        IncompleteModelResponseException.FailureKind.INVALID_CHOICE_COUNT, root);
            }
            String finishReason = choice.path("finish_reason").asText("");
            if (!"stop".equals(finishReason)) {
                IncompleteModelResponseException.FailureKind kind = "length".equals(finishReason)
                        ? IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED
                        : "tool_calls".equals(finishReason) || "function_call".equals(finishReason)
                            ? IncompleteModelResponseException.FailureKind.TOOL_CALLS
                            : IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH;
                throw incomplete("Structured model response is incomplete: finish_reason="
                        + IncompleteModelResponseException.safeFinishReason(choice.path("finish_reason")), raw, content, kind, root);
            }
            JsonNode toolCalls = choice.path("message").path("tool_calls");
            JsonNode refusal = choice.path("message").path("refusal");
            boolean hasToolCalls = !toolCalls.isMissingNode() && !toolCalls.isNull()
                    && !(toolCalls.isArray() && toolCalls.isEmpty());
            boolean refused = !refusal.isMissingNode() && !refusal.isNull()
                    && (!refusal.isTextual() || !JsonUtils.isBlankText(refusal.textValue()));
            if (hasToolCalls || refused || !content.isTextual() || JsonUtils.isBlankText(content.textValue())) {
                IncompleteModelResponseException.FailureKind kind = refused
                        ? IncompleteModelResponseException.FailureKind.REFUSAL
                        : hasToolCalls ? IncompleteModelResponseException.FailureKind.TOOL_CALLS
                            : IncompleteModelResponseException.FailureKind.MISSING_TEXT;
                throw incomplete("Structured model response did not complete a text assessment", raw, content, kind, root);
            }
        }
        if (content.isMissingNode()) {
            throw new BizException("OpenAI 兼容端点未返回内容");
        }
        return content.asText();
    }

    @Override public boolean supportsStreaming() { return cfg.isBodyStreamingEnabled(); }

    @Override public String chatStructuredStream(List<ChatTurn> turns, JsonNode schema,
                                                 Consumer<String> onDelta, Consumer<Runnable> onCancel) {
        validateStructured(schema);
        StreamContent output = new StreamContent(onDelta, miniMax);
        http.postJsonStream(trim(cfg.getBaseUrl()) + "/v1/chat/completions",
                JsonUtils.write(request(turns, true, true)), cfg.getTimeoutMs(), authHeader(),
                16 * 1024 * 1024, true, output::event, onCancel);
        return output.finish();
    }

    ObjectNode prepareStructuredRequest(List<ChatTurn> turns,JsonNode schema,boolean stream) {
        validateStructured(schema);return request(turns,true,stream);
    }
    String sendPreparedQwen(String json,boolean stream,Consumer<String> delta,Consumer<Runnable> cancel,Consumer<String> receipt) {
        if(!qwen)throw new IllegalStateException("Qwen prepared transport required");
        if(stream) {
            StreamContent output=new StreamContent(delta,false,cfg.getChatModel());
            String raw=http.postJsonStream(trim(cfg.getBaseUrl())+"/v1/chat/completions",json,cfg.getTimeoutMs(),authHeader(),
                    16*1024*1024,true,output::event,cancel);if(receipt!=null)receipt.accept(raw);return output.finish();
        }
        String raw=http.postJsonBoundedWithoutReplay(trim(cfg.getBaseUrl())+"/v1/chat/completions",json,
                cfg.getTimeoutMs(),authHeader(),16*1024*1024,cancel);
        if(receipt!=null)receipt.accept(raw);
        JsonNode envelope;
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);envelope=JsonUtils.mapper().readTree(parser);
            if(envelope==null||!envelope.isObject()||parser.nextToken()!=null||!cfg.getChatModel().equals(envelope.path("model").asText()))throw new IOException();
        }catch(IOException|RuntimeException invalid){throw new IncompleteModelResponseException("Qwen response identity invalid",raw,null,IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE,null);}
        return decodeResponse(raw,true);
    }

    private static final class StreamContent {
        private final StringBuilder content = new StringBuilder();
        private final Consumer<String> delta;
        private final boolean miniMax;
        private final String expectedModel;
        private boolean stopped, done;
        private JsonNode last;
        StreamContent(Consumer<String> d, boolean miniMax) { this(d,miniMax,null); }
        StreamContent(Consumer<String> d,boolean miniMax,String model) { delta=java.util.Objects.requireNonNull(d);this.miniMax=miniMax;this.expectedModel=model; }
        private void reject(String message, IncompleteModelResponseException.FailureKind kind) {
            throw new IncompleteModelResponseException(message, last == null ? "" : last.toString(),
                    JsonUtils.mapper().getNodeFactory().textNode(content.toString()), kind, last);
        }
        void event(String value) {
            if (done) { reject("Provider emitted an event after stream completion", IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH); }
            if ("[DONE]".equals(value)) { done = true; return; }
            try (JsonParser parser = JsonUtils.mapper().getFactory().createParser(value)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                last = JsonUtils.mapper().readTree(parser);
                if (last == null || !last.isObject() || parser.nextToken() != null) throw new IOException();
            } catch (IOException invalid) {
                reject("Invalid streaming provider JSON envelope", IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE);
            }
            if(expectedModel!=null&&!expectedModel.equals(last.path("model").asText()))reject("Qwen streaming response model differs",IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE);
            JsonNode code = last.path("base_resp").path("status_code");
            if (last.hasNonNull("error") || (last.hasNonNull("base_resp") && !last.path("base_resp").isObject())
                    || (!code.isMissingNode() && (!code.isIntegralNumber() || !code.canConvertToLong() || code.longValue() != 0)))
                reject("Streaming provider reports an error", IncompleteModelResponseException.FailureKind.PROVIDER_ERROR);
            JsonNode choices = last.path("choices");
            if (choices.isArray() && choices.isEmpty() && stopped && last.path("usage").isObject()) return;
            if (!choices.isArray() || choices.size() != 1 || !choices.get(0).isObject()
                    || !choices.get(0).path("index").isIntegralNumber() || !choices.get(0).path("index").canConvertToInt()
                    || choices.get(0).path("index").intValue() != 0)
                reject("Streaming model must return exactly one indexed choice", IncompleteModelResponseException.FailureKind.INVALID_CHOICE_COUNT);
            JsonNode choice = choices.get(0), chunk = choice.path("delta");
            if (!chunk.isObject() || stopped) reject("Streaming choice is incomplete or already stopped", IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH);
            if (chunk.hasNonNull("tool_calls") && !(chunk.path("tool_calls").isArray() && chunk.path("tool_calls").isEmpty())
                    || chunk.hasNonNull("function_call")) reject("Streaming tool output is unsupported", IncompleteModelResponseException.FailureKind.TOOL_CALLS);
            if (chunk.hasNonNull("refusal") && (!chunk.path("refusal").isTextual() || !chunk.path("refusal").asText().isEmpty()))
                reject("Streaming model refused text output", IncompleteModelResponseException.FailureKind.REFUSAL);
            if (chunk.hasNonNull("role") && !"assistant".equals(chunk.path("role").asText()))
                reject("Streaming role is not assistant", IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE);
            JsonNode text = chunk.path("content");
            if (!text.isMissingNode() && !text.isNull()) {
                if (!text.isTextual()) reject("Streaming content is not text", IncompleteModelResponseException.FailureKind.MISSING_TEXT);
                String part = text.textValue();
                if (part.length() > 4 * 1024 * 1024 - content.length()) reject("Streaming output bound exceeded", IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED);
                content.append(part);
                if (!part.isEmpty()) delta.accept(part);
            }
            if (choice.hasNonNull("finish_reason") && !(miniMax && choice.path("finish_reason").isTextual() && choice.path("finish_reason").textValue().isEmpty())) {
                String reason = choice.path("finish_reason").asText();
                if (!"stop".equals(reason)) reject("Streaming model did not stop normally",
                        "length".equals(reason) ? IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED
                                : "tool_calls".equals(reason) || "function_call".equals(reason) ? IncompleteModelResponseException.FailureKind.TOOL_CALLS
                                : IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH);
                stopped = true;
            }
        }
        String finish() {
            if (!stopped || (!done && !miniMax)) reject("Streaming model completion marker is missing", IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH);
            if (JsonUtils.isBlankText(content.toString())) reject("Streaming model returned no text", IncompleteModelResponseException.FailureKind.MISSING_TEXT);
            return content.toString();
        }
    }

    private IncompleteModelResponseException incomplete(String message, String raw, JsonNode content,
                    IncompleteModelResponseException.FailureKind kind, JsonNode root) {
        return new IncompleteModelResponseException(message, raw, content, kind, root);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", cfg.getEmbedModel());
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        String raw = http.postJson(trim(cfg.getBaseUrl()) + "/v1/embeddings",
                JsonUtils.write(body), cfg.getTimeoutMs(), authHeader(), cfg.getMaxRetry());
        JsonNode data = JsonUtils.parse(raw).path("data");
        List<float[]> result = new ArrayList<>(data.size());
        for (JsonNode node : data) {
            JsonNode vector = node.path("embedding");
            float[] array = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                array[i] = (float) vector.get(i).asDouble();
            }
            result.add(array);
        }
        return result;
    }

    @Override
    public boolean available() {
        try {
            String endpoint = trim(cfg.getBaseUrl()) + "/v1/models";
            String raw = JsonUtils.isBlankText(cfg.getApiKey()) ? http.get(endpoint, 4000)
                    : http.get(endpoint, 4000, authHeader());
            return JsonUtils.parse(raw).has("data");
        } catch (Exception e) {
            log.debug("OpenAI 兼容端点不可达: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String chatModel() {
        return cfg.getChatModel();
    }

    @Override
    public String embedModel() {
        return cfg.getEmbedModel();
    }

    private String authHeader() {
        return JsonUtils.isBlankText(cfg.getApiKey()) ? null : "Bearer " + cfg.getApiKey();
    }

    private String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
