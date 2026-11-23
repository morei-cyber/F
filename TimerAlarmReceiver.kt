package jp.focusroom.nativeapp

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("focus_room", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("running", false)) return
        val deadline = prefs.getLong("deadline", 0L)
        if (deadline > System.currentTimeMillis()) return

        val mode = prefs.getString("mode", "focus") ?: "focus"
        val title = if (mode == "focus") "集中時間が終了しました" else "休憩時間が終了しました"
        val detail = if (mode == "focus") "FOCUS ROOMに戻ると、自動で休憩モードへ切り替わります。" else "FOCUS ROOMに戻ると、集中モードと画面ロックを再開します。"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "FOCUS ROOM タイマー", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { }
    }

    companion object {
        const val CHANNEL = "focus_room_timer"
        const val NOTIFICATION_ID = 4401

        fun schedule(context: Context, deadline: Long) {
            val alarm = context.getSystemService(AlarmManager::class.java)
            val intent = PendingIntent.getBroadcast(
                context, 20, Intent(context, TimerAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT >= 31 && alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, intent)
                } else if (Build.VERSION.SDK_INT >= 23) {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, intent)
                } else {
                    alarm.setExact(AlarmManager.RTC_WAKEUP, deadline, intent)
                }
            } catch (_: SecurityException) {
                try { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, intent) } catch (_: Exception) { }
            }
        }

        fun cancel(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java)
            val intent = PendingIntent.getBroadcast(
                context, 20, Intent(context, TimerAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(intent)
        }
    }
}
