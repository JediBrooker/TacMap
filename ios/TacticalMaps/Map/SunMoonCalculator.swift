import Foundation

/// Sun and moon events for one local day. Nil means the event does not happen
/// inside the day (polar day or night, or a day without a moonrise).
struct SunMoonDay: Equatable {
    /// Begin morning nautical twilight: sun centre rises through -12°.
    var bmnt: Date?
    /// Begin morning civil twilight: sun centre rises through -6°.
    var bmct: Date?
    var sunrise: Date?
    var sunset: Date?
    /// End evening civil twilight.
    var eect: Date?
    /// End evening nautical twilight.
    var eent: Date?
    var moonrise: Date?
    var moonset: Date?
    /// Illuminated fraction of the lunar disc (0...1) at the middle of the day.
    var moonIllumination: Double
    var moonWaxing: Bool
}

/// Offline sun and moon times. Low-precision solar (NOAA/Meeus) and lunar
/// (Astronomical Almanac) positions keep this dependency-free and on-device:
/// within about 30 s of PyEphem for the sun and 6 min for the moon.
/// Android mirrors this in `SunMoonCalculator.kt`;
/// `testdata/sun_moon_times.json` pins both.
enum SunMoonCalculator {
    static let stepSeconds = 300.0
    static let bisections = 16
    static let sunriseAltitude = -0.833
    static let civilAltitude = -6.0
    static let nauticalAltitude = -12.0

    private static let auKilometres = 149_597_870.7
    private static let earthRadiusKilometres = 6378.14

    /// The local calendar day `dayOffset` days after the one containing `date`.
    /// DST changes make some days 23 or 25 hours long.
    static func localDay(containing date: Date, dayOffset: Int = 0,
                         calendar: Calendar = .current) -> DateInterval {
        let today = calendar.startOfDay(for: date)
        let start = calendar.date(byAdding: .day, value: dayOffset, to: today) ?? today
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
        return DateInterval(start: start, end: end)
    }

    static func day(latitude: Double, longitude: Double, interval: DateInterval) -> SunMoonDay {
        let start = interval.start.timeIntervalSince1970
        let end = interval.end.timeIntervalSince1970
        func sunAbove(_ threshold: Double) -> (Double) -> Double {
            { sunAltitude(unix: $0, latitude: latitude, longitude: longitude) - threshold }
        }
        let nautical = crossings(sunAbove(nauticalAltitude), start: start, end: end)
        let civil = crossings(sunAbove(civilAltitude), start: start, end: end)
        let sun = crossings(sunAbove(sunriseAltitude), start: start, end: end)
        let moon = crossings({ moonAltitudeAboveHorizon(unix: $0, latitude: latitude, longitude: longitude) },
                             start: start, end: end)
        let phase = illumination(unix: (start + end) / 2)
        func date(_ unix: Double?) -> Date? { unix.map(Date.init(timeIntervalSince1970:)) }
        return SunMoonDay(
            bmnt: date(nautical.rising), bmct: date(civil.rising),
            sunrise: date(sun.rising), sunset: date(sun.setting),
            eect: date(civil.setting), eent: date(nautical.setting),
            moonrise: date(moon.rising), moonset: date(moon.setting),
            moonIllumination: phase.fraction, moonWaxing: phase.waxing
        )
    }

    /// First upward and first downward zero crossing of `f` in [start, end),
    /// stepping `stepSeconds` and refining each bracket by bisection.
    static func crossings(_ f: (Double) -> Double, start: Double, end: Double) -> (rising: Double?, setting: Double?) {
        var rising: Double?
        var setting: Double?
        var t0 = start
        var f0 = f(t0)
        while t0 < end && (rising == nil || setting == nil) {
            let t1 = min(t0 + stepSeconds, end)
            let f1 = f(t1)
            if (f0 < 0) != (f1 < 0) {
                var lo = t0, hi = t1, flo = f0
                for _ in 0..<bisections {
                    let mid = (lo + hi) / 2
                    let fm = f(mid)
                    if (fm < 0) == (flo < 0) {
                        lo = mid
                        flo = fm
                    } else {
                        hi = mid
                    }
                }
                let time = (lo + hi) / 2
                if f0 < 0 {
                    if rising == nil { rising = time }
                } else if setting == nil {
                    setting = time
                }
            }
            t0 = t1
            f0 = f1
        }
        return (rising, setting)
    }

    // MARK: - Positions
    //
    // Arithmetic is split into short, explicitly typed steps (and the trig goes
    // through non-overloaded degree helpers) to keep Swift type-checking fast.

    private static func radians(_ degrees: Double) -> Double { degrees * .pi / 180 }
    private static func degrees(_ radians: Double) -> Double { radians * 180 / .pi }
    private static func sinDeg(_ degrees: Double) -> Double { sin(radians(degrees)) }
    private static func cosDeg(_ degrees: Double) -> Double { cos(radians(degrees)) }

    private static func julianDay(_ unix: Double) -> Double { unix / 86_400 + 2_440_587.5 }

    private static func centuries(_ unix: Double) -> Double { (julianDay(unix) - 2_451_545) / 36_525 }

    private static func meanObliquity(_ t: Double) -> Double {
        let inner: Double = 46.815 + t * (0.00059 - t * 0.001813)
        let arcSeconds: Double = 21.448 - t * inner
        let arcMinutes: Double = 26 + arcSeconds / 60
        return 23 + arcMinutes / 60
    }

