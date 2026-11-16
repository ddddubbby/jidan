package com.egg.common;

import org.springframework.http.HttpStatus;

/** 客户端需要分支处理的错误码，与开发规格 5.2 的错误表一一对应。其余情况一律用 INVALID。 */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    WRONG_CLIENT(HttpStatus.FORBIDDEN),
    RULE_CONFLICT(HttpStatus.CONFLICT),
    ORDER_LOCKED(HttpStatus.CONFLICT),
    NOT_READY(HttpStatus.CONFLICT),
    INVALID(HttpStatus.BAD_REQUEST);

    public final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }
}
