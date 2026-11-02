package com.consense.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.NestedServletException;
import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test void unexpectedFailuresArePrivateAndBusinessGuidanceRemainsVisible() {
        GlobalExceptionHandler handler=new GlobalExceptionHandler();
        ApiResponse<Void> unexpected=handler.handleOther(new javax.persistence.PersistenceException(
                "could not insert: [com.consense.domain.SourceDocument]; SQL insert into source_documents; D:/private/database"));
        assertEquals(5000,unexpected.getCode());
        assertEquals("服务内部错误，请稍后重试；若仍失败，请联系维护人员。",unexpected.getMessage());
        ApiResponse<Void> business=handler.handleBiz(new BizException(4012,"请使用替换模板操作。","template_replacement_required",null));
        assertEquals(4012,business.getCode());assertEquals("请使用替换模板操作。",business.getMessage());
        assertEquals("template_replacement_required",business.getReason());
    }

    @Test void unexpectedLogShowsBoundedCauseAndTrustedLocationWithoutPrivateContent() {
        String secret="SYNTHETIC_PRIVATE_SOURCE_TOKEN";
        OutOfMemoryError cause=new OutOfMemoryError(secret);
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("attacker."+secret,secret,secret,99),
                new StackTraceElement("com.consense.service.vetting.VettingService",secret,secret,122),
                new StackTraceElement("com.consense.service.vetting.OerBusinessEvidence",secret,secret,152),
                new StackTraceElement("com.consense.service.StorageService",secret,secret,123)});
        NestedServletException failure=new NestedServletException(secret,cause);
        failure.setStackTrace(new StackTraceElement[0]);
        failure.addSuppressed(new IllegalStateException(secret));
        Logger logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            ApiResponse<Void> response=new GlobalExceptionHandler().handleOther(failure);
            assertEquals(5000,response.getCode());assertFalse(response.getMessage().contains(secret));
            assertEquals(1,logs.list.size());ILoggingEvent event=logs.list.get(0);
            assertNull(event.getThrowableProxy());String text=event.getFormattedMessage();
            assertTrue(text.contains("NestedServletException cause=OutOfMemoryError"));
            assertTrue(text.contains("VettingService:122"));assertTrue(text.contains("OerBusinessEvidence:152"));
            assertFalse(text.contains("StorageService"));assertFalse(text.contains(secret));
        } finally {logger.detachAppender(logs);logs.stop();}
    }

    @Test void unexpectedSummaryHandlesCyclesFaultsAndKnownExceptionCategories() {
        String secret="SYNTHETIC_PRIVATE_SOURCE_TOKEN";
        Throwable first=new IllegalStateException(secret),second=new IllegalArgumentException(secret);
        first.initCause(second);second.initCause(first);
        assertTrue(DiagnosticSummary.unexpected(first).contains("cause=cycle"));
        Throwable deep=new IllegalStateException(secret);
        for(int i=0;i<7;i++)deep=new IllegalStateException(secret,deep);
        assertTrue(DiagnosticSummary.unexpected(deep).contains("cause=truncated"));
        RuntimeException noStackMemory=new RuntimeException(secret){
            @Override public StackTraceElement[] getStackTrace(){throw new OutOfMemoryError(secret);}
        };
        assertEquals("diagnostic=unavailable",DiagnosticSummary.unexpected(noStackMemory));
        RuntimeException badCause=new RuntimeException(secret){
            @Override public synchronized Throwable getCause(){throw new IllegalStateException(secret);}
        };
        assertEquals("diagnostic=unavailable",DiagnosticSummary.unexpected(badCause));
        assertEquals(5000,new GlobalExceptionHandler().handleOther(new NestedServletException(secret,noStackMemory)).getCode());
        assertTrue(DiagnosticSummary.unexpected(new java.net.ConnectException(secret)).startsWith("IOException"));
        assertTrue(DiagnosticSummary.unexpected(new java.sql.SQLTransientConnectionException(secret)).startsWith("SQLException"));
        assertTrue(DiagnosticSummary.unexpected(new javax.persistence.EntityNotFoundException(secret)).startsWith("PersistenceException"));
        assertEquals("IllegalStateException",DiagnosticSummary.of(new IllegalStateException(secret)));
    }

    @Test void nativeLinkageLogsKnownMissingLibraryAndSafeParserFrameWithoutPrivateContent() {
        String secret="SYNTHETIC_PRIVATE_SOURCE_TOKEN";
        Logger logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            String[] messages={"/private/"+secret+"/libjavajpeg.so: libjpeg.so.8: cannot open shared object file: No such file or directory",
                    "/private/"+secret+"/liblcms2.so.2: cannot open shared object file: No such file or directory",
                    "/private/"+secret+"/libsecret.so.9: cannot open shared object file: No such file or directory",
                    "libjpeg.so.8: unexpected symbol "+secret};
            String[] expected={"libjpeg.so.8","liblcms2.so.2","unknown","unknown"};
            for(int i=0;i<messages.length;i++) {
                UnsatisfiedLinkError cause=new UnsatisfiedLinkError(messages[i]);
                cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.consense.document.DocumentParser",secret,secret,420)});
                NestedServletException failure=new NestedServletException(secret,cause);failure.setStackTrace(new StackTraceElement[0]);
                assertEquals(5000,new GlobalExceptionHandler().handleOther(failure).getCode());
                ILoggingEvent event=logs.list.get(i);String text=event.getFormattedMessage();
                assertNull(event.getThrowableProxy());assertTrue(text.contains("nativeLibrary="+expected[i]),text);
                assertTrue(text.contains("com.consense.document.DocumentParser:420"),text);
                assertFalse(text.contains(secret));assertFalse(text.contains("/private/"));assertFalse(text.contains("libsecret.so.9"));
            }
        } finally {logger.detachAppender(logs);logs.stop();}
    }
}
