import SwiftUI

extension View {
    /// iPad presents .sheet as a small fixed-size form sheet which clips
    /// our longer lists/forms (Symbology, Layers, APP-6C builder...).
    /// On iPadOS 18+ this sizes the sheet as a "page" so more content
    /// shows without scrolling. No-op on iPhone (sheets already
    /// full-height) and on iOS < 18.
    @ViewBuilder
    func padSheetSizing() -> some View {
        if #available(iOS 18.0, *) {
            presentationSizing(.page)
        } else {
            self
        }
    }
}
