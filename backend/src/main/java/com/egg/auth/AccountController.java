package com.egg.auth;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.egg.auth.AccountService.AccountView;
import com.egg.auth.AccountService.WithPassword;
import com.egg.common.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 磅秤员账号管理，仅管理员（不标 @Access 即默认仅管理员）。 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    public record NewAccount(
            @NotBlank(message = "填写姓名") String name,
            @NotBlank(message = "填写 11 位手机号") @Pattern(regexp = "1\\d{10}", message = "手机号要 11 位数字") String phone) {
    }

    public record EnabledChange(@NotNull(message = "缺少启用状态") Boolean enabled) {
    }

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccountView> list() {
        return service.listScale();
    }

    @PostMapping
    public WithPassword create(@Valid @RequestBody NewAccount body, CurrentUser me) {
        return service.create(body.name(), body.phone(), me.id());
    }

    @PostMapping("/{id}/reset-password")
    public WithPassword resetPassword(@PathVariable long id, CurrentUser me) {
        return service.resetPassword(id, me.id());
    }

    @PutMapping("/{id}/enabled")
    public AccountView setEnabled(@PathVariable long id, @Valid @RequestBody EnabledChange body, CurrentUser me) {
        return service.setEnabled(id, body.enabled(), me.id());
    }
}
