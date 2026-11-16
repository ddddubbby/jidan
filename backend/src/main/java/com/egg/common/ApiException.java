package com.egg.common;

/** 业务层直接抛，由 GlobalExceptionHandler 统一转成 { code, message }。message 直接给人看，写中文。 */
public class ApiException extends RuntimeException {

    public final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID, message);
    }
}
