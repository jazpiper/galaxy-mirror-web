package com.example.galaxymirror

object MediaProjectionWakeLockPolicy {
  fun shouldHoldWakeLock(serviceRunning: Boolean, keepAwakeEnabled: Boolean): Boolean {
    return serviceRunning && keepAwakeEnabled
  }
}
