package com.volumeboosterapp

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BoosterService : Service() {

    // Constants for SharedPreferences
    private val PREFS_NAME = "BoosterPrefs"
    private val PREF_KEY_BOOST_LEVEL = "boost_level_mb" // Store level in mB
    private val PREF_KEY_LAST_BOOST_LEVEL = "last_boost_level_mb" // Remember last level for toggle
    // Constants moved into companion object

    private lateinit var sharedPreferences: SharedPreferences

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionReceiver: BroadcastReceiver? = null
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null

    companion object {
        private const val TAG = "BoosterService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "VolumeBoosterChannel"
        private const val HEALTH_CHECK_INTERVAL_MS = 3000L
        const val ACTION_START_SERVICE = "com.volumeboosterapp.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.volumeboosterapp.ACTION_STOP_SERVICE"
        const val ACTION_TOGGLE_BOOST = "com.volumeboosterapp.ACTION_TOGGLE_BOOST"
        const val ACTION_SET_BOOST_LEVEL = "com.volumeboosterapp.ACTION_SET_BOOST_LEVEL"
        const val ACTION_EXIT_APP = "com.volumeboosterapp.ACTION_EXIT_APP"
        const val ACTION_UPDATE_NOTIFICATION = "com.volumeboosterapp.ACTION_UPDATE_NOTIFICATION"
        const val ACTION_FINISH_ACTIVITY = "com.volumeboosterapp.ACTION_FINISH_ACTIVITY"
        const val ACTION_BOOST_LEVEL_UPDATED = "com.volumeboosterapp.ACTION_BOOST_LEVEL_UPDATED"
        const val EXTRA_BOOST_LEVEL = "com.volumeboosterapp.EXTRA_BOOST_LEVEL"
        const val EXTRA_NEW_BOOST_LEVEL = "com.volumeboosterapp.EXTRA_NEW_BOOST_LEVEL"
        const val DEFAULT_BOOST_LEVEL = 3500
        const val MAX_BOOST_LEVEL = 7000
    }

    /**
     * Creates a new LoudnessEnhancer instance (session 0), releasing any existing one first.
     * This is called on service start, and whenever we detect the audio session has changed
     * (e.g. track change, playback restart) to work around Android 12+ custom ROM behavior
     * where effects attached to session 0 become stale.
     */
    private fun createOrRecreateLoudnessEnhancer() {
        try {
            // Release old instance if it exists
            loudnessEnhancer?.let { old ->
                try {
                    old.enabled = false
                    old.release()
                    Log.d(TAG, "Old LoudnessEnhancer released before recreation.")
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing old LoudnessEnhancer", e)
                }
            }
            loudnessEnhancer = null

            val level = getBoostLevelFromPrefs()
            val newEnhancer = LoudnessEnhancer(0) // Session ID 0 (global output mix)
            applyBoostLevel(newEnhancer, level)
            loudnessEnhancer = newEnhancer
            Log.d(TAG, "LoudnessEnhancer (re)created. Level: $level mB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create LoudnessEnhancer", e)
            loudnessEnhancer = null
        }
    }

    /**
     * Registers a BroadcastReceiver that listens for new audio sessions being opened.
     * When a media app opens a new audio session (track change, new playback, etc.),
     * we recreate the LoudnessEnhancer to ensure the boost effect is re-applied.
     */
    private fun registerAudioSessionReceiver() {
        audioSessionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"
                Log.d(TAG, "Audio session broadcast: action=$action, sessionId=$sessionId, pkg=$pkg")

                if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                    val currentLevel = getBoostLevelFromPrefs()
                    if (currentLevel > 0) {
                        Log.d(TAG, "New audio session detected while boost is active. Recreating enhancer.")
                        createOrRecreateLoudnessEnhancer()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, audioSessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(audioSessionReceiver, filter)
        }
        Log.d(TAG, "AudioSessionReceiver registered.")
    }

    /**
     * Unregisters the audio session BroadcastReceiver.
     */
    private fun unregisterAudioSessionReceiver() {
        audioSessionReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "AudioSessionReceiver unregistered.")
            } catch (e: Exception) {
                Log.w(TAG, "Could not unregister AudioSessionReceiver", e)
            }
        }
        audioSessionReceiver = null
    }

    /**
     * Starts a periodic health check that verifies the LoudnessEnhancer is still
     * functioning correctly. If the enhancer has become stale (disabled unexpectedly
     * or gain reset), it will be recreated. Only runs while boost level > 0.
     */
    private fun startHealthCheck() {
        stopHealthCheck()
        healthCheckRunnable = object : Runnable {
            override fun run() {
                val currentLevel = getBoostLevelFromPrefs()
                if (currentLevel > 0) {
                    val enhancer = loudnessEnhancer
                    var needsRecreation = false

                    if (enhancer == null) {
                        Log.w(TAG, "Health check: enhancer is null while boost is active.")
                        needsRecreation = true
                    } else {
                        try {
                            if (!enhancer.enabled) {
                                Log.w(TAG, "Health check: enhancer is disabled unexpectedly.")
                                needsRecreation = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Health check: enhancer threw exception (likely dead)", e)
                            needsRecreation = true
                        }
                    }

                    if (needsRecreation) {
                        Log.d(TAG, "Health check: recreating LoudnessEnhancer.")
                        createOrRecreateLoudnessEnhancer()
                    }
                }
                healthCheckHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
        healthCheckHandler.postDelayed(healthCheckRunnable!!, HEALTH_CHECK_INTERVAL_MS)
        Log.d(TAG, "Health check started (interval: ${HEALTH_CHECK_INTERVAL_MS}ms).")
    }

    /**
     * Stops the periodic health check.
     */
    private fun stopHealthCheck() {
        healthCheckRunnable?.let {
            healthCheckHandler.removeCallbacks(it)
        }
        healthCheckRunnable = null
    }

    // Get current boost level from SharedPreferences
    private fun getBoostLevelFromPrefs(): Int {
        return sharedPreferences.getInt(PREF_KEY_BOOST_LEVEL, 0) // Default 0 (off)
    }

    // Get the last set (greater than 0) level from SharedPreferences
    private fun getLastBoostLevelFromPrefs(): Int {
        return sharedPreferences.getInt(PREF_KEY_LAST_BOOST_LEVEL, DEFAULT_BOOST_LEVEL) // Default
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We are not using a bound service
        return null
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        createOrRecreateLoudnessEnhancer()
        registerAudioSessionReceiver()
        startHealthCheck()
        Log.d(TAG, "Service onCreate. Initial level from Prefs: ${getBoostLevelFromPrefs()} mB")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand, Action: ${intent?.action}")

        // Ensure enhancer exists; recreate if it was lost
        if (loudnessEnhancer == null) {
            Log.w(TAG, "Enhancer is null in onStartCommand, recreating.")
            createOrRecreateLoudnessEnhancer()
        }

        // --- Start Foreground Immediately ---
        // Read the current level to create the correct initial notification
        val currentLevel = getBoostLevelFromPrefs()
        // Call startForeground() *before* processing the specific action.
        // This ensures the service complies with foreground service start requirements.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+ (API 29+), use the version with service type
            startForeground(NOTIFICATION_ID, createNotification(currentLevel > 0), 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            // For older versions, use the basic version
            startForeground(NOTIFICATION_ID, createNotification(currentLevel > 0))
        }
        Log.d(TAG, "startForeground called immediately in onStartCommand. Initial Level: $currentLevel mB")
        // --- End Start Foreground Immediately ---


        when (intent?.action) {
            // ACTION_START_SERVICE case might become redundant for starting foreground,
            // but can still be used for specific logic if needed.
            // We'll leave it for now, but the essential startForeground is already done.
            ACTION_START_SERVICE -> {
                 Log.d(TAG, "Processing ACTION_START_SERVICE (startForeground already called).")
                 // Potentially update notification if needed, though it was just created.
                 // updateNotification(currentLevel > 0) // Might be redundant
            }
            ACTION_UPDATE_NOTIFICATION -> {
                // Explicitly update the notification based on the current state
                val levelForUpdate = getBoostLevelFromPrefs()
                updateNotification(levelForUpdate > 0)
                Log.d(TAG, "Processing ACTION_UPDATE_NOTIFICATION. Level: $levelForUpdate mB")
            }
            ACTION_STOP_SERVICE -> {
                // Set level to 0 before stopping service (optional)
                // setBoostLevel(0) // User might want to leave it on
                stopForeground(true)
                stopSelf()
                Log.d(TAG, "Foreground service stopped.")
                // We might want to turn off boost when service stops (optional)
                // setBoostState(false)
            }
            ACTION_TOGGLE_BOOST -> {
                // Read current level from SharedPreferences
                val currentLevel = getBoostLevelFromPrefs()
                val lastLevel = getLastBoostLevelFromPrefs()
                val newLevel = if (currentLevel > 0) 0 else lastLevel // If on, turn off; if off, set to last level

                setBoostLevel(newLevel) // This method will write the level to both enhancer and SharedPreferences
                // Update notification (based on on/off state)
                updateNotification(newLevel > 0)
                // Update the widget as well
                VolumeBoosterWidgetProvider.updateWidget(this)
                Log.d(TAG, "Toggle action processed. New level: $newLevel mB")
            }
            ACTION_SET_BOOST_LEVEL -> {
                val requestedLevel = intent.getIntExtra(EXTRA_BOOST_LEVEL, -1)
                if (requestedLevel != -1) {
                    // Ensure it stays within the safety limit
                    val newLevel = requestedLevel.coerceIn(0, MAX_BOOST_LEVEL)
                    setBoostLevel(newLevel)
                    // Update notification (based on on/off state)
                    updateNotification(newLevel > 0)
                    // Update the widget as well
                    VolumeBoosterWidgetProvider.updateWidget(this)
                    Log.d(TAG, "Set level action processed. New level: $newLevel mB")
                } else {
                    Log.w(TAG, "EXTRA_BOOST_LEVEL not found in ACTION_SET_BOOST_LEVEL intent.")
                }
            }
            ACTION_EXIT_APP -> {
                Log.d(TAG, "Exit action received. Sending finish broadcast and stopping service.")
                // Send finish broadcast to Activity
                val finishIntent = Intent(ACTION_FINISH_ACTIVITY)
                sendBroadcast(finishIntent)

                // We can add a small delay to wait for the broadcast to arrive (optional but sometimes necessary)
                // android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                //     // Cancel notification manually
                //     val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                //     notificationManager.cancel(NOTIFICATION_ID)
                //     // Stop foreground service
                //     stopForeground(true)
                //     // Stop service
                //     stopSelf()
                // }, 100) // 100ms delay

                // Non-delayed version:
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
                stopForeground(true)
                stopSelf()

                // Additional mechanisms (broadcast etc.) might be needed to close the activity,
                // but stopping the service is usually sufficient.
            }
        }
        // Restart if service is killed by system (START_STICKY)
        // Or don't restart (START_NOT_STICKY)
        return START_STICKY
    }

    // Applies the specified level to LoudnessEnhancer
    private fun applyBoostLevel(enhancer: LoudnessEnhancer, level: Int) {
        try {
            if (level > 0) {
                enhancer.setTargetGain(level)
                enhancer.enabled = true
                Log.d(TAG, "LoudnessEnhancer gain set: $level mB")
            } else {
                enhancer.enabled = false
                Log.d(TAG, "LoudnessEnhancer disabled.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply LoudnessEnhancer level ($level mB)", e)
        }
    }

    // Sets boost level, saves to prefs, and applies to enhancer
    private fun setBoostLevel(level: Int) {
        if (loudnessEnhancer == null) {
            Log.e(TAG, "LoudnessEnhancer not available, cannot change level.")
            return
        }
        val enhancer = loudnessEnhancer // Assign value to local variable
        if (enhancer == null) { // Check local variable (extra safety)
             Log.e(TAG, "LoudnessEnhancer instance became null unexpectedly.")
             return
        }
        // Apply level (LoudnessEnhancer)
        applyBoostLevel(enhancer, level) // Use local variable

        // Code for adjusting call volume removed
        // applyVoiceCallVolume(level)

        // Save level to SharedPreferences
        sharedPreferences.edit().apply {
            putInt(PREF_KEY_BOOST_LEVEL, level)
            // If level is greater than 0, also save as last level
            if (level > 0) {
                putInt(PREF_KEY_LAST_BOOST_LEVEL, level)
            }
            apply()
        }
        Log.d(TAG, "Boost level set to $level mB and Prefs updated.")

        // Notify level change via broadcast
        val updateIntent = Intent(ACTION_BOOST_LEVEL_UPDATED).apply {
            putExtra(EXTRA_NEW_BOOST_LEVEL, level)
        }
        sendBroadcast(updateIntent)
        Log.d(TAG, "ACTION_BOOST_LEVEL_UPDATED broadcast sent. Level: $level mB")

        // Send request to update Quick Settings Tile immediately
        BoosterTileService.requestTileUpdate(this)
    }

    // Function to adjust STREAM_VOICE_CALL volume removed
    /*
    private fun applyVoiceCallVolume(boostLevel: Int) {
        try {
            if (boostLevel > 0) {
                // If booster is on, set call volume to maximum
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0) // Without FLAG_SHOW_UI
                Log.d(TAG, "STREAM_VOICE_CALL volume set to max ($maxVolume)")
            } else {
                // If booster is off, we might not change the call volume or revert to default.
                // Let's not change it for now, user might have adjusted manually.
                Log.d(TAG, "Booster disabled, STREAM_VOICE_CALL volume not changed by booster.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust STREAM_VOICE_CALL volume", e)
        }
    }
    */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Volume Booster Service Channel",
                NotificationManager.IMPORTANCE_LOW // Low importance, makes no sound but is visible
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(isBoostEnabled: Boolean): Notification {
        // Intent to open the app when notification is clicked
        val notificationIntent = Intent(this, MainActivity::class.java) // Open MainActivity
        val pendingIntentFlagsActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntentActivity = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlagsActivity)


        // Intent for toggle button (to be sent to Service)
        val toggleIntent = Intent(this, BoosterService::class.java).apply {
            action = ACTION_TOGGLE_BOOST
        }
         // Set PendingIntent flag based on Android version (for Service)
         val pendingIntentFlagsToggle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE // FLAG_IMMUTABLE is generally safer for Service
         } else {
             PendingIntent.FLAG_UPDATE_CURRENT
         }
        // Make request code different (can be different from widget) - 1 for Toggle
        val togglePendingIntent = PendingIntent.getService(this, 1, toggleIntent, pendingIntentFlagsToggle)

        // Intent for exit button (to be sent to Service)
        val exitIntent = Intent(this, BoosterService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        // Set PendingIntent flag based on Android version (for Service)
        val pendingIntentFlagsExit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // Make request code different - 2 for Exit
        val exitPendingIntent = PendingIntent.getService(this, 2, exitIntent, pendingIntentFlagsExit)


        // Set icon and text based on state
        val iconResId = if (isBoostEnabled) R.drawable.ic_volume_up_white else R.drawable.ic_volume_off_white // Assuming custom icons
        // Notification button: Shows the action to be performed (If active "Off" to turn off, if inactive "On" to turn on)
        val buttonText = if (isBoostEnabled) "OFF" else "ON"
        val contentText = if (isBoostEnabled) "Volume boost enabled" else "Volume boost disabled" // English content text

        // Use default icons if custom ones are not available
        // val iconResId = if (isBoostEnabled) android.R.drawable.ic_notification_overlay else android.R.drawable.stat_notify_call_mute

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Booster") // More generic title
            .setContentText(contentText)
            .setSmallIcon(iconResId) // Icon based on state (should be in drawable folder)
            .setContentIntent(pendingIntentActivity) // Open MainActivity on notification click
            .addAction(0, buttonText, togglePendingIntent) // On/Off button
            .addAction(0, "EXIT", exitPendingIntent) // Exit button
            .setOngoing(true) // Cannot be easily dismissed by the user
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
            .build()
    }

     private fun updateNotification(isBoostEnabled: Boolean) {
         val notification = createNotification(isBoostEnabled)
         val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
         notificationManager.notify(NOTIFICATION_ID, notification)
         Log.d(TAG, "Notification updated. State: $isBoostEnabled")
     }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        // Stop health check
        stopHealthCheck()
        // Unregister audio session receiver
        unregisterAudioSessionReceiver()
        try {
            // Disable and release enhancer when service stops
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            Log.d(TAG, "LoudnessEnhancer released from Service.")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LoudnessEnhancer from Service", e)
        }
        // Set saved level to 0 when service stops to ensure Tile updates
        sharedPreferences.edit().putInt(PREF_KEY_BOOST_LEVEL, 0).apply()
        Log.d(TAG, "Boost level set to 0 in SharedPreferences on service destroy.")
        // Request tile update one last time to ensure it reflects the off state
        BoosterTileService.requestTileUpdate(this)
        // Update the home screen widget as well
        VolumeBoosterWidgetProvider.updateWidget(this)
        Log.d(TAG, "Home screen widget update requested on service destroy.")
    }

    // Called when the task the service is associated with is removed (e.g., swiped from recents)
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved called. Stopping service.")
        // Stop the service explicitly
        stopForeground(true) // Remove notification
        stopSelf() // Stop the service itself
        super.onTaskRemoved(rootIntent)
    }
}
