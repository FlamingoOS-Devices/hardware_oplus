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
import android.util.Log

import androidx.annotation.StringRes

import com.android.internal.lineage.hardware.TouchscreenGesture
import com.flamingo.oplushw.R

enum class Gesture(
    val scanCode: Int,
    @StringRes val title: Int
) {
    DOUBLE_TAP(247, R.string.touchscreen_gesture_double_tap_title),
    DOWN_ARROW(248, R.string.touchscreen_gesture_down_arrow_title),
    UP_ARROW(249, R.string.touchscreen_gesture_up_arrow_title),
    RIGHT_ARROW(250, R.string.touchscreen_gesture_right_arrow_title),
    LEFT_ARROW(251, R.string.touchscreen_gesture_left_arrow_title),
    LETTER_O(252, R.string.touchscreen_gesture_letter_o_title),
    DOUBLE_SWIPE(253, R.string.touchscreen_gesture_double_swipe_title),
    RIGHT_SWIPE(254, R.string.touchscreen_gesture_right_swipe_title),
    LEFT_SWIPE(255, R.string.touchscreen_gesture_left_swipe_title),
    DOWN_SWIPE(256, R.string.touchscreen_gesture_down_swipe_title),
    UP_SWIPE(257, R.string.touchscreen_gesture_up_swipe_title),
    LETTER_M(258, R.string.touchscreen_gesture_letter_m_title),
    LETTER_W(259, R.string.touchscreen_gesture_letter_w_title),
    FINGERPRINT_DOWN(260, R.string.touchscreen_gesture_fingerprint_down_title),
    FINGERPRINT_UP(261, R.string.touchscreen_gesture_fingerprint_up_title),
    SINGLE_TAP(262, R.string.touchscreen_gesture_single_tap_title),
    HEART(263, R.string.touchscreen_gesture_heart_title),
    LETTER_S(264, R.string.touchscreen_gesture_letter_s_title),
}

val ScanCodes = Gesture.values().map { it.scanCode }.toIntArray()
val ScanCodeTitleMap = Gesture.values().associate { it.scanCode to it.title }

enum class Action(@StringRes val title: Int) {
    NONE(R.string.touchscreen_gesture_action_do_nothing),
    FLASHLIGHT(R.string.touchscreen_gesture_action_flashlight),
    CAMERA(R.string.touchscreen_gesture_action_camera),
    BROWSER(R.string.touchscreen_gesture_action_browser),
    DIALER(R.string.touchscreen_gesture_action_dialer),
    EMAIL(R.string.touchscreen_gesture_action_email),
    MESSAGES(R.string.touchscreen_gesture_action_messages),
    PLAY_PAUSE_MUSIC(R.string.touchscreen_gesture_action_play_pause_music),
    PREVIOUS_TRACK(R.string.touchscreen_gesture_action_previous_track),
    NEXT_TRACK(R.string.touchscreen_gesture_action_next_track),
    VOLUME_DOWN(R.string.touchscreen_gesture_action_volume_down),
    VOLUME_UP(R.string.touchscreen_gesture_action_volume_up),
    WAKEUP(R.string.touchscreen_gesture_action_wakeup),
    AMBIENT_DISPLAY(R.string.touchscreen_gesture_action_ambient_display)
}

fun getDefaultActionForScanCode(scanCode: Int): Action {
    return when(scanCode) {
        Gesture.DOUBLE_TAP.scanCode -> Action.WAKEUP
        Gesture.SINGLE_TAP.scanCode -> Action.AMBIENT_DISPLAY
        Gesture.DOUBLE_SWIPE.scanCode -> Action.PLAY_PAUSE_MUSIC
        Gesture.DOWN_ARROW.scanCode -> Action.FLASHLIGHT
        Gesture.LEFT_ARROW.scanCode -> Action.PREVIOUS_TRACK
        Gesture.RIGHT_ARROW.scanCode -> Action.NEXT_TRACK
        else -> Action.NONE
    }
}

private val ReplaceRegex = "\\s+".toRegex()

val TouchscreenGesture.settingKey: String
    get() = "ts_gesture_" + name.lowercase().replace(ReplaceRegex, "_")