package com.jidan.receiving.data

/** [CatalogRepository.catalog] 的结果。 */
data class Catalog(
    val products: List<NamedDto>,
    val specs: List<SpecDto>,
    val warehouses: List<NamedDto>,
    val tares: List<TareDto>,
    /** 启用的收购规则，建单时按名称选 */
    val rules: List<RuleViewDto> = emptyList(),
    /** bootstrap 里没有 rules 字段：服务器还是 0.2.0 之前的后端 */
    val oldBackend: Boolean = false,
) {
    /** 规格 → 当日基准价；没录的规格不在表里（APP 标“无基准价”）。 */
    fun basePrices(): Map<Long, Double?> = specs.associate { it.id to it.basePrice }
}

fun BootstrapResponse.toCatalog(): Catalog = Catalog(
    products = products.orEmpty(),
    specs = specs.orEmpty(),
    warehouses = warehouses.orEmpty(),
    tares = tares.orEmpty(),
    rules = rules.orEmpty().filter { it.enabled && (it.kind == null || it.kind.equals("BUY", ignoreCase = true)) },
    oldBackend = rules == null,
)

/** 建单用的档案与规则：商品、规格、仓库、皮重、启用的收购规则取自 bootstrap，农户与改规则走磅秤员可调的接口（开发规格 5.2）。 */
class CatalogRepository(
    private val client: ApiClient,
) {
    suspend fun farmersByTail(tail: String): List<FarmerDto> =
        client.runApi { this.farmersByTail(tail) }

    suspend fun farmersByName(q: String): List<FarmerDto> =
        client.runApi { this.farmersByName(q) }

    suspend fun farmersRecent(): List<FarmerDto> =
        client.runApi { this.farmersRecent() }

    /** 某商品规格下启用的收购规则（单据里换规则用：换了也不动已称的箱）。 */
    suspend fun buyRules(productId: Long, specId: Long): List<RuleViewDto> =
        client.runApi { this.rules(kind = "BUY", productId = productId, specId = specId, enabled = true) }
            .filter { it.enabled }

    /** 改规则的每斤尾差、每箱损耗；为空的不改。 */
    suspend fun updateRule(ruleId: Long, coef: Double?, lossJin: Double?): RuleViewDto =
        client.runApi { this.updateRule(ruleId, RulePatch(coef, lossJin)) }

    /**
     * 商品、规格（含标准净含量与当日基准价）、仓库、热键皮重，一次从 bootstrap 取齐。
     * 磅秤员账号调不了档案、规格、基准价、皮重这些仅管理员的接口（开发规格 5.2）。
     */
    suspend fun catalog(): Catalog = client.runApi { this.bootstrap() }.toCatalog()

    /** 作热键的皮重（后端只下发这一类）。 */
    suspend fun tareHotkeys(): List<TareDto> = catalog().tares

    /** 取当日批次号；失败时抛出，由称重页显示错误。 */
    suspend fun nextBatchNo(): String =
        client.runApi { this.createBatch(BatchRequest()) }.batchNo
}
