import SwiftUI

/// Full-screen unlock gate shown when an App Lock PIN is set. Offers biometric
/// (auto-prompted) and a 4-digit PIN fallback.
struct LockView: View {
    let onUnlocked: () -> Void
    @State private var pin = ""
    @State private var showError = false

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 52))
                .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
            Text("TacMap Locked").font(.title2.bold()).foregroundStyle(.white)

            SecureField("PIN", text: $pin)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.system(.title3, design: .monospaced))
                .frame(width: 180)
                .padding(10)
                .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(.white)
                .onChange(of: pin) { value in
                    let digits = value.filter(\.isNumber)
                    if digits != value { pin = digits; return }
                    if digits.count >= 4 { submit() }
                }

            if showError {
                Text("Incorrect PIN").font(.caption).foregroundStyle(.red)
            }

            if AppLock.biometryAvailable {
                Button {
                    tryBiometric()
                } label: {
                    Label("Use Face ID / Touch ID", systemImage: "faceid")
                }
                .foregroundStyle(.white.opacity(0.85))
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black)
        .onAppear { tryBiometric() }
    }

    private func submit() {
        if AppLock.verify(pin) {
            onUnlocked()
        } else {
            showError = true
            pin = ""
        }
    }

    private func tryBiometric() {
        AppLock.authenticateBiometric(reason: "Unlock TacMap") { ok in
            if ok { onUnlocked() }
        }
    }
}

/// Enable / change / disable the App Lock PIN.
struct AppLockSetupView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var enabled = AppLock.isEnabled
    @State private var newPIN = ""
    @State private var confirmPIN = ""
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Require PIN to open TacMap", isOn: $enabled)
                        .onChange(of: enabled) { on in
                            if !on { AppLock.clear(); message = "App Lock disabled." }
                        }
                } footer: {
                    Text("A deterrent if your device is lost or borrowed. Not a substitute for full device encryption.")
                }

                if enabled {
                    Section("Set a 4-digit PIN") {
                        SecureField("New PIN", text: $newPIN).keyboardType(.numberPad)
                        SecureField("Confirm PIN", text: $confirmPIN).keyboardType(.numberPad)
                        Button("Save PIN") { savePIN() }
                            .disabled(newPIN.count != 4 || confirmPIN.count != 4)
                    }
                }

                if let message {
                    Section { Text(message).font(.caption).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("App Lock")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }

    private func savePIN() {
        guard newPIN == confirmPIN, newPIN.count == 4, newPIN.allSatisfy(\.isNumber) else {
            message = "PINs must match and be 4 digits."
            return
        }
        AppLock.setPIN(newPIN)
        newPIN = ""; confirmPIN = ""
        message = "PIN saved. TacMap will lock on next launch."
    }
}
