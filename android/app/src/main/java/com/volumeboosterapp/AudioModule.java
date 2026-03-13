package com.volumeboosterapp;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import android.util.Log;
import android.content.BroadcastReceiver; // Added
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter; // Added
import android.content.SharedPreferences;
import android.media.AudioManager; // AudioManager added
import android.os.Build;
import androidx.annotation.Nullable; // Added
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
// import android.view.KeyEvent; // Removed
import java.util.Map;
import java.util.HashMap;
import com.facebook.react.bridge.WritableMap; // Added
import com.facebook.react.bridge.Arguments; // Added
import com.facebook.react.modules.core.DeviceEventManagerModule; // Added
import com.facebook.react.modules.core.PermissionAwareActivity;
import com.facebook.react.modules.core.PermissionListener;
import android.database.ContentObserver; // Added for Volume Observer
import android.os.Handler; // Added for Volume Observer
import android.provider.Settings; // Added for Volume Observer
import android.os.Looper; // Added for Main Looper
import com.facebook.react.bridge.UiThreadUtil; // Added for running on UI thread

public class AudioModule extends ReactContextBaseJavaModule {
    private static ReactApplicationContext reactContext;
    private static final String MODULE_NAME = "AudioModule";

    // Constants for SharedPreferences (same as Service)
    private static final String PREFS_NAME = "BoosterPrefs";
    private static final String PREF_KEY_BOOST_LEVEL = "boost_level_mb"; // Same as Service
    private static final String PREF_KEY_LAST_BOOST_LEVEL = "last_boost_level_mb"; // Same as Service

    private AudioManager audioManager;
    private BroadcastReceiver boostLevelReceiver;
    private ContentObserver volumeObserver; // Added Volume Observer
    private Handler volumeObserverHandler; // Added Handler for Observer

    AudioModule(ReactApplicationContext context) {
        super(context);
        reactContext = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        setupBoostLevelReceiver();
        setupVolumeObserver(); // Setup the volume observer
        Log.d(MODULE_NAME, "AudioModule initialized.");
    }

