package com.jidan.receiving.data

/** 与 openapi / 开发规格 5.2、附录 E P1-2 对齐的请求响应。 */

data class LoginRequest(
    val phone: String,
    val password: String,
    val client: String = "APP",
)

data class LoginResult(
    val token: String,
    val name: String,
    val role: String,
)

data class CurrentUser(
    val id: Long,
    val name: String,
    val phone: String,
    val role: String,
)

data class ErrorBody(
    val code: String? = null,
    val message: String? = null,
)

/**
 * `GET /app/version`（开发规格 5.2）。P2-2 / 部署任务落地前调用会失败，登录页隐藏下载入口。
 * 字段名按常见契约预留；若后端最终命名不同，合入后对照改一处即可。
 */
data class AppVersion(
    val versionCode: Int = 0,
    val versionName: String? = null,
    val downloadUrl: String? = null,
)

/**
 * `GET /app/bootstrap` 精简契约（开发规格 5.2）。完整档案列表等 P2-2 合入后再用；
 * 此处先收 options，供 [AppOptions.from] 使用。
 */
data class BootstrapResponse(
    val options: Map<String, String>? = null,
)
