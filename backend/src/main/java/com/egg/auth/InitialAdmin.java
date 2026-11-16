package com.egg.auth;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.egg.common.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始管理员（开发规格 7.2）：启动时库里没有任何管理员，才用环境变量建一个；已有管理员时什么都不做，
 * 所以改环境变量不会改掉已有管理员的密码。不做管理员管理页面，忘记密码只能再建（或由维护者改库）。
 */
@Component
@Order(2)
public class InitialAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdmin.class);

    private final AccountMapper accounts;
    private final String phone;
    private final String password;
    private final String name;

    public InitialAdmin(AccountMapper accounts,
            @Value("${egg.admin.phone:}") String phone,
            @Value("${egg.admin.password:}") String password,
            @Value("${egg.admin.name:管理员}") String name) {
        this.accounts = accounts;
        this.phone = phone;
        this.password = password;
        this.name = name;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.selectCount(Wrappers.<Account>query().eq("role", Role.ADMIN.name())) > 0) {
            return;
        }
        if (phone.isBlank() || password.isBlank()) {
            log.warn("库里没有管理员，且没有设置 EGG_ADMIN_PHONE、EGG_ADMIN_PASSWORD，没人能登录 Web。设置后重启即可");
            return;
        }
        Account a = new Account();
        a.name = name;
        a.phone = phone;
        a.passwordHash = new BCryptPasswordEncoder().encode(password);
        a.role = Role.ADMIN;
        a.enabled = true;
        a.updatedAt = LocalDateTime.now();
        accounts.insert(a);
        log.info("已创建初始管理员 {}", name);
    }
}
