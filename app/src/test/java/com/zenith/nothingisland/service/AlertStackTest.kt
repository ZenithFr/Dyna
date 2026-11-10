package com.zenith.nothingisland.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStackTest {

    @Test
    fun aSingleAlertDoesNotRotate() {
        val stack = AlertStack<String>()
        stack.upsert("a", "A")
        assertFalse(stack.rotating)
        assertEquals("a", stack.nextKey("a"))
    }

    @Test
    fun aSecondAlertStartsRotationAndCyclesInArrivalOrder() {
        val stack = AlertStack<String>()
        stack.upsert("a", "A")
        stack.upsert("b", "B")
        assertTrue(stack.rotating)
        assertEquals("b", stack.nextKey("a"))
        assertEquals("a", stack.nextKey("b"))
    }


    @Test
    fun rotationContinuesUntilTheLastAlertIsDismissed() {
        val stack = AlertStack<String>()
        stack.upsert("a", "A")
        stack.upsert("b", "B")
        assertTrue(stack.remove("a"))
        // One message left: the island still rotates through it until it is dismissed too.
        assertTrue(stack.rotating)
        assertEquals("b", stack.nextKey(null))
        assertTrue(stack.remove("b"))
        assertFalse(stack.rotating)
        assertTrue(stack.isEmpty())
        assertNull(stack.nextKey("b"))
    }

    @Test
    fun dismissingTheShownAlertMovesOnToTheNextRemainingOne() {
        val stack = AlertStack<String>()
        stack.upsert("a", "A")
        stack.upsert("b", "B")
        stack.upsert("c", "C")
        stack.remove("a")
        // "a" is gone, so the next alert in order is "b" rather than a stale index.
        assertEquals("b", stack.nextKey("a"))
    }

    @Test
    fun updatingAnAlertMovesItToTheEndWithoutDuplicating() {
        val stack = AlertStack<String>()
        stack.upsert("a", "A")
        stack.upsert("b", "B")
        stack.upsert("a", "A2")
        assertEquals(2, stack.size)
        assertEquals("A2", stack.get("a"))
        assertEquals("a", stack.nextKey("b"))
    }

    @Test
    fun retainDropsOnlyAlertsTheShadeNoLongerHas() {
        val stack = AlertStack<String>()
        stack.upsert("a", "A")
        stack.upsert("b", "B")
        stack.upsert("c", "C")
        assertEquals(1, stack.retain(setOf("a", "c")))
        assertEquals(2, stack.size)
        assertNull(stack.get("b"))
        // Arrival order survives, so the island still rotates a -> c.
        assertEquals("c", stack.nextKey("a"))
        assertTrue(stack.rotating)
    }

    @Test
    fun retainEmptyingTheQueueEndsRotation() {
        val stack = AlertStack<String>()
        stack.upsert("a", "A")
        stack.upsert("b", "B")
        assertEquals(2, stack.retain(emptySet()))
        assertTrue(stack.isEmpty())
        assertFalse(stack.rotating)
        assertEquals(0, stack.retain(emptySet()))
    }


}
