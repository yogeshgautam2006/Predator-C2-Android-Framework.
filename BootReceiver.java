package com.example.systemframework;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * ✅ BootReceiver — Starts the service automatically when phone boots up
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "PredatorBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {

            Log.d(TAG, "📱 Boot completed — starting service");

            // ✅ WakeLock to ensure service starts before CPU sleeps
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "Predator:BootWake");
            wl.acquire(10 * 1000L);

            try {
                Intent serviceIntent = new Intent(context, MyBackgroundService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service on boot: " + e.getMessage());
            } finally {
                if (wl.isHeld()) wl.release();
            }
        }
    }
}