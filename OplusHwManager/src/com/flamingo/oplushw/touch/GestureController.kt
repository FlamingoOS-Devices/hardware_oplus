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
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.media.AudioManager
import android.media.session.MediaSessionLegacyHelper
import android.net.Uri
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

class GestureController(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)

    private val settingKeyMap = SparseArray<String>()

    private val gestureWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, GESTURE_WAKELOCK_TAG)

    suspend fun enableGestures() {
        withContext(Dispatchers.Default) {
            val lhm = LineageHardwareManager.getInstance(context)
            if (!lhm.isSupported(FEATURE_TOUCHSCREEN_GESTURES)) return@withContext
            lhm.touchscreenGestures.forEach { gesture: TouchscreenGesture ->
                settingKeyMap[gesture.keycode] = gesture.settingKey
                val action = getSavedAction(
                    gesture.settingKey,
                    getDefaultActionForScanCode(gesture.keycode)
                )
                lhm.setTouchscreenGestureEnabled(gesture, action != Action.NONE)
            }
        }
    }

    private fun getSavedAction(key: String, def: Action): Action {
        val actionString = Settings.System.getStringForUser(
            context.contentResolver,
            key,
            UserHandle.USER_CURRENT
        )?.takeIf { it.isNotBlank() } ?: return def
        return try {
            Action.valueOf(actionString)
        } catch(_: IllegalArgumentException) {
            Log.e(TAG, "Unknown gesture action $actionString")
            def
        }
    }

    fun handleKeyScanCode(scanCode: Int) {
        if (scanCode == Gesture.SINGLE_TAP.scanCode && !keyguardManager.isDeviceLocked) {
            // Wake up the device if not locked
            wakeUp()
            return
        }
        val key: String = settingKeyMap[scanCode] ?: return
        // Handle gestures
        val action = getSavedAction(key, getDefaultActionForScanCode(scanCode))
        try {
            if (!gestureWakeLock.isHeld) {
                gestureWakeLock.acquire(10 * 1000)
            }
            performAction(action)
        } finally {
            if (gestureWakeLock.isHeld) {
                gestureWakeLock.release()
            }
        }
    }

    private fun performAction(action: Action) {
        when (action) {
            Action.NONE -> return
            Action.CAMERA -> launchCamera()
            Action.FLASHLIGHT -> toggleFlashlight()
            Action.BROWSER -> launchBrowser()
            Action.DIALER -> launchDialer()
            Action.EMAIL -> launchEmail()
            Action.MESSAGES -> launchMessages()
            Action.PLAY_PAUSE_MUSIC -> playPauseMusic()
            Action.PREVIOUS_TRACK -> previousTrack()
            Action.NEXT_TRACK -> nextTrack()
            Action.VOLUME_DOWN -> volumeDown()
            Action.VOLUME_UP -> volumeUp()
            Action.WAKEUP -> wakeUp()
            Action.AMBIENT_DISPLAY -> launchDozePulse()
        }
        if (action != Action.AMBIENT_DISPLAY) {
            performHapticFeedback()
        }
    }

    private fun launchCamera() {
        wakeUp()
        context.sendBroadcastAsUser(
            Intent(Intent.ACTION_SCREEN_CAMERA_GESTURE),
            UserHandle.SYSTEM,
            Manifest.permission.STATUS_BAR_SERVICE
        )
    }

    private fun launchBrowser() {
        startActivitySafely(getLaunchableIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("http:"))))
    }

    private fun launchDialer() {
        startActivitySafely(Intent(Intent.ACTION_DIAL, null))
    }

    private fun launchEmail() {
        startActivitySafely(getLaunchableIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"))))
    }

    private fun launchMessages() {
        startActivitySafely(getLaunchableIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))))
    }

    private fun toggleFlashlight() {
        FlamingoUtils.toggleCameraFlash()
    }

    private fun playPauseMusic() {
        dispatchMediaKeyToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    private fun previousTrack() {
        dispatchMediaKeyToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun nextTrack() {
        dispatchMediaKeyToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    private fun volumeDown() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    }

    private fun volumeUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    }

    private fun wakeUp() {
        powerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE, GESTURE_WAKEUP_REASON)
    }

    private fun launchDozePulse() {
        val dozeEnabled = Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.DOZE_ENABLED,
            1,
            UserHandle.USER_CURRENT
        ) == 1
        if (dozeEnabled) {
            context.sendBroadcastAsUser(Intent(PULSE_ACTION), UserHandle.SYSTEM)
        }
    }

    private fun dispatchMediaKeyToMediaSession(keycode: Int) {
        val helper = MediaSessionLegacyHelper.getHelper(context) ?: run {
            Log.w(TAG, "Unable to send media key event")
            return
        }
        val event = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, keycode, 0)
        helper.sendMediaButtonEvent(event, true)
        helper.sendMediaButtonEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), true)
    }

    private fun startActivitySafely(intent: Intent?) {
        if (intent == null) {
            Log.w(TAG, "No intent passed to startActivitySafely")
            return
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        try {
            context.startActivityAsUser(intent, null, UserHandle.SYSTEM)
            wakeUp()
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Activity not found to launch")
        }
    }

    private fun getLaunchableIntent(intent: Intent): Intent? {
        val resInfo = context.packageManager.queryIntentActivities(
            intent,
            ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
        )
        if (resInfo.isEmpty()) {
            return null
        }
        return context.packageManager.getLaunchIntentForPackage(resInfo.first().activityInfo.packageName)
    }

    private fun performHapticFeedback() {
        val hapticFeedbackEnabled = Settings.System.getIntForUser(
            context.contentResolver,
            TOUCHSCREEN_GESTURE_HAPTIC_FEEDBACK,
            1,
            UserHandle.USER_CURRENT
        ) == 1
        if (hapticFeedbackEnabled && audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            performHapticFeedback(HEAVY_CLICK_EFFECT)
        }
    }

    private fun performHapticFeedback(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(effect)
        }
    }

    companion object {
        private const val TAG = "GestureController"

        private const val PULSE_ACTION = "com.android.systemui.doze.pulse"

        private const val GESTURE_WAKEUP_REASON = "touchscreen-gesture-wakeup"
        private const val GESTURE_REQUEST = 1
        private const val GESTURE_WAKELOCK_TAG = "$TAG:GestureWakeLock"

        private const val TOUCHSCREEN_GESTURE_HAPTIC_FEEDBACK = "touchscreen_gesture_haptic_feedback"

        private val HEAVY_CLICK_EFFECT = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }
}
