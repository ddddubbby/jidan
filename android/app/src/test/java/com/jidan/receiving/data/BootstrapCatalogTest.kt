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
            {"id": 11, "name": "360枚/箱", "eggCount": 360, "stdNetKg": 45.00, "basePrice": 180.00},
            {"id": 12, "name": "480枚/箱", "eggCount": 480, "stdNetKg": 60.00, "basePrice": null}
          ],
          "warehouses": [{"id": 21, "name": "一号库"}],
          "tares": [{"id": 31, "name": "2号包装箱", "tareKg": 2.00}],
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
        assertEquals(2.0, c.tares.single().tareKg)

        val s360 = c.specs.first { it.id == 11L }
        assertEquals(45.0, s360.stdNetKg)
        assertEquals(180.0, s360.basePrice)
        assertEquals(360, s360.eggCount)
        val s480 = c.specs.first { it.id == 12L }
        assertEquals(60.0, s480.stdNetKg)
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
}
