package com.tacmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.calibration.fiducial.CalibrationPanelStatus
import com.tacmap.calibration.fiducial.CalibrationPointKind
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.Finishability
import com.tacmap.calibration.fiducial.FitGrade
import com.tacmap.calibration.fiducial.FitIssue
import com.tacmap.calibration.fiducial.RowStatus
import com.tacmap.calibration.fiducial.StatusMessage
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.mgrs.ParseOutcome
import com.tacmap.ui.AlertDialog
import com.tacmap.ui.ModalBottomSheet

private val CalOrange = Color(0xFFFFA000)
private val CalAmber = Color(0xFFFFB300)
private val CalGood = Color(0xFF74E38A)
private val CalBad = Color(0xFFFF6B5A)
private val PanelBg = Color(0xE6000000)
private val Target = 56.dp

private enum class Glyph(val icon: ImageVector, val tint: Color) {
    OK(Icons.Default.CheckCircle, CalGood),
    WARN(Icons.Default.Warning, CalAmber),
    ERROR(Icons.Default.Error, CalBad),
    INFO(Icons.Default.Info, Color.White),
}

/** state as glyph + word, never colour alone (night mode maps colour to luminance) */
private fun glyphFor(ui: CalibrationUiState, offSheet: Boolean): Glyph = when {
    offSheet -> Glyph.WARN
    ui.report.n == 0 -> Glyph.INFO
    ui.report.blocked != null -> Glyph.ERROR
    ui.report.finish is Finishability.Blocked -> Glyph.INFO
    ui.report.issue is FitIssue.Outlier || ui.report.issue is FitIssue.Ambiguous -> Glyph.ERROR
    ui.report.grade == FitGrade.POOR -> Glyph.ERROR
    ui.report.issue != null || ui.report.grade == FitGrade.FAIR || ui.report.exact -> Glyph.WARN
    else -> Glyph.OK
}

