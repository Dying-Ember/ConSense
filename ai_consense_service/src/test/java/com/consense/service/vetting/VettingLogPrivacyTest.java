package com.consense.service.vetting;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.common.BizException;
import com.consense.common.DiagnosticSummary;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class VettingLogPrivacyTest {
    private static final String PRIVATE = "SYNTHETIC_RESPONSE_BODY";

    private static final class ResponseFailure extends BizException implements DiagnosticSummary.HttpStatus {
        ResponseFailure() { super(4101, PRIVATE, null, new IllegalStateException(PRIVATE)); }
        @Override public int httpStatus() { return 422; }
    }

    @Test void failureLogKeepsDiagnosticFactsWithoutExceptionContent() {
        ResponseFailure failure = new ResponseFailure();
        failure.addSuppressed(new IllegalArgumentException(PRIVATE));
        Logger logger = (Logger) LoggerFactory.getLogger(VettingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            VettingService.logRunFailure("run-1", "source_preparation", failure);
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertNull(event.getThrowableProxy());
            assertFalse(event.getFormattedMessage().contains(PRIVATE));
            assertTrue(event.getFormattedMessage().contains("run-1"));
            assertTrue(event.getFormattedMessage().contains("source_preparation"));
            assertTrue(event.getFormattedMessage().contains("ResponseFailure code=4101 HTTP=422"));
            assertEquals(PRIVATE, failure.getMessage());
            appender.list.clear();
            VettingService.logRunFailure("run-2", "corpus_and_semantic_review", new IllegalStateException(PRIVATE, failure));
            assertEquals(1, appender.list.size());
            assertNull(appender.list.get(0).getThrowableProxy());
            assertFalse(appender.list.get(0).getFormattedMessage().contains(PRIVATE));
            assertTrue(appender.list.get(0).getFormattedMessage().contains("ResponseFailure code=4101 HTTP=422"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
