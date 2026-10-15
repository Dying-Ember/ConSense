package com.consense.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应包装：前端所有接口都按 { code, message, data } 解析。
 */
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;
    private final String reason;

    public ApiResponse(int code, String message, T data) {
        this(code, message, data, null);
    }

    public ApiResponse(int code, String message, T data, String reason) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.reason = reason;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> fail(int code, String message, String reason) {
        return new ApiResponse<>(code, message, null, reason);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getReason() { return reason; }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public T getData() {
        return data;
    }
}
