# Android sender

The Android app is the first native sender target.

## Build

Open this directory in Android Studio, use JDK 17, and allow Gradle sync. The first build downloads the Android Gradle Plugin, AndroidX, OkHttp and WebRTC dependencies. A command-line build can be run with Gradle 8.9:

    gradle :app:assembleDebug

## First LAN test

1. Start the signaling server on a computer connected to the same Wi-Fi as the Android device.
2. Find that computer's LAN IP address.
3. Enter its URL in the app, such as http://192.168.1.20:3000.
4. Tap Start screen mirroring and approve Android's system screen-capture prompt.
5. Open the receiver page on another device at http://192.168.1.20:3000.
6. Enter the six-digit code displayed in the Android app.

Replace the example IP with the actual computer address.

## Current limitations

- The current Android sender publishes video only; system audio capture and synchronization are not implemented yet.
- Capture is requested through Android MediaProjection and is visible to the user. Android requires fresh approval for each new capture session.
- The initial capture profile is 720p/30fps. Adaptive resolution, bitrate and frame-rate control are a later milestone.
- A public/internet session may need TURN. The first configuration contains a STUN server only.
- The manifest allows cleartext HTTP for private-LAN development. Use HTTPS/WSS for an internet deployment and remove or constrain cleartext traffic before release.
- Protected content and apps that opt out of screen capture remain protected.

The project must be built and tested on physical devices before a release APK is advertised as available.