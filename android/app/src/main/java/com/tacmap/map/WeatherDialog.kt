package com.tacmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Weather + UAV flight-safety widget. Fetches current conditions from
 *  Open-Meteo and shows green/amber/red drone safety assessment. */
@Composable
fun WeatherDialog(lat: Double, lng: Double, onDismiss: () -> Unit) {
    var reading by remember { mutableStateOf<WeatherReading?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    val service = remember { WeatherService() }

    LaunchedEffect(lat, lng, attempt) {
        loading = true
        reading = service.reading(lat, lng)
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Weather & UAV Safety") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Fetching conditions…")
                    }
                    reading == null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Couldn't fetch conditions. If online lookups are off " +
                                "(Settings, Privacy & OPSEC), enable them; otherwise check your connection."
                        )
                        TextButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                    else -> {
                        val r = reading!!
                        RiskBanner(UAVAssessment.risk(r))
                        Metric("Wind", r.windSpeedMs, "m/s")
                        Metric("Gusts", r.windGustsMs, "m/s")
                        Metric("Visibility", r.visibilityM?.let { it / 1000 }, "km")
                        Metric("Temp", r.temperatureC, "°C")
                        Text(
                            "Source: Open-Meteo. UAV thresholds are defaults for small drones — advisory only, not a clearance.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun RiskBanner(risk: UAVRisk) {
    val color = when (risk) {
        UAVRisk.SAFE -> Color(0xFF2E7D32)
        UAVRisk.CAUTION -> Color(0xFFEF6C00)
        UAVRisk.DANGER -> Color(0xFFC62828)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(risk.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun Metric(name: String, value: Double?, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, color = Color.Gray)
        Text(
            value?.let { "%.1f %s".format(it, unit) } ?: "—",
            fontWeight = FontWeight.SemiBold
        )
    }
}
