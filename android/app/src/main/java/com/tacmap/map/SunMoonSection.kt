package com.tacmap.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.Messages
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt

/** Offline sun and moon times for the map centre. Shown in the Weather dialog
 * whether or not online lookups are enabled: nothing leaves the device. */
@Composable
fun SunMoonSection(lat: Double, lng: Double) {
    var dayOffset by remember { mutableIntStateOf(0) }
    val (start, end) = SunMoonCalculator.localDay(System.currentTimeMillis(), dayOffset)
    val day = SunMoonCalculator.day(lat, lng, start, end)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Messages.sunMoonTitle(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(0 to Messages.sunMoonToday(), 1 to Messages.sunMoonTomorrow()).forEach { (offset, label) ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = dayOffset == offset,
                            onClick = { dayOffset = offset },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = dayOffset == offset, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        EventRow(Messages.sunMoonBmnt(), day.bmnt)
        EventRow(Messages.sunMoonBmct(), day.bmct)
        EventRow(Messages.sunMoonSunrise(), day.sunrise)
        EventRow(Messages.sunMoonSunset(), day.sunset)
        EventRow(Messages.sunMoonEect(), day.eect)
        EventRow(Messages.sunMoonEent(), day.eent)
        EventRow(Messages.sunMoonMoonrise(), day.moonrise)
        EventRow(Messages.sunMoonMoonset(), day.moonset)
        val percent = DisplayFormat.percent((day.moonIllumination * 100).roundToInt())
        ValueRow(
            Messages.sunMoonIllumination(),
            if (day.moonWaxing) Messages.sunMoonWaxing(percent) else Messages.sunMoonWaning(percent),
        )
        Text(
            Messages.sunMoonHelp(TimeZone.getDefault().id),
            fontSize = 11.sp,
            color = Color.Gray,
        )
    }
}

@Composable
private fun EventRow(name: String, millis: Long?) {
    val time = millis?.let { DisplayFormat.time(Date(it)) }
    ValueRow(name, time ?: "—", time ?: Messages.sunMoonNoEvent())
}

@Composable
private fun ValueRow(name: String, value: String, spokenValue: String = value) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = 8.dp)
                .semantics { contentDescription = spokenValue },
        )
    }
}
