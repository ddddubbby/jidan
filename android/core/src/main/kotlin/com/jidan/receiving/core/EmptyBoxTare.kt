package com.jidan.receiving.core

/** 称重皮（称空箱）的当下状态：按钮能不能按，以及按钮上要写的读数。 */
data class EmptyBoxState(
    /** 秤已连接。 */
    val connected: Boolean,
    /** 此刻台面总重（未扣皮）；还没有读数时为 null。 */
    val platform: Jin?,
    val stable: Boolean,
) {
    val available: Boolean get() = EmptyBoxTare.isEmptyBox(connected, platform, stable)
}

/**
 * 称重皮判定（v7 `APP-04`：秤上确实是一只空箱时才可操作，按该读数去皮）。
 * 判定条件照原型 `lab/app-sim.html` 的 `live()`：秤已连接、读数稳定、台面在 0.4 斤（0.2kg）以上、不到 20 斤（10kg）。
 * 更轻的是空秤或杂物，20 斤及以上的是装了蛋的箱子或不止一只箱子。
 */
object EmptyBoxTare {
    val MIN = Jin.of(0.4)
    val MAX = Jin.of(20.0)
    const val NAME = "空箱"

    fun isEmptyBox(connected: Boolean, platform: Jin?, stable: Boolean): Boolean =
        connected && stable && platform != null && platform >= MIN && platform < MAX

    /** 按下后每箱皮重取当时台面读数。 */
    fun tareOf(platform: Jin): Tare = Tare(platform, NAME)

    /** 按钮文案：能按时写按哪个读数去皮，不能按时写秤上现在是多少（没连上或没读数写 `--.-`）。 */
    fun label(s: EmptyBoxState): String {
        val shown = if (s.connected) s.platform?.format1() else null
        return if (s.available) {
            "称空箱 · 按 $shown 斤 去皮"
        } else {
            "称空箱 · 秤上 ${shown ?: "--.-"} 斤"
        }
    }
}

/**
 * 新开一单时的皮重选项（v7 `APP-04`：上一单用过的包装箱排在首位，但不自动选中）。
 * 这里只排序；开单时一律没有皮重，称重框保持未设皮重，直到磅秤员自己点一项。
 */
object TareChoices {
    /** 上一单用的排首位，其余保持原顺序。[lastTareId] 为 null 或不在列表里时原样返回。 */
    fun <T> ordered(all: List<T>, lastTareId: Long?, idOf: (T) -> Long): List<T> {
        val (last, rest) = all.partition { lastTareId != null && idOf(it) == lastTareId }
        return last + rest
    }
}
