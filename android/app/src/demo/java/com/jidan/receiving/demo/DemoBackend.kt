package com.jidan.receiving.demo

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.BatchNo
import com.jidan.receiving.data.BootstrapResponse
import com.jidan.receiving.data.CurrentUser
import com.jidan.receiving.data.FarmerDto
import com.jidan.receiving.data.LoginResult
import com.jidan.receiving.data.NamedDto
import com.jidan.receiving.data.QuoteRequest
import com.jidan.receiving.data.QuoteResult
import com.jidan.receiving.data.RulePatch
import com.jidan.receiving.data.RuleViewDto
import com.jidan.receiving.data.SpecDto
import com.jidan.receiving.data.SubmitRequest
import com.jidan.receiving.data.SubmittedDto
import com.jidan.receiving.data.TareDto
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 【仅演示版】离线假后端：一个 OkHttp 拦截器，把 APP 调的每个接口（见 `EggApi`，形状按 `api/openapi.json`）
 * 在本机内存里回答，不发任何网络请求。数据是 v7 演示数据（同后端 `DemoDataSeeder`）：
 * 农户、商品、三种规格与当日基准价、仓库、三种包装箱、规则（含粉蛋李四收购定价方案，每斤尾差 0.5 元）。
 * 重量单位斤；规则不绑农户，打包了商品、规格、包装箱与每箱损耗（附录 E R-1 行）。
 *
 * 没有数据库、没有持久化：进程结束或退出登录（[reset]）后回到初始。提交的单只存在内存里，
 * 同一批次号再提交返回原来那张（与后端一致）。计价见 [DemoPricing]。
 * 未经实物验证，也未对接任何真实后端；不是产品功能。
 */
class DemoBackend(private val today: () -> LocalDate = { LocalDate.now() }) : Interceptor {

    /** 一次回答：HTTP 状态码与 JSON 文本。 */
    class Reply(val code: Int, val json: String)

    private class Fail(val code: Int, val errCode: String, message: String) : Exception(message)

    private class Farmer(val id: Long, val name: String, val hsCode: String, val phones: List<String>)

    private class Rule(
        val id: Long,
        val name: String,
        val kind: String,
        val productId: Long,
        val specId: Long,
        val tareId: Long,
        var lossJin: BigDecimal,
        var coef: BigDecimal,
        val enabled: Boolean,
    )

    private val gson = Gson()
    private val lock = Any()

    // ---------------------------------------------------------------- 演示数据（不变的部分）

    private val products = listOf(NamedDto(1, "粉蛋"), NamedDto(2, "红蛋"))
    private val warehouses = listOf(NamedDto(1, "1号仓库"), NamedDto(2, "2号仓库"))

    /** 规格：枚数与标准净含量 W0。 */
    private val specs = listOf(
        SpecDto(1, "240枚/箱", 240, 60.0),
        SpecDto(2, "360枚/箱", 360, 90.0),
        SpecDto(3, "480枚/箱", 480, 120.0),
    )

    /** 只下发作热键的皮重（旧款木箱已停用，不在其中）。 */
    private val tares = listOf(
        TareDto(1, "1号包装箱", 3.0),
        TareDto(2, "2号包装箱", 4.0),
        TareDto(3, "3号包装箱", 5.0),
    )

    private val farmers = listOf(
        Farmer(1, "李四", "GYS0001", listOf("13603511234")),
        Farmer(2, "王秀英", "GYS0002", listOf("15803511234", "13935107788")),
        Farmer(3, "张三", "GYS0003", listOf("13800005678")),
        Farmer(4, "李四", "GYS0004", listOf("13099995678")),
    )

    /**
     * 当日基准价：今天三种都有。演示版没有 Web 端可以补录基准价，留空会让选 480枚/箱 的人卡在汇总页，
     * 所以不像演示库那样故意留空；昨天三种也都有。
     */
    private fun basePrice(date: LocalDate, specId: Long): BigDecimal? {
        val t = today()
        val table = when (date) {
            t -> mapOf(1L to "120", 2L to "180", 3L to "240")
            t.minusDays(1) -> mapOf(1L to "118", 2L to "178", 3L to "236")
            else -> emptyMap()
        }
        return table[specId]?.let(::BigDecimal)
    }

    // ---------------------------------------------------------------- 会变的部分（内存）

