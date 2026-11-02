package com.consense.service.prompt;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class PromptFormattingLogPrivacyTest {
    @Test
    void malformedTemplateKeepsFallbackWithoutLoggingFormatFragment() {
        Logger logger = (Logger) LoggerFactory.getLogger(PromptService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.WARN);
        try {
            String result = PromptService.format("header %s %314159s", "value");

            assertEquals("header value %314159s", result);
            assertEquals(1, logs.list.size());
            ILoggingEvent event = logs.list.get(0);
            assertEquals(Level.WARN, event.getLevel());
            assertFalse(event.getFormattedMessage().contains("314159"));
            assertTrue(event.getFormattedMessage().contains("MissingFormatArgumentException"));
            assertNull(event.getThrowableProxy());
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
