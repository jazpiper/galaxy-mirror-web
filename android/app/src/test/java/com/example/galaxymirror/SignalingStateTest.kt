package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class SignalingStateTest {
    @Test
    fun projectionReadiness_distinguishesMissingStartingAndReady() {
        assertEquals(
            ProjectionReadiness.MISSING_PERMISSION,
            ProjectionReadiness.from(hasProjectionIntent = false, isServiceRunning = false)
        )
        assertEquals(
            ProjectionReadiness.MISSING_PERMISSION,
            ProjectionReadiness.from(hasProjectionIntent = false, isServiceRunning = true)
        )
        assertEquals(
            ProjectionReadiness.SERVICE_STARTING,
            ProjectionReadiness.from(hasProjectionIntent = true, isServiceRunning = false)
        )
        assertEquals(
            ProjectionReadiness.READY,
            ProjectionReadiness.from(hasProjectionIntent = true, isServiceRunning = true)
        )
    }

    @Test
    fun signalingDecision_queuesOfferUntilProjectionPermissionAndServiceAreReady() {
        assertEquals(
            SignalingDecision.QUEUE_AND_REQUEST_PERMISSION,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.MISSING_PERMISSION,
                activeSessionMatches = true
            )
        )
        assertEquals(
            SignalingDecision.QUEUE_AND_SEND_STATUS,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.SERVICE_STARTING,
                activeSessionMatches = true
            )
        )
    }

    @Test
    fun signalingDecision_startsNegotiationOnlyWhenReady() {
        assertEquals(
            SignalingDecision.START_NEGOTIATION,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.READY,
                activeSessionMatches = true
            )
        )
    }

    @Test
    fun signalingDecision_ignoresInactiveSessions() {
        assertEquals(
            SignalingDecision.IGNORE_INACTIVE,
            SignalingDecision.onOffer(
                readiness = ProjectionReadiness.READY,
                activeSessionMatches = false
            )
        )
    }

    @Test
    fun cleanupPolicy_stopsProjectionWhenSessionEndsToAvoidReusingConsentIntent() {
        assertEquals(true, CleanupPolicy.shouldStopProjection(CleanupReason.VIEWER_SOCKET_CLOSED))
        assertEquals(true, CleanupPolicy.shouldStopProjection(CleanupReason.VIEWER_REPLACED))
        assertEquals(true, CleanupPolicy.shouldStopProjection(CleanupReason.ACTIVITY_DESTROYED))
        assertEquals(true, CleanupPolicy.shouldStopProjection(CleanupReason.EXPLICIT_STOP))
    }
}
