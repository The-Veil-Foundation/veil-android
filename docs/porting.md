# Карта порта iOS → Android

Инвентарь исходников `veil-ios` + `veil-macos` и их статус в этом проекте.
Нужен, чтобы продолжать порт без повторного аудита.

Всего в оригинале: **~38 000 строк Swift** (26 000 в общем ядре `veil-macos`
и 6 000 в iOS-приложении).

## Перенесено

| Swift | Строк | Kotlin | Примечание |
|---|---|---|---|
| `Models/ProxyConfig.swift` | 188 | `core/ProxyConfig.kt` | все поля, включая xhttp и wireguard |
| `Core/LinkParser.swift` | 444 | `core/LinkParser.kt` | vless, vmess, trojan, ss, hy2, tuic, anytls, wg + wg-conf |
| `Core/AddInput.swift` | 59 | `core/AddInput.kt` | ссылки, base64-тело, wg-conf; URL-подписка не реализована |
| `Core/XrayConfigBuilder.swift` | 445 | `core/XrayConfig.kt` | outbound, stream/tls/reality, tun-inbound, DNS, stats, routing |
| `Tunnel/PacketTunnelProvider.swift` | 382 | `platform/VeilVpnService.kt` | TUN, fd, foreground, жизненный цикл |
| `XrayBridge/xray.go` | 255 | без изменений | тот же Go-пакет, собранный под Android |

Тесты: 16 (парсер ссылок + сборка конфига).

## Осталось

### Модели (`veil-macos/Sources/XrayClient/Models`)

| Файл | Строк | Что внутри |
|---|---|---|
| `Routing.swift` | 462 | пресеты, правила, цели правил, `xrayRule` |
| `DNSSettings.swift` | 360 | серверы, detour, fake IP, DNS-правила |
| `RuleSet.swift` | 306 | community-листы правил |
| `AppSettings.swift` | 300 | настройки списка, обновлений, туннеля |
| `Subscription.swift` | 151 | подписка, заметки панели, трафик, срок |
| `CommunityList.swift` | 143 | каталог списков |
| `NodeAnnotation.swift` | 107 | кастомные имена, теги, пины |
| `ServerGroup.swift` | 105 | группы и их запросы |
| `GroupQuery.swift` | 65 | выборка участников |

### Ядро (`veil-macos/Sources/XrayClient/Core`)

| Файл | Строк | Приоритет |
|---|---|---|
| `XrayProfileBuilder.swift` | 593 | высокий: граф outbound'ов, balancer'ы, правила |
| `SubscriptionPayload.swift` | 561 | высокий: разбор ответов панелей |
| `ServerStore.swift` | 444 | высокий: хранилище, миграции, выделение |
| `SubscriptionFetcher.swift` | 106 | высокий: скачивание по URL (нужен нативный HTTP) |
| `ServerListBuilder.swift` | 240 | средний: секции, порядок, фильтры |
| `PingTester.swift` | 339 | средний: задержки, protect() сокета |
| `GroupResolver.swift` / `BalancerGrouper.swift` | 158 | средний: urltest/selector |
| `OutboundGraph.swift` | 81 | средний: цель → outbound |
| `NodeIdentity.swift` / `NodeFacets.swift` / `NodeRepresentation.swift` | 437 | средний: страна, протокол, JSON/ссылка |
| `GeoAssetManager.swift` | 177 | средний: geoip.dat / geosite.dat |
| `QRCode.swift` | 110 | низкий |
| `LocalizationTable.swift` | 5 764 | низкий (но именно он даёт мультиязычность) |
| `UpdateChecker.swift` / `UpdateInstaller.swift` | 595 | низкий: обновления |
| `ControlAPI.swift` / `ControlServer.swift` | 632 | не нужно: это macOS-мост к процессу |
| `ConnectionManager.swift` | 1 039 | не нужно: macOS запускает xray как процесс |
| `SingBox*.swift` | 826 | не нужно: сборка только на Xray |
| `PrivilegedHelper*` / `SystemProxy*` / `TunManager` | ~790 | не нужно: macOS-специфика |

