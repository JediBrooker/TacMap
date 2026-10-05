import XCTest
import SwiftUI
import UIKit
@testable import TacticalMaps

/// threat-model-1: the lock view and privacy cover used to be drawn inside the
/// root view, so every sheet (join code, waypoints, export, share sheet) sat on
/// top of them. They now go in their own window above .alert.
@MainActor
final class SecurityCoverTests: XCTestCase {

    override func setUp() async throws {
        try await super.setUp()
        // the host app can be covered itself (a location prompt makes it
        // .inactive) and its cover would grab our test windows, so park it
        SecurityCoverWindows.shared.apply(.none, onUnlock: {})
        SecurityCoverWindows.shared.setMissionData(nil, unlock: {})
    }

    func testLockWinsOverThePrivacyCoverInEveryPhase() {
        for phase in [ScenePhase.active, .inactive, .background] {
            for privacy in [true, false] {
                XCTAssertEqual(SecurityCover.resolve(locked: true, privacyScreen: privacy, phase: phase), .lock,
                               "locked, privacy \(privacy), \(phase)")
            }
        }
    }

    func testPrivacyCoverWheneverNotActiveAndSwitchedOn() {
        XCTAssertEqual(SecurityCover.resolve(locked: false, privacyScreen: true, phase: .inactive), .privacy)
        XCTAssertEqual(SecurityCover.resolve(locked: false, privacyScreen: true, phase: .background), .privacy)
        XCTAssertEqual(SecurityCover.resolve(locked: false, privacyScreen: true, phase: .active), .none)
        for phase in [ScenePhase.active, .inactive, .background] {
            XCTAssertEqual(SecurityCover.resolve(locked: false, privacyScreen: false, phase: phase), .none)
        }
    }

    func testCoverWindowSitsAbovePresentedSheetsWithoutDismissingThem() throws {
        let (scene, app) = try makeAppWindow()
        let sheet = UIViewController()
        app.rootViewController?.present(sheet, animated: false)
        XCTAssertTrue(app.rootViewController?.presentedViewController === sheet)

        let covers = makeCovers()
        covers.apply(.lock, onUnlock: {}, scenes: [scene])

        let cover = try XCTUnwrap(covers.coverWindow(in: scene))
        XCTAssertFalse(cover.isHidden)
        XCTAssertTrue(cover.isKeyWindow, "the lock view has to own the keyboard")
        XCTAssertGreaterThan(cover.windowLevel, UIWindow.Level.alert)
        XCTAssertGreaterThan(cover.windowLevel, app.windowLevel)
        XCTAssertEqual(cover.backgroundColor, .black)
        let centre = CGPoint(x: cover.bounds.midX, y: cover.bounds.midY)
        XCTAssertNotNil(cover.hitTest(centre, with: nil), "touches have to stop at the cover")
        XCTAssertTrue(app.accessibilityElementsHidden, "VoiceOver must not reach the sheet underneath")
        XCTAssertEqual(covers.current, .lock)

        covers.apply(.none, onUnlock: {}, scenes: [scene])
        XCTAssertNil(covers.coverWindow(in: scene))
        XCTAssertTrue(cover.isHidden)
        XCTAssertFalse(app.accessibilityElementsHidden)
        XCTAssertTrue(app.isKeyWindow, "key goes back to the app on unlock")
        XCTAssertTrue(app.rootViewController?.presentedViewController === sheet, "the sheet survives the lock")
    }

    func testPrivacyToLockKeepsOneWindow() throws {
        let (scene, _) = try makeAppWindow()
        let covers = makeCovers()
        covers.apply(.privacy, onUnlock: {}, scenes: [scene])
        let first = try XCTUnwrap(covers.coverWindow(in: scene))
        XCTAssertEqual(covers.current, .privacy)
        covers.apply(.lock, onUnlock: {}, scenes: [scene])
        XCTAssertTrue(covers.coverWindow(in: scene) === first)
        XCTAssertEqual(covers.current, .lock)
        XCTAssertEqual(scene.windows.filter { $0 is SecurityCoverWindow && !$0.isHidden }.count, 1)
    }

    func testCoverDropsTheKeyboardUnderneath() throws {
        let (scene, app) = try makeAppWindow()
        let field = UITextField(frame: CGRect(x: 20, y: 120, width: 200, height: 40))
        app.rootViewController?.view.addSubview(field)
        XCTAssertTrue(field.becomeFirstResponder())

        let covers = makeCovers()
        covers.apply(.privacy, onUnlock: {}, scenes: [scene])
        XCTAssertFalse(field.isFirstResponder, "a sheet's text field must not keep typing behind the cover")
    }

    func testWindowsThatTurnUpWhileCoveredGoUnderIt() throws {
        let (scene, app) = try makeAppWindow()
        let covers = makeCovers()
        covers.apply(.lock, onUnlock: {}, scenes: [scene])
        let cover = try XCTUnwrap(covers.coverWindow(in: scene))

        // something underneath grabbing key (a late alert, a text field)
        app.makeKey()
        XCTAssertTrue(cover.isKeyWindow)

        let late = UIWindow(windowScene: scene)
        late.rootViewController = UIViewController()
        late.windowLevel = .alert
        late.isHidden = false
        addTeardownBlock { @MainActor in late.isHidden = true }
        XCTAssertTrue(late.accessibilityElementsHidden)

        covers.apply(.none, onUnlock: {}, scenes: [scene])
        XCTAssertFalse(late.accessibilityElementsHidden)
    }

