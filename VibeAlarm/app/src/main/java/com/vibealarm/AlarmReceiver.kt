package com.vibealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson

class AlarmReceiver : BroadcastReceiver() {

    private val gson = Gson()

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val alarmId = intent.getStringExtra(AlarmConstants.EXTRA_ALARM_ID) ?: return
        val isSnooze = intent.getBooleanExtra(AlarmConstants.EXTRA_IS_SNOOZE, false)

        AlarmRingingService.startRinging(context, alarmId, isSnooze)

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmConstants.EXTRA_IS_SNOOZE, isSnooze)
        }
        context.startActivity(launch)

        val prefs = AlarmPreferences(context)
        if (isSnooze) {
            prefs.setPendingSnoozeAlarmId(null)
        }

        val payload = try {
            gson.fromJson(prefs.getLastPayloadJson(), AlarmPayload::class.java)
        } catch (_: Exception) {
            null
        }
        val alarm = payload?.alarms?.find { it.id == alarmId }
        if (alarm?.scheduleType != "once") {
            AlarmScheduler.rescheduleFromStoredJson(context.applicationContext)
        }
    }
}
