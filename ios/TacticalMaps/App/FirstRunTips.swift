import SwiftUI

/// Four short tips shown once after the first unlock and reopenable from
/// Settings. Bump `currentVersion` when the tips change enough to show again.
enum FirstRunTips {
    static let currentVersion = 1
    static let defaultsKey = "onboarding.tipsSeenVersion"

    struct Tip: Identifiable {
        let id: Int
        let systemImage: String
        let title: String
        let body: String
    }

    static var tips: [Tip] {
        [
            Tip(id: 0, systemImage: "plus.circle.fill", title: Messages.tipsPlaceTitle(), body: Messages.tipsPlaceBody()),
            Tip(id: 1, systemImage: "hand.draw.fill", title: Messages.tipsEditTitle(), body: Messages.tipsEditBody()),
            Tip(id: 2, systemImage: "antenna.radiowaves.left.and.right", title: Messages.tipsShareTitle(), body: Messages.tipsShareBody()),
            Tip(id: 3, systemImage: "moon.fill", title: Messages.tipsNightTitle(), body: Messages.tipsNightBody()),
        ]
    }

    /// UI tests drive the map directly; the tips sheet would cover it.
    static func shouldShow(defaults: UserDefaults = .standard,
                           environment: [String: String] = ProcessInfo.processInfo.environment) -> Bool {
        if environment.keys.contains(where: { $0.hasPrefix("TACMAP_UITEST_") }) { return false }
        return defaults.integer(forKey: defaultsKey) < currentVersion
    }

    static func markSeen(defaults: UserDefaults = .standard) {
        defaults.set(currentVersion, forKey: defaultsKey)
    }

    static func reset(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: defaultsKey)
    }
}

struct FirstRunTipsView: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @Environment(\.dismiss) private var dismiss
    @State private var page = 0

    var body: some View {
        let tips = FirstRunTips.tips
        let isLast = page >= tips.count - 1
        NavigationStack {
            VStack(spacing: 16) {
                TabView(selection: $page) {
                    ForEach(tips) { tip in
                        ScrollView {
                            VStack(spacing: 16) {
                                Image(systemName: tip.systemImage)
                                    .font(.system(size: 52))
                                    .foregroundStyle(.tint)
                                    .accessibilityHidden(true)
                                Text(tip.title)
                                    .font(.title2.bold())
                                    .multilineTextAlignment(.center)
                                Text(tip.body)
                                    .font(.body)
                                    .multilineTextAlignment(.center)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(.horizontal, 24)
                            .padding(.top, 24)
                        }
                        .tag(tip.id)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .always))
                .indexViewStyle(.page(backgroundDisplayMode: .always))

                Button {
                    if isLast { dismiss() } else { withAnimation { page += 1 } }
                } label: {
                    Text(isLast ? Messages.tipsDone() : Messages.tipsNext())
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
                .accessibilityIdentifier("tips.next")
            }
            .navigationTitle(Messages.tipsTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !isLast {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(Messages.tipsSkip()) { dismiss() }
                    }
                }
            }
        }
        .onDisappear { FirstRunTips.markSeen() }
    }
}
