import SwiftUI

/// The guided tour drawn over the map. Everything is dimmed except the control
/// the current step is about, which gets a pulsing ring, and a card with an
/// arrow explains it. It lives in the app's own view tree, so night mode turns
/// it red like the rest of the map, and it blocks the map while it is open.
struct MapTourOverlay: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let steps: [FirstRunTips.Step]
    @Binding var index: Int
    let anchors: [TourTarget: Anchor<CGRect>]
    let onFinish: () -> Void

    private let cardFill = Color(uiColor: .secondarySystemBackground)
    private let accent = Color.orange

    var body: some View {
        GeometryReader { proxy in
            let step = steps[min(max(index, 0), steps.count - 1)]
            let spot = spotlight(for: step.target, in: proxy)
            ZStack(alignment: .topLeading) {
                SpotlightShape(
                    hole: spot?.rect ?? CGRect(x: proxy.size.width / 2, y: proxy.size.height / 2, width: 0, height: 0),
                    cornerRadius: spot?.cornerRadius ?? 0
                )
                .fill(Color.black.opacity(0.74), style: FillStyle(eoFill: true))
                .contentShape(Rectangle())
                .onTapGesture {}
                .accessibilityHidden(true)

                if let spot {
                    SpotlightRing(spot: spot, color: accent,
                                  showsHand: step.target == .mapHold, pulses: !reduceMotion)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }

                callout(step: step, spot: spot?.rect, in: proxy)
            }
            .animation(reduceMotion ? nil : .spring(response: 0.38, dampingFraction: 0.86), value: index)
        }
        .ignoresSafeArea()
    }

    // MARK: - Spotlight

    struct Spot {
        var rect: CGRect
        var cornerRadius: CGFloat
    }

    private func spotlight(for target: TourTarget?, in proxy: GeometryProxy) -> Spot? {
        guard let target else { return nil }
        let size = proxy.size
        switch target {
        case .crosshair:
            // The crosshair is centred on the full screen, like this overlay.
            return circle(at: CGPoint(x: size.width / 2, y: size.height / 2), radius: 34)
        case .mapHold:
            // Any empty spot works; this one sits clear of the crosshair and
            // the controls at the bottom.
            return circle(at: CGPoint(x: size.width / 2, y: size.height * 0.66), radius: 48)
        default:
            guard let anchor = anchors[target] else { return nil }
            let rect = proxy[anchor].insetBy(dx: -6, dy: -6)
            let round = abs(rect.width - rect.height) < 12
            return Spot(rect: rect, cornerRadius: round ? min(rect.width, rect.height) / 2 : 18)
        }
    }

    private func circle(at centre: CGPoint, radius: CGFloat) -> Spot {
        Spot(rect: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2),
             cornerRadius: radius)
    }

    // MARK: - Callout

    @ViewBuilder
    private func callout(step: FirstRunTips.Step, spot: CGRect?, in proxy: GeometryProxy) -> some View {
        let size = proxy.size
        let insets = proxy.safeAreaInsets
        let width = min(size.width - 32, 380)
        if let spot {
            let below = size.height - spot.maxY - insets.bottom >= spot.minY - insets.top
            let cardX = min(max(spot.midX - width / 2, 16), size.width - width - 16)
            let arrowX = min(max(spot.midX - cardX, 26), width - 26)
            VStack(spacing: 0) {
                if below {
                    Color.clear.frame(height: spot.maxY + 8)
                    arrow(pointingUp: true, x: arrowX, width: width)
                    card(step: step, width: width)
                    Spacer(minLength: insets.bottom + 12)
                } else {
                    Spacer(minLength: insets.top + 12)
                    card(step: step, width: width)
                    arrow(pointingUp: false, x: arrowX, width: width)
                    Color.clear.frame(height: max(size.height - spot.minY + 8, 0))
                }
            }
            .frame(width: width, height: size.height)
            .offset(x: cardX)
        } else {
            card(step: step, width: width)
                .frame(width: size.width, height: size.height)
        }
    }

    private func arrow(pointingUp: Bool, x: CGFloat, width: CGFloat) -> some View {
        TourArrow()
            .fill(cardFill)
            .frame(width: 22, height: 11)
            .rotationEffect(.degrees(pointingUp ? 0 : 180))
            .offset(x: x - 11)
            .frame(width: width, alignment: .leading)
            .accessibilityHidden(true)
    }

    private func card(step: FirstRunTips.Step, width: CGFloat) -> some View {
        let isLast = index >= steps.count - 1
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center) {
                progress
                Spacer(minLength: 8)
                if !isLast {
                    Button(Messages.tipsSkip(), action: onFinish)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("tour.skip")
                }
            }
            Text(step.title)
                .font(.title3.bold())
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(step.body)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                if index > 0 {
                    Button(Messages.tourBack()) { index -= 1 }
                        .accessibilityIdentifier("tour.back")
                }
                Spacer(minLength: 8)
                Button {
                    if isLast { onFinish() } else { index += 1 }
                } label: {
                    Text(isLast ? Messages.tipsDone() : Messages.tipsNext())
                        .fontWeight(.semibold)
                        .foregroundStyle(.black)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 30)
                }
                .buttonStyle(.borderedProminent)
                .tint(accent)
                .accessibilityIdentifier("tour.next")
            }
            .padding(.top, 2)
        }
        .padding(16)
        .frame(width: width, alignment: .leading)
        .background(cardFill, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(accent.opacity(0.55), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape, onFinish)
        .accessibilityIdentifier("tour.card")
    }

    private var progress: some View {
        HStack(spacing: 5) {
            ForEach(steps.indices, id: \.self) { step in
                Capsule()
                    .fill(step == index ? accent : Color.secondary.opacity(0.45))
                    .frame(width: step == index ? 16 : 6, height: 6)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Messages.tourProgress(String(index + 1), String(steps.count)))
    }
}

