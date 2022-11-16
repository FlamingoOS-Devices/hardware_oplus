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

package com.flamingo.oplushw

import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Binder
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.util.Log
import android.view.KeyEvent

import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope

import com.android.internal.os.IDeviceKeyManager
import com.android.internal.os.IKeyHandler
import com.flamingo.oplushw.alertslider.AlertSliderController
import com.flamingo.oplushw.touch.GestureController
import com.flamingo.oplushw.touch.ScanCodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val TAG = "OplusHwManager/KeyHandler"
private const val DEVICE_KEY_MANAGER = "device_key_manager"

class KeyHandler : LifecycleService() {

    private val inputManager by lazy { getSystemService(InputManager::class.java) }
    private val alertSliderController by lazy { AlertSliderController(this) }
    private val gestureController by lazy { GestureController(this) }

    private val alertSliderToken = Binder()
    private val alertSliderKeyHandler = object : IKeyHandler.Stub() {
        override fun handleKeyEvent(keyEvent: KeyEvent) {
            lifecycleScope.launch {
                alertSliderEventChannel.send(Unit)
            }
        }
    }
    private val alertSliderEventChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    private val gestureToken = Binder()
    private val gestureKeyHandler = object : IKeyHandler.Stub() {
        override fun handleKeyEvent(keyEvent: KeyEvent) {
            lifecycleScope.launch {
                gestureEventChannel.send(keyEvent.scanCode)
            }
        }
    }
    private val gestureEventChannel = Channel<Int>(capacity = Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch(Dispatchers.Default) {
            registerKeyHandlers()
            gestureController.enableGestures()
        }
    }

    private fun getDeviceKeyManager(): IDeviceKeyManager? {
        val service = ServiceManager.getService(DEVICE_KEY_MANAGER) ?: run {
            Log.wtf(TAG, "Device key manager service not found")
            return null
        }
        return IDeviceKeyManager.Stub.asInterface(service)
    }

    private fun registerKeyHandlers() {
        try {
            val deviceId = inputManager.inputDeviceIds.find {
                val name = inputManager.getInputDevice(it).name
                name == "oplus,hall_tri_state_key" || name == "oplus,tri-state-key"
            } ?: run {
                Log.e(TAG, "Failed to find tri state device")
                null
            }
            val km = getDeviceKeyManager() ?: run {
                stopSelf()
                return
            }
            if (deviceId != null) {
                Log.i(TAG, "Registering alertslider keyhandler")
                km.registerKeyHandler(
                    alertSliderToken,
                    alertSliderKeyHandler,
                    intArrayOf() /* scanCodes */,
                    intArrayOf(KeyEvent.ACTION_DOWN),
                    deviceId
                )
            }
            Log.i(TAG, "Registering gesture keyhandler")
            km.registerKeyHandler(
                gestureToken,
                gestureKeyHandler,
                ScanCodes,
                intArrayOf(KeyEvent.ACTION_UP),
                -1
            )
            handleKeyEvents()
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to register key handler", e)
            stopSelf()
        }
    }

    private fun unregisterKeyHandlers() {
        try {
            getDeviceKeyManager()?.let {
                it.unregisterKeyHandler(alertSliderToken)
                it.unregisterKeyHandler(gestureToken)
            }
        } catch(e: RemoteException) {
            Log.e(TAG, "Failed to register key handler", e)
        }
    }

    private fun handleKeyEvents() {
        lifecycleScope.launch {
            for (event in alertSliderEventChannel) {
                alertSliderController.updateMode()
            }
        }
        lifecycleScope.launch(Dispatchers.Default) {
            for (scanCode in gestureEventChannel) {
                gestureController.handleKeyScanCode(scanCode)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        alertSliderController.updateConfiguration(newConfig)
    }

    override fun onDestroy() {
        alertSliderController.dispose()
        unregisterKeyHandlers()
        super.onDestroy()
    }
}