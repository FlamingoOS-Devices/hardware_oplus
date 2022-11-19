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

import android.app.ActivityManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreference

import com.android.internal.lineage.hardware.LineageHardwareManager
import com.android.internal.lineage.hardware.TouchscreenGesture
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.AppSwitchPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.flamingo.oplushw.R

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActionsActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val gesture = intent.getParcelableExtra(
                KEY_GESTURE,
                TouchscreenGesture::class.java
            ) ?: run {
                finish()
                return
            }
            setTitle(getGestureName(this, gesture))
            lifecycleScope.launch(Dispatchers.Default) {
                val hardwareManager = LineageHardwareManager.getInstance(this@ActionsActivity)
                val action = getSavedAction(this@ActionsActivity, gesture.settingKey, gesture.keycode)
                withContext(Dispatchers.Main) {
                    supportFragmentManager.commit {
                        setReorderingAllowed(true)
                        replace(
                            com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            ActionsFragment(gesture, hardwareManager, action),
                            null
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_GESTURE = "gesture"
    }
}

class ActionsFragment(
    private val gesture: TouchscreenGesture,
    private val hardwareManager: LineageHardwareManager,
    private var currentAction: Action,
) : PreferenceFragmentCompat() {

    private var selectedPref: SwitchPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.fragment_action_settings, rootKey)
        initMainSwitchPref()
        initHapticFeedbackPref()
        createShortcuts()
        createAppPrefs()
    }

    private fun initMainSwitchPref() {
        val pref = findPreference<MainSwitchPreference>(MAIN_SWITCH_PREF_KEY) ?: return
        pref.setChecked(currentAction != None)
        pref.addOnSwitchChangeListener { _, isChecked ->
            currentAction = if (isChecked) {
                getDefaultActionForScanCode(gesture.keycode)
            } else {
                None
            }
            saveCurrentAction()
        }
    }

    private fun initHapticFeedbackPref() {
        val pref = findPreference<SwitchPreference>(HAPTIC_FEEDBACK_PREF_KEY) ?: return
        pref.setChecked(currentAction.vibrate)
        pref.setOnPreferenceChangeListener { _, newValue ->
            if (currentAction is None) {
                return@setOnPreferenceChangeListener true
            }
            currentAction.vibrate = newValue as Boolean
            saveCurrentAction()
            true
        }
    }

    private fun createShortcuts() {
        val shortcutsGroup = findPreference<PreferenceGroup>(SHORTCUTS_GROUP_KEY) ?: return
        val context = requireContext()
        val activity = requireActivity()
        Shortcuts.map { actionKlass ->
            actionKlass.NAME
        }.associateWith { actionName ->
            createShortcut(actionName, currentAction.vibrate)
        }.map { (name, shortcut) ->
            SwitchPreference(context).apply {
                key = name
                title = shortcut.getTitle(context)
                icon = ContextCompat.getDrawable(activity, shortcut.icon)
                isPersistent = false
                isChecked = false
                setOnPreferenceChangeListener { pref, newValue ->
                    if (newValue == false) {
                        return@setOnPreferenceChangeListener false
                    }
                    // Make sure to update [shortcut] as [currentAction.vibrate]
                    // may have changed at this point
                    currentAction = shortcut.also {
                        it.vibrate = currentAction.vibrate
                    }
                    saveCurrentAction()
                    selectedPref?.isChecked = false
                    selectedPref = pref as SwitchPreference
                    true
                }
            }
        }.forEach { pref ->
            shortcutsGroup.addPreference(pref)
            pref.dependency = MAIN_SWITCH_PREF_KEY
            if (pref.key != currentAction::class.NAME) {
                return@forEach
            }
            selectedPref = pref.also {
                it.isChecked = true
            }
        }
    }

    private fun createAppPrefs() {
        val openAppsGroup = findPreference<PreferenceGroup>(OPEN_APPS_GROUP_KEY) ?: return
        val context = requireContext()
        val pm = context.packageManager
        lifecycleScope.launch {
            val appActionMap = withContext(Dispatchers.Default) {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                val currentUserId = ActivityManager.getCurrentUser()
                val currentUser = launcherApps.profiles.find {
                    it.identifier == currentUserId
                } ?: return@withContext emptyMap()
                launcherApps.getActivityList(null, currentUser).map {
                    it.applicationInfo
                }.sortedBy {
                    it.loadLabel(pm).toString()
                }.associateWith {
                    OpenApp(currentAction.vibrate, it.packageName)
                }
            }
            appActionMap.map { (appInfo, action) ->
                AppSwitchPreference(context).apply {
                    key = appInfo.packageName
                    title = appInfo.loadLabel(pm)
                    icon = appInfo.loadIcon(pm)
                    isPersistent = false
                    isChecked = false
                    setOnPreferenceChangeListener { pref, newValue ->
                        if (newValue == false) {
                            return@setOnPreferenceChangeListener false
                        }
                        // Make sure to update action as currentAction.vibrate
                        // may have changed at this point
                        currentAction = action.also {
                            it.vibrate = currentAction.vibrate
                        }
                        saveCurrentAction()
                        selectedPref?.isChecked = false
                        selectedPref = pref as SwitchPreference
                        true
                    }
                }
            }.forEach { pref ->
                openAppsGroup.addPreference(pref)
                pref.dependency = MAIN_SWITCH_PREF_KEY
                currentAction.let { action ->
                    if (action is OpenApp && action.packageName == pref.key) {
                        selectedPref = pref.also {
                            it.isChecked = true
                        }
                    }
                }
            }
        }
    }

    private fun saveCurrentAction() {
        currentAction.serialize()
            .onFailure {
                Log.e(TAG, "Failed to serialize action $currentAction")
            }
            .onSuccess {
                lifecycleScope.launch(Dispatchers.Default) {
                    Settings.Secure.putStringForUser(
                        requireContext().contentResolver,
                        gesture.settingKey,
                        it,
                        UserHandle.USER_CURRENT
                    )
                    hardwareManager.setTouchscreenGestureEnabled(gesture, currentAction != None)
                }
            }
    }

    companion object {
        private val TAG = ActionsFragment::class.simpleName!!

        private const val MAIN_SWITCH_PREF_KEY = "main_switch"
        private const val HAPTIC_FEEDBACK_PREF_KEY = "touchscreen_gesture_haptic_feedback"
        private const val SHORTCUTS_GROUP_KEY = "shortcuts_group"
        private const val OPEN_APPS_GROUP_KEY = "open_apps_group"

        private val Shortcuts = listOf(
            Flashlight::class,
            Camera::class,
            TogglePlayback::class,
            PreviousTrack::class,
            NextTrack::class,
            VolumeDown::class,
            VolumeUp::class,
            WakeUp::class,
            Pulse::class,
        )
    }
}