import SwiftUI
import UIKit

struct SettingsView: View {
    @ObservedObject var model = TeleprompterModel.shared
    @State private var keyInput = ""
    @State private var alertMsg: String?

    private var deviceId: String { LicenseManager.deviceId() }
    private var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "1.0"
    }

    var body: some View {
        NavigationView {
            Form {
                premiumSection
                faqSection
                aboutSection
            }
            .navigationTitle("Settings")
            .alert("Whisprompt", isPresented: Binding(
                get: { alertMsg != nil },
                set: { if !$0 { alertMsg = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(alertMsg ?? "")
            }
        }
        .navigationViewStyle(.stack)
    }

    // MARK: Premium

    private var premiumSection: some View {
        Section {
            HStack {
                Image(systemName: model.premium ? "checkmark.seal.fill" : "seal")
                    .foregroundColor(model.premium ? .green : .secondary)
                Text(model.premium ? "Premium active" : "Free version")
                    .fontWeight(.semibold)
            }
            if !model.premium {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Buy a key in \(LicenseManager.TG_BOT). It is bound to this device.")
                        .font(.caption).foregroundColor(.secondary)
                    HStack {
                        Text("Device ID")
                        Spacer()
                        Text(deviceId).font(.system(.caption, design: .monospaced))
                            .lineLimit(1).truncationMode(.middle)
                        Button {
                            UIPasteboard.general.string = deviceId
                            alertMsg = "Device ID copied"
                        } label: { Image(systemName: "doc.on.doc") }
                    }
                    TextField("Paste activation key", text: $keyInput, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .lineLimit(1...4)
                    Button("Activate") { activate() }
                        .disabled(keyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        } header: {
            Text("Premium")
        }
    }

    private func activate() {
        let ok = LicenseManager.activate(keyInput)
        model.refreshPremium()
        alertMsg = ok ? "Premium activated" : "Key is not valid for this device"
        if ok { keyInput = "" }
    }

    // MARK: FAQ

    private var faqSection: some View {
        Section("FAQ") {
            faqItem("Text stops after a while / in the background?",
                    "Keep Whisprompt in the foreground while presenting. iOS limits background Bluetooth; if the screen locks or another app takes over, the glasses page may close — reopen and press Play.")
            faqItem("How do I control it from the glasses?",
                    "Use the touchbar: swipe to scroll, single tap to switch wide/narrow, double tap to exit.")
            faqItem("Does it work offline?",
                    "Yes — fully. No account, no cloud. Your scripts never leave your phone.")
            faqItem("Restore the official Even experience?",
                    "Whisprompt doesn't change your glasses' firmware. Just open the official Even Realities app to use its features again.")
        }
    }

    private func faqItem(_ q: String, _ a: String) -> some View {
        DisclosureGroup(q) {
            Text(a).font(.footnote).foregroundColor(.secondary)
        }
    }

    // MARK: About

    private var aboutSection: some View {
        Section("About") {
            HStack { Text("Version"); Spacer(); Text(appVersion).foregroundColor(.secondary) }
            Link("Contact: Whisprompt@outlook.com",
                 destination: URL(string: "mailto:Whisprompt@outlook.com")!)
            Text("Independent app. Not affiliated with, authorized by, or endorsed by Even Realities. \"Even\", \"Even Realities\" and \"G2\" are trademarks of their respective owners.")
                .font(.caption2).foregroundColor(.secondary)
            Text("Made by a 16-year-old who just wanted to use his glasses however he wants.")
                .font(.caption2)
                .foregroundColor(.secondary.opacity(0.5))
        }
    }
}
