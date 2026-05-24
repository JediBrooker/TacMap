package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BoxScope.MapBottomBar(
    onCentre: () -> Unit,
    speedKmH: Double?,
    elevationM: Double?,
    lastUpdateEpochMs: Long?
) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ScaleBarPlaceholder()

        CentreButton(onCentre)

        TelemetryPanel(speedKmH, elevationM, lastUpdateEpochMs)
    }
}

@Composable
private fun CentreButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color.White)
        Spacer(Modifier.size(8.dp))
        Text("Centre on My Location", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ScaleBarPlaceholder() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .width(160.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("0", color = Color.White, fontSize = 10.sp)
        Text("500 m", color = Color.White, fontSize = 10.sp)
        Text("1 km", color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun TelemetryPanel(speedKmH: Double?, elevationM: Double?, lastUpdateEpochMs: Long?) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC000000))
            .padding(10.dp)
            .width(210.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        row("Speed",      speedKmH?.let { "%.1f km/h".format(it) } ?: "0.0 km/h")
        row("Elevation",  elevationM?.let { "%.0f m".format(it) } ?: "—")
        row("Satellites", "—")     // not exposed by FusedLocationProvider
        row("Last Update", lastUpdateEpochMs?.let {
            SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(it))
        } ?: "—")
    }
}

@Composable
private fun row(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp,
             fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
