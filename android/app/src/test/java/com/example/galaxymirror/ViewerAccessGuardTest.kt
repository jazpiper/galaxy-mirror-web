package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ViewerAccessGuardTest {
    @Test
    fun acceptsMatchingQueryToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = "abc123", headerToken = null))
    }

    @Test
    fun acceptsMatchingHeaderToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = null, headerToken = "abc123"))
    }

    @Test
    fun acceptsMatchingHeaderTokenEvenWhenQueryTokenIsStale() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = "old-token", headerToken = "abc123"))
    }

    @Test
    fun rejectsMissingOrMismatchedToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertFalse(guard.isAllowed(queryToken = null, headerToken = null))
        assertFalse(guard.isAllowed(queryToken = "wrong", headerToken = null))
        assertFalse(guard.isAllowed(queryToken = null, headerToken = "wrong"))
    }
}
