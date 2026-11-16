package com.jidan.receiving.data

import com.jidan.receiving.core.WeighOptions

/**
 * APP 端选项镜像（开发规格 4.3 / 9.4）。默认值即 v7 做法。
 * 正常从 `GET /app/bootstrap` 解析；P2-2 未合入前始终用 [defaults]。
 */
data class AppOptions(
    val values: Map<String, String> = DEFAULTS,
) {
    fun get(key: String): String = values[key] ?: DEFAULTS.getValue(key)

    fun weighOptions(): WeighOptions = WeighOptions.of { key -> values[key] ?: DEFAULTS[key] }

    companion object {
        /** 开发规格 4.3 第一组（APP 生效）+ 第二组 + 业务设置里 APP 会读的几项。 */
        val DEFAULTS: Map<String, String> = mapOf(
            "scale.tare-by" to "scale",
            "scale.reading-is-net" to "true",
            "scale.stable-by" to "sdk",
            "scale.stable-window-ms" to "800",
            "scale.stable-tolerance-kg" to "0.05",
            "weigh.empty-below-kg" to "5",
            "weigh.merge-tolerance-pct" to "10",
            "weigh.merge-max-k" to "8",
            "print.render" to "text",
            "print.dpi" to "203",
            "print.offset-x-mm" to "0",
            "print.offset-y-mm" to "0",
            "weigh.record-by" to "auto",
            "weigh.merge-detect" to "auto",
            "weigh.manual-entry" to "on",
            "print.enabled" to "on",
            "submit.without-price" to "block",
            "print.timing" to "per-box",
            "label.content" to "minimal",
            "device.scale-model" to "lanstar-ble",
            "device.printer-model" to "gainscha-gp-m32b",
        )

        fun defaults(): AppOptions = AppOptions(DEFAULTS)

        /**
         * [raw] 为 bootstrap 下发的选项键值；缺项用默认值。
         * P2-2 合入后由 AuthRepository / 建单流程调用。
         */
        fun from(raw: Map<String, String?>?): AppOptions {
            if (raw.isNullOrEmpty()) return defaults()
            val merged = DEFAULTS.toMutableMap()
            for ((k, v) in raw) {
                if (v != null && k in DEFAULTS) merged[k] = v
            }
            return AppOptions(merged)
        }
    }
}
