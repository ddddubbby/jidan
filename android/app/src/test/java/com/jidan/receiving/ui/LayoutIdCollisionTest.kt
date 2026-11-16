package com.jidan.receiving.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * ViewBinding 按深度优先找第一个同 id 的 View。被 <include> 进来的布局里若有与外层同名的 id，
 * 外层的那个字段会绑到 include 里的控件上（F3-1：建单页底部“开始收蛋”被设备卡片里的 action 按钮顶掉）。
 */
class LayoutIdCollisionTest {
    private val layoutDir = File("src/main/res/layout")
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(name: String): Element {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(File(layoutDir, "$name.xml")).documentElement
    }

    private fun idOf(e: Element) = e.getAttributeNS(android, "id").substringAfter("/").ifEmpty { null }

    /** 本布局自己写的 id（不进 include）与它 include 的布局名。 */
    private fun walk(e: Element, own: MutableSet<String>, includes: MutableList<String>) {
        if (e.tagName == "include") {
            includes += e.getAttribute("layout").substringAfter("/")
            return
        }
        idOf(e)?.let { own += it }
        val kids = e.childNodes
        for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { walk(it, own, includes) }
    }

    /** 某布局展开后（含它再 include 的）全部 id。 */
    private fun allIds(name: String): Set<String> {
        val own = mutableSetOf<String>(); val inc = mutableListOf<String>()
        walk(parse(name), own, inc)
        return own + inc.flatMap { allIds(it) }
    }

    @Test
    fun includedLayoutsDoNotReuseHostIds() {
        val layouts = layoutDir.listFiles { f -> f.extension == "xml" }!!.map { it.nameWithoutExtension }
        assertTrue(layouts.contains("fragment_create"))
        val clashes = layouts.flatMap { host ->
            val own = mutableSetOf<String>(); val inc = mutableListOf<String>()
            walk(parse(host), own, inc)
            inc.flatMap { child -> (allIds(child) intersect own).map { "$host ⊃ $child: @id/$it" } }
        }
        assertEquals(emptyList(), clashes)
    }
}
