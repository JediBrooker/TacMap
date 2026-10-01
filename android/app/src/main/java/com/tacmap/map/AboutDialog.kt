package com.tacmap.map

import com.tacmap.localization.L10n

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Modifier
import com.tacmap.localization.Messages
import com.tacmap.ui.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.BuildConfig
import com.tacmap.app.CrashReporter

@Composable
fun AboutDialog(onDismiss: () -> Unit, onReplayTour: (() -> Unit)? = null) {
    val context = LocalContext.current
    var crashReport by remember { mutableStateOf(CrashReporter.lastReport(context)) }
    var showNotices by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TacMap") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    L10n.text("Version %1\$s (%2\$s)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                onReplayTour?.let { replay ->
                    OutlinedButton(onClick = replay) {
                        Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(Messages.tourReplay(), modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(Messages.tourReplayHelp(), fontSize = 12.sp)
                }
                CreditSection(Messages.aboutMapData(), mapDataCredits())
                CreditSection(Messages.aboutOpenSource(), libraryCredits())
                TextButton(onClick = { showNotices = true }) { Text(Messages.aboutNoticesButton(), fontSize = 12.sp) }
                CreditSection(Messages.aboutStandards(), standardsCredits())
                Text(Messages.aboutPrivacyNote(), fontSize = 11.sp, color = Color.Gray)
                Text(L10n.text("PDF maps and overlays stay on this device unless exported."), fontSize = 11.sp, color = Color.Gray)

                crashReport?.let { report ->
                    Text(
                        L10n.text("A crash was recorded last run — nothing is sent anywhere."),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, L10n.text("TacMap crash log"))
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(intent, L10n.text("Export crash log")))
                    }) { Text(L10n.text("Export crash log"), fontSize = 12.sp) }
                    TextButton(onClick = {
                        CrashReporter.clear(context)
                        crashReport = null
                    }) { Text(L10n.text("Clear crash log"), fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(L10n.text("Close"))
            }
        }
    )
    if (showNotices) {
        ThirdPartyNoticesDialog(onDismiss = { showNotices = false })
    }
}

/** A credited source or library; tapping it opens its licence or project page. */
private class Credit(val title: String, val detail: String, val url: String)

private fun mapDataCredits() = listOf(
    Credit(
        Messages.aboutEsriImageryTitle(), Messages.aboutEsriImageryDetail(),
        "https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9",
    ),
    Credit(
        Messages.aboutEsriStylesTitle(), Messages.aboutEsriStylesDetail(),
        "https://developers.arcgis.com/documentation/mapping-and-location-services/mapping/basemap-styles-service/",
    ),
    Credit(Messages.aboutOpentopomapTitle(), Messages.aboutOpentopomapDetail(), "https://opentopomap.org/about"),
    Credit("OpenStreetMap", Messages.aboutOsmDetail(), "https://www.openstreetmap.org/copyright"),
    Credit(Messages.aboutOpenMeteoTitle(), Messages.aboutOpenMeteoDetail(), "https://open-meteo.com/en/license"),
)

private fun libraryCredits() = listOf(
    Credit("NGA mgrs-java (MIT)", Messages.aboutLibMgrsDetail(), "https://github.com/ngageoint/mgrs-java"),
    Credit("NGA grid-java (MIT)", Messages.aboutLibGridDetail(), "https://github.com/ngageoint/grid-java"),
    Credit("milsymbol (MIT)", Messages.aboutLibMilsymbolDetail(), "https://github.com/spatialillusions/milsymbol"),
    Credit("AndroidSVG (Apache 2.0)", Messages.aboutLibAndroidsvgDetail(), "https://github.com/BigBadaboom/androidsvg"),
    Credit("PdfBox-Android (Apache 2.0)", Messages.aboutLibPdfboxDetail(), "https://github.com/TomRoush/PdfBox-Android"),
    Credit("OkHttp (Apache 2.0)", Messages.aboutLibOkhttpDetail(), "https://square.github.io/okhttp/"),
    Credit("Java-WebSocket (MIT)", Messages.aboutLibWebsocketDetail(), "https://github.com/TooTallNate/Java-WebSocket"),
    Credit("Bouncy Castle (MIT)", Messages.aboutLibBouncycastleDetail(), "https://www.bouncycastle.org/about/license/"),
    Credit(
        "kotlinx.serialization (Apache 2.0)", Messages.aboutLibSerializationDetail(),
        "https://github.com/Kotlin/kotlinx.serialization",
    ),
    Credit("AndroidX (Apache 2.0)", Messages.aboutLibAndroidxDetail(), "https://developer.android.com/jetpack/androidx"),
    Credit(
        "Google Play Billing Library", Messages.aboutLibBillingDetail(),
        "https://developer.android.com/google/play/billing",
    ),
)

private fun standardsCredits() = listOf(
    Credit("OGC GeoPDF Encoding Best Practice", Messages.aboutGeopdfDetail(), "https://www.ogc.org/standards/geopdf"),
    Credit("GeoJSON (RFC 7946)", Messages.aboutGeojsonDetail(), "https://datatracker.ietf.org/doc/html/rfc7946"),
    Credit("Mapbox simplestyle-spec", Messages.aboutSimplestyleDetail(), "https://github.com/mapbox/simplestyle-spec"),
    Credit("Mapbox Maki Icon Set", Messages.aboutMakiDetail(), "https://github.com/mapbox/maki"),
)

@Composable
private fun CreditSection(heading: String, credits: List<Credit>) {
    val uriHandler = LocalUriHandler.current
    Text(heading, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    credits.forEach { credit ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    // No browser on the device: the credit text is still shown.
                    runCatching { uriHandler.openUri(credit.url) }
                }
                .padding(vertical = 2.dp),
        ) {
            Text(credit.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Text(credit.detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

/** The bundled licence texts (assets/THIRD_PARTY_NOTICES.txt). */
@Composable
private fun ThirdPartyNoticesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember {
        runCatching {
            context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Messages.aboutNoticesTitle()) },
        text = {
            Text(
                notices,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L10n.text("Close")) } },
    )
}