    // MARK: - mission data lock (3.0.1 re-review of threat-model-1)

    func testMissionDataScreenOnlyWhenNothingElseCovers() {
        XCTAssertEqual(SecurityCover.combine(app: .none, missionDataLocked: true), .missionData)
        XCTAssertEqual(SecurityCover.combine(app: .none, missionDataLocked: false), .none)
        for app in [SecurityCover.lock, .privacy] {
            XCTAssertEqual(SecurityCover.combine(app: app, missionDataLocked: true), app)
            XCTAssertEqual(SecurityCover.combine(app: app, missionDataLocked: false), app)
        }
    }

    /// auth-bound key locked, App Lock off: "Mission data locked" was an overlay
    /// in the root view, so a waypoint or export sheet left open across the lock
    /// stayed on top of it, decrypted and exportable
    func testMissionDataLockCoversASheetLeftOpen() throws {
        let (scene, app) = try makeAppWindow()
        let sheet = UIViewController()
        app.rootViewController?.present(sheet, animated: false)
        XCTAssertTrue(app.rootViewController?.presentedViewController === sheet)

        let covers = makeCovers()
        covers.apply(.none, onUnlock: {}, scenes: [scene])
        XCTAssertNil(covers.coverWindow(in: scene))
        covers.setMissionData(MissionDataLock(detail: nil), unlock: {}, scenes: [scene])

        XCTAssertEqual(covers.current, .missionData)
        let cover = try XCTUnwrap(covers.coverWindow(in: scene), "the mission data screen needs the cover window")
        XCTAssertFalse(cover.isHidden)
        XCTAssertTrue(cover.isKeyWindow)
        XCTAssertGreaterThan(cover.windowLevel, UIWindow.Level.alert)
        let centre = CGPoint(x: cover.bounds.midX, y: cover.bounds.midY)
        XCTAssertNotNil(cover.hitTest(centre, with: nil), "touches stop at the cover, not the sheet")
        XCTAssertTrue(app.accessibilityElementsHidden)
        XCTAssertTrue(app.rootViewController?.presentedViewController === sheet, "nothing dismissed")

        // App Lock on top, then unlocked with the key still locked: back to the
        // mission data screen, same window, never a gap showing the sheet
        covers.apply(.lock, onUnlock: {}, scenes: [scene])
        XCTAssertEqual(covers.current, .lock)
        XCTAssertTrue(covers.coverWindow(in: scene) === cover)
        covers.apply(.none, onUnlock: {}, scenes: [scene])
        XCTAssertEqual(covers.current, .missionData)
        XCTAssertTrue(covers.coverWindow(in: scene) === cover)
        XCTAssertFalse(cover.isHidden)

        // a new reason line keeps the window
        covers.setMissionData(MissionDataLock(detail: "nope"), unlock: {}, scenes: [scene])
        XCTAssertTrue(covers.coverWindow(in: scene) === cover)

        // unlocked: cover gone, the sheet is still there
        covers.setMissionData(nil, unlock: {}, scenes: [scene])
        XCTAssertEqual(covers.current, .none)
        XCTAssertNil(covers.coverWindow(in: scene))
        XCTAssertTrue(cover.isHidden)
        XCTAssertFalse(app.accessibilityElementsHidden)
        XCTAssertTrue(app.isKeyWindow)
        XCTAssertTrue(app.rootViewController?.presentedViewController === sheet)
    }

    func testMissionDataLockDropsTheKeyboardUnderneath() throws {
        let (scene, app) = try makeAppWindow()
        let field = UITextField(frame: CGRect(x: 20, y: 120, width: 200, height: 40))
        app.rootViewController?.view.addSubview(field)
        XCTAssertTrue(field.becomeFirstResponder())

        let covers = makeCovers()
        covers.setMissionData(MissionDataLock(detail: nil), unlock: {}, scenes: [scene])
        XCTAssertFalse(field.isFirstResponder)
    }

    // MARK: - helpers

    private func makeAppWindow() throws -> (UIWindowScene, UIWindow) {
        let scene = try XCTUnwrap(
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first,
            "unit tests run hosted in the app, it should have a scene"
        )
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIViewController()
        window.makeKeyAndVisible()
        addTeardownBlock { @MainActor in
            window.rootViewController?.presentedViewController?.dismiss(animated: false)
            window.isHidden = true
        }
        return (scene, window)
    }

    private func makeCovers() -> SecurityCoverWindows {
        let covers = SecurityCoverWindows()
        // never leave a black window over the test host if an assert bails early
        addTeardownBlock { @MainActor in
            covers.setMissionData(nil, unlock: {})
            covers.apply(.none, onUnlock: {})
        }
        return covers
    }
}
