package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Qwen chat adapter: bounded ordinary/structured output and generic OpenAI framing. */
public final class QwenChatClient implements LlmClient {
    private final OpenAiLlmClient delegate;
    private final ConsenseProperties.Llm cfg;
    private final HttpSupport http;
    public QwenChatClient(ConsenseProperties.Llm cfg,HttpSupport http) {
        if(cfg.getStructuredOpenAiThinkingMode()!=null||cfg.getStructuredOpenAiReasoningSplit()!=null)
            throw new IllegalArgumentException("Qwen profiles do not accept MiniMax thinking/reasoning-split extensions");
        this.cfg=cfg;this.http=http;delegate=OpenAiLlmClient.qwenChat(cfg,http);
    }
    /** Compare the actual adapter credential without exposing its value or a digest. */
    public boolean matchesCredential(String candidate) {
        String actual=cfg.getApiKey();
        return candidate!=null&&!candidate.isEmpty()&&actual!=null&&!actual.isEmpty()
                &&java.security.MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Override public String chat(List<ChatTurn> turns){return safeCall(()->delegate.chatStructured(turns,JsonUtils.mapper().createObjectNode()));}
    @Override public String chatStructured(List<ChatTurn> turns,JsonNode schema){return safeCall(()->delegate.chatStructured(turns,schema));}
    @Override public boolean supportsStreaming(){return delegate.supportsStreaming();}
    @Override public String chatStructuredStream(List<ChatTurn> turns,JsonNode schema,Consumer<String> onDelta,Consumer<Runnable> onCancel) {
        ProviderStreamReader.SafeDelta safe=new ProviderStreamReader.SafeDelta(cfg.getApiKey(),onDelta);
        String result=safeCall(()->delegate.chatStructuredStream(turns,schema,safe,onCancel));safe.finish();return result;
    }
    @Override public List<float[]> embed(List<String> texts){throw new BizException(5001,"Qwen chat selection does not provide deployment embeddings.");}
    @Override public boolean available() {
        if(!cfg.isEnabled()||JsonUtils.isBlankText(cfg.getApiKey()))return false;
        try {String root=cfg.getBaseUrl().replaceAll("/+$","");return JsonUtils.parse(http.get(root+"/v1/models",4000,"Bearer "+cfg.getApiKey())).has("data");}
        catch(RuntimeException unavailable){return false;}
    }
    @Override public String chatModel(){return delegate.chatModel();}
    @Override public String embedModel(){return "";}
    /** Immutable bytes built by the same adapter used for actual Qwen chat. */
    public static final class PreparedRequest {
        private final String generationJson,probeJson;private final boolean stream;
        private PreparedRequest(String generation,String probe,boolean streaming){generationJson=generation;probeJson=probe;stream=streaming;}
        public String getGenerationJson(){return generationJson;} public String getProbeJson(){return probeJson;} public boolean isStream(){return stream;}
    }
    public PreparedRequest prepare(List<ChatTurn> turns,JsonNode schema,boolean stream) {
        com.fasterxml.jackson.databind.node.ObjectNode generation=delegate.prepareStructuredRequest(turns,schema,stream);
        com.fasterxml.jackson.databind.node.ObjectNode probe=generation.deepCopy();probe.put("max_tokens",1);probe.put("stream",false);
        return new PreparedRequest(JsonUtils.write(generation),JsonUtils.write(probe),stream);
    }
    /** A real cap1 generation, deliberately separate from business-output decoding and repair. */
    public String usageProbe(PreparedRequest prepared,Consumer<Runnable> cancel) {
        try {return http.postJsonBoundedWithoutReplay(cfg.getBaseUrl().replaceAll("/+$","")+"/v1/chat/completions",
                prepared.probeJson,cfg.getTimeoutMs(),"Bearer "+cfg.getApiKey(),1024*1024,cancel);}
        catch(RuntimeException error){throw new BizException(5001,"Qwen usage preflight transport failed ("+error.getClass().getSimpleName()+")");}
    }
    public String generate(PreparedRequest prepared,Consumer<String> delta,Consumer<Runnable> cancel) {return generate(prepared,delta,cancel,null);}
    public String generate(PreparedRequest prepared,Consumer<String> delta,Consumer<Runnable> cancel,Consumer<JsonNode> receipt) {
        ProviderStreamReader.SafeDelta safe=new ProviderStreamReader.SafeDelta(cfg.getApiKey(),delta==null?ignored->{}:delta);
        String result=safeCall(()->delegate.sendPreparedQwen(prepared.generationJson,prepared.stream,safe,cancel,raw->{if(receipt!=null)receipt.accept(responseReceipt(raw));}));safe.finish();return result;
    }
    private JsonNode responseReceipt(String raw) {
        com.fasterxml.jackson.databind.node.ObjectNode safe=JsonUtils.mapper().createObjectNode();
        try {StringBuilder value=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));safe.put("responseSha256",value.toString());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        try {JsonNode envelope=JsonUtils.parse(raw);if(cfg.getChatModel().equals(envelope.path("model").asText()))safe.put("model",cfg.getChatModel());
            com.fasterxml.jackson.databind.node.ObjectNode usage=safe.putObject("usage");
            for(String key:new String[]{"prompt_tokens","completion_tokens","total_tokens"}) {JsonNode n=envelope.path("usage").get(key);if(n!=null&&n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=0)usage.put(key,n.longValue());}
        }catch(RuntimeException invalid) {/* Raw hash remains valid when the answer envelope is rejected. */}
        safe.put("usageUnavailable",!safe.path("usage").isObject()||safe.path("usage").isEmpty());safe.put("scope","provider_response_reported");return safe;
    }

