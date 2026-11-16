package com.jidan.receiving.printing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReprintActionTest {
    @Test
    fun hiddenWhenPrintingOffOrNothingUnprinted() {
        assertNull(reprintAction(printEnabled = false, unprinted = 3, printBlock = "缺纸"))
        assertNull(reprintAction(printEnabled = true, unprinted = 0, printBlock = ""))
    }

    @Test
    fun printerProblemSendsToDevicesElsePrints() {
        assertEquals(ReprintAction.FixPrinter("缺纸"), reprintAction(true, 2, "缺纸"))
        assertEquals(ReprintAction.Print, reprintAction(true, 2, ""))
    }
}
