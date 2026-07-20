package com.example.galaxymirror

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.galaxymirror.ui.main.MainScreen

@Composable
fun MainNavigation(
  accessibilityEnabled: Boolean,
  favoriteApps: List<FavoriteApp>,
  launchableApps: List<FavoriteApp>,
  screenAwakeSettings: ScreenAwakeSettings = ScreenAwakeSettings(),
  canWriteSystemSettings: Boolean = false,
  streamQualityMode: StreamQualityMode = StreamQualityMode.AUTO,
  streamQualityNetwork: StreamNetworkTransport = StreamNetworkTransport.OTHER,
  streamQualityProfile: StreamQualityProfile =
    StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER),
  isMirroringActive: Boolean = false,
  onAddFavoriteApp: (FavoriteApp) -> Unit,
  onRemoveFavoriteApp: (String) -> Unit,
  onScreenAwakeSettingsChange: (ScreenAwakeSettings) -> Unit = {},
  onStreamQualityModeChange: (StreamQualityMode) -> Unit = {},
  onOpenAppInfoSettings: () -> Unit,
  onOpenAccessibilitySettings: () -> Unit,
  onOpenWriteSettings: () -> Unit = {},
  onDisconnect: () -> Unit,
) {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            modifier = Modifier.safeDrawingPadding().padding(16.dp),
            accessibilityEnabled = accessibilityEnabled,
            favoriteApps = favoriteApps,
            launchableApps = launchableApps,
            screenAwakeSettings = screenAwakeSettings,
            canWriteSystemSettings = canWriteSystemSettings,
            streamQualityMode = streamQualityMode,
            streamQualityNetwork = streamQualityNetwork,
            streamQualityProfile = streamQualityProfile,
            isMirroringActive = isMirroringActive,
            onAddFavoriteApp = onAddFavoriteApp,
            onRemoveFavoriteApp = onRemoveFavoriteApp,
            onScreenAwakeSettingsChange = onScreenAwakeSettingsChange,
            onStreamQualityModeChange = onStreamQualityModeChange,
            onOpenAppInfoSettings = onOpenAppInfoSettings,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenWriteSettings = onOpenWriteSettings,
            onDisconnect = onDisconnect,
          )
        }
      },
  )
}
