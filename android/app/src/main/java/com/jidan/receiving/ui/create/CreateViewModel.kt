package com.jidan.receiving.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jidan.receiving.AppContainer
import com.jidan.receiving.BuildConfig
import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Tare
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
    val specs: List<SpecDto> = emptyList(),
    val warehouses: List<NamedDto> = emptyList(),
    /** 启用的收购规则（bootstrap 下发），按名称选 */
    val rules: List<RuleViewDto> = emptyList(),
    /** 服务器不下发规则（旧后端），规则一步提示更新后端 */
    val oldBackend: Boolean = false,
    val prices: Map<Long, Double?> = emptyMap(),
    val farmerId: Long? = null,
    val farmerName: String? = null,
    val farmerPhone: String? = null,
    val ruleId: Long? = null,
    val warehouseId: Long? = null,
    val warehouseName: String? = null,
    val open: CreateStep? = null,
    val error: String = "",
    val loading: Boolean = false,
    val operatorName: String = "",
    val update: UpdateOffer? = null,
) {
    val picks: CreatePicks
        get() = CreatePicks(farmerId, ruleId, warehouseId)
    val rule: RuleViewDto? get() = rules.firstOrNull { it.id == ruleId }
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
                val rules = cat.rules
                val specs = cat.specs
                val warehouses = cat.warehouses
                val prices = cat.basePrices()
                val lastWh = container.prefs.lastWarehouseId
                val wh = warehouses.firstOrNull { it.id == lastWh }
                _ui.update {
                    it.copy(
                        loading = false,
                        rules = rules,
                        oldBackend = cat.oldBackend,
                        // 选过的规则若已停用或删掉，就当没选
                        ruleId = it.ruleId?.takeIf { id -> rules.any { r -> r.id == id } },
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
                // 旧后端不认 q，只会说“请输入手机号后四位”：换成说清楚是后端要更新；出错时不显示“没有名称含…”
                val msg = e.userMessage().let { if (it.contains("手机号后四位")) OLD_BACKEND else it }
                _ui.update { it.copy(error = msg, nameHits = emptyList(), nameSearched = "") }
            }
        }
    }

    fun selectFarmer(f: FarmerDto) {
        val q = _ui.value.query
        val phone = f.matchedPhones.firstOrNull()
            ?: f.phones.firstOrNull { it.replace("\\D".toRegex(), "").endsWith(q) }
            ?: f.phones.firstOrNull()
        _ui.update {
            it.copy(
                farmerId = f.id,
                farmerName = f.name,
                farmerPhone = phone,
                open = null,
                farmers = emptyList(),
                query = "",
                noMatch = "",
                name = "",
                nameHits = emptyList(),
                nameSearched = "",
            )
        }
    }

    /** 规则不绑农户：按名称直接选（附录 E R-1 行）。 */
    fun selectRule(r: RuleViewDto) {
        _ui.update { it.copy(ruleId = r.id, open = null) }
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

    /** 三步齐了：写入 OrderDraft，调用方导航去称重（P6-4）。商品、规格、标准净含量、包装箱取自所选规则。 */
    fun commitHeader(): Boolean {
        val s = _ui.value
        if (!s.ready) return false
        val r = s.rule ?: return false
        val std = r.stdNetJin ?: s.specs.firstOrNull { it.id == r.specId }?.stdNetJin
        val stdCents = std?.let { Jin.of(it).cents }
        val tare = if (r.tareName != null && r.tareJin != null) Tare(Jin.of(r.tareJin), r.tareName) else null
        container.orderDraft.update {
            it.copy(
                farmer = Pick(s.farmerId!!, s.farmerName!!),
                farmerPhone = s.farmerPhone,
                rule = Pick(r.id, r.name),
                tare = tare,
                tareId = r.tareBoxId,
                product = Pick(r.productId, r.productName.orEmpty()),
                spec = Pick(r.specId, r.specName.orEmpty()),
                stdNetCents = stdCents,
                warehouse = Pick(s.warehouseId!!, s.warehouseName!!),
            )
        }
        container.prefs.lastWarehouseId = s.warehouseId
        return true
    }

    companion object {
        const val NAME_DEBOUNCE_MS = 250L
        const val OLD_BACKEND = "服务器还是旧版本，不支持按名称找农户。请先更新后端，或改用尾号找"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CreateViewModel(container) as T
    }
}
