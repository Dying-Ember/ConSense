package com.consense.service.llm;

import com.consense.domain.QwenGenerationAction;
import com.consense.repository.QwenGenerationActionRepository;
import com.consense.common.JsonUtils;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QwenGenerationLedgerTest {
    @Test void preflightRequestBytesSurviveBothTerminalLedgerStates() {
        QwenGenerationActionRepository actions=mock(QwenGenerationActionRepository.class);PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());QwenGenerationLedger ledger=new QwenGenerationLedger(actions,manager);
        String identity=com.consense.common.Utf8.sha256("AUTHORED identity"),actionId="authored:bytes";
        QwenGenerationAction action=new QwenGenerationAction();action.setId(actionId);action.setIdentitySha256(identity);action.setDeadlineEpochMs(System.currentTimeMillis()+60000);
        action.setMaxPreflights(2);action.setMaxAnswers(1);action.setMaxTotal(3);action.setReservationsJson("[]");
        when(actions.lockAction(actionId)).thenReturn(Optional.of(action));
        QwenGenerationLedger.Reservation completed=ledger.reserve(actionId,identity,"planner",com.consense.common.Utf8.sha256("first"),false);
        ledger.dispatch(completed);ledger.complete(completed,Map.of("probeRequestBytes",192587,"untrustedExtra","discard"));
        QwenGenerationLedger.Reservation unknown=ledger.reserve(actionId,identity,"final",com.consense.common.Utf8.sha256("second"),false);
        ledger.dispatch(unknown);ledger.unknown(unknown,Map.of("probeRequestBytes",2097152,"failureKind","HTTP413"));
        com.fasterxml.jackson.databind.JsonNode rows=JsonUtils.parse(action.getReservationsJson());
        assertEquals(192587,rows.get(0).path("receipt").path("probeRequestBytes").asInt());
        assertFalse(rows.get(0).path("receipt").has("untrustedExtra"));
        assertEquals(2097152,rows.get(1).path("receipt").path("probeRequestBytes").asInt());
        assertEquals("HTTP413",rows.get(1).path("receipt").path("failureKind").asText());
    }
    @Test void changingThePreflightPhaseCannotRepayForTheSameDurableInput() {
        QwenGenerationActionRepository actions=mock(QwenGenerationActionRepository.class);PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());QwenGenerationLedger ledger=new QwenGenerationLedger(actions,manager);
        String identity=com.consense.common.Utf8.sha256("AUTHORED identity"),input=com.consense.common.Utf8.sha256("AUTHORED same input");
        QwenGenerationAction action=new QwenGenerationAction();action.setId("authored:no-replay");action.setIdentitySha256(identity);action.setDeadlineEpochMs(System.currentTimeMillis()+60000);action.setMaxPreflights(3);action.setMaxAnswers(3);action.setMaxTotal(6);action.setPreflights(1);action.setTotal(1);
        action.setReservationsJson("[{\"phase\":\"planner\",\"kind\":\"usage_preflight_generation\",\"state\":\"COMPLETED\",\"inputSha256\":\""+input+"\"}]");when(actions.lockAction(action.getId())).thenReturn(Optional.of(action));
        IllegalStateException replay=assertThrows(IllegalStateException.class,()->ledger.reserve(action.getId(),identity,"final",input,false));
        assertEquals("qwen_generation_already_reserved_no_replay",replay.getMessage());assertEquals(1,action.getTotal());verify(actions,never()).saveAndFlush(any());
        assertNotNull(ledger.reserve(action.getId(),identity,"answer",input,true));assertEquals(2,action.getTotal());assertEquals(1,action.getAnswers());
    }
    @Test void aCeilingHasAnExplicitTypeWithoutChangingTheMessageOrRefillingTheLedger() {
        QwenGenerationActionRepository actions=mock(QwenGenerationActionRepository.class);PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());QwenGenerationLedger ledger=new QwenGenerationLedger(actions,manager);
        String identity=com.consense.common.Utf8.sha256("AUTHORED identity"),input=com.consense.common.Utf8.sha256("AUTHORED next input");
        QwenGenerationAction action=new QwenGenerationAction();action.setId("authored:ceiling");action.setIdentitySha256(identity);action.setDeadlineEpochMs(System.currentTimeMillis()+60000);action.setMaxPreflights(1);action.setMaxAnswers(1);action.setMaxTotal(1);action.setPreflights(1);action.setAnswers(0);action.setTotal(1);action.setReservationsJson("[{\"phase\":\"earlier\",\"inputSha256\":\"earlier\"}]");when(actions.lockAction(action.getId())).thenReturn(Optional.of(action));
        QwenGenerationLedger.ActionBudgetExhaustedException exhausted=assertThrows(QwenGenerationLedger.ActionBudgetExhaustedException.class,()->ledger.reserve(action.getId(),identity,"planner",input,false));assertEquals("qwen_generation_ceiling_exceeded",exhausted.getMessage());assertTrue(exhausted instanceof IllegalStateException);assertEquals(1,action.getTotal());verify(actions,never()).saveAndFlush(any());
        action.setDeadlineEpochMs(0);IllegalStateException deadline=assertThrows(IllegalStateException.class,()->ledger.reserve(action.getId(),identity,"planner",input,false));assertEquals("qwen_action_deadline_exceeded",deadline.getMessage());assertFalse(deadline instanceof QwenGenerationLedger.ActionBudgetExhaustedException);verify(actions,never()).saveAndFlush(any());
    }
}
