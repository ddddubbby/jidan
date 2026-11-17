package com.jidan.receiving.ui.sheets

import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.SameWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerGroupsTest {
    private fun groupsOf(kinds: Int) =
        SameWeight.groups((1..kinds).map { Kg.of(44.0 + it * 0.5) })

    @Test
    fun twelveKindsFoldToSixThenExpandToAll() {
        val groups = groupsOf(12)
        assertEquals(12, groups.size)

        val folded = ledgerGroupsView(groups, expanded = false)
        assertTrue(folded.foldable)
        assertEquals(6, folded.shown.size)
        assertEquals(6, folded.hidden)

        val all = ledgerGroupsView(groups, expanded = true)
        assertTrue(all.foldable)
        assertEquals(groups, all.shown)
        assertEquals(12, all.shown.size)
    }

    @Test
    fun eightKindsOrFewerAreNeverFolded() {
        val v = ledgerGroupsView(groupsOf(8), expanded = false)
        assertFalse(v.foldable)
        assertEquals(8, v.shown.size)
        assertEquals(0, v.hidden)
        assertTrue(ledgerGroupsView(groupsOf(9), expanded = false).foldable)
    }

    @Test
    fun foldedHeadKeepsMostBoxesFirst() {
        // 46 有 3 箱、其余各 1 箱：折叠后 46 仍排第一
        val weights = (listOf(46.0, 46.0, 46.0) + (1..10).map { 50.0 + it }).map { Kg.of(it) }
        val v = ledgerGroupsView(SameWeight.groups(weights), expanded = false)
        assertEquals("46kg-3箱", v.shown.first().label())
    }

    @Test
    fun displayLabelOnlyAddsInvisibleJoiners() {
        val g = SameWeight.groups(listOf(Kg.of(45.6), Kg.of(45.6))).first()
        assertEquals("45.6kg-2箱", g.label())   // 回传备注用的原文不变
        val shown = g.displayLabel()
        assertEquals(g.label(), shown.replace("\u2060", ""))
        // 能断开的地方都放了连接符：“-”后面、“2”与“箱”之间
        assertTrue(shown.contains("-\u20602\u2060箱"))
    }
}
