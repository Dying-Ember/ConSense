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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;

/**
 * Ollama 原生协议客户端：
 * 对话   POST {base}/api/chat        { model, messages, stream:false, options }
 * 向量   POST {base}/api/embed       { model, input:[...] }  （旧版回退 /api/embeddings）
 */
@Slf4j
public class OllamaLlmClient implements LlmClient, EmbeddingAvailability {

    private final ConsenseProperties.Llm cfg;
    private final HttpSupport http;

    public OllamaLlmClient(ConsenseProperties.Llm cfg, HttpSupport http) {
        this.cfg = cfg;
        this.http = http;
    }

    @Override
    public String chat(List<ChatTurn> turns) {
        return chat(turns, null);
    }

    @Override
    public String chatStructured(List<ChatTurn> turns, JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("A JSON schema object is required");
        return chat(turns, schema);
    }

    private ObjectNode request(List<ChatTurn> turns, JsonNode schema, boolean stream) {
        if (schema != null && cfg.getStructuredMaxTokens() <= 0) {
            throw new IllegalArgumentException("Structured output budget must be positive");
        }
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("model", cfg.getChatModel());
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        for (ChatTurn turn : turns) {
            ObjectNode node = messages.addObject();
            node.put("role", turn.getRole());
            node.put("content", turn.getContent());
        }
        ObjectNode options = body.putObject("options");
        options.put("temperature", cfg.getTemperature());
        options.put("num_ctx", cfg.getNumCtx());
        if (schema != null) {
            body.set("format", schema);
            Boolean thinking = cfg.getStructuredThinking();
            if (thinking != null) body.put("think", thinking);
            // Vetting must fail visibly rather than generate an unbounded quotation/list loop.
            options.put("num_predict", cfg.getStructuredMaxTokens());
        }
        return body;
    }

