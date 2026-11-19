# Platform support matrix

This matrix describes project intent and current implementation status. A target is not considered supported until the sender and receiver path has been built and tested on real hardware.

| Platform | Sender | Receiver | Status |
| --- | --- | --- | --- |
| Android | MediaProjection + WebRTC | Planned | Sender implementation started; needs device build/test |
| Desktop browser | Planned | WebRTC browser receiver | Receiver implementation added |
| Windows native | Planned | Planned | Not started |
| macOS native | Planned | Planned | Not started |
| Linux native | Planned | Planned | Not started |
| iOS / iPadOS | ReplayKit-based sender investigation | Planned | Not started |
| Android TV | Planned | Planned | Not started |
| Other smart TVs | Manufacturer / browser dependent | Manufacturer dependent | Not started |

## Important compatibility boundaries

- Android screen capture requires an explicit user approval flow and can be blocked by secure windows or protected content.
- iOS screen broadcast requires Apple's supported ReplayKit / system broadcast mechanisms; an ordinary app cannot silently capture any screen.
- Miracast requires compatible source and receiver hardware, drivers and OS support.
- Google Cast does not provide a universal full-device mirroring API for every source device.
- Browser receiver compatibility depends on WebRTC support, autoplay policy, browser version and network conditions.
- Internet sessions behind restrictive NATs may require a properly configured TURN relay.

Voxy Cast will maintain a tested-device list as physical testing becomes available.