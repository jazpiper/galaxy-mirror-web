package com.example.galaxymirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSyncPolicyTest {

    private fun policy(
        channelOpen: Boolean = true,
        mirroringActive: Boolean = true,
        overlayShowing: Boolean = false,
        clipMarkedSensitive: Boolean = false,
        isEchoOfInjectedText: Boolean = false,
    ) = ClipboardSyncPolicy.shouldSendOutbound(
        channelOpen = channelOpen,
        mirroringActive = mirroringActive,
        overlayShowing = overlayShowing,
        clipMarkedSensitive = clipMarkedSensitive,
        isEchoOfInjectedText = isEchoOfInjectedText,
    )

    @Test
    fun sendsWhenSessionIsGenuinelyActive() {
        assertTrue(policy())
    }

    @Test
    fun doesNotSendWhenChannelClosed() {
        assertFalse(policy(channelOpen = false))
    }

    @Test
    fun doesNotSendWhenChannelOpenButMirroringStopped() {
        // DataChannel은 캡처 해제보다 오래 살 수 있다. OPEN만으로는 근거가 되지 않는다.
        assertFalse(policy(mirroringActive = false))
    }

    @Test
    fun doesNotSendWhileBlackOverlayIsShowing() {
        assertFalse(policy(overlayShowing = true))
    }

    @Test
    fun doesNotSendSensitiveClip() {
        // Android 13+ 비밀번호 관리자/OTP가 EXTRA_IS_SENSITIVE를 세운다.
        assertFalse(policy(clipMarkedSensitive = true))
    }

    @Test
    fun doesNotEchoTextTheViewerJustInjected() {
        assertFalse(policy(isEchoOfInjectedText = true))
    }
}
