package com.consense.service.drafting;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.common.BizException;
import com.consense.ai.AiGateway;
import com.consense.document.DocumentParser;
import com.consense.repository.DraftVariableRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.prompt.PromptService;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import java.util.Collections;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DraftingDiagnosticLogPrivacyTest {
    @Test void reportPersistenceFailureKeepsOriginalAndSuppressedWithoutLoggingPrivateException() {
        String detail="SYNTHETIC_PRIVATE_REPORT_FAILURE";
        RuntimeException reportFailure=new IllegalStateException(detail);
        EntityManager entityManager=mock(EntityManager.class);
        doThrow(reportFailure).when(entityManager).clear();
        PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        DraftingService service=new DraftingService(mock(ProjectService.class),null,null,mock(AiGateway.class),
                mock(SourceDocumentRepository.class),mock(DraftVariableRepository.class),null,null,null,null,
                mock(PromptService.class),transactions,entityManager,mock(DraftExtractionHarness.class),null,null);
        Logger logger=(Logger)LoggerFactory.getLogger(DraftingService.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            BizException original=assertThrows(BizException.class,()->service.extractVariables("synthetic-project"));
            assertEquals(4005,original.getCode());
            assertArrayEquals(new Throwable[]{reportFailure},original.getSuppressed());
            ILoggingEvent event=logs.list.stream().filter(e->e.getFormattedMessage().contains("Unable to persist failed extraction report")).findFirst().orElseThrow(AssertionError::new);
            assertNull(event.getThrowableProxy());
            assertFalse(event.getFormattedMessage().contains(detail));
            assertTrue(event.getFormattedMessage().contains("IllegalStateException"));
        } finally {logger.detachAppender(logs);logs.stop();}
    }

    @Test void parserFailureIsReturnedWithoutBeingWrittenToOrdinaryLogs() {
        String filename = "SYNTHETIC_PRIVATE_FILENAME.docx";
        String detail = "SYNTHETIC_PRIVATE_PARSER_CONTENT";
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parseTemplate(nullable(String.class), anyString(), any(byte[].class)))
            .thenThrow(new BizException(4003, filename + ": " + detail));
        DraftingService service = new DraftingService(mock(ProjectService.class), null, parser, null,
            null, null, null, null, null, null, null, null, null, null, null, null);
        MockMultipartFile file = new MockMultipartFile("files", filename, "application/octet-stream", new byte[]{'P','K',3,4,1});
        Logger logger = (Logger) LoggerFactory.getLogger(DraftingService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            UploadResultVO result = service.uploadTemplates("synthetic-project", Collections.singletonList(file));
            assertEquals(1, result.getFailed());
            assertTrue(String.join("\n", result.getMessages()).contains(filename));
            assertTrue(String.join("\n", result.getMessages()).contains(detail));
            verify(parser).parseTemplate(nullable(String.class), eq(filename), any(byte[].class));
            assertFalse(logs.list.isEmpty());
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains(filename));
                assertFalse(event.getFormattedMessage().contains(detail));
                assertNull(event.getThrowableProxy());
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
}
