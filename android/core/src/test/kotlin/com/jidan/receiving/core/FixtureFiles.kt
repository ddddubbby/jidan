package com.jidan.receiving.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** 读仓库根 fixtures/ 的算例文件。Gradle 测试的工作目录是 android/core，往上找到带 fixtures/ 的仓库根。 */
internal fun loadFixture(name: String): JsonObject {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "fixtures/$name").isFile) dir = dir.parentFile
    val file = dir?.let { File(it, "fixtures/$name") } ?: error("找不到 fixtures/$name")
    return Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
}

internal fun JsonElement.obj() = this as JsonObject
internal fun JsonElement.arr() = this as JsonArray
internal fun JsonElement.str() = (this as JsonPrimitive).content
internal fun JsonElement.int() = (this as JsonPrimitive).intOrNull ?: error("不是整数：$this")
internal fun JsonElement.kg() = Kg.of((this as JsonPrimitive).doubleOrNull ?: error("不是数字：$this"))
