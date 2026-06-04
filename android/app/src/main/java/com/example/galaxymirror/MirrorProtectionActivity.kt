package com.example.galaxymirror

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MirrorProtectionActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(
      WindowManager.LayoutParams.FLAG_SECURE or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    )

    setContent {
      MirrorProtectionScreen(onDismiss = ::finish)
    }
  }

  override fun onDestroy() {
    window.clearFlags(
      WindowManager.LayoutParams.FLAG_SECURE or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    )
    super.onDestroy()
  }

  companion object {
    fun createIntent(context: Context): Intent = Intent(context, MirrorProtectionActivity::class.java)
  }
}

@Composable
private fun MirrorProtectionScreen(onDismiss: () -> Unit) {
  BackHandler(onBack = onDismiss)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .clickable(onClick = onDismiss)
      .padding(horizontal = 24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = MirrorProtectionContent.title,
      color = Color.White,
      fontSize = 32.sp,
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center
    )
    Text(
      modifier = Modifier.padding(top = 16.dp),
      text = MirrorProtectionContent.dismissHint,
      color = Color(0xFFBDBDBD),
      fontSize = 16.sp,
      textAlign = TextAlign.Center,
      lineHeight = 22.sp
    )
  }
}
