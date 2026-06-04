package com.example.galaxymirror

enum class ProjectionReadiness {
    MISSING_PERMISSION,
    SERVICE_STARTING,
    READY;

    companion object {
        fun from(hasProjectionIntent: Boolean, isServiceRunning: Boolean): ProjectionReadiness {
            return when {
                !hasProjectionIntent -> MISSING_PERMISSION
                isServiceRunning -> READY
                else -> SERVICE_STARTING
            }
        }
    }
}

enum class SignalingDecision {
    START_NEGOTIATION,
    QUEUE_AND_REQUEST_PERMISSION,
    QUEUE_AND_SEND_STATUS,
    IGNORE_INACTIVE;

    companion object {
        fun onOffer(
            readiness: ProjectionReadiness,
            activeSessionMatches: Boolean
        ): SignalingDecision {
            if (!activeSessionMatches) return IGNORE_INACTIVE
            return when (readiness) {
                ProjectionReadiness.READY -> START_NEGOTIATION
                ProjectionReadiness.SERVICE_STARTING -> QUEUE_AND_SEND_STATUS
                ProjectionReadiness.MISSING_PERMISSION -> QUEUE_AND_REQUEST_PERMISSION
            }
        }
    }
}

enum class CleanupReason {
    VIEWER_SOCKET_CLOSED,
    VIEWER_REPLACED,
    ACTIVITY_DESTROYED,
    EXPLICIT_STOP
}

object CleanupPolicy {
    fun shouldStopProjection(reason: CleanupReason): Boolean {
        return when (reason) {
            CleanupReason.VIEWER_SOCKET_CLOSED,
            CleanupReason.VIEWER_REPLACED,
            CleanupReason.ACTIVITY_DESTROYED,
            CleanupReason.EXPLICIT_STOP -> true
        }
    }
}
