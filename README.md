# Veil for Android

Android-порт [veil-ios](https://github.com/The-Veil-Foundation/veil-ios).
Kotlin Multiplatform + Compose, Material You. Одно ядро — Xray-core.

## Как устроен туннель

Путь пакета повторяет iOS один в один:

```
приложения ──► системная маршрутизация Android ──► TUN (VpnService.establish())
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
ядра или локального SOCKS-хопа.

Ядро работает в процессе приложения, поэтому пакет исключён из туннеля
(`Builder.addDisallowedApplication`) — иначе сокеты до сервера уходили бы в тот
же TUN.

## Что портировано

| Слой | Источник (Swift) | Статус |
|---|---|---|
| Модель сервера | `Models/ProxyConfig.swift` | портирована |
| Разбор ссылок | `Core/LinkParser.swift` | портирован, 9 тестов |
| Подписки | `Core/SubscriptionPayload.swift` | ссылки, base64, Xray-массив конфигов; тело скачивается нативно с `X-Hwid` |
| Сборка Xray-конфига | `Core/XrayConfigBuilder.swift` | портирована, 7 тестов |
| TUN + ядро | `Tunnel/PacketTunnelProvider.swift` | портирован на `VpnService` |
| Go-мост | `XrayBridge/xray.go` | переиспользован как есть |
| UI | `App/Views/*` | один экран: список, статус, добавление, настройки |

## Чего пока нет

Это вертикальный срез, а не полный порт. Не перенесено:

- заметки панели, трафик и срок подписки, автообновление;
- Mihomo/Clash YAML (распознаётся, но не импортируется);
- группы: `urltest`-бalancer и ручной selector, `BalancerGrouper`, `GroupResolver`;
- редактор маршрутизации, правила, community-листы, geo-база;
- редактор DNS (fake IP, detour, DNS-правила);
- QR-сканер и показ QR, редактор нод, инспектор конфига;
- ping-тестер (`PingTester`), сортировка/фильтры/избранное;
- локализация (`LocalizationTable`, 5.7k строк);
- диагностика, редукция секретов в логах, проверка обновлений;
- переключение сервера без переподключения (reload с сохранением TUN) —
  сейчас переподключение полное.

## Протоколы

Работают те же, что на iOS: VLESS (Reality, TLS, XTLS Vision), VMess, Trojan,
Shadowsocks, WireGuard (нативный outbound Xray).

Hysteria2, TUIC, AnyTLS импортируются в список, но помечены и не подключаются:
они требуют sing-box, а сборка сознательно только на Xray.

## Сборка

```bash
# 1. Ядро (один раз, ~10–20 минут)
Scripts/build-xray-android.sh        # → composeApp/libs/xray.aar

# 2. APK
./gradlew :composeApp:assembleDebug

# тесты ядра
./gradlew :composeApp:desktopTest
```

Без `xray.aar` проект собирается и запускается, но подключение честно отвечает
«xray.aar не подключён к сборке».

Требуется JDK 21, Android SDK 36, NDK 29, Go 1.26.

## Структура

```
composeApp/src/
├── commonMain/kotlin/dev/local/veil/
│   ├── core/      ProxyConfig, LinkParser, XrayConfig, AddInput
│   ├── platform/  AppContainer, VpnController, ServerStore
│   └── ui/        App (список, статус, шторки), Theme
├── androidMain/kotlin/dev/local/veil/
│   ├── MainActivity.kt
│   └── platform/  VeilVpnService, XrayBridge, AndroidContainer
├── desktopMain/   DesktopContainer (ядро не подключено)
└── commonTest/    тесты парсера и сборки конфига
```

## Лицензия

Исходный проект — AGPLv3. Порт наследует её: Xray-core (MPL-2.0) и AGPLv3
совместимы, но распространять сборку можно только с исходниками.

## Возможности

- VLESS (включая REALITY и XTLS Vision), VMess, Trojan, Shadowsocks, WireGuard.
- Подписки: ссылки, base64, массивы готовых Xray-конфигов; обновление по кнопке,
  в заголовке группы и по расписанию (`autoRefreshHours`).
- Тариф подписки из заголовка `subscription-userinfo`: израсходовано/лимит и дата
  окончания прямо в карточке подписки.
- Проверка узла перед подключением: ядро поднимается с локальным SOCKS и делает
  настоящий HTTPS-запрос через узел (ловятся и мёртвый порт, и неверный пароль).
- Автоподбор: если выбранный узел не отвечает, приложение само перебирает
  быстрые альтернативы подписки и сообщает об этом буллетином. При смене узла
  на лету — только быстрая TCP-проверка, туннель при этом не рвётся.
- Живой экран: скорость входящая/исходящая, время сессии (не сбрасывается при
  смене узла), пульсирующее состояние.
- Сообщения — всплывающие буллетины внизу экрана (тосты), а не баннеры в списке:
  успех/ошибка/инфо, самоисчезают, тап закрывает.
- Раздельный туннель (include/exclude), обход RU-доменов, фрагментация TLS.
- Сканер QR (camera2 + ZXing, без Google Play Services).
- Android TV: leanback-лончер, баннер, D-pad.
- Pitch Black тема для OLED (единственная кастомизация темы).
- Логи ядра прямо в приложении: просмотр, копирование, очистка.
- Кнопки диагностики в настройках: «Логи ядра», «Проверить соединение».
- Обновления по воздуху из GitHub Releases: проверка версии, скачивание APK,
  установка через системный установщик (FileProvider).
- Ссылка на исходники: https://github.com/s4ntiksu/veil-android

## Сборка

```bash
# JDK 21, Android SDK 36, переменные окружения см. ~/android-dev.md
./gradlew :composeApp:assembleDebug
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

`xray.aar` пересобирается скриптом `Scripts/build-xray-android.sh` (gomobile,
arm64-v8a + x86_64). Без AAR проект компилируется, но подключение честно откажет.

## Логи

В приложении: Настройки → Логи ядра. Файлы лежат в
`Android/data/dev.local.veil/files/xray.log` и `xray.config.json` — их можно
забрать через `adb` (`run-as` на Samsung падает на SELinux).

## Тесты

`./gradlew :composeApp:desktopTest` — 25 тестов на разбор ссылок и сборку конфига.

## Релизы и обновления

`.github/workflows/release.yml` собирает APK при пуше тега `v*`:

- `composeApp-arm64-v8a-release.apk` — телефоны;
- `composeApp-x86_64-release.apk` — эмуляторы и x86-устройства;
- `composeApp-universal-release.apk` — оба ABI в одном файле.

Сплиты включаются флагом `-PabiSplits`, поэтому локальный `assembleDebug`
по-прежнему собирает один APK. 32-битные ABI не собираются: ядро лежит
только под `arm64-v8a` и `x86_64`.

Подпись: если заданы переменные `VEIL_KEYSTORE`, `VEIL_KEYSTORE_PASSWORD`,
`VEIL_KEY_ALIAS`, `VEIL_KEY_PASSWORD` (или одноимённые секреты репозитория),
используется релизный ключ, иначе — debug-ключ (годится для альфы).

Приложение само проверяет `releases/latest` в этом репозитории: Настройки →
«Проверить обновления». Поэтому релизы должны публиковаться именно здесь,
с APK в ассетах.

## Публикация

Пошагово — `docs/release.md`: SSH-ключ, создание репозитория, секреты подписи,
первый пуш и тег для релиза.
