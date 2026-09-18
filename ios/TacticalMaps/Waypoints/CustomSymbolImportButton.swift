import SwiftUI
import UniformTypeIdentifiers

struct CustomSymbolImportButton: View {
    @ObservedObject private var store = CustomSymbolStore.shared
    @State private var choosing = false
    @State private var failed = false
    var onImport: (CustomSymbol) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(Messages.symbolsImportSymbolPack()) { choosing = true }
            Text(Messages.symbolsSymbolPackHelp())
                .font(.caption).foregroundStyle(.secondary)
            ForEach(store.packs, id: \.name) { pack in
                if !pack.attribution.isEmpty {
                    Text(verbatim: pack.attribution).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .onAppear {
            do { try store.reload() } catch { failed = true }
        }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [.json]) { result in
            switch result {
            case .failure: failed = true
            case .success(let url):
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                do {
                    guard let input = InputStream(url: url) else { throw CustomSymbolPackError.invalid }
                    input.open(); defer { input.close() }
                    var bytes = Data(); var buffer = [UInt8](repeating: 0, count: 8192)
                    while true {
                        let count = input.read(&buffer, maxLength: buffer.count)
                        if count == 0 { break }
                        guard count > 0, bytes.count <= 16 * 1024 * 1024 - count else { throw CustomSymbolPackError.invalid }
                        bytes.append(contentsOf: buffer.prefix(count))
                    }
                    let pack = try store.importPack(bytes)
                    onImport(pack.symbols[0])
                } catch { failed = true }
            }
        }
        .alert(Messages.symbolsSymbolPackFailed(), isPresented: $failed) {
            Button(Messages.acknowledge(), role: .cancel) { }
        } message: {
            Text(Messages.symbolsSymbolPackError())
        }
    }
}
