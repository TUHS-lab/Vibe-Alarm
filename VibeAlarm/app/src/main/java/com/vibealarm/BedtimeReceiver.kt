package com.vibealarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class BedtimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        createChannel(context)
        val open = PendingIntent.getActivity(
            context,
            77001,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AlarmConstants.NOTIFICATION_CHANNEL_BEDTIME)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.bedtime_notification_title))
            .setContentText(context.getString(R.string.bedtime_notification_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(AlarmConstants.NOTIFICATION_ID_BEDTIME, notification)
        AlarmScheduler.scheduleBedtimeFromStoredJson(context.applicationContext)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            AlarmConstants.NOTIFICATION_CHANNEL_BEDTIME,
            context.getString(R.string.channel_bedtime_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(ch)
    }
}
