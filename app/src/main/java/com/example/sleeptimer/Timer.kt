package com.example.sleeptimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimerCtl {
    private const val PREFS = "timer"
    private const val KEY = "end_at"
    private const val CHANNEL = "timer"
    private const val NOTIF_ID = 1

    fun endAt(c: Context): Long =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private fun lockPi(c: Context) =
        PendingIntent.getBroadcast(c, 1, Intent(c, LockReceiver::class.java), flags())
    private fun showPi(c: Context) =
        PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), flags())
    private fun cancelPi(c: Context) =
        PendingIntent.getBroadcast(c, 3, Intent(c, CancelReceiver::class.java), flags())

    fun start(c: Context, minutes: Int) = startAt(c, System.currentTimeMillis() + minutes * 60_000L)

    fun startAt(c: Context, at: Long) {
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(c)), lockPi(c))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, at).apply()
        postNotification(c, at)
    }

    /** Отмена таймера пользователем. */
    fun clear(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(lockPi(c))
        finish(c)
    }

    /** Таймер сработал или отменён: убрать состояние и уведомление. */
    fun finish(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun postNotification(c: Context, at: Long) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Таймер сна", NotificationManager.IMPORTANCE_LOW))
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        val cancel = Notification.Action.Builder(
            Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel),
            "Отмена", cancelPi(c)).build()
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Таймер сна")
            .setContentText("Экран заблокируется в $clock")
            .setWhen(at)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setContentIntent(showPi(c))
            .addAction(cancel)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}
