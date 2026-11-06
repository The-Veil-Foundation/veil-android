package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.veil.core.ProxyConfig
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.VpnStatus

/**
 * Логотип Veil — открытый защитный контур из Figma.
 * Цвета берутся из темы, поэтому знак подстраивается под системную палитру.
 */
@Composable
fun VeilMark(
    size: androidx.compose.ui.unit.Dp = 36.dp,
    modifier: Modifier = Modifier,
    background: Color = Color(0xFFDCE8D5),
    foreground: Color = Color(0xFF304E40),
) {
    Canvas(modifier.size(size)) {
        drawCircle(color = background, radius = this.size.minDimension / 2f)
        val s = this.size.minDimension / 192f
        val offset = (this.size.minDimension - 132.5f * s) / 2f
        translate(left = offset, top = offset) {
            scale(
                scale = s,
                pivot = androidx.compose.ui.geometry.Offset.Zero,
            ) {
                drawPath(leftShieldPath(), foreground)
                drawPath(rightShieldPath(), foreground)
            }
        }
    }
}

private fun leftShieldPath(): androidx.compose.ui.graphics.Path =
    androidx.compose.ui.graphics.Path().apply {
        moveTo(71.55f, 14.575f)
        lineTo(21.2f, 31.8f)
        lineTo(21.2f, 64.925f)
        cubicTo(21.2f, 88.775f, 34.45f, 106f, 53f, 116.6f)
        lineTo(60.95f, 98.05f)
        cubicTo(47.7f, 90.1f, 39.75f, 79.5f, 39.75f, 63.6f)
        lineTo(39.75f, 45.05f)
        lineTo(64.925f, 35.775f)
        lineTo(71.55f, 14.575f)
        close()
    }

private fun rightShieldPath(): androidx.compose.ui.graphics.Path =
    androidx.compose.ui.graphics.Path().apply {
        moveTo(86.125f, 19.875f)
        lineTo(111.3f, 29.15f)
        lineTo(111.3f, 63.6f)
        cubicTo(111.3f, 87.45f, 98.05f, 106f, 70.225f, 120.575f)
        lineTo(78.175f, 94.075f)
        cubicTo(87.45f, 84.8f, 92.75f, 74.2f, 92.75f, 62.275f)
        lineTo(92.75f, 43.725f)
        lineTo(79.5f, 38.425f)
        lineTo(86.125f, 19.875f)
        close()
    }

