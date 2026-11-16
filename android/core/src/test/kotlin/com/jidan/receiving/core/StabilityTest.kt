package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StabilityTest {
    private fun stability() = Stability(800, Kg(5))

    @Test
    fun steadyForTheWholeWindowIsStable() {
        val s = stability()
        assertFalse(s.update(Kg.of(48.5), 0))
        assertFalse(s.update(Kg.of(48.5), 700))
        assertTrue(s.update(Kg.of(48.5), 800))
        assertTrue(s.update(Kg.of(48.5), 900))
    }

    @Test
    fun smallJitterWithinToleranceIsStillStable() {
        val s = stability()
        s.update(Kg.of(48.50), 0)
        s.update(Kg.of(48.54), 400)
        assertTrue(s.update(Kg.of(48.52), 800))
    }

    @Test
    fun aJumpRestartsTheWindow() {
        val s = stability()
        s.update(Kg.of(48.5), 0)
        assertTrue(s.update(Kg.of(48.5), 800))
        assertFalse(s.update(Kg.of(49.0), 900))
        assertFalse(s.update(Kg.of(49.0), 1600))
        assertTrue(s.update(Kg.of(49.0), 1700))
    }

    @Test
    fun checkWithoutNewReadingHoldsTheLastValue() {
        val s = stability()
        s.update(Kg.of(30.0), 0)
        assertFalse(s.check(799))
        assertTrue(s.check(800))
        assertTrue(s.check(60_000))
    }

    @Test
    fun noReadingMeansNotStable() {
        assertFalse(stability().check(10_000))
    }

    @Test
    fun resetForgetsEverything() {
        val s = stability()
        s.update(Kg.of(30.0), 0)
        assertTrue(s.check(900))
        s.reset()
        assertFalse(s.check(1000))
    }

    @Test
    fun slowDriftBeyondToleranceInsideTheWindowIsNotStable() {
        val s = stability()
        var t = 0L
        var v = 3000
        var stable = false
        // 每 100ms 漂 0.02kg，窗口内累计 0.16kg，超出 0.05kg
        repeat(20) { stable = s.update(Kg(v), t); t += 100; v += 2 }
        assertFalse(stable)
    }
}
