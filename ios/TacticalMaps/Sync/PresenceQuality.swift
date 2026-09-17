import Foundation
import CoreLocation

struct SyncReconnectBackoff: Equatable {
    static let baseDelay: TimeInterval = 1
    static let maximumDelay: TimeInterval = 30
    static let jitterFraction = 0.20

    private(set) var attemptCount = 0

    mutating func nextDelay(randomUnit: Double = Double.random(in: 0...1)) -> TimeInterval {
        let exponent = min(attemptCount, 20)
        let unjittered = min(Self.maximumDelay, Self.baseDelay * pow(2, Double(exponent)))
        attemptCount = min(attemptCount + 1, 21)
        let unit = min(1, max(0, randomUnit))
        let multiplier = 1 + ((unit * 2 - 1) * Self.jitterFraction)
        return min(Self.maximumDelay, max(0.001, unjittered * multiplier))
    }

    mutating func reset() {
        attemptCount = 0
    }
}

struct PresenceLocationFix: Equatable {
    let latitude: Double
    let longitude: Double
    let horizontalAccuracyMetres: Double
    let monotonicTime: TimeInterval
}

struct PresenceCandidateCluster: Equatable {
    let latest: PresenceLocationFix
    let consistentFixCount: Int
}

struct PresenceJumpEvaluation: Equatable {
    let accepted: Bool
    let nextCluster: PresenceCandidateCluster?
    let recoveredFromOutlier: Bool

    init(accepted: Bool,
         nextCluster: PresenceCandidateCluster?,
         recoveredFromOutlier: Bool = false) {
        self.accepted = accepted
        self.nextCluster = nextCluster
        self.recoveredFromOutlier = recoveredFromOutlier
    }
}

enum PresenceLocationQuality {
    static let maximumHorizontalAccuracyMetres = 1_000.0
    static let maximumReportedSpeedMetresPerSecond = 1_000.0
    static let maximumPlausibleTravelSpeedMetresPerSecond = 400.0
    static let baseJumpAllowanceMetres = 200.0
    static let accuracyAllowanceMultiplier = 3.0
    static let maximumLegacyFutureSkewMilliseconds: Int64 = 30_000

    static var allowsSimulatorTeleport: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    static func isUsableAccuracy(_ value: Double) -> Bool {
        value.isFinite && value > 0 && value <= maximumHorizontalAccuracyMetres
    }

    static func hasValidWireValues(
        latitude: Double,
        longitude: Double,
        heading: Double,
        speed: Double
    ) -> Bool {
        latitude.isFinite && abs(latitude) <= 90 &&
        longitude.isFinite && abs(longitude) <= 180 &&
        heading.isFinite && heading >= 0 && heading < 360 &&
        speed.isFinite && speed >= 0 && speed <= maximumReportedSpeedMetresPerSecond
    }

    /// Strict JSON number used by legacy v2 presence. `JSONSerialization`
    /// represents booleans as `NSNumber`, so the Core Foundation type check is
    /// required before bridging; otherwise `true` could become coordinate 1.
    static func strictWireDouble(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let result = number.doubleValue
        return result.isFinite ? result : nil
    }

    static func isPlausibleLegacyTimestamp(
        _ timestampMilliseconds: Int64,
        nowMilliseconds: Int64
    ) -> Bool {
        guard timestampMilliseconds >= 0, nowMilliseconds >= 0 else { return false }
        let (sum, overflow) = nowMilliseconds.addingReportingOverflow(
            maximumLegacyFutureSkewMilliseconds
        )
        return timestampMilliseconds <= (overflow ? Int64.max : sum)
    }

    static func acceptsJump(
        previous: PresenceLocationFix?,
        candidate: PresenceLocationFix,
        allowSimulatorTeleport: Bool
    ) -> Bool {
        guard isUsableAccuracy(candidate.horizontalAccuracyMetres) else { return false }
        guard let previous else { return true }
        guard candidate.monotonicTime >= previous.monotonicTime else { return false }
        if allowSimulatorTeleport { return true }
        let elapsed = candidate.monotonicTime - previous.monotonicTime
        let accuracyAllowance = accuracyAllowanceMultiplier *
            (previous.horizontalAccuracyMetres + candidate.horizontalAccuracyMetres)
        let allowed = baseJumpAllowanceMetres + accuracyAllowance +
            maximumPlausibleTravelSpeedMetresPerSecond * elapsed
        let old = CLLocation(latitude: previous.latitude, longitude: previous.longitude)
        let next = CLLocation(latitude: candidate.latitude, longitude: candidate.longitude)
        return next.distance(from: old) <= allowed
    }

