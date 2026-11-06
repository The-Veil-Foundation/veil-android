package dev.local.veil.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Latest message wins; cancelling showSnackbar also removes the previous message. */
@Composable
internal fun BulletinHost(
    bulletin: Bulletin?,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    val dismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(bulletin?.id) {
        val current = bulletin ?: return@LaunchedEffect
        hostState.showSnackbar(
            message = current.text,
            withDismissAction = true,
            duration = if (current.kind == BulletinKind.ERROR) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        dismiss(current.id)
    }

    // SnackbarHost handles accessibility announcements and recommended timeouts.
    SnackbarHost(hostState = hostState, modifier = modifier.widthIn(max = 560.dp))
}
