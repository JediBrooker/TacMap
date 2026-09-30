import SwiftUI
import CoreLocation

/// A path to profile, as a sheet item.
struct ElevationProfileRequest: Identifiable {
    let id = UUID()
    let path: [ElevationProfile.Coordinate]

    init(_ coordinates: [CLLocationCoordinate2D]) {
        path = coordinates.map { .init(latitude: $0.latitude, longitude: $0.longitude) }
    }

    init(_ coordinates: [Coordinate2D]) {
        path = coordinates.map { .init(latitude: $0.latitude, longitude: $0.longitude) }
    }
}

/// Terrain height along a measured line or line drawing. For a straight line
/// between two points it also checks line of sight from an observer at the
/// start to a target at the end and shades the dead ground between them.
/// Android mirrors this in `ElevationProfileDialog.kt`.
struct ElevationProfileSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @Environment(\.dismiss) private var dismiss
    let request: ElevationProfileRequest
    var service = ElevationProfileService()

    private enum Phase: Equatable {
        case loading, lookupsOff, failed, tooShort
        case loaded(samples: [ElevationProfile.Sample], elevations: [Double])
    }

    @State private var phase: Phase = .loading
    @State private var attempt = 0
    @State private var observerHeight = 2.0
    @State private var targetHeight = 2.0
    @State private var selected: Int?

    /// Eye and target heights the steppers walk through, metres.
    static let heightSteps: [Double] = [0, 1, 2, 3, 5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    content
                }
                .padding()
            }
            .navigationTitle(Messages.profileTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.text("Done")) { dismiss() }
                }
            }
        }
        .task(id: attempt) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            HStack(spacing: 10) {
                ProgressView()
                Text(Messages.profileLoading()).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 160)
        case .lookupsOff:
            Label(Messages.profileLookupsOff(), systemImage: "wifi.slash")
                .accessibilityIdentifier("profile.lookupsOff")
        case .failed:
            Label(Messages.profileFailed(), systemImage: "exclamationmark.triangle")
            Button(Messages.profileRetry()) { attempt += 1 }
                .buttonStyle(.bordered)
        case .tooShort:
            Text(Messages.profileTooShort())
        case let .loaded(samples, elevations):
            loaded(samples: samples, elevations: elevations)
        }
    }

    @ViewBuilder
    private func loaded(samples: [ElevationProfile.Sample], elevations: [Double]) -> some View {
        let distances = samples.map(\.distance)
        let sight = request.path.count == 2
            ? ElevationProfile.lineOfSight(distances: distances, elevations: elevations,
                                           observerHeight: observerHeight, targetHeight: targetHeight)
            : nil

        readout(distances: distances, elevations: elevations, sight: sight)
        ElevationProfileChart(distances: distances, elevations: elevations, sight: sight, selected: $selected)
            .frame(height: 220)
            .accessibilityIdentifier("profile.chart")
        if let sight, sight.visible.contains(false) {
            Label(Messages.profileDeadGround(), systemImage: "square.fill")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .labelStyle(DeadGroundLegendStyle())
        }
        if let stats = ElevationProfile.stats(elevations: elevations) {
            ProfileStatsGrid(length: distances.last ?? 0, start: elevations.first ?? 0,
                             end: elevations.last ?? 0, stats: stats)
        }
        if request.path.count == 2 {
            lineOfSightSection(sight: sight, distances: distances)
        } else {
            Text(Messages.profileLosNeedsTwoPoints())
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        Text(Messages.profileSource())
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func readout(distances: [Double], elevations: [Double],
                         sight: ElevationProfile.SightLine?) -> some View {
        Group {
            if let index = selected, distances.indices.contains(index) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(Messages.profileReadout(DisplayFormat.distance(distances[index]),
                                                 DisplayFormat.height(elevations[index])))
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                    if let sight, sight.visible.indices.contains(index), !sight.visible[index] {
                        Text(Messages.profileReadoutHidden())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                Text(Messages.profileHint())
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
    }

    private func lineOfSightSection(sight: ElevationProfile.SightLine?, distances: [Double]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(Messages.profileLosTitle()).font(.headline)
            heightStepper(Messages.profileObserverHeight(), value: $observerHeight)
                .accessibilityIdentifier("profile.observerHeight")
            heightStepper(Messages.profileTargetHeight(), value: $targetHeight)
                .accessibilityIdentifier("profile.targetHeight")
            if let sight {
                verdict(sight, distances: distances)
            }
        }
    }

    private func heightStepper(_ title: String, value: Binding<Double>) -> some View {
        Stepper {
            HStack {
                Text(title)
                Spacer()
                Text(DisplayFormat.height(value.wrappedValue))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
        } onIncrement: {
            value.wrappedValue = Self.heightSteps.first { $0 > value.wrappedValue } ?? value.wrappedValue
        } onDecrement: {
            value.wrappedValue = Self.heightSteps.last { $0 < value.wrappedValue } ?? value.wrappedValue
        }
    }

    private func verdict(_ sight: ElevationProfile.SightLine, distances: [Double]) -> some View {
        let text: String
        if let index = sight.worstIndex, let margin = sight.worstMargin {
            let at = DisplayFormat.distance(distances[index])
            text = sight.blocked
                ? Messages.profileLosBlocked(DisplayFormat.height(margin), at)
                : Messages.profileLosClear(DisplayFormat.height(-margin), at)
        } else {
            text = Messages.profileLosClearShort()
        }
        return Label(text, systemImage: sight.blocked ? "eye.slash.fill" : "eye.fill")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(sight.blocked ? Color.red : Color.green)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier(sight.blocked ? "profile.blocked" : "profile.clear")
    }

    private func load() async {
        let samples = ElevationProfile.samples(along: request.path)
        guard samples.count >= 2 else {
            phase = .tooShort
            return
        }
        phase = .loading
        do {
            let elevations = try await service.elevations(for: samples)
            phase = .loaded(samples: samples, elevations: elevations)
        } catch ElevationProfileService.Failure.lookupsOff {
            phase = .lookupsOff
        } catch is CancellationError {
            return
        } catch {
            if !Task.isCancelled { phase = .failed }
        }
    }
}

/// A small grey square before the dead-ground legend, matching the chart.
private struct DeadGroundLegendStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            RoundedRectangle(cornerRadius: 2)
                .fill(ElevationProfileChart.deadGroundFill)
                .frame(width: 12, height: 12)
                .accessibilityHidden(true)
            configuration.title
        }
    }
}

