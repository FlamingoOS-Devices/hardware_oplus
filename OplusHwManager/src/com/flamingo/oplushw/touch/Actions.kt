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
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.NameNotFoundException

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes

import com.flamingo.oplushw.R

import kotlin.reflect.KClass

import org.json.JSONObject

private const val KEY_NAME = "name"
private const val KEY_VIBRATE = "vibrate"
private const val KEY_PACKAGE = "package"

sealed interface Action {

    var vibrate: Boolean

    fun getTitle(context: Context): String

    fun serialize(): Result<String> {
        return runCatching {
            JSONObject().apply {
                put(KEY_NAME, this@Action::class.NAME)
                put(KEY_VIBRATE, vibrate)
            }.toString()
        }
    }
}

private val camelCaseRegex = "(?<=[a-zA-Z])[A-Z]".toRegex()
val KClass<out Action>.NAME: String
	get() = camelCaseRegex.replace(simpleName!!) {
        "_${it.value}"
    }.lowercase()

object None : Action {
    override var vibrate: Boolean
        get() = false
        set(_) {
            // No-op
        }

    override fun getTitle(context: Context) =
        context.getString(R.string.touchscreen_gesture_action_do_nothing)
}

abstract class Shortcut(
    @StringRes val title: Int,
    @DrawableRes val icon: Int
) : Action {
    override fun getTitle(context: Context) = context.getString(title)
}
class Flashlight(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_flashlight,
    com.android.internal.R.drawable.ic_qs_flashlight
)
class Camera(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_camera,
    com.android.internal.R.drawable.ic_camera
)
class TogglePlayback(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_play_pause_music,
    R.drawable.ic_play_pause
)
class PreviousTrack(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_previous_track,
    R.drawable.ic_skip_previous
)
class NextTrack(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_next_track,
    R.drawable.ic_skip_next
)
class VolumeDown(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_volume_down,
    R.drawable.baseline_volume_down_24
)
class VolumeUp(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_volume_up,
    R.drawable.baseline_volume_up_24
)
class WakeUp(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_wakeup,
    R.drawable.ic_wake_up
)
class Pulse(override var vibrate: Boolean) : Shortcut(
    R.string.touchscreen_gesture_action_ambient_display,
    R.drawable.ic_wake_up
)

data class OpenApp(
    override var vibrate: Boolean,
    val packageName: String
) : Action {
    override fun getTitle(context: Context): String {
        val pm = context.packageManager
        val appName = try {
            pm.getApplicationInfo(packageName, ApplicationInfoFlags.of(0L)).loadLabel(pm).toString()
        } catch(_: NameNotFoundException) {
            packageName
        }
        return context.getString(R.string.touchscreen_gesture_action_open_app, appName)
    }

    override fun serialize(): Result<String> {
        return super.serialize().mapCatching {
            JSONObject(it).apply {
                put(KEY_PACKAGE, packageName)
            }.toString()
        }
    }
}

fun parseAction(json: String): Result<Action> =
    runCatching {
        val jsonObject = JSONObject(json)
        val isVibrationEnabled = jsonObject.optBoolean(KEY_VIBRATE, true)
        val name = jsonObject.getString(KEY_NAME)
        if (name == OpenApp::class.NAME) {
            OpenApp(isVibrationEnabled, jsonObject.getString(KEY_PACKAGE))
        } else {
            createShortcut(name, isVibrationEnabled)
        }
    }

fun createShortcut(name: String, isVibrationEnabled: Boolean): Shortcut =
    when (name) {
        Flashlight::class.NAME -> Flashlight(isVibrationEnabled)
        Camera::class.NAME -> Camera(isVibrationEnabled)
        TogglePlayback::class.NAME -> TogglePlayback(isVibrationEnabled)
        PreviousTrack::class.NAME -> PreviousTrack(isVibrationEnabled)
        NextTrack::class.NAME -> NextTrack(isVibrationEnabled)
        VolumeDown::class.NAME -> VolumeDown(isVibrationEnabled)
        VolumeUp::class.NAME -> VolumeUp(isVibrationEnabled)
        WakeUp::class.NAME -> WakeUp(isVibrationEnabled)
        Pulse::class.NAME -> Pulse(isVibrationEnabled)
        else -> throw IllegalArgumentException("Unknown action $name")
    }