package com.jidan.receiving.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit 接口表（开发规格 5.2）。这里只放磅秤员账号能调的接口；商品、规格、仓库、皮重都走 bootstrap，
 * 不得直连仅管理员的档案接口（后端 ScaleAccessTest 按本文件的声明逐个核对）。
 */
interface EggApi {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResult

    @GET("api/auth/me")
    suspend fun me(): CurrentUser

    @GET("api/app/version")
    suspend fun appVersion(): AppVersion

    @GET("api/app/bootstrap")
    suspend fun bootstrap(): BootstrapResponse

    @GET("api/farmers")
    suspend fun farmersByTail(@Query("tail") tail: String?): List<FarmerDto>

    /** 按名称、编码、号码或拼音首字母找农户（好生意里没填号码的农户靠这个）。 */
    @GET("api/farmers")
    suspend fun farmersByName(@Query("q") q: String): List<FarmerDto>

    @GET("api/farmers/recent")
    suspend fun farmersRecent(): List<FarmerDto>

    @GET("api/farmers/{id}/rules")
    suspend fun farmerRules(@Path("id") id: Long): List<RuleViewDto>

    @POST("api/batches")
    suspend fun createBatch(@Body body: BatchRequest = BatchRequest()): BatchDto

    @POST("api/orders/quote")
    suspend fun quote(@Body body: QuoteRequest): QuoteResult

    @POST("api/orders")
    suspend fun submit(@Body body: SubmitRequest): SubmittedDto

    @GET("api/rules")
    suspend fun rules(
        @Query("kind") kind: String? = null,
        @Query("productId") productId: Long? = null,
        @Query("specId") specId: Long? = null,
        @Query("q") q: String? = null,
    ): List<RuleViewDto>

    @PUT("api/rules/{id}")
    suspend fun updateRule(@Path("id") id: Long, @Body body: RuleCoefPatch): RuleViewDto

    @PUT("api/farmers/{id}/rule-binding")
    suspend fun bindFarmerRule(@Path("id") id: Long, @Body body: RuleBindingRequest): List<RuleViewDto>
}
