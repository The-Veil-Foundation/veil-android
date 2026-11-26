package dev.local.veil.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

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

@Serializable
data class ProxyConfig(
    val id: String = newId(),

    val name: String,

    val proto: ProxyProtocol,
    val address: String,
    val port: Int,

    val uuid: String? = null,
    val password: String? = null,
    val method: String? = null,
    val alterId: Int? = null,
    val flow: String? = null,
    val encryption: String? = null,

    val network: TransportNetwork = TransportNetwork.TCP,
    val security: StreamSecurity = StreamSecurity.NONE,

    val sni: String? = null,
    val alpn: List<String>? = null,
    val fingerprint: String? = null,
    val allowInsecure: Boolean = false,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,

    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,

    val xhttpMode: String? = null,
    val xPaddingBytes: String? = null,
    val xhttpExtra: String? = null,

    val obfs: String? = null,
    val obfsPassword: String? = null,
    val congestionControl: String? = null,
    val udpRelayMode: String? = null,
    val upMbps: Int? = null,
    val downMbps: Int? = null,

    val privateKey: String? = null,
    val peerPublicKey: String? = null,
    val presharedKey: String? = null,
    val localAddresses: List<String>? = null,
    val allowedIPs: List<String>? = null,
    val mtu: Int? = null,
    val reserved: List<Int>? = null,

    val hidden: Boolean = false,

    val rawConfigJson: String? = null,

    val balanced: Int = 0,

    val source: String? = null,
) {
    val endpoint: String get() = "$address:$port"

    val effectiveAllowedIPs: List<String>
        get() {
            val cleaned = (allowedIPs ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
            return cleaned.ifEmpty { listOf("0.0.0.0/0", "::/0") }
        }

    val carriesEverything: Boolean
        get() = allowedIPs.isNullOrEmpty() ||
            (allowedIPs.any { it.trim() == "0.0.0.0/0" } && allowedIPs.any { it.trim() == "::/0" })

    val isUsable: Boolean get() = proto.xraySupported

    val isPlaceholder: Boolean
        get() = address.trim().lowercase() in PLACEHOLDER_HOSTS || port <= 0
}

fun newId(): String {
    val chars = "0123456789abcdef"
    return buildString(16) {
        repeat(16) { append(chars[Random.nextInt(16)]) }
    }
}

private val PLACEHOLDER_HOSTS = setOf("", "0.0.0.0", "::", "[::]", "127.0.0.1", "localhost")
