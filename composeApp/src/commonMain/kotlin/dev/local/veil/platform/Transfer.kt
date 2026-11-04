package dev.local.veil.platform

import dev.local.veil.core.TransferPayload

/** Открытый приём: адрес для QR и способ его закрыть. */
class ReceiveSession(val url: String, private val onStop: () -> Unit) {
    fun stop() = onStop()
}

/** Перенос подписок между устройствами в одной сети и системное «Поделиться». */
interface TransferService {
    /** Телевизор или приставка: нет камеры, управление пультом. */
    val isTelevision: Boolean

    fun shareText(text: String)

    /** Открывает приём в локальной сети; null — сети нет или платформа не умеет. */
    fun startReceiving(onReceived: (TransferPayload) -> Unit): ReceiveSession?

    /** Отправляет [payload] на устройство, показавшее QR с адресом [pairingUrl]. */
    suspend fun send(pairingUrl: String, payload: TransferPayload): Result<Unit>
}

object NoTransferService : TransferService {
    override val isTelevision: Boolean = false
    override fun shareText(text: String) = Unit
    override fun startReceiving(onReceived: (TransferPayload) -> Unit): ReceiveSession? = null
    override suspend fun send(pairingUrl: String, payload: TransferPayload): Result<Unit> =
        Result.failure(UnsupportedOperationException("Перенос не поддерживается на этой платформе"))
}
