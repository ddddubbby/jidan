package com.jidan.receiving.ui.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginFormTest {
    @Test
    fun phoneFormatting() {
        assertEquals("138", formatPhoneDisplay("138"))
        assertEquals("138 0013", formatPhoneDisplay("1380013"))
        assertEquals("138 0013 8000", formatPhoneDisplay("13800138000"))
        assertEquals("13800138000", digitsPhone("138 0013 8000abc"))
    }

    @Test
    fun stepOrder() {
        assertEquals(LoginStep.SERVER, loginStep("", "13800138000", "123456", false))
        assertEquals(LoginStep.PHONE, loginStep("http://10.0.2.2:8080", "138", "123456", false))
        assertEquals(LoginStep.PASSWORD, loginStep("http://10.0.2.2:8080", "13800138000", "", false))
        assertEquals(LoginStep.READY, loginStep("http://10.0.2.2:8080", "13800138000", "123456", false))
        assertEquals(LoginStep.BUSY, loginStep("http://10.0.2.2:8080", "13800138000", "123456", true))
    }

    @Test
    fun updateGate() {
        assertFalse(shouldShowUpdate(1, null))
        assertFalse(shouldShowUpdate(2, AppVersionLike(2, "http://x/a.apk")))
        assertFalse(shouldShowUpdate(1, AppVersionLike(2, null)))
        assertFalse(shouldShowUpdate(1, AppVersionLike(2, "")))
        assertTrue(shouldShowUpdate(1, AppVersionLike(2, "http://x/a.apk")))
    }
}
