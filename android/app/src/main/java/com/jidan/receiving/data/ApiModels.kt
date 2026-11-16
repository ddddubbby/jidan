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

data class FarmerDto(
    val id: Long,
    val name: String,
    val hsCode: String? = null,
    val phones: List<String> = emptyList(),
    val matchedPhones: List<String> = emptyList(),
)

data class RuleViewDto(
    val id: Long,
    val name: String,
    val kind: String? = null,
    val productId: Long,
    val productName: String? = null,
    val specId: Long,
    val specName: String? = null,
    val coef: Double? = null,
    val enabled: Boolean = true,
)

data class ArchiveRowDto(
    val id: Long,
    val hsCode: String? = null,
    val name: String,
    val unitName: String? = null,
    val phones: List<String>? = null,
)

data class PageResultDto(
    val items: List<ArchiveRowDto> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
)

data class SpecDto(
    val id: Long,
    val name: String,
    val eggCount: Int? = null,
    val stdNetKg: Double? = null,
    val enabled: Boolean = true,
)

data class BasePriceDayDto(
    val date: String? = null,
    val items: List<BasePriceItemDto> = emptyList(),
)

data class BasePriceItemDto(
    val specId: Long,
    val specName: String? = null,
    val stdNetKg: Double? = null,
    val price: Double? = null,
)
