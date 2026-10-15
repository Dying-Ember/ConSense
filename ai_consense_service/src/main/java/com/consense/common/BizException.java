package com.consense.common;

public class BizException extends RuntimeException {

    private final int code;
    private final String reason;

    public BizException(String message) {
        this(4000, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.reason = null;
    }

    public BizException(int code, String message, String reason, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.reason = reason;
    }

    public int getCode() {
        return code;
    }

    public String getPublicReason() { return reason; }
}
