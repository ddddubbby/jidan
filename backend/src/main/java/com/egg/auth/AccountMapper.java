package com.egg.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    /** 已有 token 就沿用，没有才写入新的（同一账号多台设备登录互不影响）。一条语句完成，两台设备同时首次登录也只会留下一个。 */
    @Update("UPDATE account SET token = COALESCE(token, #{token}), last_login_at = NOW() WHERE id = #{id}")
    void touchLogin(@Param("id") long id, @Param("token") String token);

    /** 重置密码与停用启用都走这里：改字段的同时清空 token，该账号在所有设备上立即失效（BE-05）。 */
    @Update("UPDATE account SET password_hash = COALESCE(#{hash}, password_hash), enabled = COALESCE(#{enabled}, enabled), "
            + "token = NULL, updated_by = #{by}, updated_at = NOW() WHERE id = #{id}")
    void changeAndRevoke(@Param("id") long id, @Param("hash") String hash, @Param("enabled") Boolean enabled,
            @Param("by") long by);
}
