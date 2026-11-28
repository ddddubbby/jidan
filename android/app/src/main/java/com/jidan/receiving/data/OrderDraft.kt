package com.jidan.receiving.data

import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Tare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 建单三步（农户、定价规则、仓库）选好后、进入称重前的表头。
 * 商品、规格、标准净含量、包装箱都取自所选规则（附录 E R-1 行）。
 */
data class OrderHeader(
    val farmer: Pick? = null,
    val farmerPhone: String? = null,
    val rule: Pick? = null,
    /** 规则打包的包装箱；开单即设为皮重。改版前的规则可能没有，称重页再选 */
    val tare: Tare? = null,
    val tareId: Long? = null,
    val product: Pick? = null,
    val spec: Pick? = null,
    /** 规格标准净含量 W0（0.01斤）；称重状态机用。 */
    val stdNetCents: Int? = null,
    val warehouse: Pick? = null,
) {
    val ready: Boolean
        get() = farmer != null && rule != null && product != null && spec != null && warehouse != null
}

class OrderDraftStore {
    private val _header = MutableStateFlow(OrderHeader())
    val header: StateFlow<OrderHeader> = _header.asStateFlow()

    fun update(transform: (OrderHeader) -> OrderHeader) {
        _header.value = transform(_header.value)
    }

    fun clear() {
        _header.value = OrderHeader()
    }
}
