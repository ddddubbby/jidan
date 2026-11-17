package com.jidan.receiving.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceModelTest {
    @Test
    fun knownKeysShowShortModel() {
        assertEquals("LS-S2", deviceModelName("lanstar-ble"))
        assertEquals("GP-M32B", deviceModelName("gainscha-gp-m32b"))
    }

    @Test
    fun unknownKeyShownAsIs() {
        assertEquals("acme-x1", deviceModelName("acme-x1"))
    }

    @Test
    fun idKeepsLastFourWithoutSeparators() {
        assertEquals("A3F1", shortDeviceId("A3F1"))
        assertEquals("EEFF", shortDeviceId("aa:bb:cc:dd:ee:ff"))
        assertEquals("LS-S2 ··A3F1", deviceModelText("lanstar-ble", "A3F1"))
    }
}
