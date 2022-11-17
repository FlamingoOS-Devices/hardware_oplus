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
import android.os.UserHandle
import android.provider.Settings

import com.android.internal.lineage.hardware.TouchscreenGesture

private val ScanCodeTitleMap = Gesture.values().associate { it.scanCode to it.title }
private val ReplaceRegex = "\\s+".toRegex()

val TouchscreenGesture.settingKey: String
    get() = "ts_gesture_" + name.lowercase().replace(ReplaceRegex, "_")

fun getDefaultActionForScanCode(scanCode: Int): Action {
    return when(scanCode) {
        Gesture.DOUBLE_TAP.scanCode -> WakeUp(false)
        Gesture.SINGLE_TAP.scanCode -> Pulse(false)
        Gesture.DOUBLE_SWIPE.scanCode -> TogglePlayback(true)
        Gesture.DOWN_ARROW.scanCode -> Flashlight(true)
        Gesture.LEFT_ARROW.scanCode -> PreviousTrack(true)
        Gesture.RIGHT_ARROW.scanCode -> NextTrack(true)
        else -> None
    }
}

fun getSavedAction(context: Context, key: String, scanCode: Int): Action {
    val def = getDefaultActionForScanCode(scanCode)
    val json = Settings.Secure.getStringForUser(
        context.contentResolver,
        key,
        UserHandle.USER_CURRENT
    )?.takeIf { it.isNotBlank() } ?: return def
    return parseAction(json).getOrDefault(def)
}

fun getGestureName(context: Context, gesture: TouchscreenGesture): CharSequence =
    ScanCodeTitleMap[gesture.keycode]?.let {
        context.getString(it)
    } ?: gesture.name