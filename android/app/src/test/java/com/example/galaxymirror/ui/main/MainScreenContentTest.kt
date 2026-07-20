package com.example.galaxymirror.ui.main

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MainScreenContentTest {
    @Test
    fun viewerConnectionLinesShowTailscaleAndUsbOptions() {
        assertEquals(
            "Tailscale URL: http://<Android MagicDNS>:8080/?transport=tailscale",
            MainScreenContent.viewerTailscaleUrlLine(),
        )
        assertEquals(
            "USB URL: http://127.0.0.1:8080/?transport=usb",
            MainScreenContent.viewerUsbUrlLine(),
        )
        assertEquals(
            "Mac 터미널: adb forward tcp:8080 tcp:8080",
            MainScreenContent.viewerUsbForwardCommand,
        )
    }

    @Test
    fun contentExplainsSetupAndActionsInKorean() {
        val allText: List<String> =
            listOf(
                MainScreenContent.title,
                MainScreenContent.subtitle,
                MainScreenContent.viewerAddressHint,
                MainScreenContent.viewerTailscaleUrlLine(),
                MainScreenContent.viewerUsbForwardCommand,
                MainScreenContent.viewerUsbUrlLine(),
                MainScreenContent.viewerTransportHint,
                MainScreenContent.appInfoButtonLabel,
                MainScreenContent.accessibilityButtonLabel,
                MainScreenContent.accessibilityEnabledLabel,
                MainScreenContent.disconnectButtonLabel,
                MainScreenContent.screenAwakeSettingsTitle,
                MainScreenContent.keepScreenAwakeLabel,
                MainScreenContent.keepScreenAwakeDescription,
                MainScreenContent.brightnessMinimizeLabel,
                MainScreenContent.brightnessMinimizeDescription,
                MainScreenContent.writeSettingsButtonLabel,
                MainScreenContent.writeSettingsAllowedLabel,
                MainScreenContent.streamQualityTitle,
                MainScreenContent.streamQualityDescription,
                MainScreenContent.streamQualityAutoLabel,
                MainScreenContent.streamQualityDataSaverLabel,
                MainScreenContent.streamQualityStandardLabel,
                MainScreenContent.streamQualityHighLabel,
                MainScreenContent.favoriteAppsTitle,
                MainScreenContent.addFavoriteAppButtonLabel,
            ) + MainScreenContent.setupSteps + MainScreenContent.controlTips

        assertFalse(allText.any { it.contains("Hello", ignoreCase = true) })
        assertTrue(allText.any { it.contains("접근성") })
        assertTrue(allText.any { it.contains("설정") })
        assertTrue(allText.any { it.contains("연결 해제") })
        assertTrue(allText.any { it.contains("Mac") })
        assertTrue(allText.any { it.contains("8080") })
        assertFalse(allText.any { it.contains("토큰") })
        assertFalse(allText.any { it.contains("?token") })
        assertFalse(allText.any { it.contains("***") })
        assertTrue(allText.any { it.contains("애플리케이션") })
        assertTrue(allText.any { it.contains("제한된 설정") })
        assertTrue(allText.any { it.contains("허용") })
        assertTrue(allText.any { it.contains("자주") })
        assertTrue(allText.any { it.contains("앱 추가") })
        assertTrue(allText.any { it.contains("미러링 중 화면 켜짐 유지") })
        assertTrue(allText.any { it.contains("밝기 최소화") })
        assertTrue(allText.any { it.contains("시스템 설정 수정") })
        assertTrue(allText.any { it.contains("화질") })
        assertTrue(allText.any { it.contains("Wi-Fi") })
        assertTrue(allText.any { it.contains("고화질") })
        assertTrue(allText.any { it.contains("표준") })
    }
}
