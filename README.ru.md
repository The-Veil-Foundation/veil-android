# Veil для Android

[English](README.md) · **Русский**

Android-порт [veil-ios](https://github.com/The-Veil-Foundation/veil-ios) —
Kotlin Multiplatform + Compose, Material 3 Expressive, одно ядро: Xray.

**Статус:** альфа `0.1.0-alpha`. Проверено на живом Samsung Galaxy S20 FE (Android 14)
с реальными подписками VLESS/REALITY.

---

## Возможности

**Протоколы** — VLESS (REALITY, TLS, XTLS Vision, XHTTP, отпечатки uTLS),
VMess, Trojan, Shadowsocks, WireGuard. Ссылки Hysteria2, TUIC и AnyTLS
распознаются, но помечены как нерабочие: им нужен sing-box, а сборка сознательно
только на Xray.

**Подписки** — обычные share-ссылки, base64-списки и массивы готовых
Xray-конфигов. Обновление вручную, по группе и по расписанию. Заголовок
`subscription-userinfo` разбирается, поэтому карточка подписки показывает
израсходованный трафик и дату окончания.

**Проверка узлов и автоподбор** — перед подключением поднимается второй,
независимый экземпляр ядра с локальным SOCKS и делает настоящий HTTPS-запрос
через узел. Так ловятся и мёртвый порт, и неверные данные авторизации. Если
выбранный узел не прошёл проверку, автоматически перебираются быстрые
альтернативы из той же подписки. При смене сервера туннель продолжает работать,
пока проверяется кандидат, а таймер сессии не сбрасывается.

**Живой интерфейс** — скорость приёма/отдачи, время сессии, пульсирующее
состояние. Сообщения — всплывающие буллетины (тосты) внизу экрана вместо
баннеров в списке.

**Сеть** — раздельный туннель (include/exclude), обход `.ru`/`.su`/`.рф`,
фрагментация TLS ClientHello для обхода DPI (Xray `finalmask`).

**Дополнительно** — QR-сканер (camera2 + ZXing, без Google Play Services),
лончер и баннер Android TV с поддержкой D-pad, тема Pitch Black для OLED,
просмотр лога ядра с копированием и очисткой, проверка соединения, обновления
по воздуху из GitHub Releases.

---

## Как устроен туннель

Путь пакета повторяет iOS:

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

`VpnService.establish()` отдаёт `ParcelFileDescriptor`. `XrayBridge` публикует
его номер в `xray.tun.fd`, а `proxy/tun/tun_android.go` внутри Xray-core
подхватывает дескриптор и работает с ним напрямую. Никакого tun2socks, второго
ядра или локального SOCKS-хопа в пути данных.

Ядро работает в процессе приложения, поэтому пакет исключён из туннеля
(`Builder.addDisallowedApplication`) — иначе сокеты до сервера уходили бы в тот
же TUN.

При смене сервера сервис пересоздаёт TUN и передаёт ядру дубликат дескриптора:
gVisor закрывает fd при остановке ядра, и переиспользование старого оставляло бы
«подключённый» туннель, который молча отбивает все соединения.

---

## Что портировано

| Слой | Источник (Swift) | Статус |
|---|---|---|
| Модель сервера | `Models/ProxyConfig.swift` | портирована |
| Разбор ссылок | `Core/LinkParser.swift` | портирован, 9 тестов |
| Подписки | `Core/SubscriptionPayload.swift` | ссылки, base64, массивы Xray-конфигов; скачиваются нативно с `X-Hwid` |
| Сборка Xray-конфига | `Core/XrayConfigBuilder.swift` | портирована, 7 тестов |
| TUN + ядро | `Tunnel/PacketTunnelProvider.swift` | портирован на `VpnService` |
| Go-мост | `XrayBridge/xray.go` | переиспользован + пробный экземпляр |
| UI | `App/Views/*` | серверы, подписки, настройки, раздельный туннель, логи |

Пока не перенесено: группы `urltest` и ручные селекторы, импорт Mihomo/Clash
YAML, редактор правил маршрутизации, редактор DNS (fake IP, detour), инспектор
конфига и редактирование секретов, таблицы локализации.

---

## Сборка

Требуется: JDK 21, Android SDK 36, NDK 29, Go 1.26 (только для ядра).

```bash
# 1. Ядро (один раз, ~10–20 минут) → composeApp/libs/xray.aar
Scripts/build-xray-android.sh

# 2. APK
./gradlew :composeApp:assembleDebug

# 3. Тесты
./gradlew :composeApp:desktopTest
```

Без `xray.aar` проект собирается и запускается, но подключение честно отвечает
«xray.aar не подключён к сборке».

---

## Структура

```
composeApp/src/
├── commonMain/kotlin/dev/local/veil/
│   ├── core/      ProxyConfig, LinkParser, XrayConfig, AppSettings
│   ├── platform/  AppContainer, VpnController, LogProvider, UpdateService
│   └── ui/        App, экраны, компоненты, тема
├── androidMain/kotlin/dev/local/veil/
│   ├── MainActivity.kt
│   └── platform/  VeilVpnService, XrayBridge, EndpointResolver,
│                  AndroidUpdateService, провайдер логов
├── desktopMain/   заглушки для десктопа (ядра нет)
└── commonTest/    тесты парсера и сборки конфига
XrayBridge/        Go-мост (gomobile) с поддержкой пробного экземпляра
Scripts/           скрипт сборки ядра
.github/           CI и релизные workflow
```

---

## Логи

В приложении: Настройки → Логи ядра (просмотр, копирование, очистка). Файлы
лежат в `Android/data/dev.local.veil/files/xray.log` и `xray.config.json` — их
можно забрать через `adb` (`run-as` на Samsung падает на SELinux). Лог
обнуляется на каждое новое подключение.

---

## Релизы и обновления

Релизы собираются **вручную**: Actions → *Release* → *Run workflow*, вводится тег
(например `v0.1.1`, должен совпадать с `versionName`). На push и на теги ничего
не запускается — минуты не тратятся случайно. Результат:

- `veil-<версия>-arm64-v8a.apk` — телефоны;
- `veil-<версия>-x86_64.apk` — эмуляторы и x86-устройства;
- `veil-<версия>-universal.apk` — оба ABI в одном файле.

Сплиты включаются флагом `-PabiSplits`, поэтому обычный `assembleDebug`
по-прежнему собирает один APK. 32-битные ABI не собираются: ядро лежит только
под `arm64-v8a` и `x86_64`.

Подпись: если заданы `VEIL_KEYSTORE`, `VEIL_KEYSTORE_PASSWORD`, `VEIL_KEY_ALIAS`
и `VEIL_KEY_PASSWORD` (или одноимённые секреты репозитория), используется
релизный ключ, иначе — debug-ключ (годится для альфы).

Приложение само проверяет `releases/latest` этого репозитория (Настройки →
«Проверить обновления»), поэтому релизы должны публиковаться здесь, с APK в
ассетах.

Пошаговая публикация: [`docs/release.md`](docs/release.md). Проверка сборки без
релиза — так же вручную: Actions → *CI* → *Run workflow*.

---

## Тесты

`./gradlew :composeApp:desktopTest` — 25 тестов на разбор ссылок и сборку
конфига (маска фрагментации, правила обхода РФ, форматы подписок).

---

## Лицензия

Исходный проект — AGPLv3, порт наследует её — см. [LICENSE](LICENSE).
Xray-core (MPL-2.0) совместим с AGPLv3, но распространять сборку можно только
вместе с исходниками.

Репозиторий: https://github.com/The-Veil-Foundation/veil-android
