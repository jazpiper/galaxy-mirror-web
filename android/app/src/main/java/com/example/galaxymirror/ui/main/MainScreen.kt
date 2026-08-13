package com.example.galaxymirror.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.galaxymirror.FavoriteApp

import com.example.galaxymirror.ScreenAwakeSettings
import com.example.galaxymirror.StreamNetworkTransport
import com.example.galaxymirror.StreamQualityMode
import com.example.galaxymirror.StreamQualityPolicy
import com.example.galaxymirror.StreamQualityProfile
import com.example.galaxymirror.data.DefaultDataRepository
import com.example.galaxymirror.theme.GalaxyMirrorTheme

data class InfoPanelItem(val text: String, val copyText: String? = null)

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  accessibilityEnabled: Boolean = false,
  favoriteApps: List<FavoriteApp> = emptyList(),
  launchableApps: List<FavoriteApp> = emptyList(),
  screenAwakeSettings: ScreenAwakeSettings = ScreenAwakeSettings(),
  canWriteSystemSettings: Boolean = false,
  streamQualityMode: StreamQualityMode = StreamQualityMode.AUTO,
  streamQualityNetwork: StreamNetworkTransport = StreamNetworkTransport.OTHER,
  streamQualityProfile: StreamQualityProfile =
    StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER),
  isMirroringActive: Boolean = false,
  blackOverlayEnabled: Boolean = false,
  overlayPermissionReady: Boolean = false,
  onAddFavoriteApp: (FavoriteApp) -> Unit = {},
  onRemoveFavoriteApp: (String) -> Unit = {},
  onScreenAwakeSettingsChange: (ScreenAwakeSettings) -> Unit = {},
  onStreamQualityModeChange: (StreamQualityMode) -> Unit = {},
  onToggleBlackOverlay: (Boolean) -> Unit = {},
  onOpenAppInfoSettings: () -> Unit = {},
  onOpenAccessibilitySettings: () -> Unit = {},
  onOpenWriteSettings: () -> Unit = {},
  onOpenOverlaySettings: () -> Unit = {},
  onDisconnect: () -> Unit = {},
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  when (state) {
    MainScreenUiState.Loading ->
      MirrorHomeScreen(
        modifier = modifier,
        accessibilityEnabled = accessibilityEnabled,
        favoriteApps = favoriteApps,
        launchableApps = launchableApps,
        screenAwakeSettings = screenAwakeSettings,
        canWriteSystemSettings = canWriteSystemSettings,
        streamQualityMode = streamQualityMode,
        streamQualityNetwork = streamQualityNetwork,
        streamQualityProfile = streamQualityProfile,
        isMirroringActive = isMirroringActive,
        blackOverlayEnabled = blackOverlayEnabled,
        overlayPermissionReady = overlayPermissionReady,
        onAddFavoriteApp = onAddFavoriteApp,
        onRemoveFavoriteApp = onRemoveFavoriteApp,
        onScreenAwakeSettingsChange = onScreenAwakeSettingsChange,
        onStreamQualityModeChange = onStreamQualityModeChange,
        onToggleBlackOverlay = onToggleBlackOverlay,
        onOpenAppInfoSettings = onOpenAppInfoSettings,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onOpenWriteSettings = onOpenWriteSettings,
        onOpenOverlaySettings = onOpenOverlaySettings,
        onDisconnect = onDisconnect,
      )
    is MainScreenUiState.Success ->
      MirrorHomeScreen(
        modifier = modifier,
        accessibilityEnabled = accessibilityEnabled,
        favoriteApps = favoriteApps,
        launchableApps = launchableApps,
        screenAwakeSettings = screenAwakeSettings,
        canWriteSystemSettings = canWriteSystemSettings,
        streamQualityMode = streamQualityMode,
        streamQualityNetwork = streamQualityNetwork,
        streamQualityProfile = streamQualityProfile,
        isMirroringActive = isMirroringActive,
        blackOverlayEnabled = blackOverlayEnabled,
        overlayPermissionReady = overlayPermissionReady,
        onAddFavoriteApp = onAddFavoriteApp,
        onRemoveFavoriteApp = onRemoveFavoriteApp,
        onScreenAwakeSettingsChange = onScreenAwakeSettingsChange,
        onStreamQualityModeChange = onStreamQualityModeChange,
        onToggleBlackOverlay = onToggleBlackOverlay,
        onOpenAppInfoSettings = onOpenAppInfoSettings,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onOpenWriteSettings = onOpenWriteSettings,
        onOpenOverlaySettings = onOpenOverlaySettings,
        onDisconnect = onDisconnect,
      )
    is MainScreenUiState.Error ->
      MirrorHomeScreen(
        modifier = modifier,
        accessibilityEnabled = accessibilityEnabled,
        favoriteApps = favoriteApps,
        launchableApps = launchableApps,
        screenAwakeSettings = screenAwakeSettings,
        canWriteSystemSettings = canWriteSystemSettings,
        streamQualityMode = streamQualityMode,
        streamQualityNetwork = streamQualityNetwork,
        streamQualityProfile = streamQualityProfile,
        isMirroringActive = isMirroringActive,
        blackOverlayEnabled = blackOverlayEnabled,
        overlayPermissionReady = overlayPermissionReady,
        onAddFavoriteApp = onAddFavoriteApp,
        onRemoveFavoriteApp = onRemoveFavoriteApp,
        onScreenAwakeSettingsChange = onScreenAwakeSettingsChange,
        onStreamQualityModeChange = onStreamQualityModeChange,
        onToggleBlackOverlay = onToggleBlackOverlay,
        onOpenAppInfoSettings = onOpenAppInfoSettings,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onOpenWriteSettings = onOpenWriteSettings,
        onOpenOverlaySettings = onOpenOverlaySettings,
        onDisconnect = onDisconnect,
        warning = "상태를 불러오지 못했습니다: ${(state as MainScreenUiState.Error).throwable.message.orEmpty()}",
      )
  }
}

