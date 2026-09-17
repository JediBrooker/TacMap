import SwiftUI

/// Full-screen paywall. Shows up when trial expires and user hasn't bought
/// the unlock yet. Blocks everything untill they buy or restore.
struct PaywallView: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
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
            ScrollView {
                VStack(spacing: 0) {
                    Text("TacMap")
                        .font(.largeTitle.bold())
                        .foregroundStyle(green)

                    Text(expired ? L10n.text("Your free trial has ended") : L10n.text("Unlock the full version"))
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)

                    Text(bodyText)
                        .font(.subheadline)
                        .foregroundStyle(Color(white: 0.74))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 14)

                    switch store.loadState {
                    case .failed, .unavailable:
                        Text(store.loadState == .failed
                             ? L10n.text("Couldn't load purchase options. Check your connection and try again.")
                             : L10n.text("The App Store didn't return the TacMap unlock. Try again or restore an existing purchase."))
                            .font(.subheadline)
                            .foregroundStyle(orange)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 24)

                        Button {
                            Task { await store.loadProduct() }
                        } label: {
                            Text(L10n.text("Try Again"))
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 15)
                                .background(green, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .foregroundStyle(.black)
                        }
                        .disabled(store.commerceOperationActive)
                        .padding(.top, 16)

                    case .loading, .loaded:
                        Button {
                            Task { await store.purchase() }
                        } label: {
                            Group {
                                if store.purchasing || store.loadState == .loading {
                                    ProgressView().tint(.black)
                                } else {
                                    Text(buttonTitle)
                                        .font(.headline)
                                        .multilineTextAlignment(.center)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 15)
                            .background(purchaseEnabled ? green : Color(white: 0.22),
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .foregroundStyle(purchaseEnabled ? .black : Color(white: 0.5))
                        }
                        .disabled(!purchaseEnabled)
                        .padding(.top, 28)
                    }

                    Button(action: onRestore) {
                        if store.restoring {
                            ProgressView().tint(orange)
                        } else {
                            Text(L10n.text("Restore purchase"))
                                .font(.subheadline)
                                .foregroundStyle(orange)
                        }
                    }
                    .disabled(store.commerceOperationActive)
                    .padding(.top, 10)

                    Button {
                        Task { await store.redeemOfferCode() }
                    } label: {
                        if store.redeeming {
                            ProgressView().tint(orange)
                        } else {
                            Text(L10n.text("Redeem TacMap offer code"))
                                .font(.subheadline)
                                .foregroundStyle(orange)
                        }
                    }
                    .disabled(store.commerceOperationActive)
                    .padding(.top, 6)

                    Text(L10n.text("One-time purchase. No subscription."))
                        .font(.caption)
                        .foregroundStyle(Color(white: 0.48))
                        .padding(.top, 18)

                    if store.isSandbox {
                        Text(L10n.text("Test build (Sandbox) — purchases are free; you won't be charged."))
                            .font(.caption2)
                            .foregroundStyle(green.opacity(0.9))
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 6)
                    }

                    Spacer(minLength: 0)
                }
                .frame(maxWidth: 560)
                .padding(.horizontal, 28)
                .padding(.top, onClose == nil ? 36 : 64)
                .padding(.bottom, 36)
                .frame(maxWidth: .infinity)
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
                        .accessibilityLabel(L10n.text("Close unlock screen"))
                        .padding()
                    }
                    Spacer()
                }
            }
        }
        .preferredColorScheme(.dark)
        .task {
            await store.loadProduct()
        }
        .onDisappear {
            store.cancelProductLoad()
        }
        .alert(L10n.text("Restore Purchase"),
               isPresented: Binding(get: { store.restoreOutcome != nil },
                                    set: { if !$0 { store.restoreOutcome = nil } }),
               presenting: store.restoreOutcome) { _ in
            Button(Messages.acknowledge(), role: .cancel) { store.restoreOutcome = nil }
        } message: { Text($0) }
        .alert(L10n.text("Redeem Offer Code"),
               isPresented: Binding(get: { store.redemptionOutcome != nil },
                                    set: { if !$0 { store.redemptionOutcome = nil } }),
               presenting: store.redemptionOutcome) { _ in
            Button(Messages.acknowledge(), role: .cancel) { store.redemptionOutcome = nil }
        } message: { Text($0) }
    }

    private var buttonTitle: String {
        if store.purchasePending { return L10n.text("Awaiting approval…") }
        if let price = store.priceText { return L10n.text("Unlock Full Version  ·  %1$@", price) }
        return L10n.text("Loading price…")
    }

    private var purchaseEnabled: Bool {
        store.loadState == .loaded && !store.commerceOperationActive
    }

    private var bodyText: String {
        if expired {
            return Messages.trialExpiredDetails(DisplayFormat.number(Double(TrialManager.trialDays), decimals: 0))
        }
        return Messages.trialRemainingDetails(L10n.quantity("day", trialDaysRemaining))
    }
}
