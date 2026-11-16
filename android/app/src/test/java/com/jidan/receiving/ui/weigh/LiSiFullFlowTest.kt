package com.jidan.receiving.ui.weigh

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import com.jidan.receiving.device.DeviceScaleReading
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrintPayload
import com.jidan.receiving.device.fake.FakePrinter
import com.jidan.receiving.device.fake.FakeScale
import com.jidan.receiving.ui.summary.formatMoney
import java.io.File
import java.nio.file.Files
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * 李四全流程（v7 第 6 章算例）：建单 → 设皮 → FakeScale 放箱 → 自动记录 → 逐箱打印 → 确认 → 计价 → 提交。
 *
 * 真跑 `ReceivingViewModel`、`WeighController`、`PrintQueue`、`WeighingMachine`，设备是真的 `FakeScale`、`FakePrinter`，
 * 接口是真的 Retrofit + OkHttp，对着 MockWebServer 按 `api/openapi.json` 的形状回放 bootstrap、batches、quote、orders。
 * 只测逻辑与接口契约，不测布局。时间是虚拟的（`runTest`），所以放 10 箱不用真等。
 * 秤、打印机、后端都是模拟的，未经实物验证；接口回放的数字取自后端 `BenchmarkFlowTest`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiSiFullFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var contract: OpenApiContract
    private lateinit var draftDir: File
    private val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        contract = OpenApiContract(File("../../api/openapi.json").also { check(it.exists()) { "找不到 api/openapi.json" } })
        draftDir = Files.createTempDirectory("lisi-flow").toFile()
        server = MockWebServer()
        server.dispatcher = replay
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        draftDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- 回放的后端

    private class Call(val method: String, val path: String, val query: String, val body: String, val auth: String?) {
        val json: JsonObject get() = JsonParser.parseString(body).asJsonObject
    }

    private val replay = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val method = request.method.orEmpty()
            val full = request.path.orEmpty()
            val path = full.substringBefore('?')
            val query = full.substringAfter('?', "")
            val body = request.body.readUtf8()
            val call = Call(method, path, query, body, request.getHeader("Authorization"))
            calls += call
            contract.checkRequest(method, path, body)
            val reply: String = when ("$method $path") {
                "GET /api/farmers" -> FARMERS
                "GET /api/app/bootstrap" -> BOOTSTRAP
                "POST /api/batches" -> BATCH
                "POST /api/orders/quote" -> quoteFor(call)
                "POST /api/orders" -> submittedFor(call)
                else -> return contract.violations.add("回放里没有这个接口：$method $path").let {
                    MockResponse().setResponseCode(404)
                        .setBody("""{"code":"NOT_FOUND","message":"没有这个接口：$method $path"}""")
                }
            }
            if (reply.startsWith("ERROR ")) {
                contract.violations += reply.drop(6)
                return MockResponse().setResponseCode(400).setBody("""{"code":"BAD_REQUEST","message":"${reply.drop(6)}"}""")
            }
            contract.checkResponse(method, path, reply)
            return MockResponse().setHeader("Content-Type", "application/json").setBody(reply)
        }
    }

    /** 回放哪份计价：按请求里的 10 个净重认。认不出就报 400，测试会因此失败。 */
    private fun quoteFor(call: Call): String = when (netsOf(call.json.getAsJsonArray("boxNetKg"))) {
        LISI_NETS -> QUOTE_LISI
        EDITED_NETS -> QUOTE_EDITED
        ONE_BOX_NETS -> QUOTE_ONE_BOX
        else -> "ERROR 回放里没有这组净重的计价：${call.json.get("boxNetKg")}"
    }

    private fun submittedFor(call: Call): String = when (netsOf(call.json.getAsJsonArray("boxes").map { it.asJsonObject.get("netKg") })) {
        LISI_NETS -> SUBMITTED_LISI
        EDITED_NETS -> SUBMITTED_EDITED
        ONE_BOX_NETS -> SUBMITTED_ONE_BOX
        else -> "ERROR 回放里没有这组净重的提交结果"
    }

    private fun netsOf(a: Iterable<com.google.gson.JsonElement>): List<Int> = a.map { Kg.of(it.asDouble).cents }

    // ---------------------------------------------------------------- 组装

    /** 记下打到打印机的每一张标签，其余全交给真的 [FakePrinter]。 */
    private class RecordingPrinter(val inner: FakePrinter) : LabelPrinter by inner {
        val labels = mutableListOf<PrintPayload>()
        override suspend fun print(payload: PrintPayload): PrintOutcome {
            val out = inner.print(payload)
            if (out is PrintOutcome.Success) labels += payload
            return out
        }
    }

    private inner class Deps(scope: kotlinx.coroutines.CoroutineScope) : ReceivingDeps {
        // 与 AppContainer 一样：真的 ApiClient（Retrofit + OkHttp），地址指向回放服务器
        val client = ApiClient(
            baseUrl = { server.url("/").toString() },
            token = { TOKEN },
            onUnauthenticated = {},
        )
        override val catalog = CatalogRepository(client)
        override val orders = OrderRepository(client)
        override val orderDraft = OrderDraftStore()
        override val sessionStore = SessionStore(File(draftDir, "receiving_draft.json"))
        private val _options = MutableStateFlow(AppOptions.defaults())
        override val options: StateFlow<AppOptions> = _options
        override val scale = FakeScale(scope, FakeScale.MINE_ID)
        val fakePrinter = FakePrinter(scope, FakePrinter.MINE_ID)
        override val printer = RecordingPrinter(fakePrinter)
        override var lastTareId: Long? = null
        override var resumeSummaryAfterLogin = false

        override suspend fun fetchBootstrap(): BootstrapResponse {
            val b = AuthRepository(client).bootstrap()
            _options.value = AppOptions.from(b.allValues())
            return b
        }
    }

    /** 连上秤和打印机（模拟台里 LinkKeeper 做的事）。 */
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

    /** 等某个条件成立。接口回放走真的线程，所以这里一边跑虚拟时间里已排好的任务，一边真等一小会儿。 */
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

    /** 建单：照 `CreateViewModel` 的做法，用 bootstrap 里的商品、规格（含 W0）、仓库和尾号查到的农户写 OrderDraft。 */
    private suspend fun Deps.createOrder(): Pair<Long, Long> {
        val cat = catalog.catalog()
        val farmer = catalog.farmersByTail("1234").first { it.name == "李四" }
        val product = cat.products.first { it.name == "粉蛋" }
        val spec = cat.specs.first { it.name == "360枚/箱" }
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
        assertEquals(4500, orderDraft.header.value.stdNetCents) // 45kg
        return product.id to spec.id
    }

    /** 开单、选 2 号包装箱、放 10 箱，等自动记录与逐箱打印都完成。 */
    private suspend fun TestScope.weighLiSi(deps: Deps, vm: ReceivingViewModel) {
        deps.createOrder()
        connectDevices(deps)
        vm.startFromDraft()
        until("进入称重") { vm.ui.value.ready }
        val ui = vm.ui.value
        assertEquals("李四", ui.farmerName)
        assertEquals("粉蛋", ui.productName)
        assertEquals("360枚/箱", ui.specName)
        assertEquals("1号仓库", ui.warehouseName)
        assertEquals("SG20261116-1", ui.batchNo)
        // 新开一单不自动套皮重：卡片展开，称重框保持锁定，点了 2 号包装箱才解锁
        assertNull(ui.tare)
        assertTrue(ui.showTarePicker)
        assertEquals(WeighPhase.LOCKED, ui.phase)
        val tare2 = ui.tares.first { it.name == "2号包装箱" }
        vm.selectTare(tare2)
        settle(500)
        assertEquals("2号包装箱", vm.ui.value.tare?.name)
        assertEquals(Kg.of(2.0), vm.ui.value.tare?.unit)

        for (net in FakeScale.CASE_NETS_C) {
            deps.scale.placeOneBox(net)
            settle()
            deps.scale.removeLoad()
            settle()
        }
        // 逐箱打印：每箱 300ms 出纸加 60ms 间隔，留足时间
        settle(10 * 400L)
    }

    /**
     * 建 ViewModel 跑 [block]，不管成败最后都清掉它：viewModelScope 里有每 100ms 一次的 tick，
     * 不取消的话 `runTest` 收尾时会在虚拟时间里空转下去。
     */
    private fun flow(block: suspend TestScope.(Deps, ReceivingViewModel, List<ReceivingNav>) -> Unit) = runTest {
        val deps = Deps(backgroundScope)
        val vm = ReceivingViewModel(deps, clock = { currentTime })
        val store = androidx.lifecycle.ViewModelStore().also { it.put("vm", vm) }
        val navs = java.util.concurrent.CopyOnWriteArrayList<ReceivingNav>()
        backgroundScope.launch { vm.nav.collect { navs += it } }
        runCurrent()
        try {
            block(deps, vm, navs)
        } finally {
            store.clear()
        }
    }

    // ---------------------------------------------------------------- 测试

    @Test
    fun liSiTenBoxesFromBootstrapToSubmit() = flow { deps, vm, navs ->
        weighLiSi(deps, vm)

        // 建单与开单的接口：bootstrap 取档案与选项，batches 取批次号，皮重来自 bootstrap
        assertTrue(calls.any { it.method == "POST" && it.path == "/api/batches" })
        assertTrue(calls.filter { it.path == "/api/app/bootstrap" }.size >= 2)
        assertEquals("tail=1234", calls.first { it.path == "/api/farmers" }.query)

        // 流水：46.5 … 47.8，每箱 k = 1，合计 460.5，实时均重 46（原始 46.05）
        val ui = vm.ui.value
        assertEquals(FakeScale.CASE_NETS_C.toList(), ui.boxes.map { it.net.cents })
        assertEquals(
            listOf("46.5", "45.8", "46", "46", "46", "45.6", "45.6", "45.6", "45.6", "47.8"),
            ui.boxes.map { it.net.format() },
        )
        assertEquals((1..10).toList(), ui.boxes.map { it.seq })
        assertTrue(ui.boxes.all { it.tare == Kg.of(2.0) && it.tareName == "2号包装箱" && !it.manual })
        assertEquals(10, ui.count)
        assertEquals("460.5", ui.totalNet.format())
        assertEquals(Kg.of(46.0), ui.avg(vm.roundRule))
        assertEquals(4605, ui.totalNet.cents / ui.count) // 原始均重 46.05
        assertTrue(ui.scaleConnected)
        assertEquals("", ui.error)
        assertEquals("", ui.printError)

        // 默认逐箱打印：10 张，序号 1..10 各一张，标签上有净重、批次、箱序号，没有均重
        val labels = deps.printer.labels
        assertEquals(10, labels.size)
        assertEquals(0, ui.unprinted)
        assertTrue(ui.boxes.all { it.printed })
        labels.forEachIndexed { i, payload ->
            val text = (payload as PrintPayload.Text).tspl
            assertTrue("净重：${ui.boxes[i].net.format()}kg" in text, "第 ${i + 1} 张：$text")
            assertTrue("批次：SG20261116-1" in text, text)
            assertTrue("第${i + 1}箱" in text, text)
            assertFalse("均重" in text, "标签上不该有均重：$text")
        }

        // 确认计量完毕：锁流水，拉计价，进汇总页
        vm.onConfirmWeighing()
        until("计价回来") { vm.summary.value.quote != null }
        assertEquals(ReceivingNav.ToSummary, navs.single())
        // 确认后流水只读：此时加一箱不生效（ui.locked 要等下一次 publish 才刷新，这里看行为不看那个标志）
        vm.addManual(50.0)
        assertEquals(10, vm.ui.value.boxes.size)
        val quoteReq = calls.last { it.path == "/api/orders/quote" }.json
        assertEquals(10, quoteReq.getAsJsonArray("boxNetKg").size())
        assertEquals(LISI_NETS, netsOf(quoteReq.getAsJsonArray("boxNetKg")))
        assertEquals("2026-11-16", quoteReq.get("date").asString)
        assertFalse(quoteReq.has("trialRuleId"))
        assertFalse(quoteReq.has("trialCoef"))

        // 回放 181 与 1810：汇总页状态
        val s = vm.summary.value
        val q = assertNotNull(s.quote)
        assertEquals(10, q.boxCount)
        assertEquals("46", formatMoney(q.avgKg))
        assertEquals("181", formatMoney(q.unitPrice))
        assertEquals("1810", formatMoney(q.amount))
        assertEquals("李四", s.farmerName)
        assertEquals("SG20261116-1", s.batchNo)
        assertTrue(s.canSubmit)
        assertEquals(0, s.unprinted)
        assertEquals("", s.error)

        // 提交
        vm.submitOrder()
        until("提交完成") { navs.contains(ReceivingNav.ToDone) }
        val body = calls.last { it.method == "POST" && it.path == "/api/orders" }.json
        assertEquals("SG20261116-1", body.get("batchNo").asString)
        val boxes = body.getAsJsonArray("boxes")
        assertEquals(10, boxes.size())
        assertEquals((1..10).toList(), boxes.map { it.asJsonObject.get("seq").asInt })
        assertEquals(LISI_NETS, netsOf(boxes.map { it.asJsonObject.get("netKg") }))
        boxes.forEach { e ->
            val b = e.asJsonObject
            assertEquals(2.0, b.get("tareKg").asDouble, 1e-9)
            assertEquals("2号包装箱", b.get("tareName").asString)
            assertEquals(false, b.get("manual").asBoolean)
            assertTrue(b.get("rawKg") == null || b.get("rawKg").isJsonNull)
            assertEquals(true, b.get("printed").asBoolean)
            assertEquals(b.get("netKg").asDouble + 2.0, b.get("grossKg").asDouble, 1e-9)
            assertEquals(1, b.get("groupK").asInt)
        }
        assertEquals(11L, body.get("vendorId").asLong)
        assertEquals(21L, body.get("productId").asLong)
        assertEquals(31L, body.get("specId").asLong)
        assertEquals(41L, body.get("warehouseId").asLong)

        // 提交结果回放的 181 与 1810：完成页状态与汇总页一致
        val d = vm.done.value
        assertEquals("李四", d.farmerName)
        assertEquals(10, d.boxCount)
        assertEquals("460.5", d.totalNetKg?.let { Kg.of(it).format() })
        assertEquals(formatMoney(q.avgKg), formatMoney(d.avgKg))
        assertEquals(formatMoney(q.unitPrice), formatMoney(d.unitPrice))
        assertEquals(formatMoney(q.amount), formatMoney(d.amount))
        assertEquals("181", formatMoney(d.unitPrice))
        assertEquals("1810", formatMoney(d.amount))
        assertFalse(d.pendingPrice)
        // 提交成功：草稿与建单四项清掉
        assertFalse(deps.sessionStore.exists())
        assertFalse(deps.orderDraft.header.value.ready)

        // 所有请求都带登录令牌、都在 openapi 里、请求与响应的字段都对得上契约
        assertTrue(calls.all { it.auth == "Bearer $TOKEN" })
        assertEquals(emptyList(), contract.violations.toList())
    }

    @Test
    fun manualEditOfLastBoxGoesToSubmitAsManualWithRawKg() = flow { deps, vm, navs ->
        weighLiSi(deps, vm)
        assertEquals(10, deps.printer.labels.size)

        // 把第 10 箱手录为 52.8：留着秤原始读数 47.8，手录记号打上，旧标签作废并按新值重打
        vm.editManual(10, 52.8)
        settle(1000)
        val last = vm.ui.value.boxes.last()
        assertEquals(10, last.seq)
        assertEquals(Kg.of(52.8), last.net)
        assertTrue(last.manual)
        assertEquals(Kg.of(47.8), last.raw)
        assertEquals(11, deps.printer.labels.size)
        val reprint = (deps.printer.labels.last() as PrintPayload.Text).tspl
        assertTrue("净重：52.8kg" in reprint && "第10箱" in reprint, reprint)

        vm.onConfirmWeighing()
        until("计价回来") { vm.summary.value.quote != null }
        val quoteReq = calls.last { it.path == "/api/orders/quote" }.json
        assertEquals(EDITED_NETS, netsOf(quoteReq.getAsJsonArray("boxNetKg")))
        assertTrue(vm.summary.value.canSubmit)

        vm.submitOrder()
        until("提交完成") { navs.contains(ReceivingNav.ToDone) }
        val boxes = calls.last { it.method == "POST" && it.path == "/api/orders" }.json.getAsJsonArray("boxes")
        assertEquals(10, boxes.size())
        val b10 = boxes[9].asJsonObject
        assertEquals(10, b10.get("seq").asInt)
        assertEquals(52.8, b10.get("netKg").asDouble, 1e-9)
        assertEquals(true, b10.get("manual").asBoolean)
        assertEquals(47.8, b10.get("rawKg").asDouble, 1e-9)
        assertEquals(true, b10.get("printed").asBoolean)
        // 其余 9 箱没动
        boxes.take(9).forEach {
            assertEquals(false, it.asJsonObject.get("manual").asBoolean)
            assertTrue(it.asJsonObject.get("rawKg") == null)
        }
        assertEquals("182", formatMoney(vm.done.value.unitPrice))
        assertEquals("1820", formatMoney(vm.done.value.amount))
        assertEquals(emptyList(), contract.violations.toList())
    }

    /** 建单页里改选农户（照 `CreateViewModel`：只换 Pick）。 */
    private fun Deps.pickFarmer(id: Long, name: String) {
        orderDraft.update { it.copy(farmer = Pick(id, name)) }
    }

    /** 选 2 号包装箱、称一箱（净重 46.5），等自动记录完成。 */
    private suspend fun TestScope.weighOneBox(deps: Deps, vm: ReceivingViewModel) {
        vm.selectTare(vm.ui.value.tares.first { it.name == "2号包装箱" })
        settle(500)
        deps.scale.placeOneBox(FakeScale.CASE_NETS_C.first())
        settle()
        deps.scale.removeLoad()
        settle()
        settle(400L)
        assertEquals(1, vm.ui.value.boxes.size)
    }

    @Test
    fun leavingWeighWithNoBoxesFreesTheOrderSoNewFarmerIsUsed() = flow { deps, vm, navs ->
        deps.createOrder()
        connectDevices(deps)
        vm.startFromDraft()
        until("进入称重") { vm.ui.value.ready }
        assertEquals("李四", vm.ui.value.farmerName)
        assertTrue(deps.sessionStore.exists())

        // 0 箱离开：不问，直接放行，单和本地草稿都清掉，建单页四项还在
        assertEquals(LeaveWeigh.Left, vm.leaveWeigh())
        assertFalse(deps.sessionStore.exists())
        assertFalse(vm.ui.value.ready)
        assertTrue(deps.orderDraft.header.value.ready)

        // 回建单页把李四改成王秀英，再点“开始收蛋”：按新四项新开一单
        deps.pickFarmer(12, "王秀英")
        vm.startFromDraft()
        until("重新进入称重") { vm.ui.value.ready }
        assertEquals("王秀英", vm.ui.value.farmerName)
        assertEquals("王秀英", deps.sessionStore.load()?.farmer?.name)
        assertEquals(12L, deps.sessionStore.load()?.farmer?.id)

        weighOneBox(deps, vm)
        vm.onConfirmWeighing()
        until("计价回来") { vm.summary.value.quote != null }
        vm.submitOrder()
        until("提交完成") { navs.contains(ReceivingNav.ToDone) }
        val body = calls.last { it.method == "POST" && it.path == "/api/orders" }.json
        assertEquals(12L, body.get("vendorId").asLong)
        assertEquals(1, body.getAsJsonArray("boxes").size())
        assertEquals(emptyList(), contract.violations.toList())
    }

    @Test
    fun leavingWeighWithBoxesAsksFirstAndAbandonClearsEverything() = flow { deps, vm, _ ->
        deps.createOrder()
        connectDevices(deps)
        vm.startFromDraft()
        until("进入称重") { vm.ui.value.ready }
        weighOneBox(deps, vm)

        // 有箱：要确认，什么都不动（“继续称”就是不做任何事）
        assertEquals(LeaveWeigh.NeedConfirm, vm.leaveWeigh())
        assertTrue(deps.sessionStore.exists())
        assertTrue(vm.ui.value.ready)
        assertEquals(1, vm.ui.value.boxes.size)

        // 确认放弃：草稿与内存都清
        vm.abandon()
        assertFalse(deps.sessionStore.exists())
        assertFalse(vm.ui.value.ready)
    }

    private companion object {
        const val TOKEN = "test-token"

        val LISI_NETS = listOf(4650, 4580, 4600, 4600, 4600, 4560, 4560, 4560, 4560, 4780)
        val EDITED_NETS = LISI_NETS.dropLast(1) + 5280
        val ONE_BOX_NETS = listOf(4650)

        // 以下取自后端 BenchmarkFlowTest 的算例，字段按 api/openapi.json 的 Bootstrap、Result、Submitted 等
        val FARMERS = """[
            {"id":11,"name":"李四","hsCode":"HS-LISI","phones":["13603511234"],"matchedPhones":["13603511234"]},
            {"id":12,"name":"王秀英","hsCode":"HS-WANG","phones":["13811121234"],"matchedPhones":["13811121234"]}
        ]"""

        val BOOTSTRAP = """{
            "date":"2026-11-16",
            "products":[{"id":21,"name":"粉蛋"}],
            "specs":[{"id":31,"name":"360枚/箱","eggCount":360,"stdNetKg":45,"basePrice":180}],
            "warehouses":[{"id":41,"name":"1号仓库"}],
            "tares":[{"id":51,"name":"2号包装箱","tareKg":2}],
            "settings":{"round.mode":"half","print.timing":"per-box","label.content":"minimal",
                "label.width-mm":"60","label.height-mm":"40","label.gap-mm":"2"},
            "options":{"scale.tare-by":"scale","scale.reading-is-net":"true","scale.stable-by":"sdk",
                "weigh.record-by":"auto","weigh.merge-detect":"auto","print.enabled":"on","submit.without-price":"block"}
        }"""

        val BATCH = """{"batchNo":"SG20261116-1","date":"2026-11-16"}"""

        val QUOTE_LISI = """{"boxCount":10,"totalKg":460.5,"rawAvgKg":46.05,"avgKg":46,"standardNetKg":45,
            "basePrice":180,"ruleId":61,"ruleName":"粉蛋李四收购定价方案","coefficient":1,"tailKg":1,"priceDiff":1,
            "unitPrice":181,"amount":1810,"roundMode":"HALF","issues":[]}"""

        // 第 10 箱手录 52.8 后：合计 465.5、均重 46.55 四舍五入 47，多 2kg，182 元/箱
        val QUOTE_EDITED = """{"boxCount":10,"totalKg":465.5,"rawAvgKg":46.55,"avgKg":47,"standardNetKg":45,
            "basePrice":180,"ruleId":61,"ruleName":"粉蛋李四收购定价方案","coefficient":1,"tailKg":2,"priceDiff":2,
            "unitPrice":182,"amount":1820,"roundMode":"HALF","issues":[]}"""

        // 王秀英只称一箱 46.5：合计 46.5、均重 46.5 取整 47（half），多 2kg，182 元/箱
        val QUOTE_ONE_BOX = """{"boxCount":1,"totalKg":46.5,"rawAvgKg":46.5,"avgKg":47,"standardNetKg":45,
            "basePrice":180,"ruleId":62,"ruleName":"粉蛋王秀英收购定价方案","coefficient":1,"tailKg":2,"priceDiff":2,
            "unitPrice":182,"amount":182,"roundMode":"HALF","issues":[]}"""

        val SUBMITTED_ONE_BOX = """{"id":1003,"code":"RK20261116003","batchNo":"SG20261116-1","status":"PENDING","source":"APP",
            "boxCount":1,"totalNetKg":46.5,"avgKg":47,"submittedPrice":182,"submittedAmount":182}"""

        val SUBMITTED_LISI = """{"id":1001,"code":"RK20261116001","batchNo":"SG20261116-1","status":"PENDING","source":"APP",
            "boxCount":10,"totalNetKg":460.5,"avgKg":46,"submittedPrice":181,"submittedAmount":1810}"""

        val SUBMITTED_EDITED = """{"id":1002,"code":"RK20261116002","batchNo":"SG20261116-1","status":"PENDING","source":"APP",
            "boxCount":10,"totalNetKg":465.5,"avgKg":47,"submittedPrice":182,"submittedAmount":1820}"""
    }
}
