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

import androidx.annotation.StringRes

import com.flamingo.oplushw.R

private const val SCANCODE_START = 246

enum class Gesture(
    val scanCode: Int,
    @StringRes val title: Int
) {
    DOUBLE_TAP(SCANCODE_START + 1, R.string.touchscreen_gesture_double_tap_title),
    DOWN_ARROW(SCANCODE_START + 2, R.string.touchscreen_gesture_down_arrow_title),
    UP_ARROW(SCANCODE_START + 3, R.string.touchscreen_gesture_up_arrow_title),
    RIGHT_ARROW(SCANCODE_START + 4, R.string.touchscreen_gesture_right_arrow_title),
    LEFT_ARROW(SCANCODE_START + 5, R.string.touchscreen_gesture_left_arrow_title),
    LETTER_O(SCANCODE_START + 6, R.string.touchscreen_gesture_letter_o_title),
    DOUBLE_SWIPE(SCANCODE_START + 7, R.string.touchscreen_gesture_double_swipe_title),
    RIGHT_SWIPE(SCANCODE_START + 8, R.string.touchscreen_gesture_right_swipe_title),
    LEFT_SWIPE(SCANCODE_START + 9, R.string.touchscreen_gesture_left_swipe_title),
    DOWN_SWIPE(SCANCODE_START + 10, R.string.touchscreen_gesture_down_swipe_title),
    UP_SWIPE(SCANCODE_START + 11, R.string.touchscreen_gesture_up_swipe_title),
    LETTER_M(SCANCODE_START + 12, R.string.touchscreen_gesture_letter_m_title),
    LETTER_W(SCANCODE_START + 13, R.string.touchscreen_gesture_letter_w_title),
    FINGERPRINT_DOWN(SCANCODE_START + 14, R.string.touchscreen_gesture_fingerprint_down_title),
    FINGERPRINT_UP(SCANCODE_START + 15, R.string.touchscreen_gesture_fingerprint_up_title),
    SINGLE_TAP(SCANCODE_START + 16, R.string.touchscreen_gesture_single_tap_title),
    HEART(SCANCODE_START + 17, R.string.touchscreen_gesture_heart_title),
    LETTER_S(SCANCODE_START + 18, R.string.touchscreen_gesture_letter_s_title),
}