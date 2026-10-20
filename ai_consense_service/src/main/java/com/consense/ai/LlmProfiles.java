package com.consense.ai;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Safe server-owned profile catalogue. Configuration presence is not inference or quota proof. */
@Component
public class LlmProfiles {
    public static final String HEADER="X-ConSense-Llm-Profile";
    private final ConsenseProperties props;
    private final Map<String,LlmOperation> selections=new LinkedHashMap<>();
    private final LlmOperation legacy;
    private final ThreadLocal<LlmOperation> active=new ThreadLocal<>();
    /** Compatibility constructor for adapter-only callers without the additional chat beans. */
    public LlmProfiles(ConsenseProperties props,LlmClient local,LlmClient miniMax){this(props,local,miniMax,null,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public LlmProfiles(ConsenseProperties props,@Qualifier("llmClient") LlmClient local,@Qualifier("miniMaxChatClient") LlmClient miniMax,
                       @Qualifier("qwenChatClient") LlmClient qwen,@Qualifier("qwenFlashChatClient") LlmClient qwenFlash) {
        this.props=props;selections.put("local",operation("local",props.getLlm(),local,true));
        selections.put("minimax-cn",operation("minimax-cn",configuration(props,"minimax-cn"),miniMax,true));
        selections.put("qwen",operation("qwen",configuration(props,"qwen"),qwen,true));
        selections.put("qwen-flash",operation("qwen-flash",configuration(props,"qwen-flash"),qwenFlash,true));
        legacy=operation("local",props.getLlm(),local,false);
    }
    public LlmOperation resolve(String id){return id==null?legacy:selections.get(id);}
    public LlmOperation capture(){LlmOperation selected=active.get();return selected==null?legacy:selected;}
    public Scope bind(LlmOperation selected){LlmOperation previous=active.get();active.set(selected);return new Scope(previous);}
    public final class Scope implements AutoCloseable {
        private final LlmOperation previous;private Scope(LlmOperation previous){this.previous=previous;}
        @Override public void close(){if(previous==null)active.remove();else active.set(previous);}
    }
    private LlmOperation operation(String id,ConsenseProperties.Llm cfg,LlmClient client,boolean explicit) {
        String reason=unavailableReason(id,cfg);if(reason==null&&client==null)reason="adapter_unavailable";
        return new LlmOperation(explicit,client,new ModelIdentity(id,cfg.getProvider(),cfg.getChatModel(),configurationSha256(cfg),"configured_profile"),reason,cfg);
    }
    /** All configured-profile consumers use the same exact server-owned lookup. */
    public static ConsenseProperties.Llm configuration(ConsenseProperties props,String id) {
        if("local".equals(id))return props.getLlm();
        if("minimax-cn".equals(id))return props.getMinimaxCn();
        if("qwen".equals(id))return props.getQwen();
        if("qwen-flash".equals(id))return props.getQwenFlash();
        throw new IllegalArgumentException("Unknown LLM profile: "+id);
    }
    /** Same safe structured-chat fingerprint as the established sealed local token-observer recipe. */
    public static String configurationSha256(ConsenseProperties.Llm cfg) {
        Map<String,Object> safe=new LinkedHashMap<>();safe.put("provider",cfg.getProvider());safe.put("baseUrl",cfg.getBaseUrl());safe.put("chatModel",cfg.getChatModel());
        safe.put("temperature",cfg.getTemperature());safe.put("numCtx",cfg.getNumCtx());safe.put("structuredThinking",cfg.getStructuredThinking());
        safe.put("structuredOpenAiThinkingMode",cfg.getStructuredOpenAiThinkingMode());safe.put("structuredOpenAiReasoningSplit",cfg.getStructuredOpenAiReasoningSplit());
        safe.put("structuredMaxTokens",cfg.getStructuredMaxTokens());safe.put("structuredRetry",0);
        if(cfg.getStructuredEnableThinking()!=null)safe.put("structuredEnableThinking",cfg.getStructuredEnableThinking());
        String hash;try {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(JsonUtils.write(safe).getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b&0xff));hash=out.toString();}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
        return hash;
    }
    public boolean supported(String id){return selections.containsKey(id);}
    public Map<String,Object> metadata() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("defaultProfile","local");
        List<Map<String,Object>> profiles=new ArrayList<>();for(String id:selections.keySet())profiles.add(profile(id,configuration(props,id)));
        out.put("profiles",profiles);return out;
    }
    private Map<String,Object> profile(String id,ConsenseProperties.Llm cfg) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",id);out.put("provider",cfg.getProvider());out.put("model",cfg.getChatModel());
        String reason=unavailableReason(id,cfg);if(reason==null&&selections.get(id).getClient()==null)reason="adapter_unavailable";out.put("configured",reason==null);if(reason!=null)out.put("unavailableReason",reason);return out;
    }
    public String unavailableReason(String id,ConsenseProperties.Llm cfg) {
        if(!cfg.isEnabled())return "disabled";
        if(JsonUtils.isBlankText(cfg.getBaseUrl())||JsonUtils.isBlankText(cfg.getChatModel()))return "missing_configuration";
        if(!"local".equals(id)&&JsonUtils.isBlankText(cfg.getApiKey()))return "missing_api_key";
        return null;
    }
}
