package com.jidan.receiving.device.label

/**
 * 标签要素在纸上的位置（mm），原点左上（开发规格 9.3）。
 * 纸张尺寸取业务设置默认 60×40、间隙 2。
 */
enum class LabelContent { MINIMAL, FULL }

data class LabelField(
    val key: String,
    val xMm: Float,
    val yMm: Float,
    val fontMm: Float = 3f,
)

data class LabelTemplate(
    val content: LabelContent,
    val widthMm: Float,
    val heightMm: Float,
    val gapMm: Float,
    val fields: List<LabelField>,
    val qrXMm: Float,
    val qrYMm: Float,
    val qrSizeMm: Float,
) {
    companion object {
        fun of(
            content: LabelContent,
            widthMm: Float = 60f,
            heightMm: Float = 40f,
            gapMm: Float = 2f,
        ): LabelTemplate = when (content) {
            LabelContent.MINIMAL -> LabelTemplate(
                content = content,
                widthMm = widthMm,
                heightMm = heightMm,
                gapMm = gapMm,
                fields = listOf(
                    LabelField("net", 3f, 4f, 5f),
                    LabelField("batch", 3f, 12f, 3.5f),
                    LabelField("seq", 3f, 18f, 3.5f),
                ),
                qrXMm = 38f,
                qrYMm = 6f,
                qrSizeMm = 18f,
            )
            LabelContent.FULL -> LabelTemplate(
                content = content,
                widthMm = widthMm,
                heightMm = heightMm,
                gapMm = gapMm,
                fields = listOf(
                    LabelField("product", 2f, 2f, 3.2f),
                    LabelField("spec", 2f, 6.5f, 3f),
                    LabelField("net", 2f, 11f, 3.5f),
                    LabelField("farmer", 2f, 15.5f, 3f),
                    LabelField("date", 2f, 20f, 3f),
                    LabelField("warehouse", 2f, 24.5f, 3f),
                    LabelField("batch", 2f, 29f, 3f),
                    LabelField("seq", 2f, 33.5f, 3f),
                ),
                qrXMm = 40f,
                qrYMm = 8f,
                qrSizeMm = 16f,
            )
        }

        fun parseContent(raw: String?): LabelContent =
            when (raw?.trim()?.lowercase()) {
                "full" -> LabelContent.FULL
                else -> LabelContent.MINIMAL
            }
    }
}
