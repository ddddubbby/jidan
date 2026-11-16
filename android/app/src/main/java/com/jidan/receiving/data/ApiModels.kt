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
 * `GET /app/bootstrap`（开发规格 5.2）。APP 只取两张键值表：
 * [settings] 是业务设置（打印时机、标签内容、设备型号、均重取整等），
 * [options] 是高级选项里 APP 生效的两组。两张表合起来交给 [AppOptions.from]。
 */
data class BootstrapResponse(
    val settings: Map<String, String?>? = null,
    val options: Map<String, String?>? = null,
) {
    fun allValues(): Map<String, String?> = (settings ?: emptyMap()) + (options ?: emptyMap())
}

data class FarmerDto(
    val id: Long,
    val name: String,
    val hsCode: String? = null,
    val phones: List<String> = emptyList(),
    val matchedPhones: List<String> = emptyList(),
)

data class RuleBindTargetDto(
    val id: Long,
    val name: String? = null,
    val tail4: String? = null,
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
    val binds: List<RuleBindTargetDto> = emptyList(),
    val bindCount: Int = 0,
)

/** `PUT /api/farmers/{id}/rule-binding`；[ruleId] 为空即解绑。 */
data class RuleBindingRequest(
    val productId: Long,
    val specId: Long,
    val ruleId: Long? = null,
)

/** 磅秤员改系数：`PUT /api/rules/{id}` 只传 coef。 */
data class RuleCoefPatch(
    val coef: Double,
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

data class TareDto(
    val id: Long,
    val name: String,
    val tareKg: Double,
    val hotkey: Boolean = true,
    val enabled: Boolean = true,
)

/** `POST /api/batches`。磅秤员调用时后端一律取当日，APP 不传日期。 */
data class BatchRequest(
    val date: String? = null,
)

data class BatchDto(
    val batchNo: String,
)

/** `POST /api/orders/quote`（开发规格 5.2、APP-08）。 */
data class QuoteRequest(
    val vendorId: Long,
    val productId: Long,
    val specId: Long,
    val date: String? = null,
    val boxNetKg: List<Double> = emptyList(),
    val trialRuleId: Long? = null,
    val trialCoef: Double? = null,
)

/**
 * 计价预览结果（后端 `PricingService.Result`）。
 * 缺规则或缺基准价时 [unitPrice]/[amount] 为空，[issues] 含 `NO_RULE` / `NO_BASE_PRICE`。
 */
data class QuoteResult(
    val boxCount: Int = 0,
    val totalKg: Double? = null,
    val rawAvgKg: Double? = null,
    val avgKg: Double? = null,
    val standardNetKg: Double? = null,
    val basePrice: Double? = null,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val coefficient: Double? = null,
    val tailKg: Double? = null,
    val priceDiff: Double? = null,
    val unitPrice: Double? = null,
    val amount: Double? = null,
    val roundMode: String? = null,
    val issues: List<String> = emptyList(),
) {
    val noRule: Boolean get() = issues.any { it.equals("NO_RULE", ignoreCase = true) }
    val noBasePrice: Boolean get() = issues.any { it.equals("NO_BASE_PRICE", ignoreCase = true) }
}

/** `POST /api/orders` 一箱。 */
data class SubmitBoxRequest(
    val seq: Int,
    val netKg: Double,
    val grossKg: Double? = null,
    val tareKg: Double? = null,
    val tareName: String? = null,
    val groupNo: Int? = null,
    val groupK: Int? = null,
    val manual: Boolean = false,
    val rawKg: Double? = null,
    val printed: Boolean = false,
)

data class SubmitRequest(
    val batchNo: String,
    val vendorId: Long,
    val productId: Long,
    val specId: Long,
    val warehouseId: Long,
    val boxes: List<SubmitBoxRequest>,
)

/** 提交结果（完成页）。 */
data class SubmittedDto(
    val id: Long = 0,
    val code: String? = null,
    val batchNo: String? = null,
    val status: String? = null,
    val source: String? = null,
    val boxCount: Int = 0,
    val totalNetKg: Double? = null,
    val avgKg: Double? = null,
    val submittedPrice: Double? = null,
    val submittedAmount: Double? = null,
)
