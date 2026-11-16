package com.jidan.receiving.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 算例文件（开发规格 5.3）能解析、必需字段齐全。APP 与后端读仓库根 fixtures/ 里的同一份，不各存副本。
 * 核心逻辑（P3-1 起）落地后再在对应测试里逐条跑这些算例；这里只管文件本身。
 */
class FixturesTest {

    // Gradle 测试的工作目录是 android/core；往上找到带 fixtures/ 的仓库根
    private fun load(name: String): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "fixtures/$name").isFile) dir = dir.parentFile
        val file = dir?.let { File(it, "fixtures/$name") } ?: error("找不到 fixtures/$name")
        return Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
    }

    /** 收集缺失或类型不对的字段路径，一次报全。 */
    private class Check {
        val problems = mutableListOf<String>()

        private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

        fun number(n: JsonObject, path: String, field: String) {
            val p = n[field].prim()
            if (p == null || p.isString || p.doubleOrNull == null) problems += "$path.$field 应为数字"
        }

        fun text(n: JsonObject, path: String, field: String) {
            val p = n[field].prim()
            if (p == null || !p.isString || p.content.isEmpty()) problems += "$path.$field 应为非空文本"
        }

        fun bool(n: JsonObject, path: String, field: String) {
            val p = n[field].prim()
            if (p == null || p.isString || p.booleanOrNull == null) problems += "$path.$field 应为布尔值"
        }

        fun obj(n: JsonObject, path: String, field: String): JsonObject {
            val o = n[field] as? JsonObject
            if (o == null) problems += "$path.$field 应为对象"
            return o ?: JsonObject(emptyMap())
        }

        fun nonEmptyArray(n: JsonObject, path: String, field: String): List<JsonElement> {
            val a = n[field] as? JsonArray
            if (a == null || a.isEmpty()) {
                problems += "$path.$field 应为非空数组"
                return emptyList()
            }
            return a
        }

        /** 每个算例都要有 id、来源、说明。 */
        fun caseHead(c: JsonObject, path: String) {
            text(c, path, "id")
            text(c, path, "source")
            text(c, path, "desc")
        }
    }

    private fun assertNoProblems(ck: Check) = assertTrue(ck.problems.isEmpty(), ck.problems.joinToString("；"))

    private fun cases(ck: Check, root: JsonObject, name: String) = ck.nonEmptyArray(root, name, "cases").mapIndexed { i, c ->
        val path = "$name.cases[$i]"
        val o = c as? JsonObject ?: JsonObject(emptyMap()).also { ck.problems += "$path 应为对象" }
        ck.caseHead(o, path)
        path to o
    }

    @Test
    fun pricingParsesWithRequiredFields() {
        val root = load("pricing.json")
        val ck = Check()
        for ((p, c) in cases(ck, root, "pricing")) {
            val input = ck.obj(c, p, "input")
            ck.nonEmptyArray(input, "$p.input", "boxNetKg")
            listOf("standardNetKg", "basePrice", "coefficient").forEach { ck.number(input, "$p.input", it) }
            ck.text(input, "$p.input", "rounding")
            val expected = ck.obj(c, p, "expected")
            listOf("totalKg", "rawAvgKg", "avgKg", "tailKg", "priceDiff", "unitPrice", "amount").forEach { ck.number(expected, "$p.expected", it) }
        }
        ck.nonEmptyArray(root, "pricing", "roundingTable").forEachIndexed { i, row ->
            listOf("boxCount", "totalKg", "rawAvgKg", "half", "floor", "ceil").forEach {
                ck.number(row as? JsonObject ?: JsonObject(emptyMap()), "pricing.roundingTable[$i]", it)
            }
        }
        assertNoProblems(ck)
    }

    @Test
    fun detectKParsesWithRequiredFields() {
        val ck = Check()
        for ((p, c) in cases(ck, load("detect-k.json"), "detect-k")) {
            val input = ck.obj(c, p, "input")
            listOf("grossKg", "tareKg", "standardNetKg", "tolerancePct", "maxK").forEach { ck.number(input, "$p.input", it) }
            val e = ck.obj(c, p, "expected")
            listOf("k", "tareTotalKg", "netTotalKg").forEach { ck.number(e, "$p.expected", it) }
            ck.bool(e, "$p.expected", "ambiguous")
            // 识别不准时给两个候选箱数，否则必须明确为 null
            val ambiguous = (e["ambiguous"] as? JsonPrimitive)?.booleanOrNull == true
            val candidates = e["candidates"]
            if (ambiguous) {
                if (candidates !is JsonArray || candidates.size != 2) ck.problems += "$p.expected.candidates 应为两个箱数"
            } else if (candidates !is JsonNull) {
                ck.problems += "$p.expected.candidates 应为 null"
            }
        }
        assertNoProblems(ck)
    }

    @Test
    fun weighingParsesWithRequiredFields() {
        val ck = Check()
        for ((p, c) in cases(ck, load("weighing.json"), "weighing")) {
            val setup = ck.obj(c, p, "setup")
            ck.number(setup, "$p.setup", "tareKg")
            ck.number(setup, "$p.setup", "standardNetKg")
            ck.obj(setup, "$p.setup", "options")
            ck.nonEmptyArray(c, p, "events").forEachIndexed { j, e ->
                val o = e as? JsonObject ?: JsonObject(emptyMap())
                ck.number(o, "$p.events[$j]", "grossKg")
                ck.bool(o, "$p.events[$j]", "stable")
            }
            val records = ck.obj(c, p, "expected")["records"] as? JsonArray
            if (records == null) ck.problems += "$p.expected.records 应为数组"
            records?.forEachIndexed { j, r ->
                listOf("seq", "group", "k", "netKg", "tareKg", "grossKg", "atEvent").forEach {
                    ck.number(r as? JsonObject ?: JsonObject(emptyMap()), "$p.expected.records[$j]", it)
                }
            }
        }
        assertNoProblems(ck)
    }

    @Test
    fun remarkParsesWithRequiredFields() {
        val ck = Check()
        for ((p, c) in cases(ck, load("remark.json"), "remark")) {
            val input = ck.obj(c, p, "input")
            ck.nonEmptyArray(input, "$p.input", "boxNetKg")
            ck.number(input, "$p.input", "maxLength")
            val e = ck.obj(c, p, "expected")
            ck.text(e, "$p.expected", "text")
            ck.bool(e, "$p.expected", "truncated")
            ck.nonEmptyArray(e, "$p.expected", "groups").forEachIndexed { j, g ->
                val o = g as? JsonObject ?: JsonObject(emptyMap())
                ck.number(o, "$p.expected.groups[$j]", "kg")
                ck.number(o, "$p.expected.groups[$j]", "boxes")
            }
        }
        assertNoProblems(ck)
    }
}