    private String safeCall(Supplier<String> request) {
        try {return redactContent(request.get());}
        catch(IncompleteModelResponseException e) {
            String raw=redactRaw(e.getRawResponse());JsonNode envelope=null;
            try {envelope=JsonUtils.parse(raw);}catch(RuntimeException ignored) {/* Preserve invalid-envelope failure. */}
            JsonNode content=e.getModelContent()==null?null:JsonUtils.mapper().getNodeFactory().textNode(redactContent(e.getModelContent()));
            throw new IncompleteModelResponseException(redact(e.getMessage()),raw,content,e.getFailureKind(),envelope);
        }catch(BizException e){
            java.util.regex.Matcher status=java.util.regex.Pattern.compile("\\bHTTP\\s*([1-5][0-9]{2})\\b").matcher(e.getMessage()==null?"":e.getMessage());
            throw new BizException(e.getCode(),status.find()?"Qwen chat HTTP"+status.group(1):"Qwen chat transport failed");
        }catch(RuntimeException e){throw new BizException(5001,"Qwen chat transport failed ("+e.getClass().getSimpleName()+")");}
    }
    private String redactRaw(String raw){return redactJsonText(raw,true);}
    private String redactContent(String content){return redactJsonText(content,false);}
    /** Decode one business JSON value; partial content retains the existing literal policy. */
    private String redactJsonText(String raw,boolean providerEnvelope) {
        try(com.fasterxml.jackson.core.JsonParser parser=JsonUtils.mapper().getFactory().createParser(raw)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode parsed=JsonUtils.mapper().readTree(parser);
            if(parsed==null||parser.nextToken()!=null)throw new java.io.IOException("Not one provider JSON value");
            JsonNode safe=redactJson(parsed);
            if(providerEnvelope&&safe.path("choices").isArray())for(JsonNode choice:safe.path("choices")) {
                JsonNode message=choice.path("message"),content=message.path("content");
                if(message.isObject()&&content.isTextual())((com.fasterxml.jackson.databind.node.ObjectNode)message).put("content",redactContent(content.textValue()));
            }
            if(!parsed.equals(safe))return JsonUtils.write(safe);
        }catch(java.io.IOException|RuntimeException invalid){if(providerEnvelope)return "[UNTRUSTED_PROVIDER_RESPONSE_REDACTED]";}
        return redact(raw);
    }
    private JsonNode redactJson(JsonNode node) {
        if(node.isTextual())return JsonUtils.mapper().getNodeFactory().textNode(redact(node.textValue()));
        if(node.isObject()){com.fasterxml.jackson.databind.node.ObjectNode safe=JsonUtils.mapper().createObjectNode();
            node.fields().forEachRemaining(field->safe.set(redact(field.getKey()),redactJson(field.getValue())));return safe;}
        if(node.isArray()){com.fasterxml.jackson.databind.node.ArrayNode safe=JsonUtils.mapper().createArrayNode();for(JsonNode member:node)safe.add(redactJson(member));return safe;}
        return node;
    }
    private String redact(String text){return text==null||JsonUtils.isBlankText(cfg.getApiKey())?text:text.replace(cfg.getApiKey(),"[REDACTED]");}
}