/// A full-screen rectangle with a rounded hole; fill it with the even-odd rule.
/// The hole animates between steps.
private struct SpotlightShape: Shape {
    var hole: CGRect
    var cornerRadius: CGFloat

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>,
                                       AnimatablePair<AnimatablePair<CGFloat, CGFloat>, CGFloat>> {
        get {
            AnimatablePair(AnimatablePair(hole.minX, hole.minY),
                           AnimatablePair(AnimatablePair(hole.width, hole.height), cornerRadius))
        }
        set {
            hole = CGRect(x: newValue.first.first, y: newValue.first.second,
                          width: newValue.second.first.first, height: newValue.second.first.second)
            cornerRadius = newValue.second.second
        }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        let radius = max(0, min(cornerRadius, min(hole.width, hole.height) / 2))
        path.addRoundedRect(in: hole, cornerSize: CGSize(width: radius, height: radius))
        return path
    }
}

/// The ring around the highlighted control, with a soft pulse unless Reduce
/// Motion is on. The hold step adds a finger to show where to press.
private struct SpotlightRing: View {
    let spot: MapTourOverlay.Spot
    let color: Color
    let showsHand: Bool
    let pulses: Bool

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: spot.cornerRadius, style: .continuous)
                .stroke(color, lineWidth: 2.5)
            if pulses {
                TimelineView(.animation) { context in
                    let phase = context.date.timeIntervalSinceReferenceDate
                        .truncatingRemainder(dividingBy: 1.6) / 1.6
                    RoundedRectangle(cornerRadius: spot.cornerRadius, style: .continuous)
                        .stroke(color, lineWidth: 2)
                        .scaleEffect(1 + 0.3 * phase)
                        .opacity(1 - phase)
                }
            }
            if showsHand {
                Image(systemName: "hand.point.up.left.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.6), radius: 4)
                    .offset(x: 10, y: 14)
            }
        }
        .frame(width: spot.rect.width, height: spot.rect.height)
        .position(x: spot.rect.midX, y: spot.rect.midY)
    }
}

/// The small triangle joining the card to the highlighted control.
private struct TourArrow: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}