    /// Recover from a poisoned first fix only after three mutually consistent,
    /// usable candidates. Callers keep publishing the old accepted marker while
    /// this cluster forms.
    static func evaluateJump(
        previous: PresenceLocationFix?,
        candidate: PresenceLocationFix,
        existingCluster: PresenceCandidateCluster?,
        allowSimulatorTeleport: Bool
    ) -> PresenceJumpEvaluation {
        guard isUsableAccuracy(candidate.horizontalAccuracyMetres) else {
            return PresenceJumpEvaluation(accepted: false, nextCluster: existingCluster)
        }
        if acceptsJump(
            previous: previous,
            candidate: candidate,
            allowSimulatorTeleport: allowSimulatorTeleport
        ) {
            return PresenceJumpEvaluation(accepted: true, nextCluster: nil)
        }
        guard let previous,
              candidate.monotonicTime >= previous.monotonicTime else {
            return PresenceJumpEvaluation(accepted: false, nextCluster: existingCluster)
        }
        let next: PresenceCandidateCluster
        if let existingCluster,
           acceptsJump(
            previous: existingCluster.latest,
            candidate: candidate,
            allowSimulatorTeleport: false
           ) {
            next = PresenceCandidateCluster(
                latest: candidate,
                consistentFixCount: existingCluster.consistentFixCount + 1
            )
        } else {
            next = PresenceCandidateCluster(latest: candidate, consistentFixCount: 1)
        }
        return next.consistentFixCount >= 3
            ? PresenceJumpEvaluation(
                accepted: true,
                nextCluster: nil,
                recoveredFromOutlier: true
              )
            : PresenceJumpEvaluation(accepted: false, nextCluster: next)
    }

    static func fix(from location: CLLocation, uptime: TimeInterval) -> PresenceLocationFix? {
        guard isUsableAccuracy(location.horizontalAccuracy) else { return nil }
        return PresenceLocationFix(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            horizontalAccuracyMetres: location.horizontalAccuracy,
            monotonicTime: uptime
        )
    }
}

///
/// Optional independently signed horizontal-accuracy extension. It leaves the
/// exact `pv=1` payload untouched, so old decoders ignore these extra encrypted
/// envelope fields while updated clients authenticate both byte strings.
struct PresenceAccuracyAdvertisement: Equatable {
    static let envelopeVersion: Int64 = 1
    static let versionField = "pav"
    static let payloadField = "pa"
    static let signatureField = "pasig"
    static let signatureKind = "loc-accuracy"

    enum DecodeResult {
        case absent
        case valid(PresenceAccuracyAdvertisement)
        case invalid
    }

    let horizontalAccuracyMetres: Double
    let signedPayload: Data
    let signature: String

    static func encodePayload(horizontalAccuracyMetres: Double) -> Data? {
        guard PresenceLocationQuality.isUsableAccuracy(horizontalAccuracyMetres) else { return nil }
        let bits = horizontalAccuracyMetres.bitPattern
        return Data((0..<8).map { UInt8(truncatingIfNeeded: bits >> UInt64($0 * 8)) })
    }

    static func decode(from envelope: [String: Any]) -> DecodeResult {
        let fields = [versionField, payloadField, signatureField]
        guard fields.contains(where: envelope.keys.contains) else { return .absent }
        guard fields.allSatisfy(envelope.keys.contains),
              strictInteger(envelope[versionField]) == envelopeVersion,
              let encoded = envelope[payloadField] as? String,
              !encoded.isEmpty, encoded.utf8.count <= 64,
              let bytes = Data(base64Encoded: encoded),
              bytes.base64EncodedString() == encoded,
              bytes.count == 8,
              let signature = envelope[signatureField] as? String,
              !signature.isEmpty else { return .invalid }
        var bits: UInt64 = 0
        for (offset, byte) in bytes.enumerated() {
            bits |= UInt64(byte) << UInt64(offset * 8)
        }
        let accuracy = Double(bitPattern: bits)
        guard PresenceLocationQuality.isUsableAccuracy(accuracy) else { return .invalid }
        return .valid(Self(
            horizontalAccuracyMetres: accuracy,
            signedPayload: bytes,
            signature: signature
        ))
    }

    private static func strictInteger(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double.isFinite, double.rounded(.towardZero) == double,
              double >= Double(Int64.min), double <= Double(Int64.max) else { return nil }
        return number.int64Value
    }
}
