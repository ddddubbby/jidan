package com.jidan.receiving

import com.jidan.receiving.data.SessionUser
import okhttp3.Interceptor

/**
 * 演示版挂钩（F5-3）的“关”：模拟版 / 实机版（debug、release）用这一份，什么都不做。
 * 演示版（`demo` 构建类型）用 `src/demo` 里的同名对象：离线假后端 + 免登录。
 */
object DemoMode {
    val enabled: Boolean = BuildConfig.DEMO

    /** 演示版固定的服务器地址（不会真的联网）；其余构建为 null，用登录页填的地址。 */
    val baseUrl: String? = null

    fun interceptor(): Interceptor? = null

    /** 演示版的登录身份；其余构建为 null。 */
    fun session(): SessionUser? = null

    fun resetData() {}
}
