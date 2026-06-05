import SwiftUI

/// Undo / redo button pair. Appears below the compass chip whenever there
/// is history to navigate. Matches the compass chip's dark-circle aesthetic.
struct UndoRedoButtons: View {
    let canUndo: Bool
    let canRedo: Bool
    let onUndo: () -> Void
    let onRedo: () -> Void

    private let size: CGFloat = 40

    var body: some View {
        VStack(spacing: 6) {
            chip(
                symbol: "arrow.uturn.backward",
                enabled: canUndo,
                accessibilityLabel: "Undo",
                action: onUndo
            )
            chip(
                symbol: "arrow.uturn.forward",
                enabled: canRedo,
                accessibilityLabel: "Redo",
                action: onRedo
            )
        }
    }

    private func chip(symbol: String,
                      enabled: Bool,
                      accessibilityLabel: String,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(.black.opacity(0.82))
                    .frame(width: size, height: size)
                Circle()
                    .stroke(.white.opacity(0.14), lineWidth: 1)
                    .frame(width: size, height: size)
                Image(systemName: symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(enabled ? .white : .white.opacity(0.3))
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(accessibilityLabel)
    }
}
