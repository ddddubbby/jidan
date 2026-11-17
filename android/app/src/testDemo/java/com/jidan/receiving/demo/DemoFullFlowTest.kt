package com.jidan.receiving.demo

import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.WeighPhase
import com.jidan.receiving.data.ApiClient
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.AuthRepository
import com.jidan.receiving.data.BootstrapResponse
import com.jidan.receiving.data.CatalogRepository
import com.jidan.receiving.data.OrderDraftStore
import com.jidan.receiving.data.OrderRepository
import com.jidan.receiving.data.SessionStore
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.fake.FakePrinter
import com.jidan.receiving.device.fake.FakeScale
import com.jidan.receiving.ui.summary.formatMoney
import com.jidan.receiving.ui.summary.formatNum
import com.jidan.receiving.ui.weigh.OpenApiContract
import com.jidan.receiving.ui.weigh.ReceivingDeps
import com.jidan.receiving.ui.weigh.ReceivingNav
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 演示版全流程：建单 → 设皮 → FakeScale 放 10 箱 → 逐箱打印 → 确认 → 汇总（181 元/箱，1,810 元）→ 提交 → 完成页
 * → 再收一单。真跑 `ReceivingViewModel`、真的 `ApiClient`（Retrofit + OkHttp），接口由离线假后端 [DemoBackend]
 * 在拦截器里回答；秤与打印机是模拟的。响应同时按 `api/openapi.json` 核对。未经实物验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoFullFlowTest {

    private val today = LocalDate.of(2026, 11, 17)
    private lateinit var backend: DemoBackend
    private lateinit var contract: OpenApiContract
    private lateinit var draftDir: File

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        backend = DemoBackend { today }
        contract = OpenApiContract(File("../../api/openapi.json").also { check(it.exists()) })
        draftDir = Files.createTempDirectory("demo-flow").toFile()
    }

    @AfterTest
    fun tearDown() {
        draftDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** 包一层：假后端的每个响应都过一遍 openapi 契约。 */
    private val checked = Interceptor { chain ->
        val resp: Response = backend.intercept(chain)
        if (resp.code == 200) {
            val path = chain.request().url.encodedPath.replace(Regex("/\\d+(?=/|$)"), "/{id}")
            contract.checkResponse(chain.request().method, path, resp.peekBody(1 shl 20).string())
        }
        resp
    }

    private inner class Deps(scope: kotlinx.coroutines.CoroutineScope) : ReceivingDeps {
        val client = ApiClient(
            baseUrl = { "http://demo.invalid/" },
            token = { "demo-token" },
            onUnauthenticated = {},
            demoInterceptor = checked,
        )
        override val catalog = CatalogRepository(client)
        override val orders = OrderRepository(client)
        override val orderDraft = OrderDraftStore()
        override val sessionStore = SessionStore(File(draftDir, "receiving_draft.json"))
        private val _options = MutableStateFlow(AppOptions.defaults())
        override val options: StateFlow<AppOptions> = _options
        override val scale = FakeScale(scope, FakeScale.MINE_ID)
        override val printer = FakePrinter(scope, FakePrinter.MINE_ID)
        override var lastTareId: Long? = null
        override var resumeSummaryAfterLogin = false

        override suspend fun fetchBootstrap(): BootstrapResponse {
            val b = AuthRepository(client).bootstrap()
            _options.value = AppOptions.from(b.allValues())
            return b
        }
    }

    private suspend fun TestScope.connectDevices(deps: Deps) {
        val a = launch { deps.scale.connect(FakeScale.MINE_ID) }
        val b = launch { deps.printer.connect(FakePrinter.MINE_ID) }
        advanceTimeBy(FakeScale.CONNECT_MS + 1)
        runCurrent()
        a.join()
        b.join()
        assertEquals(LinkStatus.CONNECTED, deps.scale.linkStatus.value)
        assertEquals(LinkStatus.CONNECTED, deps.printer.linkStatus.value)
    }

    /** 接口走真的线程，所以一边跑虚拟时间里排好的任务，一边真等一小会儿。 */
    private suspend fun TestScope.until(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            runCurrent()
            if (cond()) return
            check(contract.violations.isEmpty()) { "等 $what 时接口对不上契约：${contract.violations.toList()}" }
            check(System.nanoTime() < deadline) { "等不到：$what" }
            withContext(Dispatchers.Default) { delay(3) }
        }
    }

    private fun TestScope.settle(ms: Long = FakeScale.UNSTABLE_MS + 200) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** 建单页做的事：尾号 1234 → 李四，规则自动带出粉蛋 360枚/箱，仓库取第一个。 */
    private suspend fun Deps.createOrderForLiSi() {
        val cat = catalog.catalog()
        val hits = catalog.farmersByTail("1234")
        assertEquals(listOf("李四", "王秀英"), hits.map { it.name })
        val farmer = hits.first()
        val rule = catalog.farmerBuyRules(farmer.id).single()
        assertEquals("粉蛋李四收购定价方案", rule.name)
        val product = cat.products.first { it.id == rule.productId }
        val spec = cat.specs.first { it.id == rule.specId }
        val warehouse = cat.warehouses.first()
        orderDraft.update {
            it.copy(
                farmer = Pick(farmer.id, farmer.name),
                product = Pick(product.id, product.name),
                spec = Pick(spec.id, spec.name),
                stdNetCents = spec.stdNetKg?.let { kg -> Kg.of(kg).cents },
                warehouse = Pick(warehouse.id, warehouse.name),
            )
        }
        assertTrue(orderDraft.header.value.ready)
        assertEquals("粉蛋", product.name)
        assertEquals("360枚/箱", spec.name)
        assertEquals(4500, orderDraft.header.value.stdNetCents)
    }

    private suspend fun TestScope.weighTenBoxes(deps: Deps, vm: ReceivingViewModel, batchNo: String) {
        deps.createOrderForLiSi()
        connectDevices(deps)
        vm.startFromDraft()
        until("进入称重") { vm.ui.value.ready }
        assertEquals(batchNo, vm.ui.value.batchNo)
        assertEquals(WeighPhase.LOCKED, vm.ui.value.phase)
        vm.selectTare(vm.ui.value.tares.first { it.name == "2号包装箱" })
        settle(500)
        for (net in FakeScale.CASE_NETS_C) {
            deps.scale.placeOneBox(net)
            settle()
            deps.scale.removeLoad()
            settle()
        }
        settle(10 * 400L)
    }

    @Test
    fun liSiFromCreateToDoneThenAnotherOrder() = runTest {
        val deps = Deps(backgroundScope)
        val vm = ReceivingViewModel(deps, clock = { currentTime })
        val store = androidx.lifecycle.ViewModelStore().also { it.put("vm", vm) }
        val navs = java.util.concurrent.CopyOnWriteArrayList<ReceivingNav>()
        backgroundScope.launch { vm.nav.collect { navs += it } }
        runCurrent()
        try {
            weighTenBoxes(deps, vm, "SG20261117-1")
            val ui = vm.ui.value
            assertEquals(10, ui.count)
            assertEquals("460.5", ui.totalNet.format())
            assertEquals(0, ui.unprinted)

            // 确认 → 汇总：均重 46，181 元/箱，1,810 元
            vm.onConfirmWeighing()
            until("计价回来") { vm.summary.value.quote != null }
            assertEquals(ReceivingNav.ToSummary, navs.single())
            val q = assertNotNull(vm.summary.value.quote)
            assertEquals("46", formatNum(q.avgKg))
            assertEquals("181", formatNum(q.unitPrice))
            assertEquals("1,810", formatMoney(q.amount))
            assertEquals("粉蛋李四收购定价方案", q.ruleName)
            assertTrue(vm.summary.value.canSubmit)

            // 规则弹层：候选里有通用规则（粉蛋 360）与王秀英的规则，改系数后汇总随之变化
            vm.loadRuleSheet()
            until("规则弹层") { !vm.ruleSheet.value.loading && vm.ruleSheet.value.candidates.isNotEmpty() }
            assertEquals("粉蛋李四收购定价方案", vm.ruleSheet.value.boundRuleName)
            assertEquals(setOf("粉蛋360通用收购方案", "粉蛋王秀英收购定价方案"), vm.ruleSheet.value.candidates.map { it.rule.name }.toSet())

            // 提交 → 完成页，仍是 181 / 1,810
            vm.submitOrder()
            until("提交完成") { navs.contains(ReceivingNav.ToDone) }
            val d = vm.done.value
            assertEquals("李四", d.farmerName)
            assertEquals(10, d.boxCount)
            assertEquals("181", formatNum(d.unitPrice))
            assertEquals("1,810", formatMoney(d.amount))
            assertFalse(deps.sessionStore.exists())

            // 再收一单：批次号接着往下数
            vm.clear()
            deps.orderDraft.clear()
            deps.createOrderForLiSi()
            vm.startFromDraft()
            until("第二单进入称重") { vm.ui.value.ready && vm.ui.value.batchNo == "SG20261117-2" }
            assertNull(vm.ui.value.tare)
            assertEquals(0, vm.ui.value.boxes.size)
            assertEquals(emptyList(), contract.violations.toList())
        } finally {
            store.clear()
        }
    }
}
