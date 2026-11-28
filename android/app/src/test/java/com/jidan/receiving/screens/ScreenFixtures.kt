package com.jidan.receiving.screens

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.floor
import kotlin.math.roundToInt
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * 渲染截图用的后端回放（F4-1）：数据与 `lab/app-sim.html` 的演示数据对齐
 * （李四 / 王秀英 / 张三，粉蛋 · 红蛋，240 / 360 / 480 三个规格，三种包装箱），
 * 接口形状同 `LiSiFullFlowTest`。只管把页面渲染出来，不核对契约，也不是接口测试。
 * 计价按 v7 第 6 章的公式现算（四舍五入取整），所以任意箱数都能回放。
 */
object ScreenFixtures {
    const val TOKEN = "screens-token"
    const val BATCH_NO = "SG20261117-1"

    /** 农户 id：李四 11，王秀英 12，张三 13。 */
    private val FARMERS = """[
        {"id":11,"name":"李四","hsCode":"HS-LISI","phones":["13603511234"],"matchedPhones":["13603511234"]},
        {"id":12,"name":"王秀英","hsCode":"HS-WANG","phones":["15803511234","13935107788"],"matchedPhones":["15803511234"]}
    ]"""

    private val RECENT = """[
        {"id":11,"name":"李四","phones":["13603511234"]},
        {"id":13,"name":"张三","phones":["13800005678"]},
        {"id":12,"name":"王秀英","phones":["15803511234","13935107788"]}
    ]"""

    private val BOOTSTRAP = """{
        "date":"2026-11-17",
        "products":[{"id":21,"name":"粉蛋"},{"id":22,"name":"红蛋"}],
        "specs":[
            {"id":30,"name":"240枚/箱","eggCount":240,"stdNetJin":30,"basePrice":120},
            {"id":31,"name":"360枚/箱","eggCount":360,"stdNetJin":45,"basePrice":180},
            {"id":32,"name":"480枚/箱","eggCount":480,"stdNetJin":60}
        ],
        "warehouses":[{"id":41,"name":"1号仓库"},{"id":42,"name":"2号仓库"}],
        "tares":[
            {"id":51,"name":"1号包装箱","tareJin":1.5},
            {"id":52,"name":"2号包装箱","tareJin":2},
            {"id":53,"name":"3号包装箱","tareJin":2.5}
        ],
        "settings":{"round.mode":"half","print.timing":"per-box","label.content":"minimal",
            "label.width-mm":"60","label.height-mm":"40","label.gap-mm":"2"},
        "options":{"scale.tare-by":"scale","scale.reading-is-net":"true","scale.stable-by":"sdk",
            "weigh.record-by":"auto","weigh.merge-detect":"auto","print.enabled":"on","submit.without-price":"block"}
    }"""

    /** 粉蛋 360 枚/箱下的三条启用的收购规则，都用 2号包装箱；规则不绑农户，建单按名称选。 */
    private fun rule(id: Int, name: String, coef: Double) =
        """{"id":$id,"name":"$name","kind":"BUY","productId":21,"productName":"粉蛋","specId":31,"specName":"360枚/箱",
            "stdNetJin":45,"tareBoxId":52,"tareName":"2号包装箱","tareJin":2,"lossJin":0,
            "coef":$coef,"enabled":true}"""

    private val RULE_LISI = rule(61, "粉蛋李四收购定价方案", 1.0)
    private val RULE_WANG = rule(62, "粉蛋王秀英收购定价方案", 1.0)
    private val RULE_COMMON = rule(63, "粉蛋360通用收购方案", 0.8)

