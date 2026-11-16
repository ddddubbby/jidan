package com.jidan.receiving.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Retrofit 接口表（开发规格 5.2）。P6-1 先接登录与版本；bootstrap 等 P2-2。 */
interface EggApi {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResult

    @GET("api/auth/me")
    suspend fun me(): CurrentUser

    @GET("api/app/version")
    suspend fun appVersion(): AppVersion

    @GET("api/app/bootstrap")
    suspend fun bootstrap(): BootstrapResponse
}