/** Крупная статус-карточка: состояние, трафик, таймер, кнопка. */
@Composable
fun StatusCard(
    status: VpnStatus,
    server: ProxyConfig?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    checkingServer: ProxyConfig? = null,
    now: Long = 0L,
) {
    val checking = checkingServer != null
    val shown = checkingServer ?: server

    // Время берём из connectedSince, а не считаем локально: иначе таймер
    // обнуляется, когда карточка уезжает из списка или приложение сворачивают.
    val seconds = if (status.connectedSince > 0 && now > 0) {
        ((now - status.connectedSince) / 1000).coerceAtLeast(0).toInt()
    } else {
        0
    }

    val connected = status.isConnected
    val working = checking || status.phase == VpnPhase.CONNECTING
    val reduceMotion = LocalReduceMotion.current || LocalTvMode.current
    val scheme = MaterialTheme.colorScheme

    val container by animateColorAsState(
        when {
            status.phase == VpnPhase.ERROR -> scheme.errorContainer
            connected -> scheme.primaryContainer
            else -> scheme.secondaryContainer
        },
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else tween(220),
        label = "status-bg",
    )
    val content by animateColorAsState(
        when {
            status.phase == VpnPhase.ERROR -> scheme.onErrorContainer
            connected -> scheme.onPrimaryContainer
            else -> scheme.onSecondaryContainer
        },
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else tween(220),
        label = "status-fg",
    )

    // Экспрессивная форма: карточка «раскрывается» при подключении, быстро.
    val big by animateFloatAsState(
        targetValue = if (connected) 44f else 32f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else VeilMotion.defaultSpatial,
        label = "status-corner",
    )
    val small by animateFloatAsState(
        targetValue = if (connected) 24f else 32f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else VeilMotion.defaultSpatial,
        label = "status-corner-small",
    )
    val cardShape = RoundedCornerShape(
        topStart = big.dp,
        topEnd = big.dp,
        bottomEnd = small.dp,
        bottomStart = big.dp,
    )

    Surface(
        color = container,
        contentColor = content,
        shape = cardShape,
        shadowElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = when {
                        checking -> tr("Проверяем сервер…", "Checking server…")
                        status.phase == VpnPhase.CONNECTED -> tr("Защищено", "Protected")
                        status.phase == VpnPhase.PAUSED -> tr("Пауза", "Paused")
                        status.phase == VpnPhase.CONNECTING -> tr("Подключаем…", "Connecting…")
                        status.phase == VpnPhase.ERROR -> tr("Ошибка", "Error")
                        else -> tr("Не защищено", "Unprotected")
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = rememberMorphShape(connected, MaterialShapePresets.Cookie, turn = .25f),
                    color = if (status.phase == VpnPhase.ERROR) scheme.error else scheme.primary,
                    contentColor = if (status.phase == VpnPhase.ERROR) scheme.onError else scheme.onPrimary,
                ) {
                    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                        if (working) ExpressiveLoader(diameter = 32.dp, color = scheme.onPrimary)
                        else AppIcon(
                            if (connected) AppIcons.Shield else if (status.phase == VpnPhase.ERROR) AppIcons.Danger else AppIcons.Power,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = shown?.name ?: tr("Сервер не выбран", "No server selected"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shown?.let {
                        if (connected) "${it.proto.wire.uppercase()} · ${formatDuration(seconds)}"
                        else "${it.proto.wire.uppercase()} · ${it.endpoint}"
                    } ?: tr("Выберите сервер ниже", "Choose a server below"),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            AnimatedVisibility(
                visible = connected,
                enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None else expandVertically(spring(dampingRatio = .85f, stiffness = 700f)) + fadeIn(),
                exit = if (reduceMotion) androidx.compose.animation.ExitTransition.None else shrinkVertically() + fadeOut(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeedChip(label = "↓", bytesPerSecond = status.downlinkSpeed)
                    SpeedChip(label = "↑", bytesPerSecond = status.uplinkSpeed)
                }
            }

            val label = when {
                checking -> tr("Проверяем…", "Checking…")
                status.phase == VpnPhase.CONNECTING -> tr("Подключаем…", "Connecting…")
                status.phase == VpnPhase.PAUSED -> tr("Возобновить", "Resume")
                connected -> tr("Отключить", "Disconnect")
                else -> tr("Подключить", "Connect")
            }

            ExpressiveActionButton(
                onClick = onToggle,
                enabled = !working,
                active = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        if (reduceMotion) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        else (fadeIn(tween(180)) + scaleIn(initialScale = 0.85f))
                            .togetherWith(fadeOut(tween(180)) + scaleOut(targetScale = 0.85f))
                    },
                    label = "button-label",
                ) { text ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (text.endsWith("…")) {
                            VpnConnectSpinner(
                                diameter = 22.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            AppIcon(
                                icon = if (connected) AppIcons.Shield else AppIcons.Power,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Компактная панель статуса: та же кнопка включения, но одной строкой.
 * Появляется, когда список серверов прокручен и большая карточка свёрнута.
 */
@Composable
fun CompactStatusBar(
    status: VpnStatus,
    server: ProxyConfig?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    checkingServer: ProxyConfig? = null,
    now: Long = 0L,
) {
    val connected = status.isConnected
    val working = checkingServer != null || status.phase == VpnPhase.CONNECTING
    val shown = checkingServer ?: server

    val seconds = if (status.connectedSince > 0 && now > 0) {
        ((now - status.connectedSince) / 1000).coerceAtLeast(0).toInt()
    } else {
        0
    }

    val container by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(180),
        label = "compact-bg",
    )
    val content by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(180),
        label = "compact-fg",
    )
    val shape = Md3Shapes.card

    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Вместо точки — форма: перетекает из круга в «печенье», когда туннель поднят.
            StatusShape(connected = connected, working = working)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = shown?.name ?: tr("Сервер не выбран", "No server selected"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(
                            when {
                                checkingServer != null -> tr("Проверяем…", "Checking…")
                                status.phase == VpnPhase.CONNECTED -> tr("Защищено", "Protected")
                                status.phase == VpnPhase.PAUSED -> tr("Пауза", "Paused")
                                status.phase == VpnPhase.CONNECTING -> tr("Подключаем…", "Connecting…")
                                status.phase == VpnPhase.ERROR -> tr("Ошибка", "Error")
                                else -> tr("Не защищено", "Unprotected")
                            },
                        )
                        if (connected) append(" · ").append(formatDuration(seconds))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (connected) {
                Text(
                    text = formatSpeed(status.downlinkSpeed),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            FilledIconButton(
                onClick = onToggle,
                enabled = !working,
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    AppIcon(
                        icon = if (connected) AppIcons.Shield else AppIcons.Power,
                        contentDescription = if (connected) tr("Отключить", "Disconnect") else tr("Подключить", "Connect"),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Скорость с плавным набором значения. */
@Composable
private fun SpeedChip(label: String, bytesPerSecond: Long) {
    val animated by animateFloatAsState(
        targetValue = bytesPerSecond.toFloat(),
        animationSpec = tween(180),
        label = "speed-$label",
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = "$label ${formatSpeed(animated.toLong())}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Пинг: цвет зависит от задержки. */
@Composable
fun PingPill(ms: Int?, measured: Boolean, pinging: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (bgTarget, fgTarget) = when {
        pinging && !measured -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        !measured -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ms == null -> scheme.errorContainer to scheme.onErrorContainer
        ms < 120 -> scheme.primaryContainer to scheme.onPrimaryContainer
        ms < 300 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    // Цвет плавно перетекает, когда замер закончился или пинг изменился.
    val bg by animateColorAsState(bgTarget, label = "ping-bg")
    val fg by animateColorAsState(fgTarget, label = "ping-fg")
    Surface(shape = RoundedCornerShape(10.dp), color = bg, contentColor = fg) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pinging && !measured) {
                ExpressiveLoader(diameter = 12.dp, color = fg)
            } else {
                AppIcon(AppIcons.Bolt, modifier = Modifier.size(12.dp))
                Spacer(Modifier.size(2.dp))
                AnimatedContent(
                    targetState = ms,
                    transitionSpec = {
                        (slideInVertically(tween(180)) { it } + fadeIn()) togetherWith
                            (slideOutVertically { -it } + fadeOut())
                    },
                    label = "ping-value",
                ) { value ->
                    Text(
                        text = value?.let { "$it" } ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val tb = gb * 1024
    fun scaled(value: Double, unit: String): String {
        // Одна цифра после запятой, пока она что-то значит: «12,4 ГБ», но «240 ГБ».
        val text = if (value < 100) {
            val tenths = kotlin.math.round(value * 10).toLong()
            val separator = if (AppLocale.language == dev.local.veil.core.AppLanguage.EN) "." else ","
            if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}$separator${tenths % 10}"
        } else {
            kotlin.math.round(value).toLong().toString()
        }
        return "$text $unit"
    }
    return when {
        bytes < 1024 -> "$bytes " + tr("Б", "B")
        bytes < mb -> scaled(bytes / kb, tr("КБ", "KB"))
        bytes < gb -> scaled(bytes / mb, tr("МБ", "MB"))
        bytes < tb -> scaled(bytes / gb, tr("ГБ", "GB"))
        else -> scaled(bytes / tb, tr("ТБ", "TB"))
    }
}

/** Русское множественное число: 1 день, 2 дня, 5 дней. */
fun ruPlural(n: Long, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** Сколько осталось до конца подписки: «37 дней», «5 ч», «истекла». */
fun formatRemaining(epochSeconds: Long, now: Long = currentTimeMillis()): String {
    val seconds = epochSeconds - now / 1000
    if (seconds <= 0) return tr("истекла", "expired")
    val days = seconds / 86400
    if (days >= 1) return tr("осталось $days ${ruPlural(days, "день", "дня", "дней")}", if (days == 1L) "1 day left" else "$days days left")
    val hours = (seconds / 3600).coerceAtLeast(1)
    return tr("осталось $hours ч", "$hours h left")
}

/** Скорость: до сотни КБ/с — в килобайтах, дальше в мегабитах. */
fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond < 1024) return "$bytesPerSecond " + tr("Б/с", "B/s")
    if (bytesPerSecond < 100 * 1024) return "${bytesPerSecond / 1024} " + tr("КБ/с", "KB/s")
    val megabits = bytesPerSecond * 8.0 / 1_000_000.0
    val tenths = (megabits * 10).toInt()
    val separator = if (AppLocale.language == dev.local.veil.core.AppLanguage.EN) "." else ","
    return "${tenths / 10}$separator${tenths % 10} " + tr("Мбит/с", "Mbit/s")
}

fun formatDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return listOf(h, m, s).joinToString(":") { it.toString().padStart(2, '0') }
}

/** Дата из epoch-секунд в виде 12.03.2026 (алгоритм civil_from_days). */
fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "—"
    val z = epochSeconds / 86400 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val yearBase = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = if (month <= 2) yearBase + 1 else yearBase
    return "${day.toString().padStart(2, '0')}.${month.toString().padStart(2, '0')}.$year"
}

/** Сколько осталось до даты; отрицательное — просрочено. */
fun daysUntil(epochSeconds: Long, now: Long = currentTimeMillis()): Long =
    if (epochSeconds <= 0) Long.MAX_VALUE else (epochSeconds - now / 1000) / 86400

fun formatAgo(timestamp: Long, now: Long = currentTimeMillis()): String {
    if (timestamp <= 0) return tr("не обновлялась", "never updated")
    val minutes = (now - timestamp) / 60_000
    return when {
        minutes < 1 -> tr("только что", "just now")
        minutes < 60 -> tr("$minutes мин назад", "$minutes min ago")
        minutes < 1440 -> tr("${minutes / 60} ч назад", "${minutes / 60} h ago")
        else -> tr("${minutes / 1440} дн назад", "${minutes / 1440} d ago")
    }
}
