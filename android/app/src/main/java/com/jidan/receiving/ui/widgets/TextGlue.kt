package com.jidan.receiving.ui.widgets

/**
 * 数字和单位不被换行拆开（纯字符串，不依赖 Android，单测直接用）。
 *
 * 汉字之间、斜杠之后，换行器都允许断开，所以“181 元/箱”会被拆成“181 元/”和“箱”，末行只剩一个字。
 * 做法：数字与单位之间用 U+00A0（不换行空格）；单位里字与字之间用 U+2060（WORD JOINER，零宽，禁止在此断行）。
 * 数字、单位本身一个字符都不改，所以复制出去、传给后端的值不受影响——只有显示用的文字里带这些零宽字符。
 */
object TextGlue {
    const val NBSP = '\u00A0'
    const val WJ = '\u2060'

    /**
     * 整段不许断：在可能断开的两个字符之间放一个 WORD JOINER。只用于“45.6kg-4箱”这种短词。
     * 数字、小数点、英文字母之间，以及“-”“/”之前，本来就不会断，不加（免得把 kg 之类的字形切开）。
     */
    fun noBreak(s: String): String =
        if (s.length < 2) s else buildString(s.length * 2) {
            s.forEachIndexed { i, c ->
                if (i > 0 && c != '-' && c != '/' && !(s[i - 1].isPlain() && c.isPlain())) append(WJ)
                append(c)
            }
        }

    private fun Char.isPlain() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.'

    /** “181 元/箱”：数字与单位之间一个不换行空格，单位内部也不许断。[space] 为 false 时紧贴（“1箱”）。 */
    fun withUnit(number: String, unit: String, space: Boolean = true): String =
        number + (if (space) NBSP else WJ) + noBreak(unit)
}