    /// Greenwich mean sidereal time in degrees.
    private static func siderealTime(_ unix: Double) -> Double {
        let days: Double = julianDay(unix) - 2_451_545
        let t: Double = days / 36_525
        var gmst: Double = 280.46061837 + 360.98564736629 * days
        gmst += 0.000387933 * t * t
        gmst -= t * t * t / 38_710_000
        return gmst.truncatingRemainder(dividingBy: 360)
    }

    /// Apparent ecliptic longitude (degrees), distance (AU) and the node
    /// longitude used for the nutation/aberration corrections.
    private static func sunEcliptic(_ t: Double) -> (longitude: Double, distance: Double, omega: Double) {
        let l0: Double = (280.46646 + t * (36000.76983 + 0.0003032 * t)).truncatingRemainder(dividingBy: 360)
        let m: Double = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        let e: Double = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        var c: Double = sinDeg(m) * (1.914602 - t * (0.004817 + 0.000014 * t))
        c += sinDeg(2 * m) * (0.019993 - 0.000101 * t)
        c += sinDeg(3 * m) * 0.000289
        let trueLongitude: Double = l0 + c
        let anomaly: Double = m + c
        let distance: Double = 1.000001018 * (1 - e * e) / (1 + e * cosDeg(anomaly))
        let omega: Double = 125.04 - 1934.136 * t
        let apparent: Double = trueLongitude - 0.00569 - 0.00478 * sinDeg(omega)
        return (apparent, distance, omega)
    }

    /// Low-precision lunar longitude, latitude and horizontal parallax (degrees).
    private static func moonEcliptic(_ t: Double) -> (longitude: Double, latitude: Double, parallax: Double) {
        var longitude: Double = 218.32 + 481267.881 * t
        longitude += 6.29 * sinDeg(135.0 + 477198.87 * t)
        longitude -= 1.27 * sinDeg(259.3 - 413335.36 * t)
        longitude += 0.66 * sinDeg(235.7 + 890534.22 * t)
        longitude += 0.21 * sinDeg(269.9 + 954397.74 * t)
        longitude -= 0.19 * sinDeg(357.5 + 35999.05 * t)
        longitude -= 0.11 * sinDeg(186.5 + 966404.03 * t)
        var latitude: Double = 5.13 * sinDeg(93.3 + 483202.02 * t)
        latitude += 0.28 * sinDeg(228.2 + 960400.89 * t)
        latitude -= 0.28 * sinDeg(318.3 + 6003.15 * t)
        latitude -= 0.17 * sinDeg(217.6 - 407332.21 * t)
        var parallax: Double = 0.9508
        parallax += 0.0518 * cosDeg(135.0 + 477198.87 * t)
        parallax += 0.0095 * cosDeg(259.3 - 413335.36 * t)
        parallax += 0.0078 * cosDeg(235.7 + 890534.22 * t)
        parallax += 0.0028 * cosDeg(269.9 + 954397.74 * t)
        return (longitude, latitude, parallax)
    }

    private static func altitude(rightAscension ra: Double, declination dec: Double,
                                 unix: Double, latitude: Double, longitude: Double) -> Double {
        let hourAngle: Double = radians(siderealTime(unix) + longitude) - ra
        let phi: Double = radians(latitude)
        let vertical: Double = sin(phi) * sin(dec)
        let horizontal: Double = cos(phi) * cos(dec) * cos(hourAngle)
        return degrees(asin(vertical + horizontal))
    }

    static func sunAltitude(unix: Double, latitude: Double, longitude: Double) -> Double {
        let t = centuries(unix)
        let sun = sunEcliptic(t)
        let epsilon: Double = radians(meanObliquity(t) + 0.00256 * cosDeg(sun.omega))
        let lambda: Double = radians(sun.longitude)
        let dec: Double = asin(sin(epsilon) * sin(lambda))
        let ra: Double = atan2(cos(epsilon) * sin(lambda), cos(lambda))
        return altitude(rightAscension: ra, declination: dec, unix: unix, latitude: latitude, longitude: longitude)
    }

    /// Geocentric altitude of the moon's centre minus the standard rise/set
    /// altitude (parallax, semi-diameter and refraction), so zero is moonrise.
    static func moonAltitudeAboveHorizon(unix: Double, latitude: Double, longitude: Double) -> Double {
        let t = centuries(unix)
        let moon = moonEcliptic(t)
        let epsilon: Double = radians(meanObliquity(t))
        let lambda: Double = radians(moon.longitude)
        let beta: Double = radians(moon.latitude)
        let sinDec: Double = sin(beta) * cos(epsilon) + cos(beta) * sin(epsilon) * sin(lambda)
        let y: Double = sin(lambda) * cos(epsilon) - tan(beta) * sin(epsilon)
        let ra: Double = atan2(y, cos(lambda))
        let geocentric = altitude(rightAscension: ra, declination: asin(sinDec),
                                  unix: unix, latitude: latitude, longitude: longitude)
        let standardAltitude: Double = 0.7275 * moon.parallax - 0.5667
        return geocentric - standardAltitude
    }

    static func illumination(unix: Double) -> (fraction: Double, waxing: Bool) {
        let t = centuries(unix)
        let sun = sunEcliptic(t)
        let moon = moonEcliptic(t)
        let separation: Double = moon.longitude - sun.longitude
        let elongation: Double = acos(cosDeg(moon.latitude) * cosDeg(separation))
        let moonDistance: Double = earthRadiusKilometres / sinDeg(moon.parallax)
        let sunDistance: Double = sun.distance * auKilometres
        let phaseAngle: Double = atan2(sunDistance * sin(elongation),
                                       moonDistance - sunDistance * cos(elongation))
        return ((1 + cos(phaseAngle)) / 2, sinDeg(separation) > 0)
    }
}