@Composable
internal fun MirrorHomeScreen(
  modifier: Modifier = Modifier,
  accessibilityEnabled: Boolean = false,
  favoriteApps: List<FavoriteApp> = emptyList(),
  launchableApps: List<FavoriteApp> = emptyList(),
  screenAwakeSettings: ScreenAwakeSettings = ScreenAwakeSettings(),
  canWriteSystemSettings: Boolean = false,
  streamQualityMode: StreamQualityMode = StreamQualityMode.AUTO,
  streamQualityNetwork: StreamNetworkTransport = StreamNetworkTransport.OTHER,
  streamQualityProfile: StreamQualityProfile =
    StreamQualityPolicy.resolve(StreamQualityMode.AUTO, StreamNetworkTransport.OTHER),
  isMirroringActive: Boolean = false,
  blackOverlayEnabled: Boolean = false,
  overlayPermissionReady: Boolean = false,
  onAddFavoriteApp: (FavoriteApp) -> Unit = {},
  onRemoveFavoriteApp: (String) -> Unit = {},
  onScreenAwakeSettingsChange: (ScreenAwakeSettings) -> Unit = {},
  onStreamQualityModeChange: (StreamQualityMode) -> Unit = {},
  onToggleBlackOverlay: (Boolean) -> Unit = {},
  onOpenAppInfoSettings: () -> Unit = {},
  onOpenAccessibilitySettings: () -> Unit = {},
  onOpenWriteSettings: () -> Unit = {},
  onOpenOverlaySettings: () -> Unit = {},
  onDisconnect: () -> Unit = {},
  warning: String? = null,
) {
  val (showAppPicker, setShowAppPicker) = rememberSaveable { mutableStateOf(false) }
  // Memoize across recompositions; these only change when the app lists change, not on every
  // serviceState-driven recomposition.
  val favoritePackages = remember(favoriteApps) { favoriteApps.map { it.packageName }.toSet() }
  val selectableApps =
    remember(launchableApps, favoritePackages) {
      launchableApps.filterNot { it.packageName in favoritePackages }
    }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = MainScreenContent.title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = MainScreenContent.subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    warning?.let {
      InfoPanel(title = "알림", items = listOf(InfoPanelItem(it)))
    }

    InfoPanel(
      title = "Mac 연결 주소",
      items =
        listOf(
          InfoPanelItem(MainScreenContent.viewerAddressHint),
          InfoPanelItem(
            text = MainScreenContent.viewerTailscaleUrlLine(),
            copyText = "http://<Android MagicDNS>:8080/?transport=tailscale"
          ),
          InfoPanelItem(
            text = MainScreenContent.viewerUsbForwardCommand,
            copyText = "adb forward tcp:8080 tcp:8080"
          ),
          InfoPanelItem(
            text = MainScreenContent.viewerUsbUrlLine(),
            copyText = "http://127.0.0.1:8080/?transport=usb"
          ),
          InfoPanelItem(MainScreenContent.viewerTransportHint),
          InfoPanelItem("앱이 켜져 있는 동안 Android 내장 서버가 8080 포트에서 대기합니다."),
        ),
    )

    InfoPanel(title = "처음 설정", items = remember { MainScreenContent.setupSteps.map { InfoPanelItem(it) } })
    InfoPanel(title = "조작 방법", items = remember { MainScreenContent.controlTips.map { InfoPanelItem(it) } })
    ScreenAwakeSettingsPanel(
      settings = screenAwakeSettings,
      canWriteSystemSettings = canWriteSystemSettings,
      blackOverlayEnabled = blackOverlayEnabled,
      overlayPermissionReady = overlayPermissionReady,
      onSettingsChange = onScreenAwakeSettingsChange,
      onToggleBlackOverlay = onToggleBlackOverlay,
      onOpenWriteSettings = onOpenWriteSettings,
      onOpenOverlaySettings = onOpenOverlaySettings,
    )
    StreamQualityPanel(
      selectedMode = streamQualityMode,
      networkTransport = streamQualityNetwork,
      profile = streamQualityProfile,
      onModeChange = onStreamQualityModeChange,
    )
    FavoriteAppsPanel(
      favoriteApps = favoriteApps,
      onAddClick = { setShowAppPicker(true) },
      onRemoveClick = onRemoveFavoriteApp,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedButton(
        onClick = onOpenAppInfoSettings,
        modifier = Modifier.fillMaxWidth(),
        enabled = !accessibilityEnabled,
      ) {
        Text(MainScreenContent.appInfoButtonLabel)
      }
      Button(
        onClick = onOpenAccessibilitySettings,
        modifier = Modifier.fillMaxWidth(),
        enabled = !accessibilityEnabled,
      ) {
        Text(
          if (accessibilityEnabled) {
            MainScreenContent.accessibilityEnabledLabel
          } else {
            MainScreenContent.accessibilityButtonLabel
          }
        )
      }
      OutlinedButton(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth(),
        enabled = isMirroringActive,
      ) {
        Text(MainScreenContent.disconnectButtonLabel)
      }
    }
  }

  if (showAppPicker) {
    FavoriteAppPickerDialog(
      launchableApps = selectableApps,
      onDismiss = { setShowAppPicker(false) },
      onSelect = { app ->
        setShowAppPicker(false)
        onAddFavoriteApp(app)
      },
    )
  }
}

