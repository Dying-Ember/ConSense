package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.domain.AdviceSession;
import com.consense.domain.ChatMessage;
import com.consense.repository.ChatMessageRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual owned loopback counter, no provider/model/DB. Stop must release a blocked history count. */
class AdviceHistoryCancellationTest {
    @Test void followUpHistoryCounterUsesTheRequestCancellationCallback() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            AdviceResponsesTransportTest.Fixture fixture=new AdviceResponsesTransportTest.Fixture();fixture.settings.getResponses().setConfiguredContextTokens(262144);
            String local="http://127.0.0.1:"+server.getAddress().getPort();AdviceResponsesTransport.NativeHttp nativeHttp=new AdviceResponsesTransport.NativeHttp();
            AdviceResponsesTransport.WireHttp wire=new AdviceResponsesTransport.WireHttp(){
                public AdviceResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String auth){throw new AssertionError("History count must register request cancellation");}
                public AdviceResponsesTransport.Response postCancelable(String endpoint,String body,long timeout,int cap,String auth,Consumer<Runnable> cancel){return nativeHttp.postCancelable(local,body,timeout,cap,auth,cancel);}
            };
            AdviceResponsesTransport.Operation operation=new AdviceResponsesTransport(fixture.props,fixture.settings,wire).capture(fixture.selection);
            AdviceSession session=new AdviceSession();session.setId("session");session.setProjectId("project");session.setScope("contract");session.setBasisScope("project_contract");
            ChatMessage user=new ChatMessage();user.setId(1L);user.setRole("user");user.setSourceScope("contract");user.setBasisScope("project_contract");user.setSourceSnapshotSha256(AdviceResponsesTransportTest.SOURCE);user.setContent("Earlier user question");
            ChatMessage assistant=new ChatMessage();assistant.setId(2L);assistant.setRole("assistant");assistant.setQuestionMessageId(1L);assistant.setSourceScope("contract");assistant.setSourceSnapshotSha256(AdviceResponsesTransportTest.SOURCE);assistant.setModelIdentityJson(JsonUtils.write(operation.getIdentity()));assistant.setContent("Earlier answer");
            ChatMessageRepository messages=mock(ChatMessageRepository.class);
            when(messages.findByProjectIdAndSessionIdOrderByIdDesc(anyString(),anyString(),any())).thenReturn(new ArrayList<>(Arrays.asList(assistant,user)));
            AdviceService advice=new AdviceService(null,null,null,null,fixture.props,null,null,messages,null,null,null);
            ReflectionTestUtils.setField(advice,"adviceSettings",fixture.settings);AtomicReference<Runnable> cancel=new AtomicReference<>();
            AdviceProgress progress=new AdviceProgress(){public void onCancel(Runnable action){cancel.set(action);}};
            Future<?> pending=executor.submit(()->ReflectionTestUtils.invokeMethod(advice,"boundedHistory",session,AdviceResponsesTransportTest.SOURCE,operation.getIdentity(),null,operation,null,(Runnable)()->{},progress));
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertNotNull(cancel.get());cancel.get().run();
            ExecutionException failure=assertThrows(ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));
            assertInstanceOf(AdviceResponsesTransport.RequestFailure.class,failure.getCause());assertEquals("transport_failed",((AdviceResponsesTransport.RequestFailure)failure.getCause()).getStatus());
            assertTrue(((AdviceResponsesTransport.RequestFailure)failure.getCause()).getReason().startsWith("http_"));
        } finally {release.countDown();executor.shutdownNow();server.stop(0);}
    }
}
