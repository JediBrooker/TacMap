package com.tacmap.map

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.PdfBakeError
import com.tacmap.calibration.PdfBakeManager
import com.tacmap.calibration.PdfMapSource
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.map.render.pdf.PdfLaunchNotice
import com.tacmap.map.render.pdf.PdfBakeFormat
import com.tacmap.map.render.pdf.PdfRenderStatus
import com.tacmap.ui.AlertDialog

/** the bake's size text, e.g. "48.2 MB" (OD-F7, the shared rule in PdfBakeFormat) */
internal fun pdfBakeSize(bytes: Long): String = PdfBakeFormat.size(bytes, java.util.Locale.forLanguageTag(DisplayFormat.uiLanguage()))

/** a tile count, grouped the way the UI language does it, never the region (OD-F7, PAR-R2-1) */
internal fun pdfBakeTiles(count: Int): String = PdfBakeFormat.tiles(count.toLong(), java.util.Locale.forLanguageTag(DisplayFormat.uiLanguage()))

internal fun pdfBakeErrorMessage(context: android.content.Context, state: PdfBakeManager.State.Failed): String =
    when (state.error) {
        PdfBakeError.NOT_CALIBRATED -> Messages.pdfBakeDisabledCaption()
        PdfBakeError.TOO_LARGE -> Messages.pdfBakeErrorTooLarge()
        PdfBakeError.NO_SPACE -> Messages.pdfBakeErrorNoSpace(pdfBakeSize(state.neededBytes))
        PdfBakeError.WRITE_FAILED -> Messages.pdfBakeErrorWriteFailed()
        PdfBakeError.RENDER_FAILED -> Messages.pdfBakeErrorRenderFailed()
        PdfBakeError.SOURCE_CHANGED -> Messages.pdfBakeErrorSourceChanged()
        PdfBakeError.INTERRUPTED -> Messages.pdfBakeInterruptedMessage()
    }

/**
 * Every PDF render alert in one place (WP2 contract G, I, J, L): couldn't draw,
 * crash recovery, interrupted import/bake notices, and the bake's estimate, confirm
 * and error. The progress chip is [PdfBakeChip], it sits on the map.
 */
@Composable
internal fun PdfRenderDialogs(
    vm: MapViewModel,
    mapSource: MapSource,
    pdfRenderStatus: PdfRenderStatus,
    dismissedRenderFailure: String?,
    onDismissRenderFailure: (String) -> Unit,
    onRetry: () -> Unit,
    /** a held back PDF, or an MBTiles pack (s14.1) that reuses the same alert as is */
    pdfRecovery: CrashSuspect?,
    confirmDeleteSuspect: Boolean,
    onConfirmDeleteSuspect: (Boolean) -> Unit,
    pdfLaunchNotice: PdfLaunchNotice?,
    bakeState: PdfBakeManager.State,
) {
    val context = LocalContext.current
    val pdf = mapSource as? PdfMapSource

    // a running bake keeps the screen on while we're in front (contract E)
    val view = LocalView.current
    val baking = bakeState is PdfBakeManager.State.Running
    DisposableEffect(baking) {
        view.keepScreenOn = baking
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(vm) {
        vm.bakeManager.finished.collect {
            Toast.makeText(context, Messages.pdfBakeDone(), Toast.LENGTH_SHORT).show()
        }
    }

    // couldn't draw. sticky until Try Again, Not Now just stops the nagging for this failure
    val failed = pdfRenderStatus as? PdfRenderStatus.Failed
    val failureKey = if (pdf != null && failed != null) "${pdf.id}:${failed.reason.code}" else null
    if (pdf != null && failed != null && failureKey != dismissedRenderFailure && pdfRecovery == null) {
        AlertDialog(
            onDismissRequest = { onDismissRenderFailure(failureKey!!) },
            title = { Text(Messages.pdfRenderFailedTitle(pdf.displayName)) },
            text = { Text(pdfRenderFailureMessage(failed.reason)) },
            confirmButton = {
                TextButton(onClick = onRetry) { Text(Messages.pdfRenderTryAgain()) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDismissRenderFailure(failureKey!!)
                        vm.restoreOnlineBasemap()
                    }) { Text(Messages.pdfRenderUseOnlineMap()) }
                    TextButton(onClick = { onDismissRenderFailure(failureKey!!) }) { Text(L10n.text("Not Now")) }
                }
            },
        )
    }

    // the crash guard held this PDF back at launch
    if (pdfRecovery != null && !confirmDeleteSuspect) {
        AlertDialog(
            onDismissRequest = { vm.dismissPdfRecovery() },
            title = { Text(Messages.pdfGuardCrashTitle(pdfRecovery.displayName)) },
            text = { Text(Messages.pdfGuardCrashMessage()) },
            confirmButton = {
                TextButton(onClick = { vm.openSuspectPdfAnyway() }) { Text(Messages.pdfGuardOpenAnyway()) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { onConfirmDeleteSuspect(true) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5A5A)),
                    ) { Text(Messages.pdfGuardDeleteMap()) }
                    TextButton(onClick = { vm.dismissPdfRecovery() }) { Text(L10n.text("Not Now")) }
                }
            },
        )
    }
    if (pdfRecovery != null && confirmDeleteSuspect) {
        // same confirm as Layers > Imported maps > Delete (M10)
        AlertDialog(
            onDismissRequest = { onConfirmDeleteSuspect(false) },
            title = { Text(Messages.mapDeleteTitle(pdfRecovery.displayName)) },
            text = { Text(Messages.mapDeleteMessage()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmDeleteSuspect(false)
                        vm.deleteSuspectPdf()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5A5A)),
                ) { Text(L10n.text("Delete")) }
            },
            dismissButton = { TextButton(onClick = { onConfirmDeleteSuspect(false) }) { Text(L10n.text("Cancel")) } },
        )
    }

    // the crash recovery alert (suppress) goes first, the interrupted notices queue behind it
    when (if (pdfRecovery == null) pdfLaunchNotice else null) {
        is PdfLaunchNotice.ImportInterrupted -> AlertDialog(
            onDismissRequest = { vm.consumePdfLaunchNotice() },
            title = { Text(Messages.pdfGuardImportInterruptedTitle()) },
            text = { Text(Messages.pdfGuardImportInterruptedMessage()) },
            confirmButton = { TextButton(onClick = { vm.consumePdfLaunchNotice() }) { Text(Messages.acknowledge()) } },
        )
        PdfLaunchNotice.BakeInterrupted -> AlertDialog(
            onDismissRequest = { vm.consumePdfLaunchNotice() },
            title = { Text(Messages.pdfBakeInterruptedTitle()) },
            text = { Text(Messages.pdfBakeInterruptedMessage()) },
            confirmButton = { TextButton(onClick = { vm.consumePdfLaunchNotice() }) { Text(Messages.acknowledge()) } },
        )
        null -> Unit
    }

    when (bakeState) {
        PdfBakeManager.State.Estimating -> AlertDialog(
            onDismissRequest = {},
            title = { Text(Messages.pdfBakeConfirmTitle()) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(Messages.pdfBakeEstimating(), modifier = Modifier.padding(start = 12.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.bakeManager.dismiss() }) { Text(L10n.text("Cancel")) } },
        )
        is PdfBakeManager.State.Confirming -> BakeConfirmDialog(
            bakeState.proposal,
            vm.bakeManager,
            // OD-F9: a row-menu bake of another map says the screen won't change
            onScreen = pdf != null && pdf.render.renderGuardToken == bakeState.proposal.renderGuardToken,
        )
        is PdfBakeManager.State.Failed -> AlertDialog(
            onDismissRequest = { vm.bakeManager.dismiss() },
            title = { Text(Messages.pdfBakeErrorTitle()) },
            text = { Text(pdfBakeErrorMessage(context, bakeState)) },
            confirmButton = { TextButton(onClick = { vm.bakeManager.dismiss() }) { Text(Messages.acknowledge()) } },
        )
        else -> Unit
    }
}

