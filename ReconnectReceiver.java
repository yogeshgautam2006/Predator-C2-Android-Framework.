package com.example.systemframework;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * ✅ ReconnectReceiver — Triggered by AlarmManager every 10 minutes
 * Ensures the service stays alive even in Doze mode and deep sleep
 */
public class ReconnectReceiver extends BroadcastReceiver {
    private static final String TAG = "PredatorReconnect";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "⏰ Reconnect alarm received");

        // ✅ FIX: Acquire a short WakeLock to guarantee code completes
        // Without this, CPU can sleep before startForegroundService executes
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Predator:AlarmWake");
        wl.acquire(10 * 1000L); // 10 seconds max

        try {
            MyBackgroundService serviceInstance = MyBackgroundService.getInstance();

            if (serviceInstance != null) {
                // Service is already running — just trigger reconnect check + reschedule alarm
                serviceInstance.onAlarmTriggered();
                Log.d(TAG, "Service alive — triggered reconnect check");
            } else {
                // Service was killed — restart it
                Log.w(TAG, "Service is dead — restarting");
                Intent serviceIntent = new Intent(context, MyBackgroundService.class);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to restart service: " + e.getMessage());
                }
            }
        } finally {
            // Always release the WakeLock
            if (wl.isHeld()) {
                wl.release();
            }
        }
    }
}