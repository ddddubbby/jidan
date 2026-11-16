package com.egg.common;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 全局只有这一处把异常转成 { code, message }。继承 ResponseEntityExceptionHandler 是为了
 * 让 Spring 自己抛的 400/404/405/415 等也走同一个格式，而不是落到默认的错误页。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return ResponseEntity.status(e.code.status).body(new ErrorBody(e.code.name(), e.getMessage(), e.conflicts));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorBody> handleConstraint(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst().map(v -> v.getMessage()).orElse("输入不合法");
        return ResponseEntity.badRequest().body(new ErrorBody(ErrorCode.INVALID.name(), message));
    }

    /** 兜底：不把堆栈和内部细节返回给客户端，只写日志。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody("INTERNAL", "系统出错了，请稍后重试"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = e.getBindingResult().getAllErrors().stream()
                .findFirst().map(err -> err.getDefaultMessage()).orElse("输入不合法");
        return ResponseEntity.badRequest().body(new ErrorBody(ErrorCode.INVALID.name(), message));
    }

    /** Spring 自己抛的其余请求错误（参数缺失、JSON 解析失败、路径不存在……）。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus http = HttpStatus.valueOf(status.value());
        boolean bad = http == HttpStatus.BAD_REQUEST;
        String code = bad ? ErrorCode.INVALID.name() : http.name();
        String message = bad ? "输入不合法" : http.getReasonPhrase();
        return ResponseEntity.status(status).headers(headers).body(new ErrorBody(code, message));
    }
}
