package com.vibealarm

import android.content.Context
import android.content.SharedPreferences

class AlarmPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(AlarmConstants.PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLastPayloadJson(json: String?) {
        prefs.edit().putString(AlarmConstants.KEY_LAST_JSON, json).apply()
    }

    fun getLastPayloadJson(): String? = prefs.getString(AlarmConstants.KEY_LAST_JSON, null)

    fun setScheduledAlarmIds(ids: Set<String>) {
        prefs.edit().putStringSet(AlarmConstants.KEY_SCHEDULED_IDS, ids.toSet()).apply()
    }

    fun getScheduledAlarmIds(): Set<String> =
        prefs.getStringSet(AlarmConstants.KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()

    fun getBiweeklyAnchorMillis(alarmId: String): Long =
        prefs.getLong(AlarmConstants.KEY_BIWEEKLY_ANCHOR_PREFIX + alarmId, 0L)

    fun setBiweeklyAnchorMillis(alarmId: String, anchorMondayEpochMillis: Long) {
        prefs.edit().putLong(AlarmConstants.KEY_BIWEEKLY_ANCHOR_PREFIX + alarmId, anchorMondayEpochMillis).apply()
    }

    fun setPendingSnoozeAlarmId(id: String?) {
        if (id == null) prefs.edit().remove("pending_snooze_alarm_id").apply()
        else prefs.edit().putString("pending_snooze_alarm_id", id).apply()
    }

    fun getPendingSnoozeAlarmId(): String? = prefs.getString("pending_snooze_alarm_id", null)

    fun setPostNotificationsGranted(granted: Boolean) {
        prefs.edit().putBoolean(AlarmConstants.KEY_POST_NOTIFICATIONS_GRANTED, granted).apply()
    }

    fun getPostNotificationsGranted(): Boolean =
        prefs.getBoolean(AlarmConstants.KEY_POST_NOTIFICATIONS_GRANTED, false)
}