    private var rules = seedRules()
    private val recent = ArrayDeque(listOf(4L, 3L, 2L, 1L))
    private val orders = LinkedHashMap<String, SubmittedDto>()
    private val batchSeq = HashMap<LocalDate, Int>()
    private var nextOrderId = 1001L
    private val roundMode = "half"

    private fun seedRules(): MutableList<Rule> = mutableListOf(
        Rule(1, "粉蛋李四收购定价方案", "BUY", 1, 2, 2, BigDecimal.ZERO, BigDecimal("0.5"), true),
        Rule(2, "红蛋张三收购定价方案", "BUY", 2, 2, 2, BigDecimal.ZERO, BigDecimal("0.75"), true),
        Rule(3, "粉蛋360通用收购方案", "BUY", 1, 2, 2, BigDecimal("-1"), BigDecimal("0.4"), true),
        Rule(4, "粉蛋王秀英收购定价方案", "BUY", 1, 2, 2, BigDecimal.ZERO, BigDecimal("0.5"), true),
        Rule(5, "红蛋王秀英小箱收购方案", "BUY", 2, 1, 1, BigDecimal.ZERO, BigDecimal("0.25"), true),
        Rule(6, "粉蛋大箱收购方案", "BUY", 1, 3, 3, BigDecimal.ZERO, BigDecimal("0.5"), true),
        Rule(7, "永丰市场粉蛋发货方案", "SELL", 1, 2, 2, BigDecimal.ZERO, BigDecimal("0.6"), true),
    )

    /** 回到刚装好的样子（退出登录时用）。 */
    fun reset() = synchronized(lock) {
        rules = seedRules()
        recent.clear()
        recent.addAll(listOf(4L, 3L, 2L, 1L))
        orders.clear()
        batchSeq.clear()
        nextOrderId = 1001L
    }