    private String chat(List<ChatTurn> turns, JsonNode schema) {
        ObjectNode body = request(turns, schema, false);
        String raw = http.postJson(trim(cfg.getBaseUrl()) + "/api/chat",
                JsonUtils.write(body), cfg.getTimeoutMs(), null, schema == null ? cfg.getMaxRetry() : 0);

        JsonNode root = JsonUtils.parse(raw);
        if (schema != null && "length".equals(root.path("done_reason").asText())) {
            throw new IncompleteModelResponseException("Structured model response exhausted its output budget; semantic review is incomplete",
                    raw, root.path("message").path("content"));
        }
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || JsonUtils.isBlankText(content.asText())) {
            throw new BizException("Ollama 未返回内容，请确认模型 " + cfg.getChatModel() + " 已 pull");
        }
        return content.asText();
    }

    @Override public boolean supportsStreaming() { return true; }

    @Override public String chatStructuredStream(List<ChatTurn> turns, JsonNode schema,
                                                 Consumer<String> onDelta, Consumer<Runnable> onCancel) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("A JSON schema object is required");
        java.util.Objects.requireNonNull(onDelta);
        StringBuilder content = new StringBuilder();
        boolean[] done = {false};
        http.postJsonStream(trim(cfg.getBaseUrl()) + "/api/chat", JsonUtils.write(request(turns, schema, true)),
                cfg.getTimeoutMs(), null, 16 * 1024 * 1024, false, value -> {
            JsonNode root;
            try (JsonParser parser = JsonUtils.mapper().getFactory().createParser(value)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                root = JsonUtils.mapper().readTree(parser);
                if (root == null || !root.isObject() || parser.nextToken() != null) throw new IOException();
            } catch (IOException invalid) { throw new IncompleteModelResponseException("Invalid Ollama streaming envelope", "", null,
                    IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE, null); }
            if (root.hasNonNull("error")) throw new IncompleteModelResponseException("Ollama streaming provider error", root.toString(), null,
                    IncompleteModelResponseException.FailureKind.PROVIDER_ERROR, root);
            if (done[0] || !root.path("done").isBoolean() || !root.path("message").isObject()
                    || !root.path("message").path("content").isTextual()
                    || !"assistant".equals(root.path("message").path("role").asText())
                    || root.path("message").hasNonNull("tool_calls")
                    && !(root.path("message").path("tool_calls").isArray() && root.path("message").path("tool_calls").isEmpty()))
                throw new IncompleteModelResponseException("Incomplete Ollama streaming message", root.toString(), null,
                        IncompleteModelResponseException.FailureKind.INVALID_RESPONSE_ENVELOPE, root);
            String part = root.path("message").path("content").textValue();
            if (part.length() > 4 * 1024 * 1024 - content.length()) throw new IncompleteModelResponseException("Ollama streaming output bound exceeded", "", null,
                    IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED, root);
            content.append(part); if (!part.isEmpty()) onDelta.accept(part);
            if (root.path("done").booleanValue()) {
                String reason = root.path("done_reason").asText();
                if (!"stop".equals(reason)) throw new IncompleteModelResponseException("Ollama streaming response did not stop normally", root.toString(),
                        JsonUtils.mapper().getNodeFactory().textNode(content.toString()), "length".equals(reason)
                        ? IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED : IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH, root);
                done[0] = true;
            }
        }, onCancel);
        if (!done[0] || JsonUtils.isBlankText(content.toString())) throw new IncompleteModelResponseException("Ollama stream ended without completed text", "",
                JsonUtils.mapper().getNodeFactory().textNode(content.toString()), IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH, null);
        return content.toString();
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

        String raw;
        try {
            raw = http.postJson(trim(cfg.getBaseUrl()) + "/api/embed",
                    JsonUtils.write(body), cfg.getTimeoutMs(), null, cfg.getMaxRetry());
            JsonNode embeddings = JsonUtils.parse(raw).path("embeddings");
            if (embeddings.isArray() && !embeddings.isEmpty()) {
                return toVectors(embeddings);
            }
        } catch (Exception e) {
            log.warn("Ollama /api/embed 不可用，回退到 /api/embeddings: {}", e.getMessage());
        }

        // 旧版逐个 embedding
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            ObjectNode single = JsonUtils.mapper().createObjectNode();
            single.put("model", cfg.getEmbedModel());
            single.put("prompt", text);
            String one = http.postJson(trim(cfg.getBaseUrl()) + "/api/embeddings",
                    JsonUtils.write(single), cfg.getTimeoutMs(), null, cfg.getMaxRetry());
            JsonNode vector = JsonUtils.parse(one).path("embedding");
            result.add(toVector(vector));
        }
        return result;
    }

    @Override
    public boolean available() {
        try {
            String raw = http.get(trim(cfg.getBaseUrl()) + "/api/tags", 4000);
            return JsonUtils.parse(raw).has("models");
        } catch (Exception e) {
            log.debug("Ollama 不可达: {}", e.getMessage());
            return false;
        }
    }

    /** Read-only inventory probe: a chat model or reachable server does not supply embeddings. */
    @Override
    public boolean embeddingAvailable() {
        String configured = modelTag(cfg.getEmbedModel());
        if (configured.isEmpty()) return false;
        try {
            JsonNode models=JsonUtils.parse(http.get(trim(cfg.getBaseUrl())+"/api/tags",4000)).path("models");
            if (!models.isArray()) return false;
            for (JsonNode model:models) {
                if (configured.equals(modelTag(model.path("name").asText("")))
                        || configured.equals(modelTag(model.path("model").asText("")))) return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("Ollama configured embedding model is unavailable: {}",e.getMessage());
            return false;
        }
    }

    private static String modelTag(String value) {
        String model=value==null?"":value.trim();
        if(model.isEmpty()) return "";
        return model.lastIndexOf(':')>model.lastIndexOf('/')?model:model+":latest";
    }

    @Override
    public String chatModel() {
        return cfg.getChatModel();
    }

    @Override
    public String embedModel() {
        return cfg.getEmbedModel();
    }

    private List<float[]> toVectors(JsonNode array) {
        List<float[]> result = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            result.add(toVector(node));
        }
        return result;
    }

    private float[] toVector(JsonNode node) {
        float[] vector = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    private String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 供健康检查展示的附加信息 */
    public Map<String, Object> tags() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            JsonNode root = JsonUtils.parse(http.get(trim(cfg.getBaseUrl()) + "/api/tags", 4000));
            List<String> models = new ArrayList<>();
            root.path("models").forEach(m -> models.add(m.path("name").asText()));
            info.put("reachable", true);
            info.put("models", models);
        } catch (Exception e) {
            info.put("reachable", false);
            info.put("error", e.getMessage());
        }
        return info;
    }
}
