package com.consense.web;

import com.consense.ai.LlmClient;
import com.consense.ai.LlmOperation;
import com.consense.ai.LlmProfiles;
import com.consense.config.ConsenseProperties;
import com.consense.service.advice.AdviceProgress;
import com.consense.service.advice.AdviceService;
import com.consense.web.dto.AdviceDtos.AskRequest;
import com.consense.web.dto.AdviceDtos.AskResponseVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdviceStreamControllerTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String BODY = "{\"question\":\"Which clause applies?\",\"scope\":\"contract\",\"basisScope\":\"project_contract\",\"sessionId\":\"session-A\"}";
    private LlmProfiles profiles() { return new LlmProfiles(new ConsenseProperties(), mock(LlmClient.class), mock(LlmClient.class)); }

    @Test void streamDeadlineCoversOneTwentyMinuteQwenCallAndRemainsBounded() {
        AdviceStreamController controller = new AdviceStreamController(mock(AdviceService.class), profiles());
        try {
            AskRequest request = new AskRequest("Which clause applies?", "contract", null, "project_contract", "session-A");
            Long timeout = controller.ask("project-A", ID, request).getTimeout();
            assertNotNull(timeout);
            assertTrue(timeout > TimeUnit.MINUTES.toMillis(20));
            assertTrue(timeout <= TimeUnit.MINUTES.toMillis(30));
        } finally { controller.shutdown(); }
    }

    @Test void sourceFailureReasonSurvivesTheSseBoundaryWithoutItsPrivateCause() throws Exception {
        AdviceService advice=mock(AdviceService.class);LlmProfiles profiles=profiles();
        doThrow(new com.consense.common.BizException(4204,"Current canonical source bindings are unavailable for the selected advice scope. Complete source review and registration before asking.","source_unregistered",new IllegalStateException("AUTHORED_PRIVATE_CAUSE")))
                .when(advice).ask(anyString(),any(AskRequest.class),any(AdviceProgress.class));
        AdviceStreamController controller=new AdviceStreamController(advice,profiles);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        try {
            MvcResult pending=mvc.perform(post("/api/advice/project-A/ask/stream").header("X-ConSense-Advice-Request",ID).contentType("application/json").content(BODY)).andExpect(request().asyncStarted()).andReturn();
            String wire=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(wire.contains("event:error"));assertTrue(wire.contains("\"reason\":\"source_unregistered\""));assertTrue(wire.contains("\"code\":4204"));
            assertFalse(wire.contains("AUTHORED_PRIVATE_CAUSE"));assertFalse(wire.contains("event:complete"));
        } finally {controller.shutdown();}
    }

    @Test void eventsUseCapturedModelAndOnlyCompleteAfterServiceCommitReturns() throws Exception {
        AdviceService advice = mock(AdviceService.class); LlmProfiles profiles = profiles();
        LlmOperation selected = profiles.resolve("minimax-cn");
        AtomicBoolean returned = new AtomicBoolean();
        doAnswer(call -> {
            assertSame(selected, profiles.capture());
            AdviceProgress progress = call.getArgument(2); progress.phase("retrieving"); progress.section("Supported [1]", Collections.singletonList("[1]"), Collections.emptyList());
            AskResponseVO response = new AskResponseVO(); response.setSessionId("session-A"); response.setCitations(Collections.singletonList("[1]")); response.setSources(Collections.emptyList()); response.setGrounded(false);
            returned.set(true); return response;
        }).when(advice).ask(eq("project-A"), any(AskRequest.class), any(AdviceProgress.class));
        AdviceStreamController controller = new AdviceStreamController(advice, profiles);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try {
            MvcResult result;
            try (LlmProfiles.Scope ignored = profiles.bind(selected)) {
                result = mvc.perform(post("/api/advice/project-A/ask/stream").header("X-ConSense-Advice-Request", ID).contentType("application/json").content(BODY)).andExpect(request().asyncStarted()).andReturn();
            }
            String wire = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(returned.get()); assertTrue(wire.contains("event:phase")); assertTrue(wire.contains("event:section")); assertTrue(wire.contains("event:complete"));
            assertTrue(wire.indexOf("event:section") < wire.indexOf("event:complete")); assertTrue(wire.contains(ID)); assertTrue(wire.contains("\"grounded\":false"));
            assertNotSame(selected, profiles.capture());
        } finally { controller.shutdown(); }
    }

    @Test void stopCallsProviderCancellationAndReleasesSessionForAnotherRequest() throws Exception {
        AdviceService advice = mock(AdviceService.class); LlmProfiles profiles = profiles();
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        doAnswer(call -> {
            AdviceProgress progress = call.getArgument(2); progress.onCancel(cancelled::countDown); entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { interrupted.set(true); Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
            progress.checkCancelled(); return null;
        }).when(advice).ask(anyString(), any(AskRequest.class), any(AdviceProgress.class));
        AdviceStreamController controller = new AdviceStreamController(advice, profiles);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try {
            mvc.perform(post("/api/advice/project-A/ask/stream").header("X-ConSense-Advice-Request", ID).contentType("application/json").content(BODY)).andExpect(request().asyncStarted());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            mvc.perform(post("/api/advice/project-A/ask/stream/" + ID + "/stop").param("sessionId", "session-A")).andExpect(status().isOk());
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertFalse(interrupted.get(), "Stopping a request must not interrupt a worker that may be reading H2");
            mvc.perform(post("/api/advice/project-A/ask/stream").header("X-ConSense-Advice-Request", ID + "2").contentType("application/json").content(BODY)).andExpect(request().asyncStarted());
        } finally { release.countDown(); controller.shutdown(); }
    }
}
