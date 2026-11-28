package com.jidan.receiving.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jidan.receiving.AppContainer
import com.jidan.receiving.BuildConfig
import com.jidan.receiving.core.Pick
import com.jidan.receiving.data.FarmerDto
import com.jidan.receiving.data.NamedDto
import com.jidan.receiving.data.RuleViewDto
import com.jidan.receiving.data.SpecDto
import com.jidan.receiving.data.userMessage
import com.jidan.receiving.ui.update.UpdateOffer
import com.jidan.receiving.ui.update.fetchUpdateOffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateUiState(
    val query: String = "",
    /** 找农户的方式：按名称（好生意里很多农户没填号码）或按手机尾号。 */
    val byName: Boolean = false,
    val name: String = "",
    val nameHits: List<FarmerDto> = emptyList(),
    /** nameHits 对应的输入；与 name 不同说明还在查。 */
    val nameSearched: String = "",
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
    val update: UpdateOffer? = null,
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
        CreateUiState(
            operatorName = container.session.value?.name.orEmpty(),
            byName = container.prefs.findFarmerByName,
        ),
    )
    val ui: StateFlow<CreateUiState> = _ui.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadCatalog()
        loadRecent()
        refreshVersion()
    }

    /** 进入建单页查一次新版本（长期登录的手机不会回登录页）；失败或没有更新就不显示。 */
    fun refreshVersion() {
        viewModelScope.launch {
            val offer = fetchUpdateOffer(BuildConfig.VERSION_CODE) { container.auth.appVersion() }
            _ui.update { it.copy(update = offer) }
        }
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

    /** 切换按名称 / 按尾号找；记住这次的选择，下一单默认同样的方式。 */
    fun setFindByName(byName: Boolean) {
        searchJob?.cancel()
        container.prefs.findFarmerByName = byName
        _ui.update {
            it.copy(
                byName = byName, query = "", farmers = emptyList(), noMatch = "",
                name = "", nameHits = emptyList(), nameSearched = "", error = "",
            )
        }
    }

    /** 按名称边输边搜：停顿一下再查，免得每敲一个字都发请求。结果总是列出来让人点，不自动选（输到一半可能只剩一个）。 */
    fun onName(raw: String) {
        val q = raw.trim()
        _ui.update { it.copy(name = raw, error = "") }
        searchJob?.cancel()
        if (q.isEmpty()) {
            _ui.update { it.copy(nameHits = emptyList(), nameSearched = "") }
            return
        }
        searchJob = viewModelScope.launch {
            delay(NAME_DEBOUNCE_MS)
            try {
                val hits = container.catalog.farmersByName(q)
                _ui.update { it.copy(nameHits = hits, nameSearched = q) }
            } catch (e: Throwable) {
                _ui.update { it.copy(error = e.userMessage(), nameHits = emptyList(), nameSearched = q) }
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
                    name = "",
                    nameHits = emptyList(),
                    nameSearched = "",
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

    companion object {
        const val NAME_DEBOUNCE_MS = 250L
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CreateViewModel(container) as T
    }
}
