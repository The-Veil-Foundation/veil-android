package dev.local.veil.core

/**
 * Адреса TUN-интерфейса. Их ставит VpnService, и те же адреса получает
 * tun inbound sing-box: system/mixed-стек слушает TCP на адресе интерфейса,
 * поэтому расхождение молча ломает соединения. Подсеть вне 198.18.0.0/15,
 * чтобы не пересекаться с пулом fake-ip.
 */
object TunAddress {
    const val IPV4 = "172.19.0.1"
    const val IPV4_PREFIX = 30
    const val IPV6 = "fdfe:dcba:9876::1"
    const val IPV6_PREFIX = 126
}
