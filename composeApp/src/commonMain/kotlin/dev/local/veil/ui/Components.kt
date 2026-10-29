package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.veil.core.ProxyConfig
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.VpnStatus
import kotlinx.coroutines.delay

/** Логотип: монета с буквой V. */
@Composable
fun VeilMark(size: androidx.compose.ui.unit.Dp = 36.dp, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        drawCircle(color = primary, radius = radius)
        drawCircle(
            color = onPrimary.copy(alpha = 0.35f),
            radius = radius - radius * 0.09f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius * 0.06f),
        )
        val w = radius * 0.46f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(center.x - w * 0.85f, center.y - w * 0.6f)
            lineTo(center.x, center.y + w * 0.75f)
            lineTo(center.x + w * 0.85f, center.y - w * 0.6f)
        }
        drawPath(
            path = path,
            color = onPrimary,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = radius * 0.2f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
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

    val container by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(500),
        label = "status-bg",
    )
    val content by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(500),
        label = "status-fg",
    )

    // Экспрессивные формы: при подключении карточка «раскрывается» в более
    // круглую, при отключении собирается обратно. Пружина мягкая, с отскоком.
    val big by animateFloatAsState(
        targetValue = if (connected) 52f else 32f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "status-corner",
    )
    val small by animateFloatAsState(
        targetValue = if (connected) 22f else 32f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "status-corner-small",
    )
    val lift by animateDpAsState(
        targetValue = if (connected) 6.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "status-lift",
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
        shadowElevation = lift,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(connected = connected, working = working)
                Spacer(Modifier.size(10.dp))
                Text(
                    text = when {
                        checking -> "Проверяем сервер…"
                        status.phase == VpnPhase.CONNECTED -> "Защищено"
                        status.phase == VpnPhase.CONNECTING -> "Подключаем…"
                        status.phase == VpnPhase.ERROR -> "Ошибка"
                        else -> "Не защищено"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(
                    visible = connected,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    Text(
                        text = formatDuration(seconds),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = shown?.name ?: "Сервер не выбран",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shown?.let { "${it.proto.wire.uppercase()} · ${it.endpoint}" } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            AnimatedVisibility(
                visible = connected,
                enter = expandVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeOut(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeedChip(label = "↓", bytesPerSecond = status.downlinkSpeed)
                    SpeedChip(label = "↑", bytesPerSecond = status.uplinkSpeed)
                }
            }

            val label = when {
                checking -> "Проверяем…"
                status.phase == VpnPhase.CONNECTING -> "Подключаем…"
                connected -> "Отключить"
                else -> "Подключить"
            }

            Button(
                onClick = onToggle,
                enabled = !working,
                shape = MaterialTheme.shapes.large,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.85f))
                            .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.85f))
                    },
                    label = "button-label",
                ) { text ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (text.endsWith("…")) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = if (text == "Отключить") Icons.Rounded.Shield
                                else Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(text, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Точка состояния с пульсирующим ореолом, когда туннель поднят. */
@Composable
private fun StatusDot(connected: Boolean, working: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "dot")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "dot-phase",
    )
    val dotColor by animateColorAsState(
        when {
            connected -> scheme.primary
            working -> scheme.tertiary
            else -> scheme.outline
        },
        label = "dot-color",
    )

    Canvas(Modifier.size(18.dp)) {
        if (connected) {
            drawCircle(
                color = dotColor.copy(alpha = (1f - phase) * 0.35f),
                radius = size.minDimension / 2f * (0.45f + phase * 0.55f),
            )
        }
        drawCircle(color = dotColor, radius = size.minDimension / 2f * 0.42f)
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
        animationSpec = tween(350),
        label = "compact-bg",
    )
    val content by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(350),
        label = "compact-fg",
    )
    val shape = RoundedCornerShape(26.dp)

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
            StatusDot(connected = connected, working = working)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = shown?.name ?: "Сервер не выбран",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(
                            when {
                                checkingServer != null -> "Проверяем…"
                                status.phase == VpnPhase.CONNECTED -> "Защищено"
                                status.phase == VpnPhase.CONNECTING -> "Подключаем…"
                                status.phase == VpnPhase.ERROR -> "Ошибка"
                                else -> "Не защищено"
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
                    Icon(
                        imageVector = if (connected) Icons.Rounded.Shield else Icons.Rounded.PowerSettingsNew,
                        contentDescription = if (connected) "Отключить" else "Подключить",
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
        animationSpec = tween(600),
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
    val (bg, fg) = when {
        pinging && !measured -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        !measured -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ms == null -> scheme.errorContainer to scheme.onErrorContainer
        ms < 120 -> scheme.primaryContainer to scheme.onPrimaryContainer
        ms < 300 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg, contentColor = fg) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pinging) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = fg)
            } else {
                Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.size(2.dp))
                Text(
                    text = ms?.let { "$it" } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Буллетин — всплывающее сообщение внизу экрана вместо баннера в списке.
 * Появляется пружинно, исчезает сам, тап закрывает досрочно.
 */
@Composable
fun BulletinHost(
    bulletin: Bulletin?,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Держим последнее непустое значение, иначе текст исчезнет до конца анимации.
    var last by remember { mutableStateOf<Bulletin?>(null) }
    if (bulletin != null) last = bulletin
    val shown = last

    AnimatedVisibility(
        visible = bulletin != null,
        enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.7f)) { it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it },
        modifier = modifier,
    ) {
        val current = shown ?: return@AnimatedVisibility
        LaunchedEffect(current.id) {
            delay(if (current.kind == BulletinKind.ERROR) 6000 else 3200)
            onDismiss(current.id)
        }

        val scheme = MaterialTheme.colorScheme
        val (bg, fg, icon) = when (current.kind) {
            BulletinKind.SUCCESS -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Rounded.CheckCircle)
            BulletinKind.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Rounded.ErrorOutline)
            BulletinKind.INFO -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Rounded.Info)
        }

        Surface(
            onClick = { onDismiss(current.id) },
            shape = MaterialTheme.shapes.large,
            color = bg,
            contentColor = fg,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = current.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Заголовок группы настроек. */
@Composable
fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp),
    )
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} МБ"
    else -> "${bytes / (1024L * 1024 * 1024)} ГБ"
}

/** Скорость: до сотни КБ/с — в килобайтах, дальше в мегабитах. */
fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond < 1024) return "$bytesPerSecond Б/с"
    if (bytesPerSecond < 100 * 1024) return "${bytesPerSecond / 1024} КБ/с"
    val megabits = bytesPerSecond * 8.0 / 1_000_000.0
    val tenths = (megabits * 10).toInt()
    return "${tenths / 10},${tenths % 10} Мбит/с"
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
    if (timestamp <= 0) return "не обновлялась"
    val minutes = (now - timestamp) / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 1440 -> "${minutes / 60} ч назад"
        else -> "${minutes / 1440} дн назад"
    }
}
