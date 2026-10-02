package com.tacmap.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tacmap.calibration.ImportLimits
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.ui.AlertDialog
import kotlinx.coroutines.delay

/**
 * s9.1 progress: copying (percent) -> reading page i of n -> saving, with Cancel.
 * Only shows once the import has run 300 ms, a small sheet never flashes it.
 */
@Composable
internal fun ImportProgressCard(progress: ImportProgress, cancelling: Boolean, onCancel: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ImportLimits.PROGRESS_HUD_DELAY_MS)
        visible = true
    }
    if (!visible) return
    val (text, fraction) = when (progress) {
        is ImportProgress.Copying -> {
            val pct = progress.percent
            Messages.mapImportCopying(pct?.let { DisplayFormat.percent(it) } ?: "") to pct?.let { it / 100f }
        }
        is ImportProgress.Reading -> Messages.mapImportReading(progress.page.toString(), progress.pages.toString()) to
            (progress.page.toFloat() / progress.pages.coerceAtLeast(1))
        ImportProgress.Saving -> Messages.mapImportSaving() to null
    }
    AlertDialog(
        // only Cancel stops it, a stray tap outside doesn't
        onDismissRequest = {},
        confirmButton = {
            if (progress.cancelVisible) {
                TextButton(onClick = onCancel, enabled = !cancelling) { Text(L10n.text("Cancel")) }
            }
        },
        text = {
            Column {
                Text(text, modifier = Modifier.padding(bottom = 10.dp))
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    )
}
