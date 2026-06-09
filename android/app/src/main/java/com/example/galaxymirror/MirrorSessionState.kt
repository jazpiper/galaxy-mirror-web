package com.example.galaxymirror

data class MirrorSessionState(
    val activeSessionId: Int = 0,
    val activeTransport: MirrorTransport? = null,
    val pendingOfferSessionId: Int? = null,
    val requiresScreenCaptureReauthorization: Boolean = false,
) {
    val hasPendingOffer: Boolean = pendingOfferSessionId != null

    fun beginSession(
        sessionId: Int,
        transport: MirrorTransport = MirrorTransport.TAILSCALE_WEBRTC,
    ): MirrorSessionState =
        copy(
            activeSessionId = sessionId,
            activeTransport = transport,
            pendingOfferSessionId = null,
            requiresScreenCaptureReauthorization = false,
        )

    fun endSession(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(activeSessionId = 0, activeTransport = null, pendingOfferSessionId = null)
        } else {
            this
        }

    fun queueOffer(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId && activeTransport == MirrorTransport.TAILSCALE_WEBRTC) {
            copy(pendingOfferSessionId = sessionId)
        } else {
            this
        }

    fun clearPendingOffer(): MirrorSessionState = copy(pendingOfferSessionId = null)

    fun projectionStopped(sessionId: Int): MirrorSessionState =
        if (activeSessionId == sessionId) {
            copy(
                activeSessionId = 0,
                activeTransport = null,
                pendingOfferSessionId = null,
                requiresScreenCaptureReauthorization = true,
            )
        } else {
            this
        }

    fun isActive(sessionId: Int): Boolean = activeSessionId == sessionId

    fun isActive(sessionId: Int, transport: MirrorTransport): Boolean =
        activeSessionId == sessionId && activeTransport == transport
}
