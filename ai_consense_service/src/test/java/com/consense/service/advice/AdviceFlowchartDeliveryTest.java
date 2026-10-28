package com.consense.service.advice;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.config.ConsenseProperties;
import com.consense.domain.AdviceSession;
import com.consense.domain.ChatMessage;
import com.consense.repository.AdviceSessionRepository;
import com.consense.repository.ChatMessageRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.web.dto.AdviceDtos.AskRequest;
import com.consense.web.dto.AdviceDtos.AskResponseVO;
import com.consense.web.dto.AdviceDtos.CitationVO;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real history-write and post-save publication sequence with detached authored carriers; no SQL or provider. */
class AdviceFlowchartDeliveryTest {
    private static final String PROJECT="authored-project",SESSION="authored-session",SCOPE="fullset";
    private static final String QUESTION="Draw the tender opening procedure as a flowchart.";
    private static final String GRAPH="{\"tool\":\"draw_flowchart\",\"nodes\":[{\"id\":\"opening\",\"label\":\"Open Envelope 1\"},{\"id\":\"decision\",\"label\":\"Check technical threshold\"}],\"edges\":[{\"from\":\"opening\",\"to\":\"decision\",\"label\":\"\"}],\"citations\":[\"[1]\"]}";

    @Test void ourHistoryIncrementDoesNotInvalidateTheGuardedDraftDelivery()throws Exception {
        Fixture f=new Fixture();f.guard.run();f.persist();
        assertEquals(1,f.stored.getRevision());
        assertDoesNotThrow(f::publish);
        assertEquals(1,f.captured.getRevision());
        assertEquals(1,f.delivered.size());assertTrue(f.delivered.get(0).contains("```mermaid\nflowchart TD"));
        assertEquals(2,f.saved.size());assertNotNull(f.response.getMessageId());
        assertEquals(2,f.guardChecks);assertEquals(1,f.sourceBoundaryChecks);
    }

    @Test void anExternalSameSessionChangeAfterOurWriteStillRejectsDelivery()throws Exception {
        Fixture f=new Fixture();f.persist();assertEquals(1,f.captured.getRevision());
        f.stored.setRevision(2);
        BizException rejected=assertThrows(BizException.class,f::publish);
        assertEquals(4202,rejected.getCode());assertTrue(f.delivered.isEmpty());assertEquals(1,f.captured.getRevision());
    }

    @Test void aSharedReadAndWriteCarrierIsNotAdvancedTwice()throws Exception {
        Fixture f=new Fixture(false);f.persist();
        assertEquals(1,f.stored.getRevision());assertEquals(1,f.captured.getRevision());
        assertDoesNotThrow(f::publish);assertEquals(1,f.delivered.size());
    }

    @Test void anExternalSameSessionChangeBeforeOurWriteStillRejectsTheCompareAndSet()throws Exception {
        Fixture f=new Fixture();f.stored.setRevision(1);
        BizException rejected=assertThrows(BizException.class,f::persist);
        assertEquals(4202,rejected.getCode());assertTrue(f.saved.isEmpty());assertEquals(0,f.captured.getRevision());
    }

    @Test void aFailedTransactionDoesNotAdvanceTheCapturedRevision()throws Exception {
        Fixture f=new Fixture();doThrow(new TransactionSystemException("Authored commit failure")).when(f.transactions).commit(any());
        assertThrows(TransactionSystemException.class,f::persist);
        assertEquals(0,f.captured.getRevision());assertTrue(f.delivered.isEmpty());
    }

    @Test void thePostWriteSourceBoundaryStillRunsAndCanRejectDelivery()throws Exception {
        Fixture f=new Fixture();f.persist();f.sourceCurrent=false;
        BizException rejected=assertThrows(BizException.class,f::publish);
        assertEquals(4204,rejected.getCode());assertEquals(1,f.sourceBoundaryChecks);assertTrue(f.delivered.isEmpty());
    }

    @Test void thePostWriteModelGuardStillRejectsAChangedModel()throws Exception {
        Fixture f=new Fixture();f.persist();f.modelCurrent=false;
        BizException rejected=assertThrows(BizException.class,f::publish);
        assertEquals(4205,rejected.getCode());assertTrue(f.delivered.isEmpty());
    }

