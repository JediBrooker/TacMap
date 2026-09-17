import SwiftUI
import CoreLocation

/// Weather + UAV safety sheet for a coordinate. User taps "Weather"
/// on map centre, we fetch conditions and show green/amber/red
/// drone risk assesment.
struct WeatherSheet: View {
    let coordinate: CLLocationCoordinate2D
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var opsec = OpsecSettings.shared

    @State private var reading: WeatherReading? = nil
    @State private var loading = true
    @State private var reloadToken = 0
    private let service = WeatherService()

    private var risk: UAVRisk { reading.map { UAVAssessment.risk(for: $0) } ?? .safe }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                // location label
                Text(MGRSFormatter.string(from: coordinate))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                if loading {
                    HStack { ProgressView(); Text(L10n.text("Fetching conditions…")) }
                        .foregroundStyle(.secondary)
                } else if let r = reading {
                    riskBanner
                    Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 10) {
                        metric(L10n.text("Wind"), value: r.windSpeedMs, unit: "m/s", icon: "wind")
                        metric(L10n.text("Gusts"), value: r.windGustsMs, unit: "m/s", icon: "wind.circle")
                        metric(L10n.text("Visibility"), value: r.visibilityM.map { $0 / 1000 }, unit: "km", icon: "eye")
                        metric(L10n.text("Temp"), value: r.temperatureC, unit: "°C", icon: "thermometer.medium")
                    }
                    Text(L10n.text("Source: Open-Meteo. UAV thresholds are defaults for small drones — treat as advisory, not a clearance."))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        Label(L10n.text("Couldn't fetch conditions. If online lookups are off (Settings, Privacy & OPSEC), enable them; otherwise check your connection."),
                              systemImage: "wifi.slash")
                            .foregroundStyle(.secondary)
                        Button { reloadToken += 1 } label: {
                            Label(L10n.text("Retry"), systemImage: "arrow.clockwise")
                        }
                    }
                }
                Spacer()
            }
            .padding()
            .navigationTitle(L10n.text("Weather & UAV Safety"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } } }
            .task(id: WeatherTaskKey(reloadToken: reloadToken, onlineLookups: opsec.onlineLookups)) {
                loading = true
                reading = opsec.onlineLookups
                    ? await service.reading(for: coordinate)
                    : nil
                loading = false
            }
        }
    }

    private var riskBanner: some View {
        let color: Color = risk == .safe ? .green : (risk == .caution ? .orange : .red)
        return HStack(spacing: 12) {
            Image(systemName: risk == .danger ? "exclamationmark.octagon.fill"
                  : (risk == .caution ? "exclamationmark.triangle.fill" : "checkmark.seal.fill"))
                .font(.title2)
            Text(risk.label).font(.headline)
            Spacer()
        }
        .foregroundStyle(.white)
        .padding()
        .frame(maxWidth: .infinity)
        .background(color, in: RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private func metric(_ name: String, value: Double?, unit: String, icon: String) -> some View {
        GridRow {
            Label(name, systemImage: icon)
                .foregroundStyle(.secondary)
            Text(value.map { String(format: "%.1f %@", $0, unit) } ?? "—")
                .font(.system(.body, design: .rounded).weight(.semibold))
                .gridColumnAlignment(.trailing)
        }
    }
}

private struct WeatherTaskKey: Hashable {
    let reloadToken: Int
    let onlineLookups: Bool
}
