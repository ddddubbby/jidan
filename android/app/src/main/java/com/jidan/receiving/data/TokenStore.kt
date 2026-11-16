package com.jidan.receiving.data

import android.content.Context

data class SessionUser(
    val token: String,
    val name: String,
    val phone: String,
    val role: String,
)

/** 登录凭证本地长期保存（v7 APP-12）；重置密码或停用后由 401 清掉。 */
class TokenStore(context: Context) {
    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun readSession(): SessionUser? {
        val token = sp.getString(KEY_TOKEN, null) ?: return null
        val name = sp.getString(KEY_NAME, null) ?: return null
        val phone = sp.getString(KEY_PHONE, null) ?: return null
        val role = sp.getString(KEY_ROLE, null) ?: return null
        if (token.isBlank()) return null
        return SessionUser(token, name, phone, role)
    }

    fun save(user: SessionUser) {
        sp.edit()
            .putString(KEY_TOKEN, user.token)
            .putString(KEY_NAME, user.name)
            .putString(KEY_PHONE, user.phone)
            .putString(KEY_ROLE, user.role)
            .apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    companion object {
        private const val NAME = "egg_token"
        private const val KEY_TOKEN = "token"
        private const val KEY_NAME = "name"
        private const val KEY_PHONE = "phone"
        private const val KEY_ROLE = "role"
    }
}