    // ---------------------------------------------------------------- 拦截器

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        val query = req.url.queryParameterNames.associateWith { req.url.queryParameter(it) }
        val reply = synchronized(lock) { handle(req.method, req.url.encodedPath, query, body) }
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message(if (reply.code in 200..299) "OK" else "Error")
            .body(reply.json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** 路由。纯函数式的入口，单测直接调这里。 */
    fun handle(method: String, path: String, query: Map<String, String?>, body: String): Reply = try {
        ok(route(method, path.trimEnd('/'), query, body))
    } catch (f: Fail) {
        Reply(f.code, gson.toJson(mapOf("code" to f.errCode, "message" to f.message)))
    }

    private fun ok(value: Any?) = Reply(200, gson.toJson(value))

    private fun route(method: String, path: String, query: Map<String, String?>, body: String): Any? {
        when ("$method $path") {
            // 演示版没有更新入口：404，APP 当作没有新版本
            "GET /api/app/version" -> throw Fail(404, "NOT_FOUND", "演示版没有更新入口")
            "POST /api/auth/login" -> return LoginResult("demo-token", "张师傅", "SCALE")
            "GET /api/auth/me" -> return CurrentUser(1, "张师傅", "13900001111", "SCALE")
            "GET /api/app/bootstrap" -> return bootstrap()
            "GET /api/farmers" -> return query["q"]?.takeIf { query["tail"] == null }?.let(::farmersByName)
                ?: farmersByTail(query["tail"])
            "GET /api/farmers/recent" -> return recent.mapNotNull { id -> farmers.firstOrNull { it.id == id } }.map { farmerDto(it) }
            "POST /api/batches" -> return nextBatch()
            "POST /api/orders/quote" -> return quote(parse(body, QuoteRequest::class.java))
            "POST /api/orders" -> return submit(parse(body, SubmitRequest::class.java))
            "GET /api/rules" -> return listRules(query)
        }
        RULE_ID.matchEntire(path)?.takeIf { method == "PUT" }?.let {
            return updateRule(it.groupValues[1].toLong(), parse(body, RulePatch::class.java))
        }
        throw Fail(404, "NOT_FOUND", "演示版没有这个接口：$method $path")
    }

    private fun <T> parse(body: String, type: Class<T>): T = try {
        gson.fromJson(body, type) ?: throw Fail(400, "INVALID", "请求内容不能为空")
    } catch (_: JsonSyntaxException) {
        throw Fail(400, "INVALID", "请求格式不对")
    }

    // ---------------------------------------------------------------- 档案

    private fun bootstrap(): BootstrapResponse {
        val t = today()
        val settingKeys = setOf(
            "round.mode", "print.timing", "invoice.type", "invoice.tax-rate",
            "label.width-mm", "label.height-mm", "label.gap-mm", "label.content",
            "device.scale-model", "device.printer-model",
        )
        val all = AppOptions.DEFAULTS.toMutableMap().also { it["round.mode"] = roundMode }
        return BootstrapResponse(
            date = t.toString(),
            products = products,
            specs = specs.map { it.copy(basePrice = basePrice(t, it.id)?.toDouble()) },
            warehouses = warehouses,
            tares = tares,
            settings = all.filterKeys { it in settingKeys },
            options = all.filterKeys { it !in settingKeys },
            rules = rules.filter { it.kind == "BUY" && it.enabled }.sortedBy { it.id }.map(::view),
        )
    }

    private fun farmerDto(f: Farmer, matched: List<String> = emptyList()) =
        FarmerDto(f.id, f.name, f.hsCode, f.phones, matched)

    private fun farmersByTail(tail: String?): List<FarmerDto> {
        if (tail == null || !Regex("\\d{4}").matches(tail)) throw Fail(400, "INVALID", "请输入手机号后四位")
        return farmers.mapNotNull { f ->
            val hit = f.phones.filter { it.takeLast(4) == tail }
            if (hit.isEmpty()) null else farmerDto(f, hit)
        }
    }

    /** 与后端相同的条件：名称、编码、号码包含 q；演示数据里没有拼音表，首字母检索只在真后端里有。 */
    private fun farmersByName(q: String): List<FarmerDto> {
        val key = q.trim()
        if (key.isEmpty()) return emptyList()
        return farmers.filter { f ->
            f.name.contains(key) || f.hsCode.contains(key, ignoreCase = true) || f.phones.any { it.contains(key) }
        }.map { farmerDto(it) }
    }

    private fun requireFarmer(id: Long): Farmer =
        farmers.firstOrNull { it.id == id } ?: throw Fail(400, "INVALID", "农户不存在")

    // ---------------------------------------------------------------- 规则

    private fun view(r: Rule): RuleViewDto {
        val spec = specs.first { it.id == r.specId }
        val tare = tares.first { it.id == r.tareId }
        return RuleViewDto(
            id = r.id,
            name = r.name,
            kind = r.kind,
            productId = r.productId,
            productName = products.first { it.id == r.productId }.name,
            specId = r.specId,
            specName = spec.name,
            stdNetJin = spec.stdNetJin,
            tareBoxId = tare.id,
            tareName = tare.name,
            tareJin = tare.tareJin,
            lossJin = r.lossJin.toDouble(),
            coef = r.coef.toDouble(),
            enabled = r.enabled,
        )
    }

    private fun listRules(q: Map<String, String?>): List<RuleViewDto> {
        val tokens = q["q"]?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        return rules.sortedBy { it.id }
            .filter { q["kind"].isNullOrBlank() || it.kind == q["kind"] }
            .filter { q["productId"].isNullOrBlank() || it.productId == q["productId"]?.toLongOrNull() }
            .filter { q["specId"].isNullOrBlank() || it.specId == q["specId"]?.toLongOrNull() }
            .filter { q["enabled"] != "true" || it.enabled }
            .map(::view)
            .filter { v ->
                tokens.all { t ->
                    v.name.contains(t) || v.productName.orEmpty().contains(t) || v.specName.orEmpty().contains(t) ||
                        v.tareName.orEmpty().contains(t)
                }
            }
    }

    /** 磅秤员改每斤尾差、每箱损耗；为空的不改。 */
    private fun updateRule(id: Long, patch: RulePatch): RuleViewDto {
        val r = rules.firstOrNull { it.id == id } ?: throw Fail(404, "NOT_FOUND", "规则不存在")
        patch.coef?.let { v ->
            val c = BigDecimal.valueOf(v).stripTrailingZeros()
            if (c.signum() < 0) throw Fail(400, "INVALID", "每斤尾差不能为负数")
            if (c.scale() > 4 || c.precision() - c.scale() > 4) throw Fail(400, "INVALID", "每斤尾差最多四位小数")
            r.coef = BigDecimal.valueOf(v)
        }
        patch.lossJin?.let { v ->
            val l = BigDecimal.valueOf(v).stripTrailingZeros()
            if (l.scale() > 2 || l.precision() - l.scale() > 3) throw Fail(400, "INVALID", "每箱损耗最多两位小数")
            r.lossJin = BigDecimal.valueOf(v)
        }
        return view(r)
    }

    // ---------------------------------------------------------------- 批次、计价、提交

    private fun nextBatch(): Map<String, String> {
        val d = today()
        val n = (batchSeq[d] ?: 0) + 1
        batchSeq[d] = n
        return mapOf("batchNo" to "SG${d.format(DateTimeFormatter.BASIC_ISO_DATE)}-$n", "date" to d.toString())
    }

    /** 按规则计价：商品与规格取自规则；trial* 是改规则前的试算。 */
    private fun price(
        ruleId: Long,
        date: LocalDate,
        boxNetJin: List<Double>,
        trialCoef: Double?,
        trialLossJin: Double?,
    ): QuoteResult {
        val rule = rules.firstOrNull { it.id == ruleId } ?: throw Fail(400, "INVALID", "规则不存在")
        if (rule.kind != "BUY") throw Fail(400, "INVALID", "发货规则不能用于采购入库单")
        val spec = specs.first { it.id == rule.specId }
        return DemoPricing.compute(
            boxNetJin = boxNetJin.map(BigDecimal::valueOf),
            standardNetJin = BigDecimal.valueOf(spec.stdNetJin ?: 0.0),
            basePrice = basePrice(date, spec.id),
            ruleId = rule.id,
            ruleName = rule.name,
            coef = trialCoef?.let(BigDecimal::valueOf) ?: rule.coef,
            roundMode = roundMode,
            lossJin = trialLossJin?.let(BigDecimal::valueOf) ?: rule.lossJin,
        )
    }

    private fun quote(q: QuoteRequest): QuoteResult {
        val date = q.date?.let { parseDate(it) } ?: today()
        return price(q.ruleId, date, q.boxNetJin, q.trialCoef, q.trialLossJin)
    }

    private fun parseDate(s: String): LocalDate = try {
        LocalDate.parse(s)
    } catch (_: DateTimeParseException) {
        throw Fail(400, "INVALID", "日期格式不对")
    }

    private fun submit(s: SubmitRequest): SubmittedDto {
        if (!Regex("SG\\d{8}-[1-9]\\d*").matches(s.batchNo)) throw Fail(400, "INVALID", "批次号格式不对")
        if (s.boxes.isEmpty()) throw Fail(400, "INVALID", "至少要有一箱")
        // 同一批次号再提交：返回原来那张
        orders[s.batchNo]?.let { return it }
        val seqs = HashSet<Int>()
        for (b in s.boxes) if (!seqs.add(b.seq)) throw Fail(400, "INVALID", "批次序号 ${b.seq} 重复了")
        requireFarmer(s.vendorId)
        val rule = rules.firstOrNull { it.id == s.ruleId } ?: throw Fail(400, "INVALID", "规则不存在")
        if (!rule.enabled) throw Fail(400, "INVALID", "这条规则已停用")
        if (warehouses.none { it.id == s.warehouseId }) throw Fail(400, "INVALID", "仓库不存在")

        val date = BatchNo.dateOf(s.batchNo)?.let { parseDate(it) } ?: today()
        val p = price(s.ruleId, date, s.boxes.map { it.netJin }, null, null)
        if (p.noBasePrice) throw Fail(409, "NOT_READY", "当日该规格没录基准价，现在不能提交")
        val seq = s.batchNo.substringAfter('-').toInt()
        val out = SubmittedDto(
            id = nextOrderId++,
            code = "CGRK" + s.batchNo.substring(2, s.batchNo.indexOf('-')) + "%03d".format(seq),
            batchNo = s.batchNo,
            status = "PENDING",
            source = "APP",
            boxCount = p.boxCount,
            totalNetJin = p.totalJin,
            avgJin = p.avgJin,
            submittedPrice = p.unitPrice,
            submittedAmount = p.amount,
        )
        orders[s.batchNo] = out
        recent.remove(s.vendorId)
        recent.addFirst(s.vendorId)
        while (recent.size > 5) recent.removeLast()
        return out
    }

    private companion object {
        val RULE_ID = Regex("^/api/rules/(\\d+)$")
    }
}
