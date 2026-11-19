# Voxy Cast architecture

## First connection path

Android sender
  MediaProjection
      |
  WebRTC video track
      |
  PeerConnection ---- SDP / ICE ---- Signaling server
      |                                  |
      +---------- encrypted media ------+
                                         |
Browser receiver <----------------------+
  RTCPeerConnection -> HTMLVideoElement

The signaling service exchanges pairing and session-negotiation messages. It is not a media relay and does not receive the screen video. WebRTC encrypts media in transit. When peers cannot establish a direct path, a TURN relay is needed; that relay is not included in this first milestone.

## Pairing lifecycle

1. The Android sender opens a WebSocket connection at /signal and sends create.
2. The server allocates a temporary six-digit code and associates it with that sender socket.
3. The browser receiver sends join with the code.
4. The server pairs one receiver with the sender and notifies the sender.
5. Sender and receiver exchange SDP offers/answers and ICE candidates through the signaling socket.
6. Video flows over the WebRTC peer connection.
7. Either side can leave; rooms expire after inactivity.

The server validates message shapes and lengths, limits pairing attempts by source IP, and never persists room state to disk. This is a development pairing system, not enterprise identity or access management.

## Planned transport adapters

- WebRTC: first transport for our own sender and receiver apps.
- Miracast / Wi-Fi Display: separate adapter on compatible hardware and operating systems.
- Google Cast: supported media/cast workflows, not a universal full-screen API.
- Apple screen-sharing APIs: platform-specific sender/receiver integration.
- USB / wired: platform-specific transport when the host OS exposes an appropriate capture and network path.

These are separate adapters because device discovery, permission models, codec negotiation and remote-input permissions differ by platform.

## Low-end adaptation strategy

The future adaptation loop should sample measured encode time, dropped frames, network RTT, packet loss, available bitrate, decoder performance and thermal signals where the OS exposes them. It should prefer changing bitrate first, then frame rate, then resolution, with hysteresis to avoid oscillation. Quality labels are targets, not guarantees.