package com.egg.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 不需要库：只验证各类异常都落成 { code, message } 与规格 5.2 的状态码。 */
class GlobalExceptionHandlerTest {

    @RestController
    static class Probe {
        record Form(@NotBlank(message = "名称不能为空") String name) {
        }

        @GetMapping("/locked")
        void locked() {
            throw new ApiException(ErrorCode.ORDER_LOCKED, "这张单已审核，不能修改");
        }

        @GetMapping("/invalid")
        void invalid() {
            throw ApiException.invalid("数量必须大于 0");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("内部细节不应泄露");
        }

        @PostMapping("/form")
        void form(@Valid @RequestBody Form form) {
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void apiExceptionKeepsCodeStatusAndMessage() throws Exception {
        mvc.perform(get("/locked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_LOCKED"))
                .andExpect(jsonPath("$.message").value("这张单已审核，不能修改"));
        mvc.perform(get("/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"));
    }

    @Test
    void everyErrorCodeMapsToStatusInSpec() {
        assertThat(ErrorCode.UNAUTHENTICATED.status.value()).isEqualTo(401);
        assertThat(ErrorCode.BAD_CREDENTIALS.status.value()).isEqualTo(401);
        assertThat(ErrorCode.WRONG_CLIENT.status.value()).isEqualTo(403);
        assertThat(ErrorCode.RULE_CONFLICT.status.value()).isEqualTo(409);
        assertThat(ErrorCode.ORDER_LOCKED.status.value()).isEqualTo(409);
        assertThat(ErrorCode.NOT_READY.status.value()).isEqualTo(409);
        assertThat(ErrorCode.INVALID.status.value()).isEqualTo(400);
    }

    @Test
    void validationFailureShowsFieldMessage() throws Exception {
        mvc.perform(post("/form").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"))
                .andExpect(jsonPath("$.message").value("名称不能为空"));
    }

    @Test
    void unreadableBodyIsInvalid() throws Exception {
        mvc.perform(post("/form").contentType(MediaType.APPLICATION_JSON).content("{坏的"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"));
    }

    @Test
    void unexpectedErrorHidesDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("系统出错了，请稍后重试"));
    }
}
