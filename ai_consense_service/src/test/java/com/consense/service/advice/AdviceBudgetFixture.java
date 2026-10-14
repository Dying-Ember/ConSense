package com.consense.service.advice;

import com.consense.ai.LlmClient;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingInputBudget;
import com.consense.service.vetting.VettingTokenBudgetCliObserver;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Bound authored observations for isolated contract tests; never a real tokenizer or model acceptance. */
public final class AdviceBudgetFixture {
    private AdviceBudgetFixture() { }
    public static void bind(VettingTokenBudgetCliObserver counter,LlmClient... clients) {
        doAnswer(call->observation(call.getArgument(0))).when(counter).observe(any());
        for(LlmClient client:clients)doAnswer(call->contract(client.chat(call.getArgument(0)))).when(client).chatStructured(anyList(),any());
    }
    /** Old authored source/scope fixtures now express the new protocol; dedicated harness tests use raw payloads. */
    public static String contract(String raw) {
        JsonNode old;
        try{old=JsonUtils.parse(raw);if(!old.isObject()||old.hasNonNull("sections")||!old.path("grounded").isBoolean())return raw;}
        catch(RuntimeException invalid){return raw;}
        ObjectNode answer=JsonUtils.mapper().createObjectNode();answer.put("grounded",old.path("grounded").asBoolean());answer.put("questionType","T5");
        String basis=old.path("basisScope").asText("project_contract");answer.put("basisScope",basis);answer.put("missingReason",old.path("missingReason").asText(""));
        answer.set("clarifications",old.hasNonNull("clarifications")?old.path("clarifications"):JsonUtils.mapper().createArrayNode());ArrayNode sections=answer.putArray("sections");
        sections.add(section("limits","limits","AUTHORED facts, assumptions and evidence limits TEST ONLY","insufficient",JsonUtils.mapper().createArrayNode()));
        if(!old.path("answer").asText("").isEmpty())sections.add(section("analysis","analysis",old.path("answer").asText(),basis,old.has("citations")?old.path("citations"):JsonUtils.mapper().createArrayNode()));
        if(!old.path("missingReason").asText("").isEmpty())sections.add(section("unknown","uncertainty",old.path("missingReason").asText(),"insufficient",JsonUtils.mapper().createArrayNode()));
        return JsonUtils.write(answer);
    }
    private static ObjectNode section(String id,String kind,String text,String basis,JsonNode refs){
        ObjectNode section=JsonUtils.mapper().createObjectNode();section.put("id",id);section.put("kind",kind);section.put("content",text);section.put("basisScope",basis);section.set("citations",refs);
        ArrayNode checks=section.putArray("checks");for(int i=1;i<=10;i++){
            ObjectNode check=checks.addObject();check.put("ruleId","R"+i);check.put("status",i==1||i==10||("analysis".equals(kind)&&i==2)?"pass":"not_applicable");
            check.put("reason","AUTHORED isolated protocol check TEST ONLY; no model or legal acceptance");check.set("citations",("analysis".equals(kind)&&i==2)?refs:JsonUtils.mapper().createArrayNode());}
        return section;
    }
    public static VettingInputBudget.Observation observation(VettingInputBudget.Input input) {
        VettingInputBudget.Observation result=new VettingInputBudget.Observation();result.setModel(input.getModel());result.setSerializedInputSha256(input.getSerializedInputSha256());
        result.setTokenizerIdentitySha256(VettingCorpus.hash("AUTHORED TOKENIZER TEST ONLY"));result.setChatTemplateIdentitySha256(VettingCorpus.hash("AUTHORED TEMPLATE TEST ONLY"));result.setEffectiveContextIdentitySha256(VettingCorpus.hash("AUTHORED CONTEXT TEST ONLY"));
        result.setCompleteChatTemplateAndSchemaObserved(true);result.setOutputReserveTokens(input.getOutputReserveTokens());result.setEffectiveContextTokens(262144);result.setInputTokens(1000);return result;
    }
}
