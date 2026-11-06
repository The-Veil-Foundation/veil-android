package dev.local.veil.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Material 3 Expressive на официальной библиотеке graphics-shapes.
 * Формы — RoundedPolygon, морфинг — через Morph (списки кубиков Безье),
 * Переходы формы используют пружины, индикатор загрузки вращается непрерывно.
 */

/**
 * Шкала форм M3 Expressive: крупные скругления для карточек и листов,
 * малые — для внутренних сегментов. Значения синхронизированы с темой.
 */
object Md3Shapes {
    val xs = 8.dp
    val sm = 12.dp
    val md = 20.dp
    val lg = 28.dp
    val xl = 36.dp

    val card = RoundedCornerShape(lg)
    val cardLarge = RoundedCornerShape(xl)
    val segmentOuter = RoundedCornerShape(lg)
    val segmentInner = RoundedCornerShape(10.dp)
    val sheet = RoundedCornerShape(topStart = xl, topEnd = xl)
    val dialog = RoundedCornerShape(lg)
    val pill = RoundedCornerShape(percent = 50)
}

/** Набор Expressive-форм (RoundedPolygon). */
object MaterialShapePresets {
    val Circle = RoundedPolygon.circle(numVertices = 16)
    val SoftTriangle = RoundedPolygon(3, rounding = CornerRounding(0.4f))
    val Clover = RoundedPolygon(4, rounding = CornerRounding(0.6f))
    val SoftPentagon = RoundedPolygon(5, rounding = CornerRounding(0.45f))
    val Flower = RoundedPolygon(6, rounding = CornerRounding(0.45f))
    val Sunny = RoundedPolygon(8, rounding = CornerRounding(0.35f))
    val Cookie = RoundedPolygon(9, rounding = CornerRounding(0.5f))
    val Scallop = RoundedPolygon(12, rounding = CornerRounding(0.35f))
    val SoftSquare = RoundedPolygon(4, rounding = CornerRounding(0.5f))
    val Burst = RoundedPolygon(12, rounding = CornerRounding(0.18f))
    val Diamond = RoundedPolygon(4, rounding = CornerRounding(0.25f)).also {
        // Поворот на 45° задаётся при морфинге через CubicsShape.
    }
}

/** Записывает замкнутый контур из кубиков Безье в [path]. */
private fun writeCubics(path: Path, cubics: List<Cubic>) {
    val first = cubics.firstOrNull() ?: return
    path.moveTo(first.anchor0X, first.anchor0Y)
    for (c in cubics) {
        path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
    }
    path.close()
}

/**
 * Compose [Shape] из списка кубиков: масштабирует единичный контур
 * (радиус 1, центр в 0,0) под заданный [size] и при желании поворачивает.
 */
class CubicsShape(
    private val cubics: List<Cubic>,
    private val rotationDegrees: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        writeCubics(path, cubics)
        val s = min(size.width, size.height) / 2f
        if (s != 0f) {
            val matrix = Matrix()
            // Порядок: масштаб → поворот → перенос (масштаб и поворот коммутируют,
            // перенос применяется последним, чтобы его не исказили).
            matrix.resetToPivotedTransform(
                pivotX = 0f,
                pivotY = 0f,
                translationX = size.width / 2f,
                translationY = size.height / 2f,
                rotationZ = rotationDegrees,
                scaleX = s,
                scaleY = s,
            )
            path.transform(matrix)
        }
        return Outline.Generic(path)
    }
}

/**
 * Переход из круга в [to] при [active], с учётом режима уменьшения анимаций.
 */
@Composable
fun rememberMorphShape(
    active: Boolean,
    to: RoundedPolygon = MaterialShapePresets.Cookie,
    turn: Float = 0.6f,
): Shape {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (LocalReduceMotion.current || LocalTvMode.current) androidx.compose.animation.core.snap()
            else VeilMotion.defaultSpatial,
        label = "morph",
    )
    val morph = remember(to) { Morph(MaterialShapePresets.Circle, to) }
    return CubicsShape(morph.asCubics(t.coerceIn(0f, 1f)), rotationDegrees = t * turn * 60f)
}

