package dev.local.veil.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

actual val qrSupported: Boolean = true

@Composable
actual fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (granted) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { view -> QrCameraController(ctx, view, onResult) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Нужен доступ к камере",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Разрешите камеру в настройках, чтобы сканировать QR-код подписки.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Рамка-подсказка
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(28.dp)),
            )

            Text(
                "Наведите на QR-код подписки или узла",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp),
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}

/**
 * Камера на camera2: превью в TextureView + кадры в ImageReader, декодирование
 * ZXing в фоновом потоке. Без Google Play Services — они на этом устройстве
 * не обязательны, а Maven-репозиторий Google недоступен.
 */
private class QrCameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val onCode: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("veil-qr").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    @Volatile private var found = false

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun openCamera(surfaceTexture: SurfaceTexture) {
        val id = runCatching {
            manager.cameraIdList.firstOrNull { candidate ->
                manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()
        }.getOrNull() ?: return

        val map = runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull() ?: return

        val size = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width <= 1600 }
            ?.minByOrNull { it.width * it.height }
            ?: Size(640, 480)

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        images = imageReader
        imageReader.setOnImageAvailableListener({ r -> decode(r) }, handler)

        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    val preview = Surface(surfaceTexture)
                    val capture = camera.createCaptureSession(
                        listOf(preview, imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(preview)
                                    addTarget(imageReader.surface)
                                    set(
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                    )
                                }
                                runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) = Unit
                        },
                        handler,
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                }
            }, handler)
        }
    }

    private fun decode(imageReader: ImageReader) {
        if (found) return
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val data = ByteArray(width * height)

            if (pixelStride == 1 && rowStride == width) {
                buffer.get(data, 0, minOf(buffer.remaining(), data.size))
            } else {
                val row = ByteArray(rowStride)
                for (y in 0 until height) {
                    val length = minOf(rowStride, buffer.remaining())
                    if (length <= 0) break
                    buffer.get(row, 0, length)
                    for (x in 0 until width) {
                        val index = x * pixelStride
                        if (index < length) data[y * width + x] = row[index]
                    }
                }
            }

            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
            val text = result?.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                found = true
                Handler(Looper.getMainLooper()).post { onCode(text) }
            }
        } catch (_: NotFoundException) {
            // кадр без кода — обычное дело
        } catch (_: Throwable) {
            // битый кадр: просто ждём следующий
        } finally {
            runCatching { image.close() }
        }
    }

    fun stop() {
        found = true
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { images?.close() }
        session = null
        device = null
        images = null
        runCatching { thread.quitSafely() }
    }
}
