package com.example.systemframework;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * ✅ PersistenceWorker — WorkManager periodic task (every 15 min)
 * 
 * WHY THIS IS CRITICAL:
 * - AlarmManager.setAndAllowWhileIdle is unreliable on Xiaomi/Samsung/Oppo
 * - WorkManager is Google's GUARANTEED background scheduler
 * - It survives Doze mode, app standby, and OEM battery killers
 * - It's the ONLY API that reliably fires on ALL Android OEMs
 * 
 * This worker checks if MyBackgroundService is alive and restarts it if dead.
 * It runs every 15 minutes (Android minimum for periodic work).
 */
public class PersistenceWorker extends Worker {
    private static final String TAG = "PredatorWorker";
    public static final String WORK_NAME = "predator_persistence";

    public PersistenceWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "⏰ WorkManager fired — checking service status");

        try {
            MyBackgroundService serviceInstance = MyBackgroundService.getInstance();

            if (serviceInstance != null) {
                // Service is alive — trigger reconnect check
                Log.d(TAG, "Service is alive — triggering connection check");
                serviceInstance.onAlarmTriggered();
            } else {
                // Service is DEAD — restart it
                Log.w(TAG, "🔴 Service is DEAD — restarting via WorkManager");
                restartService();
            }
        } catch (Exception e) {
            Log.e(TAG, "WorkManager error: " + e.getMessage());
            // Even if it fails, try to restart the service
            restartService();
        }

        return Result.success();
    }

    private void restartService() {
        try {
            Context context = getApplicationContext();
            Intent serviceIntent = new Intent(context, MyBackgroundService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "✅ Service restart command sent");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart service: " + e.getMessage());
        }
    }
}
