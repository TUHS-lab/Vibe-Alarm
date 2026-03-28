package com.vibealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private val gson = Gson()

    fun scheduleAllFromJson(context: Context, json: String?) {
        val prefs = AlarmPreferences(context)
        prefs.saveLastPayloadJson(json)
        val payload = try {
            gson.fromJson(json, AlarmPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "parse payload", e)
            null
        } ?: AlarmPayload()

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelTrackedAlarms(context, am, prefs)
        cancelSnoozeInternal(context, am, prefs)
        cancelBedtimeAlarm(context, am)

        val zone = ZoneId.systemDefault()
        val scheduled = mutableSetOf<String>()

        for (alarm in payload.alarms) {
            if (!alarm.enabled) continue
            val next = computeNextTriggerUtc(alarm, zone, prefs) ?: continue
            scheduleAlarmClock(context, am, alarm.id, next, isSnooze = false)
            scheduled.add(alarm.id)
        }
        prefs.setScheduledAlarmIds(scheduled)
        scheduleBedtimeIfNeeded(context, am, json, prefs, zone)
    }

    /** Reschedule next bedtime reminder (alarm time − 8h) after a bedtime notification fires. */
    fun scheduleBedtimeFromStoredJson(context: Context) {
        val prefs = AlarmPreferences(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        scheduleBedtimeIfNeeded(context, am, prefs.getLastPayloadJson(), prefs, zone)
    }

    fun rescheduleFromStoredJson(context: Context) {
        scheduleAll(context, AlarmRepository.load(context))
    }

    /** Entry point used by [AlarmBridge] and [BootReceiver]. */
    fun scheduleAll(context: Context, json: String?) {
        scheduleAllFromJson(context.applicationContext, json)
    }

    fun scheduleSnooze(context: Context, alarmId: String) {
        val prefs = AlarmPreferences(context)
        val payload = try {
            gson.fromJson(prefs.getLastPayloadJson(), AlarmPayload::class.java)
        } catch (_: Exception) {
            null
        } ?: return
        val minutes = payload.settings.snoozeDuration.coerceIn(1, 10)
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelSnoozeInternal(context, am, prefs)
        prefs.setPendingSnoozeAlarmId(alarmId)
        scheduleAlarmClock(context, am, alarmId, trigger, isSnooze = true)
    }

    fun cancelSnoozeAlarm(context: Context) {
        val prefs = AlarmPreferences(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelSnoozeInternal(context, am, prefs)
    }

    private fun cancelSnoozeInternal(context: Context, am: AlarmManager, prefs: AlarmPreferences) {
        val id = prefs.getPendingSnoozeAlarmId() ?: return
        cancelAlarmPending(context, am, id, isSnooze = true)
        prefs.setPendingSnoozeAlarmId(null)
    }

    private fun cancelTrackedAlarms(context: Context, am: AlarmManager, prefs: AlarmPreferences) {
        for (id in prefs.getScheduledAlarmIds()) {
            cancelAlarmPending(context, am, id, isSnooze = false)
        }
    }

    private fun cancelAlarmPending(
        context: Context,
        am: AlarmManager,
        alarmId: String,
        isSnooze: Boolean,
    ) {
        val pi = alarmPendingIntent(context, alarmId, isSnooze)
        am.cancel(pi)
        pi.cancel()
    }

    fun computeNextTriggerUtc(
        alarm: AlarmItem,
        zone: ZoneId,
        prefs: AlarmPreferences,
    ): Long? {
        val zdt = when (alarm.scheduleType) {
            "once" -> nextOnce(alarm, zone)
            "weekly" -> {
                val days = alarm.days.toMutableList()
                while (days.size < 7) days.add(false)
                if (days.none { it }) nextOnce(alarm, zone) else nextWeekly(alarm, zone)
            }
            "biweekly" -> nextBiweekly(alarm, zone, prefs)
            "lastday" -> nextLastDayOfMonth(alarm, zone)
            else -> {
                val days = alarm.days.toMutableList()
                while (days.size < 7) days.add(false)
                if (days.none { it }) nextOnce(alarm, zone) else nextWeekly(alarm, zone)
            }
        } ?: return null
        return zdt.toInstant().toEpochMilli()
    }

    private fun nextOnce(alarm: AlarmItem, zone: ZoneId): ZonedDateTime? {
        val now = ZonedDateTime.now(zone)
        for (add in 0..2L) {
            val d = now.toLocalDate().plusDays(add)
            val trigger = atDateWithAlarmTime(d, alarm.hour, alarm.minute, alarm.period, zone)
            if (trigger.isAfter(now)) return trigger
        }
        return null
    }

    private fun nextWeekly(alarm: AlarmItem, zone: ZoneId): ZonedDateTime? {
        val now = ZonedDateTime.now(zone)
        val days = alarm.days.toMutableList()
        while (days.size < 7) days.add(false)
        for (add in 0L..370L) {
            val d = now.toLocalDate().plusDays(add)
            val idx = dayIndexMon0(d.dayOfWeek)
            if (idx < days.size && days[idx]) {
                val trigger = atDateWithAlarmTime(d, alarm.hour, alarm.minute, alarm.period, zone)
                if (trigger.isAfter(now)) return trigger
            }
        }
        return null
    }

    private fun nextBiweekly(alarm: AlarmItem, zone: ZoneId, prefs: AlarmPreferences): ZonedDateTime? {
        ensureBiweeklyAnchor(alarm.id, zone, prefs)
        val anchorMs = prefs.getBiweeklyAnchorMillis(alarm.id)
        val anchorMonday = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
            .with(DayOfWeek.MONDAY)
        val now = ZonedDateTime.now(zone)
        val days = alarm.days.toMutableList()
        while (days.size < 7) days.add(false)
        for (add in 0L..400L) {
            val d = now.toLocalDate().plusDays(add)
            val idx = dayIndexMon0(d.dayOfWeek)
            if (idx >= days.size || !days[idx]) continue
            val monday = d.with(DayOfWeek.MONDAY)
            val weeks = ChronoUnit.WEEKS.between(anchorMonday, monday)
            if (weeks % 2 != 0L) continue
            val trigger = atDateWithAlarmTime(d, alarm.hour, alarm.minute, alarm.period, zone)
            if (trigger.isAfter(now)) return trigger
        }
        return null
    }

    private fun ensureBiweeklyAnchor(alarmId: String, zone: ZoneId, prefs: AlarmPreferences) {
        if (prefs.getBiweeklyAnchorMillis(alarmId) != 0L) return
        val monday = ZonedDateTime.now(zone).toLocalDate().with(DayOfWeek.MONDAY)
        val ms = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        prefs.setBiweeklyAnchorMillis(alarmId, ms)
    }

    private fun nextLastDayOfMonth(alarm: AlarmItem, zone: ZoneId): ZonedDateTime? {
        val now = ZonedDateTime.now(zone)
        var ym = YearMonth.from(now.toLocalDate())
        for (i in 0..36) {
            val lastDay = ym.atEndOfMonth()
            val trigger = atDateWithAlarmTime(lastDay, alarm.hour, alarm.minute, alarm.period, zone)
            if (trigger.isAfter(now)) return trigger
            ym = ym.plusMonths(1)
        }
        return null
    }

    private fun dayIndexMon0(d: DayOfWeek): Int = when (d) {
        DayOfWeek.MONDAY -> 0
        DayOfWeek.TUESDAY -> 1
        DayOfWeek.WEDNESDAY -> 2
        DayOfWeek.THURSDAY -> 3
        DayOfWeek.FRIDAY -> 4
        DayOfWeek.SATURDAY -> 5
        DayOfWeek.SUNDAY -> 6
    }

    private fun to24Hour(hour12: Int, period: String): Int {
        val p = period.uppercase()
        val h = hour12.coerceIn(1, 12)
        return when (p) {
            "AM" -> if (h == 12) 0 else h
            "PM" -> if (h == 12) 12 else h + 12
            else -> if (h == 12) 0 else h
        }
    }

    private fun atDateWithAlarmTime(
        date: LocalDate,
        hour12: Int,
        minute: Int,
        period: String,
        zone: ZoneId,
    ): ZonedDateTime {
        val h24 = to24Hour(hour12, period)
        val lt = LocalTime.of(h24, minute.coerceIn(0, 59))
        return date.atTime(lt).atZone(zone)
    }

    private fun requestCodeFor(alarmId: String, isSnooze: Boolean): Int {
        var rc = alarmId.hashCode()
        if (rc < 0) rc = -rc
        rc = rc and 0x0fffffff
        return if (isSnooze) rc xor 0x10000000 else rc
    }

    private fun alarmPendingIntent(
        context: Context,
        alarmId: String,
        isSnooze: Boolean,
    ): PendingIntent {
        val action = if (isSnooze) AlarmConstants.ACTION_SNOOZE_FIRE else AlarmConstants.ACTION_ALARM_FIRE
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmConstants.EXTRA_IS_SNOOZE, isSnooze)
        }
        val rc = requestCodeFor(alarmId, isSnooze)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, rc, intent, flags)
    }

    private fun scheduleAlarmClock(
        context: Context,
        am: AlarmManager,
        alarmId: String,
        triggerMillis: Long,
        isSnooze: Boolean,
    ) {
        val pi = alarmPendingIntent(context, alarmId, isSnooze)
        val show = PendingIntent.getActivity(
            context,
            requestCodeFor(alarmId, isSnooze) xor 0x20000000,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val info = AlarmManager.AlarmClockInfo(triggerMillis, show)
        am.setAlarmClock(info, pi)
    }

    private fun bedtimePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BedtimeReceiver::class.java).apply {
            action = AlarmConstants.ACTION_BEDTIME_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            AlarmConstants.REQUEST_CODE_BEDTIME,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelBedtimeAlarm(context: Context, am: AlarmManager) {
        val pi = bedtimePendingIntent(context)
        am.cancel(pi)
        pi.cancel()
    }

    private fun scheduleBedtimeIfNeeded(
        context: Context,
        am: AlarmManager,
        json: String?,
        prefs: AlarmPreferences,
        zone: ZoneId,
    ) {
        cancelBedtimeAlarm(context, am)
        val payload = try {
            gson.fromJson(json, AlarmPayload::class.java)
        } catch (_: Exception) {
            null
        } ?: return
        if (!payload.settings.bedtime) return
        var nextAlarmMs: Long? = null
        for (alarm in payload.alarms) {
            if (!alarm.enabled) continue
            val next = computeNextTriggerUtc(alarm, zone, prefs) ?: continue
            if (nextAlarmMs == null || next < nextAlarmMs) {
                nextAlarmMs = next
            }
        }
        val anchor = nextAlarmMs ?: return
        val bedtimeMs = Instant.ofEpochMilli(anchor).atZone(zone).minusHours(8).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        if (bedtimeMs <= now) return
        val pi = bedtimePendingIntent(context)
        val show = PendingIntent.getActivity(
            context,
            77002,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(bedtimeMs, show), pi)
    }
}