private struct ProfileStatsGrid: View {
    let length: Double
    let start: Double
    let end: Double
    let stats: ElevationProfile.Stats

    var body: some View {
        let items: [(String, String)] = [
            (Messages.profileLength(), DisplayFormat.distance(length)),
            (Messages.profileClimb(), DisplayFormat.height(stats.ascent)),
            (Messages.profileStart(), DisplayFormat.height(start)),
            (Messages.profileDescent(), DisplayFormat.height(stats.descent)),
            (Messages.profileEnd(), DisplayFormat.height(end)),
            (Messages.profileLowest(), DisplayFormat.height(stats.minimum)),
            (Messages.profileHighest(), DisplayFormat.height(stats.maximum)),
        ]
        LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading),
                            GridItem(.flexible(), alignment: .leading)],
                  alignment: .leading, spacing: 10) {
            ForEach(items.indices, id: \.self) { index in
                VStack(alignment: .leading, spacing: 2) {
                    Text(items[index].0).font(.caption).foregroundStyle(.secondary)
                    Text(items[index].1).font(.body.monospacedDigit())
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// The profile chart: terrain filled below its line (grey where the observer
/// cannot see it), the sight line dashed over it with the critical point
/// marked, and a marker at the touched point. Drag across it to read heights.
struct ElevationProfileChart: View {
    let distances: [Double]
    let elevations: [Double]
    let sight: ElevationProfile.SightLine?
    @Binding var selected: Int?

    static let terrainFill = Color(red: 0.36, green: 0.62, blue: 0.36).opacity(0.55)
    static let deadGroundFill = Color(white: 0.42).opacity(0.75)

    private let leftInset: CGFloat = 52
    private let bottomInset: CGFloat = 22

    var body: some View {
        GeometryReader { geo in
            let plot = CGRect(x: leftInset, y: 6, width: max(geo.size.width - leftInset - 6, 1),
                              height: max(geo.size.height - bottomInset - 6, 1))
            let scale = ChartScale(distances: distances, heights: elevations + (sight?.heights ?? []), plot: plot)
            Canvas { context, _ in
                drawGrid(in: &context, scale: scale, plot: plot)
                drawTerrain(in: &context, scale: scale, plot: plot)
                drawSight(in: &context, scale: scale)
                drawSelection(in: &context, scale: scale, plot: plot)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in selected = scale.index(atX: value.location.x) }
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Messages.profileTitle())
        .accessibilityValue(accessibilitySummary)
    }

    private var accessibilitySummary: String {
        guard let stats = ElevationProfile.stats(elevations: elevations) else { return "" }
        return [
            "\(Messages.profileLength()) \(DisplayFormat.distance(distances.last ?? 0))",
            "\(Messages.profileLowest()) \(DisplayFormat.height(stats.minimum))",
            "\(Messages.profileHighest()) \(DisplayFormat.height(stats.maximum))",
        ].joined(separator: ", ")
    }

    private func drawGrid(in context: inout GraphicsContext, scale: ChartScale, plot: CGRect) {
        for level in scale.gridLevels {
            let y = scale.y(level)
            var line = Path()
            line.move(to: CGPoint(x: plot.minX, y: y))
            line.addLine(to: CGPoint(x: plot.maxX, y: y))
            context.stroke(line, with: .color(.secondary.opacity(0.35)), lineWidth: 0.5)
            context.draw(Text(DisplayFormat.height(level)).font(.caption2).foregroundColor(.secondary),
                         at: CGPoint(x: plot.minX - 4, y: y), anchor: .trailing)
        }
        let total = distances.last ?? 0
        context.draw(Text(DisplayFormat.distance(0)).font(.caption2).foregroundColor(.secondary),
                     at: CGPoint(x: plot.minX, y: plot.maxY + 4), anchor: .topLeading)
        context.draw(Text(DisplayFormat.distance(total)).font(.caption2).foregroundColor(.secondary),
                     at: CGPoint(x: plot.maxX, y: plot.maxY + 4), anchor: .topTrailing)
    }

    private func drawTerrain(in context: inout GraphicsContext, scale: ChartScale, plot: CGRect) {
        guard distances.count >= 2 else { return }
        // Fill each step under the terrain, grey where the observer can't see.
        for i in 0..<(distances.count - 1) {
            var column = Path()
            column.move(to: CGPoint(x: scale.x(distances[i]), y: plot.maxY))
            column.addLine(to: CGPoint(x: scale.x(distances[i]), y: scale.y(elevations[i])))
            column.addLine(to: CGPoint(x: scale.x(distances[i + 1]), y: scale.y(elevations[i + 1])))
            column.addLine(to: CGPoint(x: scale.x(distances[i + 1]), y: plot.maxY))
            column.closeSubpath()
            let hidden = sight.map { !$0.visible[i + 1] } ?? false
            context.fill(column, with: .color(hidden ? Self.deadGroundFill : Self.terrainFill))
        }
        var ridge = Path()
        for i in distances.indices {
            let point = CGPoint(x: scale.x(distances[i]), y: scale.y(elevations[i]))
            if i == 0 { ridge.move(to: point) } else { ridge.addLine(to: point) }
        }
        context.stroke(ridge, with: .color(.primary), lineWidth: 1.5)
    }

    private func drawSight(in context: inout GraphicsContext, scale: ChartScale) {
        guard let sight else { return }
        let colour: Color = sight.blocked ? .red : .orange
        var line = Path()
        for i in distances.indices {
            let point = CGPoint(x: scale.x(distances[i]), y: scale.y(sight.heights[i]))
            if i == 0 { line.move(to: point) } else { line.addLine(to: point) }
        }
        context.stroke(line, with: .color(colour), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
        for (index, radius) in [(0, 4.0), (distances.count - 1, 4.0)] {
            let centre = CGPoint(x: scale.x(distances[index]), y: scale.y(sight.heights[index]))
            context.fill(Path(ellipseIn: CGRect(x: centre.x - radius, y: centre.y - radius,
                                                width: radius * 2, height: radius * 2)), with: .color(colour))
        }
        if let index = sight.worstIndex {
            let centre = CGPoint(x: scale.x(distances[index]), y: scale.y(elevations[index]))
            context.stroke(Path(ellipseIn: CGRect(x: centre.x - 6, y: centre.y - 6, width: 12, height: 12)),
                           with: .color(colour), lineWidth: 2)
        }
    }

    private func drawSelection(in context: inout GraphicsContext, scale: ChartScale, plot: CGRect) {
        guard let index = selected, distances.indices.contains(index) else { return }
        let x = scale.x(distances[index])
        var line = Path()
        line.move(to: CGPoint(x: x, y: plot.minY))
        line.addLine(to: CGPoint(x: x, y: plot.maxY))
        context.stroke(line, with: .color(.primary.opacity(0.6)), lineWidth: 1)
        let y = scale.y(elevations[index])
        context.fill(Path(ellipseIn: CGRect(x: x - 5, y: y - 5, width: 10, height: 10)), with: .color(.primary))
    }
}

/// Maps distances and heights into the plot, with round-number height lines.
private struct ChartScale {
    let plot: CGRect
    let total: Double
    let low: Double
    let high: Double
    let gridLevels: [Double]
    let distances: [Double]

    init(distances: [Double], heights: [Double], plot: CGRect) {
        self.plot = plot
        self.distances = distances
        total = max(distances.last ?? 1, 1)
        let minimum = heights.min() ?? 0
        let maximum = heights.max() ?? 1
        let step = ChartScale.niceStep(for: max(maximum - minimum, 10) / 3)
        low = (minimum / step).rounded(.down) * step
        high = max((maximum / step).rounded(.up) * step, low + step)
        gridLevels = stride(from: low, through: high + step / 2, by: step).map { $0 }
    }

    /// 1, 2 or 5 times a power of ten.
    static func niceStep(for raw: Double) -> Double {
        let magnitude = pow(10, (log10(max(raw, 1))).rounded(.down))
        for multiple in [1.0, 2.0, 5.0, 10.0] where multiple * magnitude >= raw {
            return multiple * magnitude
        }
        return 10 * magnitude
    }

    func x(_ distance: Double) -> CGFloat { plot.minX + CGFloat(distance / total) * plot.width }

    func y(_ height: Double) -> CGFloat { plot.maxY - CGFloat((height - low) / (high - low)) * plot.height }

    func index(atX x: CGFloat) -> Int? {
        guard !distances.isEmpty else { return nil }
        let target = Double((x - plot.minX) / plot.width) * total
        var best = 0
        for i in distances.indices where abs(distances[i] - target) < abs(distances[best] - target) {
            best = i
        }
        return best
    }
}
