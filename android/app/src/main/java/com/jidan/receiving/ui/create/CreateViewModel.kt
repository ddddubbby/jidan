package com.jidan.receiving.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jidan.receiving.AppContainer
import com.jidan.receiving.core.Pick
import com.jidan.receiving.data.FarmerDto
import com.jidan.receiving.data.NamedDto
import com.jidan.receiving.data.RuleViewDto
import com.jidan.receiving.data.SpecDto
import com.jidan.receiving.data.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateUiState(
    val query: String = "",
    val farmers: List<FarmerDto> = emptyList(),
    val recent: List<FarmerDto> = emptyList(),
    val noMatch: String = "",
    val products: List<NamedDto> = emptyList(),
    val specs: List<SpecDto> = emptyList(),
    val warehouses: List<NamedDto> = emptyList(),
    val rules: List<RuleViewDto> = emptyList(),
    val prices: Map<Long, Double?> = emptyMap(),
    val farmerId: Long? = null,
    val farmerName: String? = null,
    val farmerPhone: String? = null,
    val productId: Long? = null,
    val productName: String? = null,
    val specId: Long? = null,
    val specName: String? = null,
    val warehouseId: Long? = null,
    val warehouseName: String? = null,
    val open: CreateStep? = null,
    val error: String = "",
    val loading: Boolean = false,
    val operatorName: String = "",
) {
    val picks: CreatePicks
        get() = CreatePicks(farmerId, productId, specId, warehouseId)
    val missing: List<CreateStep> get() = missingSteps(picks)
    val active: CreateStep? get() = activeStep(picks, open)
    val ready: Boolean get() = missing.isEmpty()
}

class CreateViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        CreateUiState(operatorName = container.session.value?.name.orEmpty()),
    )
    val ui: StateFlow<CreateUiState> = _ui.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadCatalog()
        loadRecent()
    }

    fun loadCatalog() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = "") }
            try {
                val cat = container.catalog.catalog()
                val products = cat.products
                val specs = cat.specs
                val warehouses = cat.warehouses
                val prices = cat.basePrices()
                val lastWh = container.prefs.lastWarehouseId
                val wh = warehouses.firstOrNull { it.id == lastWh }
                _ui.update {
                    it.copy(
                        loading = false,
                        products = products,
                        specs = specs,
                        warehouses = warehouses,
                        prices = prices,
                        warehouseId = wh?.id ?: it.warehouseId,
                        warehouseName = wh?.name ?: it.warehouseName,
                    )
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun loadRecent() {
        viewModelScope.launch {
            try {
                val recent = container.catalog.farmersRecent()
                _ui.update { it.copy(recent = recent) }
            } catch (_: Throwable) {
                // 最近农户失败不挡建单
            }
        }
    }

    fun onTail(raw: String) {
        val q = digitsTail(raw)
        _ui.update { it.copy(query = q, noMatch = "", error = "") }
        searchJob?.cancel()
        if (q.length < 4) {
            _ui.update { it.copy(farmers = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            try {
                val hits = container.catalog.farmersByTail(q)
                if (hits.size == 1) {
                    selectFarmer(hits[0])
                    return@launch
                }
                // 无匹配：清空尾号并提示（原型 farmerResults / APP-02）
                if (hits.isEmpty()) {
                    _ui.update { it.copy(farmers = emptyList(), query = "", noMatch = q) }
                    return@launch
                }
                _ui.update { it.copy(farmers = hits, noMatch = "") }
            } catch (e: Throwable) {
                _ui.update { it.copy(error = e.userMessage(), farmers = emptyList()) }
            }
        }
    }

    fun selectFarmer(f: FarmerDto) {
        viewModelScope.launch {
            val q = _ui.value.query
            val phone = f.matchedPhones.firstOrNull()
                ?: f.phones.firstOrNull { it.replace("\\D".toRegex(), "").endsWith(q) }
                ?: f.phones.firstOrNull()
            _ui.update {
                it.copy(
                    farmerId = f.id,
                    farmerName = f.name,
                    farmerPhone = phone,
                    productId = null,
                    productName = null,
                    specId = null,
                    specName = null,
                    open = null,
                    rules = emptyList(),
                    farmers = emptyList(),
                    query = "",
                    noMatch = "",
                )
            }
            try {
                val rules = container.catalog.farmerBuyRules(f.id)
                val pairs = rules.map { it.productId to it.specId }
                val (pid, sid) = autoFillFromRules(pairs)
                val product = _ui.value.products.firstOrNull { it.id == pid }
                val spec = _ui.value.specs.firstOrNull { it.id == sid }
                _ui.update {
                    it.copy(
                        rules = rules,
                        productId = pid,
                        productName = product?.name ?: rules.firstOrNull()?.productName,
                        specId = sid,
                        specName = spec?.name ?: rules.firstOrNull()?.specName,
                    )
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun selectProduct(p: NamedDto) {
        val pairs = _ui.value.rules.map { it.productId to it.specId }
        val sid = autoFillSpecForProduct(pairs, p.id)
        val spec = _ui.value.specs.firstOrNull { it.id == sid }
            ?: _ui.value.rules.firstOrNull { it.productId == p.id && it.specId == sid }
                ?.let { SpecDto(it.specId, it.specName ?: "") }
        _ui.update {
            it.copy(
                productId = p.id,
                productName = p.name,
                specId = sid,
                specName = spec?.name,
                open = null,
            )
        }
    }

    fun selectSpec(s: SpecDto) {
        _ui.update { it.copy(specId = s.id, specName = s.name, open = null) }
    }

    fun selectWarehouse(w: NamedDto) {
        _ui.update { it.copy(warehouseId = w.id, warehouseName = w.name, open = null) }
    }

    fun edit(step: CreateStep) {
        _ui.update { it.copy(open = step) }
    }

    fun nudge() {
        _ui.update { it.copy(open = null) }
    }

    /** 四步齐了：写入 OrderDraft，调用方导航去称重（P6-4）。 */
    fun commitHeader(): Boolean {
        val s = _ui.value
        if (!s.ready) return false
        val std = s.specs.firstOrNull { it.id == s.specId }?.stdNetKg
        val stdCents = std?.let { com.jidan.receiving.core.Kg.of(it).cents }
        container.orderDraft.update {
            it.copy(
                farmer = Pick(s.farmerId!!, s.farmerName!!),
                farmerPhone = s.farmerPhone,
                product = Pick(s.productId!!, s.productName!!),
                spec = Pick(s.specId!!, s.specName!!),
                stdNetCents = stdCents,
                warehouse = Pick(s.warehouseId!!, s.warehouseName!!),
            )
        }
        container.prefs.lastWarehouseId = s.warehouseId
        return true
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CreateViewModel(container) as T
    }
}
