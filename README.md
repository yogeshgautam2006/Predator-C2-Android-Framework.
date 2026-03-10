# Predator-C2: Universal Android Monitoring Framework

A high-performance C2 (Command & Control) framework engineered for real-time telemetry and persistent background monitoring on **Android 14 & 15**.

## 🚀 Key Engineering Features
* **Immortal Background Service**: Utilizes `ForegroundService` (Data Sync/Remote Messaging) with API 35 compatibility.
* **Anti-Doze Logic**: Implements `AlarmManager` with `setAndAllowWhileIdle` to bypass strict Android battery optimizations.
* **Smart Heartbeat**: Custom `WakeLock` management (1-min pulse) to prevent OEM-specific process killing (Xiaomi/Samsung).
* **Real-Time Telemetry**: Powered by **Socket.io** for sub-second data transmission of SMS and system status.

## 🛠️ Tech Stack
- **Android**: Java (Native), Android SDK 35
- **Backend**: Node.js, Socket.io
- **Tunneling**: Ngrok / Cloudflare Tunnels
