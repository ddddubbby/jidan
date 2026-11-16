package com.jidan.receiving.data

/** 登录、版本查询与 APP 下发。 */
class AuthRepository(
    private val client: ApiClient,
) {
    suspend fun login(phone: String, password: String): LoginResult =
        client.runApi { this.login(LoginRequest(phone = phone, password = password, client = "APP")) }

    suspend fun me(): CurrentUser = client.runApi { this.me() }

    suspend fun appVersion(): AppVersion = client.runApi { this.appVersion() }

    suspend fun bootstrap(): BootstrapResponse = client.runApi { this.bootstrap() }
}
