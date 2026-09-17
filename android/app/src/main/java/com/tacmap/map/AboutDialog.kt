package com.tacmap.map

import com.tacmap.localization.L10n

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
import com.tacmap.BuildConfig
import com.tacmap.app.CrashReporter

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
                    L10n.text("Version %1\$s (%2\$s)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(L10n.text("Satellite: Esri World Imagery (Esri, Maxar, Earthstar Geographics)"), fontSize = 12.sp)
                Text(L10n.text("Topographic / street: Esri basemap styles · map data © OpenStreetMap contributors"), fontSize = 12.sp)
                Text(L10n.text("Terrain: OpenTopoMap (CC-BY-SA) · map data © OpenStreetMap contributors"), fontSize = 12.sp)
                Text(L10n.text("Elevation / weather: Open-Meteo · Copernicus DEM (CC BY 4.0)"), fontSize = 12.sp)
                Text(L10n.text("APP-6C symbols: spatialillusions/milsymbol"), fontSize = 12.sp)
                Text(L10n.text("Unit Sync transport: Java-WebSocket + SLF4J (MIT; notices bundled)"), fontSize = 12.sp)
                Text(L10n.text("PDF maps and overlays stay on this device unless exported."), fontSize = 12.sp)

                crashReport?.let { report ->
                    Text(
                        L10n.text("A crash was recorded last run — nothing is sent anywhere."),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, L10n.text("TacMap crash log"))
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(intent, L10n.text("Export crash log")))
                    }) { Text(L10n.text("Export crash log"), fontSize = 12.sp) }
                    TextButton(onClick = {
                        CrashReporter.clear(context)
                        crashReport = null
                    }) { Text(L10n.text("Clear crash log"), fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(L10n.text("Close"))
            }
        }
    )
}
