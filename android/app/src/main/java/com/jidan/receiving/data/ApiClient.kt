package com.jidan.receiving.data

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 按 Prefs 里的服务器地址组装 Retrofit；Authorization 头取 TokenStore。
 * 401 + UNAUTHENTICATED 时清登录并回调（登录接口自己的 BAD_CREDENTIALS 不触发）。
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val onUnauthenticated: () -> Unit,
) {
    constructor(prefs: Prefs, tokenStore: TokenStore, onUnauthenticated: () -> Unit) : this(
        baseUrl = { prefs.normalizedBaseUrl() },
        token = { tokenStore.readSession()?.token },
        onUnauthenticated = onUnauthenticated,
    )

    private val gson = Gson()
    private val lock = Any()

    @Volatile private var cachedBase: String? = null
    @Volatile private var cachedApi: EggApi? = null

    fun api(): EggApi {
        val base = baseUrl()
        require(base.isNotEmpty()) { "填写服务器地址" }
        synchronized(lock) {
            if (cachedApi != null && cachedBase == base) return cachedApi!!
            cachedBase = base
            cachedApi = build(base)
            return cachedApi!!
        }
    }

    /** 改了服务器地址后下次 [api] 会重建。 */
    fun invalidate() {
        synchronized(lock) {
            cachedBase = null
            cachedApi = null
        }
    }

    private fun build(baseUrl: String): EggApi {
        val auth = Interceptor { chain ->
            val req = chain.request()
            val bearer = token()
            val next = if (!bearer.isNullOrBlank()) {
                req.newBuilder().header("Authorization", "Bearer $bearer").build()
            } else {
                req
            }
            chain.proceed(next)
        }
        val unauth = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 401) {
                val peek = response.peekBody(64 * 1024).string()
                val code = parseError(peek)?.code
                // 登录接口的 BAD_CREDENTIALS 也是 401，不能清会话
                if (code == "UNAUTHENTICATED") {
                    onUnauthenticated()
                }
            }
            response
        }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .addInterceptor(unauth)
            .addInterceptor(logging)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(EggApi::class.java)
    }

    fun parseError(body: String?): ErrorBody? {
        if (body.isNullOrBlank()) return null
        return try {
            gson.fromJson(body, ErrorBody::class.java)
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    companion object {
        fun mapFailure(e: Throwable, parseError: (String?) -> ErrorBody?): ApiError {
            when (e) {
                is ApiError -> return e
                is HttpException -> {
                    val raw = try {
                        e.response()?.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    val err = parseError(raw)
                    val code = err?.code ?: "INTERNAL"
                    val msg = err?.message?.takeIf { it.isNotBlank() }
                        ?: if (e.code() == 401) "登录已失效" else "系统出错了，请稍后重试"
                    return ApiError(e.code(), code, msg)
                }
                is IOException -> return ApiError(0, "NETWORK", "连不上服务器，请检查网络后重试")
                else -> return ApiError(0, "INTERNAL", e.message?.takeIf { it.isNotBlank() } ?: "系统出错了，请稍后重试")
            }
        }
    }
}

suspend fun <T> ApiClient.runApi(block: suspend EggApi.() -> T): T {
    return try {
        block(api())
    } catch (e: Throwable) {
        throw ApiClient.mapFailure(e) { parseError(it) }
    }
}
