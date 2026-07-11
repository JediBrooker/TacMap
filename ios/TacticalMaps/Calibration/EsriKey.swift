import Foundation

/// The ArcGIS Location Platform key for the Esri basemap, and whether we have one.
///
/// Substituted into Info.plist (ESRIApiKey) from Secrets.xcconfig at build time.
/// Not a secret - it ships in the bundle and is a quota/billing control.
///
/// When the key is absent the Esri basemap is simply unavailable: we never fall
/// back to the old unauthenticated server.arcgisonline.com endpoint, because
/// hot-linking that is exactly the licensing problem the key exists to fix.
enum EsriKey {
    static var token: String {
        (Bundle.main.infoDictionary?["ESRIApiKey"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static var isAvailable: Bool { !token.isEmpty }
}