    // Function to send event to React Native
    private void sendEvent(String eventName, @Nullable WritableMap params) {
        if (reactContext.hasActiveCatalystInstance()) {
            // Log before emitting
            Log.d(MODULE_NAME, "Sending event: " + eventName + " Params: " + (params != null ? params.toString() : "null"));
            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, params);
        } else {
             Log.w(MODULE_NAME, "Could not send event, Catalyst instance not active: " + eventName);
        }
    }

    // Setup and register BroadcastReceiver
    private void setupBoostLevelReceiver() {
        boostLevelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BoosterService.ACTION_BOOST_LEVEL_UPDATED.equals(intent.getAction())) {
                    int newLevel = intent.getIntExtra(BoosterService.EXTRA_NEW_BOOST_LEVEL, -1);
                    if (newLevel != -1) {
                        Log.d(MODULE_NAME, "ACTION_BOOST_LEVEL_UPDATED broadcast received. New Level: " + newLevel);
                        WritableMap params = Arguments.createMap();
                        params.putInt("boostLevel", newLevel);
                        sendEvent("BoostLevelChanged", params);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BoosterService.ACTION_BOOST_LEVEL_UPDATED);

        // For Android Tiramisu (API 33) and above, RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED must be specified
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(reactContext, boostLevelReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } else {
            // Flag not required for older versions
            reactContext.registerReceiver(boostLevelReceiver, filter);
        }
         Log.d(MODULE_NAME, "BoostLevelReceiver registered.");
    }

    // Setup and register ContentObserver for system volume changes
    private void setupVolumeObserver() {
        volumeObserverHandler = new Handler(Looper.getMainLooper()); // Use Main Looper
        volumeObserver = new ContentObserver(volumeObserverHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                try {
                    int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    Log.d(MODULE_NAME, "System Volume Changed (Observer). Current Volume: " + currentVolume);
                    final WritableMap params = Arguments.createMap();
                    params.putInt("systemVolume", currentVolume);
                    // Ensure sendEvent is called on the UI thread
                    UiThreadUtil.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            sendEvent("SystemVolumeChanged", params);
                        }
                    });
                } catch (Exception e) {
                    Log.e(MODULE_NAME, "Error processing volume change in observer", e);
                }
            }
        };

        reactContext.getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver);
        Log.d(MODULE_NAME, "VolumeObserver registered.");

        // Immediately send the current volume after registering the observer
        try {
            int initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            Log.d(MODULE_NAME, "Sending initial volume after observer setup: " + initialVolume);
            final WritableMap initialParams = Arguments.createMap();
            initialParams.putInt("systemVolume", initialVolume);
            // Ensure sendEvent is called on the UI thread
            UiThreadUtil.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    sendEvent("SystemVolumeChanged", initialParams);
                }
            });
        } catch (Exception e) {
             Log.e(MODULE_NAME, "Error sending initial volume after observer setup", e);
        }
    }


    @NonNull
    @Override
    public String getName() {
        return MODULE_NAME;
    }

    // Toggle boost (between 0 and last level)
    @ReactMethod
    public void toggleBoost(Promise promise) {
        try {
            Log.d(MODULE_NAME, "toggleBoost called (toggle intent will be sent).");
            Intent serviceIntent = new Intent(getReactApplicationContext(), BoosterService.class);
            serviceIntent.setAction(BoosterService.ACTION_TOGGLE_BOOST);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getReactApplicationContext().startForegroundService(serviceIntent);
            } else {
                getReactApplicationContext().startService(serviceIntent);
            }
            Log.d(MODULE_NAME, "ACTION_TOGGLE_BOOST intent sent to BoosterService.");

            // Note: We no longer calculate the expected level here.
            // The JS side will rely on the BoostLevelChanged event.
            // SharedPreferences prefs = getReactApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // int currentLevel = prefs.getInt(PREF_KEY_BOOST_LEVEL, 0);
            // int lastLevel = prefs.getInt(PREF_KEY_LAST_BOOST_LEVEL, BoosterService.DEFAULT_BOOST_LEVEL);
            // int expectedNewLevel = (currentLevel > 0) ? 0 : lastLevel;
            promise.resolve(true); // Indicate intent was sent successfully

        } catch (Exception e) {
            Log.e(MODULE_NAME, "Error sending toggle intent to BoosterService.", e);
            promise.reject("E_SERVICE_INTENT", "Could not send toggle command to service.", e);
        }
    }

    // Set boost level
    @ReactMethod
    public void setBoostLevel(int level, Promise promise) {
        try {
            Log.d(MODULE_NAME, "setBoostLevel called. Requested Level: " + level + " mB");
            Intent serviceIntent = new Intent(getReactApplicationContext(), BoosterService.class);
            serviceIntent.setAction(BoosterService.ACTION_SET_BOOST_LEVEL);
            serviceIntent.putExtra(BoosterService.EXTRA_BOOST_LEVEL, level);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getReactApplicationContext().startForegroundService(serviceIntent);
            } else {
                getReactApplicationContext().startService(serviceIntent);
            }
            Log.d(MODULE_NAME, "ACTION_SET_BOOST_LEVEL intent sent to BoosterService.");

            promise.resolve(level);

        } catch (Exception e) {
            Log.e(MODULE_NAME, "Error sending set level intent to BoosterService.", e);
            promise.reject("E_SERVICE_INTENT", "Could not send set level command to service.", e);
        }
    }

     // Get boost level
     @ReactMethod
     public void getBoostLevel(Promise promise) {
         try {
             SharedPreferences prefs = getReactApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
             int level = prefs.getInt(PREF_KEY_BOOST_LEVEL, 0);
             Log.d(MODULE_NAME, "getBoostLevel called. Level read from SharedPreferences: " + level + " mB");
             promise.resolve(level);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Error reading SharedPreferences.", e);
             promise.reject("E_PREFS_READ", "Could not read boost level.", e);
         }
     }

     // Get last active Boost level (saved for Toggle)
     @ReactMethod
     public void getLastActiveBoostLevel(Promise promise) {
         try {
             SharedPreferences prefs = getReactApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
             // BoosterService.DEFAULT_BOOST_LEVEL is used by default
             int level = prefs.getInt(PREF_KEY_LAST_BOOST_LEVEL, BoosterService.DEFAULT_BOOST_LEVEL);
             Log.d(MODULE_NAME, "getLastActiveBoostLevel called. Level read from SharedPreferences: " + level + " mB");
             promise.resolve(level);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Error reading SharedPreferences (last active).", e);
             promise.reject("E_PREFS_READ_LAST", "Could not read last active boost level.", e);
         }
     }

     // Get maximum Boost level (from BoosterService)
     @ReactMethod
     public void getMaxBoostLevel(Promise promise) {
         try {
             // Access BoosterService.MAX_BOOST_LEVEL constant
             promise.resolve(BoosterService.MAX_BOOST_LEVEL);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Could not get maximum boost level.", e);
             promise.reject("E_GET_MAX_BOOST", "Could not get maximum boost level.", e);
         }
     }


     // --- System Volume Methods ---

     @ReactMethod
     public void getSystemVolume(Promise promise) {
         if (audioManager == null) {
             promise.reject("E_AUDIO_MANAGER", "AudioManager could not be initialized.");
             return;
         }
         try {
             int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
             promise.resolve(currentVolume);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Could not get system volume level.", e);
             promise.reject("E_GET_VOLUME", "Could not get system volume level.", e);
         }
     }

     @ReactMethod
     public void getMaxSystemVolume(Promise promise) {
         if (audioManager == null) {
             promise.reject("E_AUDIO_MANAGER", "AudioManager could not be initialized.");
             return;
         }
         try {
             int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
             promise.resolve(maxVolume);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Could not get maximum system volume level.", e);
             promise.reject("E_GET_MAX_VOLUME", "Could not get maximum system volume level.", e);
         }
     }

     @ReactMethod
     public void setSystemVolume(int volume, Promise promise) {
         if (audioManager == null) {
             promise.reject("E_AUDIO_MANAGER", "AudioManager could not be initialized.");
             return;
         }
         try {
             int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
             int safeVolume = Math.max(0, Math.min(volume, maxVolume));

             audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, safeVolume, AudioManager.FLAG_SHOW_UI);
             promise.resolve(safeVolume);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Could not set system volume level.", e);
             promise.reject("E_SET_VOLUME", "Could not set system volume level.", e);
         }
     }

     // --- Media Control Methods Removed ---
     // isMusicActive method removed
     // sendMediaKeyEvent method removed


     // --- Service Control Methods ---

     @ReactMethod
     public void startBoosterService(Promise promise) {
         try {
             Intent serviceIntent = new Intent(getReactApplicationContext(), BoosterService.class);
             serviceIntent.setAction(BoosterService.ACTION_START_SERVICE);
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 getReactApplicationContext().startForegroundService(serviceIntent);
             } else {
                 getReactApplicationContext().startService(serviceIntent);
             }
             Log.d(MODULE_NAME, "BoosterService start command sent.");
             promise.resolve(true);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Could not start BoosterService.", e);
             promise.reject("E_SERVICE_START", "Could not start BoosterService.", e);
         }
     }

     @ReactMethod
     public void stopBoosterService(Promise promise) {
          try {
              Intent serviceIntent = new Intent(getReactApplicationContext(), BoosterService.class);
              serviceIntent.setAction(BoosterService.ACTION_STOP_SERVICE);
              getReactApplicationContext().startService(serviceIntent);
              Log.d(MODULE_NAME, "BoosterService stop command sent.");
              promise.resolve(true);
          } catch (Exception e) {
              Log.e(MODULE_NAME, "Could not stop BoosterService.", e);
              promise.reject("E_SERVICE_STOP", "Could not stop BoosterService.", e);
          }
     }

     // --- Notification Permission ---

     private static final int POST_NOTIFICATION_PERMISSION_REQUEST_CODE = 123;

     @ReactMethod
     public void requestNotificationPermission(Promise promise) {
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
             promise.resolve(true);
             return;
         }

         Context context = getReactApplicationContext();
         if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
             promise.resolve(true);
             return;
         }

         PermissionAwareActivity activity = (PermissionAwareActivity) getCurrentActivity();
         if (activity == null) {
             promise.reject("E_ACTIVITY_NOT_FOUND", "Activity not found.");
             return;
         }

         activity.requestPermissions(
                 new String[]{Manifest.permission.POST_NOTIFICATIONS},
                 POST_NOTIFICATION_PERMISSION_REQUEST_CODE,
                 new PermissionListener() {
                     @Override
                     public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                         if (requestCode == POST_NOTIFICATION_PERMISSION_REQUEST_CODE) {
                             if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                                 promise.resolve(true);
                             } else {
                                 promise.resolve(false);
                             }
                             return true;
                         }
                         return false;
                     }
                 }
         );
     }

     // Exit App
     @ReactMethod
     public void exitApp(Promise promise) {
         try {
             Log.d(MODULE_NAME, "exitApp called.");
             Intent serviceIntent = new Intent(getReactApplicationContext(), BoosterService.class);
             serviceIntent.setAction(BoosterService.ACTION_EXIT_APP);

             // Start service to deliver the exit command
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 getReactApplicationContext().startForegroundService(serviceIntent);
             } else {
                 getReactApplicationContext().startService(serviceIntent);
             }
             Log.d(MODULE_NAME, "ACTION_EXIT_APP intent sent to BoosterService.");
             promise.resolve(true);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Error sending exit intent to BoosterService.", e);
             promise.reject("E_SERVICE_INTENT_EXIT", "Could not send exit command to service.", e);
         }
     }

     // Force notification update
     @ReactMethod
     public void forceUpdateNotification(Promise promise) {
         try {
             Log.d(MODULE_NAME, "forceUpdateNotification called.");
             Intent serviceIntent = new Intent(getReactApplicationContext(), BoosterService.class);
             // Use a new action defined in BoosterService
             serviceIntent.setAction(BoosterService.ACTION_UPDATE_NOTIFICATION);

             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 getReactApplicationContext().startForegroundService(serviceIntent);
             } else {
                 getReactApplicationContext().startService(serviceIntent);
             }
             Log.d(MODULE_NAME, "ACTION_UPDATE_NOTIFICATION intent sent to BoosterService.");
             promise.resolve(true);
         } catch (Exception e) {
             Log.e(MODULE_NAME, "Error sending update notification intent to BoosterService.", e);
             promise.reject("E_SERVICE_INTENT_UPDATE", "Could not send update notification command to service.", e);
         }
     }

    // Send constants to React Native
    @Override
    public Map<String, Object> getConstants() {
        // Media key codes removed
        final Map<String, Object> constants = new HashMap<>();
        // Other constants can be added here if needed
        return constants;
    }


    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
        // Unregister receiver
        if (boostLevelReceiver != null) {
            try {
                reactContext.unregisterReceiver(boostLevelReceiver);
                 Log.d(MODULE_NAME, "BoostLevelReceiver unregistered.");
            } catch (IllegalArgumentException e) {
                 Log.w(MODULE_NAME, "Could not unregister BoostLevelReceiver, might already be unregistered.", e);
            }
        }
        // Unregister volume observer
        if (volumeObserver != null) {
             try {
                 reactContext.getContentResolver().unregisterContentObserver(volumeObserver);
                 Log.d(MODULE_NAME, "VolumeObserver unregistered.");
             } catch (Exception e) {
                 Log.w(MODULE_NAME, "Could not unregister VolumeObserver.", e);
             }
        }
        Log.d(MODULE_NAME, "AudioModule onCatalystInstanceDestroy called.");
    }
}
