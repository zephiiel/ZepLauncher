package com.zephiiel.zeplauncher

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class RecentNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "ZepNotifications"
        private const val MAX_SAVED_NOTIFICATIONS = 8
        const val PREFERENCES_NAME = "widget_data"
        const val KEY_NOTIFICATIONS = "active_notifications"
        const val ACTION_WIDGET_DATA_CHANGED =
            "com.zephiiel.zeplauncher.ACTION_WIDGET_DATA_CHANGED"
        const val ACTION_OPEN_NOTIFICATION =
            "com.zephiiel.zeplauncher.ACTION_OPEN_NOTIFICATION"
        const val ACTION_DISMISS_NOTIFICATION =
            "com.zephiiel.zeplauncher.ACTION_DISMISS_NOTIFICATION"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
    }

    private var actionReceiverRegistered = false
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val key = intent.getStringExtra(EXTRA_NOTIFICATION_KEY) ?: return
            when (intent.action) {
                ACTION_OPEN_NOTIFICATION -> openNotification(key)
                ACTION_DISMISS_NOTIFICATION -> {
                    cancelNotification(key)
                    refreshActiveNotifications()
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerActionReceiver()
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        unregisterActionReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterActionReceiver()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        refreshActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refreshActiveNotifications()
    }

    private fun registerActionReceiver() {
        if (actionReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_OPEN_NOTIFICATION)
            addAction(ACTION_DISMISS_NOTIFICATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(actionReceiver, filter)
        }
        actionReceiverRegistered = true
    }

    private fun unregisterActionReceiver() {
        if (!actionReceiverRegistered) return
        unregisterReceiver(actionReceiver)
        actionReceiverRegistered = false
    }

    private fun refreshActiveNotifications() {
        val active = activeNotifications
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .take(MAX_SAVED_NOTIFICATIONS)
        val saved = JSONArray()
        active.forEach { statusBarNotification ->
            saved.put(toJson(statusBarNotification))
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
            .putString(KEY_NOTIFICATIONS, saved.toString())
            .apply()
        sendBroadcast(Intent(ACTION_WIDGET_DATA_CHANGED).setPackage(packageName))
    }

    private fun toJson(sbn: StatusBarNotification): JSONObject {
        val appName = try {
            val application = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(application).toString()
        } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
            Log.w(TAG, "Notification source app is no longer installed.", error)
            sbn.packageName
        }
        val extras = sbn.notification.extras
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" · ")
            )?.toString().orEmpty()
        return JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("app", appName.take(100))
            .put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(200))
            .put("body", body.take(400))
    }

    private fun openNotification(key: String) {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return
        val contentIntent = sbn.notification.contentIntent
        if (contentIntent != null) {
            try {
                contentIntent.send()
                return
            } catch (error: android.app.PendingIntent.CanceledException) {
                Log.w(TAG, "Notification action expired; opening its source app instead.", error)
            }
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(sbn.packageName)
        if (launchIntent == null) {
            Log.w(TAG, "Notification source app has no launch activity.")
            return
        }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } catch (error: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Notification source app could not be opened.", error)
        }
    }
}
