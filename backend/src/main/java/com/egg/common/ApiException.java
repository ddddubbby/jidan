package com.egg.common;

import java.util.List;

/** 业务层直接抛，由 GlobalExceptionHandler 统一转成 { code, message }。message 直接给人看，写中文。 */
public class ApiException extends RuntimeException {

    public final ErrorCode code;
    /** 只有 RULE_CONFLICT 用：冲突的规则与农户，原样放进响应的 conflicts 字段。其余为 null。 */
    public final List<?> conflicts;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, List<?> conflicts) {
        super(message);
        this.code = code;
        this.conflicts = conflicts;
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID, message);
    }
}
