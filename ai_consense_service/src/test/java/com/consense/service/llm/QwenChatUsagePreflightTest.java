package com.consense.service.llm;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QwenChatUsagePreflightTest {
    @Test void englishFileExportUsesItsOwnFinitePaidActionBudget() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"file-export");
        try(QwenChatUsagePreflight.Operation.ActionScope ignored=op.openAction("file-export:authored",()->{})) { }
        org.mockito.ArgumentCaptor<QwenGenerationLedger.Limits> limits=org.mockito.ArgumentCaptor.forClass(QwenGenerationLedger.Limits.class);
        verify(ledger).open(eq("file-export:authored"),anyString(),limits.capture());
        assertEquals(24,limits.getValue().preflights);assertEquals(24,limits.getValue().answers);assertEquals(48,limits.getValue().total);
    }
    private final ConsenseProperties props=new ConsenseProperties();
    private final HttpSupport http=mock(HttpSupport.class);
    private final QwenGenerationLedger ledger=mock(QwenGenerationLedger.class);
    private final QwenChatUsagePreflight usage=new QwenChatUsagePreflight(props,ledger);
    private static final String SOURCE=VettingCorpus.hash("AUTHORED SOURCE ONLY");
    private LlmOperation chat(String id) {
        props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED-QWEN-SECRET");
        props.getQwenFlash().setEnabled(true);props.getQwenFlash().setApiKey("AUTHORED-QWEN-SECRET");
        return new LlmProfiles(props,mock(LlmClient.class),mock(LlmClient.class),new QwenChatClient(props.getQwen(),http),new QwenChatClient(props.getQwenFlash(),http)).resolve(id);
    }
    private static String envelope(String model,int cap,long prompt,String content) {
        com.fasterxml.jackson.databind.node.ObjectNode n=JsonUtils.mapper().createObjectNode();n.put("object","chat.completion");n.put("id",UUID.randomUUID().toString());n.put("model",model);
        com.fasterxml.jackson.databind.node.ObjectNode c=n.putArray("choices").addObject();c.put("index",0);c.put("finish_reason",cap==1?"length":"stop");c.putObject("message").put("role","assistant").put("content",content);
        n.putObject("usage").put("prompt_tokens",prompt).put("completion_tokens",1).put("total_tokens",prompt+1);return JsonUtils.write(n);
    }
    private void answer() {
        doAnswer(call->{JsonNode body=JsonUtils.parse(call.getArgument(1));return envelope(body.path("model").asText(),body.path("max_tokens").asInt(),42,body.path("max_tokens").asInt()==1?"{":"[]");})
                .when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
    }
    @Test void bothProfilesSealOnlyCapAndStreamDifferencesAndReserveBeforeDispatch() {
        answer();for(String profile:Arrays.asList("qwen","qwen-flash")) {
            QwenChatUsagePreflight.Operation op=usage.capture(chat(profile),"vetting");
            try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:"+profile,()->{})) {
                JsonNode schema=JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}");
                VettingInputBudget.Input in=op.input("SYSTEM AUTHORED","WHOLE SOURCE AUTHORED",schema,SOURCE);
                VettingInputBudget.Result b=VettingInputBudget.check(in,input->op.observe(input,"final",()->{}));
                assertTrue(b.isExtraDispatchPermitted());assertFalse(b.getObservation().isCompleteChatTemplateAndSchemaObserved());assertNull(b.getObservation().getTokenizerIdentitySha256());
                assertEquals(0,op.complete(in,b,"SYSTEM AUTHORED","WHOLE SOURCE AUTHORED",schema,Map.class,new ArrayList<>(),new LinkedHashMap<>(),null).size());
            }
        }
        org.mockito.InOrder order=inOrder(ledger,http);order.verify(ledger).open(eq("test:qwen"),anyString(),any());order.verify(ledger).reserve(eq("test:qwen"),anyString(),eq("final"),anyString(),eq(false));order.verify(ledger).dispatch(any());order.verify(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
        org.mockito.ArgumentCaptor<String> bodies=org.mockito.ArgumentCaptor.forClass(String.class);verify(http,times(4)).postJsonBoundedWithoutReplay(anyString(),bodies.capture(),anyLong(),anyString(),anyInt(),any());
        for(int i=0;i<4;i+=2){com.fasterxml.jackson.databind.node.ObjectNode probe=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(bodies.getAllValues().get(i));com.fasterxml.jackson.databind.node.ObjectNode actual=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(bodies.getAllValues().get(i+1));assertEquals(1,probe.path("max_tokens").asInt());assertEquals(16384,actual.path("max_tokens").asInt());probe.remove(Arrays.asList("max_tokens","stream"));actual.remove(Arrays.asList("max_tokens","stream"));assertEquals(actual,probe);}
    }
    @Test void identicalFinalInputReusesPaidObservationAcrossPlannerFinalAndRepair() {
        answer();QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"vetting");
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:stable",()->{})) {
            VettingInputBudget.Input in=op.input("SYS","WHOLE",JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}"),SOURCE);
            VettingInputBudget.Result a=VettingInputBudget.check(in,i->op.observe(i,"planner",()->{})),b=VettingInputBudget.check(in,i->op.observe(i,"final",()->{}));
            assertEquals(a.getObservation().getQwenUsageEstimate().getResponseSha256(),b.getObservation().getQwenUsageEstimate().getResponseSha256());assertNotSame(a.getObservation(),b.getObservation());assertTrue(QwenChatUsagePreflight.sameObservation(a,b));
            b.getObservation().setInputTokens(43);assertFalse(QwenChatUsagePreflight.sameObservation(a,b));assertTrue(QwenChatUsagePreflight.sameRetryPolicy(a,b));
            VettingInputBudget.Result repair=VettingInputBudget.check(in,i->op.observe(i,"repair",()->{}));assertTrue(QwenChatUsagePreflight.sameObservation(a,repair));assertEquals(42,repair.getObservation().getInputTokens());
        }
        verify(http,times(1)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
        verify(ledger,times(1)).reserve(anyString(),anyString(),eq("planner"),anyString(),eq(false));verify(ledger,times(1)).complete(any(),anyMap());
        verify(ledger,never()).reserve(anyString(),anyString(),eq("final"),anyString(),eq(false));verify(ledger,never()).reserve(anyString(),anyString(),eq("repair"),anyString(),eq(false));
    }
    @Test void changedPromptSchemaSourceAndActionCannotBorrowTheObservation() {
        answer();QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"vetting");JsonNode schema=JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}");
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:bindings",()->{})) {
            VettingInputBudget.Input input=op.input("SYS","WHOLE",schema,SOURCE);op.observe(input,"planner",()->{});
            VettingInputBudget.Input changedBudget=VettingInputBudget.copy(input);changedBudget.setOutputReserveTokens(32768);VettingInputBudget.rebind(changedBudget);assertThrows(IllegalArgumentException.class,()->op.observe(changedBudget,"final",()->{}));
            op.observe(op.input("SYS","CHANGED",schema,SOURCE),"final",()->{});
            op.observe(op.input("SYS","WHOLE",JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"),SOURCE),"final",()->{});
            op.observe(op.input("SYS","WHOLE",schema,VettingCorpus.hash("CHANGED SOURCE")),"final",()->{});
        }
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:other-action",()->{})) {op.observe(op.input("SYS","WHOLE",schema,SOURCE),"final",()->{});}
        verify(http,times(5)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());verify(ledger,times(5)).reserve(anyString(),anyString(),anyString(),anyString(),eq(false));
    }
    @Test void reusedObservationStillChecksSourcesDeadlineCancellationAndConfiguration() {
        answer();QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"vetting");Runnable sourceGuard=mock(Runnable.class),callGuard=mock(Runnable.class);
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:guards",sourceGuard)) {
            VettingInputBudget.Input in=op.input("SYS","WHOLE",JsonUtils.parse("{\"type\":\"array\"}"),SOURCE);op.observe(in,"planner",callGuard);clearInvocations(sourceGuard,callGuard);
            op.observe(in,"final",callGuard);verify(sourceGuard,atLeast(2)).run();verify(callGuard,atLeast(2)).run();
            assertThrows(IllegalStateException.class,()->op.observe(in,"INVALID PHASE",callGuard));
            Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->op.observe(in,"interrupted",callGuard));}finally{Thread.interrupted();}
            doThrow(new IllegalStateException("source changed")).when(callGuard).run();assertThrows(IllegalStateException.class,()->op.observe(in,"source-changed",callGuard));reset(callGuard);
            doThrow(new IllegalStateException("qwen_action_deadline_exceeded")).when(ledger).open(eq("test:guards"),anyString(),any());assertThrows(IllegalStateException.class,()->op.observe(in,"expired",callGuard));
            doNothing().when(ledger).open(eq("test:guards"),anyString(),any());double temperature=props.getQwen().getTemperature();props.getQwen().setTemperature(0.92);assertThrows(IllegalStateException.class,()->op.observe(in,"config-changed",callGuard));props.getQwen().setTemperature(temperature);
            assertThrows(java.util.concurrent.CancellationException.class,()->op.observe(in,"cancelled",callGuard,Runnable::run));assertThrows(java.util.concurrent.CancellationException.class,()->op.observe(in,"cancelled-again",callGuard));
        }
        verify(http,times(1)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());verify(ledger,times(1)).reserve(anyString(),anyString(),anyString(),anyString(),eq(false));
    }
    @Test void malformedUsageCannotPopulateTheReusableObservation() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"vetting");when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenReturn(envelope("qwen3.8-flash",1,42,"{"));
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:never-cache-unknown",()->{})) {
            VettingInputBudget.Input in=op.input("SYS","WHOLE",JsonUtils.parse("{\"type\":\"array\"}"),SOURCE);
            assertFalse(VettingInputBudget.check(in,i->op.observe(i,"planner",()->{})).isExtraDispatchPermitted());assertFalse(VettingInputBudget.check(in,i->op.observe(i,"final",()->{})).isExtraDispatchPermitted());
        }
        verify(http,times(2)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());verify(ledger,times(2)).unknown(any(),anyMap());verify(ledger,never()).complete(any(),anyMap());
    }
    @Test void transportFailureCannotPopulateTheReusableObservation() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"vetting");when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenThrow(new IllegalStateException("AUTHORED timeout"));
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:never-cache-transport",()->{})) {
            VettingInputBudget.Input in=op.input("SYS","WHOLE",JsonUtils.parse("{\"type\":\"array\"}"),SOURCE);
            assertFalse(VettingInputBudget.check(in,i->op.observe(i,"planner",()->{})).isExtraDispatchPermitted());assertFalse(VettingInputBudget.check(in,i->op.observe(i,"final",()->{})).isExtraDispatchPermitted());
        }
        verify(http,times(2)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());verify(ledger,times(2)).unknown(any(),anyMap());verify(ledger,never()).complete(any(),anyMap());
    }
    @Test void aCompletedReceiptWithAFailedExitGuardIsNotReused() {
        answer();QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"vetting");java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();Runnable guard=()->{if(calls.incrementAndGet()==4)throw new IllegalStateException("AUTHORED source changed after receipt");};
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:exit-guard",()->{})) {
            VettingInputBudget.Input in=op.input("SYS","WHOLE",JsonUtils.parse("{\"type\":\"array\"}"),SOURCE);
            assertFalse(VettingInputBudget.check(in,i->op.observe(i,"planner",guard)).isExtraDispatchPermitted());assertTrue(VettingInputBudget.check(in,i->op.observe(i,"final",guard)).isExtraDispatchPermitted());
        }
        verify(http,times(2)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());verify(ledger,times(2)).complete(any(),anyMap());verify(ledger,never()).unknown(any(),anyMap());
    }
    @Test void malformedOrWrongModelUsageStaysUnknownAndCannotDispatchAnswer() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"vetting");
        when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenReturn(envelope("qwen3.8-flash",1,42,"{"));
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:wrongmodel",()->{})) {
            VettingInputBudget.Input in=op.input("S","U",JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}"),SOURCE);
            VettingInputBudget.Result b=VettingInputBudget.check(in,i->op.observe(i,"final",()->{}));assertEquals("budget_unknown",b.getStatus());assertFalse(b.isExtraDispatchPermitted());verify(ledger).unknown(any(),anyMap());verify(ledger,never()).reserve(anyString(),anyString(),eq("answer"),anyString(),eq(true));
        }
    }
    @Test void declaredContextMarginRejectsLargeUsageWithoutClaimingExact() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen-flash"),"advice",5);
        when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenReturn(envelope("qwen3.8-flash",1,950849,"{"));
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:large",()->{})) {
            QwenChatUsagePreflight.PreparedRequest req=op.prepareChat(Arrays.asList(LlmClient.ChatTurn.system("S"),LlmClient.ChatTurn.user("WHOLE")),JsonUtils.mapper().createObjectNode(),SOURCE,false);
            VettingInputBudget.Result b=op.observe(req,"final",()->{},null);assertEquals("over_budget",b.getStatus());assertFalse(b.isExtraDispatchPermitted());assertNull(b.getObservation().getEffectiveContextIdentitySha256());assertThrows(IllegalArgumentException.class,()->op.generate(req,b,"answer",()->{},null,null));
        }
    }
    @Test void diagnosticLengthNeverAuthorizesIncompleteBusinessOutputOrAutomaticRetry() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"advice",0);
        when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenReturn(envelope("qwen3.8-27b",1,42,"{"));
        try(QwenChatUsagePreflight.Operation.ActionScope action=op.openAction("test:business-length",()->{})) {
            QwenChatUsagePreflight.PreparedRequest request=op.prepareChat(Arrays.asList(LlmClient.ChatTurn.system("S"),LlmClient.ChatTurn.user("WHOLE")),JsonUtils.mapper().createObjectNode(),SOURCE,false);
            VettingInputBudget.Result b=op.observe(request,"final",()->{},null);assertTrue(b.isExtraDispatchPermitted());
            assertThrows(IncompleteModelResponseException.class,()->op.generate(request,b,"answer",()->{},null,null));verify(http,times(2)).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());verify(ledger).unknown(any(),anyMap());
        }
    }
    @Test void disablingOrRemovingCredentialsAfterCaptureRejectsBeforeDispatch() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"advice",0);props.getQwen().setEnabled(false);
        assertThrows(IllegalStateException.class,()->op.openAction("test:disabled",()->{}));props.getQwen().setEnabled(true);props.getQwen().setApiKey("");
        assertThrows(IllegalStateException.class,op::assertCurrent);verifyNoInteractions(http,ledger);
    }
    @Test void profileAndPolicyChangesRejectBeforeAnyGeneration() {
        QwenChatUsagePreflight.Operation op=usage.capture(chat("qwen"),"advice",5);props.getQwenUsagePreflight().setActionLifetimeMs(1000);
        assertThrows(IllegalStateException.class,()->op.openAction("test:drift",()->{}));verifyNoInteractions(http,ledger);
        props.getQwenUsagePreflight().setActionLifetimeMs(7200000);props.getQwen().setTemperature(0.92);assertThrows(IllegalStateException.class,op::assertCurrent);verifyNoInteractions(http,ledger);
    }
}
