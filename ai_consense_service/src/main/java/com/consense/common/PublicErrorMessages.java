package com.consense.common;

/** Only explicit business guidance may cross the API boundary. */
public final class PublicErrorMessages {
    private PublicErrorMessages() {}

    public static String from(Throwable failure,String fallback) {
        return failure instanceof BizException && failure.getMessage()!=null ? failure.getMessage() : fallback;
    }
}
