package com.jidan.receiving.data

/** 建单用的档案与规则（不依赖 P2-2 bootstrap；开发规格 5.2）。 */
class CatalogRepository(
    private val client: ApiClient,
) {
    suspend fun farmersByTail(tail: String): List<FarmerDto> =
        client.runApi { this.farmersByTail(tail) }

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

    suspend fun products(): List<ArchiveRowDto> =
        client.runApi { this.archive("products", pageSize = 100) }.items

    suspend fun warehouses(): List<ArchiveRowDto> =
        client.runApi { this.archive("warehouses", pageSize = 100) }.items

    suspend fun specs(): List<SpecDto> =
        client.runApi { this.specs() }.filter { it.enabled }

    suspend fun basePriceBySpec(): Map<Long, Double?> =
        client.runApi { this.basePrices(null) }.items.associate { it.specId to it.price }

    /** 启用且作热键的皮重。 */
    suspend fun tareHotkeys(): List<TareDto> =
        client.runApi { this.tares() }.filter { it.enabled && it.hotkey }

    /** 取当日批次号；失败时抛出，由称重页显示错误。 */
    suspend fun nextBatchNo(): String =
        client.runApi { this.createBatch(BatchRequest()) }.batchNo
}
