package dev.local.veil.core

fun diagnosticSummary(servers: List<ProxyConfig>, subscriptionCount: Int): String = buildString {
    appendLine("Veil diagnostics v1")
    appendLine("Privacy: names, addresses, URLs, keys, device IDs and logs omitted.")
    appendLine("Servers: ${servers.size}")
    appendLine("Subscriptions: $subscriptionCount")
    for (protocol in ProxyProtocol.entries) {
        appendLine("${protocol.wire}: ${servers.count { it.proto == protocol }}")
    }
}
