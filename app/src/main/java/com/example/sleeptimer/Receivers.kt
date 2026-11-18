package com.example.sleeptimer

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

class AdminReceiver : DeviceAdminReceiver()

class LockReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        TimerCtl.finish(c)
        val am = c.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(c, AdminReceiver::class.java))) dpm.lockNow()
    }
}

class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        TimerCtl.clear(c)
    }
}