@Composable
private fun PanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: ImageVector? = null,
    /** line 2 chips: smaller text + padding so four of them fit a 360 dp phone in German */
    compact: Boolean = false,
    /** icon only (Undo on line 2, like iOS), the text goes to TalkBack instead */
    iconOnly: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = Target)
            .then(if (iconOnly) Modifier.width(Target).semantics { contentDescription = text } else Modifier),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = if (iconOnly) 0.dp else if (compact) 6.dp else 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) CalOrange else Color.White.copy(alpha = 0.14f),
            contentColor = if (primary) Color.Black else Color.White,
            disabledContainerColor = Color(0xFF3A3A3A),
            disabledContentColor = Color.White.copy(alpha = 0.4f),
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (iconOnly) 24.dp else 18.dp))
            if (!iconOnly) Spacer(Modifier.width(4.dp))
        }
        if (!iconOnly) {
            Text(
                text,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 12.sp else 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Bottom calibration panel (contract s10), inline so the map stays live. Every
 * target is 56 dp; Finish sits bottom right, well away from the ✕ top left.
 */
@Composable
internal fun CalibrationPanel(
    ui: CalibrationUiState,
    capture: CalibrationCapture?,
    cameraKnown: Boolean,
    onUndo: () -> Unit,
    onDatum: () -> Unit,
    onGrid: () -> Unit,
    onPoints: () -> Unit,
    onAdd: () -> Unit,
    onFinish: () -> Unit,
    onMove: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeselect: () -> Unit,
    onCancelMove: () -> Unit,
    onSetHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // s10 priority + the secondary line, pure and pinned by the fit report fixture
    val status = CalibrationPanelStatus.of(ui.report, capture, cameraKnown)
    val offSheet = status.offSheet
    val moving = ui.phase as? CalibrationPhase.Moving
    val primary: String = when {
        moving != null -> Messages.calibrationMoving((ui.state.point(moving.id)?.number ?: 0).toString())
        else -> CalibrationText.text(status.primary)
    }
    val secondary: String? = status.secondary?.let(CalibrationText::text)
    val glyph = if (moving != null) Glyph.INFO else glyphFor(ui, offSheet)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(PanelBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.draftUnsaved) {
            Text(
                Messages.calibrationDraftUnsaved(),
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CalAmber)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(glyph.icon, contentDescription = null, tint = glyph.tint, modifier = Modifier.size(22.dp).padding(top = 1.dp))
            Column(Modifier.weight(1f)) {
                Text(primary, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                secondary?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 2) }
            }
        }
        if (moving != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(L10n.text("Cancel"), onCancelMove, Modifier.weight(1f))
                PanelButton(Messages.calibrationSetHere(), onSetHere, Modifier.weight(1.4f), enabled = !offSheet, primary = true)
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // OD-F3: the datum chip used to get whatever was left after three full width
            // buttons, in German that was nothing. Undo goes icon only (iOS does the same) and
            // the chip + Points share the rest, so the chip never drops under the 56 dp target
            PanelButton(L10n.text("Undo"), onUndo, enabled = ui.canUndo, icon = Icons.AutoMirrored.Filled.Undo, iconOnly = true)
            PanelButton(
                CalibrationText.datumName(ui.state.datumId),
                onDatum,
                Modifier.weight(1f).widthIn(min = Target).semantics { contentDescription = Messages.calibrationDatumTitle() },
                compact = true,
            )
            PanelButton(
                Messages.calibrationGridToggle(),
                onGrid,
                enabled = !ui.display.provisional,
                primary = ui.gridOn,
                icon = Icons.Default.GridOn,
                compact = true,
            )
            PanelButton("${Messages.calibrationPointsButton()} (${ui.state.points.size})", onPoints, Modifier.weight(1f), compact = true)
        }
        val selected = ui.selected
        // the entry card owns the point while it's open: a second Add / Edit here would
        // throw away what's typed, so the action row waits for Save or Cancel
        val idle = ui.phase !is CalibrationPhase.Entering
        if (selected != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelButton(Messages.calibrationActionMove(), { onMove(selected.id) }, Modifier.weight(1f), enabled = idle)
                PanelButton(Messages.calibrationActionEdit(), { onEdit(selected.id) }, Modifier.weight(1.3f), enabled = idle)
                PanelButton(L10n.text("Delete"), { onDelete(selected.id) }, Modifier.weight(1f), enabled = idle)
                PanelButton(Messages.calibrationActionDone(), onDeselect, Modifier.weight(1f), enabled = idle)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(
                    Messages.calibrationAddPoint(),
                    onAdd,
                    Modifier.weight(2f),
                    enabled = idle && !offSheet && capture != null &&
                        ui.state.points.size < com.tacmap.calibration.fiducial.CalibrationState.MAX_POINTS,
                    primary = true,
                    icon = Icons.Default.Add,
                )
                PanelButton(
                    L10n.text("Finish"),
                    onFinish,
                    Modifier.weight(1f),
                    enabled = idle && ui.report.finish !is Finishability.Blocked,
                )
            }
        }
    }
}

