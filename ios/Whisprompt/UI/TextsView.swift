import SwiftUI

struct TextsView: View {
    @ObservedObject var model = TeleprompterModel.shared
    @State private var editing: SavedText?
    @State private var showUpsell = false

    var body: some View {
        NavigationView {
            List {
                if model.texts.isEmpty {
                    Text("No scripts yet. Tap + to add one.")
                        .foregroundColor(.secondary)
                }
                ForEach(model.texts) { t in
                    Button {
                        editing = t
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(t.title.isEmpty ? "Untitled" : t.title)
                                .font(.headline).foregroundColor(.primary)
                            Text("\(t.wordCount) words")
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
                .onDelete { model.delete(at: $0) }
            }
            .navigationTitle("Texts")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        if model.canAddText() { editing = SavedText(title: "", body: "") }
                        else { showUpsell = true }
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $editing) { text in
                TextEditorSheet(text: text) { saved in
                    model.addOrUpdate(saved)
                    editing = nil
                }
            }
            .alert("Free version limit", isPresented: $showUpsell) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("The free version allows up to \(TeleprompterModel.FREE_MAX_TEXTS) scripts of \(TeleprompterModel.FREE_MAX_WORDS) words. Unlock the full version in \(LicenseManager.TG_BOT), or delete a script.")
            }
        }
        .navigationViewStyle(.stack)
    }
}

/// Редактор одного скрипта. Для free-версии применяется лимит слов при сохранении.
struct TextEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var text: SavedText
    let onSave: (SavedText) -> Void

    private var limit: Int? { TeleprompterModel.shared.wordLimit }
    private var wordCount: Int { text.body.split(whereSeparator: { $0.isWhitespace }).count }

    var body: some View {
        NavigationView {
            Form {
                Section("Title") {
                    TextField("Title", text: $text.title)
                }
                Section {
                    TextEditor(text: $text.body)
                        .frame(minHeight: 240)
                } header: {
                    HStack {
                        Text("Script")
                        Spacer()
                        if let limit {
                            Text("\(wordCount)/\(limit) words")
                                .foregroundColor(wordCount > limit ? .red : .secondary)
                        } else {
                            Text("\(wordCount) words").foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Edit script")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(text) }
                        .disabled(text.title.trimmingCharacters(in: .whitespaces).isEmpty
                                  && text.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