@Composable
private fun ScreenAwakeSettingsPanel(
  settings: ScreenAwakeSettings,
  canWriteSystemSettings: Boolean,
  blackOverlayEnabled: Boolean = false,
  overlayPermissionReady: Boolean = false,
  onSettingsChange: (ScreenAwakeSettings) -> Unit,
  onToggleBlackOverlay: (Boolean) -> Unit = {},
  onOpenWriteSettings: () -> Unit,
  onOpenOverlaySettings: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = MainScreenContent.screenAwakeSettingsTitle,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      SettingsSwitchRow(
        title = MainScreenContent.keepScreenAwakeLabel,
        description = MainScreenContent.keepScreenAwakeDescription,
        checked = settings.keepScreenAwakeDuringMirroring,
        onCheckedChange = {
          onSettingsChange(settings.copy(keepScreenAwakeDuringMirroring = it))
        },
      )
      SettingsSwitchRow(
        title = MainScreenContent.brightnessMinimizeLabel,
        description = MainScreenContent.brightnessMinimizeDescription,
        checked = settings.minimizeBrightnessDuringMirroring,
        onCheckedChange = {
          onSettingsChange(settings.copy(minimizeBrightnessDuringMirroring = it))
        },
      )
      SettingsSwitchRow(
        title = "📱 블랙 오버레이 차단 모드",
        description = "미러링 시 실물 화면을 검은색으로 차단하여 OLED 발열 및 픽셀 전력을 차단합니다. (화면 터치 시 즉시 해제)",
        checked = blackOverlayEnabled,
        onCheckedChange = onToggleBlackOverlay,
      )
      Text(
        text = MainScreenContent.writeSettingsRequiredHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
          onClick = onOpenWriteSettings,
          modifier = Modifier.weight(1f),
          enabled = !canWriteSystemSettings,
        ) {
          Text(
            if (canWriteSystemSettings) {
              MainScreenContent.writeSettingsAllowedLabel
            } else {
              MainScreenContent.writeSettingsButtonLabel
            },
          )
        }
        OutlinedButton(
          onClick = onOpenOverlaySettings,
          modifier = Modifier.weight(1f),
          enabled = !overlayPermissionReady,
        ) {
          Text(
            if (overlayPermissionReady) {
              "그리기 권한 허용됨"
            } else {
              "다른 앱 위에 그리기 권한"
            },
          )
        }
      }
    }
  }
}

