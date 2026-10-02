import XCTest
import CoreGraphics
@testable import TacticalMaps

/// CoreGraphics ignores OCMDs, PDFOptionalContent makes hidden ones hide.
/// Fixtures: testdata/optional_content (scripts/gen_optional_content_pdfs.py),
/// each case as a classic xref table and as object + xref streams.
final class PDFOptionalContentTests: XCTestCase {

    static func dir() throws -> URL {
        var d = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let c = d.appendingPathComponent("testdata/optional_content")
            if FileManager.default.fileExists(atPath: c.path) { return c }
            d = d.deletingLastPathComponent()
        }
        throw XCTSkip("testdata/optional_content not found")
    }

    /// red square at page centre drawn?
    func centreIsRed(_ page: CGPDFPage) throws -> Bool {
        let ctx = try XCTUnwrap(CGContext(data: nil, width: 100, height: 100, bitsPerComponent: 8, bytesPerRow: 400,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: 100, height: 100))
        ctx.drawPDFPage(page)
        let p = try XCTUnwrap(ctx.data).assumingMemoryBound(to: UInt8.self)
        return p[50 * 400 + 50 * 4 + 1] < 128
    }

    func testRendererHonoursEveryOptionalContentCase() throws {
        let d = try Self.dir()
        let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: d.appendingPathComponent("manifest.json"))) as? [String: Any]
        let cases = try XCTUnwrap(manifest?["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 30)
        XCTAssertTrue(cases.contains { $0["layout"] as? String == "object-stream" })
        for c in cases {
            let name = try XCTUnwrap(c["file"] as? String)
            let want = try XCTUnwrap(c["visible"] as? Bool)
            let (_, page) = try PDFTileRenderer.openPage(url: d.appendingPathComponent(name), pageIndex: 0)
            XCTAssertEqual(try centreIsRed(page), want, name)
        }
    }

    /// the bug, pinned: plain CoreGraphics draws a hidden OCMD
    func testPlainCoreGraphicsStillIgnoresOCMDs() throws {
        let url = try Self.dir().appendingPathComponent("ocmd_allon_on_off.pdf")
        let page = try XCTUnwrap(CGPDFDocument(url as CFURL)?.page(at: 1))
        XCTAssertTrue(try centreIsRed(page), "if this fails CoreGraphics learned OCMDs and the workaround can go")
    }

    func testNothingToFixMeansThePlainFile() throws {
        let d = try Self.dir()
        XCTAssertNil(PDFOptionalContent.resolved(url: d.appendingPathComponent("ocg_off.pdf")))
        XCTAssertNil(PDFOptionalContent.resolved(url: d.appendingPathComponent("ocmd_anyon_on_off.pdf")))
        XCTAssertNotNil(PDFOptionalContent.resolved(url: d.appendingPathComponent("ocmd_single_off_objstm.pdf")))
    }

    func testFileOnDiskIsUntouched() throws {
        let url = try Self.dir().appendingPathComponent("ocmd_ve_or_not_objstm.pdf")
        let before = try Data(contentsOf: url)
        _ = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
        XCTAssertEqual(try Data(contentsOf: url), before)
    }

    /// untrusted input: garbage, truncation, self referencing xref, deep nesting
    func testHostileInputBailsOutQuietly() throws {
        let d = try Self.dir()
        let good = try Data(contentsOf: d.appendingPathComponent("ocmd_allon_on_off.pdf"))
        var inputs: [Data] = [Data(), Data("%PDF-1.7\nstartxref\n999999\n%%EOF".utf8),
                              good.prefix(good.count / 2), Data(repeating: 0x5B, count: 200_000)]
        inputs.append(Data("%PDF-1.7\n1 0 obj\n".utf8) + Data(repeating: 0x5B, count: 100_000)
                      + Data("\nendobj\nxref\n0 2\n0000000000 65535 f \n0000000009 00000 n \ntrailer\n<< /Size 2 /Root 1 0 R /Prev 0 >>\nstartxref\n0\n%%EOF\n".utf8))
        for (i, data) in inputs.enumerated() {
            let tail = data.withUnsafeBytes { PDFOptionalContent.updateTail(for: $0) }
            XCTAssertNil(tail, "input \(i)")
        }
    }
}
