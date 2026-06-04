package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MirrorSessionStateTest {
    @Test
    fun beginSessionReplacesPreviousSessionAndClearsPendingOffer() {
        val state =
            MirrorSessionState()
                .beginSession(1)
                .queueOffer(1)
                .beginSession(2)

        assertEquals(2, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
        assertFalse(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun endActiveSessionClearsSessionState() {
        val state =
            MirrorSessionState()
                .beginSession(7)
                .queueOffer(7)
                .endSession(7)

        assertEquals(0, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
    }

    @Test
    fun endingInactiveSessionDoesNotClearActiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(7)
                .endSession(6)

        assertEquals(7, state.activeSessionId)
    }

    @Test
    fun projectionStoppedRequiresReauthorizationAndClearsPendingOffer() {
        val state =
            MirrorSessionState()
                .beginSession(3)
                .queueOffer(3)
                .projectionStopped(3)

        assertEquals(0, state.activeSessionId)
        assertFalse(state.hasPendingOffer)
        assertTrue(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun projectionStoppedForReplacedSessionDoesNotClearNewActiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(1)
                .beginSession(2)
                .projectionStopped(1)

        assertEquals(2, state.activeSessionId)
        assertFalse(state.requiresScreenCaptureReauthorization)
    }

    @Test
    fun queueOfferIgnoresInactiveSession() {
        val state =
            MirrorSessionState()
                .beginSession(3)
                .queueOffer(4)

        assertNull(state.pendingOfferSessionId)
        assertFalse(state.hasPendingOffer)
    }
}
