package com.vibealarm

import android.content.Context

/**
 * Persists the last JSON payload shared with the WebView / scheduler (alarms, settings, history).
 */
object AlarmRepository {

    fun save(context: Context, json: String) {
        AlarmPreferences(context.applicationContext).saveLastPayloadJson(json)
    }

    fun load(context: Context): String {
        return AlarmPreferences(context.applicationContext).getLastPayloadJson()
            ?: "{\"alarms\":[],\"settings\":{}}"
    }
}
