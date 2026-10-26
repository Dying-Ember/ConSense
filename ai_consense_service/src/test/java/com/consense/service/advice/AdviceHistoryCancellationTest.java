package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.domain.AdviceSession;
import com.consense.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Optional history is bounded locally; no history-only model or token-count request exists. */
class AdviceHistoryCancellationTest {
    @Test void keepsWholeRecentPairsAndDropsOnlyOversizedOldHistoryWithoutTransport() {
        AdviceResponsesTransportTest.Fixture fixture=new AdviceResponsesTransportTest.Fixture();
        fixture.settings.setHistoryMaxExchanges(2);fixture.settings.setHistoryMaxMessages(4);fixture.settings.setHistoryMaxTokens(1024);
        ChatMessageRepository messages=mock(ChatMessageRepository.class);
        String identity=JsonUtils.write(fixture.selection.getIdentity());
        ChatMessageRepository.ContextView oldUser=row(1L,"user",null,"Old question",identity);
        ChatMessageRepository.ContextView oldAnswer=row(2L,"assistant",1L,String.join("",Collections.nCopies(1000,"\u4e2d")),identity);
        ChatMessageRepository.ContextView user=row(3L,"user",null,"Recent question",identity);
        ChatMessageRepository.ContextView assistant=row(4L,"assistant",3L,"Recent complete answer",identity);
        when(messages.findHistoryContext(anyString(),anyString(),any())).thenReturn(Arrays.asList(assistant,user,oldAnswer,oldUser));
        AdviceService advice=new AdviceService(null,null,null,null,fixture.props,null,null,messages,null,null,null);
        ReflectionTestUtils.setField(advice,"adviceSettings",fixture.settings);
        String material=ReflectionTestUtils.invokeMethod(advice,"boundedHistory",session(),AdviceResponsesTransportTest.SOURCE,fixture.selection.getIdentity(),(Runnable)()->{});
        assertTrue(material.contains("Recent question"));assertTrue(material.contains("Recent complete answer"));assertFalse(material.contains("Old question"));
        assertTrue(material.getBytes(StandardCharsets.UTF_8).length<=992);
        verify(messages).findHistoryContext(eq("project"),eq("session"),any());verifyNoMoreInteractions(messages);
        assertEquals(1000,oldAnswer.getContent().length());
    }
    @Test void cancellationGuardStillStopsLocalHistoryAssembly() {
        AdviceResponsesTransportTest.Fixture fixture=new AdviceResponsesTransportTest.Fixture();
        ChatMessageRepository messages=mock(ChatMessageRepository.class);String identity=JsonUtils.write(fixture.selection.getIdentity());
        List<ChatMessageRepository.ContextView> history=Arrays.asList(row(2L,"assistant",1L,"Answer",identity),row(1L,"user",null,"Question",identity));
        when(messages.findHistoryContext(anyString(),anyString(),any())).thenReturn(history);
        AdviceService advice=new AdviceService(null,null,null,null,fixture.props,null,null,messages,null,null,null);
        ReflectionTestUtils.setField(advice,"adviceSettings",fixture.settings);
        assertThrows(CancellationException.class,()->ReflectionTestUtils.invokeMethod(advice,"boundedHistory",session(),AdviceResponsesTransportTest.SOURCE,fixture.selection.getIdentity(),(Runnable)()->{throw new CancellationException("Authored cancellation");}));
    }
    private AdviceSession session(){AdviceSession session=new AdviceSession();session.setId("session");session.setProjectId("project");session.setScope("contract");session.setBasisScope("project_contract");return session;}
    private ChatMessageRepository.ContextView row(Long id,String role,Long question,String content,String identity){
        ChatMessageRepository.ContextView row=mock(ChatMessageRepository.ContextView.class);
        when(row.getId()).thenReturn(id);when(row.getRole()).thenReturn(role);when(row.getQuestionMessageId()).thenReturn(question);
        when(row.getSourceScope()).thenReturn("contract");when(row.getBasisScope()).thenReturn("project_contract");
        when(row.getSourceSnapshotSha256()).thenReturn(AdviceResponsesTransportTest.SOURCE);when(row.getContent()).thenReturn(content);when(row.getModelIdentityJson()).thenReturn(identity);return row;
    }
}
