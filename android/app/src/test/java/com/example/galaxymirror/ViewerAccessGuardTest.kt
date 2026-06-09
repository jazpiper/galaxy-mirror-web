package com.example.galaxymirror

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ViewerAccessGuardTest {
    @Test
    fun acceptsMatchingQueryToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = "abc123", headerToken = null, requestHost = "phone.ts.net"))
    }

    @Test
    fun acceptsMatchingHeaderToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = null, headerToken = "abc123", requestHost = "phone.ts.net"))
    }

    @Test
    fun acceptsMatchingHeaderTokenEvenWhenQueryTokenIsStale() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = "old-token", headerToken = "abc123", requestHost = "phone.ts.net"))
    }

    @Test
    fun acceptsLoopbackHostWithoutToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertTrue(guard.isAllowed(queryToken = null, headerToken = null, requestHost = "127.0.0.1"))
        assertTrue(guard.isAllowed(queryToken = null, headerToken = null, requestHost = "127.0.0.1:8080"))
        assertTrue(guard.isAllowed(queryToken = null, headerToken = null, requestHost = "localhost:8080"))
        assertTrue(guard.isAllowed(queryToken = null, headerToken = null, requestHost = "[::1]:8080"))
    }

    @Test
    fun rejectsMissingOrMismatchedToken() {
        val guard = ViewerAccessGuard(expectedToken = "abc123")

        assertFalse(guard.isAllowed(queryToken = null, headerToken = null, requestHost = "phone.ts.net"))
        assertFalse(guard.isAllowed(queryToken = "wrong", headerToken = null, requestHost = "phone.ts.net"))
        assertFalse(guard.isAllowed(queryToken = null, headerToken = "wrong", requestHost = "phone.ts.net"))
    }
}
