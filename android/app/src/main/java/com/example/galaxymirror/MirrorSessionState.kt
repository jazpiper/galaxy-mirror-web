package com.example.galaxymirror

data class MirrorSessionState(
    val activeSessionId: Int = 0,
    val pendingOfferSessionId: Int? = null,
    val requiresScreenCaptureReauthorization: Boolean = false,
) {
    val hasPendingOffer: Boolean = pendingOfferSessionId != null

    fun beginSession(sessionId: Int): MirrorSessionState =
        copy(
            activeSessionId = sessionId,
            pendingOfferSessionId = null,
            requiresScreenCaptureReauthorization = false,
        )

    fun endSession(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(activeSessionId = 0, pendingOfferSessionId = null)
        } else {
            this
        }

    fun queueOffer(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(pendingOfferSessionId = sessionId)
        } else {
            this
        }

    fun clearPendingOffer(): MirrorSessionState = copy(pendingOfferSessionId = null)

    fun projectionStopped(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(
                activeSessionId = 0,
                pendingOfferSessionId = null,
                requiresScreenCaptureReauthorization = true,
            )
        } else {
            this
        }

    fun isActive(sessionId: Int): Boolean = activeSessionId == sessionId
}
