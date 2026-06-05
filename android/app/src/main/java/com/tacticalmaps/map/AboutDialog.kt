package com.tacticalmaps.map

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.BuildConfig
import com.tacticalmaps.app.CrashReporter

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var crashReport by remember { mutableStateOf(CrashReporter.lastReport(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TacMap") },
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

                crashReport?.let { report ->
                    Text(
                        "A crash was recorded last run — nothing is sent anywhere.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "TacMap crash log")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(intent, "Export crash log"))
                    }) { Text("Export crash log", fontSize = 12.sp) }
                    TextButton(onClick = {
                        CrashReporter.clear(context)
                        crashReport = null
                    }) { Text("Clear crash log", fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
