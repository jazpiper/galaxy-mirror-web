package com.example.galaxymirror.ui.main

object MainScreenContent {
    const val title = "Android Mirror"
    const val subtitle = "Android 화면을 Mac 브라우저로 보고, 터치와 키보드를 원격 입력합니다."
    fun viewerAddressHint(token: String): String =
        if (token.isBlank()) {
            "Mac Chrome에서 Android 앱에 표시되는 토큰 포함 주소로 접속하세요."
        } else {
            "Mac Chrome 주소창에 아래 토큰 포함 URL을 입력하세요."
        }

    fun viewerTokenLine(token: String): String =
        if (token.isBlank()) {
            "접속 토큰: 생성 중"
        } else {
            "접속 토큰: $token"
        }

    fun viewerTailscaleUrlLine(token: String): String =
        if (token.isBlank()) {
            "Tailscale URL: http://<Android MagicDNS>:8080/?token=<접속 토큰>&transport=tailscale"
        } else {
            "Tailscale URL: http://<Android MagicDNS>:8080/?token=$token&transport=tailscale"
        }

    fun viewerUsbUrlLine(token: String): String =
        if (token.isBlank()) {
            "USB URL: http://127.0.0.1:8080/?token=<접속 토큰>&transport=usb"
        } else {
            "USB URL: http://127.0.0.1:8080/?token=$token&transport=usb"
        }

    fun viewerUrlLine(token: String): String = viewerTailscaleUrlLine(token)

    const val viewerUsbForwardCommand = "Mac 터미널: adb forward tcp:8080 tcp:8080"
    const val viewerTransportHint = "Tailscale은 무선/원격 연결, USB는 adb forward가 켜진 Mac 직접 연결에 사용합니다."
    const val viewerTokenHint = "토큰은 Tailscale 내부망에서도 오접속으로 인한 원격 제어를 막는 로컬 보호 장치입니다."
    const val appInfoButtonLabel = "앱 정보 열기"
    const val accessibilityButtonLabel = "접근성 설정 열기"
    const val accessibilityEnabledLabel = "접근성 입력 활성화됨"
    const val disconnectButtonLabel = "미러링 연결 해제"
    const val screenAwakeSettingsTitle = "미러링 화면 설정"
    const val keepScreenAwakeLabel = "미러링 중 화면 켜짐 유지"
    const val keepScreenAwakeDescription = "연결 중에는 Android 화면이 자동으로 꺼지지 않게 준비합니다."
    const val brightnessMinimizeLabel = "밝기 최소화 모드"
    const val brightnessMinimizeDescription = "미러링 연결 중 Android 밝기를 최저로 낮추고, 연결 해제 시 이전 밝기로 복원합니다."
    const val writeSettingsButtonLabel = "밝기 권한 설정 열기"
    const val writeSettingsAllowedLabel = "밝기 권한 허용됨"
    const val writeSettingsRequiredHint = "밝기 최소화는 Android의 시스템 설정 수정 권한이 필요합니다."
    const val streamQualityTitle = "스트림 화질"
    const val streamQualityDescription = "자동 모드는 Wi-Fi에서 고화질, 4G/5G 모바일 데이터에서 표준 화질로 시작합니다."
    const val streamQualityAutoLabel = "자동"
    const val streamQualityDataSaverLabel = "저데이터"
    const val streamQualityStandardLabel = "표준"
    const val streamQualityHighLabel = "고화질"
    const val favoriteAppsTitle = "자주 쓰는 앱"
    const val addFavoriteAppButtonLabel = "앱 추가"
    const val removeFavoriteAppButtonLabel = "삭제"
    const val emptyFavoriteAppsLabel = "아직 추가된 앱이 없습니다."
    const val appPickerTitle = "추가할 앱 선택"

    val setupSteps =
        listOf(
            "앱 실행 직후 화면 공유 권한 팝업이 뜨면 전체 화면 공유를 허용합니다.",
            "Mac Chrome에서 Android 앱에 표시된 token 포함 8080 포트 주소로 접속하고 미러링 연결하기를 누릅니다.",
            "처음 설치했다면 Android 설정 > 애플리케이션 > Android Mirror > 우측 상단 메뉴 > 제한된 설정 허용을 먼저 켭니다.",
            "아래 앱 정보 열기 버튼으로 Android Mirror 앱 정보 화면에 바로 이동할 수 있습니다.",
            "원격 터치와 키보드 입력이 필요하면 접근성 설정 > 설치된 앱에서 Android Mirror 서비스를 켭니다.",
            "밝기 최소화 모드를 쓰려면 시스템 설정 수정 권한 화면에서 Android Mirror 허용을 켭니다.",
            "Android 입력창을 한 번 터치한 뒤 Mac 브라우저의 미러 화면을 클릭하고 키보드로 입력합니다.",
        )

    val controlTips =
        listOf(
            "클릭은 Android 탭, 드래그는 스와이프로 전달됩니다.",
            "일반 문자, Enter, Backspace는 현재 Android 입력창에 전달됩니다.",
            "Escape는 Android 뒤로가기, Home은 홈, F1은 최근 앱으로 동작합니다.",
        )
}
