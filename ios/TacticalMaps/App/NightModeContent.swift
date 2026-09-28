import SwiftUI

/// Red night mode for SwiftUI content: grayscale turns every pixel into its
/// luminance, then a red colour multiply keeps only red at the chosen
/// brightness, so each pixel becomes (brightness × luminance, 0, 0) and the
/// map, symbols and text stay readable. Both are standard SwiftUI effects, so
/// they render the same on devices and in the Simulator (an earlier window
/// overlay relied on a Core Animation blend mode that devices ignore, which
/// painted the whole screen solid red). Applied at the app root and to every
/// sheet through `nightSheet`. The effects stay attached at identity when
/// night mode is off so toggling never replaces the view tree.
struct NightModeContent: ViewModifier {
    @ObservedObject private var opsec = OpsecSettings.shared

    func body(content: Content) -> some View {
        let on = opsec.nightMode
        content
            .grayscale(on ? 1 : 0)
            .colorMultiply(on ? Color(red: opsec.nightModeBrightness, green: 0, blue: 0) : .white)
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
