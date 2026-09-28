package com.tacmap.ui

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import com.tacmap.settings.OpsecSettings

/** Red night mode as seen by every window of the app. */
@Immutable
data class NightModeState(
    val enabled: Boolean = false,
    val brightness: Float = OpsecSettings.DEFAULT_NIGHT_MODE_BRIGHTNESS,
)

/** Provided at the activity root; dialogs and popups inherit it. */
val LocalNightMode = compositionLocalOf { NightModeState() }

/**
 * Red night mode for preserving night vision. A hardware layer with a colour
 * matrix turns each pixel into (brightness × luminance, 0, 0). Dialogs,
 * bottom sheets and menus draw in their own windows, so each applies the same
 * layer through [NightWindowFilter]; the wrappers below do that for every
 * dialog type the app uses, and `NightModeSourceTest` keeps the app on them.
 * iOS mirrors this with blend-mode overlays.
 */
object NightModeFilter {
    /** Rec. 601 luma weights into the red channel, scaled by [brightness]. */
    fun matrix(brightness: Float): FloatArray {
        val k = brightness.coerceIn(0f, 1f)
        return floatArrayOf(
            0.299f * k, 0.587f * k, 0.114f * k, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun apply(view: View, state: NightModeState) {
        if (state.enabled) {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix(state.brightness)) }
            view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }
}

/** Applies night mode to the window this composable is in. */
@Composable
fun NightWindowFilter() {
    val state = LocalNightMode.current
    val root = LocalView.current.rootView
    DisposableEffect(root, state) {
        NightModeFilter.apply(root, state)
        onDispose { }
    }
}

// Night-mode-aware stand-ins for the Material and Compose window composables.
// Same names and parameters as the originals the app uses.

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            NightWindowFilter()
            confirmButton()
        },
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        properties = properties,
    )
}

@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        NightWindowFilter()
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        NightWindowFilter()
        content()
    }
}

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        NightWindowFilter()
        content()
    }
}

/** Hides the system bars in night mode: their icons are drawn by the system,
 * outside the app's windows, and would stay white. Swipe reveals them. */
@Composable
fun NightSystemBars(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
            if (enabled) {
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(bars)
            } else {
                controller.show(bars)
            }
        }
        onDispose { }
    }
}
