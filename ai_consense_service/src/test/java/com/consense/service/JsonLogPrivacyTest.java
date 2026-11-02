package com.consense.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.domain.DraftVariable;
import com.consense.domain.Project;
import com.consense.service.drafting.DraftingService;
import com.consense.web.dto.DraftingDtos.VariableVO;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class JsonLogPrivacyTest {
    private static final String TITLE_SECRET = "SYNTHETIC_TITLE_PRIVATE";
    private static final String OPTIONS_SECRET = "SYNTHETIC_OPTIONS_PRIVATE";
    private static final String COLS_SECRET = "SYNTHETIC_COLS_PRIVATE";

    @Test void malformedTitleKeepsEmptyFallbackWithoutLoggingSource() {
        Project project = new Project();
        project.setTitleLinesJson("[\"" + TITLE_SECRET);
        ProjectService service = new ProjectService(null, null, null, null, null, null, null);
        Logger logger = (Logger) LoggerFactory.getLogger(ProjectService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs); logger.setLevel(Level.DEBUG);
        try {
            assertTrue(service.toVO(project).getTitleLines().isEmpty());
            assertEquals(1, logs.list.size());
            assertSafe(logs.list.get(0), TITLE_SECRET);
        } finally { logger.setLevel(previous); logger.detachAppender(logs); logs.stop(); }
    }

    @Test void malformedVariableListsKeepEmptyFallbackWithoutLoggingSource() throws Exception {
        DraftVariable variable = new DraftVariable();
        variable.setVarKey("synthetic-key");
        variable.setOptionsJson("[\"" + OPTIONS_SECRET);
        variable.setColsJson("[\"" + COLS_SECRET);
        DraftingService service = new DraftingService(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        Method toVariable = DraftingService.class.getDeclaredMethod("toVariableVO", DraftVariable.class);
        toVariable.setAccessible(true);
        Logger logger = (Logger) LoggerFactory.getLogger(DraftingService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs); logger.setLevel(Level.DEBUG);
        try {
            VariableVO result = (VariableVO) toVariable.invoke(service, variable);
            assertTrue(result.getOptions().isEmpty());
            assertTrue(result.getCols().isEmpty());
            assertEquals(2, logs.list.size());
            assertSafe(logs.list.get(0), OPTIONS_SECRET);
            assertSafe(logs.list.get(1), COLS_SECRET);
        } finally { logger.setLevel(previous); logger.detachAppender(logs); logs.stop(); }
    }

    private static void assertSafe(ILoggingEvent event, String secret) {
        assertEquals(Level.DEBUG, event.getLevel());
        assertFalse(event.getFormattedMessage().contains(secret));
        assertNull(event.getThrowableProxy());
        assertTrue(event.getFormattedMessage().contains("BizException"));
    }
}
