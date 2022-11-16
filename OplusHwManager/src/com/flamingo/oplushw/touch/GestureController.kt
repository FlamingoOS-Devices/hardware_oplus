/**
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flamingo.oplushw.touch

import android.Manifest
import android.app.AppLockManager
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.display.AmbientDisplayConfiguration
import android.media.AudioManager
import android.media.session.MediaSessionLegacyHelper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.util.SparseArray
import android.view.KeyEvent

import com.android.internal.R
import com.android.internal.lineage.hardware.LineageHardwareManager
import com.android.internal.lineage.hardware.LineageHardwareManager.FEATURE_TOUCHSCREEN_GESTURES
import com.android.internal.lineage.hardware.TouchscreenGesture
import com.android.internal.util.flamingo.FlamingoUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PULSE_ACTION = "com.android.systemui.doze.pulse"
private const val GESTURE_WAKEUP_REASON = "touchscreen-gesture-wakeup"
private const val GESTURE_REQUEST = 1

private val TAG = GestureController::class.simpleName!!
private val GESTURE_WAKELOCK_TAG = "$TAG:GestureWakeLock"
private val HEAVY_CLICK_EFFECT = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

class GestureController(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val appLockManager = context.getSystemService(AppLockManager::class.java)

    private val settingKeyMap = SparseArray<String>()

    private val gestureWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, GESTURE_WAKELOCK_TAG)

    private val ambientDisplayConfig = AmbientDisplayConfiguration(context)

    suspend fun enableGestures() {
        withContext(Dispatchers.Default) {
            val lhm = LineageHardwareManager.getInstance(context)
            if (!lhm.isSupported(FEATURE_TOUCHSCREEN_GESTURES)) return@withContext
            var isDt2wEnabled = false
            lhm.touchscreenGestures.forEach { gesture: TouchscreenGesture ->
                settingKeyMap[gesture.keycode] = gesture.settingKey
                val action = getSavedAction(context, gesture.settingKey, gesture.keycode)
                lhm.setTouchscreenGestureEnabled(gesture, action != None)
                if (gesture.isDoubleTapGesture && action is WakeUp) {
                    isDt2wEnabled = true
                }
            }
            Settings.Secure.putIntForUser(
                context.contentResolver,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
                if (isDt2wEnabled) 1 else 0,
                UserHandle.USER_CURRENT
            )
        }
    }

    suspend fun handleKeyScanCode(scanCode: Int) {
        if (scanCode == Gesture.SINGLE_TAP.scanCode && !keyguardManager.isDeviceLocked) {
            // Wake up the device if not locked
            wakeUp()
            return
        }
        val key: String = settingKeyMap[scanCode] ?: return
        // Handle gestures
        withContext(Dispatchers.Default) {
            try {
                if (!gestureWakeLock.isHeld) {
                    gestureWakeLock.acquire(10 * 1000)
                }
                performAction(getSavedAction(context, key, scanCode))
            } finally {
                if (gestureWakeLock.isHeld) {
                    gestureWakeLock.release()
                }
            }
        }
    }

    private fun performAction(action: Action) {
        val success = when (action) {
            is None -> return
            is Flashlight -> toggleFlashlight()
            is Camera -> launchCamera()
            is TogglePlayback -> togglePlayback()
            is PreviousTrack -> previousTrack()
            is NextTrack -> nextTrack()
            is VolumeDown -> adjustVolume(false)
            is VolumeUp -> adjustVolume(true)
            is WakeUp -> wakeUp()
            is Pulse -> pulse()
            is OpenApp -> openApp(action.packageName)
            else -> throw IllegalArgumentException("Unknown action $action")
        }
        if (success && action.vibrate) {
            performHapticFeedback()
        }
    }

    private fun launchCamera(): Boolean {
        wakeUp()
        context.sendBroadcastAsUser(
            Intent(Intent.ACTION_SCREEN_CAMERA_GESTURE),
            UserHandle.SYSTEM,
            Manifest.permission.STATUS_BAR_SERVICE
        )
        return true
    }

    private fun toggleFlashlight(): Boolean {
        FlamingoUtils.toggleCameraFlash()
        return true
    }

    private fun togglePlayback() =
        dispatchMediaKeyToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    private fun previousTrack() =
        dispatchMediaKeyToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun nextTrack() =
        dispatchMediaKeyToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT)

    private fun adjustVolume(raise: Boolean): Boolean {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
        return true
    }

    private fun wakeUp(): Boolean {
        powerManager.wakeUp(
            SystemClock.uptimeMillis(),
            PowerManager.WAKE_REASON_GESTURE,
            GESTURE_WAKEUP_REASON
        )
        return true
    }

    private fun pulse(): Boolean {
        if (!ambientDisplayConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) {
            return false
        }
        context.sendBroadcastAsUser(Intent(PULSE_ACTION), UserHandle.SYSTEM)
        return true
    }

    private fun openApp(packageName: String): Boolean {
        if (appLockManager.hiddenPackages.contains(packageName)) {
            return false
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "Failed to find launch intent for package $packageName")
            return false
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        try {
            context.startActivityAsUser(launchIntent, null, UserHandle.SYSTEM)
            wakeUp()
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Activity not found to launch in $packageName")
            return false
        }
        return true
    }

    private fun dispatchMediaKeyToMediaSession(keycode: Int): Boolean {
        val helper = MediaSessionLegacyHelper.getHelper(context) ?: run {
            Log.w(TAG, "Unable to send media key event")
            return false
        }
        val event = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, keycode, 0)
        helper.sendMediaButtonEvent(event, true)
        helper.sendMediaButtonEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), true)
        return true
    }

    private fun performHapticFeedback() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(HEAVY_CLICK_EFFECT)
        }
    }
}
