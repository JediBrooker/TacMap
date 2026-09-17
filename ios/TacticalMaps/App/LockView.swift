import SwiftUI

/// Full-screen unlock gate when App Lock PIN is set. Offers biometric
/// (auto-prompted) and 4-digit PIN fallback. After repeated failures
/// PIN entry is throttled with escalating lockout (see AppLock).
struct LockView: View {
    let onUnlocked: () -> Void
    @State private var pin = ""
    @State private var showError = false
    @State private var lockoutRemaining: TimeInterval = AppLock.lockoutRemaining

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var isLockedOut: Bool { lockoutRemaining > 0 }

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 52))
                .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
            Text(L10n.text("TacMap Locked")).font(.title2.bold()).foregroundStyle(.white)

            SecureField("PIN", text: $pin)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.system(.title3, design: .monospaced))
                .frame(width: 180)
                .padding(10)
                .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(.white)
                .disabled(isLockedOut)
                .opacity(isLockedOut ? 0.4 : 1)
                .onChange(of: pin) { value in
                    let digits = value.filter(\.isNumber)
                    if digits != value { pin = digits; return }
                    showError = false
                    if digits.count >= 4 { submit() }
                }

            if isLockedOut {
                Text(L10n.text("Too many attempts. Try again in %1$@s", Int(lockoutRemaining.rounded(.up))))
                    .font(.caption).foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
            } else if showError {
                Text(L10n.text("Incorrect PIN")).font(.caption).foregroundStyle(.red)
            }

            if AppLock.biometryAvailable {
                Button {
                    tryBiometric()
                } label: {
                    Label(L10n.text("Use Face ID / Touch ID"), systemImage: "faceid")
                }
                .foregroundStyle(.white.opacity(0.85))
                .disabled(isLockedOut)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black)
        .onAppear { if !isLockedOut { tryBiometric() } }
        .onReceive(tick) { _ in lockoutRemaining = AppLock.lockoutRemaining }
    }

    private func submit() {
        if AppLock.verify(pin) {
            onUnlocked()
        } else {
            pin = ""
            lockoutRemaining = AppLock.lockoutRemaining
            showError = !isLockedOut
        }
    }

    private func tryBiometric() {
        guard !isLockedOut else { return }
        AppLock.authenticateBiometric(reason: L10n.text("Unlock TacMap")) { ok in
            if ok { onUnlocked() }
        }
    }
}

/// Enable / change / disable App Lock PIN. Disabling or changing existing
/// PIN requires entering the current one so it can't be removed by someone
/// who doesn't know it.
struct AppLockSetupView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isEnabled = AppLock.isEnabled
    @State private var currentPIN = ""
    @State private var newPIN = ""
    @State private var confirmPIN = ""
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                if isEnabled {
                    Section {
                        SecureField(L10n.text("Current PIN"), text: $currentPIN).keyboardType(.numberPad)
                    } header: {
                        Text(L10n.text("App Lock is on"))
                    } footer: {
                        Text(L10n.text("Enter your current PIN to change or turn off the lock."))
                    }
                    Section(L10n.text("Change PIN")) {
                        SecureField(L10n.text("New PIN"), text: $newPIN).keyboardType(.numberPad)
                        SecureField(L10n.text("Confirm new PIN"), text: $confirmPIN).keyboardType(.numberPad)
                        Button(L10n.text("Change PIN")) { changePIN() }
                            .disabled(currentPIN.count != 4 || newPIN.count != 4 || confirmPIN.count != 4)
                    }
                    Section {
                        Button(L10n.text("Turn Off App Lock"), role: .destructive) { disable() }
                            .disabled(currentPIN.count != 4)
                    }
                } else {
                    Section {
                        SecureField(L10n.text("New PIN"), text: $newPIN).keyboardType(.numberPad)
                        SecureField(L10n.text("Confirm PIN"), text: $confirmPIN).keyboardType(.numberPad)
                        Button(L10n.text("Enable App Lock")) { enable() }
                            .disabled(newPIN.count != 4 || confirmPIN.count != 4)
                    } header: {
                        Text(L10n.text("Set a 4-digit PIN"))
                    } footer: {
                        Text(L10n.text("A deterrent if your device is lost or borrowed. Not a substitute for full device encryption."))
                    }
                }

                if let message {
                    Section { Text(message).font(.caption).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle(L10n.text("App Lock"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } } }
        }
    }

    private func enable() {
        guard newPIN == confirmPIN, newPIN.count == 4, newPIN.allSatisfy(\.isNumber) else {
            message = L10n.text("PINs must match and be 4 digits.")
            return
        }
        do {
            try AppLock.setPIN(newPIN)
            resetFields()
            isEnabled = true
            message = L10n.text("App Lock enabled. TacMap will lock when backgrounded.")
        } catch {
            message = error.localizedDescription
        }
    }

    private func changePIN() {
        guard AppLock.verify(currentPIN) else {
            message = AppLock.lockoutRemaining > 0
                ? L10n.text("Too many attempts. Try again shortly.")
                : L10n.text("Current PIN is incorrect.")
            return
        }
        guard newPIN == confirmPIN, newPIN.count == 4, newPIN.allSatisfy(\.isNumber) else {
            message = L10n.text("New PINs must match and be 4 digits.")
            return
        }
        do {
            try AppLock.setPIN(newPIN)
            resetFields()
            message = L10n.text("PIN changed.")
        } catch {
            message = error.localizedDescription
        }
    }

    private func disable() {
        do {
            if try AppLock.disable(currentPIN: currentPIN) {
                resetFields()
                isEnabled = false
                message = L10n.text("App Lock disabled.")
            } else {
                message = AppLock.lockoutRemaining > 0
                    ? L10n.text("Too many attempts. Try again shortly.")
                    : L10n.text("Current PIN is incorrect.")
            }
        } catch {
            message = error.localizedDescription
        }
    }

    private func resetFields() {
        currentPIN = ""; newPIN = ""; confirmPIN = ""
    }
}
