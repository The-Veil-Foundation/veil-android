<p align="center">
  <img src=".github/assets/logo.png" width="128" height="128" alt="Veil">
</p>

<h1 align="center">Veil for Android</h1>

<p align="center">
  A fast, native VPN client for Android and Android TV.<br>
  Free and open source.
</p>

<p align="center">
  <a href="https://faustyu1.github.io/veil/">Website</a> ·
  <a href="https://github.com/The-Veil-Foundation/veil-macos">macOS</a> ·
  <a href="https://github.com/The-Veil-Foundation/veil-ios">iOS</a> ·
  <a href="https://github.com/The-Veil-Foundation/veil-android/releases">Download</a> ·
  <a href="https://github.com/The-Veil-Foundation/veil-android/issues">Issues</a>
</p>

<p align="center">
  <a href="https://github.com/The-Veil-Foundation/veil-android/releases/latest"><img src="https://img.shields.io/github/v/release/The-Veil-Foundation/veil-android?include_prereleases" alt="Release"></a>
  <a href="https://www.gnu.org/licenses/agpl-3.0"><img src="https://img.shields.io/badge/License-AGPL_v3-blue.svg" alt="License: AGPL v3"></a>
  <a href="https://github.com/The-Veil-Foundation/veil-android/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/The-Veil-Foundation/veil-android/ci.yml?branch=main&label=build" alt="Build"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin Multiplatform">
  <img src="https://img.shields.io/badge/core-Xray-orange" alt="Core: Xray">
  <img src="https://img.shields.io/badge/sing--box-bundled-brightgreen" alt="sing-box: bundled">
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

