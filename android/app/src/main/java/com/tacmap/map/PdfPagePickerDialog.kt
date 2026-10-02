package com.tacmap.map

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.calibration.ImportLimits
import com.tacmap.calibration.PageGeorefState
import com.tacmap.calibration.PdfPageRenderer
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.ui.AlertDialog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// one pdfium renderer at a time: thumbnails render one after another, never in parallel
private const val THUMB_MAX_ASPECT = 1.3

private val thumbnailDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-thumbnails").apply { isDaemon = true } }
    .asCoroutineDispatcher()

/**
 * s9.6 page choice (D5-13): a multi-page PDF with no usable georef. Pages that
 * declared one we couldn't read carry the badge. Cancel drops the copy.
 */
@Composable
internal fun PdfPagePickerDialog(
    prepared: PreparedPdfImport,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val thumbPx = (ImportLimits.THUMBNAIL_WIDTH_DP * density).toInt().coerceIn(64, 480)
    // longest side, so a portrait page still comes out 120 dp wide (height capped at 1.3x)
    val thumbMaxPx = (thumbPx * THUMB_MAX_ASPECT).toInt()
    // ~0.4 MB each in 565 at 3x, 24 of them ~10 MB
    val cache = remember(prepared.file) { LruCache<Int, Bitmap>(ImportLimits.THUMBNAIL_CACHE_ENTRIES) }
    val uri = remember(prepared.file) { Uri.fromFile(prepared.file) }
    val pages = prepared.inspection.pages
    val pageCount = prepared.inspection.pageCount.toString()
    AlertDialog(
        onDismissRequest = onCancel,
        // E11: a stray tap outside would throw the copy away, only Cancel (or Back) does that
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(Messages.mapImportChoosePageTitle()) },
        text = {
            Column {
                // E14: from Layers the pages may well be georeferenced, so not the import wording
                Text(
                    if (prepared.existingEntryId != null) Messages.mapChoosePageMessage(pageCount)
                    else Messages.mapImportChoosePageMessage(pageCount),
                    fontSize = 13.sp,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(pages, key = { it.index }) { page ->
                        var thumb by remember(page.index) { mutableStateOf(cache.get(page.index)) }
                        LaunchedEffect(page.index) {
                            if (thumb == null) {
                                thumb = runCatching {
                                    withContext(thumbnailDispatcher) {
                                        PdfPageRenderer.renderPage(context, uri, page.index, maxDimension = thumbMaxPx, config = Bitmap.Config.RGB_565).bitmap
                                    }
                                }.getOrNull()?.also { cache.put(page.index, it) }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clickable { onPick(page.index) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier
                                    .width(ImportLimits.THUMBNAIL_WIDTH_DP.dp)
                                    .heightIn(min = 48.dp, max = (ImportLimits.THUMBNAIL_WIDTH_DP * THUMB_MAX_ASPECT).dp)
                                    .background(Color(0x22FFFFFF)),
                            ) {
                                thumb?.let {
                                    Image(
                                        it.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(Messages.mapImportPageLabel((page.index + 1).toString()), fontWeight = FontWeight.SemiBold)
                                if (page.state is PageGeorefState.Rejected) {
                                    Text(Messages.mapImportPageBadgeRejected(), fontSize = 11.sp, color = Color(0xFFFF9800))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(L10n.text("Cancel")) } },
    )
}
