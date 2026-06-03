import SwiftUI

/// Full-screen paywall shown once the free trial has lapsed and the unlock
/// has not been purchased. Blocks the app until the user buys the one-time
/// unlock or restores a previous purchase.
struct PaywallView: View {
    @ObservedObject var store: StoreManager
    /// >0 while the trial is still running; 0 once it has expired.
    let trialDaysRemaining: Int
    let onRestore: () -> Void
    /// When non-nil the paywall is being shown on-demand (e.g. from the menu
    /// during the trial) and gets a close button. nil = the hard launch gate.
    var onClose: (() -> Void)? = nil

    private let green = Color(red: 0.55, green: 0.95, blue: 0.55)   // hud_green
    private let orange = Color(red: 0.95, green: 0.64, blue: 0.29)  // hud_orange
    private let background = Color(red: 0.082, green: 0.098, blue: 0.086) // launcher_background

    private var expired: Bool { trialDaysRemaining <= 0 }

    var body: some View {
        ZStack {
            background.ignoresSafeArea()
            VStack(spacing: 0) {
                Spacer()

                Text("TacticalMaps")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(green)

                Text(expired ? "Your free trial has ended" : "Unlock the full version")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.top, 10)

                Text(bodyText)
                    .font(.subheadline)
                    .foregroundStyle(Color(white: 0.74))
                    .multilineTextAlignment(.center)
                    .padding(.top, 14)
                    .padding(.horizontal, 28)

                Button {
                    Task { await store.purchase() }
                } label: {
                    Group {
                        if store.purchasing {
                            ProgressView().tint(.black)
                        } else {
                            Text(buttonTitle)
                                .font(.system(size: 16, weight: .bold))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(store.product == nil ? Color(white: 0.22) : green,
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .foregroundStyle(store.product == nil ? Color(white: 0.5) : .black)
                }
                .disabled(store.product == nil || store.purchasing)
                .padding(.top, 28)
                .padding(.horizontal, 28)

                Button(action: onRestore) {
                    Text("Restore purchase")
                        .font(.subheadline)
                        .foregroundStyle(orange)
                }
                .padding(.top, 10)

                Text("One-time purchase. No subscription.")
                    .font(.caption)
                    .foregroundStyle(Color(white: 0.48))
                    .padding(.top, 18)

                Spacer()
            }

            if let onClose {
                VStack {
                    HStack {
                        Spacer()
                        Button(action: onClose) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title2)
                                .symbolRenderingMode(.hierarchical)
                                .foregroundStyle(Color(white: 0.6))
                        }
                        .padding()
                    }
                    Spacer()
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var buttonTitle: String {
        if let price = store.priceText { return "Unlock Full Version  ·  \(price)" }
        return "Loading price…"
    }

    private var bodyText: String {
        if expired {
            return "Your \(TrialManager.trialDays)-day free trial is over. Make a one-time "
                + "purchase to keep using TacticalMaps — live MGRS, GeoPDF maps, "
                + "NATO APP-6 symbology and GeoJSON export."
        }
        let unit = trialDaysRemaining == 1 ? "day" : "days"
        return "You're on the free trial (\(trialDaysRemaining) \(unit) left). "
            + "Unlock now for permanent access."
    }
}
