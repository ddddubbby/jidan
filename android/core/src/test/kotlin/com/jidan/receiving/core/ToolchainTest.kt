package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals

// 只证明 :core 的测试工具链能跑；P3 起换成真正的核心逻辑测试
class ToolchainTest {
    @Test
    fun testsRun() {
        assertEquals(4600, 46 * 100)
    }
}
