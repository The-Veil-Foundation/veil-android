package dev.local.veil.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

actual val qrSupported: Boolean = true

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val QR_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

/**
 * Пробует источник несколькими способами: обычный бинаризатор, глобальный
 * (лучше на ровно освещённом экране) и в инверсии (светлый код на тёмном).
 */
private fun MultiFormatReader.decodeAll(source: LuminanceSource, thorough: Boolean): String? {
    val attempts = buildList {
        add(BinaryBitmap(HybridBinarizer(source)))
        if (thorough) {
            add(BinaryBitmap(GlobalHistogramBinarizer(source)))
            add(BinaryBitmap(HybridBinarizer(source.invert())))
        }
    }
    for (bitmap in attempts) {
        val text = runCatching { decodeWithState(bitmap)?.text?.trim() }.getOrNull()
        reset()
        if (!text.isNullOrBlank()) return text
    }
    return null
}

/** QR со скриншота или фото из галереи. */
private fun decodeImage(context: Context, uri: Uri): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    bitmap.recycle()
    val reader = MultiFormatReader().apply { setHints(QR_HINTS) }
    return reader.decodeAll(source, thorough = true)
}

/**
 * Полноэкранный сканер QR на camera2 + ZXing.
 *
 *  - превью center-crop без искажений, кадры для декодера ~1080p;
 *  - пока сканер открыт, экран зафиксирован в портрете: так рамка, кнопки и
 *    превью не разъезжаются при повороте телефона;
 *  - вспышка через запрос камеры, а не через setTorchMode;
 *  - можно выбрать скриншот с QR из галереи (разрешение камеры для этого не нужно).
 */
