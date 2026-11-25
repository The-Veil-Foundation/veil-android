# Internet sharing (experimental)

Android has two separate sharing modes in Veil. Sharing is session-only: it does
not start on boot, and stops when the VPN is disconnected, paused or fails.
An already-active sharing session stays alive when switching servers or cores:
the hotspot, test network, proxy listener and session credentials are retained.
Traffic briefly stops while the authenticated loopback core ingress restarts;
existing connections may need to reconnect. Sharing still being initialized is
cancelled if a VPN switch interrupts it.
Both modes forward through the active core and honor its routing rules, including
user-selected direct/bypass rules.

## Shizuku, Android 13+

Start Shizuku using USB ADB, wireless debugging, or root. Veil checks the running
Binder and its permission, not Wi-Fi connectivity or the chosen startup method.
A Wi-Fi client connection is not necessary with USB ADB/root. A Wi-Fi hotspot is
still used to connect downstream devices.

Turn off an already-running Android Wi-Fi hotspot before starting this mode.
Veil uses a Shizuku UserService to create a test TUN, attaches a separate libbox
instance, publishes the test network, asks Android to prefer test networks and
starts Wi-Fi tethering. The bridge forwards IPv4/IPv6 TCP/UDP via an authenticated
loopback SOCKS ingress on the active Xray or sing-box core.

The status is active only after the tethering callback confirms the test TUN as
upstream. A changed upstream triggers a hotspot-stop attempt. Shutdown stops Wi-Fi
tethering before releasing the test network. If stopping fails, the test TUN is
retained as a sink and the UI tells the user to turn the hotspot off manually.
The screen does not claim leak-proof protection: platform events can be delayed,
and a process crash or firmware routing transition can briefly bypass VPN. This
uses hidden Android APIs and is intentionally labeled experimental.

Only Wi-Fi tethering is managed. Existing USB/Bluetooth tethering is not started
or stopped by Veil. The test-network preference is global, so avoid running another
test-network tethering app at the same time.

## Proxy, Android 7+

Enable the Android hotspot yourself, refresh local addresses, and select the
phone's private IPv4 address on that network. Veil binds a mixed HTTP/SOCKS5 proxy
to that address on port 10880, with a random session password and username `veil`.
The listener is closed when sharing or VPN stops. No wildcard listener is created.

Configure address, port and credentials in each client application. Only apps
that honor the proxy are covered; other traffic can go directly through Android
sharing. Some OS-level Wi-Fi proxy settings do not support authentication; use a
client that does. Proxy credentials are not persisted in settings or logs.

## Implementation and validation

- `SharingConfig`: authenticated core ingress and libbox bridge configuration.
- `AndroidInternetSharing`: session ownership, readiness, permission and lifecycle.
- `TetheringService`: shell/root process, hidden test-network and tethering APIs.
- `SharingScreen`: Material 3 UI, dock entry and controls.

`SharingConfigTest` checks authentication, loopback forwarding, retained core
routing, both IP families, and rejection of wildcard/public listener addresses.
Hardware validation must additionally cover start/stop, TCP/UDP/DNS on a second
device, IPv6, upstream changes, VPN disconnect/reconnect, and Shizuku/process death.
A successful build alone does not verify firmware-specific tethering behavior.

The shell attribution helper is adapted from v2rayNG PR #5903 (GPL-3.0):
https://github.com/2dust/v2rayNG/pull/5903
Android implementation reference:
https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/common/TetheringLib/src/android/net/TetheringManager.java
