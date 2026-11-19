"use strict";

(() => {
  const form = document.getElementById("join-form");
  const codeInput = document.getElementById("pair-code");
  const connectButton = document.getElementById("connect-btn");
  const disconnectButton = document.getElementById("disconnect-btn");
  const status = document.getElementById("status");
  const statusText = document.getElementById("status-text");
  const screenPanel = document.getElementById("screen-panel");
  const video = document.getElementById("remote-video");
  const placeholder = document.getElementById("video-placeholder");
  const streamState = document.getElementById("stream-state");
  const resolutionStat = document.getElementById("stat-resolution");
  const fpsStat = document.getElementById("stat-fps");
  const connectionStat = document.getElementById("stat-connection");

  let socket = null;
  let peer = null;
  let pendingCandidates = [];
  let statsTimer = null;

  function setStatus(message, stateName = "idle") {
    statusText.textContent = message;
    status.dataset.state = stateName;
  }

  function socketUrl() {
    const url = new URL("/signal", window.location.href);
    url.protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    return url.toString();
  }

  function send(message) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  }

  function createPeer() {
    if (peer) return peer;
    peer = new RTCPeerConnection({
      iceServers: [{ urls: ["stun:stun.l.google.com:19302"] }]
    });
    peer.ontrack = (event) => {
      const stream = event.streams && event.streams[0];
      if (stream) video.srcObject = stream;
      placeholder.hidden = true;
      streamState.textContent = "Receiving video";
      video.play().catch(() => {
        streamState.textContent = "Tap the video to start playback";
      });
    };
    peer.onicecandidate = (event) => {
      if (event.candidate) {
        send({
          type: "ice-candidate",
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex
        });
      }
    };
    peer.onconnectionstatechange = () => {
      if (!peer) return;
      const stateName = peer.connectionState;
      connectionStat.textContent = stateName;
      if (stateName === "connected") setStatus("Connected — live screen incoming", "connected");
      if (stateName === "failed") setStatus("Connection failed. Try reconnecting on the same Wi-Fi.", "error");
      if (stateName === "disconnected") setStatus("Connection interrupted — trying to recover", "error");
    };
    peer.oniceconnectionstatechange = () => {
      if (peer) connectionStat.textContent = peer.iceConnectionState;
    };
    return peer;
  }

  async function applyOffer(sdp) {
    const connection = createPeer();
    await connection.setRemoteDescription({ type: "offer", sdp });
    for (const candidate of pendingCandidates) await connection.addIceCandidate(candidate);
    pendingCandidates = [];
    const answer = await connection.createAnswer();
    await connection.setLocalDescription(answer);
    send({ type: "answer", sdp: answer.sdp });
    setStatus("Pairing complete — negotiating video", "connected");
  }

  async function applyCandidate(message) {
    const candidate = new RTCIceCandidate({
      candidate: message.candidate,
      sdpMid: message.sdpMid,
      sdpMLineIndex: message.sdpMLineIndex
    });
    if (!peer || !peer.remoteDescription) pendingCandidates.push(candidate);
    else await peer.addIceCandidate(candidate);
  }

  function closeSession(sendLeave = true) {
    if (sendLeave) send({ type: "leave" });
    if (statsTimer) window.clearInterval(statsTimer);
    statsTimer = null;
    if (peer) peer.close();
    peer = null;
    pendingCandidates = [];
    if (socket) {
      socket.onclose = null;
      socket.close();
    }
    socket = null;
    video.srcObject = null;
    placeholder.hidden = false;
    screenPanel.hidden = true;
    connectButton.disabled = false;
    connectionStat.textContent = "Disconnected";
    resolutionStat.textContent = "—";
    fpsStat.textContent = "—";
  }

  function startStats() {
    if (statsTimer) window.clearInterval(statsTimer);
    statsTimer = window.setInterval(async () => {
      if (!peer) return;
      try {
        const reports = await peer.getStats();
        reports.forEach((report) => {
          if (report.type === "inbound-rtp" && report.kind === "video" && !report.isRemote) {
            const width = report.frameWidth || 0;
            const height = report.frameHeight || 0;
            if (width && height) resolutionStat.textContent = width + " × " + height;
            if (typeof report.framesPerSecond === "number") {
              fpsStat.textContent = Math.round(report.framesPerSecond) + " FPS";
            }
          }
        });
      } catch {
        // Stats are optional; streaming must continue if a browser omits them.
      }
    }, 1500);
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const code = codeInput.value.trim();
    if (!/^\d{6}$/.test(code)) {
      setStatus("Enter the six-digit code shown by the sender", "error");
      codeInput.focus();
      return;
    }
    if (!("RTCPeerConnection" in window) || !("WebSocket" in window)) {
      setStatus("This browser does not support the required real-time APIs", "error");
      return;
    }
    closeSession(false);
    connectButton.disabled = true;
    screenPanel.hidden = false;
    setStatus("Connecting to Voxy Cast…");
    socket = new WebSocket(socketUrl());
    socket.onopen = () => send({ type: "join", code });
    socket.onmessage = async (event) => {
      let message;
      try { message = JSON.parse(event.data); }
      catch {
        closeSession(false);
        setStatus("Received an invalid signaling message", "error");
        return;
      }
      try {
        if (message.type === "joined") {
          setStatus("Paired — waiting for the sender to start", "connected");
          startStats();
        } else if (message.type === "offer") {
          await applyOffer(message.sdp);
        } else if (message.type === "ice-candidate") {
          await applyCandidate(message);
        } else if (message.type === "room-closed") {
          closeSession(false);
          setStatus("The sender ended this session", "error");
        } else if (message.type === "peer-left") {
          setStatus("The sender disconnected", "error");
        } else if (message.type === "error") {
          const descriptions = {
            "invalid-code": "Enter a valid six-digit code",
            "room-unavailable": "That code is invalid, expired or already in use",
            "too-many-attempts": "Too many attempts. Wait a minute and retry."
          };
          const description = descriptions[message.code] || "Unable to pair with that device";
          closeSession(false);
          setStatus(description, "error");
        }
      } catch (error) {
        closeSession(false);
        setStatus("WebRTC setup failed: " + (error && error.message ? error.message : "unknown error"), "error");
      }
    };
    socket.onerror = () => setStatus("Signaling connection failed", "error");
    socket.onclose = () => {
      if (socket) setStatus("Signaling connection closed", "error");
      connectButton.disabled = false;
    };
  });

  disconnectButton.addEventListener("click", () => {
    closeSession(true);
    setStatus("Disconnected");
  });
  video.addEventListener("click", () => video.play().catch(() => {}));
  window.addEventListener("beforeunload", () => closeSession(true));
  codeInput.addEventListener("input", () => {
    codeInput.value = codeInput.value.replace(/\D/g, "").slice(0, 6);
  });
})();