package com.jidan.receiving.ui.devices

import com.jidan.receiving.device.LinkStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** 与 [linkAction] 同序的纯函数断言（避免 JVM 单测依赖 Android Context）。 */
private data class GateSnap(val bt: Boolean, val perm: Boolean)

private fun action(g: GateSnap, status: LinkStatus, paired: String?): LinkActionKind = when {
    !g.bt -> LinkActionKind.OPEN_BLUETOOTH
    !g.perm -> LinkActionKind.GRANT_PERMISSION
    status == LinkStatus.CONNECTING -> LinkActionKind.CONNECTING
    status == LinkStatus.CONNECTED -> LinkActionKind.NONE
    paired.isNullOrBlank() -> LinkActionKind.SEARCH
    else -> LinkActionKind.RESEARCH
}

class LinkActionTest {
    @Test
    fun precedence() {
        val g = GateSnap(bt = true, perm = true)
        assertEquals(LinkActionKind.OPEN_BLUETOOTH, action(GateSnap(false, true), LinkStatus.DISCONNECTED, null))
        assertEquals(LinkActionKind.GRANT_PERMISSION, action(GateSnap(true, false), LinkStatus.DISCONNECTED, null))
        assertEquals(LinkActionKind.CONNECTING, action(g, LinkStatus.CONNECTING, "A3F1"))
        assertEquals(LinkActionKind.NONE, action(g, LinkStatus.CONNECTED, "A3F1"))
        assertEquals(LinkActionKind.SEARCH, action(g, LinkStatus.DISCONNECTED, null))
        assertEquals(LinkActionKind.RESEARCH, action(g, LinkStatus.DISCONNECTED, "A3F1"))
        assertEquals(LinkActionKind.RESEARCH, action(g, LinkStatus.LOST, "A3F1"))
    }
}
