package dev.local.veil.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Единый «сегментированный» стиль настроек — тот же, что в bottom sheet сервера:
 * каждая строка это отдельный скруглённый сегмент surfaceContainerHigh,
 * между строками зазор 2.dp, у крайних радиус 24.dp, у внутренних — 6.dp.
 * Никаких тонких линий-разделителей: роль разделителя играет сам зазор.
 */

/* ------------------------------------------------------------------ */
/* Контейнеры                                                          */
/* ------------------------------------------------------------------ */

/** Группа настроек: подпись с иконкой и «остров» из сегментов. */
@Composable
internal fun SettingsGroup(
    title: String,
    icon: ImageVector,
    rows: List<@Composable () -> Unit>,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(start = 2.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    icon,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        SegmentedGroup(rows = rows)
    }
}

/**
 * Сегментированный список как в bottom sheet: строки — отдельные скруглённые
 * сегменты с зазором 2.dp; крайние получают большой радиус, внутренние — малый.
 * Этот «разделитель» и есть зазор между сегментами.
 */
@Composable
internal fun SegmentedGroup(
    modifier: Modifier = Modifier,
    rows: List<@Composable () -> Unit>,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) 24.dp else 6.dp
            val bottom = if (index == rows.lastIndex) 24.dp else 6.dp
            Surface(
                shape = RoundedCornerShape(
                    topStart = top,
                    topEnd = top,
                    bottomStart = bottom,
                    bottomEnd = bottom,
                ),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                row()
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Строки                                                              */
/* ------------------------------------------------------------------ */

/** Переключатель: подпись, описание и Switch справа. */
@Composable
internal fun SegmentSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    SegmentRow(onClick = { onCheckedChange(!checked) }, interactionSource = source) {
        icon?.let { SegmentIcon(it) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            subtitle?.let { SegmentCaption(it) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Переход в другой экран или действие: подпись, описание и шеврон. */
@Composable
internal fun SegmentAction(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    SegmentRow(onClick = onClick) {
        icon?.let { SegmentIcon(it) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            subtitle?.let { SegmentCaption(it) }
        }
        AppIcon(
            AppIcons.ChevronRight,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Вариант выбора: подпись и ряд чипов под ней. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SegmentChoice(
    title: String,
    options: List<SegmentOption>,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            icon?.let { SegmentIcon(it) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                subtitle?.let { SegmentCaption(it) }
            }
        }
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, start = if (icon != null) 50.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                ChipSmall(
                    text = option.label,
                    selected = option.selected,
                    onClick = option.onClick,
                )
            }
        }
    }
}

/** Только информация: подпись слева, значение справа. */
@Composable
internal fun SegmentInfo(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Поле ввода внутри сегмента. */
@Composable
internal fun SegmentInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Вариант для [SegmentChoice]. */
internal class SegmentOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/* ------------------------------------------------------------------ */
/* Внутреннее                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun SegmentRow(
    onClick: (() -> Unit)?,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val scale = pressScale(interactionSource, pressed = 0.985f)
    val clickable = if (onClick != null) {
        Modifier.clickable(interactionSource = interactionSource, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickable)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun SegmentIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun SegmentCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/* ------------------------------------------------------------------ */
/* Чип                                                                 */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipSmall(text: String, selected: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "chip-bg",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chip-fg",
    )
    val corner by animateDpAsState(
        if (selected) 18.dp else 12.dp,
        tween(180),
        label = "chip-r",
    )
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source, 0.92f)
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(corner),
        color = color,
        contentColor = content,
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