    private fun ruleCoef(id: Long): Double = when (id) {
        61L, 62L -> 1.0
        63L -> 0.8
        else -> 1.0
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** 回放服务：按方法加路径回固定内容；认不出的接口回 404，渲染测试里会看到“连不上”之类的页面。 */
    val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val full = request.path.orEmpty()
            val path = full.substringBefore('?')
            val query = full.substringAfter('?', "")
            val body = request.body.readUtf8()
            return when ("${request.method} $path") {
                "POST /api/auth/login" -> json("""{"token":"$TOKEN","name":"张师傅","role":"SCALE"}""")
                "GET /api/app/version" -> json("""{"versionCode":1,"versionName":"0.1.0"}""")
                // bootstrap 带上启用的收购规则（建单按名称选）
                "GET /api/app/bootstrap" -> json(BOOTSTRAP.trimEnd().removeSuffix("}") + ""","rules":[$RULE_LISI,$RULE_WANG,$RULE_COMMON]}""")
                "GET /api/farmers" -> json(if (query.contains("tail=1234")) FARMERS else "[]")
                "GET /api/farmers/recent" -> json(RECENT)
                "GET /api/rules" -> json("[$RULE_LISI,$RULE_WANG,$RULE_COMMON]")
                "POST /api/batches" -> json("""{"batchNo":"$BATCH_NO","date":"2026-11-17"}""")
                "POST /api/orders/quote" -> json(quote(JsonParser.parseString(body).asJsonObject))
                "POST /api/orders" -> json(submitted(JsonParser.parseString(body).asJsonObject))
                else -> MockResponse().setResponseCode(404)
                    .setBody("""{"code":"NOT_FOUND","message":"回放里没有这个接口：${request.method} $path"}""")
            }
        }
    }

    private fun centsOf(jin: Double) = (jin * 100).roundToInt()

    /** 四舍五入取整（半数进位），与 v7 第 6 章、后端 PricingService 的 half 模式一致。 */
    private fun roundHalf(totalCents: Int, n: Int): Int = floor((2.0 * totalCents + n * 100) / (2.0 * n * 100)).toInt()

    private fun fmt(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.4f".format(d).trimEnd('0')

    private fun quote(req: JsonObject): String {
        val nets = req.getAsJsonArray("boxNetJin").map { centsOf(it.asDouble) }
        val n = nets.size
        val total = nets.sum()
        val rawAvg = total.toDouble() / n / 100
        val avg = roundHalf(total, n)
        val std = 45
        val base = 180.0
        val coef = when {
            req.has("trialCoef") && !req.get("trialCoef").isJsonNull -> req.get("trialCoef").asDouble
            else -> ruleCoef(req.get("ruleId").asLong)
        }
        val ruleId = req.get("ruleId").asLong
        val ruleName = when (ruleId) {
            62L -> "粉蛋王秀英收购定价方案"
            63L -> "粉蛋360通用收购方案"
            else -> "粉蛋李四收购定价方案"
        }
        val tail = (avg - std).toDouble()
        val unit = base + tail * coef
        return """{"boxCount":$n,"totalJin":${fmt(total / 100.0)},"rawAvgJin":${fmt(rawAvg)},"avgJin":$avg,
            "standardNetJin":$std,"basePrice":${fmt(base)},"ruleId":$ruleId,"ruleName":"$ruleName",
            "coefficient":${fmt(coef)},"tailJin":${fmt(tail)},"priceDiff":${fmt(tail)},
            "unitPrice":${fmt(unit)},"amount":${fmt(unit * n)},"roundMode":"HALF","issues":[]}"""
    }

    private fun submitted(req: JsonObject): String {
        val boxes = req.getAsJsonArray("boxes")
        val nets = boxes.map { centsOf(it.asJsonObject.get("netJin").asDouble) }
        val n = nets.size
        val total = nets.sum()
        val avg = roundHalf(total, n)
        val unit = 180.0 + (avg - 45)
        return """{"id":1001,"code":"RK20261117001","batchNo":"$BATCH_NO","status":"PENDING","source":"APP",
            "boxCount":$n,"totalNetJin":${fmt(total / 100.0)},"avgJin":$avg,
            "submittedPrice":${fmt(unit)},"submittedAmount":${fmt(unit * n)}}"""
    }
}
