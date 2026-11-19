"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { RoomRegistry } = require("../src/rooms");

function socket() {
  return {
    readyState: 1,
    messages: [],
    send(value) { this.messages.push(JSON.parse(value)); }
  };
}

test("creates a six-digit room code and assigns the sender", () => {
  const registry = new RoomRegistry();
  const sender = socket();
  const code = registry.create(sender);

  assert.match(code, /^\d{6}$/);
  assert.equal(sender.roomCode, code);
  assert.equal(sender.role, "sender");
  assert.equal(registry.rooms.size, 1);
});

test("pairs one receiver and relays valid WebRTC signaling", () => {
  const registry = new RoomRegistry();
  const sender = socket();
  const receiver = socket();
  const code = registry.create(sender);

  assert.ok(registry.join(code, receiver));
  assert.equal(receiver.role, "receiver");
  assert.equal(registry.join(code, socket()), false);

  assert.equal(registry.relay(sender, { type: "offer", sdp: "test-sdp" }), true);
  assert.deepEqual(receiver.messages[0], { type: "offer", sdp: "test-sdp" });
});

test("rejects malformed or oversized signaling payloads", () => {
  const registry = new RoomRegistry();
  const sender = socket();
  const receiver = socket();
  const code = registry.create(sender);
  registry.join(code, receiver);

  assert.equal(registry.relay(sender, { type: "offer", sdp: 12 }), false);
  assert.equal(registry.relay(sender, { type: "answer", sdp: "x".repeat(200001) }), false);
  assert.equal(registry.relay(sender, { type: "unknown" }), false);
});

test("notifies the sender when a receiver leaves and expires rooms", () => {
  const registry = new RoomRegistry({ ttlMs: 10 });
  const sender = socket();
  const receiver = socket();
  const code = registry.create(sender);
  registry.join(code, receiver);

  registry.detach(receiver);
  assert.equal(sender.messages.at(-1).type, "peer-left");
  registry.sweep(Date.now() + 100);
  assert.equal(registry.rooms.has(code), false);
});