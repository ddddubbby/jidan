package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Tare
import com.jidan.receiving.core.TareChoices
import com.jidan.receiving.data.TareDto

/**
 * 新开一单时皮重卡片的起始状态（v7 `APP-04`：上一单用过的包装箱排在首位，但不自动选中）。
 * 不管 [lastTareId] 有没有值，[tare] 都是 null、卡片都展开：称重框保持未设皮重，磅秤员点了一项才解除锁定。
 */
data class TareStart(
    /** 皮重热键，上一单用的排首位。 */
    val tares: List<TareDto>,
    /** 上一单用的热键 id；没有或已不在热键里为 null。 */
    val lastTareId: Long?,
    val tare: Tare? = null,
    val showPicker: Boolean = true,
) {
    companion object {
        fun of(hotkeys: List<TareDto>, lastTareId: Long?): TareStart {
            val last = lastTareId?.takeIf { id -> hotkeys.any { it.id == id } }
            return TareStart(TareChoices.ordered(hotkeys, last) { it.id }, last)
        }
    }
}
