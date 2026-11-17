package com.jidan.receiving.demo

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jidan.receiving.data.ApiClient
import com.jidan.receiving.data.ApiError
import com.jidan.receiving.data.AuthRepository
import com.jidan.receiving.data.CatalogRepository
import com.jidan.receiving.data.OrderRepository
import com.jidan.receiving.data.QuoteRequest
import com.jidan.receiving.data.SubmitBoxRequest
import com.jidan.receiving.data.SubmitRequest
import com.jidan.receiving.data.runApi
import com.jidan.receiving.ui.weigh.OpenApiContract
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 离线假后端：每个接口的形状（对 `api/openapi.json`）与李四算例（v7 第 6 章）。 */
class DemoBackendTest {

    private val today = LocalDate.of(2026, 11, 17)
    private val backend = DemoBackend { today }
    private val gson = Gson()
    private val contract = OpenApiContract(File("../../api/openapi.json").also { check(it.exists()) })

    private val lisiNets = listOf(46.5, 45.8, 46.0, 46.0, 46.0, 45.6, 45.6, 45.6, 45.6, 47.8)

    private fun call(method: String, path: String, query: Map<String, String?> = emptyMap(), body: Any? = null): DemoBackend.Reply {
        val json = body?.let { gson.toJson(it) }.orEmpty()
        contract.checkRequest(method, path.replace(Regex("/\\d+(?=/|$)"), "/{id}"), json)
        val reply = backend.handle(method, path, query, json)
        if (reply.code == 200) {
            contract.checkResponse(method, path.replace(Regex("/\\d+(?=/|$)"), "/{id}"), reply.json)
        }
        return reply
    }

    private fun obj(r: DemoBackend.Reply): JsonObject = JsonParser.parseString(r.json).asJsonObject
    private fun arr(r: DemoBackend.Reply) = JsonParser.parseString(r.json).asJsonArray

    @Test
    fun bootstrapHasV7DemoArchive() {
        val b = obj(call("GET", "/api/app/bootstrap"))
        assertEquals("2026-11-17", b.get("date").asString)
        assertEquals(listOf("粉蛋", "红蛋"), b.getAsJsonArray("products").map { it.asJsonObject.get("name").asString })
        val specs = b.getAsJsonArray("specs").map { it.asJsonObject }
        assertEquals(listOf("240枚/箱", "360枚/箱", "480枚/箱"), specs.map { it.get("name").asString })
        assertEquals(listOf(30.0, 45.0, 60.0), specs.map { it.get("stdNetKg").asDouble })
        // 演示版没有 Web 端补录，今天三种都有基准价
        assertEquals(listOf(120.0, 180.0, 240.0), specs.map { it.get("basePrice").asDouble })
        assertEquals(listOf("1号仓库", "2号仓库"), b.getAsJsonArray("warehouses").map { it.asJsonObject.get("name").asString })
        assertEquals(
            listOf("1号包装箱" to 1.5, "2号包装箱" to 2.0, "3号包装箱" to 2.5),
            b.getAsJsonArray("tares").map { it.asJsonObject.get("name").asString to it.asJsonObject.get("tareKg").asDouble },
        )
        assertEquals("half", b.getAsJsonObject("settings").get("round.mode").asString)
        assertEquals("block", b.getAsJsonObject("options").get("submit.without-price").asString)
    }

    @Test
    fun farmersByTailAndRecent() {
        val t1234 = arr(call("GET", "/api/farmers", mapOf("tail" to "1234"))).map { it.asJsonObject }
        assertEquals(listOf("李四", "王秀英"), t1234.map { it.get("name").asString })
        assertEquals(listOf("13603511234"), t1234[0].getAsJsonArray("matchedPhones").map { it.asString })
        val t5678 = arr(call("GET", "/api/farmers", mapOf("tail" to "5678"))).map { it.asJsonObject }
        assertEquals(listOf("张三", "李四"), t5678.map { it.get("name").asString })
        val t7788 = arr(call("GET", "/api/farmers", mapOf("tail" to "7788"))).map { it.asJsonObject }
        assertEquals(listOf("王秀英"), t7788.map { it.get("name").asString })
        assertEquals(2, t7788[0].getAsJsonArray("phones").size())
        assertEquals(0, arr(call("GET", "/api/farmers", mapOf("tail" to "0000"))).size())
        assertEquals(400, backend.handle("GET", "/api/farmers", mapOf("tail" to "12"), "").code)
        assertEquals(listOf(4L, 3L, 2L, 1L), arr(call("GET", "/api/farmers/recent")).map { it.asJsonObject.get("id").asLong })
    }

