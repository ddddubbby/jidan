package com.egg.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.egg.auth.AuthService.Client;
import com.egg.auth.AuthService.LoginResult;
import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(
            @NotBlank(message = "填写手机号") String phone,
            @NotBlank(message = "填写密码") String password,
            @NotNull(message = "缺少客户端类型") Client client) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @Access(Who.PUBLIC)
    @PostMapping("/login")
    public LoginResult login(@Valid @RequestBody LoginRequest body) {
        return auth.login(body.phone(), body.password(), body.client());
    }

    @Access(Who.ADMIN_OR_SCALE)
    @GetMapping("/me")
    public CurrentUser me(CurrentUser me) {
        return me;
    }
}
