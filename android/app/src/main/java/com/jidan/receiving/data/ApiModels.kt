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
 * `GET /app/bootstrap`（开发规格 5.2），磅秤员建单与称重要用的东西一次下发：
 * [products]、[specs]（含标准净含量与当日基准价，没录为 null）、[warehouses]、[tares]（后端只下发热键皮重），
 * 以及两张键值表：[settings] 是业务设置（打印时机、标签内容、设备型号、均重取整等），
 * [options] 是高级选项里 APP 生效的两组，两张表合起来交给 [AppOptions.from]。
 * [rules] 是启用的收购规则：建单按名称选，选了就定了商品、规格、包装箱与每箱损耗（附录 E R-1 行）。
 * 列表字段都可空：后端少给一项时当空列表，不让整个解析失败。
 */
data class BootstrapResponse(
    val date: String? = null,
    val products: List<NamedDto>? = null,
    val specs: List<SpecDto>? = null,
    val warehouses: List<NamedDto>? = null,
    val tares: List<TareDto>? = null,
    val settings: Map<String, String?>? = null,
    val options: Map<String, String?>? = null,
    val rules: List<RuleViewDto>? = null,
) {
    fun allValues(): Map<String, String?> = (settings ?: emptyMap()) + (options ?: emptyMap())

    /** 规格 id → 标准净含量（斤）；缺规格或缺 W0 的不在表里。 */
    fun stdNetBySpec(): Map<Long, Double> =
        specs.orEmpty().mapNotNull { sp -> sp.stdNetJin?.let { sp.id to it } }.toMap()
}

data class FarmerDto(
    val id: Long,
    val name: String,
    val hsCode: String? = null,
    val phones: List<String> = emptyList(),
    val matchedPhones: List<String> = emptyList(),
)

/**
 * 定价规则（后端 `RuleService.RuleView`）。规则不绑农户，打包了商品、规格、包装箱（皮重）与每箱损耗调整。
 * [tareBoxId] 为空是改版前建的规则还没设包装箱。
 */
data class RuleViewDto(
    val id: Long,
    val name: String,
    val kind: String? = null,
    val productId: Long,
    val productName: String? = null,
    val specId: Long,
    val specName: String? = null,
    val stdNetJin: Double? = null,
    val tareBoxId: Long? = null,
    val tareName: String? = null,
    val tareJin: Double? = null,
    /** 每箱损耗调整（斤），正数加、负数减 */
    val lossJin: Double? = null,
    val coef: Double? = null,
    val enabled: Boolean = true,
)

/** 磅秤员改规则：`PUT /api/rules/{id}` 只传每斤尾差与每箱损耗，为空的不改。 */
data class RulePatch(
    val coef: Double? = null,
    val lossJin: Double? = null,
)

/** 商品、仓库（bootstrap 的 products、warehouses）。 */
data class NamedDto(
    val id: Long,
    val name: String,
)

/** bootstrap 的规格；[basePrice] 是当日基准价，没录为 null（APP 标“无基准价”）。后端只下发启用的规格。 */
data class SpecDto(
    val id: Long,
    val name: String,
    val eggCount: Int? = null,
    val stdNetJin: Double? = null,
    val basePrice: Double? = null,
)

/** bootstrap 的皮重；后端只下发启用且作热键的。 */
data class TareDto(
    val id: Long,
    val name: String,
    val tareJin: Double,
)

/** `POST /api/batches`。磅秤员调用时后端一律取当日，APP 不传日期。 */
data class BatchRequest(
    val date: String? = null,
)

data class BatchDto(
    val batchNo: String,
)

/** `POST /api/orders/quote`（开发规格 5.2、APP-08）。商品与规格取自规则；trial* 是改规则前的试算。 */
data class QuoteRequest(
    val ruleId: Long,
    val date: String? = null,
    val boxNetJin: List<Double> = emptyList(),
    val trialCoef: Double? = null,
    val trialLossJin: Double? = null,
)

/**
 * 计价预览结果（后端 `PricingService.Result`）。
 * 缺规则或缺基准价时 [unitPrice]/[amount] 为空，[issues] 含 `NO_RULE` / `NO_BASE_PRICE`。
 */
data class QuoteResult(
    val boxCount: Int = 0,
    val totalJin: Double? = null,
    val rawAvgJin: Double? = null,
    val avgJin: Double? = null,
    val standardNetJin: Double? = null,
    val basePrice: Double? = null,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val coefficient: Double? = null,
    /** 规则的每箱损耗调整（斤）；计价时加到每箱净重上 */
    val lossJin: Double? = null,
    val tailJin: Double? = null,
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
    val netJin: Double,
    val grossJin: Double? = null,
    val tareJin: Double? = null,
    val tareName: String? = null,
    val groupNo: Int? = null,
    val groupK: Int? = null,
    val manual: Boolean = false,
    val rawJin: Double? = null,
    val printed: Boolean = false,
)

data class SubmitRequest(
    val batchNo: String,
    val vendorId: Long,
    val ruleId: Long,
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
    val totalNetJin: Double? = null,
    val avgJin: Double? = null,
    val submittedPrice: Double? = null,
    val submittedAmount: Double? = null,
)
