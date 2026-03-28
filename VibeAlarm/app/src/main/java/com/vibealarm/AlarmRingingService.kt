package com.vibealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson

class AlarmRingingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volumeRampHandler: Handler? = null
    private var volumeRampRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AlarmConstants.ACTION_STOP_RINGING) {
            stopRingingAndSelf()
            return START_NOT_STICKY
        }

        val alarmId = intent?.getStringExtra(AlarmConstants.EXTRA_ALARM_ID) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        val prefs = AlarmPreferences(this)
        val payload = try {
            Gson().fromJson(prefs.getLastPayloadJson(), AlarmPayload::class.java)
        } catch (_: Exception) {
            null
        }
        val alarm = payload?.alarms?.find { it.id == alarmId }
        val vibPattern = payload?.settings?.vibPattern ?: "Pulse"
        val fadeInSeconds = payload?.settings?.fadeInDuration?.coerceIn(0, 120) ?: 30

        createChannel()
        val fullScreen = PendingIntent.getActivity(
            this,
            88001,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, AlarmConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.alarm_notification_title))
            .setContentText(alarm?.label?.takeIf { it.isNotBlank() } ?: getString(R.string.alarm_notification_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                AlarmConstants.NOTIFICATION_ID_RINGING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(AlarmConstants.NOTIFICATION_ID_RINGING, notification)
        }

        startAlarmSound(alarm?.sound, fadeInSeconds)
        startVibration(vibPattern)

        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            AlarmConstants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "VibeAlarm::RingingWakeLock",
        ).apply {
            acquire(30 * 60 * 1000L)
        }
    }

    private fun startAlarmSound(soundName: String?, fadeInSeconds: Int) {
        cancelVolumeRamp()
        try {
            mediaPlayer?.release()
            val uri: Uri = soundUriForName(soundName)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(applicationContext, uri)
                isLooping = true
                prepare()
            }
            mediaPlayer = mp
            applyVolumeFade(mp, fadeInSeconds)
        } catch (e: Exception) {
            try {
                val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(applicationContext, fallback)
                    isLooping = true
                    prepare()
                }
                mediaPlayer = mp
                applyVolumeFade(mp, fadeInSeconds)
            } catch (_: Exception) {
            }
        }
    }

    private fun applyVolumeFade(mp: MediaPlayer, fadeInSeconds: Int) {
        cancelVolumeRamp()
        if (fadeInSeconds <= 0) {
            mp.setVolume(1f, 1f)
            mp.start()
            return
        }
        mp.setVolume(0f, 0f)
        mp.start()
        val handler = Handler(Looper.getMainLooper())
        volumeRampHandler = handler
        var volume = 0f
        val step = 1f / fadeInSeconds.toFloat().coerceAtLeast(1f)
        val runnable = object : Runnable {
            override fun run() {
                val current = mediaPlayer ?: return
                if (volume < 1f) {
                    volume = (volume + step).coerceAtMost(1f)
                    try {
                        current.setVolume(volume, volume)
                    } catch (_: Exception) {
                    }
                    if (volume < 1f) {
                        handler.postDelayed(this, 1000L)
                    }
                }
            }
        }
        volumeRampRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelVolumeRamp() {
        volumeRampRunnable?.let { r ->
            volumeRampHandler?.removeCallbacks(r)
        }
        volumeRampRunnable = null
        volumeRampHandler = null
    }

    private fun soundUriForName(name: String?): Uri {
        val resId = when (name) {
            "Morning Piano" -> R.raw.morning_piano
            "Digital Beep" -> R.raw.alarm_default
            "Soft Bells", "Acoustic Strum", "Pulse Tone" -> R.raw.alarm_default
            else -> R.raw.alarm_default
        }
        return try {
            Uri.parse("android.resource://$packageName/$resId")
        } catch (_: Exception) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
    }

    private fun startVibration(patternName: String) {
        if (patternName == "Silent") return
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return

        val pattern = when (patternName) {
            "Pulse" -> longArrayOf(0, 200, 100, 200)
            "Ripple" -> longArrayOf(0, 100, 50, 100, 50, 200)
            "Storm" -> longArrayOf(0, 300, 100, 300, 100, 300)
            else -> longArrayOf(0, 200, 100, 200)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopRingingAndSelf() {
        cancelVolumeRamp()
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelVolumeRamp()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        fun startRinging(context: Context, alarmId: String, isSnooze: Boolean) {
            val i = Intent(context, AlarmRingingService::class.java).apply {
                putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmConstants.EXTRA_IS_SNOOZE, isSnooze)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stopRinging(context: Context) {
            val i = Intent(context, AlarmRingingService::class.java).apply {
                action = AlarmConstants.ACTION_STOP_RINGING
            }
            context.startService(i)
        }
    }
}
