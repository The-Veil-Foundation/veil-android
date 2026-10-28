package dev.local.veil.platform

import android.util.Log

/**
 * Обёртка над `xray.aar` (gomobile-биндинг XrayBridge из veil-ios).
 *
 * Через рефлексию, а не прямыми вызовами: AAR собирается отдельным скриптом,
 * и без него проект всё равно должен компилироваться. Имена методов проверяются
 * один раз при первом обращении.
 */
object XrayBridge {
    private const val TAG = "VeilXray"
    private const val CLASS_NAME = "xray.Xray"

    private val clazz: Class<*>? by lazy {
        runCatching { Class.forName(CLASS_NAME) }.getOrNull()
    }

    val available: Boolean get() = clazz != null

    /** "26.x.y" или null, если AAR не подключён. */
    fun version(): String? = callString("version")

    val isRunning: Boolean
        get() = callNoArgs("isRunning") as? Boolean ?: false

    fun start(configJson: String, tunFd: Int, assetDir: String, maxMemoryMb: Int): Result<Unit> =
        runCatching {
            val c = clazz ?: error("xray.aar не подключён")
            // gomobile превращает Go int в Java long.
            val method = c.methods.firstOrNull { it.name == "start" && it.parameterTypes.size == 4 }
                ?: error("xray.Xray.start не найден")
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
                // Gomobile бросает Exception с текстом ошибки Xray — отдаём его как есть.
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

    /**
     * Второй экземпляр ядра для проверки узла. Основной туннель при этом
     * продолжает работать — переключение сервера больше не рвёт соединения.
     */
    fun startProbe(configJson: String, assetDir: String): Result<Unit> = runCatching {
        val c = clazz ?: error("xray.aar не подключён")
        val method = c.methods.firstOrNull { it.name == "startProbe" && it.parameterTypes.size == 2 }
            ?: error("xray.Xray.startProbe не найден — пересоберите xray.aar")
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
        val c = clazz ?: error("xray.aar не подключён")
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
