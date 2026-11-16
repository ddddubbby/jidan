package com.egg.auth;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.egg.common.ApiException;
import com.egg.common.Role;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 磅秤员账号的新增、重置密码、停用启用（`WEB-10`）。管理员账号不在这里管理（开发规格 7.2）。 */
@Service
public class AccountService {

    /** 对外的账号信息：没有密码也没有 token。 */
    public record AccountView(long id, String name, String phone, boolean enabled, LocalDateTime lastLoginAt) {
    }

    /** 初始密码、新密码只在这一次响应里出现，库里只存 BCrypt。 */
    public record WithPassword(AccountView account, String password) {
    }

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final AccountMapper accounts;

    public AccountService(AccountMapper accounts) {
        this.accounts = accounts;
    }

    public List<AccountView> listScale() {
        return accounts.selectList(Wrappers.<Account>query().eq("role", Role.SCALE.name()).orderByAsc("id"))
                .stream().map(AccountService::view).toList();
    }

    /** 单据上显示磅秤员、审核人的姓名用；管理员与磅秤员同表，都能查到。 */
    public Map<Long, String> names(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accounts.selectList(Wrappers.<Account>query().in("id", ids)).stream()
                .collect(Collectors.toMap(a -> a.id, a -> a.name));
    }

    public WithPassword create(String name, String phone, long by) {
        String password = generatePassword();
        Account a = new Account();
        a.name = name.trim();
        a.phone = phone;
        a.passwordHash = encoder.encode(password);
        a.role = Role.SCALE;
        a.enabled = true;
        a.updatedBy = by;
        a.updatedAt = LocalDateTime.now();
        try {
            accounts.insert(a);
        } catch (DuplicateKeyException e) {
            throw ApiException.invalid("这个手机号已有账号");
        }
        return new WithPassword(view(a), password);
    }

    public WithPassword resetPassword(long id, long by) {
        Account a = scaleAccount(id);
        String password = generatePassword();
        accounts.changeAndRevoke(id, encoder.encode(password), null, by);
        return new WithPassword(view(accounts.selectById(id)), password);
    }

    public AccountView setEnabled(long id, boolean enabled, long by) {
        scaleAccount(id);
        accounts.changeAndRevoke(id, null, enabled, by);
        return view(accounts.selectById(id));
    }

    private Account scaleAccount(long id) {
        Account a = accounts.selectById(id);
        if (a == null || a.role != Role.SCALE) {
            throw ApiException.invalid("账号不存在");
        }
        return a;
    }

    /** 6 位数字：磅秤员在手机上输，原型也是这个长度。 */
    private String generatePassword() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private static AccountView view(Account a) {
        return new AccountView(a.id, a.name, a.phone, a.enabled, a.lastLoginAt);
    }
}
