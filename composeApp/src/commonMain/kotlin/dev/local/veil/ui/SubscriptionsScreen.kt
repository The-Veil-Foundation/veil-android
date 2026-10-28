package dev.local.veil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.veil.core.Subscription

@Composable
fun SubscriptionsScreen(
    state: VeilState,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Ссылка или share-ссылки") },
                placeholder = { Text("https://subs.example.com/…") },
                shape = RoundedCornerShape(20.dp),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        state.addInput(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !state.busy,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.busy) "Загружаю…" else "Добавить", fontWeight = FontWeight.Bold) }

                if (state.subscriptions.isNotEmpty()) {
                    TextButton(onClick = state::refreshAll, enabled = !state.busy) {
                        Text(if (state.busy) "…" else "Все")
                    }
                }
            }
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.subscriptions, key = { it.url }) { subscription ->
                SubscriptionCardRow(
                    subscription = subscription,
                    busy = state.busy,
                    onRefresh = { state.refreshSubscription(subscription) },
                    onDelete = { state.removeSubscription(subscription) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionCardRow(
    subscription: Subscription,
    busy: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = maskUrl(subscription.url),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Удалить", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = "${subscription.nodeCount} нод · обновлена ${formatAgo(subscription.lastFetched)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subscription.totalBytes > 0 || subscription.usedBytes > 0) {
                SubscriptionUsageBlock(subscription)
            }
            Row {
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (busy) " Обновляю…" else " Обновить")
                }
            }
        }
    }
}

/** Расход трафика тарифа: сколько съедено из лимита и до какой даты. */
@Composable
private fun SubscriptionUsageBlock(subscription: Subscription) {
    val total = subscription.totalBytes
    val used = subscription.usedBytes.coerceAtLeast(0)
    val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val leftDays = daysUntil(subscription.expireAt)
    val expiringSoon = leftDays <= 3

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (total > 0) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (fraction > 0.9f) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            Text(
                text = "${formatBytes(used)} из ${formatBytes(total)} · осталось ${formatBytes(total - used)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Text(
                text = "Израсходовано ${formatBytes(used)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (subscription.expireAt > 0) {
            Text(
                text = "до ${formatDate(subscription.expireAt)}" +
                    if (leftDays in 0..3) " · осталось $leftDays дн." else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (expiringSoon) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** URL подписки — это credential, целиком в интерфейсе его показывать нельзя. */
private fun maskUrl(url: String): String {
    val host = url.substringAfter("://").substringBefore('/')
    val tail = url.takeLast(4)
    return "$host/…$tail"
}
