package com.tacmap.map

import com.tacmap.localization.L10n

internal val PRIVACY_OPSEC_LABEL: String get() = L10n.text("Settings, Privacy & OPSEC")

/** Production menu transition kept explicit and host-testable: selecting the
 * OPSEC row closes the popup before presenting its settings dialog. */
internal fun openPrivacyAndOpsecFromMenu(
    setMenuOpen: (Boolean) -> Unit,
    setDialogVisible: (Boolean) -> Unit,
) {
    setMenuOpen(false)
    setDialogVisible(true)
}
