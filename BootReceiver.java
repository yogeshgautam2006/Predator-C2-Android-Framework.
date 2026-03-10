package com.example.systemframework;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Phone start hote hi service ko trigger karega
            Intent serviceIntent = new Intent(context, MyBackgroundService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        }
    }
}