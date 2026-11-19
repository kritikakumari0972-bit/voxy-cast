"use strict";

const http = require("node:http");
const path = require("node:path");
const express = require("express");
const { WebSocket, WebSocketServer } = require("ws");
const { RoomRegistry } = require("./rooms");

const PORT = Number.parseInt(process.env.PORT || "3000", 10);
const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({
  noServer: true,
  maxPayload: 256 * 1024,
  perMessageDeflate: false
});
const rooms = new RoomRegistry();
const joinAttempts = new Map();

app.disable("x-powered-by");
app.use((req, res, next) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("X-Frame-Options", "DENY");
  next();
});
app.get("/health", (_req, res) => res.json({ ok: true, service: "voxy-cast-signaling" }));
app.use(express.static(path.resolve(__dirname, "../../web-receiver"), {
  extensions: ["html"],
  index: "index.html"
}));

function send(ws, payload) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload));
}

function allowJoin(ip) {
  const now = Date.now();
  const entry = joinAttempts.get(ip) || { count: 0, resetAt: now + 60_000 };
  if (now >= entry.resetAt) {
    entry.count = 0;
    entry.resetAt = now + 60_000;
  }
  entry.count += 1;
  joinAttempts.set(ip, entry);
  return entry.count <= 12;
}

wss.on("connection", (ws, request) => {
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });
  ws.on("message", (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      send(ws, { type: "error", code: "invalid-json" });
      return;
    }
    if (!message || typeof message.type !== "string") {
      send(ws, { type: "error", code: "invalid-message" });
      return;
    }
    if (message.type === "create") {
      if (ws.roomCode) {
        send(ws, { type: "error", code: "already-in-room" });
        return;
      }
      try {
        send(ws, { type: "room-created", code: rooms.create(ws) });
      } catch {
        send(ws, { type: "error", code: "room-create-failed" });
      }
      return;
    }
    if (message.type === "join") {
      const ip = request.socket.remoteAddress || "unknown";
      if (!allowJoin(ip)) {
        send(ws, { type: "error", code: "too-many-attempts" });
        ws.close(1008, "Too many pairing attempts");
        return;
      }
      const code = typeof message.code === "string" ? message.code : "";
      if (!/^\d{6}$/.test(code)) {
        send(ws, { type: "error", code: "invalid-code" });
        return;
      }
      const room = rooms.join(code, ws);
      if (!room) {
        send(ws, { type: "error", code: "room-unavailable" });
        return;
      }
      send(ws, { type: "joined" });
      send(room.sender, { type: "peer-joined" });
      return;
    }
    if (message.type === "leave") {
      rooms.detach(ws);
      send(ws, { type: "left" });
      return;
    }
    if (["offer", "answer", "ice-candidate"].includes(message.type)) {
      if (!rooms.relay(ws, message)) send(ws, { type: "error", code: "signal-rejected" });
      return;
    }
    if (message.type !== "pong") send(ws, { type: "error", code: "unsupported-message" });
  });
  ws.on("close", () => rooms.detach(ws));
  ws.on("error", () => rooms.detach(ws));
});

server.on("upgrade", (request, socket, head) => {
  const pathname = new URL(request.url || "/", "http://localhost").pathname;
  if (pathname !== "/signal") {
    socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(request, socket, head, (ws) => wss.emit("connection", ws, request));
});

const maintenanceTimer = setInterval(() => {
  const now = Date.now();
  rooms.sweep(now);
  for (const [ip, entry] of joinAttempts) {
    if (entry.resetAt < now) joinAttempts.delete(ip);
  }
  for (const client of wss.clients) {
    if (client.isAlive === false) {
      client.terminate();
      continue;
    }
    client.isAlive = false;
    client.ping();
  }
}, 30_000);
maintenanceTimer.unref();

server.listen(PORT, "0.0.0.0", () => {
  console.log("Voxy Cast signaling server listening on port " + PORT);
});

function shutdown() {
  clearInterval(maintenanceTimer);
  for (const client of wss.clients) client.close(1001, "Server shutting down");
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5_000).unref();
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);