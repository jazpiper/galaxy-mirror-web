package com.example.galaxymirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerOriginGuardTest {

    @Test
    fun missingOriginIsAllowedForNonBrowserClients() {
        // curl, adb 스모크 테스트, 주소창 직접 입력은 Origin을 보내지 않는다.
        assertTrue(ViewerOriginGuard.isAllowed(null, "127.0.0.1:8080"))
    }

    @Test
    fun sameOriginLoopbackIsAllowed() {
        assertTrue(ViewerOriginGuard.isAllowed("http://127.0.0.1:8080", "127.0.0.1:8080"))
    }

    @Test
    fun sameOriginMagicDnsHostIsAllowed() {
        // 고정 allowlist가 아니라 Host 대조이므로 MagicDNS 호스트명이 무엇이든 통과한다.
        assertTrue(ViewerOriginGuard.isAllowed("http://galaxy-s24:8080", "galaxy-s24:8080"))
    }

    @Test
    fun crossOriginAttackerPageIsRejected() {
        assertFalse(ViewerOriginGuard.isAllowed("http://evil.example", "127.0.0.1:8080"))
    }

    @Test
    fun dnsRebindingKeepsAttackerOriginAndIsRejected() {
        // 리바인딩으로 Host가 폰을 가리켜도 Origin은 공격자 도메인으로 남는다.
        assertFalse(ViewerOriginGuard.isAllowed("http://rebind.example", "galaxy-s24:8080"))
    }

    @Test
    fun differentPortOnSameHostIsRejected() {
        assertFalse(ViewerOriginGuard.isAllowed("http://127.0.0.1:9999", "127.0.0.1:8080"))
    }

    @Test
    fun opaqueNullOriginIsRejected() {
        // 샌드박스 iframe이나 file:// 문서는 Origin이 "null"이다.
        assertFalse(ViewerOriginGuard.isAllowed("null", "127.0.0.1:8080"))
    }

    @Test
    fun originPresentButHostMissingIsRejected() {
        assertFalse(ViewerOriginGuard.isAllowed("http://127.0.0.1:8080", null))
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertTrue(ViewerOriginGuard.isAllowed("http://Galaxy-S24:8080", "galaxy-s24:8080"))
    }
}
