package com.egg.common;

/** 错误响应体（开发规格 5.2）。 */
public record ErrorBody(String code, String message) {
}