    private static final class Fixture {
        final AdviceSession stored=session(0),captured;
        final AdviceSessionRepository sessions=mock(AdviceSessionRepository.class);
        final ChatMessageRepository messages=mock(ChatMessageRepository.class);
        final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
        final AdviceService service;
        final AskResponseVO response=new AskResponseVO();
        final List<ChatMessage> saved=new ArrayList<>();final List<String> delivered=new ArrayList<>();
        final Object context,publication;final Runnable guard;final String sourceSnapshot;
        int guardChecks,sourceBoundaryChecks;boolean sourceCurrent=true,modelCurrent=true;
        Fixture()throws Exception {this(true);}
        Fixture(boolean detachedReads)throws Exception {
            when(transactions.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
            when(sessions.findById(SESSION)).thenAnswer(call->Optional.of(detachedReads?session(stored.getRevision()):stored));
            when(sessions.lockSession(PROJECT,SESSION)).thenReturn(Optional.of(stored));
            when(sessions.save(any())).thenAnswer(call->call.getArgument(0));
            AtomicLong ids=new AtomicLong();when(messages.save(any())).thenAnswer(call->{ChatMessage message=call.getArgument(0);if(message.getId()==null){message.setId(ids.incrementAndGet());saved.add(message);}return message;});
            SourceDocumentRepository documents=mock(SourceDocumentRepository.class);
            when(documents.findBusinessSourcesByProjectId(PROJECT)).thenReturn(Collections.emptyList());
            service=new AdviceService(null,null,null,null,new ConsenseProperties(),documents,null,messages,null,null,transactions);
            ReflectionTestUtils.setField(service,"adviceSessions",sessions);ReflectionTestUtils.setField(service,"entityManager",mock(EntityManager.class));
            sourceSnapshot=ReflectionTestUtils.invokeMethod(service,"conversationSourceIdentity",PROJECT,SCOPE,false,null);
            captured=ReflectionTestUtils.invokeMethod(service,"requireConversation",PROJECT,SESSION);
            if(detachedReads)assertNotSame(stored,captured,"The read and locked-write entities must be detached carriers, as in production");
            Class<?> contextType=Class.forName(AdviceService.class.getName()+"$QuestionContext");
            Constructor<?> contextConstructor=contextType.getDeclaredConstructor(String.class,List.class);contextConstructor.setAccessible(true);
            context=contextConstructor.newInstance(QUESTION,Collections.emptyList());
            guard=()->{guardChecks++;if(!modelCurrent)throw new BizException(4205,"Authored model changed");ReflectionTestUtils.invokeMethod(service,"assertConversationCurrent",captured);};
            Runnable boundary=()->{sourceBoundaryChecks++;if(!sourceCurrent)throw new BizException(4204,"Authored source changed");};
            AdviceProgress progress=new AdviceProgress(){@Override public boolean streaming(){return true;}@Override public void section(String content,List<String> citations,List<CitationVO> sources){delivered.add(content);}};
            CitationVO source=new CitationVO();source.setEvidenceId("[1]");
            Class<?> publicationType=Class.forName(AdviceService.class.getName()+"$Publication");
            Constructor<?> publicationConstructor=publicationType.getDeclaredConstructor(AdviceService.class,List.class,boolean.class,AdviceProgress.class,Runnable.class,Runnable.class,contextType);publicationConstructor.setAccessible(true);
            publication=publicationConstructor.newInstance(service,Collections.singletonList(source),false,progress,guard,boundary,context);
            AdviceFlowchartTool.Result draft=AdviceFlowchartTool.render(GRAPH,Collections.singletonList("[1]"));
            ReflectionTestUtils.setField(publication,"draftFlowchart",draft);
            response.setGrounded(false);response.setBasisScope("insufficient");response.setSourceScope(SCOPE);response.setContent(LocalizedText.same(draft.content(false)));response.setCitations(draft.getCitations());response.setSources(Collections.singletonList(source));response.setClarifications(Collections.emptyList());
        }
        void persist(){AskRequest request=new AskRequest(QUESTION,SCOPE,null,"project_contract",SESSION);ReflectionTestUtils.invokeMethod(service,"persistExchange",PROJECT,SCOPE,QUESTION,request,context,response,null,Collections.emptyList(),null,captured,sourceSnapshot,JsonUtils.write(Collections.singletonMap("applicationTool","draw_flowchart")),(Runnable)()->{},false);}
        void publish(){ReflectionTestUtils.invokeMethod(publication,"publishValidated");}
        static AdviceSession session(long revision){AdviceSession row=new AdviceSession();row.setId(SESSION);row.setProjectId(PROJECT);row.setScope(SCOPE);row.setBasisScope("project_contract");row.setRevision(revision);return row;}
    }
}
