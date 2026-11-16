package com.jidan.receiving.ui.create

/** 建单四步纯逻辑（对照 lab/app-sim.html STEPS / farmerResults）。 */

enum class CreateStep { FARMER, PRODUCT, SPEC, WAREHOUSE }

data class CreatePicks(
    val farmerId: Long? = null,
    val productId: Long? = null,
    val specId: Long? = null,
    val warehouseId: Long? = null,
)

fun missingSteps(p: CreatePicks): List<CreateStep> = buildList {
    if (p.farmerId == null) add(CreateStep.FARMER)
    if (p.productId == null) add(CreateStep.PRODUCT)
    if (p.specId == null) add(CreateStep.SPEC)
    if (p.warehouseId == null) add(CreateStep.WAREHOUSE)
}

fun activeStep(p: CreatePicks, open: CreateStep?): CreateStep? =
    open ?: missingSteps(p).firstOrNull()

fun digitsTail(raw: String): String = raw.filter(Char::isDigit).take(4)

/**
 * 选农户后：仅一条启用收购规则时自动带出商品与规格（原型 ACT.farmer）。
 * 返回 Pair(productId, specId)；多条或没有则两者 null。
 */
fun autoFillFromRules(rules: List<Pair<Long, Long>>): Pair<Long?, Long?> =
    if (rules.size == 1) rules[0].first to rules[0].second else null to null

fun autoFillSpecForProduct(rules: List<Pair<Long, Long>>, productId: Long): Long? {
    val hit = rules.filter { it.first == productId }
    return if (hit.size == 1) hit[0].second else null
}
