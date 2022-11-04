/*
 * Copyright (C) 2021 The LineageOS Project
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

package com.flamingo.device.alertslider

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.media.AudioManager
import android.media.AudioSystem
import android.os.Looper
import android.os.UEventObserver
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_NO_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_OFF
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.KeyEvent

import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope

import com.android.internal.os.IDeviceKeyManager
import com.android.internal.os.IKeyHandler

import java.io.File
import java.lang.Thread
import java.util.concurrent.Executors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEVICE_KEY_MANAGER = "device_key_manager"

private val TAG = KeyHandler::class.simpleName!!

class KeyHandler : LifecycleService() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val alertSliderController by lazy { AlertSliderController(this) }
    private val inputManager by lazy { getSystemService(InputManager::class.java) }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != AudioManager.STREAM_MUTE_CHANGED_ACTION) {
                return
            }
            val stream = intent?.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
            val state = intent?.getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false)
            if (stream == AudioSystem.STREAM_MUSIC && state == false) {
                wasMuted = false
            }
        }
    }

    private val positionChangeChannel = Channel<AlertSliderPosition>(capacity = Channel.CONFLATED)

    private val eventChannel = Channel<KeyEvent>(capacity = Channel.CONFLATED)
    private val keyHandler = object : IKeyHandler.Stub() {
        override fun handleKeyEvent(keyEvent: KeyEvent) {
            lifecycleScope.launch {
                eventChannel.send(keyEvent)
            }
        }
    }

    private val executorService = Executors.newSingleThreadExecutor()
    private var wasMuted = false

    private var inputEventReceiver: InputEventReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            broadcastReceiver,
            IntentFilter(AudioManager.STREAM_MUTE_CHANGED_ACTION)
        )
        lifecycleScope.launch(Dispatchers.IO) {
            for (position in positionChangeChannel) {
                handlePosition(position)
            }
        }
        val alertSlider = inputManager.inputDeviceIds.map {
            inputManager.getInputDevice(it)
        }.find {
            it.name == "oplus,hall_tri_state_key"
        }?.let {
            Log.d(TAG, "device = $it")
        } ?: {
            Log.e(TAG, "input device not found")
        }
        registerKeyHandler()
    }

    /*private fun getDeviceKeyManager(): IDeviceKeyManager? {
        val service = ServiceManager.getService(DEVICE_KEY_MANAGER) ?: run {
            Log.wtf(TAG, "Device key manager service not found")
            return null
        }
        return IDeviceKeyManager.Stub.asInterface(service)
    }*/

    private fun registerKeyHandler() {
        val inputMonitor = inputManager.monitorGestureInput("alertslider", Display.DEFAULT_DISPLAY)
        inputEventReceiver = object : InputEventReceiver(
            inputMonitor.inputChannel,
            Looper.myLooper()
        ) {
            override fun onInputEvent(event: InputEvent) {
                handleInputEvent(event)
            }
        }
        /*try {
            getDeviceKeyManager()?.registerKeyHandler(keyHandler, (0..1000).toIntArray(), intArrayOf(KeyEvent.ACTION_DOWN))
            handleKeyEvents()
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to register key handler", e)
            stopSelf()
        }*/
    }

    /*private fun unregisterKeyHandler() {
        try {
            getDeviceKeyManager()?.unregisterKeyHandler(keyHandler)
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to register key handler", e)
        }
    }*/

    private suspend fun handleKeyEvents() {
        withContext(Dispatchers.IO) {
            for (event in eventChannel) {
                handleKeyEvent(event)
            }
        }
    }

    private fun handleKeyEvent(keyEvent: KeyEvent) {
        
    }

    private fun handleInputEvent(inputEvent: InputEvent) {
        Log.d(TAG, "handleInputEvent")
        if (inputEvent.device.name == "oplus,hall_tri_state_key") {
            Log.d(TAG, "alert slider detected")
        }
    }

    private suspend fun handlePosition(
        sliderPosition: AlertSliderPosition,
        vibrate: Boolean = true,
        showDialog: Boolean = true
    ) {
        val savedMode = Settings.System.getStringForUser(
            contentResolver,
            sliderPosition.modeKey,
            UserHandle.USER_CURRENT
        ) ?: sliderPosition.defaultMode.toString()
        val mode = try {
            Mode.valueOf(savedMode)
        } catch(_: IllegalArgumentException) {
            Log.e(TAG, "Unrecognised mode $savedMode")
            return
        }
        performSliderAction(mode, vibrate)
        if (showDialog) updateDialogAndShow(mode, sliderPosition)
    }

    private fun performSliderAction(mode: Mode, vibrate: Boolean) {
        val muteMedia = Settings.System.getIntForUser(
            contentResolver,
            MUTE_MEDIA_WITH_SILENT,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        executorService.submit {
            when (mode) {
                Mode.NORMAL -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                    setZenMode(ZEN_MODE_OFF)
                    if (vibrate) performHapticFeedback(HEAVY_CLICK_EFFECT)
                    if (muteMedia && wasMuted) {
                        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                Mode.PRIORITY -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                    setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                    if (vibrate) performHapticFeedback(HEAVY_CLICK_EFFECT)
                    if (muteMedia && wasMuted) {
                        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                Mode.VIBRATE -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_VIBRATE
                    setZenMode(ZEN_MODE_OFF)
                    if (vibrate) performHapticFeedback(DOUBLE_CLICK_EFFECT)
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
    }

    private fun setZenMode(zenMode: Int) {
        // Set zen mode
        notificationManager.setZenMode(zenMode, null, TAG)

        // Wait until zen mode change is committed
        while (notificationManager.getZenMode() != zenMode) {
            Thread.sleep(10)
        }
    }

    private fun performHapticFeedback(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(effect)
        }
    }

    private suspend fun updateDialogAndShow(mode: Mode, position: AlertSliderPosition) {
        withContext(Dispatchers.Main) {
            alertSliderController.updateDialog(mode)
            alertSliderController.showDialog(position)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        alertSliderController.updateConfiguration(newConfig)
    }

    override fun onDestroy() {
        inputEventReceiver?.dispose()
        //unregisterKeyHandler()
        unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeyHandler"

        // Vibration effects
        private val HEAVY_CLICK_EFFECT =
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        private val DOUBLE_CLICK_EFFECT =
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

        private const val MUTE_MEDIA_WITH_SILENT = "config_mute_media"
        
        // Paths
        private const val SYSFS_EXTCON = "/sys/devices/platform/soc/soc:tri_state_key/extcon"
    }
}