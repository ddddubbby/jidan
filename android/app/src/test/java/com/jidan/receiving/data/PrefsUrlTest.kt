package com.jidan.receiving.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Prefs.normalizedBaseUrl 依赖 SharedPreferences；这里用同规则的纯函数复刻，
 * 保证地址规范化与 Retrofit 约定一致。
 */
class PrefsUrlTest {
    private fun normalize(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        while (u.endsWith("/")) u = u.dropLast(1)
        return "$u/"
    }

    @Test
    fun addsSchemeAndSlash() {
        assertEquals("http://192.168.1.10:8080/", normalize("192.168.1.10:8080"))
        assertEquals("http://10.0.2.2:8080/", normalize("http://10.0.2.2:8080///"))
        assertEquals("https://egg.example/", normalize("https://egg.example"))
        assertEquals("", normalize("  "))
    }
}
