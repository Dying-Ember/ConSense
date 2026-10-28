package com.consense.service.vetting;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.web.dto.VettingDtos.VettingJobVO;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VettingRunProbeContextTest {
    @Test void normalAsyncRunCarriesPersistedIdentityIntoScalarSemanticLogs() throws Exception {
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticEnabled(false);
        assertNull(props.getVetting().getProbeDirectory());assertNull(props.getVetting().getServiceProbeDirectory());
        Project project=new Project();project.setId("authored-project");project.setNameEn("Authored project");
        ProjectService projects=mock(ProjectService.class);when(projects.require(project.getId())).thenReturn(project);
        SourceDocument source=new SourceDocument();source.setId(1L);source.setProjectId(project.getId());
        source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setFileName("SCT.txt");source.setFileKey("SCT");
        source.setReviewRole("tender");source.setDocumentScope("package");source.setParseStatus("PARSED");
        source.setTextContent("SCT 1.1 The Contractor shall retain the tender source.");NormalSourceCandidateGraph.capture(source);
        SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        when(sources.findSelectedBusinessSourcesByProjectId(project.getId(),"fullset","vetting")).thenReturn(Collections.singletonList(source));
        when(sources.findScopeSourcesByProjectId(project.getId())).thenReturn(Collections.singletonList(source));
        VettingRunRepository runs=mock(VettingRunRepository.class);AtomicReference<VettingRun> stored=new AtomicReference<>();
        when(runs.saveAndFlush(any(VettingRun.class))).thenAnswer(call->{VettingRun run=call.getArgument(0);stored.set(run);return run;});
        when(runs.findById(anyString())).thenAnswer(call->Optional.ofNullable(stored.get()).filter(run->run.getId().equals(call.getArgument(0))));
        when(runs.lockById(anyString())).thenAnswer(call->Optional.ofNullable(stored.get()).filter(run->run.getId().equals(call.getArgument(0))));
        when(runs.findFirstByProjectIdOrderByStartedAtDesc(project.getId())).thenAnswer(call->Optional.ofNullable(stored.get()));
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        LlmClient provider=mock(LlmClient.class);ModelIdentity identity=new ModelIdentity("authored","fixture","unused",VettingCorpus.hash("authored-config"),"configured");
        Constructor<LlmOperation> operationCtor=LlmOperation.class.getDeclaredConstructor(boolean.class,LlmClient.class,ModelIdentity.class,String.class,ConsenseProperties.Llm.class);
        operationCtor.setAccessible(true);LlmOperation operation=operationCtor.newInstance(false,provider,identity,null,props.getLlm());
        LlmProfiles profiles=mock(LlmProfiles.class);when(profiles.capture()).thenReturn(operation);
        AiGateway gateway=mock(AiGateway.class);VettingSemanticReview semantic=spy(new VettingSemanticReview(gateway,props,mock(VettingRetrievalClient.class)));
        CompletableFuture<String> observed=new CompletableFuture<>();
        doAnswer(call->{call.callRealMethod();observed.complete(call.getArgument(0));throw new IllegalStateException("Authored stop after semantic correlation");})
                .when(semantic).reviewWithProgress(nullable(String.class),eq(project.getId()),anyList(),eq("en"),any(VettingSemanticReview.ReviewProgress.class),anyList(),any(Runnable.class));
        VettingService service=new VettingService(projects,mock(StorageService.class),mock(DocumentParser.class),props,sources,
                mock(VettingFindingRepository.class),runs,semantic,mock(VettingReportWriter.class),tx,profiles);
        Logger logger=(Logger)LoggerFactory.getLogger(VettingReviewProbe.class);ListAppender<ILoggingEvent> trace=new ListAppender<>();trace.start();logger.addAppender(trace);
        try {
            VettingJobVO job=service.startRun(project.getId(),"en");
            assertEquals(job.getId(),observed.get(10,TimeUnit.SECONDS),"The persisted service run must reach semantic review even when file probes are off");
            assertTrue(trace.list.stream().anyMatch(event->event.getFormattedMessage().contains("kind=review run="+job.getId())&&event.getFormattedMessage().contains("stage=run_started")));
            assertNull(props.getVetting().getProbeDirectory());verifyNoInteractions(provider);
        } finally {service.stopWorker();logger.detachAppender(trace);}
    }

    @Test void legacySemanticCallerWithoutRunIdentityStillWorksWithFileProbeOff() {
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticEnabled(false);
        AiGateway gateway=mock(AiGateway.class);
        VettingSemanticReview.Result result=new VettingSemanticReview(gateway,props,mock(VettingRetrievalClient.class))
                .reviewWithProgress("authored-project",Collections.emptyList(),"en",(completed,total,message)->{});
        assertNotNull(result);assertEquals(0,result.getActualGatewayCallCount());verify(gateway,never()).requireAvailable();
    }
}
