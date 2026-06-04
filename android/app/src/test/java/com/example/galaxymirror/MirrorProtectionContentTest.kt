package com.example.galaxymirror

import junit.framework.TestCase.assertEquals
import org.junit.Test

class MirrorProtectionContentTest {
    @Test
    fun copy_matchesProtectedMirroringScreenText() {
        assertEquals("미러링 중", MirrorProtectionContent.title)
        assertEquals(
            "화면을 터치하면 보호 화면을 닫습니다.",
            MirrorProtectionContent.dismissHint
        )
    }
}
