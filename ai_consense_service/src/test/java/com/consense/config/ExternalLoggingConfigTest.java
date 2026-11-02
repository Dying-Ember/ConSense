package com.consense.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalLoggingConfigTest {
    @TempDir Path directory;

    @Test void keepsNormalFileLogsAndSuppressesFrameworkExceptions() throws Exception {
        Path file = directory.resolve("app.log");
        Path xml = Paths.get("tools/offline_delivery/app-competition-logback.xml").toAbsolutePath();
        assertTrue(Files.isRegularFile(xml));
        LoggerContext context = new LoggerContext();
        context.putProperty("LOG_FILE", file.toString());
        try {
            JoranConfigurator parser = new JoranConfigurator();
            parser.setContext(context);
            parser.doConfigure(xml.toFile());
            assertTrue(context.getStatusManager().getCopyOfStatusList().stream()
                    .noneMatch(status -> status.getEffectiveLevel() >= Status.ERROR));
            context.getLogger(Logger.ROOT_LOGGER_NAME).info("ordinary-record");
            context.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper")
                    .error("SYNTHETIC_SQL_SECRET", new IllegalStateException("SYNTHETIC_STACK_SECRET", new RuntimeException("SYNTHETIC_CAUSE_SECRET")));
            context.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper")
                    .warn("SQL Error: 987654321, SQLState: TOKEN");
            context.getLogger("org.springframework.transaction.support.TransactionTemplate")
                    .error("SYNTHETIC_TRANSACTION_SECRET");
        } finally {
            context.stop();
        }
        String ordinary = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String framework = new String(Files.readAllBytes(Paths.get(file + ".framework")), StandardCharsets.UTF_8);
        assertTrue(ordinary.contains("ordinary-record"));
        assertTrue(framework.contains("SqlExceptionHelper"));
        assertTrue(framework.contains("TransactionTemplate"));
        assertTrue(framework.contains("exception=java.lang.IllegalStateException"));
        assertTrue(framework.contains("exception=none"));
        assertFalse(framework.contains("TOKEN"));
        assertFalse(framework.contains("987654321"));
        assertFalse(framework.contains("java.lang.RuntimeException"));
        assertFalse((ordinary + framework).contains("SYNTHETIC_"));
    }
}
