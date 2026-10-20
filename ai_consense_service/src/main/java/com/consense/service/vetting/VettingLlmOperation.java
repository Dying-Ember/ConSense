package com.consense.service.vetting;

import com.consense.ai.LlmOperation;
import com.consense.ai.ModelIdentity;
import com.consense.config.ConsenseProperties;

/** Vetting-only wire choice, captured before queue dispatch; shared Chat profiles remain unchanged. */
public final class VettingLlmOperation {
    private final ModelIdentity identity;
    private final VettingResponsesTransport.Operation responses;
    private final com.consense.service.llm.QwenChatUsagePreflight.Operation qwen;
    private VettingLlmOperation(ModelIdentity identity,VettingResponsesTransport.Operation responses){this(identity,responses,null);}
    private VettingLlmOperation(ModelIdentity identity,VettingResponsesTransport.Operation responses,com.consense.service.llm.QwenChatUsagePreflight.Operation qwen){this.identity=identity;this.responses=responses;this.qwen=qwen;}
    public ModelIdentity getIdentity(){return identity;}
    public VettingResponsesTransport.Operation getResponses(){return responses;}
    public boolean isResponses(){return responses!=null;}
    public com.consense.service.llm.QwenChatUsagePreflight.Operation getQwen(){return qwen;}
    public boolean isQwen(){return qwen!=null;}
    static VettingLlmOperation capture(LlmOperation chat,ConsenseProperties props,VettingResponsesTransport transport) {
        return capture(chat,props,transport,null);
    }
    static VettingLlmOperation capture(LlmOperation chat,ConsenseProperties props,VettingResponsesTransport transport,com.consense.service.llm.QwenChatUsagePreflight usage) {
        if(!props.getVetting().isSemanticEnabled())return new VettingLlmOperation(chat==null?null:chat.getIdentity(),null);
        if(chat!=null&&chat.isExplicit()&&java.util.Arrays.asList("qwen","qwen-flash").contains(chat.getIdentity().getProfileId())) {
            if(usage==null)throw new IllegalStateException("Qwen usage-preflight component unavailable");
            com.consense.service.llm.QwenChatUsagePreflight.Operation selected=usage.capture(chat,"vetting");
            return new VettingLlmOperation(selected.getIdentity(),null,selected);
        }
        boolean explicit=chat!=null&&chat.isExplicit();
        boolean minimax=explicit&&"minimax-cn".equals(chat.getIdentity().getProfileId());
        String wire=props.getVetting().getMinimaxCnWire();
        if(minimax&&!"chat-completions".equals(wire)&&!"responses".equals(wire))throw new IllegalArgumentException("Vetting minimax-cn wire unsupported");
        boolean selected=minimax?"responses".equals(wire):!explicit&&props.getVetting().getResponses().isEnabled();
        if(!selected)return new VettingLlmOperation(chat==null?null:chat.getIdentity(),null);
        if(transport==null)throw new IllegalStateException("Vetting Responses transport unavailable");
        String effort=null;
        if(minimax) {
            String mode=chat.getStructuredOpenAiThinkingMode();
            if("disabled".equals(mode))effort="none";
            else if("adaptive".equals(mode))effort="high";
            else throw new IllegalArgumentException("Vetting Responses thinking mode unknown");
        }
        VettingResponsesTransport.Operation responses=transport.capture(minimax?"minimax-cn":"vetting-responses",effort);
        if(minimax&&(!chat.getIdentity().getModel().equals(responses.getIdentity().getModel())||chat.getStructuredMaxTokens()!=responses.getOutputReserveTokens()))
            throw new IllegalArgumentException("Vetting Responses model or output reserve differs from selected operation");
        return new VettingLlmOperation(responses.getIdentity(),responses);
    }
}
