package com.tacmap.map

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.tacmap.localization.Messages
import com.tacmap.waypoints.CustomSymbolStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Load after the Activity's mission-key gate; the file picker belongs to Import/Export. */
@Composable
internal fun CustomSymbolLibraryInfo(onLoaded: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { CustomSymbolStore.reload() }; onLoaded()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) { failed = true }
    }
    Text(if (failed) Messages.symbolsSymbolPackError() else Messages.symbolsImportLocation())
    CustomSymbolStore.packs.forEach { pack -> if (pack.attribution.isNotBlank()) Text(pack.attribution) }
}
