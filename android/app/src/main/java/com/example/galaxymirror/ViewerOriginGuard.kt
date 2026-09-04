package com.example.galaxymirror

/**
 * 브라우저를 경유한 크로스 오리진 접근(CSWSH / CSRF)만 차단한다.
 *
 * 이것은 뷰어 토큰 인증이 아니다. 의도적으로 제거된 `ViewerAccessGuard`/`ViewerAccessTokenStore`를
 * 되살리는 것이 아니며, 사용자나 클라이언트가 교환해야 할 비밀값이 전혀 없다. 신뢰 경계는 여전히
 * loopback + WireGuard 터널이고, 여기서 막는 것은 "그 경계 밖의 웹페이지가 사용자의 브라우저를
 * 대리인으로 삼아 서버에 말을 거는" 경로 하나뿐이다.
 *
 * 판정 규칙:
 * - Origin 헤더가 없으면 거부한다.
 * - Origin이 있으면 Host와 authority(호스트:포트)가 정확히 일치해야 한다. 고정 allowlist 대신
 *   Host 대조를 쓰는 이유는 Tailscale MagicDNS 호스트명이 환경마다 달라지기 때문이다.
 *   DNS 리바인딩도 Origin이 공격자 도메인으로 남으므로 이 검사에 걸린다.
 */
object ViewerOriginGuard {
    fun isAllowed(originHeader: String?, hostHeader: String?): Boolean {
        val origin = originHeader ?: return false
        val host = hostHeader ?: return false
        val originAuthority = origin.substringAfter("://", "")
        // "null" origin(샌드박스 iframe, file://)은 authority가 비어 거부된다.
        if (originAuthority.isEmpty()) return false
        return originAuthority.equals(host, ignoreCase = true)
    }
}
