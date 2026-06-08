package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class HardwareKeyActionTest {
    @Test
    fun mapsVolumeAndLockKeycodesToSemanticActions() {
        assertEquals(HardwareKeyAction.VolumeUp, HardwareKeyAction.fromKeyCode(24))
        assertEquals(HardwareKeyAction.VolumeDown, HardwareKeyAction.fromKeyCode(25))
        assertEquals(HardwareKeyAction.ToggleMute, HardwareKeyAction.fromKeyCode(164))
        assertEquals(HardwareKeyAction.LockScreen, HardwareKeyAction.fromKeyCode(26))
    }

    @Test
    fun unsupportedKeycodesDoNotMapToHardwareActions() {
        assertNull(HardwareKeyAction.fromKeyCode(4))
        assertNull(HardwareKeyAction.fromKeyCode(187))
        assertNull(HardwareKeyAction.fromKeyCode(99))
    }
}
