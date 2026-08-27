package com.example.galaxymirror

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationKeysTest {

    @Test
    fun mainImplementsNavKey() {
        val navKey: Any = Main
        assertTrue(navKey is NavKey)
    }

    @Test
    fun mainDataObjectProperties() {
        assertEquals("Main", Main.toString())
        assertEquals(Main, Main)
        assertNotNull(Main.hashCode())
    }
}
