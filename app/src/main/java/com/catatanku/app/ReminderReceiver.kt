package com.catatanku.app

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.catatanku.app.reminders.Reminders

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, 0)
        val due = intent.getLongExtra(Reminders.EXTRA_DUE, 0L)
        when (intent.action) {
            Reminders.ACT_FIRE -> Reminders.fire(ctx, id, snoozed = false, expectedDue = due)
            Reminders.ACT_SNOOZED -> Reminders.fire(ctx, id, snoozed = true)
            Reminders.ACT_DONE -> Reminders.done(ctx, id)
            Reminders.ACT_SNOOZE -> Reminders.snooze(ctx, id)
            Reminders.ACT_QUIET -> Reminders.quiet(ctx, id)
            Reminders.ACT_TEST -> Reminders.fireTest(ctx)
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Reminders.suppressLatenessBriefly(ctx)
                Reminders.rescheduleAll(ctx)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Reminders.rescheduleAll(ctx)
        }
    }
}
