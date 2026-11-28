package com.jidan.receiving.ui.create

import com.jidan.receiving.data.RuleViewDto

/**
 * 建单三步纯逻辑（对照 lab/app-sim.html STEPS / farmerResults）。
 * 定价规则不绑农户，按名称直接选；选了规则，商品、规格、包装箱与每箱损耗就都定了（附录 E R-1 行）。
 */
enum class CreateStep { FARMER, RULE, WAREHOUSE }

data class CreatePicks(
    val farmerId: Long? = null,
    val ruleId: Long? = null,
    val warehouseId: Long? = null,
)

fun missingSteps(p: CreatePicks): List<CreateStep> = buildList {
    if (p.farmerId == null) add(CreateStep.FARMER)
    if (p.ruleId == null) add(CreateStep.RULE)
    if (p.warehouseId == null) add(CreateStep.WAREHOUSE)
}

fun activeStep(p: CreatePicks, open: CreateStep?): CreateStep? =
    open ?: missingSteps(p).firstOrNull()

fun digitsTail(raw: String): String = raw.filter(Char::isDigit).take(4)

/** 每箱损耗的写法：+0.5 斤、−1 斤；0 或没有写 null。 */
fun lossText(loss: Double?): String? {
    if (loss == null || loss == 0.0) return null
    val abs = kotlin.math.abs(loss)
    val n = if (abs % 1.0 == 0.0) abs.toLong().toString() else abs.toString()
    return (if (loss > 0) "+" else "−") + n + " 斤"
}

/** 规则一行写清它打包了什么：粉蛋 360枚/箱 · 2号包装箱 4 斤 · 每箱 −1 斤。 */
fun packText(r: RuleViewDto): String = buildString {
    append(listOfNotNull(r.productName, r.specName).joinToString(" "))
    if (r.tareName != null) {
        append(" · ").append(r.tareName)
        r.tareJin?.let { t -> append(" ").append(if (t % 1.0 == 0.0) t.toLong().toString() else t.toString()).append(" 斤") }
    }
    lossText(r.lossJin)?.let { append(" · 每箱 ").append(it) }
}

