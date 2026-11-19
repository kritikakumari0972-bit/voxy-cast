# Voxy Cast

**A low-latency, cross-platform screen-mirroring project.**

Voxy Cast is being built as a multi-platform wireless display system: native senders, browser and desktop receivers, adaptive streaming, and multiple connection transports. The project is in early development; compatibility is added platform by platform rather than assumed.

## Current development milestone

The first implementation establishes the real-time connection foundation:

- A Node.js signaling server with temporary six-digit pairing codes.
- A browser receiver that negotiates and displays an incoming WebRTC video stream.
- An Android native sender project using Android MediaProjection and WebRTC.
- No media is routed through the signaling server: WebRTC carries the media peer-to-peer when the network allows it.
- Initial video-only milestone. System audio, remote input, QR pairing, TURN relay, native desktop apps and TV integrations are not yet implemented.

## Repository layout

| Path | Purpose |
| --- | --- |
| \`android/\` | Native Android sender |
| \`signaling/\` | Pairing and WebRTC signaling server |
| \`web-receiver/\` | Browser-based receiver |
| \`docs/\` | Architecture and platform support notes |

## Run the signaling server and browser receiver

Requirements: Node.js 20 or newer.

\`\`\`bash
cd signaling
npm install
npm test
npm start
\`\`\`

Open \`http://localhost:3000\` on the receiver device. For a phone-to-computer LAN session, use the computer's LAN IP address, for example \`http://192.168.1.20:3000\`. Allow inbound TCP port 3000 in the computer's firewall.

In the Android app, enter the same base URL in the signaling-server field. The sender displays a six-digit code; enter that code in the browser receiver.

Both devices should initially be on the same Wi-Fi network. The browser and Android sender must be able to reach the signaling server.

## Android build

Open the \`android/\` directory in Android Studio and let Gradle sync. The app requires an Android device that supports MediaProjection. The user must approve screen capture for every session. See [android/README.md](android/README.md).

## Network and security notes

- The signaling service exchanges SDP and ICE candidates only; it does not record or proxy screen video.
- The initial STUN configuration is intended for development and same-network testing. Some networks require a TURN relay; remote-network reliability is not guaranteed until TURN is configured.
- For internet deployment, put the service behind HTTPS/WSS and configure a properly authenticated TURN service. Do not expose a development server over the public internet without transport security, origin policy, and abuse controls.
- Pairing codes are temporary and rate-limited, but a six-digit code is not an authentication system for sensitive environments.
- Protected apps may block capture. Voxy Cast will respect OS and app capture restrictions.

## Project principles

1. **Low-end first:** adapt resolution, frame rate and bitrate to measured device and network capability.
2. **Native APIs first:** use official capture and display APIs for each platform.
3. **Transport flexibility:** WebRTC first; add supported native casting and wired transports as separate adapters.
4. **Honest compatibility:** publish tested device/OS combinations and never claim universal support without verification.
5. **Privacy by design:** explicit capture approval, visible session state, encrypted transport and no hidden capture.

## Status

Early development — the browser receiver and signaling foundation are the first working target. Platform integrations and release builds must be tested on real hardware before being described as production-ready.