package com.jidan.receiving.ui.devices

import com.jidan.receiving.device.LinkStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** 直接测 [linkAction] 的纯函数重载（Context 版只是读 gate 后转调它）。 */
class LinkActionTest {
    private fun act(
        bt: Boolean = true,
        perm: Boolean = true,
        status: LinkStatus = LinkStatus.DISCONNECTED,
        paired: String? = null,
        empty: Boolean = false,
    ) = linkAction(bt, perm, status, paired, empty)

    @Test
    fun precedence() {
        assertEquals(LinkActionKind.OPEN_BLUETOOTH, act(bt = false, status = LinkStatus.DISCONNECTED))
        assertEquals(LinkActionKind.GRANT_PERMISSION, act(perm = false))
        assertEquals(LinkActionKind.CONNECTING, act(status = LinkStatus.CONNECTING, paired = "A3F1"))
        assertEquals(LinkActionKind.NONE, act(status = LinkStatus.CONNECTED, paired = "A3F1"))
        assertEquals(LinkActionKind.SEARCH, act())
        assertEquals(LinkActionKind.RESEARCH, act(paired = "A3F1"))
        assertEquals(LinkActionKind.RESEARCH, act(status = LinkStatus.LOST, paired = "A3F1"))
    }

    /** APP-01 三种无设备路径：蓝牙关给“打开蓝牙”，没授权给“允许使用蓝牙”，搜不到给“重新搜索”。 */
    @Test
    fun noDevicePathsGiveTheRightButton() {
        assertEquals("打开蓝牙", act(bt = false).label(isScale = true))
        assertEquals("允许使用蓝牙", act(perm = false).label(isScale = true))
        assertEquals("搜索秤", act().label(isScale = true))
        assertEquals("重新搜索", act(empty = true).label(isScale = true))
        assertEquals("重新搜索", act(empty = true).label(isScale = false))
        assertEquals("搜索打印机", act().label(isScale = false))
    }

    @Test
    fun bluetoothOffWinsOverEverythingElse() {
        // 蓝牙关着、又没授权、又搜过空：先开蓝牙，一次只给一个操作
        assertEquals(LinkActionKind.OPEN_BLUETOOTH, act(bt = false, perm = false, empty = true, paired = "A3F1"))
        assertEquals(LinkActionKind.GRANT_PERMISSION, act(perm = false, empty = true, paired = "A3F1"))
    }

    @Test
    fun searchedEmptyDoesNotHideConnectingOrConnected() {
        assertEquals(LinkActionKind.CONNECTING, act(status = LinkStatus.CONNECTING, empty = true))
        assertEquals(LinkActionKind.NONE, act(status = LinkStatus.CONNECTED, empty = true))
    }
}
