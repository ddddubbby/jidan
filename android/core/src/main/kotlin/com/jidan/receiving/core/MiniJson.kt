package com.jidan.receiving.core

/**
 * `Session` 存本地草稿用的最小 JSON 读写（开发规格 9.2、附录 C 第 18 条）。
 * `:core` 不依赖任何库，所以不用 kotlinx.serialization；只支持草稿里出现的几种值：
 * 对象（[Map]）、数组（[List]）、字符串、整数（[Long]）、布尔、null。小数与指数写法不认，读到就抛异常。
 */
internal object MiniJson {

    fun write(value: Any?): String = StringBuilder().also { put(it, value) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipSpace()
        require(p.done()) { "JSON 末尾有多余内容（位置 ${p.pos}）" }
        return v
    }

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is String -> putString(sb, v)
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); put(sb, x) }
                sb.append(']')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    putString(sb, k as String)
                    sb.append(':')
                    put(sb, x)
                }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("不支持的类型：${v::class.simpleName}")
        }
    }

    private fun putString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun done() = pos >= s.length

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            require(!done()) { "JSON 提前结束" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("意外的字符 '$c'")
            }
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg（位置 $pos）")

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, pos)) fail("意外的内容")
            pos += w.length
            return v
        }

        private fun num(): Long {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) fail("只支持整数")
            return s.substring(start, pos).toLongOrNull() ?: fail("数字不合法")
        }

        private fun str(): String {
            pos++ // 开头的引号
            val sb = StringBuilder()
            while (true) {
                if (done()) fail("字符串没有结束")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (done()) fail("字符串没有结束")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u 转义不完整")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("\\u 转义不合法"))
                                pos += 4
                            }
                            else -> fail("不认识的转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipSpace()
            if (!done() && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipSpace()
                if (done()) fail("数组没有结束")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("数组里应是逗号或右括号")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (!done() && s[pos] == '}') { pos++; return out }
            while (true) {
                skipSpace()
                if (done() || s[pos] != '"') fail("对象的键必须是字符串")
                val k = str()
                skipSpace()
                if (done() || s[pos++] != ':') fail("键后面应是冒号")
                out[k] = value()
                skipSpace()
                if (done()) fail("对象没有结束")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("对象里应是逗号或右括号")
                }
            }
        }
    }
}