@Composable
private fun StreamQualityPanel(
  selectedMode: StreamQualityMode,
  networkTransport: StreamNetworkTransport,
  profile: StreamQualityProfile,
  onModeChange: (StreamQualityMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = MainScreenContent.streamQualityTitle,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = MainScreenContent.streamQualityDescription,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text =
          "현재 ${profile.label}: ${profile.width}x${profile.height} ${profile.fps}fps, " +
            "${profile.maxBitrateBps / 1_000_000.0}Mbps 상한 · 네트워크 ${networkTransport.koreanLabel}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          StreamQualityButton(
            label = MainScreenContent.streamQualityAutoLabel,
            selected = selectedMode == StreamQualityMode.AUTO,
            onClick = { onModeChange(StreamQualityMode.AUTO) },
            modifier = Modifier.weight(1f),
          )
          StreamQualityButton(
            label = MainScreenContent.streamQualityDataSaverLabel,
            selected = selectedMode == StreamQualityMode.DATA_SAVER,
            onClick = { onModeChange(StreamQualityMode.DATA_SAVER) },
            modifier = Modifier.weight(1f),
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          StreamQualityButton(
            label = MainScreenContent.streamQualityStandardLabel,
            selected = selectedMode == StreamQualityMode.STANDARD,
            onClick = { onModeChange(StreamQualityMode.STANDARD) },
            modifier = Modifier.weight(1f),
          )
          StreamQualityButton(
            label = MainScreenContent.streamQualityHighLabel,
            selected = selectedMode == StreamQualityMode.HIGH,
            onClick = { onModeChange(StreamQualityMode.HIGH) },
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun StreamQualityButton(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (selected) {
    Button(onClick = onClick, modifier = modifier) {
      Text(label)
    }
  } else {
    OutlinedButton(onClick = onClick, modifier = modifier) {
      Text(label)
    }
  }
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun InfoPanel(title: String, items: List<InfoPanelItem>, modifier: Modifier = Modifier) {
  val clipboardManager = LocalClipboardManager.current
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      items.forEachIndexed { index, item ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "${index + 1}.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
            )
            Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
          }
          if (item.copyText != null) {
            IconButton(onClick = { clipboardManager.setText(AnnotatedString(item.copyText)) }) {
              Icon(Icons.Outlined.ContentCopy, contentDescription = "복사")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FavoriteAppsPanel(
  favoriteApps: List<FavoriteApp>,
  onAddClick: () -> Unit,
  onRemoveClick: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        text = MainScreenContent.favoriteAppsTitle,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      if (favoriteApps.isEmpty()) {
        Text(
          text = MainScreenContent.emptyFavoriteAppsLabel,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        favoriteApps.forEach { app ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = app.label,
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onRemoveClick(app.packageName) }) {
              Text(MainScreenContent.removeFavoriteAppButtonLabel)
            }
          }
        }
      }
      OutlinedButton(onClick = onAddClick, modifier = Modifier.fillMaxWidth()) {
        Text(MainScreenContent.addFavoriteAppButtonLabel)
      }
    }
  }
}

@Composable
private fun FavoriteAppPickerDialog(
  launchableApps: List<FavoriteApp>,
  onDismiss: () -> Unit,
  onSelect: (FavoriteApp) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(MainScreenContent.appPickerTitle) },
    text = {
      if (launchableApps.isEmpty()) {
        Text(MainScreenContent.emptyFavoriteAppsLabel)
      } else {
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
          items(launchableApps, key = { it.packageName }) { app ->
            TextButton(onClick = { onSelect(app) }, modifier = Modifier.fillMaxWidth()) {
              Text(app.label, modifier = Modifier.fillMaxWidth())
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("닫기")
      }
    },
  )
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  GalaxyMirrorTheme { MirrorHomeScreen(modifier = Modifier.padding(16.dp)) }
}

@Preview(showBackground = true, widthDp = 340)
@Composable
fun MainScreenPortraitPreview() {
  GalaxyMirrorTheme { MirrorHomeScreen(modifier = Modifier.padding(16.dp)) }
}
