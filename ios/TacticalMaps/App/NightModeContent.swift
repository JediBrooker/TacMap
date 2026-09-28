import SwiftUI

/// The luminance half of night mode for SwiftUI content. iOS offers no public
/// colour-matrix filter for a whole window, so SwiftUI's grayscale turns each
/// root and every sheet into brightness first; the window overlay in
/// `NightMode.swift` then keeps only the red channel. Content that bypasses
/// this (system alerts, share sheets) is still red, just without the
/// luminance mapping. The effect stays attached with amount 0 when night mode
/// is off so toggling never replaces the view tree.
struct NightModeContent: ViewModifier {
    @ObservedObject private var opsec = OpsecSettings.shared

    func body(content: Content) -> some View {
        content.grayscale(opsec.nightMode ? 1 : 0)
    }
}

extension View {
    func nightModeContent() -> some View {
        modifier(NightModeContent())
    }

    /// Use instead of `.sheet`: sheets are separate hosting controllers, so
    /// each needs its own night-mode conversion. `NightModeSourceTests` keeps
    /// the app on these.
    func nightSheet<Content: View>(
        isPresented: Binding<Bool>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            content().nightModeContent()
        }
    }

    func nightSheet<Item: Identifiable, Content: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        sheet(item: item, onDismiss: onDismiss) { value in
            content(value).nightModeContent()
        }
    }
}
