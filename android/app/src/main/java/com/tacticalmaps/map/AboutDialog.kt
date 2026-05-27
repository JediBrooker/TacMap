package com.tacticalmaps.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.BuildConfig

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TacticalMaps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Map data: OpenStreetMap contributors", fontSize = 12.sp)
                Text("APP-6C symbols: spatialillusions/milsymbol", fontSize = 12.sp)
                Text("PDF maps and overlays stay on this device unless exported.", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
