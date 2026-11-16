package com.egg.auth;

import java.security.SecureRandom;
import java.util.HexFormat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.egg.common.ApiException;
import com.egg.common.CurrentUser;
import com.egg.common.ErrorCode;
import com.egg.common.Role;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 登录与按 token 认人（开发规格 7.2）。token 不透明、长期有效，只在重置密码或停用时作废。 */
@Service
public class AuthService {

    /** 登录时声明自己是哪个客户端；角色与客户端对不上就拒绝。 */
    public enum Client {
        APP(Role.SCALE, "这个账号只能用 Web 管理端"),
        WEB(Role.ADMIN, "磅秤员账号只能用 APP");

        final Role allowed;
        final String refusal;

        Client(Role allowed, String refusal) {
            this.allowed = allowed;
            this.refusal = refusal;
        }
    }

    public record LoginResult(String token, String name, Role role) {
    }

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final AccountMapper accounts;

    public AuthService(AccountMapper accounts) {
        this.accounts = accounts;
    }

    public LoginResult login(String phone, String password, Client client) {
        Account a = accounts.selectOne(Wrappers.<Account>query().eq("phone", phone));
        // 手机号不存在与密码错误给同一个提示，不透露哪个手机号开过账号
        if (a == null || !encoder.matches(password, a.passwordHash)) {
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "手机号或密码不对");
        }
        if (!a.enabled) {
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "账号已停用，请联系管理员");
        }
        // 角色检查放在密码之后：不知道密码的人看不出某个手机号是管理员
        if (a.role != client.allowed) {
            throw new ApiException(ErrorCode.WRONG_CLIENT, client.refusal);
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        accounts.touchLogin(a.id, HexFormat.of().formatHex(raw));
        return new LoginResult(accounts.selectById(a.id).token, a.name, a.role);
    }

    /** 查不到（token 不对、已被清空、账号已停用）返回 null，由拦截器统一回 401。 */
    public CurrentUser byToken(String token) {
        Account a = accounts.selectOne(Wrappers.<Account>query()
                .eq("token", token).eq("enabled", true));
        return a == null ? null : new CurrentUser(a.id, a.name, a.phone, a.role);
    }
}
