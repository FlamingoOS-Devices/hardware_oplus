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
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat

import com.android.internal.lineage.hardware.LineageHardwareManager
import com.android.internal.lineage.hardware.LineageHardwareManager.FEATURE_TOUCHSCREEN_GESTURES
import com.android.internal.lineage.hardware.TouchscreenGesture
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.flamingo.oplushw.R

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    SettingsFragment(),
                    null
                )
            }
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        lifecycleScope.launch(Dispatchers.Default) {
            val context = requireContext()
            val hardwareManager = LineageHardwareManager.getInstance(context)
            if (!hardwareManager.isSupported(FEATURE_TOUCHSCREEN_GESTURES)) {
                return@launch
            }
            val gestures = hardwareManager.touchscreenGestures
            withContext(Dispatchers.Main) {
                setPreferencesFromResource(R.xml.fragment_touch_settings, rootKey)
                gestures.map {
                    createPreference(it, context, hardwareManager)
                }.forEach {
                    preferenceScreen.addPreference(it)
                }
            }
        }
    }

    private fun createPreference(
        gesture: TouchscreenGesture,
        context: Context,
        hardwareManager: LineageHardwareManager
    ) = GesturePreference(context, gesture, lifecycleScope).apply {
            setOnPreferenceClickListener {
                val intent = Intent(context, ActionsActivity::class.java)
                    .putExtra(ActionsActivity.KEY_GESTURE, gesture)
                context.startActivityAsUser(intent, UserHandle.SYSTEM)
                true
            }
            setOnPreferenceChangeListener { _, newValue ->
                val action = if (newValue == true) {
                    getDefaultActionForScanCode(gesture.keycode)
                } else {
                    None
                }
                action.serialize()
                    .onFailure {
                        Log.e(TAG, "Failed to serialize action $action")
                    }
                    .onSuccess {
                        lifecycleScope.launch(Dispatchers.Default) {
                            Settings.Secure.putStringForUser(
                                context.contentResolver,
                                gesture.settingKey,
                                it,
                                UserHandle.USER_CURRENT
                            )
                            hardwareManager.setTouchscreenGestureEnabled(gesture, action != None)
                        }
                    }
                true
            }
        }

    companion object {
        private val TAG = SettingsFragment::class.simpleName!!
    }
}
