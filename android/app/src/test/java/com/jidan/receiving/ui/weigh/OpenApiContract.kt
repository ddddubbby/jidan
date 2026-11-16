package com.jidan.receiving.ui.weigh

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 按仓库里的 `api/openapi.json` 核对请求与回放的响应：方法加路径存在，字段都在契约里，必填字段都带了。
 * 只看字段名与结构，不看取值类型；`additionalProperties` 的键值表（settings、options）不逐项核对。
 * 违规记在 [violations]，测试末尾断言为空。
 */
class OpenApiContract(file: File) {
    private val root: JsonObject = JsonParser.parseString(file.readText()).asJsonObject
    private val paths = root.getAsJsonObject("paths")
    private val schemas = root.getAsJsonObject("components").getAsJsonObject("schemas")

    val violations = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun operation(method: String, path: String): JsonObject? =
        paths.getAsJsonObject(path)?.getAsJsonObject(method.lowercase())

    fun checkRequest(method: String, path: String, body: String) {
        val op = operation(method, path)
        if (op == null) {
            violations += "契约里没有 $method $path"
            return
        }
        val schema = op.getAsJsonObject("requestBody")
            ?.getAsJsonObject("content")?.getAsJsonObject("application/json")?.getAsJsonObject("schema")
        if (schema == null) {
            if (body.isNotBlank()) violations += "$method $path 契约里没有请求体，却收到：$body"
            return
        }
        if (body.isBlank()) {
            // 可选请求体（如 POST /api/batches）可以不带
            if (op.getAsJsonObject("requestBody").get("required")?.asBoolean == true) {
                violations += "$method $path 请求体必填，却是空的"
            }
            return
        }
        validate(schema, JsonParser.parseString(body), "$method $path 请求")
    }

    fun checkResponse(method: String, path: String, body: String) {
        val op = operation(method, path)
        if (op == null) {
            violations += "契约里没有 $method $path"
            return
        }
        val schema = op.getAsJsonObject("responses")?.getAsJsonObject("200")
            ?.getAsJsonObject("content")?.entrySet()?.firstOrNull()?.value?.asJsonObject?.getAsJsonObject("schema")
            ?: return
        validate(schema, JsonParser.parseString(body), "$method $path 响应")
    }

    private fun resolve(schema: JsonObject): JsonObject {
        val ref = schema.get("\$ref")?.asString ?: return schema
        return schemas.getAsJsonObject(ref.substringAfterLast('/'))
    }

    private fun validate(raw: JsonObject, value: JsonElement, where: String) {
        val schema = resolve(raw)
        when {
            schema.get("type")?.asString == "array" -> {
                if (!value.isJsonArray) {
                    violations += "$where 应是数组"
                    return
                }
                val items = schema.getAsJsonObject("items") ?: return
                (value as JsonArray).forEachIndexed { i, e -> validate(items, e, "$where[$i]") }
            }
            schema.has("properties") -> {
                if (!value.isJsonObject) {
                    violations += "$where 应是对象"
                    return
                }
                val obj = value.asJsonObject
                val props = schema.getAsJsonObject("properties")
                for ((k, v) in obj.entrySet()) {
                    val sub = props.getAsJsonObject(k)
                    if (sub == null) {
                        violations += "$where 的字段 $k 不在契约里"
                    } else if (!v.isJsonNull) {
                        validate(sub, v, "$where.$k")
                    }
                }
                schema.getAsJsonArray("required")?.forEach { r ->
                    val name = r.asString
                    if (!obj.has(name) || obj.get(name).isJsonNull) violations += "$where 缺必填字段 $name"
                }
            }
        }
    }
}