@Composable
actual fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var requested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    var decodingImage by remember { mutableStateOf(false) }

    fun deliver(code: String) {
        if (delivered) return
        delivered = true
        onResult(code)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (!ok) {
            error = tr(
                "Без доступа к камере можно выбрать QR из фото или разрешить камеру в настройках.",
                "Without camera access you can pick a QR from photos or allow the camera in settings.",
            )
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decodingImage = true
        scope.launch {
            val text = withContext(Dispatchers.Default) { runCatching { decodeImage(context, uri) }.getOrNull() }
            decodingImage = false
            if (text != null) {
                deliver(text)
            } else {
                error = tr("На фото не нашлось QR-кода", "No QR code found in the photo")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Портрет на время сканирования; прежнюю ориентацию возвращаем при закрытии.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (activity != null && previous != null) activity.requestedOrientation = previous
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val frame: Dp = min(maxWidth * 0.72f, 320.dp)
            // Рамка чуть выше центра — так под ней помещается подсказка.
            val frameShift: Dp = -(maxHeight * 0.04f)

            val controller = remember { mutableStateOf<QrCameraController?>(null) }
            if (granted) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { view ->
                            controller.value = QrCameraController(
                                context = ctx,
                                textureView = view,
                                onCode = ::deliver,
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
            }

            ViewfinderOverlay(frame = frame, shift = frameShift, active = granted && error == null)

            // Подсказка под рамкой.
            Text(
                error ?: tr(
                    "Наведите на QR-код подписки или сервера",
                    "Point at a subscription or server QR code",
                ),
                color = if (error == null) Color.White else Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = frameShift + frame / 2 + 36.dp)
                    .padding(horizontal = 32.dp),
            )

            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(Icons.Rounded.Close, tr("Закрыть", "Close"), onClick = onDismiss)
                    Text(
                        tr("Сканер QR", "Scan QR"),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    if (granted && torchAvailable) {
                        RoundControl(
                            icon = if (torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                            description = tr("Вспышка", "Flashlight"),
                            highlighted = torchOn,
                            onClick = {
                                val c = controller.value ?: return@RoundControl
                                torchOn = !torchOn
                                c.setTorch(torchOn)
                            },
                        )
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }

                Spacer(Modifier.weight(1f))

                if (!granted) {
                    PillButton(
                        text = tr("Разрешить камеру", "Allow camera"),
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                PillButton(
                    text = if (decodingImage) tr("Ищу QR…", "Looking for QR…") else tr("Из фото", "From photo"),
                    icon = Icons.Rounded.PhotoLibrary,
                    onClick = {
                        if (decodingImage) return@PillButton
                        error = null
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Затемнение вокруг рамки и уголки, как в системной камере. */
@Composable
private fun ViewfinderOverlay(frame: Dp, shift: Dp, active: Boolean) {
    val corner by animateColorAsState(
        if (active) Color.White else Color.White.copy(alpha = 0.45f),
        animationSpec = tween(160),
        label = "corner",
    )
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val side = frame.toPx()
        val radius = 28.dp.toPx()
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f + shift.toPx()
        drawRect(Color.Black.copy(alpha = 0.52f))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = GeoSize(side, side),
            cornerRadius = CornerRadius(radius, radius),
            blendMode = BlendMode.Clear,
        )

        // Уголки: дуга скругления + короткие отрезки по сторонам.
        val stroke = 4.dp.toPx()
        val arm = side * 0.16f
        val inset = stroke / 2f
        val l = left + inset
        val t = top + inset
        val r = left + side - inset
        val b = top + side - inset
        val rr = radius - inset
        val path = Path().apply {
            // левый верхний
            moveTo(l, t + rr + arm); lineTo(l, t + rr)
            arcTo(androidx.compose.ui.geometry.Rect(l, t, l + 2 * rr, t + 2 * rr), 180f, 90f, false)
            lineTo(l + rr + arm, t)
            // правый верхний
            moveTo(r - rr - arm, t); lineTo(r - rr, t)
            arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rr, t, r, t + 2 * rr), 270f, 90f, false)
            lineTo(r, t + rr + arm)
            // правый нижний
            moveTo(r, b - rr - arm); lineTo(r, b - rr)
            arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rr, b - 2 * rr, r, b), 0f, 90f, false)
            lineTo(r - rr - arm, b)
            // левый нижний
            moveTo(l + rr + arm, b); lineTo(l + rr, b)
            arcTo(androidx.compose.ui.geometry.Rect(l, b - 2 * rr, l + 2 * rr, b), 90f, 90f, false)
            lineTo(l, b - rr - arm)
        }
        drawPath(path, corner, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (highlighted) Color.White else Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (highlighted) Color.Black else Color.White)
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
    // Подсказки ставим один раз: decode(bitmap, hints) пересоздаёт ридеры на
    // каждом кадре, а decodeWithState переиспользует их.
    private val reader = MultiFormatReader().apply { setHints(QR_HINTS) }
    private var frameIndex = 0
    private var sensorOrientation = 90
    private var zoomRatio: Float? = null

    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var previewSize: Size? = null
    private var luminance = ByteArray(0)
    private var lastFrameAt = 0L
    private var torch = false
    private var requestBuilder: CaptureRequest.Builder? = null
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

    /**
     * Вспышку включаем флагом в повторяющемся запросе: CameraManager.setTorchMode
     * не работает, пока камера открыта этим же приложением.
     */
    fun setTorch(on: Boolean) {
        handler.post {
            val builder = requestBuilder ?: return@post
            val s = session ?: return@post
            torch = on
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
            )
            runCatching { s.setRepeatingRequest(builder.build(), null, handler) }
        }
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

        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        zoomRatio = chooseZoom(characteristics)
        val preview = choosePreviewSize(map, viewWidth, viewHeight)
        val analysis = chooseAnalysisSize(map, preview)
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
                        val builder = runCatching {
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview)
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                )
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                // Лёгкий зум: телефон можно держать дальше, где
                                // автофокус ещё работает, а модули плотного кода
                                // всё равно крупнее на кадре.
                                if (android.os.Build.VERSION.SDK_INT >= 30) {
                                    zoomRatio?.let { set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
                                }
                                if (torch) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                            }
                        }.getOrNull() ?: return
                        requestBuilder = builder
                        runCatching { s.setRepeatingRequest(builder.build(), null, handler) }
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
            if (now - lastFrameAt < 40L) return
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

        // Код почти всегда в центре, под рамкой: декодируем центральный
        // квадрат — меньше пикселей, быстрее и меньше ложных «находок» по краям.
        // Каждый третий кадр смотрим кадр целиком (код у края или очень крупный),
        // а через кадр — другим бинаризатором и в инверсии (светлый код на тёмном).
        val index = frameIndex++
        val side = (min(width, height) * 0.85f).toInt()
        val source: LuminanceSource = if (index % 3 == 2) {
            PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        } else {
            PlanarYUVLuminanceSource(
                data, width, height, (width - side) / 2, (height - side) / 2, side, side, false,
            )
        }
        return reader.decodeAll(source, thorough = index % 2 == 1)
    }

    private fun pickCameraId(): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull { candidate ->
            manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    /**
     * Превью — близко к соотношению сторон вида и не больше 1080p. Размеры камеры
     * всегда «альбомные» (ширина > высоты), а вид в портрете — наоборот, поэтому
     * сравниваем длинную сторону с короткой, а не width/height как есть.
     */
    private fun choosePreviewSize(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        viewWidth: Int,
        viewHeight: Int,
    ): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1920, 1080)
        val viewRatio = if (viewWidth > 0 && viewHeight > 0) {
            max(viewWidth, viewHeight).toFloat() / min(viewWidth, viewHeight)
        } else {
            16f / 9f
        }
        // Шире 16:9 камера не даёт, а 4:3 на вытянутом экране обрезается сильнее.
        val targetRatio = min(viewRatio, 16f / 9f)
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1080 && it.height >= 480 }
            .ifEmpty { sizes }
        return candidates.minByOrNull { size ->
            val ratio = max(size.width, size.height).toFloat() / min(size.width, size.height)
            abs(ratio - targetRatio) * 4_000_000 + abs(size.width * size.height - 1_920 * 1_080)
        } ?: sizes.first()
    }

    /**
     * Кадры для ZXing — того же соотношения сторон, что и превью (чтобы рамка на
     * экране совпадала с тем, что декодируется), и как можно ближе к 1080p:
     * плотный vless-QR на 720p даёт ~3 пикселя на модуль и не читается.
     */
    private fun chooseAnalysisSize(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        preview: Size,
    ): Size {
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val previewRatio = preview.width.toFloat() / preview.height
        val usable = sizes.filter { it.width * it.height <= 1920 * 1080 && it.height >= 480 }
            .ifEmpty { sizes.filter { it.width * it.height <= 1920 * 1080 } }
            .ifEmpty { sizes }
        return usable.minByOrNull { size ->
            abs(size.width.toFloat() / size.height - previewRatio) * 4_000_000 +
                abs(size.width * size.height - 1_920 * 1_080)
        } ?: Size(1280, 720)
    }

    /** ~1.5× если камера умеет (API 30+), иначе без зума. */
    private fun chooseZoom(characteristics: CameraCharacteristics): Float? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return null
        if (range.upper < 1.5f) return null
        return 1.5f.coerceIn(range.lower, range.upper)
    }

    /**
     * TextureView растягивает буфер камеры на весь вид. Камера сама поворачивает
     * кадр под естественную ориентацию устройства, так что в портрете буфер
     * 1920×1080 показывается как 1080×1920 — и сжимается под вытянутый экран.
     * Здесь считаем матрицу center-crop: картинка заполняет вид без искажений.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int, preview: Size) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val cx = vw / 2f
        val cy = vh / 2f
        val matrix = Matrix()

        // Буфер уже повернут на ориентацию сенсора (под ROTATION_0). Его размеры
        // в этой «естественной» ориентации:
        val sensorSideways = sensorOrientation % 180 != 0
        val naturalW = if (sensorSideways) preview.height.toFloat() else preview.width.toFloat()
        val naturalH = if (sensorSideways) preview.width.toFloat() else preview.height.toFloat()
        // Повёрнутый на 90/270 дисплей меняет стороны контента местами.
        val displaySideways = displayDegrees % 180 != 0
        val contentW = if (displaySideways) naturalH else naturalW
        val contentH = if (displaySideways) naturalW else naturalH

        if (displaySideways) {
            // Схема из Camera2Basic: вид → буфер со сменой сторон, center-crop,
            // затем поворот против поворота дисплея.
            val viewRect = RectF(0f, 0f, vw, vh)
            val bufferRect = RectF(0f, 0f, preview.height.toFloat(), preview.width.toFloat())
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / preview.height, vw / preview.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(-displayDegrees.toFloat(), cx, cy)
        } else {
            val scale = max(vw / contentW, vh / contentH)
            matrix.setScale(contentW * scale / vw, contentH * scale / vh, cx, cy)
            if (displayDegrees == 180) matrix.postRotate(180f, cx, cy)
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
        requestBuilder = null
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
