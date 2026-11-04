package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.veil.core.SplitMode

/**
 * Раздельный туннель в духе Material 3 Expressive: крупные формы, пружинные
 * переходы, «сегментированный» список с группированными скруглениями.
 *
 * Режим EXCLUDE — в VPN всё, кроме отмеченных; INCLUDE — только отмеченные.
 */
@Composable
fun SplitTunnelScreen(
    state: VeilState,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val settings = state.settings
    val enabled = settings.splitMode != SplitMode.OFF

    LaunchedEffect(Unit) { state.loadApps() }

    // Изменения применяются при выходе с экрана: туннель пересоздаётся один раз.
    DisposableEffect(Unit) {
        state.beginSplitEdit()
        onDispose { state.finishSplitEdit() }
    }

    val filtered = remember(state.apps, query, showSystem, settings.splitPackages) {
        state.apps.filter { app ->
            (showSystem || !app.isSystem || app.packageName in settings.splitPackages) &&
                (query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "modes") {
            ModeSelector(selected = settings.splitMode, onSelect = state::setSplitMode)
            Spacer(Modifier.size(12.dp))
        }

        item(key = "hint") {
            val warn = settings.splitMode == SplitMode.INCLUDE && settings.splitPackages.isEmpty()
            InfoCard(
                icon = if (warn) AppIcons.Danger else AppIcons.Info,
                text = when {
                    warn -> tr("Отметьте хотя бы одно приложение — иначе в туннель нечего пускать.", "Select at least one app — otherwise nothing will go through the tunnel.")
                    settings.splitMode == SplitMode.OFF -> tr("Весь трафик идёт через VPN. Выберите «Кроме» или «Только», чтобы отметить приложения.", "All traffic goes through the VPN. Choose “Except” or “Only” to select apps.")
                    settings.splitMode == SplitMode.EXCLUDE -> tr("Отмеченные приложения пойдут мимо туннеля.", "Selected apps will bypass the tunnel.")
                    else -> tr("Через туннель пойдут только отмеченные приложения. Остальные — напрямую.", "Only selected apps will go through the tunnel. The rest go direct.")
                },
                warning = warn,
            )
            Spacer(Modifier.size(12.dp))
        }

        item(key = "search") {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                placeholder = { Text(tr("Поиск приложения", "Search apps")) },
                leadingIcon = { AppIcon(AppIcons.Search) },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = scaleIn(tween(180)) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        IconButton(onClick = { query = "" }) {
                            AppIcon(AppIcons.Close, contentDescription = tr("Очистить", "Clear"))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions.Default,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text(tr("Системные", "System")) },
                    shape = RoundedCornerShape(12.dp),
                )
                AnimatedContent(
                    targetState = settings.splitPackages.size,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(tween(180)) { if (up) it else -it } + fadeIn()) togetherWith
                            (slideOutVertically { if (up) -it else it } + fadeOut())
                    },
                    modifier = Modifier.weight(1f),
                    label = "count",
                ) { count ->
                    Text(
                        text = tr("Выбрано: $count", "Selected: $count"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = settings.splitPackages.isNotEmpty(),
                    enter = scaleIn(tween(180)) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    TextButton(onClick = state::clearSplitPackages) { Text(tr("Сбросить", "Reset")) }
                }
            }
        }

        if (state.appsLoading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    ExpressiveLoader(diameter = 48.dp)
                }
            }
        } else if (filtered.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = if (state.apps.isEmpty()) tr("Список приложений недоступен", "App list unavailable") else tr("Ничего не найдено", "Nothing found"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        } else {
            val last = filtered.lastIndex
            items(filtered.size, key = { filtered[it].packageName }) { index ->
                val app = filtered[index]
                val checked = app.packageName in settings.splitPackages
                val color by animateColorAsState(
                    if (checked && enabled) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    label = "app-bg",
                )
                // Выбранная строка «вспухает» в пузырь, остальные остаются сегментами.
                val popped = checked && enabled
                val cornerSpec = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.6f, stiffness = 420f)
                val top by animateDpAsState(
                    if (popped) 28.dp else if (index == 0) 24.dp else 6.dp, cornerSpec, label = "top",
                )
                val bottom by animateDpAsState(
                    if (popped) 28.dp else if (index == last) 24.dp else 6.dp, cornerSpec, label = "bottom",
                )
                val source = remember { MutableInteractionSource() }
                val scale = pressScale(source, 0.97f)
                Surface(
                    onClick = { state.toggleSplitPackage(app.packageName) },
                    enabled = enabled,
                    interactionSource = source,
                    shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .alpha(if (enabled) 1f else 0.5f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(state, app.packageName, app.label, popped)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (app.isSystem) tr("${app.packageName} · системное", "${app.packageName} · system") else app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        CheckBadge(checked = checked, enabled = enabled)
                    }
                }
            }
        }
    }
}

