package com.volumeboosterapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences // SharedPreferences added
// import android.media.audiofx.LoudnessEnhancer // Removed
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
// import kotlinx.coroutines.* // Removed

class VolumeBoosterWidgetProvider : AppWidgetProvider() {

    // Local LoudnessEnhancer removed. State will be managed by BoosterService.

    // Coroutine scope removed.

    companion object {
        // Widget's own toggle action (can be different from or same as Service's)
        // Using Service's ACTION_TOGGLE_BOOST might be more logical.
        // Let's use the Service's action for now.
        // private const val ACTION_TOGGLE_BOOST_WIDGET = "com.volumeboosterapp.ACTION_TOGGLE_BOOST_WIDGET"
        private const val TAG = "WidgetProvider"
        private const val PREFS_NAME = "BoosterPrefs" // Same as Service
        private const val PREF_KEY_BOOST_LEVEL = "boost_level_mb" // Same as Service

        // Helper function to update the widget manually
        fun updateWidget(context: Context) {
            val intent = Intent(context, VolumeBoosterWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, VolumeBoosterWidgetProvider::class.java))
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Widget update broadcast sent.")
        }

        // Get current boost level from SharedPreferences
        private fun getBoostLevelFromPrefs(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(PREF_KEY_BOOST_LEVEL, 0) // Default 0 (off)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called.")
        // Update all widget instances based on the current level in SharedPreferences
        val currentLevel = getBoostLevelFromPrefs(context)
        val isEnabled = currentLevel > 0 // Consider it on if level is greater than 0
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId, isEnabled) // Use updateAppWidget here
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive called. Action: ${intent.action}")

        // Send toggle command to Service when widget button is clicked
        // Note: We are using the Service's action directly instead of listening for the widget's own action.
        // This requires us to use getService when creating the PendingIntent.
        // If getBroadcast were used, we would need to send an intent to the service within onReceive.
        // The current structure (PendingIntent with getService) is more correct.
        // Therefore, the toggle logic inside onReceive is no longer needed.
        // Let's just listen for the update action.
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE == intent.action) {
             // This runs when updateWidget is called or the system updates the widget.
             // Since onUpdate is already called, no additional action is needed here.
             Log.d(TAG, "Widget update request received (onUpdate will likely be triggered).")
        } else {
             Log.d(TAG, "Unknown action received: ${intent.action}")
        }
        /* Old toggle logic (if getBroadcast were used):
        if (BoosterService.ACTION_TOGGLE_BOOST == intent.action) {
             Log.d(TAG, "Widget toggle button clicked. Sending intent to Service.")
             val serviceIntent = Intent(context, BoosterService::class.java).apply {
                 action = BoosterService.ACTION_TOGGLE_BOOST
             }
             // Start the service (if already running, only onStartCommand is triggered)
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 context.startForegroundService(serviceIntent)
             } else {
                 context.startService(serviceIntent)
             }
             // Note: After changing state, Service will send a broadcast to update the widget
             // or call updateWidget. So no additional update needed here.
        } else if (AppWidgetManager.ACTION_APPWIDGET_UPDATE == intent.action) {
             // This runs when updateWidget is called or the system updates the widget.
             // Since onUpdate is already called, no additional action is needed here.
             Log.d(TAG, "Widget update request received (onUpdate will likely be triggered).")
        }
        */ // Close comment block properly
    }

    // toggleBoost function removed.
    // updateWidgetViews function removed (done in onUpdate).

    // Renamed back to updateAppWidget
    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        isBoostEnabled: Boolean // Current state is taken as a parameter
    ) {
        Log.d(TAG, "updateAppWidget called. Widget ID: $appWidgetId, State: $isBoostEnabled")
        val views = RemoteViews(context.packageName, R.layout.volume_booster_widget)

        // Set button icon based on state (should be same as icons in Service)
        val iconResId = if (isBoostEnabled) R.drawable.ic_volume_up_white else R.drawable.ic_volume_off_white // Assuming custom icons
        // If custom icons are not available:
        // val iconResId = if (isBoostEnabled) android.R.drawable.ic_notification_overlay else android.R.drawable.stat_notify_call_mute
        views.setImageViewResource(R.id.widget_button, iconResId)

        // Create click intent for button (to be sent to Service)
        val toggleIntent = Intent(context, BoosterService::class.java).apply {
            // We could use a different action to indicate it came from the widget, but it's simpler for the Service to listen to a single action.
            action = BoosterService.ACTION_TOGGLE_BOOST
        }
        // Set PendingIntent flag based on Android version (for Service)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
             PendingIntent.FLAG_UPDATE_CURRENT
        }
        // Request code should be different from the notification in Service (to avoid collision)
        // Use getService to directly call the service
        val pendingIntent = PendingIntent.getService(context, 2, toggleIntent, pendingIntentFlags) // Request code 2

        views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)

        // Update the widget
        try {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget $appWidgetId updated successfully.")
        } catch (e: Exception) {
             Log.e(TAG, "Error updating widget $appWidgetId", e)
        }
    }

     // No need to release resources when app or widget is removed (no local enhancer anymore)
     override fun onDisabled(context: Context?) {
         super.onDisabled(context)
         Log.d(TAG, "onDisabled called.")
         // widgetScope.cancel() // Removed
         // Local enhancer release removed.
     }
}
