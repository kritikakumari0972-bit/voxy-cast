"use strict";

const { randomInt } = require("node:crypto");
const SOCKET_OPEN = 1;
const SIGNAL_TYPES = new Set(["offer", "answer", "ice-candidate"]);

class RoomRegistry {
  constructor(options = {}) {
    this.ttlMs = options.ttlMs ?? 10 * 60 * 1000;
    this.rooms = new Map();
  }

  create(sender) {
    let code = null;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const candidate = String(randomInt(0, 1_000_000)).padStart(6, "0");
      if (!this.rooms.has(candidate)) {
        code = candidate;
        break;
      }
    }
    if (!code) throw new Error("Unable to allocate a pairing code");
    const room = { code, sender, receiver: null, expiresAt: Date.now() + this.ttlMs };
    this.rooms.set(code, room);
    sender.roomCode = code;
    sender.role = "sender";
    return code;
  }

  join(code, receiver) {
    const room = this.rooms.get(code);
    if (!room || room.sender.readyState !== SOCKET_OPEN) return false;
    if (room.receiver && room.receiver.readyState === SOCKET_OPEN) return false;
    room.receiver = receiver;
    room.expiresAt = Date.now() + this.ttlMs;
    receiver.roomCode = code;
    receiver.role = "receiver";
    return room;
  }

  relay(socket, message) {
    if (!message || !SIGNAL_TYPES.has(message.type)) return false;
    const room = this.rooms.get(socket.roomCode);
    if (!room) return false;
    const peer = socket.role === "sender" ? room.receiver : room.sender;
    if (!peer || peer.readyState !== SOCKET_OPEN) return false;

    if ((message.type === "offer" || message.type === "answer") &&
        (typeof message.sdp !== "string" || message.sdp.length > 200000)) return false;
    if (message.type === "ice-candidate" &&
        (typeof message.candidate !== "string" || message.candidate.length > 20000)) return false;

    const payload = { type: message.type };
    if (message.type === "offer" || message.type === "answer") {
      payload.sdp = message.sdp;
    } else {
      payload.candidate = message.candidate;
      payload.sdpMid = typeof message.sdpMid === "string" ? message.sdpMid : null;
      payload.sdpMLineIndex = Number.isInteger(message.sdpMLineIndex) ? message.sdpMLineIndex : null;
    }
    room.expiresAt = Date.now() + this.ttlMs;
    peer.send(JSON.stringify(payload));
    return true;
  }

  detach(socket) {
    const code = socket.roomCode;
    const role = socket.role;
    socket.roomCode = null;
    socket.role = null;
    if (!code) return;
    const room = this.rooms.get(code);
    if (!room) return;

    if (role === "sender" || room.sender === socket) {
      this.send(room.receiver, { type: "room-closed" });
      this.rooms.delete(code);
      return;
    }
    if (role === "receiver" || room.receiver === socket) {
      if (room.receiver === socket) room.receiver = null;
      room.expiresAt = Date.now() + this.ttlMs;
      this.send(room.sender, { type: "peer-left" });
    }
  }

  sweep(now = Date.now()) {
    for (const [code, room] of this.rooms) {
      const senderConnected = room.sender.readyState === SOCKET_OPEN;
      const receiverConnected = room.receiver && room.receiver.readyState === SOCKET_OPEN;
      if (senderConnected && receiverConnected) continue;
      if (room.expiresAt <= now) {
        this.send(room.sender, { type: "room-closed" });
        this.send(room.receiver, { type: "room-closed" });
        this.rooms.delete(code);
      }
    }
  }

  send(socket, payload) {
    if (socket && socket.readyState === SOCKET_OPEN) socket.send(JSON.stringify(payload));
  }
}

module.exports = { RoomRegistry };