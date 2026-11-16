package com.jidan.receiving.data

/**
 * 登录与版本查询。bootstrap 在 P2-2 合入前调用会失败，调用方应吞掉并继续用 [AppOptions.defaults]。
 */
class AuthRepository(
    private val client: ApiClient,
) {
    suspend fun login(phone: String, password: String): LoginResult =
        client.runApi { this.login(LoginRequest(phone = phone, password = password, client = "APP")) }

    suspend fun me(): CurrentUser = client.runApi { this.me() }

    suspend fun appVersion(): AppVersion = client.runApi { this.appVersion() }

    /** 待 P2-2；失败时返回 null。 */
    suspend fun bootstrapOrNull(): BootstrapResponse? = try {
        client.runApi { this.bootstrap() }
    } catch (_: ApiError) {
        null
    }
}
