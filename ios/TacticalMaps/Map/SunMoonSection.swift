import SwiftUI
import CoreLocation

/// Offline sun and moon times for the map centre. Shown in the Weather sheet
/// whether or not online lookups are enabled: nothing leaves the device.
struct SunMoonSection: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let coordinate: CLLocationCoordinate2D
    @State private var dayOffset = 0

    var body: some View {
        let interval = SunMoonCalculator.localDay(containing: Date(), dayOffset: dayOffset)
        let day = SunMoonCalculator.day(latitude: coordinate.latitude,
                                        longitude: coordinate.longitude,
                                        interval: interval)
        VStack(alignment: .leading, spacing: 10) {
            Text(Messages.sunMoonTitle())
                .font(.headline)
            Picker(Messages.sunMoonTitle(), selection: $dayOffset) {
                Text(Messages.sunMoonToday()).tag(0)
                Text(Messages.sunMoonTomorrow()).tag(1)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("sunMoon.day")

            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 8) {
                row(Messages.sunMoonBmnt(), day.bmnt, icon: "sun.horizon")
                row(Messages.sunMoonBmct(), day.bmct, icon: "sun.horizon.fill")
                row(Messages.sunMoonSunrise(), day.sunrise, icon: "sunrise")
                row(Messages.sunMoonSunset(), day.sunset, icon: "sunset")
                row(Messages.sunMoonEect(), day.eect, icon: "sun.horizon.fill")
                row(Messages.sunMoonEent(), day.eent, icon: "sun.horizon")
                row(Messages.sunMoonMoonrise(), day.moonrise, icon: "moon.stars")
                row(Messages.sunMoonMoonset(), day.moonset, icon: "moon.zzz")
                GridRow {
                    Label(Messages.sunMoonIllumination(), systemImage: "moon.circle")
                        .foregroundStyle(.secondary)
                    Text(illumination(day))
                        .font(.system(.body, design: .rounded).weight(.semibold))
                        .gridColumnAlignment(.trailing)
                }
                .accessibilityElement(children: .combine)
            }

            Text(Messages.sunMoonHelp(TimeZone.current.identifier))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func row(_ name: String, _ date: Date?, icon: String) -> some View {
        GridRow {
            Label(name, systemImage: icon)
                .foregroundStyle(.secondary)
            Text(date.map { DisplayFormat.time($0) } ?? "—")
                .font(.system(.body, design: .rounded).weight(.semibold).monospacedDigit())
                .gridColumnAlignment(.trailing)
                .accessibilityLabel(date.map { DisplayFormat.time($0) } ?? Messages.sunMoonNoEvent())
        }
        .accessibilityElement(children: .combine)
    }

    private func illumination(_ day: SunMoonDay) -> String {
        let percent = DisplayFormat.percent(Int((day.moonIllumination * 100).rounded()))
        return day.moonWaxing ? Messages.sunMoonWaxing(percent) : Messages.sunMoonWaning(percent)
    }
}