### iOS-приложение (`App/Views`, 4 732 строки)

| Экран | Приоритет |
|---|---|
| `ContentView.swift` (797) — список серверов, фильтры, сортировка | высокий |
| `AddView.swift` (192) — вставка/скан/фото | высокий |
| `ServerRow.swift` / `SubscriptionHeaderRow.swift` (391) | высокий |
| `SettingsView.swift` (306) | высокий |
| `SourcesView.swift` (297) — что вернула подписка | средний |
| `GroupsView.swift` + `GroupMembersView.swift` (566) | средний |
| `RoutingView.swift` + `RulesView.swift` (491) | средний |
| `DNSView.swift` (354) | средний |
| `NodeEditorView.swift` (148) | средний |
| `LogView.swift` (126) | средний |
| `QRDisplayView.swift` / `QRScannerView.swift` (356) | низкий |
| `GeoDatabaseView.swift` / `CommunityListsView.swift` (201) | низкий |
| `Components.swift` (157) | по мере надобности |

## Порядок продолжения

1. **Переключение сервера без переподключения.** На Android TUN можно не
   пересоздавать: держать `ParcelFileDescriptor` и перезапускать только ядро
   (`XrayBridge.stop()` → `start()` с тем же fd). Это прямой аналог iOS `reload`.
2. **Подписки.** `SubscriptionPayload` + `SubscriptionFetcher` + `ServerStore`.
   Скачивание — нативно (OkHttp), потому что браузерный слой не нужен.
3. **`XrayProfileBuilder`.** После появления групп и правил конфиг собирается
   не из одного сервера, а из графа.
4. **Ping.** На Android вместо исключённых маршрутов используется
   `VpnService.protect(socket)`: сокет пинга обязан идти мимо туннеля.
5. **Экраны** по одному, начиная с `SettingsView` и `SourcesView`.

## Отличия платформ, которые нельзя перенести один в один

| iOS | Android |
|---|---|
| `NEPacketTunnelNetworkSettings` + `excludedRoutes` | `VpnService.Builder` умеет только `addRoute`; исключения делаются через `protect()` |
| `NWPathMonitor` для смены сети | `ConnectivityManager.NetworkCallback` |
| `NETunnelProviderSession.sendProviderMessage` | тот же процесс, `StateFlow` |
| App Group `group.dev.local.veil` | `filesDir` + `SharedPreferences` |
| Kill extension при превышении памяти | ядро в основном процессе, лимит не нужен (но Go-память стоит ограничивать) |
| utun ищется перебором fd | fd приходит прямо из `establish()` |

## Android-специфика, найденная на живом устройстве

### Go-резолвер не умеет DNS
В APK нет `/etc/resolv.conf` (на Android его вообще нет), поэтому Go-резолвер
внутри `xray.aar` не может разрешить имя узла: `dialing to tcp:node.example.com:443`
висит вечно, туннель считается поднятым, но трафик нулевой. Симптом на экране —
«Защищено» и `↓ 0 Б`.
Решение: `EndpointResolver` резолвит домены серверов нативной Java-DNS (она
работает через netd) и подставляет IP в конфиг, оставляя SNI/REALITY
`serverName` и ws `Host` доменом.

### Проверка узла перед подключением
TCP-пинг показывает только открытый порт, поэтому перед стартом туннеля
поднимается ядро с локальным SOCKS-инбаундом (`XrayInbound.LocalProxy`) и
делается HTTPS-запрос через узел. Если узел мёртв — приложение само перебирает
быстрые альтернативы подписки и переключается (или откатывается на прежний узел
при смене на лету).
Важно: проба должна идти по HTTPS — при `targetSdk 36` Android блокирует
cleartext-запросы, и HTTP-проба даёт ложный отказ на любом узле.

### Логи
`xray.log` и `xray.config.json` пишутся в `Android/data/dev.local.veil/files/`:
`run-as` на Samsung падает на SELinux, а этот каталог читается через adb.
