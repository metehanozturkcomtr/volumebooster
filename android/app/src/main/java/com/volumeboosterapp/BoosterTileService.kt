package com.volumeboosterapp

import android.content.Context
import android.content.ComponentName // Added
// import android.content.Context // Duplicate import removed
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N) // TileService requires API level 24 (Nougat)
class BoosterTileService : TileService() {

    private lateinit var sharedPreferences: SharedPreferences
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Update tile only when boost level changes
        if (key == PREF_KEY_BOOST_LEVEL) {
            updateTileState()
        }
    }

    companion object {
        private const val TAG = "BoosterTileService"
        // SharedPreferences constants (same as BoosterService)
        private const val PREFS_NAME = "BoosterPrefs"
        private const val PREF_KEY_BOOST_LEVEL = "boost_level_mb"

        // Static method to update the Tile externally
        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(context, ComponentName(context, BoosterTileService::class.java))
                    Log.d(TAG, "Tile update requested.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request tile update", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Called when the tile becomes visible
    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening")
        updateTileState()
        // Start listening for SharedPreferences changes
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    // Called when the tile is no longer visible
    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "onStopListening")
        // Remove the listener
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // Called when the tile is clicked
    override fun onClick() {
        super.onClick()
        Log.d(TAG, "onClick")
        
        val currentLevel = sharedPreferences.getInt(PREF_KEY_BOOST_LEVEL, 0)
        val isEnabled = currentLevel > 0

        val actionToSend = if (isEnabled) {
            BoosterService.ACTION_EXIT_APP // Completely close the app instead of just disabling boost
        } else {
            BoosterService.ACTION_TOGGLE_BOOST // Turn on the boost
        }

        // Send command to BoosterService
        val intent = Intent(this, BoosterService::class.java).apply {
            action = actionToSend
        }
        // Start the service (if already running, only onStartCommand is triggered)
        // Since TileService is a Context, we can call startService directly.
        try {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 startForegroundService(intent)
             } else {
                 startService(intent)
             }
             Log.d(TAG, "ACTION_TOGGLE_BOOST intent sent to BoosterService.")
        } catch (e: Exception) {
             Log.e(TAG, "Failed to start BoosterService / send intent.", e)
             // In case of error, tile state might not update; it will be updated again in onStartListening.
        }
        // Note: SharedPreferences will be updated after the Service changes state
        // and prefsListener will trigger updateTileState.
        // So, no need for manual update here.
    }

    // Updates the appearance and state of the tile
    private fun updateTileState() {
        val tile = qsTile ?: return // Exit if Tile is not ready yet
        val currentLevel = sharedPreferences.getInt(PREF_KEY_BOOST_LEVEL, 0)
        val isEnabled = currentLevel > 0

        Log.d(TAG, "updateTileState - Enabled: $isEnabled (Level: $currentLevel)")

        // Logic to show the current state:
        if (isEnabled) { // Booster ON
            tile.state = Tile.STATE_ACTIVE   // Active (clickable)
            tile.label = "Booster On"      // Will display "Booster On" (current state)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_volume_up_white) // On icon
        } else { // Booster OFF
            tile.state = Tile.STATE_INACTIVE // Changed to INACTIVE for deactivated (gray) appearance
            tile.label = "Booster Off"     // Will display "Booster Off" (current state)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_volume_off_white) // Off icon
        }
        tile.updateTile()
    }

    // Called when the service is destroyed
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // Remove the listener (safety measure)
        try {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            // Might already be removed
        }
    }
}
