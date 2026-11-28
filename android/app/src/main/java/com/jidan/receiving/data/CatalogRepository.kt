package com.jidan.receiving.data

/** [CatalogRepository.catalog] 的结果。 */
data class Catalog(
    val products: List<NamedDto>,
    val specs: List<SpecDto>,
    val warehouses: List<NamedDto>,
    val tares: List<TareDto>,
) {
    /** 规格 → 当日基准价；没录的规格不在表里（APP 标“无基准价”）。 */
    fun basePrices(): Map<Long, Double?> = specs.associate { it.id to it.basePrice }
}

fun BootstrapResponse.toCatalog(): Catalog = Catalog(
    products = products.orEmpty(),
    specs = specs.orEmpty(),
    warehouses = warehouses.orEmpty(),
    tares = tares.orEmpty(),
)

/** 建单用的档案与规则：商品、规格、仓库、皮重取自 bootstrap，规则与农户走磅秤员可调的接口（开发规格 5.2）。 */
class CatalogRepository(
    private val client: ApiClient,
) {
    suspend fun farmersByTail(tail: String): List<FarmerDto> =
        client.runApi { this.farmersByTail(tail) }

    suspend fun farmersByName(q: String): List<FarmerDto> =
        client.runApi { this.farmersByName(q) }

    suspend fun farmersRecent(): List<FarmerDto> =
        client.runApi { this.farmersRecent() }

    /** 启用的收购规则（kind = BUY）。 */
    suspend fun farmerBuyRules(farmerId: Long): List<RuleViewDto> =
        client.runApi { this.farmerRules(farmerId) }
            .filter { it.enabled && it.kind.equals("BUY", ignoreCase = true) }

    /** 某商品规格下启用的收购规则列表（含绑定户数）。 */
    suspend fun buyRules(productId: Long, specId: Long): List<RuleViewDto> =
        client.runApi { this.rules(kind = "BUY", productId = productId, specId = specId) }
            .filter { it.enabled }

    suspend fun updateRuleCoef(ruleId: Long, coef: Double): RuleViewDto =
        client.runApi { this.updateRule(ruleId, RuleCoefPatch(coef)) }

    /** [ruleId] 为空即解绑该商品规格上的采购规则。 */
    suspend fun bindFarmerRule(farmerId: Long, productId: Long, specId: Long, ruleId: Long?): List<RuleViewDto> =
        client.runApi {
            this.bindFarmerRule(farmerId, RuleBindingRequest(productId, specId, ruleId))
        }

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