    @Test
    fun lisiRuleAndQuoteMatchV7() {
        val rules = arr(call("GET", "/api/farmers/1/rules")).map { it.asJsonObject }
        assertEquals(1, rules.size)
        assertEquals("粉蛋李四收购定价方案", rules[0].get("name").asString)
        assertEquals(1.0, rules[0].get("coef").asDouble)
        assertEquals(1, rules[0].get("bindCount").asInt)

        val q = obj(call("POST", "/api/orders/quote", body = QuoteRequest(1, 1, 2, "2026-11-17", lisiNets)))
        assertEquals(10, q.get("boxCount").asInt)
        assertEquals(460.5, q.get("totalKg").asDouble)
        assertEquals(46.05, q.get("rawAvgKg").asDouble)
        assertEquals(46.0, q.get("avgKg").asDouble)
        assertEquals(45.0, q.get("standardNetKg").asDouble)
        assertEquals(180.0, q.get("basePrice").asDouble)
        assertEquals(181.0, q.get("unitPrice").asDouble)
        assertEquals(1810.0, q.get("amount").asDouble)
        assertEquals("HALF", q.get("roundMode").asString)
        assertEquals(0, q.getAsJsonArray("issues").size())

        // 第 10 箱手录 52.8：均重 46.55 → 47，182 元/箱，1820 元（v7 6.6）
        val edited = obj(call("POST", "/api/orders/quote", body = QuoteRequest(1, 1, 2, "2026-11-17", lisiNets.dropLast(1) + 52.8)))
        assertEquals(182.0, edited.get("unitPrice").asDouble)
        assertEquals(1820.0, edited.get("amount").asDouble)
    }

    @Test
    fun ruleSheetFlowTrialBindAndCoefEditUpdateMemory() {
        fun unit(extra: QuoteRequest.() -> QuoteRequest = { this }) =
            obj(call("POST", "/api/orders/quote", body = QuoteRequest(1, 1, 2, "2026-11-17", lisiNets).extra()))

        // 试算：只换系数 1.5 → 180 + 1.5 × 1 = 181.5（不落库）
        assertEquals(181.5, unit { copy(trialCoef = 1.5) }.get("unitPrice").asDouble)
        assertEquals(181.0, unit().get("unitPrice").asDouble)
        // 试算另一条规则（粉蛋 360 通用，系数 0.8）→ 180.8
        assertEquals(180.8, unit { copy(trialRuleId = 3) }.get("unitPrice").asDouble)

        // 候选：粉蛋 360 的收购规则 3 条（李四、通用、王秀英），发货规则不在内
        val candidates = arr(call("GET", "/api/rules", mapOf("kind" to "BUY", "productId" to "1", "specId" to "2"))).map { it.asJsonObject }
        assertEquals(listOf(1L, 3L, 4L), candidates.map { it.get("id").asLong })

        // 改系数落内存
        val patched = obj(call("PUT", "/api/rules/1", body = com.jidan.receiving.data.RuleCoefPatch(2.0)))
        assertEquals(2.0, patched.get("coef").asDouble)
        assertEquals(182.0, unit().get("unitPrice").asDouble)
        assertEquals(400, backend.handle("PUT", "/api/rules/1", emptyMap(), """{"coef":-1}""").code)

        // 改绑通用规则，再解绑
        val bound = arr(call("PUT", "/api/farmers/1/rule-binding", body = com.jidan.receiving.data.RuleBindingRequest(1, 2, 3)))
        assertEquals(listOf(3L), bound.map { it.asJsonObject.get("id").asLong })
        assertEquals(180.8, unit().get("unitPrice").asDouble)
        val unbound = arr(call("PUT", "/api/farmers/1/rule-binding", body = com.jidan.receiving.data.RuleBindingRequest(1, 2, null)))
        assertEquals(0, unbound.size())
        val none = unit()
        assertTrue(none.getAsJsonArray("issues").any { it.asString == "NO_RULE" })
        assertTrue(!none.has("unitPrice"))

        // 退出登录复位后回到初始
        backend.reset()
        assertEquals(181.0, unit().get("unitPrice").asDouble)
    }

