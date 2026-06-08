package com.example.galaxymirror

sealed class HardwareKeyAction {
    data object VolumeUp : HardwareKeyAction()
    data object VolumeDown : HardwareKeyAction()
    data object ToggleMute : HardwareKeyAction()
    data object LockScreen : HardwareKeyAction()

    companion object {
        fun fromKeyCode(keyCode: Int): HardwareKeyAction? =
            when (keyCode) {
                24 -> VolumeUp
                25 -> VolumeDown
                164 -> ToggleMute
                26 -> LockScreen
                else -> null
            }
    }
}
