package com.tacmap.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.waypoints.CustomSymbolStore
import com.tacmap.waypoints.MarkerCatalog

@Composable
internal fun CustomSymbolPicker(entries: List<MarkerCatalog.Entry>, selected: MarkerCatalog.Entry, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    TextButton(onClick = { open = true }) { Text(selected.displayName) }
    if (open) AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { open = false },
        title = { Text(Messages.symbolsCustomSymbols()) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(L10n.text("Search")) })
                val results = entries.filter { com.tacmap.waypoints.CustomSymbolSearch.matches(it.displayName, query) }
                if (results.isEmpty()) Text(Messages.symbolsNoMatches())
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) {
                    items(results, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(entry.id); open = false }.padding(8.dp)) {
                            val bitmap = remember(entry.id) { CustomSymbolStore.symbol(entry.id)?.image()?.asImageBitmap() }
                            if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
                            Text(entry.displayName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(L10n.text("Done")) } }
    )
}
