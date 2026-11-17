package com.jidan.receiving.screens

/**
 * 孤字行判定（F4-1）：多行文字的最后一行去掉空白后只剩一个字，或一个字加标点，视为孤字行。
 * 纯 JVM，不依赖 Android；扫描 View 树的部分在 [ScreenHarness]。
 */
object OrphanLine {
    private val punctTypes = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    ).map { it.toInt() }.toSet()

    private fun isSpace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun isPunct(cp: Int) = Character.getType(cp) in punctTypes

    /** [lastLine] 是排版后最后一行的原文（可带换行符或空白）。空行不算孤字。 */
    fun isOrphan(lastLine: String): Boolean {
        val cps = lastLine.codePoints().toArray().filterNot(::isSpace)
        if (cps.isEmpty()) return false
        // 一个字（含只剩一个标点），或一个字加前后标点，如“箱。”“（元）”
        return cps.count { !isPunct(it) } <= 1 && cps.size <= 3
    }

    /** 最后一行去掉首尾空白后的样子，写进 orphans.tsv。 */
    fun trimmed(lastLine: String): String = lastLine.trim { isSpace(it.code) }
}

/** 一处孤字行：哪套尺寸、哪个页面状态、哪个控件、整段文字、最后一行。 */
data class OrphanHit(
    val config: String,
    val screen: String,
    val view: String,
    val text: String,
    val lastLine: String,
    val lines: Int,
) {
    fun tsv(): String =
        listOf(config, screen, view, text.replace('\n', '⏎'), lastLine, lines.toString()).joinToString("\t")

    companion object {
        const val HEADER = "config\tscreen\tview\ttext\tlastLine\tlines"
    }
}

/**
 * 允许名单：每行 `config<TAB>screen<TAB>lastLine<TAB>reason`，`#` 开头为注释，空行忽略。
 * config 如 `393-1.3`，screen 如 `11-ledger-sheet`，lastLine 是孤字行的原文，三者都可写 `*`；reason 必填，写明为什么可以留。
 * 默认是空的：出现孤字行就让渲染任务失败。
 */
class OrphanAllowList(private val rules: List<List<String>>) {
    fun allows(hit: OrphanHit): Boolean = rules.any { (config, screen, last, _) ->
        (config == "*" || config == hit.config) &&
            (screen == "*" || screen == hit.screen) &&
            (last == "*" || last == hit.lastLine)
    }

    companion object {
        val EMPTY = OrphanAllowList(emptyList())

        fun parse(text: String): OrphanAllowList {
            val rules = text.lines().mapIndexedNotNull { i, raw ->
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.trimStart().startsWith("#")) return@mapIndexedNotNull null
                val cols = line.split('\t')
                require(cols.size == 4 && cols[3].isNotBlank()) {
                    "允许名单第 ${i + 1} 行要四列（config、screen、lastLine、reason），reason 必填：$line"
                }
                cols
            }
            return OrphanAllowList(rules)
        }
    }
}
