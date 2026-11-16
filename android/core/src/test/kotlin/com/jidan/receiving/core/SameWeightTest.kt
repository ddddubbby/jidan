package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SameWeightTest {

    @Test
    fun remarkCasesGroupOrder() {
        val cases = loadFixture("remark.json")["cases"]!!.arr()
        assertTrue(cases.isNotEmpty())
        for (c in cases.map { it.obj() }) {
            val id = c["id"]!!.str()
            val boxes = c["input"]!!.obj()["boxNetKg"]!!.arr().map { it.kg() }
            val expected = c["expected"]!!.obj()
            val groups = SameWeight.groups(boxes)
            assertEquals(
                expected["groups"]!!.arr().map { WeightGroup(it.obj()["kg"]!!.kg(), it.obj()["boxes"]!!.int()) },
                groups,
                "$id 归类顺序",
            )
            // 归类段与备注全文里的写法一致（备注里用英文逗号相连）
            val text = expected["text"]!!.str()
            assertEquals(text.substringAfter("归类："), groups.joinToString(",") { it.label() }, "$id 归类文字")
        }
    }

    @Test
    fun tiesFollowFirstSeenNotWeight() {
        // 45.6 先出现、46 后出现，各 2 箱：按重量排 46 会在前，按 v7 6.3 应是 45.6 在前
        val groups = SameWeight.groups(listOf(45.6, 46.0, 46.0, 45.6, 47.0).map { Kg.of(it) })
        assertEquals(listOf("45.6kg-2箱", "46kg-2箱", "47kg-1箱"), groups.map { it.label() })
    }

    @Test
    fun groupsByRecordedValueWithoutRounding() {
        val groups = SameWeight.groups(listOf(Kg(4560), Kg(4561), Kg(4560)))
        assertEquals(listOf("45.6kg-2箱", "45.61kg-1箱"), groups.map { it.label() })
    }

    @Test
    fun emptyAndSingle() {
        assertEquals(emptyList(), SameWeight.groups(emptyList()))
        assertEquals(listOf(WeightGroup(Kg(4650), 1)), SameWeight.groups(listOf(Kg(4650))))
    }
}