@Composable
private fun BakeConfirmDialog(proposal: PdfBakeManager.Proposal, manager: PdfBakeManager, onScreen: Boolean) {
    val context = LocalContext.current
    var chosen by remember(proposal) { mutableStateOf(proposal.initial) }
    AlertDialog(
        onDismissRequest = { manager.dismiss() },
        title = { Text(Messages.pdfBakeConfirmTitle()) },
        text = {
            Column {
                val minutes = Messages.pdfBakeMinutes(DisplayFormat.number(chosen.minutes.toDouble(), 0))
                Text(
                    when (ImportUiRules.bakeConfirmKey(onScreen)) {
                        "pdf_bake_confirm_message" -> Messages.pdfBakeConfirmMessage(proposal.pdfName, minutes)
                        else -> Messages.pdfBakeConfirmMessageInactive(proposal.pdfName, minutes)
                    },
                    fontSize = 14.sp,
                )
                proposal.options.forEach { o ->
                    val row = Messages.pdfBakeOptionRow(
                        DisplayFormat.number(o.option.maxZoom.toDouble(), 0),
                        pdfBakeTiles(o.option.tiles),
                        pdfBakeSize(o.bytes),
                    ) + if (o.enoughSpace) "" else Messages.pdfBakeOptionNoSpace()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = o.enoughSpace) { chosen = o }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == o, onClick = { chosen = o }, enabled = o.enoughSpace)
                        Text(row, fontSize = 13.sp, color = if (o.enoughSpace) Color.Unspecified else Color(0xFF8A938A))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { manager.start(chosen) }, enabled = chosen.enoughSpace) { Text(Messages.pdfBakeGenerate()) }
        },
        dismissButton = { TextButton(onClick = { manager.dismiss() }) { Text(L10n.text("Cancel")) } },
    )
}

/** "Offline tiles 42%" + Cancel, on the map while a bake runs (survives the sheet closing) */
@Composable
internal fun PdfBakeChip(state: PdfBakeManager.State, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val running = state as? PdfBakeManager.State.Running ?: return
    val percent = if (running.total > 0) (running.done * 100L / running.total).toInt() else 0
    Row(
        modifier = modifier
            .background(Color(0xE6202020), RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                Messages.pdfBakeChip(DisplayFormat.number(percent.toDouble(), 0)),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.size(width = 110.dp, height = 3.dp),
            )
        }
        TextButton(onClick = onCancel) { Text(L10n.text("Cancel"), color = Color(0xFFFFC247)) }
    }
}
