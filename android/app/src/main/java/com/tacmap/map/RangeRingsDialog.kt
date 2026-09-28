package com.tacmap.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.RangeRings
import com.tacmap.localization.DecimalInput
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.waypoints.Waypoint

/** Unit for the ring spacing field. [persisted] values are stored in preferences. */
enum class RangeRingUnit(val persisted: String, val metresPerUnit: Double) {
    METRES("metres", 1.0),
    KILOMETRES("kilometres", 1000.0);

    val label: String
        get() = when (this) {
            METRES -> Messages.ringsUnitMetres()
            KILOMETRES -> Messages.ringsUnitKilometres()
        }

    companion object {
        fun fromPersisted(value: String?): RangeRingUnit = entries.firstOrNull { it.persisted == value } ?: METRES
    }
}

/**
 * Adds range rings around the selected symbol as ordinary line drawings on its
 * layer. [onCreate] commits them as one undo step and returns whether they
 * were saved; failures keep this dialog open so the caller's retry UI applies.
 */
@Composable
fun RangeRingsDialog(
    waypoint: Waypoint,
    layerColor: Int?,
    onCreate: (List<DrawingFeature>) -> Boolean,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current.density
    var storedCount by rememberPersistedString("range_rings_count", "3")
    var spacingText by rememberPersistedString("range_rings_spacing", "500")
    var storedUnit by rememberPersistedString("range_rings_unit", RangeRingUnit.METRES.persisted)
    val count = storedCount.toIntOrNull()?.coerceIn(1, RangeRings.MAX_RINGS) ?: 3
    val unit = RangeRingUnit.fromPersisted(storedUnit)
    val radii = DecimalInput.parse(spacingText)?.let { RangeRings.radii(it * unit.metresPerUnit, count) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Messages.ringsTitle()) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Messages.ringsCountValue(DisplayFormat.number(count.toDouble(), 0)),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { storedCount = (count - 1).coerceAtLeast(1).toString() },
                        enabled = count > 1,
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = Messages.ringsFewer())
                    }
                    IconButton(
                        onClick = { storedCount = (count + 1).coerceAtMost(RangeRings.MAX_RINGS).toString() },
                        enabled = count < RangeRings.MAX_RINGS,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = Messages.ringsMore())
                    }
                }
                OutlinedTextField(
                    value = spacingText,
                    onValueChange = { spacingText = it },
                    label = { Text(Messages.ringsSpacing()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RangeRingUnit.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .selectable(
                                    selected = unit == option,
                                    onClick = { storedUnit = option.persisted },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = unit == option, onClick = null)
                            Text(option.label, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
                Text(Messages.decimalInputHint(), fontSize = 11.sp, color = Color.Gray)
                Text(Messages.ringsPreview(), fontWeight = FontWeight.SemiBold)
                if (radii != null) {
                    Text(radii.joinToString(", ") { DisplayFormat.distance(it) })
                } else {
                    Text(Messages.ringsInvalid(), color = Color(0xFFB00020))
                }
                Text(Messages.ringsHelp(), fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(
                enabled = radii != null,
                onClick = {
                    val selected = radii ?: return@TextButton
                    val rings = RangeRings.features(waypoint, selected, layerColor, density)
                    if (onCreate(rings)) onDismiss()
                },
            ) {
                Text(Messages.ringsCreate())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(L10n.text("Cancel")) }
        },
    )
}
