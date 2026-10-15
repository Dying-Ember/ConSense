package com.consense.web;

import com.consense.ai.LlmOperation;
import com.consense.ai.LlmProfiles;
import com.consense.common.ApiResponse;
import com.consense.common.BizException;
import com.consense.service.advice.AdviceProgress;
import com.consense.service.advice.AdviceService;
import com.consense.web.dto.AdviceDtos.AskRequest;
import com.consense.web.dto.AdviceDtos.AskResponseVO;
import com.consense.web.dto.AdviceDtos.CitationVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request-scoped events; the existing AdviceService owns evidence gates and atomic history. */
@RestController
@RequestMapping("/api/advice/{projectId}/ask/stream")
public class AdviceStreamController {
    private final AdviceService advice;
    private final LlmProfiles profiles;
    private final Map<String, Job> requests = new ConcurrentHashMap<>();
    private final Map<String, Job> sessions = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "advice-stream"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public AdviceStreamController(AdviceService advice, LlmProfiles profiles) { this.advice = advice; this.profiles = profiles; }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@PathVariable String projectId,
                         @RequestHeader("X-ConSense-Advice-Request") String requestId,
                         @RequestBody AskRequest request) {
        if (!requestId.matches("[A-Za-z0-9_-]{16,100}") || request.getSessionId() == null || request.getSessionId().trim().isEmpty()
                || request.getQuestion() == null || request.getQuestion().trim().isEmpty())
            throw new BizException(4201, "A nonempty question, bound session and unique request ID are required.");
        LlmOperation operation = profiles.capture();
        Job job = new Job(projectId, request.getSessionId(), requestId);
        if (requests.putIfAbsent(requestId, job) != null) throw new BizException(4201, "This consultation request is already running.");
        String sessionKey = projectId + "\u0000" + request.getSessionId();
        if (sessions.putIfAbsent(sessionKey, job) != null) {
            requests.remove(requestId, job); throw new BizException(4201, "A question is already being processed in this session.");
        }
        job.cleanup = () -> { requests.remove(requestId, job); sessions.remove(sessionKey, job); if (job.future instanceof Runnable) executor.remove((Runnable) job.future); };
        job.emitter.onCompletion(() -> job.cancel(false));
        job.emitter.onTimeout(() -> job.cancel(true));
        job.emitter.onError(error -> job.cancel(false));
        try {
            job.phase("queued");
            Future<?> future = executor.submit(() -> {
                try (LlmProfiles.Scope ignored = profiles.bind(operation)) {
                    job.checkCancelled();
                    AskResponseVO result = advice.ask(projectId, request, job);
                    job.checkCancelled();
                    job.committed.set(true);
                    job.send("complete", Collections.singletonMap("response", result));
                    job.emitter.complete();
                } catch (CancellationException stopped) {
                    job.error(4201, "Consultation stopped. Completion was not confirmed; check this session's history before retrying.");
                } catch (RuntimeException failure) {
                    job.error(failure instanceof BizException ? ((BizException) failure).getCode() : 5002,
                            failure instanceof BizException ? failure.getMessage() : "Consultation failed before completion. Please retry or check the backend logs.");
                } finally { requests.remove(requestId, job); sessions.remove(sessionKey, job); }
            });
            job.future = future;
            if (job.cancelled.get()) { future.cancel(true); job.cleanup.run(); }
        } catch (RuntimeException failure) {
            requests.remove(requestId, job); sessions.remove(sessionKey, job); job.cancel(true);
            if (failure instanceof RejectedExecutionException) throw new BizException(4201, "The consultation queue is full. Please retry later.");
            throw failure;
        }
        return job.emitter;
    }

    @PostMapping("/{requestId}/stop")
    public ApiResponse<Void> stop(@PathVariable String projectId, @PathVariable String requestId, @RequestParam String sessionId) {
        Job job = requests.get(requestId);
        if (job != null) {
            if (!job.projectId.equals(projectId) || !job.sessionId.equals(sessionId)) throw new BizException(4201, "The stop request does not match this project and session.");
            job.cancel(true);
        }
        return ApiResponse.ok();
    }

    @PreDestroy public void shutdown() { requests.values().forEach(job -> job.cancel(true)); executor.shutdownNow(); }

    private static final class Job implements AdviceProgress {
        private final String projectId, sessionId, requestId;
        private final SseEmitter emitter = new SseEmitter(600000L);
        private final AtomicBoolean cancelled = new AtomicBoolean(), committed = new AtomicBoolean();
        private final List<Runnable> cancellations = new CopyOnWriteArrayList<>();
        private volatile Future<?> future;
        private Runnable cleanup = () -> { };
        private Job(String projectId, String sessionId, String requestId) { this.projectId = projectId; this.sessionId = sessionId; this.requestId = requestId; }
        @Override public boolean streaming() { return true; }
        @Override public void checkCancelled() { if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Consultation stopped"); }
        @Override public void phase(String phase) { checkCancelled(); send("phase", Collections.singletonMap("phase", phase)); }
        @Override public void section(String content, List<String> citations, List<CitationVO> sources) {
            checkCancelled(); Map<String, Object> body = new LinkedHashMap<>(); body.put("content", content); body.put("citations", citations); body.put("sources", sources); send("section", body);
        }
        @Override public void onCancel(Runnable action) {
            if (cancelled.get()) { action.run(); return; }
            cancellations.add(action);
            if (cancelled.get() && cancellations.remove(action)) action.run();
        }
        private void send(String event, Map<String, ?> body) {
            Map<String, Object> value = new LinkedHashMap<>(body); value.put("requestId", requestId);
            try { emitter.send(SseEmitter.event().name(event).data(value, MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException disconnected) { cancel(false); throw new CancellationException("Consultation connection closed"); }
        }
        private void error(int code, String message) {
            if (committed.get()) { emitter.complete(); return; }
            Map<String, Object> body = new LinkedHashMap<>(); body.put("code", code); body.put("message", message);
            try { send("error", body); } catch (CancellationException ignored) { }
            emitter.complete();
        }
        private void cancel(boolean close) {
            if (!committed.get() && cancelled.compareAndSet(false, true)) {
                cancellations.forEach(action -> { try { action.run(); } catch (RuntimeException ignored) { } });
                Future<?> task = future; if (task != null) task.cancel(true);
                cleanup.run();
            }
            if (close) emitter.complete();
        }
    }
}
