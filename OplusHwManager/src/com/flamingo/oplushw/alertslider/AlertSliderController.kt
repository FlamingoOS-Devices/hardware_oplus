/*
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

package com.flamingo.oplushw.alertslider

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.media.AudioSystem
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_NO_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_OFF
import android.util.Log

import java.io.File

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AlertSliderController(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val stream = intent?.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
            val state = intent?.getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false)
            if (stream == AudioSystem.STREAM_MUSIC && state == false) {
                wasMuted = false
            }
        }
    }

    private var wasMuted = false

    init {
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter(AudioManager.STREAM_MUTE_CHANGED_ACTION)
        )
    }

    private val dialog = AlertSliderDialog(context)
    private val dismissDialogRunnable = Runnable { dialog.dismiss() }

    suspend fun updateMode() {
        withContext(Dispatchers.IO) {
            runCatching {
                when (PROC_FILE.readText().trim()) {
                    "1" -> handlePosition(AlertSliderPosition.Top)
                    "2" -> handlePosition(AlertSliderPosition.Middle)
                    "3" -> handlePosition(AlertSliderPosition.Bottom)
                }
            }.onFailure {
                Log.e(TAG, "Failed to read proc file", it)
            }
        }
    }

    fun updateConfiguration(newConfig: Configuration) {
        removeHandlerCalbacks()
        dialog.updateConfiguration(newConfig)
    }

    fun dispose() {
        context.unregisterReceiver(broadcastReceiver)
    }

    private suspend fun handlePosition(sliderPosition: AlertSliderPosition) {
        val savedMode = Settings.System.getStringForUser(
            context.contentResolver,
            sliderPosition.modeKey,
            UserHandle.USER_CURRENT
        ) ?: sliderPosition.defaultMode.toString()
        val mode = try {
            Mode.valueOf(savedMode)
        } catch(_: IllegalArgumentException) {
            Log.e(TAG, "Unrecognised mode $savedMode")
            return
        }
        performSliderAction(mode)
        withContext(Dispatchers.Main) {
            updateDialog(mode)
            showDialog(sliderPosition)
        }
    }

    private suspend fun performSliderAction(mode: Mode) {
        val muteMedia = Settings.System.getIntForUser(
            context.contentResolver,
            MUTE_MEDIA_WITH_SILENT,
            0,
            UserHandle.USER_CURRENT
        ) == 1
        when (mode) {
            Mode.NORMAL -> {
                audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                setZenMode(ZEN_MODE_OFF)
                performHapticFeedback(HEAVY_CLICK_EFFECT)
                if (muteMedia && wasMuted) {
                    audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                }
            }
            Mode.PRIORITY -> {
                audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                performHapticFeedback(HEAVY_CLICK_EFFECT)
                if (muteMedia && wasMuted) {
                    audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                }
            }
            Mode.VIBRATE -> {
                audioManager.ringerModeInternal = AudioManager.RINGER_MODE_VIBRATE
                setZenMode(ZEN_MODE_OFF)
                performHapticFeedback(DOUBLE_CLICK_EFFECT)
                if (muteMedia && wasMuted) {
                    audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                }
            }
            Mode.SILENT -> {
                audioManager.ringerModeInternal = AudioManager.RINGER_MODE_SILENT
                setZenMode(ZEN_MODE_OFF)
                if (muteMedia) {
                    audioManager.adjustVolume(AudioManager.ADJUST_MUTE, 0)
                    wasMuted = true
                }
            }
            Mode.DND -> {
                audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                setZenMode(ZEN_MODE_NO_INTERRUPTIONS)
            }
        }
    }

    private suspend fun setZenMode(zenMode: Int) {
        // Set zen mode
        notificationManager.setZenMode(zenMode, null, TAG)

        // Wait until zen mode change is committed
        while (notificationManager.zenMode != zenMode) {
            delay(10)
        }
    }

    private fun performHapticFeedback(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }

    private fun updateDialog(mode: Mode) {
        dialog.setIconAndLabel(mode.icon, mode.title)
    }

    private fun showDialog(position: AlertSliderPosition) {
        removeHandlerCalbacks()
        if (powerManager.isInteractive) {
            dialog.show(position)
            handler.postDelayed(dismissDialogRunnable, TIMEOUT)
        }
    }

    private fun removeHandlerCalbacks() {
        if (handler.hasCallbacks(dismissDialogRunnable)) {
            handler.removeCallbacks(dismissDialogRunnable)
        }
    }

    companion object {
        private const val TAG = "AlertSliderController"

        // Vibration effects
        private val HEAVY_CLICK_EFFECT =
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        private val DOUBLE_CLICK_EFFECT =
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

        private const val MUTE_MEDIA_WITH_SILENT = "config_mute_media"

        private const val TIMEOUT = 1000L

        private val PROC_FILE = File("/proc/tristatekey/tri_state")
    }
}
