package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MeasureToolbar(
    session: MeasureSession,
    modifier: Modifier = Modifier
) {
    if (!session.isActive) return
    val distance = MeasureFormat.distance(session.totalDistanceMeters)
    val mils = session.lastBearingMils?.let { "%04d mils".format(it) }
    val area = session.enclosedAreaSquareMeters?.let(MeasureFormat::area)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Tool indicator
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Color(0xFFFFA500), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Straighten, contentDescription = null, tint = Color.Black,
                 modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                distance,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (mils != null || area != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mils != null) Text(mils, color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (area != null) Text("· $area", color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { session.undo() },
            enabled = session.points.isNotEmpty(),
            modifier = Modifier
                .size(30.dp)
                .background(Color.White.copy(alpha = 0.10f), CircleShape)
        ) {
            Icon(Icons.Default.Undo, contentDescription = "Undo last point", tint = Color.White,
                 modifier = Modifier.size(16.dp))
        }
        Button(
            onClick = { session.cancel() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFA500),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(30.dp)
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
