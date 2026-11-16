package com.egg.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 错误响应体（开发规格 5.2）。conflicts 只在 RULE_CONFLICT 时出现，其余响应里没有这个字段。 */
public record ErrorBody(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) List<?> conflicts) {

    public ErrorBody(String code, String message) {
        this(code, message, null);
    }
}
