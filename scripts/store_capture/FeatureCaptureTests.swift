import XCTest

/// Capture production UI from the pinned 3.0 snapshot. Debug hooks only import
/// public fixtures and position the camera; they do not fake app content.
final class FeatureCaptureTests: XCTestCase {
    private var app = XCUIApplication()
    private var output: URL { URL(fileURLWithPath: ProcessInfo.processInfo.environment["STORE_OUTPUT"]!) }
    private var source: String { ProcessInfo.processInfo.environment["STORE_SOURCE"]! }
    override func setUpWithError() throws { continueAfterFailure = false }
    private func launch(pdf: String? = nil, point: String? = nil, camera: String = "37.786,-122.438,14", online: Bool = false) {
        app.terminate();app.launchArguments = ["-opsec.relayURL","ws://127.0.0.1:8794"]; app.launchEnvironment = ["TACMAP_DEBUG_GRID":"1", "TACMAP_DEBUG_CAMERA":camera]
        app.launchEnvironment[online ? "TACMAP_UITEST_ONLINE" : "TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        if let pdf { app.launchEnvironment["TACMAP_DEBUG_IMPORT_PDF"] = pdf }
        if let point { app.launchEnvironment["TACMAP_DEBUG_CALIBRATION_POINT"] = point }
        app.launch(); sleep(4)
    }
    private func button(_ text: String) -> XCUIElement {
        let matches=app.buttons.matching(NSPredicate(format:"identifier == %@ OR label CONTAINS[c] %@",text,text));for i in 0..<matches.count { let b=matches.element(boundBy:i);if b.isHittable { return b } };return matches.firstMatch
    }
    private func tap(_ text: String, timeout: Double = 12) {
        let b=button(text);XCTAssertTrue(b.waitForExistence(timeout:timeout),"Missing \(text): \(app.debugDescription)")
        XCTAssertTrue(b.isHittable,"Hidden \(text)");b.tap();sleep(1)
    }
    private func menu(_ text: String) { tap("Menu");tap(text) }
    private func close() {
        for s in ["Done","Close","Cancel"] { let b=button(s);if b.exists && b.isHittable { b.tap();sleep(1);return } }
        if app.otherElements["PopoverDismissRegion"].exists { app.coordinate(withNormalizedOffset:CGVector(dx:0.04,dy:0.55)).tap() } else { app.swipeDown() };sleep(1)
    }
    private func scrollUp() { if app.collectionViews.firstMatch.exists { app.collectionViews.firstMatch.swipeUp() } else if app.scrollViews.firstMatch.exists { app.scrollViews.firstMatch.swipeUp() } else { app.swipeUp() };sleep(1) }
    func test06Settings() {
        launch();menu("Import / Export");scrollUp();capture("exports-formats");close()
        menu("Settings, Privacy & OPSEC");capture("privacy");scrollUp();capture("display-language");close()
        menu("App Lock");capture("app-lock");close();menu("About & Credits");capture("about-tour");close()
    }
    func test07Graphics() {
        launch(pdf:source+"/testdata/store/GeoPDF_San_Francisco.pdf",camera:"37.774,-122.445,14",online:true)
        menu("Drawings");tap("Line Tool");for (x,y) in [(0.25,0.45),(0.5,0.5),(0.7,0.64)] { app.coordinate(withNormalizedOffset:CGVector(dx:x,dy:y)).tap() };capture("drawing-route");tap("Finish");sleep(1)
        app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).press(forDuration:1.6);tap("Range Rings Here");capture("range-rings-options");tap("Add Rings");capture("range-rings")
        if button("Close drawing editor").exists { tap("Close drawing editor") }
        menu("Layers and Labels");capture("basemap-options");close()
    }
    func test08Team() {
        launch(camera:"37.774,-122.445,14",online:true);menu("Unit Sync")
        if button("Leave room").exists { tap("Leave room");sleep(1) }
        let code=try! String(contentsOfFile:source+"/../store-captures/room.private",encoding:.utf8)
        let name=app.textFields.matching(NSPredicate(format:"placeholderValue BEGINSWITH[c] %@","Room name")).firstMatch
        if name.exists { name.tap();name.typeText("FIELD TEAM") }
        let field=app.textFields.matching(NSPredicate(format:"placeholderValue ==[c] %@","Unit join code")).firstMatch;XCTAssertTrue(field.exists);field.tap();field.typeText(code+"\n")
        tap("Join / create room");if button("Enable & Join").waitForExistence(timeout:3) { tap("Enable & Join") };sleep(8);let cs=app.textFields.matching(NSPredicate(format:"placeholderValue ==[c] %@","Callsign")).firstMatch;if cs.exists { cs.tap();let old=(cs.value as? String) ?? "";cs.typeText(String(repeating:XCUIKeyboardKey.delete.rawValue,count:old==cs.placeholderValue ? 0 : old.count)+(app.frame.width > 600 ? "BRAVO" : "ALPHA")+"\n") };close()
        menu("TacMap Chat");sleep(20)
        let message=app.textFields.matching(NSPredicate(format:"placeholderValue ==[c] %@","Message")).firstMatch;XCTAssertTrue(message.waitForExistence(timeout:15));message.tap();message.typeText("Rally point set. Route checks complete.")
        let review=button("Review room send");let ready=XCTNSPredicateExpectation(predicate:NSPredicate(format:"isEnabled == true"),object:review);XCTAssertEqual(XCTWaiter().wait(for:[ready],timeout:80),.completed);review.tap();tap("Send to");app.swipeDown();sleep(3);capture("chat-live");close()
        menu("Unit Sync");scrollUp();scrollUp();capture("sync-live");close();capture("team-map");sleep(80)
    }
    func test09More() {
        launch(pdf:source+"/testdata/store/GeoPDF_San_Francisco.pdf",camera:"37.774,-122.445,14",online:true)
        app.coordinate(withNormalizedOffset:CGVector(dx:0.22,dy:0.65)).press(forDuration:1.6);tap("Range Rings Here");capture("range-rings-options");tap("Add Rings");capture("range-rings");if button("Close drawing editor").exists { tap("Close drawing editor") }
        menu("Layers and Labels");capture("basemap-options");let bake=button("Generate offline tiles");for _ in 0..<6 { if bake.exists && bake.isHittable { break };scrollUp() };tap("Generate offline tiles");sleep(6);capture("bake-options");close();close()
        menu("TacMap Chat");sleep(5);if app.keyboards.count > 0 { let hide=app.buttons.matching(NSPredicate(format:"label CONTAINS[c] %@","Hide keyboard")).firstMatch;if hide.exists { hide.tap() } };capture("chat-clean");close();sleep(110)
    }
    func test10Details() {
        launch(pdf:source+"/testdata/store/San_Francisco_Map_Book.pdf",online:true);sleep(4);capture("page-chooser");close()
        launch(pdf:source+"/testdata/store/GeoPDF_San_Francisco.pdf",camera:"37.774,-122.445,14",online:true);menu("Measure")
        for (x,y) in [(0.3,0.45),(0.65,0.50),(0.6,0.68)] { app.coordinate(withNormalizedOffset:CGVector(dx:x,dy:y)).tap();sleep(1) };capture("measure-area");tap("Done")
        menu("Start Track Recording");capture("recording");menu("Stop Track Recording");close()
        menu("TacMap Chat");capture("chat-clean");close();sleep(130)
    }
    func test11Tools() {
        launch(pdf:source+"/testdata/store/GeoPDF_San_Francisco.pdf",camera:"37.774,-122.445,14",online:true)
        menu("Start Track Recording");sleep(12);capture("recording-gps");menu("Stop Track Recording");close()
        menu("Drawings");tap("Area");for (x,y) in [(0.25,0.45),(0.5,0.4),(0.7,0.64)] { app.coordinate(withNormalizedOffset:CGVector(dx:x,dy:y)).tap() };capture("drawing-area");tap("Finish");if button("Close drawing editor").exists { tap("Close drawing editor") }
        menu("Drawings");tap("Free Draw");app.coordinate(withNormalizedOffset:CGVector(dx:0.24,dy:0.64)).press(forDuration:0.1,thenDragTo:app.coordinate(withNormalizedOffset:CGVector(dx:0.6,dy:0.52)));capture("drawing-free");if button("Done").exists { close() }
    }
    func test12PageChooser() {
        launch(pdf:source+"/testdata/store/San_Francisco_Map_Book.pdf",online:true);menu("Layers and Labels")
        let m=app.buttons["maps.menu.San_Francisco_Map_Book"];for _ in 0..<8 { if m.exists && m.isHittable { break };scrollUp() };XCTAssertTrue(m.exists && m.isHittable,app.debugDescription);m.tap();tap("Choose page");sleep(3);capture("page-chooser");close()
    }
    private func capture(_ name:String,hold:UInt32=6) {
        let start=Date().timeIntervalSince1970;sleep(1);let shot=XCUIScreen.main.screenshot()
        try! FileManager.default.createDirectory(at:output,withIntermediateDirectories:true)
        try! shot.pngRepresentation.write(to:output.appendingPathComponent(name+".png"))
        let att=XCTAttachment(screenshot:shot);att.name=name;att.lifetime = .keepAlways;add(att);sleep(hold)
        let data=try! JSONSerialization.data(withJSONObject:["name":name,"start":start,"end":Date().timeIntervalSince1970],options:[.sortedKeys])
        let path=output.appendingPathComponent("marks.jsonl")
        if !FileManager.default.fileExists(atPath:path.path) { FileManager.default.createFile(atPath:path.path,contents:nil) }
        let h=try! FileHandle(forWritingTo:path);try! h.seekToEnd();try! h.write(contentsOf:data);try! h.write(contentsOf:Data([10]));try! h.close()
    }
    func test01Offline() {
        launch(pdf:source+"/testdata/store/GeoPDF_San_Francisco.pdf")
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout:30));capture("pdf-hero")
        app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.55)).press(forDuration:0.1,thenDragTo:app.coordinate(withNormalizedOffset:CGVector(dx:0.6,dy:0.58)))
        capture("pdf-pan",hold:3)
        menu("Import / Export");XCTAssertTrue(button("PDF Map").exists);XCTAssertTrue(button("Offline Tiles").exists);capture("import-export");close()
        launch(pdf:source+"/testdata/store/San_Francisco_Field_Map.pdf",point:"676.742,487.370",camera:"37.786,-122.438,15")
        let nad=app.buttons["calibration.datum.NAD83"];sleep(3);for _ in 0..<7 { if nad.exists && nad.isHittable { break };scrollUp();sleep(1) };XCTAssertTrue(nad.exists && nad.isHittable,app.debugDescription)
        capture("calibration-datum");nad.tap();sleep(1)
        let fids=[("676.742,487.370","10SEG 48000 80000"),("1149.142,484.526","10SEG 52000 80000"),("1151.979,956.929","10SEG 52000 84000"),("679.583,959.768","10SEG 48000 84000")]
        for (index,fid) in fids.enumerated() {
            if index > 0 { launch(point:fid.0,camera:"37.786,-122.438,15") };tap("calibration.add")
            let field=app.textFields["calibration.entry.field"];XCTAssertTrue(field.waitForExistence(timeout:10));field.tap();field.typeText(fid.1)
            if button("Hide keyboard").exists { tap("Hide keyboard") } else { field.typeText("\n") }
            if index==1 { capture("calibration-entry") };tap("calibration.entry.save")
        }
        launch(camera:"37.786,-122.438,13")
        XCTAssertTrue(app.buttons["calibration.finish"].waitForExistence(timeout:15));XCTAssertTrue(app.buttons["calibration.finish"].isEnabled)
        capture("calibration-fit");tap("calibration.points");capture("calibration-points");close();tap("calibration.finish")
        if button("Finish anyway").waitForExistence(timeout:2) { tap("Finish anyway") };capture("calibrated-map")
        menu("Layers and Labels")
        for _ in 0..<6 { let b=app.buttons["maps.row.San_Francisco_Field_Map"];if b.exists && b.isHittable { break };scrollUp() }
        capture("map-library");close()
    }
    func test02Navigation() {
        launch(pdf:source+"/testdata/store/GeoPDF_San_Francisco.pdf",camera:"37.788,-122.445,15",online:true);capture("navigation-hud")
        let compass=button("North Up, map heading");if compass.exists { compass.tap();sleep(1);capture("heading-up",hold:4);compass.tap() }
        menu("Search");let field=app.textFields.firstMatch;XCTAssertTrue(field.waitForExistence(timeout:8));field.tap();field.typeText("10SEG 48000 84000");app.swipeDown();capture("search");close()
        menu("Measure");for (x,y) in [(0.3,0.45),(0.65,0.50)] { app.coordinate(withNormalizedOffset:CGVector(dx:x,dy:y)).tap();sleep(1) };capture("measure-line",hold:4)
        tap("measure.profile");XCTAssertTrue(app.otherElements["profile.chart"].waitForExistence(timeout:40),app.debugDescription);capture("line-of-sight");close()
        app.coordinate(withNormalizedOffset:CGVector(dx:0.6,dy:0.68)).tap();sleep(1);capture("measure-area",hold:4);tap("Done")
        menu("Start Track Recording");capture("recording");menu("Stop Track Recording");if button("Done").exists { close() }
        menu("Weather");sleep(5);capture("weather");scrollUp();capture("sun-moon");close()
        tap("map.nightMode");capture("night-mode");tap("map.nightMode")
    }
    func test04Field() {
        launch(pdf:source+"/testdata/store/GeoPDF_San_Francisco.pdf",camera:"37.774,-122.445,14",online:true)
        menu("Start Track Recording");capture("recording");menu("Stop Track Recording");close()
        menu("Weather");sleep(5);capture("weather");app.scrollViews.firstMatch.swipeUp();capture("sun-moon");close()
        tap("map.nightMode");capture("night-mode");tap("map.nightMode")
    }
    func test03Mission() {
        launch(camera:"37.788,-122.445,15");menu("Symbology");let add=app.buttons["Add at Crosshair"]
        for _ in 0..<20 { if add.exists && add.isHittable { break };scrollUp() };tap("Add at Crosshair");capture("symbol-builder");tap("Save");tap("Done")
        let editor=app.buttons["Close symbol editor"];if editor.exists { editor.tap() }
        menu("Drawings");capture("drawings");close();menu("Layers and Labels");capture("layers-labels");close()
        menu("Unit Sync");capture("unit-sync");close();menu("TacMap Chat");capture("chat");close()
        menu("Import / Export");capture("exports");scrollUp();capture("exports-formats",hold:4);close()
        menu("Settings, Privacy & OPSEC");capture("privacy");scrollUp();capture("display-language");close()
        menu("App Lock");capture("app-lock");close();menu("About & Credits");capture("about-tour");close()
    }
}
