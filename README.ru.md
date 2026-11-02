<p align="center">
  <img src=".github/assets/logo.png" width="128" height="128" alt="Veil">
</p>

<h1 align="center">Veil для Android</h1>

<p align="center">
  Быстрый нативный VPN-клиент для Android и Android TV.<br>
  Бесплатный и с открытым исходным кодом.
</p>

<p align="center">
  <a href="https://faustyu1.github.io/veil/">Сайт</a> ·
  <a href="https://github.com/The-Veil-Foundation/veil-macos">macOS</a> ·
  <a href="https://github.com/The-Veil-Foundation/veil-ios">iOS</a> ·
  <a href="https://github.com/The-Veil-Foundation/veil-android/releases">Скачать</a> ·
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

Veil для Android — порт [veil-ios](https://github.com/The-Veil-Foundation/veil-ios) на
Kotlin Multiplatform + Jetpack Compose с одним ядром —
[Xray-core](https://github.com/XTLS/Xray-core). Интерфейс Material 3 Expressive, профили
подписок, подключение в одно касание, настоящая проверка узлов с автоподбором и туннель
без tun2socks и лишних хопов в пути данных.

> **Статус: альфа (`0.1.0-alpha`).** Проверено на живом Samsung Galaxy S20 FE с реальными
> подписками VLESS/REALITY. Шероховатости возможны.

> **Дисклеймер.** Veil — клиент прокси-протоколов для приватности, разработки и законного
> обхода цензуры. Вы сами отвечаете за соблюдение законов и условий сервисов, которые к вам
> применимы. Встроенное ядро — стороннее ПО под собственной лицензией.

## Установка

Скачайте APK из [последнего релиза](https://github.com/The-Veil-Foundation/veil-android/releases/latest):

| Файл | Для чего |
| --- | --- |
| `veil-<версия>-arm64-v8a.apk` | телефоны и большинство приставок Android TV |
| `veil-<версия>-x86_64.apk` | эмуляторы и x86-устройства |
| `veil-<версия>-universal.apk` | оба ABI в одном файле — берите, если не уверены |

Разрешите установку из неизвестных источников, когда Android спросит. Нужен Android 7.0
(API 24) или новее; 32-битные устройства не поддерживаются. Обновления приложение проверяет
само в GitHub Releases (*Настройки → Проверить обновления*).

## Возможности

### Протоколы

| Ядро | Протоколы | Транспорты и защита |
| --- | --- | --- |
| **Xray-core** | VLESS, VMess, Trojan, Shadowsocks, WireGuard | REALITY, TLS, XTLS Vision, XHTTP, отпечатки uTLS |

Ссылки Hysteria2, TUIC и AnyTLS распознаются, но помечаются как нерабочие — им нужен
sing-box, а сборка сознательно только на Xray.

### Подписки

- Обычные share-ссылки, base64-списки, массивы готовых Xray-конфигов и **Mihomo / Clash YAML**
  (`proxies:` импортируются как узлы; группы и правила пока не используются).
- Обновление вручную, по группе и по расписанию.
- Разбирается `subscription-userinfo` — карточка подписки показывает трафик и дату окончания.

### Проверка узлов и автоподбор

- Перед подключением второй, независимый экземпляр ядра делает **настоящий HTTPS-запрос**
  через узел — ловится и мёртвый порт, и неверные данные авторизации.
- Если выбранный узел не прошёл проверку, автоматически перебираются самые быстрые
  альтернативы из той же подписки.
- При смене сервера туннель работает, пока проверяется кандидат; таймер сессии не сбрасывается.

### Сеть

- **Раздельный туннель** — включение или исключение приложений.
- **Обход РФ** — `.ru` / `.su` / `.рф` идут напрямую.
- **Фрагментация для DPI** — дробление TLS ClientHello через Xray `finalmask`.

### Остальное

- **Живой интерфейс** — скорость приёма/отдачи, время сессии, пульсирующее состояние, тосты.
- **QR-сканер** — camera2 + ZXing, без Google Play Services.
- **Android TV** — пункт в лончере, баннер и полная навигация с D-pad.
- **Pitch Black** — чёрная тема для OLED-экранов.
- **Логи ядра** — просмотр в приложении с копированием и очисткой.
- **Обновления по воздуху** — прямо из GitHub Releases.

## Как это работает

```
приложения ──► маршрутизация Android ──► TUN (VpnService.establish())
                                            │ fd
                                            ▼
                                Xray `tun` inbound (gVisor)
                                            │ правила, sniffing, DNS
                                            ▼
                                VLESS / VMess / Trojan / SS / WireGuard
                                            │
                                            ▼
                                          сервер
```

`VpnService.establish()` отдаёт `ParcelFileDescriptor`. `XrayBridge` публикует его номер в
`xray.tun.fd`, а `proxy/tun/tun_android.go` внутри Xray-core работает с дескриптором
напрямую — без tun2socks, второго ядра и локального SOCKS-хопа.

<details>
<summary>Подробности</summary>

- Ядро работает в процессе приложения, поэтому пакет исключён из туннеля
  (`Builder.addDisallowedApplication`) — иначе сокеты до сервера уходили бы в тот же TUN.
- При смене сервера сервис пересоздаёт TUN и передаёт ядру **дубликат** дескриптора: gVisor
  закрывает fd при остановке ядра, и переиспользование старого оставляло бы «подключённый»
  туннель, который молча отбивает все соединения.

</details>

## Сборка из исходников

Нужны JDK 21, Android SDK 36, NDK 29 и Go 1.26 (только для ядра).

```bash
git clone https://github.com/The-Veil-Foundation/veil-android.git && cd veil-android

Scripts/build-xray-android.sh          # ядро, один раз (~10–20 мин) → composeApp/libs/xray.aar
./gradlew :composeApp:assembleDebug    # APK
```

> Без `xray.aar` проект собирается и запускается, но подключение честно отвечает
> «xray.aar не подключён к сборке».

```bash
./gradlew :composeApp:desktopTest      # 28 тестов: ссылки, сборка конфига, Mihomo YAML
```

<details>
<summary>Релизы и подпись</summary>

Релизы собираются **вручную**: *Actions → Release → Run workflow*, с тегом вроде `v0.1.1`,
который должен совпадать с `versionName`. На push и теги ничего не запускается. Проверка
сборки без релиза — так же через *Actions → CI*.

Сплиты по ABI включаются флагом `-PabiSplits`, поэтому обычный `assembleDebug` собирает один
APK. Ядро есть только под `arm64-v8a` и `x86_64`.

Для релизного ключа задайте `VEIL_KEYSTORE`, `VEIL_KEYSTORE_PASSWORD`, `VEIL_KEY_ALIAS` и
`VEIL_KEY_PASSWORD` (или одноимённые секреты репозитория), иначе используется debug-ключ.

Пошаговая инструкция: [`docs/release.md`](docs/release.md).

</details>

<details>
<summary>Логи</summary>

В приложении: *Настройки → Логи ядра*. Файлы лежат в
`Android/data/dev.local.veil/files/xray.log` и `xray.config.json`, их можно забрать через
`adb` (`run-as` на Samsung падает на SELinux). Лог обнуляется при каждом новом подключении.

</details>

## Что портировано

| Слой | Источник (Swift) | Статус |
| --- | --- | --- |
| Модель сервера | `Models/ProxyConfig.swift` | ✅ портирована |
| Разбор ссылок | `Core/LinkParser.swift` | ✅ портирован, покрыт тестами |
| Подписки | `Core/SubscriptionPayload.swift` | ✅ ссылки, base64, массивы Xray, `X-Hwid` |
| Сборка Xray-конфига | `Core/XrayConfigBuilder.swift` | ✅ портирована, покрыта тестами |
| TUN + ядро | `Tunnel/PacketTunnelProvider.swift` | ✅ на `VpnService` |
| Go-мост | `XrayBridge/xray.go` | ✅ переиспользован + пробный экземпляр |
| UI | `App/Views/*` | ✅ серверы, подписки, настройки, раздельный туннель, логи |
| Группы `urltest`, селекторы Mihomo | — | ⏳ пока нет |
| Редактор правил маршрутизации, редактор DNS | — | ⏳ пока нет |
| Инспектор конфига, скрытие секретов | — | ⏳ пока нет |
| Таблицы локализации | — | ⏳ пока нет |

Подробнее — в [`docs/porting.md`](docs/porting.md).

## Структура проекта

```
composeApp/src/
├── commonMain/kotlin/dev/local/veil/
│   ├── core/      ProxyConfig, LinkParser, XrayConfig, AppSettings
│   ├── platform/  AppContainer, VpnController, LogProvider, UpdateService
│   └── ui/        App, экраны, компоненты, тема
├── androidMain/kotlin/dev/local/veil/
│   ├── MainActivity.kt
│   └── platform/  VeilVpnService, XrayBridge, EndpointResolver, AndroidUpdateService
├── desktopMain/   заглушки для десктопа (ядра нет)
└── commonTest/    тесты парсера и сборки конфига
XrayBridge/        Go-мост (gomobile) с пробным экземпляром
Scripts/           скрипт сборки ядра
docs/              заметки о порте, инструкция по релизам
```

## Другие платформы

- **macOS** — [veil-macos](https://github.com/The-Veil-Foundation/veil-macos)
- **iOS / iPadOS** — [veil-ios](https://github.com/The-Veil-Foundation/veil-ios)

## Благодарности

- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — прокси-движок
- [ZXing](https://github.com/zxing/zxing) — распознавание QR
- [JetBrains Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — UI

## История звёзд

<a href="https://star-history.com/#The-Veil-Foundation/veil-android&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=The-Veil-Foundation/veil-android&type=Date&theme=dark">
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=The-Veil-Foundation/veil-android&type=Date">
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=The-Veil-Foundation/veil-android&type=Date">
  </picture>
</a>

## Лицензия

Veil для Android — свободное ПО под **GNU Affero General Public License v3.0**, см.
[LICENSE](LICENSE); лицензия унаследована от исходного проекта.
[Xray-core](https://github.com/XTLS/Xray-core) распространяется под MPL-2.0, она совместима
с AGPLv3; любую распространяемую сборку нужно сопровождать исходниками.
