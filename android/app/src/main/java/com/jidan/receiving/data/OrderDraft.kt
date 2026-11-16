package com.jidan.receiving.data

import com.jidan.receiving.core.Pick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 建单四项（进入称重前）。P6-4 起再扩成完整 ReceivingViewModel / Session。
 */
data class OrderHeader(
    val farmer: Pick? = null,
    val farmerPhone: String? = null,
    val product: Pick? = null,
    val spec: Pick? = null,
    val warehouse: Pick? = null,
) {
    val ready: Boolean
        get() = farmer != null && product != null && spec != null && warehouse != null
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
