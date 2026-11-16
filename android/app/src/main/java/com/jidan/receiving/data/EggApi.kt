package com.jidan.receiving.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query

/** Retrofit 接口表（开发规格 5.2）。bootstrap 等 P2-2；建单用档案/规则接口已有。 */
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

    @GET("api/farmers/recent")
    suspend fun farmersRecent(): List<FarmerDto>

    @GET("api/farmers/{id}/rules")
    suspend fun farmerRules(@Path("id") id: Long): List<RuleViewDto>

    @GET("api/archive/{type}")
    suspend fun archive(
        @Path("type") type: String,
        @Query("q") q: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
    ): PageResultDto

    @GET("api/specs")
    suspend fun specs(): List<SpecDto>

    @GET("api/base-prices")
    suspend fun basePrices(@Query("date") date: String? = null): BasePriceDayDto

    @GET("api/tares")
    suspend fun tares(): List<TareDto>

    @POST("api/batches")
    suspend fun createBatch(@Body body: BatchRequest = BatchRequest()): BatchDto

    @POST("api/orders/quote")
    suspend fun quote(@Body body: QuoteRequest): QuoteResult
}