/** Material loading sequence: seven silhouettes, spring morph and continuous rotation. */
@Composable
fun ExpressiveLoader(
    modifier: Modifier = Modifier,
    diameter: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (LocalTvMode.current || LocalReduceMotion.current) {
        Canvas(modifier = modifier.progressSemantics().size(diameter)) {
            drawCircle(color = color, radius = size.minDimension / 2f)
        }
        return
    }
    val sequence = LoadingShapes.sequence
    // Пары соседних форм для морфа (последняя → первая, цикл).
    val morphs = remember(sequence) {
        List(sequence.size) { i -> Morph(sequence[i], sequence[(i + 1) % sequence.size]) }
    }

    var index by remember { mutableIntStateOf(0) }
    var turn by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    val rotation = remember { Animatable(0f) }
    // Переиспользуемый Path: без аллокации на каждом кадре.
    val path = remember { Path() }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                rotation.animateTo(360f, infiniteRepeatable(tween(4666, easing = LinearEasing)))
            }
            while (true) {
                val morph = launch {
                    progress.animateTo(1f, spring(dampingRatio = .6f, stiffness = 200f, visibilityThreshold = .1f))
                    index = (index + 1) % sequence.size
                    turn = (turn + 1) % 4
                    progress.snapTo(0f)
                }
                delay(650)
                morph.join()
            }
        }
    }

    Canvas(
        modifier = modifier
            .progressSemantics()
            .size(diameter)
            .drawWithCache {
                // Пружина допускает перелёт, а морф ожидает [0, 1].
                val t = progress.value.coerceIn(0f, 1f)

                path.reset()
                writeCubics(path, morphs[index].asCubics(t))

                // Rotation accumulates independently of the seven-shape index.
                val angle = 90f * (turn + progress.value) + rotation.value
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val scale = min(this.size.width, this.size.height) * .72f
                val bounds = path.getBounds()
                path.translate(-bounds.center)

                onDrawBehind {
                    rotate(degrees = angle, pivot = Offset(cx, cy)) {
                        translate(left = cx, top = cy) {
                            scale(scale = scale, pivot = Offset.Zero) {
                                drawPath(path, color)
                            }
                        }
                    }
                }
            },
    ) {}
}

/** Gesture-driven counterpart of the indeterminate loading indicator. */
@Composable
internal fun PullLoadingShape(progress: () -> Float) {
    val morph = remember { Morph(RoundedPolygon.circle().normalized(), LoadingShapes.sequence.first()) }
    val path = remember { Path() }
    val matrix = remember { Matrix() }
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(Modifier.size(36.dp)) {
        val fraction = progress().coerceIn(0f, 1f)
        path.reset()
        writeCubics(path, morph.asCubics(fraction))
        matrix.reset()
        matrix.scale(size.width * .72f, size.height * .72f)
        path.transform(matrix)
        path.translate(center - path.getBounds().center)
        rotate(-progress() * 180f) { drawPath(path, color) }
    }
}

/** Значок в «печенье», форма медленно вращается (содержимое остаётся на месте). */
@Composable
fun CookieBadge(
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    spinning: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (LocalTvMode.current || LocalReduceMotion.current || !spinning) {
        Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val s = min(size.width, size.height) / 2f
                translate(left = cx, top = cy) {
                    scale(scale = s, pivot = Offset.Zero) {
                        drawPath(cookiePathForBadge(), color)
                    }
                }
            }
            content()
        }
        return
    }
    val spin = rememberInfiniteTransition(label = "cookie")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "cookie-rotation",
    )
    val cookiePath = remember { Path().also { writeCubics(it, MaterialShapePresets.Cookie.cubics) } }

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val s = min(size.width, size.height) / 2f
            rotate(degrees = if (spinning) rotation else 0f, pivot = Offset(cx, cy)) {
                translate(left = cx, top = cy) {
                    scale(scale = s, pivot = Offset.Zero) {
                        drawPath(cookiePath, color)
                    }
                }
            }
        }
        content()
    }
}

private fun cookiePathForBadge(): Path =
    Path().also { writeCubics(it, MaterialShapePresets.Cookie.cubics) }

/**
 * Спиннер на экране подключения: прерывистый морфинг форм (треугольник →
 * сквиркл → пятиугольник → …) с резкими рывками поворота — имитация поиска и
 * инициализации VPN-сервера. Цвет по умолчанию — primary.
 */
@Composable
fun VpnConnectSpinner(
    modifier: Modifier = Modifier,
    diameter: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    ExpressiveLoader(modifier = modifier, diameter = diameter, color = color)
}

/** Точка статуса: при подключении морфится в пятиугольник. */
@Composable
fun StatusShape(connected: Boolean, working: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(
        when {
            connected -> scheme.primary
            working -> scheme.tertiary
            else -> scheme.outline
        },
        animationSpec = tween(180),
        label = "status-shape-color",
    )
    if (working) {
        ExpressiveLoader(modifier, 18.dp, color)
    } else {
        val shape = rememberMorphShape(active = connected, to = MaterialShapePresets.SoftPentagon)
        Box(modifier.size(18.dp).clip(shape).background(color))
    }
}
