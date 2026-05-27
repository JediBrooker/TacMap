package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Position-agnostic MGRS readout card. Caller decides where in the
 * layout it sits and applies any safe-area / status-bar padding.
 */
@Composable
fun MgrsHeader(
    mgrs: String,
    wgs84: String,
    isBrowsing: Boolean,
    accuracy: Double?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isBrowsing) "MGRS (Map Centre)" else "MGRS (Your Location)",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp
        )
        Text(
            text = mgrs,
            color = Color(0xFF8CF28C),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("WGS84", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(wgs84, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationSearching,
                contentDescription = null,
                tint = Color(0xFFF2A24A),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                if (isBrowsing) "Map Centre" else "Live Location",
                color = Color(0xFFF2A24A),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                accuracy?.let { "Accuracy ±%.0fm".format(it) } ?: "Accuracy N/A",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            )
        }
    }
}
