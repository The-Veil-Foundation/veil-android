package dev.local.veil.platform

import dev.local.veil.core.TransferPayload

class ReceiveSession(val url: String, private val onStop: () -> Unit) {
    fun stop() = onStop()
}

interface TransferService {

    val isTelevision: Boolean

    fun shareText(text: String)

    fun startReceiving(onReceived: (TransferPayload) -> Unit): ReceiveSession?

    suspend fun send(pairingUrl: String, payload: TransferPayload): Result<Unit>
}

object NoTransferService : TransferService {
    override val isTelevision: Boolean = false
    override fun shareText(text: String) = Unit
    override fun startReceiving(onReceived: (TransferPayload) -> Unit): ReceiveSession? = null
    override suspend fun send(pairingUrl: String, payload: TransferPayload): Result<Unit> =
        Result.failure(UnsupportedOperationException("Перенос не поддерживается на этой платформе"))
}
