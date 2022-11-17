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

package com.flamingo.oplushw.touch

import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings

import com.android.internal.lineage.hardware.TouchscreenGesture
import com.android.settingslib.PrimarySwitchPreference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GesturePreference(
    context: Context,
    private val gesture: TouchscreenGesture,
    private val coroutineScope: CoroutineScope
) : PrimarySwitchPreference(context) {

    private val settingsObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            updateSwitchAndSummary()
        }
    }

    init {
        title = getGestureName(context, gesture)
        updateSwitchAndSummary()
    }

    private fun updateSwitchAndSummary() {
        coroutineScope.launch {
            val action = withContext(Dispatchers.Default) {
                getSavedAction(context, gesture.settingKey, gesture.keycode)
            }
            summary = action.getTitle(context)
            isChecked = action != None
        }
    }

    override fun onAttached() {
        super.onAttached()
        coroutineScope.launch(Dispatchers.Default) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(gesture.settingKey),
                false,
                settingsObserver,
                UserHandle.USER_CURRENT
            )
        }
    }

    override fun onDetached() {
        coroutineScope.launch(Dispatchers.Default) {
            context.contentResolver.unregisterContentObserver(settingsObserver)
        }
        super.onDetached()
    }
}