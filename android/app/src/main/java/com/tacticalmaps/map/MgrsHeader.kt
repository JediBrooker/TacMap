package com.tacticalmaps.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Position-agnostic MGRS readout card. Caller decides where in the
 * layout it sits and applies any safe-area / status-bar padding.
 *
 * Tap → copy the MGRS string to clipboard.
 * Long-press → invokes [onDropPin] with the displayed coordinate so the
 * caller can drop a waypoint at that exact spot.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MgrsHeader(
    mgrs: String,
    wgs84: String,
    isBrowsing: Boolean,
    accuracy: Double?,
    modifier: Modifier = Modifier,
    onDropPin: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC000000))
            .combinedClickable(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("MGRS", mgrs))
                    Toast.makeText(context, "MGRS copied", Toast.LENGTH_SHORT).show()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onLongClick = onDropPin?.let { drop ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        drop()
                    }
                }
            )
            // Tight padding so the header doesn't dominate the map —
            // matches the iOS card's vertical 5pt / horizontal 14pt.
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = if (isBrowsing) "MGRS (Map Centre)" else "MGRS (Your Location)",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            lineHeight = 12.sp
        )
        Text(
            text = mgrs,
            color = Color(0xFF8CF28C),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 26.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("WGS84", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp,
                 fontWeight = FontWeight.Bold, lineHeight = 12.sp)
            Text(wgs84, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                 fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationSearching,
                contentDescription = null,
                tint = Color(0xFFF2A24A),
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.size(5.dp))
            Text(
                if (isBrowsing) "Map Centre" else "Live Location",
                color = Color(0xFFF2A24A),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 13.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                accuracy?.let { "Accuracy ±%.0fm".format(it) } ?: "Accuracy N/A",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp,
                textAlign = TextAlign.End
            )
        }
    }
}
