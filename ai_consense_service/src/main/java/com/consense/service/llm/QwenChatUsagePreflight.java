package com.consense.service.llm;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingInputBudget;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Paid cap1 Chat generation policy. It is neither a native exact observer nor a no-generation counter. */
@Component
public final class QwenChatUsagePreflight {
    public static final String VERSION="qwen-chat-usage-preflight-v1";
    public static final String KIND="provider_reported_usage_preflight";
    public static final String SCOPE="configured_qwen_chat_usage_preflight_not_exact_template_observation";
    public static final long CONTEXT=1000000,MARGIN=32768;
    public static final int OUTPUT=16384;
    private final ConsenseProperties props;
    private final QwenGenerationLedger ledger;
    public QwenChatUsagePreflight(ConsenseProperties props,QwenGenerationLedger ledger){this.props=props;this.ledger=ledger;}
    @Data public static class WireIdentity {
        private String version,kind,profileSha256,policySha256,generationEntitySha256,preflightEntitySha256,commonInputSha256;
        private long configuredContextTokens,safetyMarginTokens;
        private int outputReserveTokens;
        private String contextEvidenceScope="configured_operation_limit_not_measured_provider_maximum";
    }
    @Data public static class UsageEstimate {
        private String kind,version,profileSha256,policySha256,generationEntitySha256,preflightEntitySha256,commonInputSha256,responseSha256;
        private long configuredContextTokens,safetyMarginTokens,completionTokens;
        private String modelIdentityScope="exact_response_model_observed",finishReason;
    }
    public boolean selected(LlmOperation chat){return chat!=null&&chat.isExplicit()&&chat.getIdentity()!=null&&Arrays.asList("qwen","qwen-flash").contains(chat.getIdentity().getProfileId());}
    public Operation capture(LlmOperation chat,String module){return capture(chat,module,0);}
    public Operation capture(LlmOperation chat,String module,int historyCandidateLimit) {
        if(!selected(chat))throw new IllegalArgumentException("Qwen explicit captured profile required");
        return new Operation(chat,module,historyCandidateLimit);
    }
    private QwenGenerationLedger.Limits limits(String module,int h) {
        ConsenseProperties.QwenUsagePreflight p=props.getQwenUsagePreflight();
        if(!p.isEnabled())throw new IllegalStateException("qwen_usage_preflight_policy_disabled");
        if("vetting".equals(module))return new QwenGenerationLedger.Limits(p.getVettingMaxPreflights(),p.getVettingMaxAnswers(),p.getVettingMaxTotal(),p.getActionLifetimeMs());
        if("file-export".equals(module))return new QwenGenerationLedger.Limits(24,24,48,p.getActionLifetimeMs());
        if(!"advice".equals(module)||h<0||h>100)throw new IllegalArgumentException("Qwen bounded action scope required");
        return new QwenGenerationLedger.Limits(h+2,2,h+4,p.getActionLifetimeMs());
    }
    private String policySha(String module,int h) {
        QwenGenerationLedger.Limits l=limits(module,h);
        return hash(JsonUtils.write(Arrays.asList(VERSION,KIND,CONTEXT,OUTPUT,MARGIN,module,h,l.preflights,l.answers,l.total,l.lifetimeMs,
                "cap1_is_real_generation_no_replay_no_refund","exact_response_model_required","complete_input_output_margin_policy")));
    }
    public final class Operation {
        private final LlmOperation chat;
        private final QwenChatClient client;
        private final String module,profileSha,policySha;
        private final int historyLimit;
        private final QwenGenerationLedger.Limits limits;
        private final ModelIdentity identity;
        private final Map<String,PreparedRequest> prepared=new LinkedHashMap<>();
        private final ThreadLocal<ActionContext> action=new ThreadLocal<>();
        private Operation(LlmOperation selected,String scope,int history) {
            chat=selected;module=scope;historyLimit=history;profileSha=profileIdentity(LlmProfiles.configuration(props,chat.getIdentity().getProfileId()));policySha=policySha(scope,history);limits=limits(scope,history);
            if(!(chat.getClient() instanceof QwenChatClient))throw new IllegalArgumentException("Qwen prepared adapter required");client=(QwenChatClient)chat.getClient();
            identity=new ModelIdentity(chat.getIdentity().getProfileId(),"qwen-chat-completions",chat.getIdentity().getModel(),hash(profileSha+"|"+policySha),"configured_qwen_usage_preflight_policy");assertCurrent();
        }
        public ModelIdentity getIdentity(){return identity;}
        public String getPolicySha256(){return policySha;}
        public void assertObservationCredential(String candidate) {
            assertCurrent();
            if(!client.matchesCredential(candidate))throw new IllegalStateException("qwen_observation_credential_differs");
        }
        public int getOutputReserveTokens(){return OUTPUT;}
        public boolean supportsStreaming(){return client.supportsStreaming();}
        public void assertCurrent() {
            ConsenseProperties.Llm c=LlmProfiles.configuration(props,chat.getIdentity().getProfileId());
            String required="qwen".equals(chat.getIdentity().getProfileId())?"qwen3.8-27b":"qwen3.8-flash";
            if(!c.isEnabled()||JsonUtils.isBlankText(c.getApiKey())||!profileSha.equals(profileIdentity(c))||!policySha.equals(policySha(module,historyLimit))||!required.equals(c.getChatModel())
                    ||c.getStructuredMaxTokens()!=OUTPUT||!Boolean.FALSE.equals(c.getStructuredEnableThinking())||c.getStructuredOpenAiThinkingMode()!=null
                    ||c.getStructuredOpenAiReasoningSplit()!=null||c.getTimeoutMs()<=0||c.getTimeoutMs()>limits.lifetimeMs||chat.getUnavailableReason()!=null)
                throw new IllegalStateException("qwen_captured_profile_or_policy_changed");
        }
        public ActionScope openAction(String id,Runnable guard) {
            Objects.requireNonNull(guard,"Qwen current-source guard required");guard.run();assertCurrent();ledger.open(id,identity.getConfigurationSha256(),limits);guard.run();assertCurrent();
            ActionContext previous=action.get();action.set(new ActionContext(id,guard));return new ActionScope(previous);
        }
        public final class ActionScope implements AutoCloseable {
            private final ActionContext previous;private ActionScope(ActionContext p){previous=p;}
            @Override public void close(){if(previous==null)action.remove();else action.set(previous);}
        }
        public VettingInputBudget.Input input(String system,String user,JsonNode schema,String sourceSha) {
            VettingInputBudget.Input in=VettingInputBudget.input("qwen-chat-completions",identity.getModel(),profileSha,system,user,schema,OUTPUT,sourceSha);
            return seal(in,false).input;
        }
        /** Advice supplies its actual final session/schema/retry envelope exactly once. */
        public PreparedRequest prepareChat(List<LlmClient.ChatTurn> turns,JsonNode schema,String sourceSha,boolean stream) {
            VettingInputBudget.Input in=VettingInputBudget.chatInput("qwen-chat-completions",identity.getModel(),profileSha,turns,OUTPUT,sourceSha);
            in.setSchema(schema.deepCopy());VettingInputBudget.rebind(in);return seal(in,stream);
        }
        private PreparedRequest seal(VettingInputBudget.Input in,boolean stream) {
            assertCurrent();if(!VettingInputBudget.validBinding(in)||in.getWire()!=null||in.getQwenWire()!=null||!sha(in.getSourceSnapshotSha256()))throw new IllegalArgumentException("Qwen complete input binding required");
            QwenChatClient.PreparedRequest bytes=client.prepare(in.getMessages(),in.getSchema(),stream);WireIdentity wire=new WireIdentity();
            wire.setVersion(VERSION);wire.setKind(KIND);wire.setProfileSha256(profileSha);wire.setPolicySha256(policySha);
            wire.setGenerationEntitySha256(hash(bytes.getGenerationJson()));wire.setPreflightEntitySha256(hash(bytes.getProbeJson()));
            ObjectNode common=strict(bytes.getGenerationJson());common.remove(Arrays.asList("max_tokens","stream"));wire.setCommonInputSha256(hash(JsonUtils.write(Arrays.asList(common,in.getSchema(),in.getSourceSnapshotSha256()))));
            wire.setConfiguredContextTokens(CONTEXT);wire.setOutputReserveTokens(OUTPUT);wire.setSafetyMarginTokens(MARGIN);VettingInputBudget.bindQwenWire(in,wire);
            PreparedRequest request=new PreparedRequest(in,bytes);PreparedRequest prior=prepared.get(in.getSerializedInputSha256());
            if(prior!=null){if(!prior.inputJson.equals(request.inputJson))throw new IllegalArgumentException("Qwen prepared input changed");return prior;}
            prepared.put(in.getSerializedInputSha256(),request);return request;
        }
        private PreparedRequest bound(VettingInputBudget.Input in) {
            assertCurrent();PreparedRequest request=in==null?null:prepared.get(in.getSerializedInputSha256());
            if(request==null||!request.inputJson.equals(JsonUtils.write(in))||!VettingInputBudget.validBinding(in))throw new IllegalArgumentException("Qwen sealed request binding changed");return request;
        }
        private ActionContext current(Runnable guard) {
            ActionContext context=action.get();if(context==null)throw new IllegalStateException("qwen_durable_action_required");
            context.checkCancelled();context.guard.run();if(guard!=null)guard.run();assertCurrent();context.checkCancelled();return context;
        }
        public VettingInputBudget.Observation observe(VettingInputBudget.Input in,String phase,Runnable guard){return observe(in,phase,guard,null);}
        public VettingInputBudget.Observation observe(VettingInputBudget.Input in,String phase,Runnable guard,Consumer<Runnable> cancel) {
            PreparedRequest request=bound(in);ActionContext context=current(guard);
            if(phase==null||!phase.matches("[a-z][a-z0-9-]{0,47}"))throw new IllegalStateException("qwen_reservation_binding_required");
            String saved=context.observations.get(in.getSerializedInputSha256());
            if(saved!=null) {
                if(cancel!=null)cancel.accept(()->context.cancelled.set(true));
                current(guard);ledger.open(context.id,identity.getConfigurationSha256(),limits);assertCurrent();context.checkCancelled();
                return JsonUtils.read(saved,VettingInputBudget.Observation.class);
            }
            QwenGenerationLedger.Reservation reservation=ledger.reserve(context.id,identity.getConfigurationSha256(),phase,in.getSerializedInputSha256(),false);
            String raw=null;boolean terminal=false;
            try {
                current(guard);ledger.dispatch(reservation);raw=client.usageProbe(request.bytes,cancel);current(guard);
                JsonNode envelope=strict(raw),choice=envelope.path("choices").path(0),usage=envelope.path("usage");
                if(!"chat.completion".equals(envelope.path("object").asText())||!identity.getModel().equals(envelope.path("model").asText())||envelope.hasNonNull("error")
                        ||!envelope.path("choices").isArray()||envelope.path("choices").size()!=1||!choice.isObject()
                        ||!choice.path("index").isIntegralNumber()||!choice.path("index").canConvertToInt()||choice.path("index").intValue()!=0
                        ||!Arrays.asList("stop","length").contains(choice.path("finish_reason").asText())||!choice.path("message").isObject()
                        ||!"assistant".equals(choice.path("message").path("role").asText())||!choice.path("message").path("content").isTextual()||choice.path("message").hasNonNull("function_call")
                        ||present(choice.path("message").get("tool_calls"))||present(choice.path("message").get("refusal"))
                        ||!integer(usage.get("prompt_tokens"),true)||!integer(usage.get("completion_tokens"),false)||usage.path("completion_tokens").longValue()>1)
                    throw new IllegalStateException("qwen_preflight_envelope_or_usage_invalid");
                long input=usage.path("prompt_tokens").longValue();long completion=usage.path("completion_tokens").longValue();
                if(usage.has("total_tokens")&&(!integer(usage.get("total_tokens"),false)||usage.path("total_tokens").longValue()!=Math.addExact(input,completion)))throw new IllegalStateException("qwen_preflight_total_usage_invalid");
                VettingInputBudget.Observation out=new VettingInputBudget.Observation();out.setModel(identity.getModel());out.setSerializedInputSha256(in.getSerializedInputSha256());
                out.setInputTokens(input);out.setEffectiveContextTokens(CONTEXT);out.setOutputReserveTokens(OUTPUT);out.setScope(SCOPE);out.setCompleteChatTemplateAndSchemaObserved(false);
                WireIdentity w=in.getQwenWire();UsageEstimate estimate=new UsageEstimate();estimate.setKind(KIND);estimate.setVersion(VERSION);estimate.setProfileSha256(profileSha);estimate.setPolicySha256(policySha);
                estimate.setGenerationEntitySha256(w.getGenerationEntitySha256());estimate.setPreflightEntitySha256(w.getPreflightEntitySha256());estimate.setCommonInputSha256(w.getCommonInputSha256());
                estimate.setResponseSha256(hash(raw));estimate.setConfiguredContextTokens(CONTEXT);estimate.setSafetyMarginTokens(MARGIN);estimate.setCompletionTokens(completion);estimate.setFinishReason(choice.path("finish_reason").asText());out.setQwenUsageEstimate(estimate);
                Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("requestSha256",w.getPreflightEntitySha256());receipt.put("responseSha256",hash(raw));receipt.put("promptTokens",input);receipt.put("completionTokens",completion);
                if(usage.has("total_tokens"))receipt.put("totalTokens",usage.path("total_tokens").longValue());receipt.put("finishReason",estimate.getFinishReason());receipt.put("stableObservationSha256",hash(JsonUtils.write(stableObservation(out))));
                ledger.complete(reservation,receipt);terminal=true;current(guard);
                // Reuse token accounting only after the paid receipt and exit guards have succeeded.
                if(Arrays.asList("provider_estimated_fit","over_budget").contains(checkEstimate(in,out,new VettingInputBudget.Result()).getStatus())&&context.observations.size()<limits.preflights)
                    context.observations.put(in.getSerializedInputSha256(),JsonUtils.write(out));
                return out;
            }catch(RuntimeException error){if(!terminal){Map<String,Object> failure=new LinkedHashMap<>();failure.put("failureKind",error.getClass().getSimpleName());failure.put("requestSha256",in.getQwenWire().getPreflightEntitySha256());if(raw!=null)failure.put("responseSha256",hash(raw));ledger.unknown(reservation,failure);}throw error;}
        }
        public VettingInputBudget.Result observe(PreparedRequest request,String phase,Runnable guard,Consumer<Runnable> cancel) {
            return VettingInputBudget.check(request.input,in->observe(in,phase,guard,cancel));
        }
        public String generate(PreparedRequest request,VettingInputBudget.Result budget,String phase,Runnable guard,Consumer<String> delta,Consumer<Runnable> cancel) {return generate(request,budget,phase,guard,delta,cancel,null);}
        private String generate(PreparedRequest request,VettingInputBudget.Result budget,String phase,Runnable guard,Consumer<String> delta,Consumer<Runnable> cancel,Consumer<JsonNode> metadata) {
            bound(request.input);VettingInputBudget.Result checked=new VettingInputBudget.Result();checked.setSerializedInputSha256(request.input.getSerializedInputSha256());
            if(budget==null||!budget.isExtraDispatchPermitted()||!Objects.equals(budget.getSerializedInputSha256(),request.input.getSerializedInputSha256())||budget.getObservation()==null
                    ||!checkEstimate(request.input,budget.getObservation(),checked).isExtraDispatchPermitted())throw new IllegalArgumentException("Qwen bound usage-preflight acceptance required");
            ActionContext context=current(guard);QwenGenerationLedger.Reservation reservation=ledger.reserve(context.id,identity.getConfigurationSha256(),phase,request.input.getSerializedInputSha256(),true);boolean terminal=false;
            Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("requestSha256",request.input.getQwenWire().getGenerationEntitySha256());
            try {
                current(guard);ledger.dispatch(reservation);String text=client.generate(request.bytes,delta,cancel,safe->{
                    if(safe.path("responseSha256").isTextual())receipt.put("responseSha256",safe.path("responseSha256").textValue());
                    for(String[] key:new String[][]{{"prompt_tokens","promptTokens"},{"completion_tokens","completionTokens"},{"total_tokens","totalTokens"}}){JsonNode value=safe.path("usage").get(key[0]);if(value!=null&&value.isIntegralNumber()&&value.canConvertToLong())receipt.put(key[1],value.longValue());}
                    if(metadata!=null)metadata.accept(safe);
                });current(guard);
                ledger.complete(reservation,receipt);terminal=true;current(guard);return text;
            }catch(RuntimeException error){if(!terminal){receipt.put("failureKind",error.getClass().getSimpleName());ledger.unknown(reservation,receipt);}throw error;}
        }
        public <T> List<T> complete(VettingInputBudget.Input in,VettingInputBudget.Result budget,String system,String user,JsonNode schema,Class<T> type,List<String> rawOut,Map<String,Long> timings,Consumer<JsonNode> metadata) {
            PreparedRequest request=bound(in);VettingInputBudget.Input regenerated=input(system,user,schema,in.getSourceSnapshotSha256());
            if(!request.inputJson.equals(JsonUtils.write(regenerated)))throw new IllegalArgumentException("Qwen final prompts differ from sealed input");
            LlmClient preparedClient=new LlmClient(){
                public String chatStructured(List<ChatTurn> turns,JsonNode suppliedSchema){if(!JsonUtils.write(turns).equals(JsonUtils.write(in.getMessages()))||!schema.equals(suppliedSchema))throw new IllegalArgumentException("Qwen gateway envelope changed");return generate(request,budget,"answer",()->{},null,null,metadata);}
                public String chat(List<ChatTurn> turns){throw new UnsupportedOperationException();}
                public List<float[]> embed(List<String> texts){throw new UnsupportedOperationException();}
                public boolean available(){return true;}public String chatModel(){return identity.getModel();}public String embedModel(){return "";}
            };
            return new AiGateway(preparedClient).completeStructuredJsonList(system,user,type,schema,rawOut,timings);
        }
        private final class ActionContext {
            final String id;final Runnable guard;
            final Map<String,String> observations=new LinkedHashMap<>();
            final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            ActionContext(String id,Runnable guard){this.id=id;this.guard=guard;}
            void checkCancelled(){if(cancelled.get()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("qwen_action_cancelled");}
        }
    }
    public static final class PreparedRequest {
        private final VettingInputBudget.Input input;private final String inputJson;private final QwenChatClient.PreparedRequest bytes;
        private PreparedRequest(VettingInputBudget.Input input,QwenChatClient.PreparedRequest bytes){this.input=VettingInputBudget.copy(input);this.inputJson=JsonUtils.write(input);this.bytes=bytes;}
        public VettingInputBudget.Input getInput(){return VettingInputBudget.copy(input);}
        public String getGenerationJson(){return bytes.getGenerationJson();}public String getPreflightJson(){return bytes.getProbeJson();}
    }
    public static boolean isQwen(VettingInputBudget.Input in){return in!=null&&in.getQwenWire()!=null;}
    public static VettingInputBudget.Result checkEstimate(VettingInputBudget.Input in,VettingInputBudget.Observation o,VettingInputBudget.Result result) {
        WireIdentity w=in.getQwenWire();UsageEstimate e=o.getQwenUsageEstimate();
        if(w==null||e==null||!"qwen-chat-completions".equals(in.getProvider())||in.getWire()!=null||o.getProviderEstimate()!=null||!VERSION.equals(w.getVersion())||!KIND.equals(w.getKind())||!SCOPE.equals(o.getScope())
                ||o.isCompleteChatTemplateAndSchemaObserved()||o.getTokenizerIdentitySha256()!=null||o.getChatTemplateIdentitySha256()!=null||o.getEffectiveContextIdentitySha256()!=null
                ||!Objects.equals(in.getModel(),o.getModel())||!Objects.equals(in.getSerializedInputSha256(),o.getSerializedInputSha256())
                ||!Objects.equals(in.getProviderConfigurationSha256(),w.getProfileSha256())||!VERSION.equals(e.getVersion())||!KIND.equals(e.getKind())
                ||!sha(w.getProfileSha256())||!sha(w.getPolicySha256())||!sha(w.getGenerationEntitySha256())||!sha(w.getPreflightEntitySha256())||!sha(w.getCommonInputSha256())||!sha(e.getResponseSha256())
                ||!Objects.equals(w.getProfileSha256(),e.getProfileSha256())||!Objects.equals(w.getPolicySha256(),e.getPolicySha256())||!Objects.equals(w.getGenerationEntitySha256(),e.getGenerationEntitySha256())
                ||!Objects.equals(w.getPreflightEntitySha256(),e.getPreflightEntitySha256())||!Objects.equals(w.getCommonInputSha256(),e.getCommonInputSha256())
                ||w.getConfiguredContextTokens()!=CONTEXT||e.getConfiguredContextTokens()!=CONTEXT||w.getSafetyMarginTokens()!=MARGIN||e.getSafetyMarginTokens()!=MARGIN
                ||!"configured_operation_limit_not_measured_provider_maximum".equals(w.getContextEvidenceScope())||!"exact_response_model_observed".equals(e.getModelIdentityScope())
                ||!Arrays.asList("qwen3.8-27b","qwen3.8-flash").contains(o.getModel())||o.getInputTokens()<=0||o.getEffectiveContextTokens()!=CONTEXT
                ||o.getOutputReserveTokens()!=OUTPUT||in.getOutputReserveTokens()!=OUTPUT||w.getOutputReserveTokens()!=OUTPUT||e.getCompletionTokens()<0||e.getCompletionTokens()>1
                ||!Arrays.asList("stop","length").contains(e.getFinishReason())){result.setReason("qwen_preflight_identity_or_policy_unknown");return result;}
        if(o.getInputTokens()>CONTEXT-OUTPUT-MARGIN){result.setStatus("over_budget");result.setReason("qwen_usage_output_and_absolute_margin_exceed_configured_limit");return result;}
        result.setStatus("provider_estimated_fit");result.setReason("paid_qwen_usage_preflight_not_exact_template_observation");result.setExtraDispatchPermitted(true);return result;
    }
    public static Object policyIdentity(VettingInputBudget.Observation o) {
        if(o==null||o.getQwenUsageEstimate()==null)return null;UsageEstimate e=o.getQwenUsageEstimate();Map<String,Object> m=new LinkedHashMap<>();
        m.put("model",o.getModel());m.put("kind",e.getKind());m.put("version",e.getVersion());m.put("profileSha256",e.getProfileSha256());m.put("policySha256",e.getPolicySha256());
        m.put("context",o.getEffectiveContextTokens());m.put("outputReserve",o.getOutputReserveTokens());m.put("margin",e.getSafetyMarginTokens());m.put("modelIdentityScope",e.getModelIdentityScope());m.put("exact",o.isCompleteChatTemplateAndSchemaObserved());
        m.put("scope",o.getScope());m.put("tokenizerIdentitySha256",o.getTokenizerIdentitySha256());m.put("chatTemplateIdentitySha256",o.getChatTemplateIdentitySha256());m.put("effectiveContextIdentitySha256",o.getEffectiveContextIdentitySha256());return m;
    }
    private static Object stableObservation(VettingInputBudget.Observation o) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("policy",policyIdentity(o));if(o!=null&&o.getQwenUsageEstimate()!=null){UsageEstimate e=o.getQwenUsageEstimate();m.put("inputSha",o.getSerializedInputSha256());m.put("inputTokens",o.getInputTokens());m.put("generationEntitySha256",e.getGenerationEntitySha256());m.put("preflightEntitySha256",e.getPreflightEntitySha256());m.put("commonInputSha256",e.getCommonInputSha256());}return m;
    }
    public static boolean sameObservation(VettingInputBudget.Result a,VettingInputBudget.Result b) {
        return a!=null&&b!=null&&a.getObservation()!=null&&b.getObservation()!=null&&a.getObservation().getQwenUsageEstimate()!=null&&b.getObservation().getQwenUsageEstimate()!=null
                &&Objects.equals(a.getStatus(),b.getStatus())&&Objects.equals(a.getReason(),b.getReason())&&a.isExtraDispatchPermitted()==b.isExtraDispatchPermitted()
                &&Objects.equals(a.getSerializedInputSha256(),b.getSerializedInputSha256())&&JsonUtils.write(stableObservation(a.getObservation())).equals(JsonUtils.write(stableObservation(b.getObservation())));
    }
    public static boolean sameRetryPolicy(VettingInputBudget.Result a,VettingInputBudget.Result b) {
        return a!=null&&b!=null&&a.getObservation()!=null&&b.getObservation()!=null&&policyIdentity(a.getObservation())!=null
                &&JsonUtils.write(policyIdentity(a.getObservation())).equals(JsonUtils.write(policyIdentity(b.getObservation())));
    }
    private static boolean integer(JsonNode n,boolean positive){return n!=null&&n.isIntegralNumber()&&n.canConvertToLong()&&(positive?n.longValue()>0:n.longValue()>=0);}
    private static boolean present(JsonNode n){return n!=null&&!n.isNull()&&!(n.isArray()&&n.isEmpty())&&!(n.isTextual()&&n.textValue().isEmpty());}
    private static ObjectNode strict(String json) {
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(json)){parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode n=JsonUtils.mapper().readTree(parser);if(n==null||!n.isObject()||parser.nextToken()!=null)throw new IOException();return (ObjectNode)n;}
        catch(IOException|RuntimeException invalid){throw new IllegalStateException("qwen_preflight_strict_json_invalid");}
    }
    private static String profileIdentity(ConsenseProperties.Llm c){return hash(LlmProfiles.configurationSha256(c)+"|singleGenerationTimeoutMs|"+c.getTimeoutMs());}
    private static String hash(String text){return VettingCorpus.hash(text);}private static boolean sha(String text){return text!=null&&text.matches("[a-f0-9]{64}");}
}
