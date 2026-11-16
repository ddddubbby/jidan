package com.jidan.receiving.data

import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Session
import com.jidan.receiving.core.Tare
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 不碰 Android 文件：只验 Session JSON 与 P6-5 草稿契约一致。 */
class SessionStoreRoundTripTest {
    @Test
    fun draftRoundTripKeepsManual() {
        var s = Session(
            batchNo = "SG20261116-1",
            farmer = Pick(1, "李四"),
            product = Pick(2, "粉蛋"),
            spec = Pick(3, "360枚/箱"),
            warehouse = Pick(4, "一号库"),
            tare = Tare(Kg.of(2.0), "2号箱"),
        )
        s = s.addManual(Kg.of(46.0)).editManual(1, Kg.of(52.8))
        val again = Session.fromJson(s.toJson())
        assertEquals(s, again)
        assertTrue(again.boxes[0].manual)
        assertEquals(Kg.of(52.8), again.boxes[0].net)
    }

    @Test
    fun draftRoundTripKeepsW0AndOldDraftStillReads() {
        val s = Session(
            batchNo = "SG20261116-1",
            farmer = Pick(1, "李四"), product = Pick(2, "粉蛋"), spec = Pick(3, "360枚/箱"), warehouse = Pick(4, "一号库"),
            tare = Tare(Kg.of(2.0), "2号箱"), stdNet = Kg.of(45.0),
        ).addManual(Kg.of(46.0))
        val text = s.toJson()
        assertEquals(s, Session.fromJson(text))
        assertEquals(Kg.of(45.0), Session.fromJson(text).stdNet)

        // 旧版本写的草稿没有 stdNet 键
        val old = text.replace(Regex(",\"stdNet\":\\d+"), "")
        assertTrue("stdNet" !in old, old)
        val back = Session.fromJson(old)
        assertEquals(null, back.stdNet)
        assertEquals(s.copy(stdNet = null), back)
    }
}