    @Test
    fun batchNumbersIncrementAndSubmitIsIdempotentByBatchNo() {
        val b1 = obj(call("POST", "/api/batches")).get("batchNo").asString
        val b2 = obj(call("POST", "/api/batches")).get("batchNo").asString
        assertEquals("SG20261117-1", b1)
        assertEquals("SG20261117-2", b2)

        fun boxes() = lisiNets.mapIndexed { i, kg -> SubmitBoxRequest(i + 1, kg, kg + 2, 2.0, "2号包装箱", i + 1, 1, false, null, true) }
        val first = obj(call("POST", "/api/orders", body = SubmitRequest(b1, 1, 1, 2, 1, boxes())))
        assertEquals("PENDING", first.get("status").asString)
        assertEquals("APP", first.get("source").asString)
        assertEquals(10, first.get("boxCount").asInt)
        assertEquals(460.5, first.get("totalNetKg").asDouble)
        assertEquals(46.0, first.get("avgKg").asDouble)
        assertEquals(181.0, first.get("submittedPrice").asDouble)
        assertEquals(1810.0, first.get("submittedAmount").asDouble)
        assertEquals("CGRK20261117001", first.get("code").asString)

        // 同一批次号再来：原样返回同一张，即使这回带的箱不同
        val again = obj(call("POST", "/api/orders", body = SubmitRequest(b1, 1, 1, 2, 1, boxes().take(3))))
        assertEquals(first.get("id").asLong, again.get("id").asLong)
        assertEquals(10, again.get("boxCount").asInt)

        // 提交过的农户排到最近的第一个
        assertEquals(1L, arr(call("GET", "/api/farmers/recent")).first().asJsonObject.get("id").asLong)
    }

    @Test
    fun submitWithoutPriceIsBlockedLikeTheRealBackend() {
        val box = listOf(SubmitBoxRequest(1, 60.0, 62.0, 2.0, "2号包装箱"))
        // 王秀英 480枚/箱（没有她的规则）
        val r = backend.handle("POST", "/api/orders", emptyMap(), gson.toJson(SubmitRequest("SG20261117-1", 2, 1, 3, 1, box)))
        assertEquals(409, r.code)
        assertEquals("NOT_READY", obj(r).get("code").asString)
        // 另一位李四 480 有规则；前天没有基准价
        val q = obj(call("POST", "/api/orders/quote", body = QuoteRequest(4, 1, 3, "2026-11-15", listOf(60.0))))
        assertTrue(q.getAsJsonArray("issues").any { it.asString == "NO_BASE_PRICE" })
        assertTrue(!q.has("amount"))
    }

    @Test
    fun noUpdateEntryAndUnknownPaths() {
        assertEquals(404, backend.handle("GET", "/api/app/version", emptyMap(), "").code)
        assertEquals(404, backend.handle("GET", "/api/nope", emptyMap(), "").code)
    }

    /** 经真的 ApiClient（Retrofit + OkHttp），拦截器接在 OkHttp 上，不联网。 */
    @Test
    fun answersThroughRealApiClientWithoutNetwork(): Unit = runBlocking {
        val client = ApiClient(
            baseUrl = { "http://demo.invalid/" },
            token = { "demo-token" },
            onUnauthenticated = {},
            demoInterceptor = backend,
        )
        val cat = CatalogRepository(client)
        assertEquals(3, cat.catalog().specs.size)
        assertEquals("李四", cat.farmersByTail("1234").first().name)
        assertEquals("SG20261117-1", cat.nextBatchNo())
        val quote = OrderRepository(client).quote(QuoteRequest(1, 1, 2, "2026-11-17", lisiNets))
        assertEquals(181.0, quote.unitPrice)
        assertEquals(1810.0, quote.amount)
        val err = assertFailsWith<ApiError> { AuthRepository(client).appVersion() }
        assertEquals(404, err.status)
        assertNotNull(client.runApi { bootstrap() }.date)
    }
}