private data class ModeItem(val mode: SplitMode, val title: String, val subtitle: String, val icon: ImageVector)

private val modeItems = listOf(
    ModeItem(SplitMode.OFF, tr("Выкл", "Off"), tr("Всё в VPN", "All in VPN"), AppIcons.Global),
    ModeItem(SplitMode.EXCLUDE, tr("Кроме", "Except"), tr("Мимо VPN", "Bypass VPN"), AppIcons.Block),
    ModeItem(SplitMode.INCLUDE, tr("Только", "Only"), tr("Только в VPN", "Only in VPN"), AppIcons.Check),
)

/** Выбранная кнопка расширяется и округляется пружинно (Expressive). */
@Composable
private fun ModeSelector(selected: SplitMode, onSelect: (SplitMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        modeItems.forEach { item ->
            val isSelected = item.mode == selected
            val spec: androidx.compose.animation.core.AnimationSpec<Float> =
                androidx.compose.animation.core.tween(180)
            val weight by animateFloatAsState(if (isSelected) 1.35f else 1f, spec, label = "mode-w")
            val corner by animateDpAsState(
                if (isSelected) 32.dp else 16.dp,
                tween(180),
                label = "mode-r",
            )
            val container by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "mode-bg",
            )
            val content by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "mode-fg",
            )
            val source = remember { MutableInteractionSource() }
            val press = pressScale(source, 0.92f)
            val iconScale by animateFloatAsState(
                if (isSelected) 1.25f else 1f,
                tween(180),
                label = "mode-icon",
            )
            Surface(
                onClick = { onSelect(item.mode) },
                interactionSource = source,
                shape = RoundedCornerShape(corner),
                color = container,
                contentColor = content,
                modifier = Modifier
                    .weight(weight)
                    .graphicsLayer { scaleX = press; scaleY = press },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppIcon(
                        item.icon,
                        modifier = Modifier.size(26.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                    )
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                    )
                    Text(
                        item.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, text: String, warning: Boolean) {
    val container by animateColorAsState(
        if (warning) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        label = "info-bg",
    )
    val content = if (warning) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.animateContentSize(tween(180)),
    ) {
        AnimatedContent(
            targetState = icon to text,
            transitionSpec = {
                (slideInVertically(tween(180)) { it / 3 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 3 } + fadeOut())
            },
            label = "info",
        ) { (shownIcon, shownText) ->
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(shownIcon)
                Text(shownText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AppIcon(state: VeilState, packageName: String, label: String, selected: Boolean) {
    val icon by produceState<ImageBitmap?>(initialValue = state.cachedIcon(packageName), key1 = packageName) {
        value = state.appIcon(packageName)
    }
    val bitmap = icon
    val corner by animateDpAsState(
        if (selected) 23.dp else 16.dp,
        tween(180),
        label = "icon-r",
    )
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Индикатор выбора: морфится в печенье при активации (Expressive). */
@Composable
private fun CheckBadge(checked: Boolean, enabled: Boolean) {
    val active = checked && enabled
    val badgeShape = rememberMorphShape(active = active, to = MaterialShapePresets.Cookie)
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        label = "badge-bg",
    )
    Box(
        Modifier
            .size(30.dp)
            .clip(badgeShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = checked,
            enter = scaleIn(tween(180)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            AppIcon(
                AppIcons.Check,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
