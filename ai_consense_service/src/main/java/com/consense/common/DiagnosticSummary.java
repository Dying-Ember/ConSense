package com.consense.common;

/** Fixed diagnostic facts for ordinary logs; exception messages and causes may contain source data. */
public final class DiagnosticSummary {
    private static final String UNAVAILABLE = "diagnostic=unavailable";
    private static final java.util.regex.Pattern MISSING_NATIVE_LIBRARY = java.util.regex.Pattern.compile(
            "(?:^|[\\s/:\\\\])(libjpeg\\.so\\.8|liblcms2\\.so\\.2|libharfbuzz\\.so\\.0|libfreetype\\.so\\.6|libfontconfig\\.so\\.1|libgraphite2\\.so\\.3|libpng16\\.so\\.16): cannot open shared object file(?::|$)");
    private DiagnosticSummary() {}

    public interface HttpStatus {
        int httpStatus();
    }

    public interface Category {
        Enum<?> diagnosticCategory();
    }

    public static String of(Throwable failure) {
        if (failure == null) return "unknown";
        String summary = failure.getClass().getSimpleName();
        if (failure instanceof BizException) summary += " code=" + ((BizException) failure).getCode();
        if (failure instanceof Category) {
            Enum<?> category = ((Category) failure).diagnosticCategory();
            if (category != null) summary += " category=" + category.name();
        }
        if (failure instanceof HttpStatus) {
            int status = ((HttpStatus) failure).httpStatus();
            if (status >= 100 && status <= 599) summary += " HTTP=" + status;
        }
        return summary;
    }

    /** Limit ordinary-log diagnostics to known types and application frame locations. */
    public static String unexpected(Throwable failure) {
        if (failure == null) return "unknown";
        try {
            StringBuilder result = new StringBuilder();
            Throwable[] seen = new Throwable[6];
            int count = 0;
            while (failure != null && count < seen.length) {
                for (int i = 0; i < count; i++) {
                    if (seen[i] == failure) return result.append(" cause=cycle").toString();
                }
                seen[count++] = failure;
                if (count > 1) result.append(" cause=");
                result.append(trustedType(failure));
                if (failure instanceof UnsatisfiedLinkError) {
                    String message = failure.getMessage();
                    java.util.regex.Matcher library = message == null || message.length() > 4096
                            ? null : MISSING_NATIVE_LIBRARY.matcher(message);
                    result.append(" nativeLibrary=").append(library != null && library.find() ? library.group(1) : "unknown");
                }
                int found = 0;
                StackTraceElement[] stack = failure.getStackTrace();
                for (int i = 0; i < Math.min(stack.length, 512) && found < 2; i++) {
                    StackTraceElement frame = stack[i];
                    String owner = frame.getClassName();
                    int line = frame.getLineNumber();
                    if (trustedFrame(owner) && line > 0 && line <= 100000) {
                        result.append(" at=").append(owner).append(':').append(line);
                        found++;
                    }
                }
                failure = failure.getCause();
            }
            if (failure != null) result.append(" cause=truncated");
            return result.toString();
        } catch (Throwable ignored) {
            return UNAVAILABLE;
        }
    }

    private static String trustedType(Throwable failure) {
        if (failure instanceof java.io.IOException) return "IOException";
        if (failure instanceof java.sql.SQLException) return "SQLException";
        if (failure instanceof javax.persistence.PersistenceException) return "PersistenceException";
        Class<?> type = failure.getClass();
        String name = type.getName();
        if ((type.getClassLoader() == null && name.startsWith("java.lang."))
                || name.equals("org.springframework.web.util.NestedServletException")
                || name.equals("com.consense.common.BizException")
                || name.equals("com.consense.service.sources.CanonicalEvidenceException")) {
            return name.substring(name.lastIndexOf('.') + 1);
        }
        return "other";
    }

    private static boolean trustedFrame(String name) {
        return name.equals("com.consense.document.DocumentParser")
                || name.equals("com.consense.service.vetting.VettingService")
                || name.equals("com.consense.service.vetting.OerBusinessEvidence")
                || name.equals("com.consense.service.vetting.OerCanonicalRetrievalClient")
                || name.equals("com.consense.service.sources.CanonicalEvidenceService")
                || name.equals("com.consense.service.sources.CanonicalCurrentSourceReader")
                || name.equals("com.consense.service.sources.NormalObservedPreparationService")
                || name.equals("com.consense.service.vetting.NormalSourceCandidateGraph")
                || name.equals("com.consense.common.JsonUtils")
                || name.equals("com.consense.service.StorageService")
                || name.equals("com.consense.web.VettingController");
    }
}
