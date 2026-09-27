package com.example.workshifttracker

import org.junit.Assert.*
import org.junit.Test

class RotaBlockOwnershipTest {
    @Test fun ambiguousBoundaryAbstainsWhileCenteredEmployeeGetsBlock() {
        val starts = listOf(100f, 270f, 510f, 760f)
        assertEquals(1, RotaBlockOwnership.resolve(starts, 400f, 1000f).block)
        assertNull(RotaBlockOwnership.resolve(starts, 509f, 1000f).block)
        assertNull(RotaBlockOwnership.resolve(starts, 76f, 1000f).block)
    }
}
