package com.vibealarm

import com.google.gson.annotations.SerializedName

data class AlarmPayload(
    val alarms: List<AlarmItem> = emptyList(),
    val settings: SettingsPayload = SettingsPayload(),
)

data class SettingsPayload(
    val snoozeDuration: Int = 9,
    val fadeInDuration: Int = 30,
    val vibPattern: String = "Pulse",
    val bedtime: Boolean = true,
    /** JS + native: do not re-show notification permission onboarding */
    val permissionsGranted: Boolean = false,
)

data class AlarmItem(
    val id: String,
    val hour: Int,
    val minute: Int,
    val period: String,
    val label: String = "",
    val sound: String = "Soft Bells",
    val days: List<Boolean> = List(7) { true },
    val enabled: Boolean = true,
    val tag: String = "",
    val note: String = "",
    @SerializedName("scheduleType") val scheduleType: String = "weekly",
)
