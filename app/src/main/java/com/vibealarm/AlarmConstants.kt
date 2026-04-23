package com.vibealarm

object AlarmConstants {
    const val ACTION_ALARM_FIRE = "com.vibealarm.ACTION_ALARM_FIRE"
    const val ACTION_SNOOZE_FIRE = "com.vibealarm.ACTION_SNOOZE_FIRE"
    const val ACTION_STOP_RINGING = "com.vibealarm.ACTION_STOP_RINGING"
    const val ACTION_BEDTIME_FIRE = "com.vibealarm.ACTION_BEDTIME_FIRE"

    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_IS_SNOOZE = "is_snooze"

    const val PREFS_NAME = "vibe_alarm_prefs"
    const val KEY_LAST_JSON = "last_alarm_json"
    const val KEY_SCHEDULED_IDS = "scheduled_alarm_ids"
    const val KEY_BIWEEKLY_ANCHOR_PREFIX = "biweekly_anchor_"
    const val KEY_POST_NOTIFICATIONS_GRANTED = "post_notifications_granted"

    const val NOTIFICATION_CHANNEL_ID = "alarm_channel"
    const val NOTIFICATION_CHANNEL_BEDTIME = "bedtime_channel"
    const val NOTIFICATION_ID_RINGING = 1001
    const val NOTIFICATION_ID_BEDTIME = 1002

    const val REQUEST_CODE_BEDTIME = 778_889

    /** Must match MainActivity / permission bridge */
    const val REQ_POST_NOTIFICATIONS = 2001
}
