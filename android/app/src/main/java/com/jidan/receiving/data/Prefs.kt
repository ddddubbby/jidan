package com.jidan.receiving.data

import android.content.Context

/** 上次的仓库、皮重、手机号、服务器地址（开发规格 9.4）。P6-1 先接手机号与服务器地址。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER, value.trim()).apply()

    var lastPhone: String
        get() = sp.getString(KEY_PHONE, "") ?: ""
        set(value) = sp.edit().putString(KEY_PHONE, value.filter(Char::isDigit).take(11)).apply()

    var lastWarehouseId: Long?
        get() = if (sp.contains(KEY_WAREHOUSE)) sp.getLong(KEY_WAREHOUSE, 0L) else null
        set(value) {
            val ed = sp.edit()
            if (value == null) ed.remove(KEY_WAREHOUSE) else ed.putLong(KEY_WAREHOUSE, value)
            ed.apply()
        }

    var lastTareId: Long?
        get() = if (sp.contains(KEY_TARE)) sp.getLong(KEY_TARE, 0L) else null
        set(value) {
            val ed = sp.edit()
            if (value == null) ed.remove(KEY_TARE) else ed.putLong(KEY_TARE, value)
            ed.apply()
        }

    var pairedScaleId: String?
        get() = sp.getString(KEY_SCALE, null)
        set(value) {
            val ed = sp.edit()
            if (value.isNullOrBlank()) ed.remove(KEY_SCALE) else ed.putString(KEY_SCALE, value)
            ed.apply()
        }

    var pairedPrinterId: String?
        get() = sp.getString(KEY_PRINTER, null)
        set(value) {
            val ed = sp.edit()
            if (value.isNullOrBlank()) ed.remove(KEY_PRINTER) else ed.putString(KEY_PRINTER, value)
            ed.apply()
        }

    /** Retrofit 要求以 `/` 结尾；去掉用户多打的空白与末尾斜杠再补上。 */
    fun normalizedBaseUrl(): String {
        var u = serverUrl.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        while (u.endsWith("/")) u = u.dropLast(1)
        return "$u/"
    }

    companion object {
        private const val NAME = "egg_prefs"
        private const val KEY_SERVER = "server_url"
        private const val KEY_PHONE = "last_phone"
        private const val KEY_WAREHOUSE = "last_warehouse_id"
        private const val KEY_TARE = "last_tare_id"
        private const val KEY_SCALE = "paired_scale_id"
        private const val KEY_PRINTER = "paired_printer_id"
    }
}
