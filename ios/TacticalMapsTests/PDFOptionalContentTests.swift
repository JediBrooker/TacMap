import XCTest
import CoreGraphics
import Compression
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

    // MARK: 3.0.1 review (threat-model-2, integration-commits-1/2)

    /// writes objects in order, then a classic table or an unfiltered xref stream
    struct HostilePDF {
        var data = Data("%PDF-1.7\n".utf8)
        var offsets: [Int: Int] = [:]

        mutating func obj(_ n: Int, _ body: String) { obj(n, Data(body.utf8)) }
        mutating func obj(_ n: Int, _ body: Data) {
            offsets[n] = data.count
            data += Data("\(n) 0 obj\n".utf8) + body + Data("\nendobj\n".utf8)
        }
        mutating func stream(_ n: Int, _ dict: String, _ payload: Data, length: String? = nil) {
            obj(n, Data("<< \(dict) /Length \(length ?? String(payload.count)) >>\nstream\n".utf8) + payload + Data("\nendstream".utf8))
        }

        func classic(_ trailer: String) -> Data {
            var d = data
            let at = d.count
            let size = (offsets.keys.max() ?? 0) + 1
            d += Data("xref\n0 \(size)\n".utf8)
            for n in 0..<size {
                d += Data((offsets[n].map { String(format: "%010d 00000 n \n", $0) } ?? "0000000000 65535 f \n").utf8)
            }
            d += Data("trailer\n<< /Size \(size) \(trailer) >>\nstartxref\n\(at)\n%%EOF\n".utf8)
            return d
        }

        /// xref stream as object selfNum, W [1 4 2]. packed[n] = (stream, index)
        func xrefStream(_ selfNum: Int, packed: [Int: (Int, Int)] = [:], dict: String) -> Data {
            var d = data
            let at = d.count
            let size = max(selfNum, offsets.keys.max() ?? 0, packed.keys.max() ?? 0) + 1
            var rows = Data()
            func row(_ t: UInt8, _ a: Int, _ b: Int) {
                rows.append(t)
                for s in stride(from: 24, through: 0, by: -8) { rows.append(UInt8((a >> s) & 0xFF)) }
                rows += [UInt8((b >> 8) & 0xFF), UInt8(b & 0xFF)]
            }
            for n in 0..<size {
                if n == selfNum { row(1, at, 0) }
                else if let o = offsets[n] { row(1, o, 0) }
                else if let p = packed[n] { row(2, p.0, p.1) }
                else { row(0, 0, 0) }
            }
            d += Data("\(selfNum) 0 obj\n<< /Type /XRef /Size \(size) /W [1 4 2] \(dict) /Length \(rows.count) >>\nstream\n".utf8)
            d += rows + Data("\nendstream\nendobj\nstartxref\n\(at)\n%%EOF\n".utf8)
            return d
        }
    }

    static let catalog = "<< /Type /Catalog /OCProperties << /OCGs [] /D << >> >> >>"

    /// zlib stream the way PDFs carry it, 2 byte header + raw deflate
    static func zlib(_ src: Data) throws -> Data {
        var out = Data(count: src.count / 100 + 64 * 1024)
        let n = out.withUnsafeMutableBytes { dst in
            src.withUnsafeBytes { s in
                compression_encode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, dst.count,
                                          s.bindMemory(to: UInt8.self).baseAddress!, s.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n > 0 else { throw XCTSkip("compression_encode_buffer failed") }
        return Data([0x78, 0x9C]) + out.prefix(n)
    }

    /// numbers past Int range used to trap (swift overflow = crash, and on a
    /// restore that was a crash loop). every one of these has to come back nil
    func testCraftedNumbersFallBackInsteadOfTrapping() throws {
        var inputs: [(String, Data)] = []
        func startxrefOnly(_ body: String) -> Data {
            let head = "%PDF-1.7\n"
            return Data((head + body + "startxref\n\(head.utf8.count)\n%%EOF\n").utf8)
        }
        // the reviewers' two: xref stream /Length Int.max, and /Index starting at Int.max
        inputs.append(("xref stream /Length", startxrefOnly(
            "1 0 obj\n<< /Type /XRef /Size 2 /W [1 2 1] /Length 9223372036854775807 >>\nstream\nAAAA\nendstream\nendobj\n")))
        inputs.append(("xref stream /Index", startxrefOnly(
            "1 0 obj\n<< /Type /XRef /Size 2 /W [1 1 1] /Index [9223372036854775807 2] /Length 6 >>\nstream\n"
            + "\u{01}\u{09}\u{00}\u{01}\u{09}\u{00}\nendstream\nendobj\n")))
        inputs.append(("xref stream second /Index pair", startxrefOnly(
            "1 0 obj\n<< /Type /XRef /Size 2 /W [1 1 1] /Index [1 4 9223372036854775807 2] /Length 18 >>\nstream\n"
            + String(repeating: "\u{01}\u{09}\u{00}", count: 6) + "\nendstream\nendobj\n")))
        // a scanned object with a huge /Length (classic table, so it gets past init)
        var a = HostilePDF()
        a.obj(1, Self.catalog)
        a.stream(2, "/Type /X", Data("AAAA".utf8), length: "9223372036854775807")
        inputs.append(("scanned stream /Length", a.classic("/Root 1 0 R")))
        // object stream with a huge /Length, reached through a packed entry
        var b = HostilePDF()
        b.obj(1, Self.catalog)
        b.stream(2, "/Type /ObjStm /N 1 /First 4", Data("3 0 << >>".utf8), length: "9223372036854775807")
        inputs.append(("object stream /Length", b.xrefStream(4, packed: [3: (2, 0)], dict: "/Root 1 0 R")))
        // classic subsection starting past maxObjectNumber, and an object stream offset way past its data
        let head = "%PDF-1.7\n1 0 obj\n\(Self.catalog)\nendobj\n"
        inputs.append(("classic subsection start", Data((head + "xref\n999999999999999999 1\n0000000009 00000 n \n"
            + "trailer\n<< /Size 9223372036854775807 /Root 1 0 R >>\nstartxref\n\(head.utf8.count)\n%%EOF\n").utf8)))
        var c = HostilePDF()
        c.obj(1, Self.catalog)
        c.stream(2, "/Type /ObjStm /N 1 /First 4", Data("3 999999999999999999 << >>".utf8))
        inputs.append(("object stream /First", c.xrefStream(4, packed: [3: (2, 0)], dict: "/Root 1 0 R")))
        for (name, data) in inputs {
            let tail = data.withUnsafeBytes { PDFOptionalContent.updateTail(for: $0) }
            XCTAssertNil(tail, name)
        }
    }

    /// a junk trailer /Size on a file that does need fixing: selfNum + 1 used to
    /// trap. its ignored now and the hidden OCMD still hides
    func testHugeTrailerSizeStillHidesTheOCMD() throws {
        let d = try Self.dir()
        let name = "ocmd_allon_on_off_objstm.pdf"
        let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: d.appendingPathComponent("manifest.json"))) as? [String: Any]
        let want = try XCTUnwrap((manifest?["cases"] as? [[String: Any]])?.first { $0["file"] as? String == name }?["visible"] as? Bool)
        XCTAssertFalse(want)
        // newest xref is the stream at the end, startxref points before /Size so
        // growing it shifts nothing that matters
        var bytes = try Data(contentsOf: d.appendingPathComponent(name))
        let old = Data("/Size 12".utf8)
        let range = try XCTUnwrap(bytes.range(of: old, options: .backwards))
        bytes.replaceSubrange(range, with: Data("/Size 9223372036854775807".utf8))
        let tail = bytes.withUnsafeBytes { PDFOptionalContent.updateTail(for: $0) }
        XCTAssertNotNil(tail)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("ocmd-size-\(UUID()).pdf")
        try bytes.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
        XCTAssertEqual(try centreIsRed(page), want)
    }

    /// many object streams that each inflate to ~30 MiB of spaces: every one used
    /// to stay cached (1.35 GB RSS from a 1.27 MB file). Now the pass decodes at
    /// most maxTotalInflatedBytes, keeps at most maxCachedObjStmBytes, then gives up
    func testObjectStreamBombStaysInsideTheMemoryBudget() throws {
        // the numbers THREAT_MODEL s3 quotes
        XCTAssertEqual(PDFOptionalContent.maxInflatedBytes, 32 << 20)
        XCTAssertEqual(PDFOptionalContent.maxTotalInflatedBytes, 64 << 20)
        XCTAssertEqual(PDFOptionalContent.maxCachedObjStmBytes, 32 << 20)
        XCTAssertEqual(PDFOptionalContent.maxObjects, 2_000_000)
        XCTAssertEqual(PDFOptionalContent.maxObjectLoads, 1_000_000)
        XCTAssertEqual(PDFOptionalContent.parseSlackBytes, 16 << 20)
        let body = Data("1000 0 << /K 1 >>".utf8) + Data(repeating: 0x20, count: 30 * 1024 * 1024)
        XCTAssertLessThanOrEqual(body.count, PDFOptionalContent.maxInflatedBytes, "each one alone is fine")
        let packed = try Self.zlib(body)
        let streams = 6
        var p = HostilePDF()
        p.obj(1, Self.catalog)
        var entries: [Int: (Int, Int)] = [:]
        for i in 0..<streams {
            p.stream(2 + i, "/Type /ObjStm /N 1 /First 7 /Filter /FlateDecode", packed)
            entries[2 + streams + i] = (2 + i, 0)
        }
        let data = p.xrefStream(2 + 2 * streams, packed: entries, dict: "/Root 1 0 R")
        XCTAssertLessThan(data.count, 1024 * 1024)
        try data.withUnsafeBytes { buf in
            let file = try XCTUnwrap(PDFFile(bytes: buf))
            XCTAssertNil(PDFOptionalContent.updateTail(file: file))
            XCTAssertTrue(file.exhausted)
            XCTAssertLessThanOrEqual(file.decodedBytes, PDFOptionalContent.maxTotalInflatedBytes)
            XCTAssertLessThanOrEqual(file.peakCachedObjStmBytes, PDFOptionalContent.maxCachedObjStmBytes)
            XCTAssertGreaterThan(file.peakCachedObjStmBytes, 0, "it did get as far as caching one")
        }
    }

    /// stream objects with no /Length and no endstream: each one used to scan to
    /// EOF (quadratic, 1 MB took 1.4 s). the scan never needs stream bytes now
    func testStreamsWithoutLengthStayLinear() throws {
        var p = HostilePDF()
        p.obj(1, Self.catalog)
        let junk = String(repeating: "x", count: 200)
        for n in 2..<3000 { p.obj(n, "<< /Type /X >>\nstream\n" + junk) }
        let data = p.classic("/Root 1 0 R")
        try data.withUnsafeBytes { buf in
            let file = try XCTUnwrap(PDFFile(bytes: buf))
            XCTAssertNil(PDFOptionalContent.updateTail(file: file), "nothing to fix")
            XCTAssertFalse(file.exhausted)
            XCTAssertLessThan(file.parsedBytes, data.count)
        }
    }

    /// thousands of xref entries all pointing at one big object: each read lexes
    /// all of it again. the parse budget keeps the total linear in the file
    func testAliasedXrefEntriesHitTheParseBudget() throws {
        var p = HostilePDF()
        p.obj(1, Self.catalog)
        p.obj(2, "[(" + String(repeating: "a", count: 40_000) + ")]")
        for n in 3..<3003 { p.offsets[n] = p.offsets[2] }
        let data = p.classic("/Root 1 0 R")
        try data.withUnsafeBytes { buf in
            let file = try XCTUnwrap(PDFFile(bytes: buf))
            XCTAssertNil(PDFOptionalContent.updateTail(file: file))
            XCTAssertTrue(file.exhausted)
            let allowance = PDFOptionalContent.parseSlackBytes + 2 * (data.count + file.decodedBytes)
            // at most one read past the line
            XCTAssertLessThanOrEqual(file.parsedBytes, allowance + data.count)
        }
    }

    /// /VE refs that fan out two ways per level, 31 levels deep (2^32 reads before)
    func testVisibilityExpressionFanOutIsCountBounded() throws {
        var p = HostilePDF()
        p.obj(1, "<< /Type /Catalog /OCProperties << /OCGs [40 0 R] /D << >> >> >>")
        p.obj(2, "<< /Type /OCMD /VE [/And 3 0 R 3 0 R] >>")
        for k in 3..<33 { p.obj(k, "[/And \(k + 1) 0 R \(k + 1) 0 R]") }
        p.obj(33, "[/Or 40 0 R 40 0 R]")
        p.obj(40, "<< /Type /OCG /Name (x) >>")
        let data = p.classic("/Root 1 0 R")
        try data.withUnsafeBytes { buf in
            let file = try XCTUnwrap(PDFFile(bytes: buf))
            XCTAssertNil(PDFOptionalContent.updateTail(file: file))
            XCTAssertTrue(file.exhausted)
            XCTAssertLessThanOrEqual(file.objectLoads, PDFOptionalContent.maxObjectLoads + 1)
        }
    }

    // MARK: 3.0.1 re-review: lexing itself was never budgeted

    static func lex(_ s: Data) -> (PDFValue?, PDFLexer) {
        s.withUnsafeBytes { buf in
            var lx = PDFLexer(b: buf, pos: 0)
            let v = lx.value()
            return (v, lx)
        }
    }

    /// [[/ /...] [/ /...]], n nodes in all counting the three arrays
    static func slashTree(nodes: Int) -> Data {
        let a = (nodes - 3) / 2
        return Data("[[".utf8) + Data(repeating: 0x2F, count: a) + Data("] [".utf8)
            + Data(repeating: 0x2F, count: nodes - 3 - a) + Data("]]".utf8)
    }

    /// every "/" was a boxed value of ~40 bytes and only each container was
    /// capped, so one value could be tens of millions of nodes
    func testOneLexedValueHasAHeapBudget() {
        // the numbers THREAT_MODEL s3 quotes
        XCTAssertEqual(PDFOptionalContent.maxValueBytes, 16 << 20)
        XCTAssertEqual(PDFOptionalContent.nodeBytes, 64)
        XCTAssertEqual(PDFOptionalContent.maxTokenBytes, 4096)
        XCTAssertEqual(PDFOptionalContent.maxStringBytes, 1 << 20)
        let fits = PDFOptionalContent.maxValueBytes / PDFOptionalContent.nodeBytes
        XCTAssertEqual(fits, 262_144)
        XCTAssertGreaterThan(fits, PDFOptionalContent.maxContainerItems, "one full container still lexes")

        let (ok, lx) = Self.lex(Self.slashTree(nodes: fits))
        XCTAssertNotNil(ok)
        XCTAssertEqual(lx.heapLeft, 0)
        XCTAssertFalse(lx.overBudget)
        let (over, lx2) = Self.lex(Self.slashTree(nodes: fits + 1))
        XCTAssertTrue(over == nil, "one node over")
        XCTAssertTrue(lx2.overBudget)
    }

    /// the reviewer's file: one object stream, ~30 KB compressed, holding
    /// [[/ x199000] x150]. that lexed into 1.28 GB with no budget firing
    func testSlashTreeInAnObjectStreamIsSkipped() throws {
        let inner = Data("[".utf8) + Data(repeating: 0x2F, count: 199_000) + Data("]".utf8)
        var tree = Data("[".utf8)
        for _ in 0..<150 { tree += inner }
        tree += Data("]".utf8)
        XCTAssertLessThanOrEqual(tree.count + 8, PDFOptionalContent.maxInflatedBytes, "inside the decode budgets")
        let packed = try Self.zlib(Data("3 0 ".utf8) + tree)
        var p = HostilePDF()
        p.obj(1, Self.catalog)
        p.stream(2, "/Type /ObjStm /N 1 /First 4 /Filter /FlateDecode", packed)
        let data = p.xrefStream(4, packed: [3: (2, 0)], dict: "/Root 1 0 R")
        XCTAssertLessThan(data.count, 64 * 1024)
        try data.withUnsafeBytes { buf in
            let file = try XCTUnwrap(PDFFile(bytes: buf))
            // not XCTAssertNil, a failure would print all 30 million nodes
            XCTAssertTrue(file.object(3) == nil)
            XCTAssertEqual(file.oversizedValues, 1)
            XCTAssertLessThanOrEqual(file.lastValueBytes, PDFOptionalContent.maxValueBytes + PDFOptionalContent.nodeBytes)
            XCTAssertLessThan(file.parsedBytes, 1 << 20, "stopped at the budget, not at the end of 30 MB")
            XCTAssertNil(PDFOptionalContent.updateTail(file: file))
            XCTAssertFalse(file.exhausted, "one oversized object is skipped, the pass goes on")
        }
    }

    /// same tree in the catalog: it used to stay alive for the whole pass (and get
    /// lexed again by the scan, and copied into the tail). now the catalog just
    /// doesnt lex, so the plain file is drawn
    func testGiantCatalogFallsBackToThePlainFile() throws {
        func file(x: String) throws -> Data {
            let body = "1 0 << /Type /Catalog /X \(x) /OCProperties << /OCGs [5 0 R] /D << /OFF [5 0 R] >> >> >>"
            var p = HostilePDF()
            p.stream(2, "/Type /ObjStm /N 1 /First 4 /Filter /FlateDecode", try Self.zlib(Data(body.utf8)))
            p.obj(4, "<< /Type /OCMD /OCGs 5 0 R /P /AllOn >>")
            p.obj(5, "<< /Type /OCG /Name (x) >>")
            return p.xrefStream(6, packed: [1: (2, 0)], dict: "/Root 1 0 R")
        }
        // control: small /X, the hidden OCMD gets its tail
        let small = try file(x: "[/a /b]")
        XCTAssertNotNil(small.withUnsafeBytes { PDFOptionalContent.updateTail(for: $0) })

        let big = try file(x: String(decoding: Self.slashTree(nodes: 300_000), as: UTF8.self))
        try big.withUnsafeBytes { buf in
            let f = try XCTUnwrap(PDFFile(bytes: buf))
            XCTAssertNil(PDFOptionalContent.updateTail(file: f))
            XCTAssertEqual(f.oversizedValues, 1)
            XCTAssertEqual(f.objectLoads, 1, "gave up at the catalog")
        }
    }

    /// keyword, name and string tokens were copied whole before anything looked
    /// at them (a 200 MiB token peaked at 0.4 to 0.6 GB)
    func testTokensAreCappedBeforeTheyAreCopied() {
        let cap = PDFOptionalContent.maxTokenBytes
        func keyword(_ n: Int) -> Int {
            Data(repeating: 0x61, count: n).withUnsafeBytes { buf in
                var lx = PDFLexer(b: buf, pos: 0)
                let w = lx.keyword()
                XCTAssertEqual(lx.pos, n, "still steps over the whole run")
                return w.count
            }
        }
        XCTAssertEqual(keyword(cap), cap)
        XCTAssertEqual(keyword(cap + 1), 0)

        func name(_ n: Int) -> PDFLexer { Self.lex(Data("/".utf8) + Data(repeating: 0x61, count: n)).1 }
        XCTAssertFalse(name(cap).overBudget)
        XCTAssertTrue(Self.lex(Data("/".utf8) + Data(repeating: 0x61, count: cap + 1)).0 == nil)
        XCTAssertTrue(name(cap + 1).overBudget)

        let s = PDFOptionalContent.maxStringBytes
        func literal(_ n: Int) -> (PDFValue?, PDFLexer) { Self.lex(Data("(".utf8) + Data(repeating: 0x61, count: n) + Data(")".utf8)) }
        if case .string(let got)? = literal(s).0 { XCTAssertEqual(got.count, s) } else { XCTFail("1 MiB literal") }
        XCTAssertTrue(literal(s + 1).0 == nil)
        XCTAssertTrue(literal(s + 1).1.overBudget)

        func hex(_ digits: Int) -> (PDFValue?, PDFLexer) { Self.lex(Data("<".utf8) + Data(repeating: 0x41, count: digits) + Data(">".utf8)) }
        if case .string(let got)? = hex(2 * s).0 { XCTAssertEqual(got.count, s) } else { XCTFail("1 MiB hex") }
        XCTAssertTrue(hex(2 * s + 2).0 == nil)
        XCTAssertTrue(hex(2 * s + 2).1.overBudget)
    }

    /// an oversized object costs only itself: the OCMD next to it still hides
    func testOversizedObjectIsSkippedAndThePassGoesOn() throws {
        var p = HostilePDF()
        p.obj(1, "<< /Type /Catalog /OCProperties << /OCGs [5 0 R] /D << /OFF [5 0 R] >> >> >>")
        p.obj(3, "(" + String(repeating: "a", count: PDFOptionalContent.maxStringBytes + 10) + ")")
        p.obj(4, "<< /Type /OCMD /OCGs 5 0 R /P /AllOn >>")
        p.obj(5, "<< /Type /OCG /Name (x) >>")
        let data = p.classic("/Root 1 0 R")
        try data.withUnsafeBytes { buf in
            let file = try XCTUnwrap(PDFFile(bytes: buf))
            XCTAssertNotNil(PDFOptionalContent.updateTail(file: file))
            XCTAssertEqual(file.oversizedValues, 1)
        }
    }

    /// evaluate keeps every level's array alive while it recurses, so a /VE
    /// array that refs itself held 32 copies of a max size value (262 MB from an
    /// 863 byte file). arrays loaded for one /VE share one value's budget now
    func testVisibilityExpressionArraysShareOneHeapBudget() throws {
        var p = HostilePDF()
        p.obj(1, "<< /Type /Catalog /OCProperties << /OCGs [5 0 R] /D << /OFF [5 0 R] >> >> >>")
        // ~6.5 MB a copy by the lexer's estimate, so the third one is over
        p.obj(3, "[/Or 3 0 R " + String(repeating: "[/Or] ", count: 50_000) + "]")
        p.obj(4, "<< /Type /OCMD /VE [/And 3 0 R] >>")
        p.obj(5, "<< /Type /OCG /Name (x) >>")
        let data = p.classic("/Root 1 0 R")
        try data.withUnsafeBytes { buf in
            let file = try XCTUnwrap(PDFFile(bytes: buf))
            guard case .dict(let ocmd)? = file.object(4) else { return XCTFail("OCMD") }
            _ = file.object(3)
            let one = file.lastValueBytes
            XCTAssertGreaterThan(one * 3, PDFOptionalContent.maxValueBytes)
            XCTAssertLessThanOrEqual(one * 2, PDFOptionalContent.maxValueBytes)
            let before = file.objectLoads
            XCTAssertNil(PDFOptionalContent.visible(ocmd: ocmd, file: file, ocgOn: { _ in true }))
            XCTAssertEqual(file.objectLoads - before, 3, "was 32, one a level")
        }
    }
}
