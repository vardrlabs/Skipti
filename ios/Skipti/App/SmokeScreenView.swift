import SwiftUI

#if DEBUG
// D-235 (C4, iOS L1, decision 108): a proof surface, Debug builds only.
/// The smoke screen, proof the whole stack runs: Swift → UniFFI → Rust
/// core → SQLCipher vault → iOS Keychain. Deliberately bare: it is a
/// proof surface, reached with `--smoke` since D-128 made `HomeShell` the
/// product root.
struct SmokeScreenView: View {
    @State private var result: VaultSmoke.Result?
    /// §10.3's status register: terse, never an error dialog.

    var body: some View {
        List {
            Section("Core") {
                row("skipti_core version", result?.version ?? "…")
            }
            Section("Vault round-trip (real Keychain)") {
                row("record + kv identical after reopen", check(result?.recordRoundTrip))
                row("wrong key rejected as WrongKey", check(result?.wrongKeyRejected))
                row("original key still opens (no re-key)", check(result?.originalKeyStillOpens))
            }
            Section("Account") {
                row("Firebase account", Account.shared.isSignedIn ? "signed in" : "no account")
            }
            if let detail = result?.detail, !detail.isEmpty {
                Section { Text(detail).foregroundStyle(.red) }
            }
        }
        .navigationTitle("Skipti smoke")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink("Balance") {
                    BalanceScreen(source: .vault)
                }
                .barButtonText()
                .barTint()
            }
            ToolbarItem(placement: .bottomBar) {
                HStack {
                    NavigationLink("Expenses") {
                        ExpenseListView()
                    }
                    // D-126 hung the account surface here because this menu
                    // WAS the root. Since D-128 users reach it from the
                    // Balance toolbar glyph in HomeShell; this link stays
                    // only as a proof-surface shortcut.
                    NavigationLink("Account") {
                        AccountHubScreen(vaultPath: LedgerStore.defaultStore().path)
                    }
                    NavigationLink("Sign in") {
                        SignInScreen()
                    }
                    NavigationLink("Add expense") {
                        AddExpenseScreen()
                    }
                    NavigationLink("Settle up") {
                        SettleScreen(source: .vault)
                    }
                }
                .barButtonText()
                .barTint()
            }
        }
        .task { runSmoke() }
    }


    /// Runs off the main actor: SQLCipher open + Keychain are synchronous C calls.
    private func runSmoke() {
        DispatchQueue.global(qos: .userInitiated).async {
            let r = VaultSmoke.run()
            DispatchQueue.main.async { self.result = r }
        }
    }

    private func check(_ value: Bool?) -> String {
        switch value {
        case .some(true): return "✅"
        case .some(false): return "❌"
        case nil: return "…"
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).font(TypeRole.mono(14)).foregroundStyle(.secondary)
        }
    }
}
#endif
