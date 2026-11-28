package com.jidan.receiving.data

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1-1：商品、规格、仓库、皮重都从 `GET /api/app/bootstrap` 一次取齐（磅秤员账号调不了档案接口）。
 * 样例是后端 `OrderSubmitTest.bootstrapDeliversPricesSettingsAndAppOptions` 那组夹具的响应形状：
 * 规格 360 当日有基准价、480 没录；只下发热键皮重。
 */
class BootstrapCatalogTest {
    private val sample = """
        {
          "date": "2026-11-16",
          "products": [{"id": 1, "name": "粉蛋"}, {"id": 2, "name": "红蛋"}],
          "specs": [
            {"id": 11, "name": "360枚/箱", "eggCount": 360, "stdNetJin": 45.00, "basePrice": 180.00},
            {"id": 12, "name": "480枚/箱", "eggCount": 480, "stdNetJin": 60.00, "basePrice": null}
          ],
          "warehouses": [{"id": 21, "name": "一号库"}],
          "tares": [{"id": 31, "name": "2号包装箱", "tareJin": 2.00}],
          "settings": {"round.mode": "half", "print.timing": "per-box"},
          "options": {"scale.tare-by": "scale", "weigh.merge-tolerance-pct": "10"}
        }
    """.trimIndent()

    private fun parse(json: String): BootstrapResponse = Gson().fromJson(json, BootstrapResponse::class.java)

    @Test
    fun parsesProductsSpecsWarehousesAndTares() {
        val c = parse(sample).toCatalog()
        assertEquals(listOf("粉蛋", "红蛋"), c.products.map { it.name })
        assertEquals(listOf(21L), c.warehouses.map { it.id })
        assertEquals("一号库", c.warehouses.single().name)
        assertEquals("2号包装箱", c.tares.single().name)
        assertEquals(2.0, c.tares.single().tareJin)

        val s360 = c.specs.first { it.id == 11L }
        assertEquals(45.0, s360.stdNetJin)
        assertEquals(180.0, s360.basePrice)
        assertEquals(360, s360.eggCount)
        val s480 = c.specs.first { it.id == 12L }
        assertEquals(60.0, s480.stdNetJin)
        assertNull(s480.basePrice)
    }

    @Test
    fun basePricesKeepMissingAsNull() {
        val prices = parse(sample).toCatalog().basePrices()
        assertEquals(180.0, prices[11L])
        assertTrue(prices.containsKey(12L))
        assertNull(prices[12L])
    }

    @Test
    fun settingsAndOptionsStillFeedAppOptions() {
        val o = AppOptions.from(parse(sample).allValues())
        assertEquals("per-box", o.get("print.timing"))
        assertEquals("scale", o.get("scale.tare-by"))
    }

    @Test
    fun missingListsBecomeEmpty() {
        val c = parse("""{"date": "2026-11-16"}""").toCatalog()
        assertEquals(emptyList(), c.products)
        assertEquals(emptyList(), c.specs)
        assertEquals(emptyList(), c.warehouses)
        assertEquals(emptyList(), c.tares)
    }

    /** 新后端 bootstrap 的 rules 原样（本机起后端、磅秤员登录取到的形状），停用的不进建单。 */
    @Test
    fun parsesRulesFromNewBackend() {
        val c = parse(
            """
            {"date": "2026-11-28", "rules": [
              {"id": 1, "name": "粉蛋李四收购定价方案", "kind": "BUY", "productId": 1, "productName": "粉蛋",
               "specId": 2, "specName": "360枚/箱", "stdNetJin": 90.0, "tareBoxId": 2, "tareName": "2号包装箱",
               "tareJin": 4.0, "lossJin": 0.0, "coef": 0.5, "enabled": true},
              {"id": 3, "name": "粉蛋360通用收购方案", "kind": "BUY", "productId": 1, "specId": 2,
               "lossJin": -1.0, "coef": 0.4, "enabled": true},
              {"id": 4, "name": "停用的方案", "kind": "BUY", "productId": 1, "specId": 2, "enabled": false}
            ]}
            """.trimIndent(),
        ).toCatalog()
        assertEquals(listOf("粉蛋李四收购定价方案", "粉蛋360通用收购方案"), c.rules.map { it.name })
        assertEquals(-1.0, c.rules[1].lossJin)
        assertEquals(false, c.oldBackend)
    }

    /** 旧后端不下发 rules：标出来，规则一步提示更新后端，而不是只说“没有规则”。 */
    @Test
    fun missingRulesMeansOldBackend() {
        assertTrue(parse("""{"date": "2026-11-16"}""").toCatalog().oldBackend)
        assertEquals(false, parse("""{"date": "2026-11-16", "rules": []}""").toCatalog().oldBackend)
    }
}
