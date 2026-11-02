package dev.local.veil.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Единый набор иконок приложения — стандартные Material Rounded.
 * Имена нейтральные, чтобы экранный код не зависел от конкретного набора.
 */
object AppIcons {
    val Server = Icons.Rounded.Dns
    val ServerAlt = Icons.Rounded.Storage
    val Cloud = Icons.Rounded.CloudSync
    val Routing = Icons.Rounded.AltRoute
    val Structure = Icons.Rounded.AccountTree
    val Settings = Icons.Rounded.Settings
    val Tuning = Icons.Rounded.Tune
    val Shield = Icons.Rounded.Shield
    val ShieldCheck = Icons.Rounded.VerifiedUser
    val Qr = Icons.Rounded.QrCode2
    val Eye = Icons.Rounded.Visibility
    val EyeOff = Icons.Rounded.VisibilityOff
    val Incognito = Icons.Rounded.Security
    val Bell = Icons.Rounded.NotificationsActive
    val Refresh = Icons.Rounded.Refresh
    val ChevronRight = Icons.Rounded.ChevronRight
    val ChevronDown = Icons.Rounded.ExpandMore
    val ChevronUp = Icons.Rounded.ExpandLess
    val ArrowLeft = Icons.AutoMirrored.Rounded.ArrowBack
    val Add = Icons.Rounded.Add
    val Close = Icons.Rounded.Close
    val Check = Icons.Rounded.Check
    val Copy = Icons.Rounded.ContentCopy
    val Share = Icons.Rounded.Share
    val Trash = Icons.Rounded.Delete
    val Info = Icons.Rounded.Info
    val Danger = Icons.Rounded.WarningAmber
    val Power = Icons.Rounded.PowerSettingsNew
    val Devices = Icons.Rounded.Apps
    val Sort = Icons.Rounded.Sort
    val Box = Icons.Rounded.Inventory2
    val Filter = Icons.Rounded.FilterList
    val Stopwatch = Icons.Rounded.Speed
    val Clock = Icons.Rounded.Schedule
    val Moon = Icons.Rounded.DarkMode
    val Sun = Icons.Rounded.LightMode
    val Update = Icons.Rounded.SystemUpdate
    val Document = Icons.Rounded.Description
    val Pause = Icons.Rounded.Pause
    val Play = Icons.Rounded.PlayArrow
    val Global = Icons.Rounded.Public
    val More = Icons.Rounded.MoreVert
    val Bolt = Icons.Rounded.Bolt
    val Block = Icons.Rounded.Block
    val Search = Icons.Rounded.Search
}

/** Иконка темы по умолчанию: размер и цвет берутся из `LocalContentColor`. */
@Composable
fun AppIcon(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(icon, contentDescription = contentDescription, modifier = modifier, tint = tint)
}
