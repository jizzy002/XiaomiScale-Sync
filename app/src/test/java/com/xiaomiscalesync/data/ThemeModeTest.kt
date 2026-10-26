package com.xiaomiscalesync.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun storedModesRoundTripAndUnknownValuesDefaultToSystem() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStoredName(mode.name))
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredName(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredName("PLUM"))
    }
}
