package com.vibealarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface

class AlarmBridge(
    private val context: Context,
    private val onPayloadUpdated: () -> Unit,
) {

    @JavascriptInterface
    fun onAlarmsChanged(json: String) {
        AlarmRepository.save(context.applicationContext, json)
        AlarmScheduler.scheduleAll(context.applicationContext, json)
        onPayloadUpdated()
    }

    @JavascriptInterface
    fun stopAlarmSound() {
        AlarmRingingService.stopRinging(context.applicationContext)
    }

    @JavascriptInterface
    fun triggerAlarm(id: String) {
        AlarmRingingService.startRinging(context.applicationContext, id, false)
        val launch = android.content.Intent(context, MainActivity::class.java).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(AlarmConstants.EXTRA_ALARM_ID, id)
            putExtra(AlarmConstants.EXTRA_IS_SNOOZE, false)
        }
        context.startActivity(launch)
    }

    @JavascriptInterface
    fun scheduleSnooze(alarmId: String) {
        AlarmScheduler.scheduleSnooze(context.applicationContext, alarmId)
    }

    @JavascriptInterface
    fun cancelSnoozeAlarm() {
        AlarmScheduler.cancelSnoozeAlarm(context.applicationContext)
    }

    @JavascriptInterface
    fun getPersistedPayload(): String {
        return AlarmPreferences(context.applicationContext).getLastPayloadJson() ?: ""
    }

    @JavascriptInterface
    fun hasPostNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Called from Configuration screen when user taps Continue — not at app cold start.
     */
    @JavascriptInterface
    fun requestPostNotificationsPermission() {
        Handler(Looper.getMainLooper()).post {
            val act = context as? MainActivity ?: return@post
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                AlarmPreferences(act.applicationContext).setPostNotificationsGranted(true)
                act.notifyWebPermissionResult(true)
                return@post
            }
            if (ContextCompat.checkSelfPermission(
                    act,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                AlarmPreferences(act.applicationContext).setPostNotificationsGranted(true)
                act.notifyWebPermissionResult(true)
                return@post
            }
            ActivityCompat.requestPermissions(
                act,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                AlarmConstants.REQ_POST_NOTIFICATIONS,
            )
        }
    }

    @JavascriptInterface
    fun shouldShowPermissionOnboarding(): Boolean {
        val prefs = AlarmPreferences(context.applicationContext)
        if (prefs.getPostNotificationsGranted()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    @JavascriptInterface
    fun markPermissionFlowCompleted() {
        AlarmPreferences(context.applicationContext).setPostNotificationsGranted(true)
    }

    @JavascriptInterface
    fun setVibrationPattern(pattern: String) {
        val appCtx = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appCtx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appCtx.getSystemService(Vibrator::class.java)
            }
            if (vibrator == null) return@post
            if (pattern == "Silent") {
                vibrator.cancel()
                return@post
            }
            val timings = when (pattern) {
                "Pulse" -> longArrayOf(0, 200, 100, 200)
                "Ripple" -> longArrayOf(0, 100, 50, 100, 50, 200)
                "Storm" -> longArrayOf(0, 300, 100, 300, 100, 300)
                else -> longArrayOf(0, 200, 100, 200)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        }
    }
}