Veil for Android is a Kotlin Multiplatform + Jetpack Compose port of
[veil-ios](https://github.com/The-Veil-Foundation/veil-ios), built on
[Xray-core](https://github.com/XTLS/Xray-core) and
[sing-box](https://github.com/SagerNet/sing-box). The engine is picked automatically per
protocol — no manual core switch. Material 3 Expressive UI, subscription profiles, one-tap
connect, real node verification with automatic failover, phone-to-TV transfer, and a tunnel
with no tun2socks and no extra hops in the data path.

> **Status: alpha (`0.2.0-alpha`).** Local release builds have been installed on a physical
> Samsung SM-S916B. Earlier testing used a Galaxy S20 FE with live VLESS/REALITY
> subscriptions. Both engines are bundled; not every protocol or UI gesture has been
> verified on a device. See the [changelog](CHANGELOG.md) for unpublished changes.

> **Disclaimer.** Veil is a client for proxy protocols intended for privacy, development,
> and lawful circumvention of censorship. You are responsible for complying with the laws
> and terms of service that apply to you. The bundled core is third-party software under
> its own licence.

## Development updates

- Experimental [internet sharing](docs/internet-sharing.md) through Shizuku or an
  authenticated proxy. Active sharing retains the hotspot and credentials during
  VPN server/core switches; traffic pauses briefly. Disconnect, pause or VPN failure
  stops sharing. Reconnection behavior still needs hardware validation.
- Android TV: server action buttons for remotes, improved text scrolling, focus
  entry in settings and theme-colored screen margins. ARMv7 builds have been
  installed on an Android 11 TV box; final visual validation is pending.
- Subscription refresh preserves stable node IDs, isolates sources and updates
  imported groups. Translation dictionaries load on demand; unchanged logs skip
  repeated masking, with masking performed off the UI thread.
- Release APKs use code/resource shrinking and ABI splits. Optional extra ZIP
  compression via `Scripts/recompress-apk.py` produces an **unsigned** APK that
  must be aligned and signed again; gains are modest because native cores dominate.

## Install

Grab an APK from the [latest release](https://github.com/The-Veil-Foundation/veil-android/releases/latest):

| File | For |
| --- | --- |
| `veil-<version>-armeabi-v7a.apk` | 32-bit ARMv7 phones and Android TV boxes |
| `veil-<version>-arm64-v8a.apk` | phones and most Android TV boxes — the one you need |
| `veil-<version>-x86_64.apk` | emulators and x86 devices |

Allow installing from unknown sources when Android asks. Requires Android 7.0 (API 24) or
later; 32-bit ARMv7 devices are supported. The app checks GitHub Releases for updates
itself (*Settings → Check for updates*).

## Features

### Protocols

| Engine | Protocols | Transports & security | Status |
| --- | --- | --- | --- |
| **Xray-core** | VLESS, VMess, Trojan, Shadowsocks, WireGuard | REALITY, TLS, XTLS Vision, XHTTP, uTLS fingerprints | ✅ automatic |
| **sing-box** | Hysteria2, TUIC, AnyTLS | QUIC-based and modern transports | ✅ automatic |

There is no core switcher: the engine follows the selected node's protocol. REALITY nodes stay
on Xray — sing-box 1.12 cannot complete REALITY authentication with newer Xray servers yet.
Hysteria2, TUIC and AnyTLS run on the bundled sing-box libbox; they have not been verified
against live servers yet (the test subscription contained none).

### Subscriptions

- Plain share links, base64 lists, ready-made Xray config arrays and **Mihomo / Clash YAML**
  (`proxies:` plus simple `select` / `url-test` groups). Nested groups, built-in
  `DIRECT`/`REJECT` members and provider/filter-based groups are reported as unsupported;
  Mihomo routing rules are not imported. Imported groups are available in Routing → Groups;
  importing them does not automatically change your default route.
- **Happ** (`happ://`) and **INCY** (`incy://`) share links.
- Xray configs with a balancer show how many outbounds the panel folded into the node.
- Manual, per-group and scheduled refresh.
- Reads `subscription-userinfo`, so each subscription card shows traffic used and expiry.
- Rename servers and subscriptions from their action sheets. Custom names are stored
  separately from panel data and retained across subscription refreshes.

### Node verification and failover

- Before connecting, a second, independent core instance performs a **real HTTPS request**
  through the node — catching dead ports *and* bad credentials.
- If the selected node fails, the fastest alternatives from the same subscription are probed
  and used automatically.
- Switching servers keeps the live tunnel up while the candidate is tested; the session timer
  survives.

### Servers

- **Action sheet** per server and subscription — connect, ping, copy or share the link, show a
  QR, refresh or delete the subscription.
- **Node details** — protocol, address, transport and security, with keys hidden until you tap
  *Show keys*.
- **Three ping modes** — TCP handshake (fast), ICMP (like `ping`), or a **real** request through
  the server (what apps actually feel). Ping one node or a whole group.

### Share and transfer

- **Share links and QR codes** for any node, rebuilt from its config (`ShareLink`, the inverse
  of the parser).
- **QR from image** — Add → QR from image opens Android's file picker. The decoded text
  is placed in the input for review; nothing is imported or fetched until you confirm.
  Supports normal, inverted and transparent-background QR images. Files are limited to
  16 MB and downsampled to at most 2048 pixels per side; crop around small codes if needed.
  Does not require camera or broad gallery access.
- **Phone → TV transfer.** The TV box shows a QR with its LAN address; the phone scans it and
  sends its subscriptions and manual servers over the local network. Only private
  (`10/8`, `172.16/12`, `192.168/16`) addresses are accepted, so a forged QR cannot make the
  phone send them to a server on the internet.
- **System share sheet** for sending anything to another app.

### Network

- **Split tunneling** — everything through VPN, everything *except* selected apps, or *only*
  selected apps, with search.
- **Pause** — closes the tunnel so traffic goes directly, while the app, notification and
  session stay alive; resume recreates the TUN and reconnects.
- **IPv6** in the tunnel or around it, adjustable **MTU**.
- **RU bypass** — `.ru` / `.su` / `.рф` go direct.
- **DPI fragmentation** — TLS ClientHello fragmentation via Xray `finalmask`.
- **Routing and DNS editors** — presets, ordered rules, server groups, community lists
  and geo-database management. Engine-specific behavior still needs broader device testing.

### Everything else

- **Material 3 Expressive** — spring motion, emphasized typography, pill chips and a floating
  navigation bar in portrait that becomes a side rail in landscape and on TV. Content is kept
  clear of display cutouts.
- **Live UI** — download/upload speed, session time, pulsing connection state, floating toasts.
- **Cleaner server screen** — animated search, no Veil logo/title in the header, larger
  button logos and a compact connection card without a duplicate left-hand logo.
- **Predictive back** — gesture-driven transitions with a previous-screen preview for tabs,
  routing subpages, subscription details, logs, split tunneling and transfer screens.
  Uses Android's predictive-back events; gesture behavior still needs device validation.
- **Settings** — one scrollable list, with app/core logs and a privacy-safe diagnostic summary.
- **Notification / Live Updates** — session timer, pause/resume and disconnect. Requests an
  Android 16+ status chip; Samsung Now Bar / ColorOS Live Alerts availability depends on
  firmware, permissions and OEM eligibility rules. Falls back to a regular notification.
- **QR scanner** — camera2 + ZXing, no Google Play Services required; autofocus, torch and
  high-resolution analysis for small codes.
- **Android TV** — launcher entry, banner, full D-pad navigation and a *Receive from another
  device* screen.
- **Pitch Black** — true-black theme for OLED screens.
- **App and core logs** — separate App, Tunnel and Checks views, filtering, copy and clear.
  The app log is an in-memory session log, capped at 500 events; it is not a crash archive.
- **12 language choices** — RU/EN plus the macOS translation tables for Chinese, Spanish,
  Hindi, Arabic, French, Portuguese, German, Japanese, Indonesian and Turkish. Arabic uses
  RTL layout. Android-only or dynamic strings without a matching translation fall back to
  English; see [translation coverage](docs/translations.md).
- **Over-the-air updates** — straight from GitHub Releases.

## How it works

```
apps ──► Android routing ──► TUN (VpnService.establish())
                                │ fd
                                ▼
                Xray `tun` inbound (gVisor) ── or ── sing-box `tun` (libbox)
                                │ rules, sniffing, DNS
                                ▼
   VLESS / VMess / Trojan / SS / WireGuard / Hysteria2 / TUIC / AnyTLS
                                │
                                ▼
                              server
```

`VpnService.establish()` returns a `ParcelFileDescriptor`. Xray publishes its number through
`xray.tun.fd`, and `proxy/tun/tun_android.go` inside Xray-core attaches to it directly. The
sing-box bridge does the same for libbox. Either way there is no tun2socks, no second core and
no local SOCKS hop.

<details>
<summary>Details</summary>

- The core runs inside the app process, so the package is excluded from the tunnel
  (`Builder.addDisallowedApplication`) — otherwise its sockets to the server would loop back
  into the same TUN.
- On a server switch the service recreates the TUN and hands the core a **duplicated**
  descriptor: gVisor closes the fd when the core stops, so reusing it would leave a
  "connected" tunnel that silently refuses every connection.
- Pause closes the TUN and stops the core, so traffic goes out directly while the foreground
  service keeps running; resume creates a fresh interface and starts the core again.

</details>

## Build from source

Requires JDK 21 and Android SDK 36. NDK r28c or newer and Go 1.27+ are only needed to rebuild the cores.

```bash
git clone https://github.com/The-Veil-Foundation/veil-android.git && cd veil-android

./gradlew :composeApp:assembleDebug    # APK
```

The repository ships a prebuilt combined gomobile AAR, `composeApp/libs/veil-core.aar`
(Xray-core and sing-box libbox merged under one `go.Seq`), so a clean clone builds without Go.
`Scripts/build-core-android.sh` rebuilds both cores together for `armeabi-v7a`,
`arm64-v8a`, and `x86_64`. Set `ANDROID_HOME` and `ANDROID_NDK_HOME` before running it.
The dependency versions are pinned in `CoreBridge/go.mod`.
Go 1.27 builds use `http2legacy` for sing-box's HTTP/2 compatibility and a local QPACK
adapter for the combined dependency graph. Details: [`CoreBridge/README.md`](CoreBridge/README.md).

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:assembleRelease -PabiSplits
```

Release APKs are written to `composeApp/build/outputs/apk/release/` as
`composeApp-<abi>-release.apk`. Published release assets use `veil-<version>-<abi>.apk`.
Updating an existing installation requires the same signing key; do not uninstall if
you need to preserve its data.

<details>
<summary>Releases and signing</summary>

Releases are built **manually**: *Actions → Release → Run workflow*, with a tag such as
`v0.1.1` that must match the `VERSION` file (the single source of the app version). Nothing runs on push or tags. A build-only check
runs the same way via *Actions → CI*.

ABI splits are enabled with `-PabiSplits`, so a plain `assembleDebug` still builds one APK.
The core ships for `armeabi-v7a`, `arm64-v8a`, and `x86_64`.

For a release key set `VEIL_KEYSTORE`, `VEIL_KEYSTORE_PASSWORD`, `VEIL_KEY_ALIAS` and
`VEIL_KEY_PASSWORD` (or the matching repository secrets); otherwise the debug key is used.

Step-by-step guide: [`docs/release.md`](docs/release.md) (Russian).

</details>

<details>
<summary>Logs</summary>

In the app: *Settings → App and core logs*. App events are kept only for the current process.
Core files live in
`Android/data/org.veil.app/files/xray.log` and `xray.config.json` and can be pulled with
`adb` (`run-as` fails on Samsung due to SELinux). The log is truncated on every new connection.
The log viewer and its copy action mask known credentials, URLs, hosts and IP addresses.
Original core files and config exports are not rewritten and can still contain secrets.
Masking is best-effort: review any diagnostic material before sharing.
*Share diagnostics* exports only aggregate server/protocol counts and the app
version, not raw logs, profiles, URLs or device IDs.

</details>

## Porting status

| Layer | Source (Swift) | Status |
| --- | --- | --- |
| Server model | `Models/ProxyConfig.swift` | ✅ ported |
| Link parsing | `Core/LinkParser.swift` | ✅ ported, tested |
| Subscriptions | `Core/SubscriptionPayload.swift` | ✅ links, base64, Xray arrays, `X-Hwid` |
| Xray config builder | `Core/XrayConfigBuilder.swift` | ✅ ported, tested |
| sing-box engine | — | ✅ runtime bundled: Hysteria2, TUIC, AnyTLS; REALITY stays on Xray |
| TUN + core | `Tunnel/PacketTunnelProvider.swift` | ✅ on `VpnService` |
| Go bridge | `XrayBridge/xray.go` | ✅ reused + probe instance |
| UI | `App/Views/*` | ✅ servers, subscriptions, settings, split tunnel, logs, transfer |
| Share links | — | ✅ `ShareLink`, inverse of the parser |
| `urltest` groups, selectors | — | Implemented in both builders; simple Mihomo groups imported |
| Routing rules editor, DNS editor | — | Implemented; cross-engine parity still under review |
| Config inspector, hidden keys | — | Implemented; viewed/copied logs masked, original files remain raw |
| Localization tables | — | 12 choices; 5,460 macOS translations imported, Android-specific gaps fall back to English |

More in [`docs/porting.md`](docs/porting.md).

## Project layout

```
composeApp/
├── libs/veil-core.aar   prebuilt Xray + sing-box cores (one gomobile AAR)
└── src/
    ├── commonMain/kotlin/dev/local/veil/
    │   ├── core/      ProxyConfig, LinkParser, XrayConfig, SingboxConfig, AppSettings
    │   ├── platform/  AppContainer, VpnController, LogProvider, UpdateService, Device
    │   └── ui/        App, screens, components, theme, motion
    ├── androidMain/kotlin/
    │   ├── dev/local/veil/MainActivity.kt
    │   ├── dev/local/veil/platform/  VeilVpnService, XrayBridge, EndpointResolver, AndroidUpdateService
    │   └── singbox/   Singbox (libbox bridge)
    ├── desktopMain/   desktop stubs (no core)
    └── commonTest/    parser and config builder tests
XrayBridge/        Go bridge (gomobile), probe instance support
Scripts/           core build script
docs/              porting notes, release guide
```

## Other platforms

- **macOS** — [veil-macos](https://github.com/The-Veil-Foundation/veil-macos)
- **iOS / iPadOS** — [veil-ios](https://github.com/The-Veil-Foundation/veil-ios)

## Acknowledgements

- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — proxy engine
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box) — second engine
- [ZXing](https://github.com/zxing/zxing) — QR decoding
- [JetBrains Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — UI

## Star History

<a href="https://star-history.com/#The-Veil-Foundation/veil-android&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=The-Veil-Foundation/veil-android&type=Date&theme=dark">
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=The-Veil-Foundation/veil-android&type=Date">
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=The-Veil-Foundation/veil-android&type=Date">
  </picture>
</a>

## License

Veil for Android is free software, licensed under the **GNU Affero General Public License
v3.0** — see [LICENSE](LICENSE). It inherits the licence of the original project.
[Xray-core](https://github.com/XTLS/Xray-core) is MPL-2.0, which is compatible with AGPLv3;
any distributed build must be accompanied by its source.
