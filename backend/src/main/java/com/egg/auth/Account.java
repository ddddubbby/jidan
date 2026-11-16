package com.egg.auth;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.egg.common.Role;

/** 磅秤员与管理员同表（开发规格 5.1）。只在 auth 包内使用，对外的响应用 AccountView，不带密码与 token。 */
@TableName("account")
public class Account {

    public Long id;
    public String name;
    public String phone;
    public String passwordHash;
    public Role role;
    public Boolean enabled;
    public String token;
    public LocalDateTime lastLoginAt;
    public Long updatedBy;
    public LocalDateTime updatedAt;
}
