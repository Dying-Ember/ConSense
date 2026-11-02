package com.consense.service.seed;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class DemoDataInitializerLogTest {
    @Test void malformedSeedIsIgnoredWithoutLoggingItsContents() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DemoDataInitializer.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            DemoDataInitializer initializer = new DemoDataInitializer(null, null, null, null);
            Method read = DemoDataInitializer.class.getDeclaredMethod("readSeed", String.class, Class.class);
            read.setAccessible(true);
            List<?> result = (List<?>) read.invoke(initializer, "seed/malformed-private-fixture.json", SeedModels.SeedProject.class);
            assertTrue(result.isEmpty());
            assertEquals(1, logs.list.size());
            ILoggingEvent event = logs.list.get(0);
            assertTrue(event.getFormattedMessage().contains("JsonMappingException"));
            assertFalse(event.getFormattedMessage().contains("SYNTHETIC_PRIVATE_CONTENT"));
            assertNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
