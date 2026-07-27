package com.tacmap.map

internal const val PRIVACY_OPSEC_LABEL = "Settings, Privacy & OPSEC"

/** Production menu transition kept explicit and host-testable: selecting the
 * OPSEC row closes the popup before presenting its settings dialog. */
internal fun openPrivacyAndOpsecFromMenu(
    setMenuOpen: (Boolean) -> Unit,
    setDialogVisible: (Boolean) -> Unit,
) {
    setMenuOpen(false)
    setDialogVisible(true)
}
