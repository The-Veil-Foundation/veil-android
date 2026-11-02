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
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.ru.md">Русский</a>
</p>

---

Veil for Android is a Kotlin Multiplatform + Jetpack Compose port of
[veil-ios](https://github.com/The-Veil-Foundation/veil-ios), built on a single core —
[Xray-core](https://github.com/XTLS/Xray-core). Material 3 Expressive UI, subscription
profiles, one-tap connect, real node verification with automatic failover, and a tunnel
with no tun2socks and no extra hops in the data path.

> **Status: alpha (`0.1.0-alpha`).** Tested on a physical Samsung Galaxy S20 FE with live
> VLESS/REALITY subscriptions. Expect rough edges.

> **Disclaimer.** Veil is a client for proxy protocols intended for privacy, development,
> and lawful circumvention of censorship. You are responsible for complying with the laws
> and terms of service that apply to you. The bundled core is third-party software under
> its own licence.

## Install

Grab an APK from the [latest release](https://github.com/The-Veil-Foundation/veil-android/releases/latest):

| File | For |
| --- | --- |
| `veil-<version>-arm64-v8a.apk` | phones and most Android TV boxes |
| `veil-<version>-x86_64.apk` | emulators and x86 devices |
| `veil-<version>-universal.apk` | both ABIs in one file — pick this if unsure |

Allow installing from unknown sources when Android asks. Requires Android 7.0 (API 24) or
later; 32-bit devices are not supported. The app checks GitHub Releases for updates
itself (*Settings → Check for updates*).

## Features

### Protocols

| Core | Protocols | Transports & security |
| --- | --- | --- |
| **Xray-core** | VLESS, VMess, Trojan, Shadowsocks, WireGuard | REALITY, TLS, XTLS Vision, XHTTP, uTLS fingerprints |

Hysteria2, TUIC and AnyTLS links are recognised but marked unusable — they need sing-box,
and this build is deliberately Xray-only.

### Subscriptions

- Plain share links, base64 lists, ready-made Xray config arrays and **Mihomo / Clash YAML**
  (`proxies:` imported as nodes; groups and rules are not used yet).
- Manual, per-group and scheduled refresh.
- Reads `subscription-userinfo`, so each subscription card shows traffic used and expiry.

### Node verification and failover

- Before connecting, a second, independent core instance performs a **real HTTPS request**
  through the node — catching dead ports *and* bad credentials.
- If the selected node fails, the fastest alternatives from the same subscription are probed
  and used automatically.
- Switching servers keeps the live tunnel up while the candidate is tested; the session timer
  survives.

### Network

- **Split tunneling** — include or exclude apps.
- **RU bypass** — `.ru` / `.su` / `.рф` go direct.
- **DPI fragmentation** — TLS ClientHello fragmentation via Xray `finalmask`.

### Everything else

- **Live UI** — download/upload speed, session time, pulsing connection state, floating toasts.
- **QR scanner** — camera2 + ZXing, no Google Play Services required.
- **Android TV** — launcher entry, banner and full D-pad navigation.
- **Pitch Black** — true-black theme for OLED screens.
- **Core logs** — in-app viewer with copy and clear.
- **Over-the-air updates** — straight from GitHub Releases.

## How it works

```
apps ──► Android routing ──► TUN (VpnService.establish())
                                │ fd
                                ▼
                    Xray `tun` inbound (gVisor)
                                │ rules, sniffing, DNS
                                ▼
                    VLESS / VMess / Trojan / SS / WireGuard
                                │
                                ▼
                              server
```

`VpnService.establish()` returns a `ParcelFileDescriptor`. `XrayBridge` publishes its
number through `xray.tun.fd`, and `proxy/tun/tun_android.go` inside Xray-core attaches to it
directly — no tun2socks, no second core, no local SOCKS hop.

<details>
<summary>Details</summary>

- The core runs inside the app process, so the package is excluded from the tunnel
  (`Builder.addDisallowedApplication`) — otherwise its sockets to the server would loop back
  into the same TUN.
- On a server switch the service recreates the TUN and hands the core a **duplicated**
  descriptor: gVisor closes the fd when the core stops, so reusing it would leave a
  "connected" tunnel that silently refuses every connection.

</details>

## Build from source

Requires JDK 21, Android SDK 36, NDK 29, and Go 1.26 (only for the core).

```bash
git clone https://github.com/The-Veil-Foundation/veil-android.git && cd veil-android

Scripts/build-xray-android.sh          # core, once (~10–20 min) → composeApp/libs/xray.aar
./gradlew :composeApp:assembleDebug    # APK
```

> Without `xray.aar` the project still compiles and runs, but connecting reports
> "xray.aar is not bundled".

```bash
./gradlew :composeApp:desktopTest      # 28 tests: link parsing, config generation, Mihomo YAML
```

<details>
<summary>Releases and signing</summary>

Releases are built **manually**: *Actions → Release → Run workflow*, with a tag such as
`v0.1.1` that must match `versionName`. Nothing runs on push or tags. A build-only check
runs the same way via *Actions → CI*.

ABI splits are enabled with `-PabiSplits`, so a plain `assembleDebug` still builds one APK.
The core ships only for `arm64-v8a` and `x86_64`.

For a release key set `VEIL_KEYSTORE`, `VEIL_KEYSTORE_PASSWORD`, `VEIL_KEY_ALIAS` and
`VEIL_KEY_PASSWORD` (or the matching repository secrets); otherwise the debug key is used.

Step-by-step guide: [`docs/release.md`](docs/release.md) (Russian).

</details>

<details>
<summary>Logs</summary>

In the app: *Settings → Core logs*. Files live in
`Android/data/dev.local.veil/files/xray.log` and `xray.config.json` and can be pulled with
`adb` (`run-as` fails on Samsung due to SELinux). The log is truncated on every new connection.

</details>

## Porting status

| Layer | Source (Swift) | Status |
| --- | --- | --- |
| Server model | `Models/ProxyConfig.swift` | ✅ ported |
| Link parsing | `Core/LinkParser.swift` | ✅ ported, tested |
| Subscriptions | `Core/SubscriptionPayload.swift` | ✅ links, base64, Xray arrays, `X-Hwid` |
| Xray config builder | `Core/XrayConfigBuilder.swift` | ✅ ported, tested |
| TUN + core | `Tunnel/PacketTunnelProvider.swift` | ✅ on `VpnService` |
| Go bridge | `XrayBridge/xray.go` | ✅ reused + probe instance |
| UI | `App/Views/*` | ✅ servers, subscriptions, settings, split tunnel, logs |
| `urltest` groups, Mihomo selectors | — | ⏳ not yet |
| Routing rules editor, DNS editor | — | ⏳ not yet |
| Config inspector, secret redaction | — | ⏳ not yet |
| Localization tables | — | ⏳ not yet |

More in [`docs/porting.md`](docs/porting.md).

## Project layout

```
composeApp/src/
├── commonMain/kotlin/dev/local/veil/
│   ├── core/      ProxyConfig, LinkParser, XrayConfig, AppSettings
│   ├── platform/  AppContainer, VpnController, LogProvider, UpdateService
│   └── ui/        App, screens, components, theme
├── androidMain/kotlin/dev/local/veil/
│   ├── MainActivity.kt
│   └── platform/  VeilVpnService, XrayBridge, EndpointResolver, AndroidUpdateService
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