/** ✕ top left, as far from Finish as the screen allows (D2-08) */
@Composable
internal fun CalibrationLeaveButton(onLeave: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Target)
            .clip(CircleShape)
            .background(PanelBg)
            .clickable(onClick = onLeave)
            .semantics { contentDescription = Messages.calibrationClose() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** [+] [-] on the right edge, +/-1 zoom about the crosshair */
@Composable
internal fun CalibrationZoomButtons(onZoom: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(1, Icons.Default.Add, Messages.calibrationZoomIn()),
            Triple(-1, Icons.Default.Remove, Messages.calibrationZoomOut()),
        ).forEach { (delta, icon, label) ->
            Box(
                modifier = Modifier
                    .size(Target)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelBg)
                    .clickable { onZoom(delta) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/**
 * The entry card, docked at the TOP so the crosshair stays visible between it and
 * the keyboard on small phones. Map stays pannable underneath.
 */
@Composable
internal fun CalibrationEntryCard(
    ui: CalibrationUiState,
    pointNumber: Int,
    gpsAccuracyM: Double?,
    gpsAllowed: Boolean,
    moveToCrosshairVisible: Boolean,
    onText: (String) -> Unit,
    onKind: (CalibrationPointKind) -> Unit,
    onLabel: (String) -> Unit,
    onUseGps: () -> Unit,
    onMoveToCrosshair: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = ui.entry ?: return
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    // straight into typing when the card opens (gloves: one less tap)
    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
        keyboard?.show()
    }
    var labelOpen by remember { mutableStateOf(entry.label.isNotEmpty()) }
    val parsed = entry.parsed
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF0101010))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(Messages.calibrationEntryTitle(pointNumber.toString()), color = CalOrange, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        OutlinedTextField(
            value = entry.text,
            onValueChange = { if (it.length <= 96) onText(it) },
            singleLine = true,
            placeholder = { Text(Messages.calibrationEntryPlaceholder(), fontFamily = FontFamily.Monospace) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = Color.White),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrect = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
        when (val p = entry.parse) {
            is ParseOutcome.Ok -> {
                val line = p.reference.messages.joinToString(" · ") { CalibrationText.text(it) } +
                    " · " + CalibrationText.datumName(ui.state.datumId)
                Text(line, color = CalGood, fontSize = 13.sp)
            }
            is ParseOutcome.Err -> CalibrationText.parseError(p)?.let { Text(it, color = CalBad, fontSize = 13.sp) }
            null -> Unit
        }
        if (parsed?.kindSegment == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    CalibrationPointKind.INTERSECTION to Messages.calibrationKindIntersection(),
                    CalibrationPointKind.FEATURE to Messages.calibrationKindFeature(),
                ).forEach { (k, label) ->
                    val on = entry.kind == k
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) CalOrange else Color.White.copy(alpha = 0.12f))
                            .clickable { onKind(k) }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
        entry.check?.message?.let { m ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = CalAmber, modifier = Modifier.size(18.dp))
                Text(CalibrationText.text(m), color = CalAmber, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onUseGps,
                enabled = gpsAllowed,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    gpsAccuracyM?.let { Messages.calibrationUseGps(DisplayFormat.distance(it)) } ?: Messages.calibrationUseCurrentLocationNoFix(),
                    fontSize = 12.sp, maxLines = 2,
                )
            }
            if (moveToCrosshairVisible) {
                OutlinedButton(onClick = onMoveToCrosshair, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(Messages.calibrationMoveToCrosshair(), fontSize = 12.sp, maxLines = 2)
                }
            }
        }
        if (gpsAccuracyM != null && !gpsAllowed && gpsAccuracyM > com.tacmap.calibration.ImportLimits.GPS_MAX_ACCURACY_M) {
            Text(Messages.calibrationGpsTooCoarse(DisplayFormat.distance(gpsAccuracyM)), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        if (labelOpen) {
            OutlinedTextField(
                value = entry.label,
                onValueChange = { if (it.length <= 80) onLabel(it) },
                singleLine = true,
                placeholder = { Text(Messages.calibrationLabelPlaceholder()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { keyboard?.hide() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.KeyboardHide, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(Messages.calibrationHideKeyboard(), fontSize = 12.sp)
            }
            if (!labelOpen) {
                TextButton(onClick = { labelOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(Messages.calibrationLabelPlaceholder(), fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(L10n.text("Cancel"), onCancel, Modifier.weight(1f))
            PanelButton(
                if (entry.check?.warn == true) Messages.calibrationSaveAnyway() else Messages.calibrationSave(),
                onSave,
                Modifier.weight(1.4f),
                enabled = entry.canSave,
                primary = true,
            )
        }
    }
}

private fun residualGlyph(status: RowStatus?): Glyph? = when (status) {
    RowStatus.OK -> Glyph.OK
    RowStatus.WARN -> Glyph.WARN
    RowStatus.ERROR -> Glyph.ERROR
    null -> null
}

/** half height list: fit summary then a row per point, tap = select + fly there */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalibrationPointsSheet(
    ui: CalibrationUiState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
            Text(Messages.calibrationPointsTitle(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(CalibrationText.text(ui.report.primaryStatus), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            ui.state.sortedPoints.forEach { p ->
                val residual = ui.report.residualsM[p.number]
                val status = ui.report.rowStatus?.get(p.number)
                    ?: if (p.number in ui.report.flagged) RowStatus.ERROR else null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onSelect(p.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("#${p.number}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.canonical ?: p.input, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val kindText = when {
                            p.reference is CalibrationReference.Geographic -> null
                            p.kind == CalibrationPointKind.FEATURE -> Messages.calibrationKindFeature()
                            else -> Messages.calibrationKindIntersection()
                        }
                        listOfNotNull(kindText, p.label).takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), fontSize = 11.sp, color = Color(0xFF8A938A))
                        }
                    }
                    Text(
                        if (residual == null || ui.report.n == 3) "—" else Messages.calibrationRowResidual(DisplayFormat.distance(residual)),
                        fontSize = 12.sp,
                    )
                    residualGlyph(status)?.let { g -> Icon(g.icon, contentDescription = null, tint = g.tint, modifier = Modifier.size(20.dp)) }
                }
                HorizontalDivider()
            }
        }
    }
}

/** contract s10 datum list, fixed order, "not sure" picks WGS84 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalibrationDatumSheet(
    current: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
            Text(Messages.calibrationDatumTitle(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(Messages.calibrationDatumHelp(), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            CalibrationText.DATUM_ORDER.forEach { id ->
                DatumRow(CalibrationText.datumName(id), id == current) { onChoose(id) }
            }
            DatumRow(Messages.calibrationDatumUnsure(), false) { onChoose(com.tacmap.calibration.GeoDatums.WGS84.id) }
            Text(
                Messages.calibrationDatumNationalGridNote(),
                fontSize = 11.sp,
                color = Color(0xFF8A938A),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun DatumRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CalOrange)
    }
}

/** leave / finish confirm / resume prompt / save failed, the four alerts of s2 */
@Composable
internal fun CalibrationDialogs(
    ui: CalibrationUiState,
    ageText: (Long) -> String,
    onLeaveKeep: () -> Unit,
    onLeaveDiscard: () -> Unit,
    onContinue: () -> Unit,
    onFinishAnyway: () -> Unit,
    onAddMore: () -> Unit,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onRetrySave: () -> Unit,
    onNotNow: () -> Unit,
) {
    when (ui.phase) {
        CalibrationPhase.LeaveDialog -> AlertDialog(
            onDismissRequest = onContinue,
            title = { Text(Messages.calibrationLeaveTitle()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(Messages.calibrationLeaveMessage())
                    TextButton(onClick = onLeaveKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(Messages.calibrationLeaveKeep(), fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = onLeaveDiscard,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = CalBad),
                    ) { Text(Messages.calibrationLeaveDiscard()) }
                }
            },
            confirmButton = { TextButton(onClick = onContinue) { Text(Messages.calibrationLeaveContinue()) } },
        )
        CalibrationPhase.FinishConfirm -> AlertDialog(
            onDismissRequest = onAddMore,
            title = { Text(Messages.calibrationFinishConfirmTitle()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.report.confirmMessages.forEach { Text("• " + CalibrationText.text(it)) }
                }
            },
            confirmButton = { TextButton(onClick = onFinishAnyway) { Text(Messages.calibrationFinishAnyway()) } },
            dismissButton = { TextButton(onClick = onAddMore) { Text(Messages.calibrationAddMore(), fontWeight = FontWeight.Bold) } },
        )
        CalibrationPhase.ResumePrompt -> ui.resumeDraft?.let { d ->
            AlertDialog(
                onDismissRequest = onResume,
                title = { Text(Messages.calibrationResumeTitle()) },
                text = { Text(Messages.calibrationResumeMessage(Messages.calibrationPointCount(d.points.size), ageText(d.updatedAtMs))) },
                confirmButton = { TextButton(onClick = onResume) { Text(Messages.calibrationResume(), fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = onStartOver) { Text(Messages.calibrationStartOver()) } },
            )
        }
        CalibrationPhase.SaveFailed -> AlertDialog(
            onDismissRequest = onNotNow,
            text = { Text(Messages.calibrationSaveFailed()) },
            confirmButton = { TextButton(onClick = onRetrySave) { Text(L10n.text("Retry")) } },
            dismissButton = { TextButton(onClick = onNotNow) { Text(L10n.text("Not now")) } },
        )
        else -> Unit
    }
}

/** draft age for the resume prompt: minutes / hours, older shows the date */
internal fun calibrationAgeText(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = ((nowMs - updatedAtMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 60 -> Messages.minuteAgoCount(minutes.toInt())
        minutes < 48 * 60 -> Messages.hourAgoCount((minutes / 60).toInt())
        else -> DisplayFormat.dateTime(java.util.Date(updatedAtMs))
    }
}

/** the amber band label + border bits the header uses while calibrating */
internal val CalibrationHeaderColor: Color = CalAmber
