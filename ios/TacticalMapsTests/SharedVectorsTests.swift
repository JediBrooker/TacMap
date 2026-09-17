import XCTest
import Foundation
import CoreLocation
@testable import TacticalMaps

/// Loads the shared golden vectors in testdata/ - the SAME files the Android
/// suite reads - and checks the iOS implementations match. This is what
/// catches the two native ports of affine solve, MGRS formatting, and
/// GeoJSON geometry silenty drifting apart.
final class SharedVectorsTests: XCTestCase {

    // MARK: fixture loading (walk up from this source file to repo-root testdata/)

    private func fixture(_ name: String) throws -> Any {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) {
                let data = try Data(contentsOf: candidate)
                return try JSONSerialization.jsonObject(with: data)
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/\(name) from \(#filePath)")
        throw CocoaError(.fileNoSuchFile)
    }

    private func fixtureData(_ name: String) throws -> Data {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try Data(contentsOf: candidate)
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/\(name) from \(#filePath)")
        throw CocoaError(.fileNoSuchFile)
    }

    private func dbl(_ a: Any?) -> Double { (a as? NSNumber)?.doubleValue ?? .nan }

    // MARK: affine solve

    func testSharedAffineVectors() throws {
        let root = try fixture("affine_fits.json") as! [String: Any]
        let cases = root["cases"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let tf = c["transform"] as! [String: Any]
            let fids = (c["fiduciaries"] as! [[String: Any]]).map {
                Fiduciary(pdfX: dbl($0["pdfX"]), pdfY: dbl($0["pdfY"]), mgrs: "",
                          latitude: dbl($0["lat"]), longitude: dbl($0["lon"]))
            }
            let tol = dbl(c["coeffTolerance"])
            let rmsMax = dbl(c["rmsMaxMetres"])
            let r = try AffineFitter.fit(fids)
            XCTAssertEqual(r.transform.a, dbl(tf["a"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.b, dbl(tf["b"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.c, dbl(tf["c"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.d, dbl(tf["d"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.e, dbl(tf["e"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.f, dbl(tf["f"]), accuracy: tol, name)
            XCTAssertLessThan(r.rmsMetres, rmsMax, name)
        }
    }

    // MARK: MGRS formatting + parsing

    func testSharedMGRSVectors() throws {
        let root = try fixture("mgrs_samples.json") as! [String: Any]
        for c in (root["coordinates"] as! [[String: Any]]) {
            let lat = dbl(c["lat"]); let lon = dbl(c["lon"])
            let coord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
            XCTAssertEqual(MGRSFormatter.string(from: coord, spaced: true), c["spaced"] as! String)
            XCTAssertEqual(MGRSFormatter.string(from: coord, spaced: false), c["compact"] as! String)
            let back = try XCTUnwrap(MGRSFormatter.coordinate(from: c["spaced"] as! String))
            XCTAssertEqual(back.latitude, lat, accuracy: 1e-3)
            XCTAssertEqual(back.longitude, lon, accuracy: 1e-3)
        }
        for s in (root["invalid"] as! [String]) {
            XCTAssertNil(MGRSFormatter.coordinate(from: s), "should reject: \(s)")
        }
    }

    // MARK: GeoJSON geometry

    private func exportedGeometries(waypoints: [Waypoint], drawings: [DrawingShape]) throws -> [[String: Any]] {
        let json = try GeoJSONExporter.export(waypoints: waypoints, drawings: drawings)
        let root = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
        return (root["features"] as! [[String: Any]]).map { $0["geometry"] as! [String: Any] }
    }

    /// Numeric-aware deep comparison (so `2` and `2.0` match).
    private func geomEqual(_ a: Any?, _ b: Any?) -> Bool {
        if let na = a as? NSNumber, let nb = b as? NSNumber { return abs(na.doubleValue - nb.doubleValue) < 1e-9 }
        if let sa = a as? String, let sb = b as? String { return sa == sb }
        if let aa = a as? [Any], let bb = b as? [Any] {
            return aa.count == bb.count && zip(aa, bb).allSatisfy { geomEqual($0, $1) }
        }
        if let da = a as? [String: Any], let db = b as? [String: Any] {
            return Set(da.keys) == Set(db.keys) && da.allSatisfy { geomEqual($0.value, db[$0.key]) }
        }
        return false
    }

    func testSharedGeoJSONGeometry() throws {
        let root = try fixture("geojson_geometry.json") as! [String: Any]

        let pt = root["point"] as! [String: Any]
        let pin = pt["input"] as! [String: Any]
        let wp = Waypoint(name: "p", latitude: dbl(pin["lat"]), longitude: dbl(pin["lon"]), kind: .generic)
        XCTAssertTrue(geomEqual(try exportedGeometries(waypoints: [wp], drawings: [])[0], pt["geometry"]), "point")

        let ln = root["line"] as! [String: Any]
        let lcoords = (ln["input"] as! [[String: Any]]).map { Coordinate2D(latitude: dbl($0["lat"]), longitude: dbl($0["lon"])) }
        let line = DrawingShape(kind: .polyline, coordinates: lcoords)
        XCTAssertTrue(geomEqual(try exportedGeometries(waypoints: [], drawings: [line])[0], ln["geometry"]), "line")

        let pg = root["polygon"] as! [String: Any]
        let pcoords = (pg["input"] as! [[String: Any]]).map { Coordinate2D(latitude: dbl($0["lat"]), longitude: dbl($0["lon"])) }
        let poly = DrawingShape(kind: .polygon, coordinates: pcoords)
        XCTAssertTrue(geomEqual(try exportedGeometries(waypoints: [], drawings: [poly])[0], pg["geometry"]), "polygon")
    }

    // MARK: drawing style units + independence

    func testSharedDrawingStyleContract() throws {
        let root = try fixture("drawing_style.json") as! [String: Any]
        let widthContract = root["widthContract"] as! [String: Any]
        XCTAssertEqual(widthContract["iosStoredUnit"] as? String, "screenIndependentPoint")

        for item in root["widthRoundTrips"] as! [[String: Any]] {
            let key = item["caseKey"] as! String
            let portable = dbl(item["sourceStoredPixels"]) / dbl(item["sourceDensity"])
            XCTAssertEqual(portable, dbl(item["expectedPortableWidth"]), accuracy: 1e-9, key)
            XCTAssertEqual(portable * dbl(item["targetDensity"]),
                           dbl(item["expectedTargetStoredPixels"]), accuracy: 1e-9, key)
        }

        let polygon = root["polygonStyle"] as! [String: Any]
        let portable = polygon["portable"] as! [String: Any]
        let expectedIOS = polygon["expectedIos"] as! [String: Any]
        var baseStyle = DrawingStyle()
        baseStyle.strokeColorHex = portable["strokeRgb"] as! String
        baseStyle.strokeWidth = dbl(portable["strokeWidth"])
        baseStyle.fillColorHex = portable["fillRgb"] as? String
        baseStyle.fillOpacity = dbl(portable["fillOpacity"])
        baseStyle.dashPattern = (expectedIOS["dashPattern"] as! [Any]).map(dbl)

        XCTAssertEqual(baseStyle.strokeWidth, dbl(expectedIOS["strokeWidthPoints"]), accuracy: 1e-9,
                       "iOS stores and renders the portable width directly as points")
        XCTAssertEqual(baseStyle.strokeColorHex, expectedIOS["strokeRgb"] as? String)
        XCTAssertEqual(baseStyle.fillColorHex, expectedIOS["fillRgb"] as? String)
        XCTAssertEqual(baseStyle.fillOpacity, dbl(expectedIOS["fillOpacity"]), accuracy: 1e-9)

        let layer = DrawingLayer(name: "Style Contract", defaultColorHex: "#000000")
        func exportedProperties(_ style: DrawingStyle) throws -> [String: Any] {
            let shape = DrawingShape(
                kind: .polygon,
                coordinates: [Coordinate2D(latitude: -33.0, longitude: 151.0),
                              Coordinate2D(latitude: -33.1, longitude: 151.0),
                              Coordinate2D(latitude: -33.1, longitude: 151.1)],
                style: style,
                layerID: layer.id
            )
            let text = try GeoJSONExporter.export(drawings: [shape], layers: [layer])
            let object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as! [String: Any]
            let feature = (object["features"] as! [[String: Any]])[0]
            return feature["properties"] as! [String: Any]
        }

        let baseProperties = try exportedProperties(baseStyle)
        XCTAssertEqual(dbl(baseProperties["stroke-width"]), baseStyle.strokeWidth, accuracy: 1e-9)
        XCTAssertEqual(baseProperties["stroke"] as? String, baseStyle.strokeColorHex)
        XCTAssertEqual(baseProperties["fill"] as? String, baseStyle.fillColorHex)
        XCTAssertEqual(dbl(baseProperties["fill-opacity"]), baseStyle.fillOpacity, accuracy: 1e-9)
        XCTAssertEqual(baseProperties["tacticalmaps:stroke_style"] as? String, "dashed")

        for item in polygon["independentMutations"] as! [[String: Any]] {
            let key = item["caseKey"] as! String
            let mutation = item["mutation"] as! [String: Any]
            var changed = baseStyle
            if let stroke = mutation["strokeRgb"] as? String { changed.strokeColorHex = stroke }
            if let fill = mutation["fillRgb"] as? String { changed.fillColorHex = fill }
            if mutation["fillOpacity"] != nil { changed.fillOpacity = dbl(mutation["fillOpacity"]) }

            XCTAssertEqual(changed.strokeColorHex, item["expectedStrokeRgb"] as? String, key)
            XCTAssertEqual(changed.fillColorHex, item["expectedFillRgb"] as? String, key)
            XCTAssertEqual(changed.fillOpacity, dbl(item["expectedFillOpacity"]), accuracy: 1e-9, key)
            let properties = try exportedProperties(changed)
            XCTAssertEqual(properties["stroke"] as? String, item["expectedStrokeRgb"] as? String, key)
            XCTAssertEqual(properties["fill"] as? String, item["expectedFillRgb"] as? String, key)
            XCTAssertEqual(dbl(properties["fill-opacity"]), dbl(item["expectedFillOpacity"]),
                           accuracy: 1e-9, key)
        }
    }

    // MARK: selected-symbol edit contract

    func testSharedSymbolEditContract() throws {
        let root = try fixture("symbol_edit_contract.json") as! [String: Any]
        XCTAssertEqual(root["fieldOrder"] as? [String], SymbolEditDraft.fieldOrder)
        let normalizationContract = root["normalization"] as! [String: Any]
        let amplifierContract = normalizationContract["unitAmplifiers"] as! [String: Any]
        XCTAssertEqual(amplifierContract["lengthUnit"] as? String, "unicodeScalar")
        XCTAssertEqual((amplifierContract["higherFormationMaximum"] as? NSNumber)?.intValue,
                       UnitAmplifierText.higherFormationMaxLength)
        XCTAssertEqual((amplifierContract["uniqueIdentifierMaximum"] as? NSNumber)?.intValue,
                       UnitAmplifierText.uniqueIdentifierMaxLength)
        let boundary = amplifierContract["nonBmpBoundaryCase"] as! [String: Any]
        XCTAssertEqual(
            UnitAmplifierText.normalized(
                boundary["input"] as? String,
                maximumLength: UnitAmplifierText.higherFormationMaxLength),
            boundary["expectedHigherFormation"] as? String
        )
        let initial = root["initialWaypoint"] as! [String: Any]
        let layerID = UUID(uuidString: initial["layerId"] as! String)!
        let original = Waypoint(
            id: UUID(uuidString: initial["id"] as! String)!,
            name: initial["name"] as! String,
            notes: initial["notes"] as? String,
            latitude: dbl(initial["latitude"]),
            longitude: dbl(initial["longitude"]),
            elevation: dbl(initial["elevationMetres"]),
            kind: .controlMeasure(.axisOfMainAttack),
            rotation: dbl(initial["rotationDegrees"]),
            scaleX: dbl(initial["scaleX"]),
            scaleY: dbl(initial["scaleY"]),
            taskColor: TaskColor(rawValue: initial["taskColor"] as! String)!,
            layerID: layerID
        )
        let fallback = DrawingLayer.legacyFallbackID

        for item in root["draftCases"] as! [[String: Any]] {
            let key = item["caseKey"] as! String
            let input = item["input"] as! [String: Any]
            var draft = SymbolEditDraft(waypoint: original)
            if let value = input["name"] as? String { draft.name = value }
            if let value = input["notes"] as? String { draft.notes = value }
            if let value = input["elevationText"] as? String { draft.elevationText = value }
            if input["rotationDegrees"] != nil { draft.rotationDegrees = dbl(input["rotationDegrees"]) }
            if input["scaleX"] != nil { draft.scaleX = dbl(input["scaleX"]) }
            if input["scaleY"] != nil { draft.scaleY = dbl(input["scaleY"]) }
            if input["kindCategory"] as? String == "generic" { draft.kind = .generic }

            if let expectedError = item["expectedError"] as? String {
                XCTAssertThrowsError(
                    try draft.applying(to: original,
                                       availableLayerIDs: [layerID],
                                       fallbackLayerID: fallback), key
                ) { XCTAssertEqual($0.localizedDescription, expectedError, key) }
                XCTAssertEqual((item["expectedStoreMutations"] as? NSNumber)?.intValue, 0, key)
                continue
            }

            let updated = try draft.applying(to: original,
                                             availableLayerIDs: [layerID],
                                             fallbackLayerID: fallback)
            let expected = item["expected"] as! [String: Any]
            if let value = expected["name"] as? String {
                if let selectedDisplayName = item["selectedKindDisplayName"] as? String {
                    XCTAssertEqual(value, selectedDisplayName, key)
                    XCTAssertEqual(updated.name, draft.kind.displayName, key)
                } else {
                    XCTAssertEqual(updated.name, value, key)
                }
            }
            if expected.keys.contains("notes") {
                XCTAssertEqual(updated.notes, expected["notes"] as? String, key)
            }
            if expected.keys.contains("elevationMetres") {
                if expected["elevationMetres"] is NSNull {
                    XCTAssertNil(updated.elevation, key)
                } else {
                    XCTAssertEqual(try XCTUnwrap(updated.elevation),
                                   dbl(expected["elevationMetres"]), accuracy: 1e-9, key)
                }
            }
            if expected["rotationDegrees"] != nil {
                XCTAssertEqual(updated.rotation, dbl(expected["rotationDegrees"]), accuracy: 1e-9, key)
                XCTAssertEqual(updated.scaleX, dbl(expected["scaleX"]), accuracy: 1e-9, key)
                XCTAssertEqual(updated.scaleY, dbl(expected["scaleY"]), accuracy: 1e-9, key)
            }
            if let task = expected["taskColor"] as? String {
                XCTAssertEqual(updated.taskColor.rawValue, task, key)
            }
        }

        var missingLayer = SymbolEditDraft(waypoint: original)
        missingLayer.layerID = UUID()
        let repaired = try missingLayer.applying(to: original,
                                                availableLayerIDs: [fallback],
                                                fallbackLayerID: fallback)
        XCTAssertEqual(repaired.layerID, fallback)
        let normalization = root["normalization"] as! [String: Any]
        XCTAssertEqual(normalization["elevationValidationError"] as? String,
                       SymbolEditDraft.elevationValidationError)
        let accessibility = root["accessibility"] as! [String: Any]
        XCTAssertEqual(CGFloat((accessibility["iosMinimumTargetPoints"] as! NSNumber).doubleValue),
                       SymbolEditorAccessibility.minimumTargetPoints)
        XCTAssertEqual(accessibility["requiredLabels"] as? [String],
                       SymbolEditorAccessibility.requiredLabels)

        var stagedMove = SymbolEditDraft(waypoint: original)
        let crosshair = CLLocationCoordinate2D(latitude: -34.1, longitude: 150.9)
        stagedMove.stageMove(to: crosshair)
        XCTAssertTrue(stagedMove.hasStagedMove(from: original))
        XCTAssertEqual(original.latitude, dbl(initial["latitude"]),
                       "Move must not mutate the selected model before Save")
        let moved = try stagedMove.applying(to: original,
                                            availableLayerIDs: [layerID],
                                            fallbackLayerID: fallback)
        XCTAssertEqual(moved.latitude, crosshair.latitude)
        XCTAssertEqual(moved.longitude, crosshair.longitude)
        XCTAssertEqual(moved.name, original.name, "Move changes coordinates only in its draft step")
    }


    // MARK: external-import identity

    private struct ImportIdentityFixture: Decodable {
        struct Object: Decodable {
            let caseKey: String
            let kind: ExternalImportIdentityResolver.ObjectKind
            let id: String?
            let layerId: String
        }
        struct ExpectedObject: Decodable {
            let caseKey: String
            let kind: ExternalImportIdentityResolver.ObjectKind
            let sourceId: String?
            let resolvedId: UUID
            let layerId: String
            let resolution: ExternalImportIdentityResolver.ResolutionReason
        }
        struct Expected: Decodable {
            let resolvedObjectsInInputOrder: [ExpectedObject]
            let consumedRemintIdsInOrder: [UUID]
            let importedObjectCount: Int
            let importedWaypointCount: Int
            let importedDrawingCount: Int
            let preservedIdCount: Int
            let remintedIdCount: Int
            let finalGlobalObjectCountIncludingExisting: Int
            let resolvedWaypointIdsInOrder: [UUID]
            let resolvedDrawingIdsInOrder: [UUID]
        }
        struct Retry: Decodable {
            struct Prior: Decodable { let caseKey: String; let resolvedId: UUID }
            struct Partial: Decodable {
                let committedCaseKeys: [String]
                let uncommittedCaseKeys: [String]
                let expectedAdditionalObjectsOnRetry: Int
            }
            let priorResolutionMap: [Prior]
            let partialFirstAttempt: Partial
            let expectedResolvedIdsOnRetryInInputOrder: [UUID]
            let expectedNewRemintIdsConsumedOnRetry: [UUID]
            let expectedAdditionalObjectsAfterCompletedRetry: Int
            let expectedFinalGlobalObjectCountIncludingExisting: Int
        }
        let existingObjects: [Object]
        let injectedRemintIds: [UUID]
        let incomingObjects: [Object]
        let expected: Expected
        let retry: Retry
    }

    func testSharedExternalImportIdentityFixture() throws {
        let fixture = try JSONDecoder().decode(
            ImportIdentityFixture.self,
            from: fixtureData("import_identity.json")
        )
        let existingWaypoints = Set(fixture.existingObjects.compactMap {
            $0.kind == .waypoint ? $0.id.flatMap(UUID.init(uuidString:)) : nil
        })
        let existingDrawings = Set(fixture.existingObjects.compactMap {
            $0.kind == .drawing ? $0.id.flatMap(UUID.init(uuidString:)) : nil
        })
        var remintIterator = fixture.injectedRemintIds.makeIterator()
        var resolver = ExternalImportIdentityResolver(
            existingWaypointIDs: existingWaypoints,
            existingDrawingIDs: existingDrawings,
            idFactory: { remintIterator.next()! }
        )

        let resolved = fixture.incomingObjects.map {
            resolver.resolve(sourceID: $0.id, kind: $0.kind, caseKey: $0.caseKey)
        }
        let expected = fixture.expected.resolvedObjectsInInputOrder
        XCTAssertEqual(resolved, expected.map(\.resolvedId))
        XCTAssertEqual(resolver.resolutions.map(\.caseKey), expected.map(\.caseKey))
        XCTAssertEqual(resolver.resolutions.map(\.sourceID), expected.map(\.sourceId))
        XCTAssertEqual(resolver.resolutions.map(\.resolution), expected.map(\.resolution))
        XCTAssertEqual(fixture.incomingObjects.map(\.layerId), expected.map(\.layerId),
                       "identity resolution must never rewrite layer references")
        XCTAssertEqual(resolver.resolutions.filter { $0.resolution == .preserved }.count,
                       fixture.expected.preservedIdCount)
        XCTAssertEqual(resolver.resolutions.filter { $0.resolution != .preserved }.count,
                       fixture.expected.remintedIdCount)
        XCTAssertEqual(resolver.resolutions.filter { $0.kind == .waypoint }.map(\.resolvedID),
                       fixture.expected.resolvedWaypointIdsInOrder)
        XCTAssertEqual(resolver.resolutions.filter { $0.kind == .drawing }.map(\.resolvedID),
                       fixture.expected.resolvedDrawingIdsInOrder)
        XCTAssertEqual(Set(resolved).count, fixture.expected.importedObjectCount)
        XCTAssertEqual(existingWaypoints.count + existingDrawings.count + resolved.count,
                       fixture.expected.finalGlobalObjectCountIncludingExisting)
        XCTAssertEqual(fixture.injectedRemintIds, fixture.expected.consumedRemintIdsInOrder)

        let expectedByCaseKey = Dictionary(uniqueKeysWithValues: expected.map { ($0.caseKey, $0) })
        let prior = fixture.retry.priorResolutionMap.map { item -> ExternalImportIdentityResolver.Resolution in
            let pinned = expectedByCaseKey[item.caseKey]!
            XCTAssertEqual(item.resolvedId, pinned.resolvedId)
            return ExternalImportIdentityResolver.Resolution(
                caseKey: pinned.caseKey,
                kind: pinned.kind,
                sourceID: pinned.sourceId,
                resolvedID: pinned.resolvedId,
                resolution: pinned.resolution
            )
        }
        let committedIDs = Set(fixture.retry.partialFirstAttempt.committedCaseKeys.compactMap {
            expectedByCaseKey[$0]?.resolvedId
        })
        var unexpectedRemintCalls = 0
        var retryResolver = ExternalImportIdentityResolver(
            existingWaypointIDs: existingWaypoints.union(committedIDs),
            existingDrawingIDs: existingDrawings,
            priorResolutions: prior,
            idFactory: {
                unexpectedRemintCalls += 1
                return UUID()
            }
        )
        let retryIDs = fixture.incomingObjects.map {
            retryResolver.resolve(sourceID: $0.id, kind: $0.kind, caseKey: $0.caseKey)
        }
        XCTAssertEqual(retryIDs, fixture.retry.expectedResolvedIdsOnRetryInInputOrder)
        XCTAssertEqual(unexpectedRemintCalls, fixture.retry.expectedNewRemintIdsConsumedOnRetry.count)
        XCTAssertEqual(fixture.retry.partialFirstAttempt.uncommittedCaseKeys.count,
                       fixture.retry.partialFirstAttempt.expectedAdditionalObjectsOnRetry)
        XCTAssertEqual(fixture.retry.expectedAdditionalObjectsAfterCompletedRetry, 0)
        XCTAssertEqual(fixture.retry.expectedFinalGlobalObjectCountIncludingExisting,
                       fixture.expected.finalGlobalObjectCountIncludingExisting)
    }

    // MARK: offline search

    func testSharedOfflineSearchContract() throws {
        let root = try fixture("search_contract.json") as! [String: Any]
        let anchorJSON = root["anchor"] as! [String: Any]
        let anchor = CLLocationCoordinate2D(
            latitude: dbl(anchorJSON["latitude"]),
            longitude: dbl(anchorJSON["longitude"])
        )
        let layerNames = Dictionary(uniqueKeysWithValues:
            (root["layers"] as! [[String: Any]]).map {
                ($0["id"] as! String, $0["name"] as! String)
            }
        )

        let waypointRecords = (root["waypoints"] as! [[String: Any]]).map { item in
            let rawID = item["id"] as! String
            return OfflineSearchRecord(
                id: "waypoint:\(rawID)",
                target: .waypoint(UUID(uuidString: rawID)!),
                name: item["name"] as! String,
                notes: item["notes"] as? String,
                typeTerms: [
                    item["kindDisplayName"] as! String,
                    item["categoryDisplayName"] as! String,
                ],
                layerName: layerNames[item["layerId"] as! String]!,
                coordinate: CLLocationCoordinate2D(
                    latitude: dbl(item["latitude"]),
                    longitude: dbl(item["longitude"])
                ),
                createdOrder: (item["createdOrder"] as! NSNumber).int64Value
            )
        }
        let drawingRecords = (root["drawings"] as! [[String: Any]]).map { item in
            let rawID = item["id"] as! String
            return OfflineSearchRecord(
                id: "drawing:\(rawID)",
                target: .drawing(UUID(uuidString: rawID)!),
                name: item["name"] as! String,
                notes: item["notes"] as? String,
                typeTerms: [item["geometryDisplayName"] as! String],
                layerName: layerNames[item["layerId"] as! String]!,
                coordinate: CLLocationCoordinate2D(
                    latitude: dbl(item["centreLatitude"]),
                    longitude: dbl(item["centreLongitude"])
                ),
                createdOrder: (item["createdOrder"] as! NSNumber).int64Value
            )
        }
        let records = waypointRecords + drawingRecords

        for searchCase in root["queries"] as! [[String: Any]] {
            let caseKey = searchCase["caseKey"] as! String
            let output = OfflineSearchEngine.search(
                searchCase["query"] as! String,
                anchor: anchor,
                records: records
            )
            XCTAssertEqual(
                output.results.map(\.id),
                searchCase["expectedResultIds"] as! [String],
                caseKey
            )
            XCTAssertEqual(output.statusMessage, searchCase["expectedStatus"] as? String, caseKey)
            XCTAssertEqual((searchCase["networkRequests"] as! NSNumber).intValue, 0, caseKey)

            if let expected = searchCase["expectedCoordinate"] as? [String: Any] {
                let coordinate = try XCTUnwrap(output.results.first?.coordinate, caseKey)
                let tolerance = dbl(expected["toleranceDegrees"])
                XCTAssertEqual(coordinate.latitude, dbl(expected["latitude"]),
                               accuracy: tolerance, caseKey)
                XCTAssertEqual(coordinate.longitude, dbl(expected["longitude"]),
                               accuracy: tolerance, caseKey)
            }
        }

        let places = root["places"] as! [String: Any]
        XCTAssertEqual(places["sectionOrder"] as? String, "afterOfflineResults")
        XCTAssertEqual(places["requiresExplicitOnlineLookupsSetting"] as? Bool, true)
    }

    func testProductionPlaceCoordinatorNeverInvokesProviderForCoordinateInput() async throws {
        let anchor = CLLocationCoordinate2D(latitude: -33.8568, longitude: 151.2153)
        let root = try fixture("search_contract.json") as! [String: Any]
        let coordinateEgress = root["coordinateEgress"] as! [String: Any]
        let queries = coordinateEgress["mustStayOffline"] as! [String]
        var providerCalls = 0
        for query in queries {
            let offline = OfflineSearchEngine.search(query, anchor: anchor, records: [])
            XCTAssertTrue(offline.recognizedCoordinateInput, query)
            let outcome = try await OnlinePlaceLookup.perform(
                rawQuery: query,
                offlineOutput: offline,
                onlineLookups: true,
                provider: {
                    providerCalls += 1
                    return []
                }
            )
            XCTAssertTrue(outcome.results.isEmpty, query)
        }
        XCTAssertEqual(providerCalls, 0,
                       "the production provider closure must remain dark for coordinates")
    }

    func testProductionPlaceCoordinatorCallsProviderOnlyForEligiblePlaceQuery() async throws {
        let anchor = CLLocationCoordinate2D(latitude: -33.8568, longitude: 151.2153)
        let record = OfflineSearchRecord(
            id: "waypoint:alpha",
            target: .waypoint(UUID()),
            name: "Alpha",
            notes: nil,
            typeTerms: ["Waypoint"],
            layerName: "Operations",
            coordinate: anchor,
            createdOrder: 1
        )
        let offline = OfflineSearchEngine.search("Alpha", anchor: anchor, records: [record])
        for prose in ["Route 1885", "33 South Road", "56 North Street"] {
            XCTAssertFalse(
                OfflineSearchEngine.search(prose, anchor: anchor, records: [])
                    .recognizedCoordinateInput,
                "real prose containing coordinate-like tokens must remain eligible: \(prose)"
            )
        }
        var providerCalls = 0
        let place = SearchResult(
            id: "place:spy",
            title: "Alpha Place",
            subtitle: "Provider result",
            coordinate: anchor,
            kind: .place,
            target: .coordinate
        )
        let outcome = try await OnlinePlaceLookup.perform(
            rawQuery: "Alpha",
            offlineOutput: offline,
            onlineLookups: true,
            provider: {
                providerCalls += 1
                return [place]
            }
        )
        XCTAssertEqual(providerCalls, 1)
        XCTAssertEqual(offline.results.map(\.id), ["waypoint:alpha"])
        XCTAssertEqual(outcome.results.map(\.id), ["place:spy"],
                       "the UI appends provider output after its offline section")

        let disabled = try await OnlinePlaceLookup.perform(
            rawQuery: "Alpha",
            offlineOutput: offline,
            onlineLookups: false,
            provider: {
                providerCalls += 1
                return [place]
            }
        )
        XCTAssertEqual(providerCalls, 1)
        XCTAssertEqual(disabled.statusMessage, OnlinePlaceLookup.disabledStatus)
    }

    func testOfflineSearchMatchesAndroidBlankAndCoordinatePrecedence() {
        let anchor = CLLocationCoordinate2D(latitude: -33.8568, longitude: 151.2153)
        let older = OfflineSearchRecord(
            id: "waypoint:older", target: .waypoint(UUID()),
            name: "56HLH 34900 52288", notes: nil, typeTerms: ["Waypoint"],
            layerName: "Operations", coordinate: anchor, createdOrder: 1)
        let newer = OfflineSearchRecord(
            id: "drawing:newer", target: .drawing(UUID()),
            name: "Latest", notes: nil, typeTerms: ["Line"],
            layerName: "Operations", coordinate: anchor, createdOrder: 2)

        XCTAssertEqual(
            OfflineSearchEngine.search("", anchor: anchor, records: [older, newer]).results.map(\.id),
            ["drawing:newer", "waypoint:older"]
        )
        XCTAssertEqual(
            OfflineSearchEngine.search(
                "56HLH 34900 52288", anchor: anchor, records: [older, newer]).results.map(\.id),
            ["coordinate:mgrs"],
            "a recognized coordinate wins instead of being mixed with mission-name matches"
        )
        let unequalGridHalves = OfflineSearchEngine.search(
            "12 3456", anchor: anchor, records: [])
        XCTAssertTrue(unequalGridHalves.recognizedCoordinateInput)
        XCTAssertEqual(unequalGridHalves.statusMessage, OfflineSearchEngine.coordinateRangeMessage,
                       "like Android, unequal grid halves fall through to the strict lat/lon parser")
    }

    func testFreshInstallOnlineFeaturesDefaultOff() {
        XCTAssertFalse(OpsecSettings.defaultOnlineLookups)
        XCTAssertFalse(OpsecSettings.defaultOnlineBasemaps)
        XCTAssertFalse(OpsecSettings.defaultBackgroundUnitSyncLocation,
                       "screen-off location sharing must require explicit opt-in")
    }
}
