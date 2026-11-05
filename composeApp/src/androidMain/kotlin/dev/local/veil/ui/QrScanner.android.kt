package dev.local.veil.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

actual val qrSupported: Boolean = true

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Полноэкранный сканер QR на camera2 + ZXing.
 *
 * Раньше здесь выбиралось *минимальное* разрешение камеры (иногда 176×144),
 * камера не закрывалась после закрытия диалога и не было вспышки — на слабых
 * устройствах код просто не распознавался. Теперь:
 *  - анализ кадра идёт в ~1280×720 (но не больше 2 Мп), а не в минимальном;
 *  - превью и кадры согласованы по соотношению сторон, картинка не растянута;
 *  - камера, сессия, ImageReader и поток закрываются на onDispose;
 *  - есть автофокус, вспышка и понятное сообщение об ошибке.
 */
@Composable
actual fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var requested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (!ok) {
            error = tr(
                "Без доступа к камере сканировать нечего. Разрешите камеру в настройках приложения.",
                "Without camera access there is nothing to scan. Allow the camera in the app settings.",
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                val controller = remember { mutableStateOf<QrCameraController?>(null) }
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { view ->
                            controller.value = QrCameraController(
                                context = ctx,
                                textureView = view,
                                onCode = { code ->
                                    if (!delivered) {
                                        delivered = true
                                        onResult(code)
                                    }
                                },
                                onError = { message -> if (error == null) error = message },
                                onTorchAvailable = { torchAvailable = it },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Камера должна освободиться сразу, а не после сборки мусора:
                // иначе повторное открытие сканера остаётся с чёрным экраном.
                DisposableEffect(Unit) {
                    onDispose {
                        controller.value?.stop()
                        controller.value = null
                    }
                }

                if (torchAvailable) {
                    FilledIconButton(
                        onClick = {
                            val c = controller.value ?: return@FilledIconButton
                            torchOn = c.toggleTorch()
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 116.dp),
                    ) {
                        Icon(
                            if (torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                            contentDescription = tr("Вспышка", "Flashlight"),
                        )
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tr("Нужен доступ к камере", "Camera access needed"),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        tr(
                            "Разрешите камеру, чтобы сканировать QR-код подписки или узла.",
                            "Allow the camera to scan a subscription or server QR code.",
                        ),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    ) {
                        Text(tr("Открыть настройки", "Open settings"), color = Color.White)
                    }
                }
            }

            // Подсказка-рамка: квадрат по центру, под ним — статус.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(248.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp)),
            )

            Text(
                error ?: tr("Наведите на QR-код подписки или узла", "Point at a subscription or server QR code"),
                color = if (error == null) Color.White else Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 64.dp),
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = tr("Закрыть", "Close"), tint = Color.White)
            }
        }
    }
}

/**
 * Камера на camera2: превью в TextureView + кадры в ImageReader, декодирование
 * ZXing в фоновом потоке. Без Google Play Services — на устройстве они не нужны,
 * а Maven-репозиторий Google может быть недоступен.
 */
