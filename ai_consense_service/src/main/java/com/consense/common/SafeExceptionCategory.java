package com.consense.common;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;

/** Preserve an exception type without exposing its message or stack trace. */
public final class SafeExceptionCategory extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        IThrowableProxy failure = event.getThrowableProxy();
        if (failure == null) return "exception=none";
        String name = failure.getClassName();
        if (name == null || name.length() > 200 || !name.matches("[A-Za-z0-9_.$]+")) return "exception=unknown";
        return "exception=" + name;
    }
}
