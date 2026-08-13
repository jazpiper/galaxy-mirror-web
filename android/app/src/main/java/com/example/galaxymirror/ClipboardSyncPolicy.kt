package com.example.galaxymirror

/**
 * 폰 → 뷰어 방향 클립보드 전송 여부를 결정한다.
 *
 * 이 프로젝트의 합의는 "뷰어는 화면에 보이는 것을 본다"이다. 클립보드 복사는 화면에 아무것도
 * 그리지 않으므로, 제어 채널이 열려 있다는 사실만으로 평문을 흘리면 그 합의를 넘어선다.
 * 특히 비밀번호 관리자에서 복사한 암호나 OTP가 여기 해당한다.
 */
object ClipboardSyncPolicy {
    fun shouldSendOutbound(
        channelOpen: Boolean,
        mirroringActive: Boolean,
        overlayShowing: Boolean,
        clipMarkedSensitive: Boolean,
        isEchoOfInjectedText: Boolean,
    ): Boolean {
        // 채널이 열려 있어도 캡처가 끝난 뒤일 수 있다. DataChannel은 캡처 해제보다 오래 산다.
        if (!channelOpen || !mirroringActive) return false
        // 블랙 오버레이는 프라이버시 모드로 제시되는 기능이다. 화면을 가리면서 클립보드를
        // 계속 흘려보내면 없느니만 못하다.
        if (overlayShowing) return false
        // Android 13+ 비밀번호 관리자/OTP 필드가 세우는 플래그. "미리보기·로깅 금지" 신호다.
        if (clipMarkedSensitive) return false
        // 뷰어가 방금 주입한 텍스트가 되돌아오는 에코는 무시한다.
        if (isEchoOfInjectedText) return false
        return true
    }
}
