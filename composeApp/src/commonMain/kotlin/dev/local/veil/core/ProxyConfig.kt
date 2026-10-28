package dev.local.veil.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Поддерживаемые протоколы. Как и в iOS-сборке, реально работает только Xray. */
@Serializable
enum class ProxyProtocol(val wire: String) {
    @SerialName("vless") VLESS("vless"),
    @SerialName("vmess") VMESS("vmess"),
    @SerialName("trojan") TROJAN("trojan"),
    @SerialName("ss") SHADOWSOCKS("ss"),
    @SerialName("hysteria2") HYSTERIA2("hysteria2"),
    @SerialName("tuic") TUIC("tuic"),
    @SerialName("wireguard") WIREGUARD("wireguard"),
    @SerialName("anytls") ANYTLS("anytls"),
    ;

    /** Xray умеет всё, кроме QUIC-протоколов и AnyTLS из этого списка. */
    val xraySupported: Boolean
        get() = this != HYSTERIA2 && this != TUIC && this != ANYTLS

    companion object {
        fun fromWire(value: String): ProxyProtocol? = entries.firstOrNull { it.wire == value.lowercase() }
    }
}

@Serializable
enum class TransportNetwork(val wire: String) {
    @SerialName("tcp") TCP("tcp"),
    @SerialName("ws") WS("ws"),
    @SerialName("grpc") GRPC("grpc"),
    @SerialName("http") HTTP("http"),
    @SerialName("kcp") KCP("kcp"),
    @SerialName("quic") QUIC("quic"),
    @SerialName("xhttp") XHTTP("xhttp"),
    ;

    companion object {
        fun fromWire(value: String?): TransportNetwork =
            entries.firstOrNull { it.wire == value?.lowercase() } ?: TCP
    }
}

@Serializable
enum class StreamSecurity(val wire: String) {
    @SerialName("none") NONE("none"),
    @SerialName("tls") TLS("tls"),
    @SerialName("reality") REALITY("reality"),
    ;

    companion object {
        fun fromWire(value: String?): StreamSecurity =
            entries.firstOrNull { it.wire == value?.lowercase() } ?: NONE
    }
}

/**
 * Один сервер. Каноническое представление, из которого собирается конфиг Xray.
 */
@Serializable
data class ProxyConfig(
    val id: String = newId(),

    val name: String,

    val proto: ProxyProtocol,
    val address: String,
    val port: Int,

    // auth
    val uuid: String? = null,
    val password: String? = null,
    val method: String? = null,
    val alterId: Int? = null,
    val flow: String? = null,
    val encryption: String? = null,

    // transport
    val network: TransportNetwork = TransportNetwork.TCP,
    val security: StreamSecurity = StreamSecurity.NONE,

    // tls / reality
    val sni: String? = null,
    val alpn: List<String>? = null,
    val fingerprint: String? = null,
    val allowInsecure: Boolean = false,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,

    // ws / http / grpc
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,

    // xhttp
    val xhttpMode: String? = null,
    val xPaddingBytes: String? = null,
    val xhttpExtra: String? = null,

    // sing-box only
    val obfs: String? = null,
    val obfsPassword: String? = null,
    val congestionControl: String? = null,
    val udpRelayMode: String? = null,
    val upMbps: Int? = null,
    val downMbps: Int? = null,

    // wireguard
    val privateKey: String? = null,
    val peerPublicKey: String? = null,
    val presharedKey: String? = null,
    val localAddresses: List<String>? = null,
    val allowedIPs: List<String>? = null,
    val mtu: Int? = null,
    val reserved: List<Int>? = null,

    // ui
    val hidden: Boolean = false,

    /**
     * Полный Xray-конфиг из подписки, если панель отдаёт не ссылку, а готовый
     * документ (Remnawave/Happ). При подключении из него берётся всё, кроме
     * inbounds — они заменяются на наш tun.
     */
    val rawConfigJson: String? = null,

    /** Из какой подписки пришла нода — чтобы обновление заменяло её целиком. */
    val source: String? = null,
) {
    val endpoint: String get() = "$address:$port"

    val effectiveAllowedIPs: List<String>
        get() {
            val cleaned = (allowedIPs ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
            return cleaned.ifEmpty { listOf("0.0.0.0/0", "::/0") }
        }

    /** Полный туннель или только отдельные префиксы (WireGuard-сайдкар). */
    val carriesEverything: Boolean
        get() = allowedIPs.isNullOrEmpty() ||
            (allowedIPs.any { it.trim() == "0.0.0.0/0" } && allowedIPs.any { it.trim() == "::/0" })

    val isUsable: Boolean get() = proto.xraySupported
}

/** Короткий стабильный идентификатор вместо UUID. */
fun newId(): String {
    val chars = "0123456789abcdef"
    return buildString(16) {
        repeat(16) { append(chars[Random.nextInt(16)]) }
    }
}
