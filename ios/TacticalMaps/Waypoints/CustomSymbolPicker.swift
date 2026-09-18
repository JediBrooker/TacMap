import SwiftUI

struct CustomSymbolPicker: View {
    @ObservedObject private var store = CustomSymbolStore.shared
    @Binding var selection: String
    @State private var query = ""
    @Environment(\.dismiss) private var dismiss

    private var results: [MarkerCatalog.Entry] {
        store.entries.filter { CustomSymbolSearch.matches($0.name, query: query) }
    }
    var body: some View {
        List {
            if results.isEmpty {
                Text(Messages.symbolsNoMatches())
            }
            ForEach(results, id: \.id) { entry in
                Button {
                    selection = entry.id
                    dismiss()
                } label: {
                    HStack {
                        if let image = store.symbol(entry.id)?.image() {
                            Image(uiImage: image).resizable().scaledToFit().frame(width: 40, height: 40)
                        }
                        Text(verbatim: entry.name).foregroundStyle(.primary)
                        Spacer()
                        if entry.id == selection { Image(systemName: "checkmark") }
                    }
                }
            }
        }
        .navigationTitle(Messages.symbolsCustomSymbols())
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L10n.text("Search"))
    }
}
