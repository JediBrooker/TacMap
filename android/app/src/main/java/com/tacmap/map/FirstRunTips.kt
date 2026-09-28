package com.tacmap.map

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.Messages

/**
 * Four short tips shown once after the first unlock and reopenable from
 * Settings. Bump [CURRENT_VERSION] when the tips change enough to show again.
 * iOS mirrors this in `FirstRunTips.swift`.
 */
object FirstRunTips {
    const val CURRENT_VERSION = 1
    private const val PREFS = "first_run_tips"
    private const val KEY_SEEN_VERSION = "seen_version"

    class Tip(val icon: ImageVector, val title: String, val body: String)

    val tips: List<Tip>
        get() = listOf(
            Tip(Icons.Default.AddCircle, Messages.tipsPlaceTitle(), Messages.tipsPlaceBody()),
            Tip(Icons.Default.PanTool, Messages.tipsEditTitle(), Messages.tipsEditBody()),
            Tip(Icons.Default.SettingsInputAntenna, Messages.tipsShareTitle(), Messages.tipsShareBody()),
            Tip(Icons.Default.DarkMode, Messages.tipsNightTitle(), Messages.tipsNightBody()),
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldShow(context: Context): Boolean =
        prefs(context).getInt(KEY_SEEN_VERSION, 0) < CURRENT_VERSION

    /** Also used by instrumented tests before launching MainActivity, so the
     * tips never cover the map they drive. */
    fun markSeen(context: Context) {
        prefs(context).edit().putInt(KEY_SEEN_VERSION, CURRENT_VERSION).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_SEEN_VERSION).apply()
    }
}

@Composable
internal fun FirstRunTipsDialog(onFinished: () -> Unit) {
    val tips = FirstRunTips.tips
    var page by rememberSaveable { mutableIntStateOf(0) }
    val tip = tips[page.coerceIn(0, tips.lastIndex)]
    val isLast = page >= tips.lastIndex
    AlertDialog(
        onDismissRequest = onFinished,
        icon = { Icon(tip.icon, contentDescription = null, modifier = Modifier.size(36.dp)) },
        title = { Text(tip.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tip.body)
                Text(
                    Messages.tipsPage(
                        DisplayFormat.number((page + 1).toDouble(), 0),
                        DisplayFormat.number(tips.size.toDouble(), 0),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (isLast) onFinished() else page += 1 }) {
                Text(if (isLast) Messages.tipsDone() else Messages.tipsNext())
            }
        },
        dismissButton = if (isLast) null else {
            { TextButton(onClick = onFinished) { Text(Messages.tipsSkip()) } }
        },
    )
}
