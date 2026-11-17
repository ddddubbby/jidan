package com.jidan.receiving.ui

import com.jidan.receiving.screens.OrphanAllowList
import com.jidan.receiving.screens.OrphanHit
import com.jidan.receiving.screens.OrphanLine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 孤字行判定与允许名单（F4-1 渲染截图用的检查，纯 JVM）。 */
class OrphanLineTest {
    @Test
    fun oneCharacterIsOrphan() {
        assertTrue(OrphanLine.isOrphan("箱"))
        assertTrue(OrphanLine.isOrphan("  箱\n"))
        assertTrue(OrphanLine.isOrphan("5"))
    }

    @Test
    fun oneCharacterWithPunctuationIsOrphan() {
        assertTrue(OrphanLine.isOrphan("箱。"))
        assertTrue(OrphanLine.isOrphan("（元）"))
        assertTrue(OrphanLine.isOrphan("元/"))
    }

    @Test
    fun purePunctuationLineIsOrphan() {
        assertTrue(OrphanLine.isOrphan("。"))
    }

    @Test
    fun twoCharactersOrMoreAreNot() {
        assertFalse(OrphanLine.isOrphan("kg"))
        assertFalse(OrphanLine.isOrphan("元/箱"))
        assertFalse(OrphanLine.isOrphan("共 3 箱"))
    }

    @Test
    fun zeroWidthJoinerAndNoBreakSpaceDoNotCountAsCharacters() {
        // F4-3：数字与单位之间用的零宽连接符、不换行空格，不算字
        assertTrue(OrphanLine.isOrphan("\u2060箱\u00A0"))
        assertFalse(OrphanLine.isOrphan("1\u2060箱"))
    }

    @Test
    fun blankLineIsNotOrphan() {
        assertFalse(OrphanLine.isOrphan(""))
        assertFalse(OrphanLine.isOrphan(" \n"))
    }

    @Test
    fun allowListMatchesByConfigScreenAndLastLine() {
        val hit = OrphanHit("393-1.3", "11-ledger-sheet", "groups", "…", "箱", 2)
        val list = OrphanAllowList.parse("# 注释\n\n393-1.3\t11-ledger-sheet\t箱\t字号放大后这一行是有意留的\n")
        assertTrue(list.allows(hit))
        assertFalse(list.allows(hit.copy(config = "360-1.0")))
        assertFalse(list.allows(hit.copy(lastLine = "元")))
        assertTrue(OrphanAllowList.parse("*\t*\t*\t全部放行（仅测试）").allows(hit))
        assertFalse(OrphanAllowList.EMPTY.allows(hit))
        assertFalse(OrphanAllowList.parse("# 只有注释\n").allows(hit))
    }

    @Test
    fun allowListRequiresReason() {
        assertFailsWith<IllegalArgumentException> { OrphanAllowList.parse("393-1.0\t*\t箱\t") }
    }
}
