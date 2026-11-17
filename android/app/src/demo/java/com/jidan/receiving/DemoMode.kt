package com.jidan.receiving

import com.jidan.receiving.data.SessionUser
import com.jidan.receiving.demo.DemoBackend
import okhttp3.Interceptor

/**
 * 演示版挂钩（F5-3）的“开”。仅 `demo` 构建类型编进来，模拟版 / 实机版里没有这些代码。
 *
 * 演示版是给合作方现场试用的：不要服务器、不要登录。接口全部由 [DemoBackend] 在本机内存里回答，
 * 数据取 v7 演示数据；启动即以磅秤员「张师傅」登录。秤与打印机仍是模拟台（模拟版同款）。
 * 未经实物验证；这里的计价只为离线演示，正式系统只在后端计价。
 */
object DemoMode {
    val enabled: Boolean = BuildConfig.DEMO

    /** 不会真的联网：假后端在 OkHttp 拦截器里就回答了。 */
    val baseUrl: String? = "http://demo.invalid/"

    private val backend = DemoBackend()

    fun interceptor(): Interceptor? = backend

    fun session(): SessionUser? = SessionUser(
        token = "demo-token",
        name = "张师傅",
        phone = "13900001111",
        role = "SCALE",
    )

    /** 退出登录时：假后端的内存数据回到刚装好的样子。 */
    fun resetData() = backend.reset()
}