private class QrCameraController(
    context: Context,
    private val textureView: TextureView,
    private val onCode: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onTorchAvailable: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("veil-qr").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reader = MultiFormatReader()
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var previewSize: Size? = null
    private var luminance = ByteArray(0)
    private var lastFrameAt = 0L
    private var torch = false
    private var reportedError = false

    @Volatile private var closed = false

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera(width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                previewSize?.let { configureTransform(width, height, it) }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun toggleTorch(): Boolean {
        val id = cameraId ?: return false
        torch = !torch
        return runCatching {
            manager.setTorchMode(id, torch)
            torch
        }.getOrDefault(false)
    }

    private fun openCamera(viewWidth: Int, viewHeight: Int) {
        if (closed) return
        val id = runCatching { pickCameraId() }.getOrNull()
        if (id == null) {
            fail(tr("Камера не найдена", "No camera found"))
            return
        }
        cameraId = id
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (characteristics == null || map == null) {
            fail(tr("Камера недоступна", "Camera is unavailable"))
            return
        }

        val preview = choosePreviewSize(map, viewWidth, viewHeight)
        val analysis = chooseAnalysisSize(map)
        previewSize = preview
        textureView.surfaceTexture?.setDefaultBufferSize(preview.width, preview.height)
        if (viewWidth > 0 && viewHeight > 0) configureTransform(viewWidth, viewHeight, preview)

        val imageReader = runCatching {
            ImageReader.newInstance(analysis.width, analysis.height, ImageFormat.YUV_420_888, 2)
        }.getOrNull()
        if (imageReader == null) {
            fail(tr("Не удалось получить кадры камеры", "Could not read camera frames"))
            return
        }
        images = imageReader
        imageReader.setOnImageAvailableListener({ r -> drainFrame(r) }, handler)

        val flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        mainHandler.post { if (!closed) onTorchAvailable(flash) }

        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) {
                        camera.close()
                        return
                    }
                    device = camera
                    startSession(camera, imageReader)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, errorCode: Int) {
                    camera.close()
                    device = null
                    fail(
                        tr(
                            "Камера занята другим приложением или недоступна",
                            "The camera is busy or unavailable",
                        ),
                    )
                }
            }, handler)
        }.onFailure {
            fail(tr("Не удалось открыть камеру: ${it.message}", "Could not open the camera: ${it.message}"))
        }
    }

    private fun startSession(camera: CameraDevice, imageReader: ImageReader) {
        val surfaceTexture = textureView.surfaceTexture
        if (surfaceTexture == null) {
            fail(tr("Превью недоступно", "Preview is unavailable"))
            return
        }
        val preview = Surface(surfaceTexture)
        runCatching {
            camera.createCaptureSession(
                listOf(preview, imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed) {
                            s.close()
                            return
                        }
                        session = s
                        val request = runCatching {
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview)
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                )
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                        }.getOrNull() ?: return
                        runCatching { s.setRepeatingRequest(request, null, handler) }
                            .onFailure { fail(tr("Камера не запустилась", "The camera did not start")) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        fail(tr("Не удалось настроить камеру", "Could not configure the camera"))
                    }
                },
                handler,
            )
        }.onFailure {
            fail(tr("Не удалось настроить камеру: ${it.message}", "Could not configure the camera: ${it.message}"))
        }
    }

    private fun drainFrame(imageReader: ImageReader) {
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (closed) return
            // Больше ~12 кадров в секунду декодировать незачем: ZXing на слабом
            // телефоне не успевает, а очередь ImageReader начинает копиться.
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 80L) return
            lastFrameAt = now
            val text = decode(image)
            if (!text.isNullOrBlank() && !closed) {
                closed = true
                mainHandler.post { onCode(text) }
            }
        } catch (_: Throwable) {
            // битый кадр — ждём следующий
        } finally {
            runCatching { image.close() }
        }
    }

    private fun decode(image: Image): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        if (luminance.size != width * height) luminance = ByteArray(width * height)
        val data = luminance

        if (rowStride == width) {
            buffer.rewind()
            buffer.get(data, 0, min(buffer.remaining(), data.size))
        } else {
            // Y-плоскость лежит с выравниванием строк: копируем построчно,
            // иначе картинка разъезжается и код не находится.
            var out = 0
            var y = 0
            while (y < height && out < data.size) {
                val start = y * rowStride
                if (start >= buffer.limit()) break
                val length = min(width, buffer.limit() - start)
                buffer.position(start)
                buffer.get(data, out, length)
                out += length
                y++
            }
        }

        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        return runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)?.text?.trim()
        }.getOrNull()
    }

    private fun pickCameraId(): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull { candidate ->
            manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    /** Превью — близко к соотношению сторон экрана и не больше 2 Мп. */
    private fun choosePreviewSize(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        viewWidth: Int,
        viewHeight: Int,
    ): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val viewRatio = if (viewWidth > 0 && viewHeight > 0) {
            viewWidth.toFloat() / viewHeight
        } else {
            16f / 9f
        }
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .ifEmpty { sizes }
        return candidates.minByOrNull { size ->
            val ratioPenalty = abs(size.width.toFloat() / size.height - viewRatio) * 400_000
            val areaPenalty = abs(size.width * size.height - 1_280 * 720)
            ratioPenalty + areaPenalty
        } ?: sizes.first()
    }

    /** Кадры для ZXing — максимум 2 Мп, но не меньше 640 px по ширине. */
    private fun chooseAnalysisSize(
        map: android.hardware.camera2.params.StreamConfigurationMap,
    ): Size {
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val usable = sizes.filter { it.width * it.height <= 1920 * 1080 }
        val preferred = usable.filter { it.width >= 640 }.ifEmpty { usable }
        return preferred.minByOrNull { size ->
            abs(size.width * size.height - 1_280 * 720) + abs(size.width.toFloat() / size.height - 16f / 9f) * 200_000
        } ?: sizes.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int, preview: Size) {
        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // При повороте на 90/270 буфер и вид меняются местами.
        val bufferRect =
            if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                RectF(0f, 0f, preview.height.toFloat(), preview.width.toFloat())
            } else {
                RectF(0f, 0f, preview.width.toFloat(), preview.height.toFloat())
            }
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / preview.height,
                viewWidth.toFloat() / preview.width,
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun fail(message: String) {
        if (closed || reportedError) return
        reportedError = true
        mainHandler.post { onError(message) }
    }

    /** Идемпотентно: вызывается и из onDispose, и из onSurfaceTextureDestroyed. */
    fun stop() {
        closed = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { images?.close() }
        images = null
        runCatching { thread.quitSafely() }
    }
}
