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

    suspend fun products(): List<ArchiveRowDto> =
        client.runApi { this.archive("products", pageSize = 100) }.items

    suspend fun warehouses(): List<ArchiveRowDto> =
        client.runApi { this.archive("warehouses", pageSize = 100) }.items

    suspend fun specs(): List<SpecDto> =
        client.runApi { this.specs() }.filter { it.enabled }

    suspend fun basePriceBySpec(): Map<Long, Double?> =
        client.runApi { this.basePrices(null) }.items.associate { it.specId to it.price }
}
