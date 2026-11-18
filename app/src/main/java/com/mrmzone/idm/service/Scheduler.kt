package com.mrmzone.idm.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mrmzone.idm.data.Prefs
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.ui.MainActivity
import java.util.Calendar

object Scheduler {
    private const val ACT_START = "com.mrmzone.idm.SCHED_START"
    private const val ACT_STOP = "com.mrmzone.idm.SCHED_STOP"

    fun apply(ctx: Context) {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startPi = pi(ctx, ACT_START, 10)
        val stopPi = pi(ctx, ACT_STOP, 11)
        am.cancel(startPi); am.cancel(stopPi)
        if (!p.schedEnabled || p.schedDays == 0) return
        set(ctx, am, next(p.schedStartMin, p.schedDays), startPi)
        if (p.schedStopEnabled) set(ctx, am, next(p.schedStopMin, p.schedDays), stopPi)
    }

    fun nextStartTime(ctx: Context): Long? { val p = Prefs(ctx); return if (p.schedEnabled && p.schedDays != 0) next(p.schedStartMin, p.schedDays) else null }

    private fun set(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            val show = PendingIntent.getActivity(ctx, 12, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.set(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun pi(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, SchedulerReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun next(minOfDay: Int, days: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, minOfDay / 60); c.set(Calendar.MINUTE, minOfDay % 60)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        repeat(8) {
            val dow = c.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday
            if (days and (1 shl dow) != 0) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun handle(ctx: Context, action: String?) {
        DM.init(ctx)
        when (action) {
            ACT_START -> DM.startQueue()
            ACT_STOP -> DM.stopQueue()
        }
        apply(ctx)
    }
}

class SchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                Scheduler.apply(ctx)
                DM.init(ctx) // resumes interrupted downloads when auto-resume is on
            }
            else -> Scheduler.handle(ctx, intent.action)
        }
    }
}
