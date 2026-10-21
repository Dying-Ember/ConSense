package com.consense.service.vetting;

import com.consense.ai.LlmProfiles;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.VettingRun;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.VettingDtos.RunResultVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored fatal errors on the real worker; mock persistence, no HTTP/model/OCR or exhausted heap. */
class VettingFatalErrorWorkflowTest {
    @Test void initializationErrorFailsRunCompletesWaiterAndStopsLeaseRenewal() throws Exception {
        try(Fixture f=new Fixture()) {
            Error original=new OutOfMemoryError("authored action initialization error");
            when(f.profiles.bind(isNull())).thenThrow(original);
            f.dispatch();f.assertFatal(original);
            assertEquals("FAILED",f.run.getStatus());assertEquals("NEEDS_RERUN",f.run.getRecoveryStatus());
            assertNull(f.run.getExecutionOwner());assertNull(f.run.getLeaseUntil());
            verify(f.runs,never()).findById(anyString());
            verify(f.semantic,never()).bindOperation(any());
        }
    }

    @Test void executionErrorFailsRunCompletesWaiterAndPropagatesTheSameError() throws Exception {
        try(Fixture f=new Fixture()) {
            Error original=new OutOfMemoryError("authored captured-run load error");
            when(f.runs.findById(f.run.getId())).thenThrow(original);
            f.dispatch();f.assertFatal(original);
            assertEquals("FAILED",f.run.getStatus());assertNotNull(f.run.getFinishedAt());
            verify(f.runs).lockById(f.run.getId());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void failurePersistenceCannotReplaceOriginalErrorOrKeepItsLeaseAlive(boolean secondaryIsError) throws Exception {
        try(Fixture f=new Fixture()) {
            Error original=new OutOfMemoryError("authored original worker error");
            Throwable secondary=secondaryIsError?new OutOfMemoryError("authored failure-persistence error"):
                    new IllegalStateException("authored failure-persistence exception");
            when(f.runs.findById(f.run.getId())).thenThrow(original);
            when(f.runs.lockById(f.run.getId())).thenThrow(secondary);
            String owner=f.run.getExecutionOwner();Instant lease=f.run.getLeaseUntil();
            f.dispatch();f.assertFatal(original);
            assertTrue(Arrays.asList(original.getSuppressed()).contains(secondary));
            assertEquals("QUEUED",f.run.getStatus(),"Failed persistence must not fabricate a durable FAILED state");
            assertEquals(owner,f.run.getExecutionOwner());assertEquals(lease,f.run.getLeaseUntil());
            verify(f.runs,never()).saveAndFlush(any());
        }
    }

    @Test void recoveryInitializationErrorUsesTheSameFailurePolicyWithoutChangingTheRecipe() {
        try(Fixture f=new Fixture()) {
            Error original=new OutOfMemoryError("authored recovery model-identity load error");
            f.pending().clear();f.probes().clear();
            when(f.checkpoints.claim(f.run.getId(),f.run.getExecutionOwner())).thenReturn(true);
            when(f.profiles.resolve(isNull())).thenThrow(original);
            Error escaped=assertThrows(OutOfMemoryError.class,()->ReflectionTestUtils.invokeMethod(f.service,"recover",f.run));
            assertSame(original,escaped);assertEquals("FAILED",f.run.getStatus());
            assertNull(f.run.getExecutionOwner());assertTrue(f.pending().isEmpty());
            f.service.renewLeases();verify(f.checkpoints,never()).renew(anyString(),anyString());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ConsenseProperties props=new ConsenseProperties();
        final VettingRunRepository runs=mock(VettingRunRepository.class);
        final VettingSemanticReview semantic=mock(VettingSemanticReview.class);
        final LlmProfiles profiles=mock(LlmProfiles.class);
        final VettingCheckpointService checkpoints=mock(VettingCheckpointService.class);
        final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        final VettingService service=new VettingService(mock(ProjectService.class),mock(StorageService.class),
                mock(DocumentParser.class),props,mock(SourceDocumentRepository.class),mock(VettingFindingRepository.class),
                runs,semantic,new VettingReportWriter(),tx,profiles);
        final VettingRun run=new VettingRun();
        final CompletableFuture<RunResultVO> done=new CompletableFuture<>();
        final AtomicReference<Throwable> escaped=new AtomicReference<>();
        final CountDownLatch uncaught=new CountDownLatch(1);
        final ThreadPoolExecutor worker=(ThreadPoolExecutor)ReflectionTestUtils.getField(service,"worker");
        final VettingServiceProbe probe;
        Fixture() {
            when(tx.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->new SimpleTransactionStatus());
            run.setId(UUID.randomUUID().toString());run.setProjectId("authored-fatal-project");run.setLang("en");
            run.setStatus("QUEUED");run.setPhase("queued");run.setExecutionOwner((String)ReflectionTestUtils.getField(service,"executionOwner"));
            run.setLeaseUntil(Instant.now().plusSeconds(120));
            when(runs.lockById(run.getId())).thenReturn(Optional.of(run));
            when(runs.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
            service.setCheckpoints(checkpoints);pending().put(run.getId(),done);
            probe=VettingServiceProbe.start(props,run.getId(),run.getProjectId(),"authored_fatal_workflow");
            probes().put(run.getId(),probe);
            worker.setThreadFactory(task->{Thread thread=new Thread(task,"authored-vetting-fatal");thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((failed,error)->{escaped.set(error);uncaught.countDown();});return thread;});
        }
        void dispatch() {
            ReflectionTestUtils.invokeMethod(service,"dispatch",run,Collections.emptyList(),null,null,done,probe,"");
        }
        void assertFatal(Error original) throws Exception {
            ExecutionException waiter=assertThrows(ExecutionException.class,()->done.get(3,TimeUnit.SECONDS));
            assertSame(original,waiter.getCause(),"Waiting callers receive the actual fatal error");
            assertTrue(uncaught.await(3,TimeUnit.SECONDS),"The fatal error must still escape the worker");
            assertSame(original,escaped.get());assertFalse(pending().containsKey(run.getId()));
            assertFalse(probes().containsKey(run.getId()));
            service.renewLeases();verify(checkpoints,never()).renew(anyString(),anyString());
        }
        @SuppressWarnings("unchecked") Map<String,CompletableFuture<RunResultVO>> pending() {
            return (Map<String,CompletableFuture<RunResultVO>>)ReflectionTestUtils.getField(service,"completions");
        }
        @SuppressWarnings("unchecked") Map<String,VettingServiceProbe> probes() {
            return (Map<String,VettingServiceProbe>)ReflectionTestUtils.getField(service,"serviceProbes");
        }
        @Override public void close() {service.stopWorker();}
    }
}
