package com.example.galaxymirror.ui.main

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import com.example.galaxymirror.FavoriteApp
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.galaxymirror.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun setupInstructionsAndActions_exist() {
    composeTestRule.setContent { MirrorHomeScreen() }

    composeTestRule.onNodeWithText(MainScreenContent.title).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.viewerAddressHint).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.appInfoButtonLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.accessibilityButtonLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.disconnectButtonLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.screenAwakeSettingsTitle).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.keepScreenAwakeLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.brightnessMinimizeLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.writeSettingsButtonLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.streamQualityTitle).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.streamQualityAutoLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.streamQualityHighLabel).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.favoriteAppsTitle).assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.addFavoriteAppButtonLabel).assertExists()
  }

  @Test
  fun setupButtonsAreDisabledWhenAccessibilityIsEnabled() {
    composeTestRule.setContent { MirrorHomeScreen(accessibilityEnabled = true) }

    composeTestRule.onNodeWithText(MainScreenContent.appInfoButtonLabel).assertIsNotEnabled()
    composeTestRule.onNodeWithText(MainScreenContent.accessibilityEnabledLabel).assertIsNotEnabled()
  }

  @Test
  fun disconnectButtonIsEnabledOnlyWhenMirroringIsActive() {
    composeTestRule.setContent { MirrorHomeScreen(isMirroringActive = false) }

    composeTestRule.onNodeWithText(MainScreenContent.disconnectButtonLabel).assertIsNotEnabled()

    composeTestRule.setContent { MirrorHomeScreen(isMirroringActive = true) }

    composeTestRule.onNodeWithText(MainScreenContent.disconnectButtonLabel).assertIsEnabled()
  }

  @Test
  fun favoriteAppsAreShownWithDeleteAction() {
    composeTestRule.setContent {
      MirrorHomeScreen(
        favoriteApps = listOf(FavoriteApp("com.chat", "Chat")),
        launchableApps = listOf(FavoriteApp("com.mail", "Mail")),
      )
    }

    composeTestRule.onNodeWithText("Chat").assertExists()
    composeTestRule.onNodeWithText(MainScreenContent.removeFavoriteAppButtonLabel).assertExists()
  }
}
