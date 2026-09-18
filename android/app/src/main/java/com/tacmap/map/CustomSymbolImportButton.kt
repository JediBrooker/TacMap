package com.tacmap.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.waypoints.CustomSymbol
import com.tacmap.waypoints.CustomSymbolStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CustomSymbolImportButton(onLoaded: () -> Unit = {}, onImport: (CustomSymbol) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { CustomSymbolStore.reload() }; onLoaded()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) { failed = true }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val pack = withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { CustomSymbolStore.importPack(it) }
                    }
                    onImport(pack.symbols.first())
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                } catch (_: Exception) { failed = true
                } finally { busy = false }
            }
        }
    }
    TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/json", "application/octet-stream")) }) {
        Text(Messages.symbolsImportSymbolPack())
    }
    Text(Messages.symbolsSymbolPackHelp())
    CustomSymbolStore.packs.forEach { pack -> if (pack.attribution.isNotBlank()) Text(pack.attribution) }
    if (failed) AlertDialog(
        onDismissRequest = { failed = false },
        title = { Text(Messages.symbolsSymbolPackFailed()) },
        text = { Text(Messages.symbolsSymbolPackError()) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(Messages.acknowledge()) } }
    )
}
