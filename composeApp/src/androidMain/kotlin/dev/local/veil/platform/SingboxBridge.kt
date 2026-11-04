package dev.local.veil.platform

import android.util.Log

/**
 * Обёртка над `sing-box.aar` (gomobile-биндинг `singbox.Singbox`).
 *
 * Как и [XrayBridge], работает через рефлексию: пока AAR не подключён к сборке
 * (класса нет), приложение компилируется и живёт на одном Xray. Когда
 * `sing-box.aar` появится в `composeApp/libs`, методы подхватятся без правок.
 */
object SingboxBridge {
    private const val TAG = "VeilSingbox"
    private const val CLASS_NAME = "singbox.Singbox"

    private val clazz: Class<*>? by lazy {
        runCatching { Class.forName(CLASS_NAME) }.getOrNull()
    }

    val available: Boolean get() = clazz != null

    /** "1.x.y" или null, если AAR не подключён. */
    fun version(): String? = callString("version")

    val isRunning: Boolean
        get() = callNoArgs("isRunning") as? Boolean ?: false

    fun start(configJson: String, tunFd: Int, assetDir: String, maxMemoryMb: Int): Result<Unit> =
        runCatching {
            val c = clazz ?: error("sing-box.aar не подключён")
            val method = c.methods.firstOrNull { it.name == "start" && it.parameterTypes.size == 4 }
                ?: error("singbox.Singbox.start не найден")
            val types = method.parameterTypes
            try {
                method.invoke(
                    null,
                    configJson,
                    if (types[1] == Long::class.javaPrimitiveType) tunFd.toLong() else tunFd,
                    assetDir,
                    if (types[3] == Long::class.javaPrimitiveType) maxMemoryMb.toLong() else maxMemoryMb,
                )
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
            }
        }

    fun stop() {
        runCatching { clazz?.getMethod("stop")?.invoke(null) }
            .onFailure { Log.w(TAG, "stop failed", it) }
    }

    fun prepareLog(path: String) {
        runCatching {
            val c = clazz ?: return
            c.getMethod("prepareLog", String::class.java).invoke(null, path)
        }.onFailure { Log.w(TAG, "prepareLog failed", it) }
    }

    /** Второй экземпляр для проверки узла, основной туннель не трогаем. */
    fun startProbe(configJson: String, assetDir: String): Result<Unit> = runCatching {
        val c = clazz ?: error("sing-box.aar не подключён")
        val method = c.methods.firstOrNull { it.name == "startProbe" && it.parameterTypes.size == 2 }
            ?: error("singbox.Singbox.startProbe не найден")
        try {
            method.invoke(null, configJson, assetDir)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    fun stopProbe() {
        runCatching { clazz?.getMethod("stopProbe")?.invoke(null) }
            .onFailure { Log.w(TAG, "stopProbe failed", it) }
    }

    fun checkConfig(configJson: String): Result<Unit> = runCatching {
        val c = clazz ?: error("sing-box.aar не подключён")
        c.getMethod("checkConfig", String::class.java).invoke(null, configJson)
    }

    fun uplinkTotal(): Long {
        val c = clazz ?: return 0L
        return runCatching { c.getMethod("uplinkTotal").invoke(null) as? Long ?: 0L }.getOrDefault(0L)
    }

    fun downlinkTotal(): Long {
        val c = clazz ?: return 0L
        return runCatching { c.getMethod("downlinkTotal").invoke(null) as? Long ?: 0L }.getOrDefault(0L)
    }

    private fun callString(name: String): String? =
        clazz?.let { runCatching { it.getMethod(name).invoke(null) as? String }.getOrNull() }

    private fun callNoArgs(name: String): Any? =
        clazz?.let { runCatching { it.getMethod(name).invoke(null) }.getOrNull() }
}
