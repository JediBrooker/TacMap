import XCTest
@testable import TacticalMaps

/// Pins profile sampling, statistics, line of sight and dead ground to
/// testdata/elevation_profile.json (shared with Android).
final class ElevationProfileTests: XCTestCase {
    private var fixture: [String: Any]!

    override func setUpWithError() throws {
        try super.setUpWithError()
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata/elevation_profile.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                fixture = try XCTUnwrap(
                    JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any]
                )
                return
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/elevation_profile.json")
    }

    private func dbl(_ value: Any?) -> Double { (value as? NSNumber)?.doubleValue ?? .nan }

    private func path(_ value: Any?) -> [ElevationProfile.Coordinate] {
        (value as! [[String: Any]]).map {
            ElevationProfile.Coordinate(latitude: dbl($0["lat"]), longitude: dbl($0["lon"]))
        }
    }

    func testConstantsMatchSharedFixture() {
        XCTAssertEqual(ElevationProfile.earthRadiusMetres, dbl(fixture["earthRadiusMetres"]))
        XCTAssertEqual(ElevationProfile.refractionCoefficient, dbl(fixture["refractionCoefficient"]))
        XCTAssertEqual(ElevationProfile.maxSamples, fixture["maxSamples"] as? Int)
        XCTAssertEqual(ElevationProfile.targetSpacingMetres, dbl(fixture["targetSpacingMetres"]))
        XCTAssertEqual(ElevationProfileService.batchSize, fixture["requestBatchSize"] as? Int)
    }

    func testSamplingMatchesSharedFixture() {
        let metres = dbl(fixture["toleranceMetres"])
        let degrees = dbl(fixture["toleranceDegrees"])
        for testCase in fixture["sampling"] as! [[String: Any]] {
            let name = testCase["name"] as! String
            let samples = ElevationProfile.samples(along: path(testCase["path"]))
            XCTAssertEqual(samples.count, testCase["count"] as? Int, name)
            XCTAssertEqual(samples.last?.distance ?? 0, dbl(testCase["lengthMetres"]), accuracy: metres, name)
            for expected in testCase["samples"] as! [[String: Any]] {
                let sample = samples[expected["index"] as! Int]
                XCTAssertEqual(sample.distance, dbl(expected["distance"]), accuracy: metres, name)
                XCTAssertEqual(sample.latitude, dbl(expected["lat"]), accuracy: degrees, name)
                XCTAssertEqual(sample.longitude, dbl(expected["lon"]), accuracy: degrees, name)
            }
        }
    }

    func testStatsAndLineOfSightMatchSharedFixture() throws {
        let metres = dbl(fixture["toleranceMetres"])
        for testCase in fixture["analysis"] as! [[String: Any]] {
            let name = testCase["name"] as! String
            let distances = (testCase["distances"] as! [NSNumber]).map(\.doubleValue)
            let elevations = (testCase["elevations"] as! [NSNumber]).map(\.doubleValue)

            let stats = try XCTUnwrap(ElevationProfile.stats(elevations: elevations), name)
            let expectedStats = testCase["stats"] as! [String: Any]
            XCTAssertEqual(stats.minimum, dbl(expectedStats["min"]), accuracy: metres, name)
            XCTAssertEqual(stats.maximum, dbl(expectedStats["max"]), accuracy: metres, name)
            XCTAssertEqual(stats.ascent, dbl(expectedStats["ascent"]), accuracy: metres, name)
            XCTAssertEqual(stats.descent, dbl(expectedStats["descent"]), accuracy: metres, name)

            let sight = try XCTUnwrap(ElevationProfile.lineOfSight(
                distances: distances, elevations: elevations,
                observerHeight: dbl(testCase["observerHeight"]),
                targetHeight: dbl(testCase["targetHeight"])
            ), name)
            XCTAssertEqual(sight.blocked, testCase["blocked"] as? Bool, name)
            XCTAssertEqual(sight.worstIndex, testCase["worstIndex"] as? Int, name)
            if let margin = testCase["worstMargin"] as? NSNumber {
                XCTAssertEqual(sight.worstMargin ?? .nan, margin.doubleValue, accuracy: metres, name)
            } else {
                XCTAssertNil(sight.worstMargin, name)
            }
            XCTAssertEqual(sight.visible, testCase["visible"] as? [Bool], name)
            let heights = (testCase["sightLine"] as! [NSNumber]).map(\.doubleValue)
            XCTAssertEqual(sight.heights.count, heights.count, name)
            for (actual, expected) in zip(sight.heights, heights) {
                XCTAssertEqual(actual, expected, accuracy: metres, name)
            }
        }
    }

    func testChartRangeKeepsTheTerrainReadableOnLongLines() {
        XCTAssertNil(ElevationProfile.chartRange(elevations: [], sightHeights: []))
        // No sight line: just the terrain.
        XCTAssertEqual(ElevationProfile.chartRange(elevations: [120, 80, 150], sightHeights: []), 80...150)
        // A 100 m mast and a target above the terrain are always shown.
        XCTAssertEqual(ElevationProfile.chartRange(elevations: [100, 130, 110], sightHeights: [200, 160, 115]), 100...200)
        // Curvature drops a long sight line far below: clipped one terrain
        // span (70 m) under the lowest ground.
        XCTAssertEqual(ElevationProfile.chartRange(elevations: [180, 250, 206], sightHeights: [182, -40_000, 208]),
                       110...250)
        // Flat ground still leaves at least 50 m.
        XCTAssertEqual(ElevationProfile.chartRange(elevations: [10, 10, 10], sightHeights: [12, -900, 12]), -40...12)
    }

    func testRequestsGoOutInBatchesRoundedToFourDecimals() throws {
        for testCase in fixture["requests"] as! [[String: Any]] {
            let name = testCase["name"] as! String
            let samples = ElevationProfile.samples(along: path(testCase["path"]))
            let urls = ElevationProfileService.requestURLs(for: samples)
            let batches = testCase["batches"] as! [[String: Any]]
            XCTAssertEqual(urls.count, batches.count, name)
            for (url, batch) in zip(urls, batches) {
                let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems, name)
                let latitudes = try XCTUnwrap(items.first { $0.name == "latitude" }?.value, name).split(separator: ",")
                let longitudes = try XCTUnwrap(items.first { $0.name == "longitude" }?.value, name).split(separator: ",")
                XCTAssertEqual(url.host, "api.open-meteo.com", name)
                XCTAssertEqual(latitudes.count, batch["count"] as? Int, name)
                XCTAssertEqual(longitudes.count, batch["count"] as? Int, name)
                XCTAssertEqual(latitudes.first.map(String.init), batch["latitudeFirst"] as? String, name)
                XCTAssertEqual(longitudes.first.map(String.init), batch["longitudeFirst"] as? String, name)
                XCTAssertEqual(latitudes.last.map(String.init), batch["latitudeLast"] as? String, name)
                XCTAssertEqual(longitudes.last.map(String.init), batch["longitudeLast"] as? String, name)
            }
        }
    }

    func testFetchRefusesWhileOnlineLookupsAreOff() async {
        var called = false
        let service = ElevationProfileService(onlineLookups: { false }) { _ in
            called = true
            return (Data(), URLResponse())
        }
        let samples = ElevationProfile.samples(along: [
            .init(latitude: 51.5, longitude: -0.12), .init(latitude: 51.51, longitude: -0.12),
        ])
        do {
            _ = try await service.elevations(for: samples)
            XCTFail("Expected lookupsOff")
        } catch {
            XCTAssertEqual(error as? ElevationProfileService.Failure, .lookupsOff)
        }
        XCTAssertFalse(called, "No request may leave the device while online lookups are off")
    }

    func testFetchJoinsBatchesInOrder() async throws {
        let samples = ElevationProfile.samples(along: [
            .init(latitude: 51.5, longitude: -0.12), .init(latitude: 51.5405, longitude: -0.12),
        ])
        XCTAssertEqual(samples.count, 151)
        var requests = 0
        let service = ElevationProfileService(onlineLookups: { true }) { request in
            requests += 1
            let count = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
                .queryItems!.first { $0.name == "latitude" }!.value!.split(separator: ",").count
            let offset = requests == 1 ? 0 : 100
            let body = try JSONSerialization.data(withJSONObject: ["elevation": (0..<count).map { offset + $0 }])
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (body, response)
        }
        let heights = try await service.elevations(for: samples)
        XCTAssertEqual(requests, 2)
        XCTAssertEqual(heights, (0..<151).map(Double.init))
    }

    func testFetchFailsOnShortOrBadResponses() async {
        let samples = ElevationProfile.samples(along: [
            .init(latitude: 51.5, longitude: -0.12), .init(latitude: 51.501, longitude: -0.12),
        ])
        let service = ElevationProfileService(onlineLookups: { true }) { request in
            let body = try JSONSerialization.data(withJSONObject: ["elevation": [1.0]])
            return (body, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        do {
            _ = try await service.elevations(for: samples)
            XCTFail("A response with too few heights must fail")
        } catch {
            XCTAssertEqual(error as? ElevationProfileService.Failure, .network)
        }
    }
}
