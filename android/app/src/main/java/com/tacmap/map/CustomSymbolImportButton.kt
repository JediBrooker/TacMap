package com.tacmap.map

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.tacmap.localization.L10n
import androidx.compose.runtime.*
import com.tacmap.localization.Messages
import com.tacmap.waypoints.CustomSymbolStore

/** Load after the Activity's mission-key gate; the file picker belongs to Import/Export. */
@Composable
internal fun CustomSymbolLibraryInfo(onLoaded: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    var packs by remember { mutableStateOf(CustomSymbolStore.packs) }
    LaunchedEffect(Unit) {
        try { CustomSymbolStore.reload(); packs = CustomSymbolStore.packs; onLoaded()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) { failed = true }
    }
    Text(if (failed) Messages.symbolsSymbolPackError() else Messages.symbolsImportLocation(), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
    var showCredits by remember { mutableStateOf(false) }
    if (packs.any { it.attribution.isNotBlank() }) {
        TextButton(onClick = { showCredits = !showCredits }) { Text(L10n.text("About & Credits")) }
        if (showCredits) packs.forEach { pack ->
            if (pack.attribution.isNotBlank()) Text(pack.attribution, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}
