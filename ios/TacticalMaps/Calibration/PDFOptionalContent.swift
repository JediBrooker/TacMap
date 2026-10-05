import Foundation
import CoreGraphics
import Compression

/// CoreGraphics honours a plain optional content group that's OFF, but it
/// ignores optional content membership dicts (OCMD) completely, so anything
/// gated by one always draws. USGS US Topo hides its orthoimage that way
/// (/P /AllOn over Orthoimage + Images, Images OFF by default), so iOS drew the
/// photo while Android (pdfium) and every desktop reader hide it.
///
/// Fix without touching the file: work out which OCMDs are hidden under the
/// default config (/D), then hand CoreGraphics the original bytes plus an
/// in-memory incremental update that turns each hidden OCMD into a plain OCG
/// listed in /D /OFF. Original file stays byte for byte on disk.
///
/// The PDF is untrusted, so everything here is budgeted and any surprise
/// (encryption, odd xref, unsupported filter, budget hit) just returns nil and
/// we draw the original like before. Budgets are for the whole pass, not per
/// object, and offsets/lengths from the file get range checked before any
/// math on them (Int overflow traps, so a crafted /Length used to crash us).
enum PDFOptionalContent {

    // budgets, per pass unless it says otherwise
    static let maxXrefSections = 64
    static let maxObjects = 2_000_000
    /// highest object number we accept, keeps start + count sums nowhere near Int.max
    static let maxObjectNumber = Int(Int32.max)
    static let maxScannedObjects = 500_000
    /// every object read, the scan plus resolving refs
    static let maxObjectLoads = 1_000_000
    /// one decoded stream
    static let maxInflatedBytes = 32 * 1024 * 1024
    /// everything decoded in the pass, re-decodes after an eviction included
    static let maxTotalInflatedBytes = 64 * 1024 * 1024
    /// decoded object streams kept around for lookups, oldest goes first
    static let maxCachedObjStmBytes = 32 * 1024 * 1024
    /// bytes lexed or scanned for endstream: this much, plus 2x the file and
    /// 2x what got decoded. keeps the work linear in the input
    static let parseSlackBytes = 16 * 1024 * 1024
    static let maxDepth = 64
    static let maxContainerItems = 200_000
    /// rough heap one lexed value may take, nodeBytes a node plus its name and
    /// string bytes. a 1 byte "/" is a whole node, so the per container cap
    /// alone let a 30 KB object stream lex into a gigabyte. fits one full
    /// maxContainerItems array
    static let maxValueBytes = 16 * 1024 * 1024
    static let nodeBytes = 64
    /// one keyword or name, real ones are a few bytes (the spec says 127)
    static let maxTokenBytes = 4096
    /// one literal or hex string, decoded
    static let maxStringBytes = 1024 * 1024

    /// the document CoreGraphics should draw, or nil to draw the plain file
    static func document(url: URL) -> CGPDFDocument? {
        guard let resolved = resolved(url: url) else { return nil }
        guard let provider = SplicedBytes.provider(original: resolved.original, tail: resolved.tail) else { return nil }
        return CGPDFDocument(provider)
    }

    // MARK: cache

    private struct CacheKey: Hashable { let path: String; let size: Int; let mtime: Double }
    private final class Entry { let tail: Data?; init(_ t: Data?) { tail = t } }
    private static let lock = NSLock()
    /// one pass at a time. a second lane asking for the same file waits and gets
    /// the cached answer instead of doing it all again next to the first
    private static let computeLock = NSLock()
    private static var cache: [CacheKey: Entry] = [:]
    private static var order: [CacheKey] = []

    static func resolved(url: URL) -> (original: Data, tail: Data)? {
        // stat the real file, a symlink's own attrs are the link's (imports are never links, test samples are)
        let url = url.resolvingSymlinksInPath()
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = (attrs[.size] as? NSNumber)?.intValue,
              let mtime = (attrs[.modificationDate] as? Date)?.timeIntervalSince1970,
              let data = try? Data(contentsOf: url, options: .alwaysMapped), data.count == size else { return nil }
        let key = CacheKey(path: url.path, size: size, mtime: mtime)
        lock.lock()
        let hit = cache[key]
        lock.unlock()
        if let hit { return hit.tail.map { (data, $0) } }
        computeLock.lock()
        defer { computeLock.unlock() }
        lock.lock()
        let done = cache[key]
        lock.unlock()
        if let done { return done.tail.map { (data, $0) } }
        let tail = data.withUnsafeBytes { updateTail(for: $0) }
        lock.lock()
        if cache[key] == nil {
            cache[key] = Entry(tail)
            order.append(key)
            while order.count > 8 { cache[order.removeFirst()] = nil }
        }
        lock.unlock()
        return tail.map { (data, $0) }
    }

    // MARK: the work

    /// bytes to append so CoreGraphics hides what the default config hides,
    /// nil when there's nothing to fix (or we can't tell safely)
    static func updateTail(for bytes: UnsafeRawBufferPointer) -> Data? {
        guard let file = PDFFile(bytes: bytes) else { return nil }
        return updateTail(file: file)
    }

    /// same, on a file thats already open (tests look at its budget counters after)
    static func updateTail(file: PDFFile) -> Data? {
        guard file.trailer["Encrypt"] == nil,
              case .ref(let rootNum, _)? = file.trailer["Root"],
              case .dict(let catalog)? = file.object(rootNum) else { return nil }
        guard let ocpRaw = catalog["OCProperties"], case .dict(var ocp)? = file.resolve(ocpRaw),
              case .dict(var config)? = file.resolve(ocp["D"] ?? .null) else { return nil }

        let off = Set(file.refArray(config["OFF"]))
        let on = Set(file.refArray(config["ON"]))
        let baseOff = { if case .name(let n)? = config["BaseState"] { return n == Array("OFF".utf8) } else { return false } }()
        func ocgOn(_ n: Int) -> Bool { off.contains(n) ? false : on.contains(n) ? true : !baseOff }

        var hidden: [Int] = []
        var scanned = 0
        for n in file.objectNumbers() {
            scanned += 1
            guard scanned <= maxScannedObjects, !file.exhausted else { return nil }
            guard case .dict(let d)? = file.object(n), case .name(let t)? = d["Type"], t == Array("OCMD".utf8) else { continue }
            if visible(ocmd: d, file: file, ocgOn: ocgOn) == false { hidden.append(n) }
        }
        guard !hidden.isEmpty else { return nil }

        // hidden OCMDs become plain OCGs, listed OFF and in /OCGs
        let hiddenRefs = hidden.map { PDFValue.ref($0, file.generation($0)) }
        config["OFF"] = .array(file.inlineArray(config["OFF"]) + hiddenRefs)
        ocp["D"] = .dict(config)
        ocp["OCGs"] = .array(file.inlineArray(ocp["OCGs"]) + hiddenRefs)

        var objects: [(num: Int, gen: Int, body: [UInt8])] = []
        if case .ref(let ocpNum, let ocpGen) = ocpRaw {
            objects.append((ocpNum, ocpGen, PDFValue.dict(ocp).serialized()))
        } else {
            var cat = catalog
            cat["OCProperties"] = .dict(ocp)
            objects.append((rootNum, file.generation(rootNum), PDFValue.dict(cat).serialized()))
        }
        let ocg = Array("<< /Type /OCG /Name (TacMap hidden) >>".utf8)
        for n in hidden { objects.append((n, file.generation(n), ocg)) }
        // a budget ran out somewhere along the way, half an answer isnt good enough
        guard !file.exhausted else { return nil }
        return file.incrementalUpdate(objects)
    }

    /// ISO 32000 8.11.2.2: /VE wins over /OCGs + /P. nil = cant evaluate, leave it
    static func visible(ocmd d: PDFDict, file: PDFFile, ocgOn: (Int) -> Bool) -> Bool? {
        if case .array(let ve)? = d["VE"] {
            var held = 0
            return evaluate(ve: ve, file: file, ocgOn: ocgOn, depth: 0, held: &held)
        }
        let groups: [Int]
        switch d["OCGs"] {
        case .ref(let n, _)?:
            // a single OCG ref, or a ref to an array of them
            if case .array? = file.object(n) { groups = file.refArray(.ref(n, 0)) } else { groups = [n] }
        case .array?: groups = file.refArray(d["OCGs"])
        default: return nil
        }
        guard !groups.isEmpty else { return nil }
        let states = groups.map(ocgOn)
        var policy = "AnyOn"
        if case .name(let p)? = d["P"] { policy = String(decoding: p, as: UTF8.self) }
        switch policy {
        case "AllOn": return states.allSatisfy { $0 }
        case "AnyOn": return states.contains(true)
        case "AnyOff": return states.contains(false)
        case "AllOff": return states.allSatisfy { !$0 }
        default: return nil
        }
    }

    /// held = heap of the arrays loaded by ref that are still alive up the stack.
    /// each level keeps its array while it recurses, so 32 levels of a max size
    /// array was 32 values at once. they share one value's budget now
    private static func evaluate(ve: [PDFValue], file: PDFFile, ocgOn: (Int) -> Bool, depth: Int, held: inout Int) -> Bool? {
        guard depth < 32, case .name(let op)? = ve.first else { return nil }
        var operands: [Bool] = []
        for v in ve.dropFirst() {
            switch v {
            case .ref(let n, _):
                if case .array(let inner)? = file.object(n) {
                    let bytes = file.lastValueBytes
                    held += bytes
                    defer { held -= bytes }
                    guard held <= maxValueBytes,
                          let r = evaluate(ve: inner, file: file, ocgOn: ocgOn, depth: depth + 1, held: &held) else { return nil }
                    operands.append(r)
                } else {
                    operands.append(ocgOn(n))
                }
            case .array(let inner):
                guard let r = evaluate(ve: inner, file: file, ocgOn: ocgOn, depth: depth + 1, held: &held) else { return nil }
                operands.append(r)
            default: return nil
            }
        }
        switch String(decoding: op, as: UTF8.self) {
        case "And": return operands.isEmpty ? nil : operands.allSatisfy { $0 }
        case "Or": return operands.isEmpty ? nil : operands.contains(true)
        case "Not": return operands.count == 1 ? !operands[0] : nil
        default: return nil
        }
    }
}

// MARK: - values

struct PDFDict {
    var items: [(key: [UInt8], value: PDFValue)] = []
    subscript(_ key: String) -> PDFValue? {
        get { let k = Array(key.utf8); return items.first { $0.key == k }?.value }
        set {
            let k = Array(key.utf8)
            if let i = items.firstIndex(where: { $0.key == k }) {
                if let newValue { items[i].value = newValue } else { items.remove(at: i) }
            } else if let newValue { items.append((k, newValue)) }
        }
    }
}

indirect enum PDFValue {
    case null, bool(Bool), int(Int), real(Double), name([UInt8]), string([UInt8])
    case array([PDFValue]), dict(PDFDict), ref(Int, Int)

    func serialized() -> [UInt8] { var out: [UInt8] = []; write(into: &out); return out }

    func write(into out: inout [UInt8]) {
        switch self {
        case .null: out += Array("null".utf8)
        case .bool(let b): out += Array((b ? "true" : "false").utf8)
        case .int(let i): out += Array(String(i).utf8)
        case .real(let r):
            var s = String(format: "%.10f", r)
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
            out += Array(s.utf8)
        case .name(let n):
            out.append(0x2F)
            for b in n {
                if b > 0x20 && b < 0x7F && !PDFLexer.isDelimiter(b) && b != 0x23 { out.append(b) }
                else { out += Array(String(format: "#%02X", b).utf8) }
            }
        case .string(let s):
            out.append(0x3C)
            for b in s { out += Array(String(format: "%02X", b).utf8) }
            out.append(0x3E)
        case .array(let a):
            out.append(0x5B)
            for v in a { out.append(0x20); v.write(into: &out) }
            out += [0x20, 0x5D]
        case .dict(let d):
            out += [0x3C, 0x3C]
            for (k, v) in d.items { out.append(0x20); PDFValue.name(k).write(into: &out); out.append(0x20); v.write(into: &out) }
            out += [0x20, 0x3E, 0x3E]
        case .ref(let n, let g): out += Array("\(n) \(g) R".utf8)
        }
    }
}

// MARK: - lexer

struct PDFLexer {
    let b: UnsafeRawBufferPointer
    var pos: Int
    /// heap left for the value being lexed, every top level value() starts over
    var heapLeft = PDFOptionalContent.maxValueBytes
    /// the last value() came back nil because it was too big, not malformed
    var overBudget = false

    private mutating func spend(_ n: Int) -> Bool {
        heapLeft -= n
        if heapLeft < 0 { overBudget = true }
        return !overBudget
    }

    /// a finished name or string, nil if its over cap or the value's out of heap
    private mutating func keep(_ bytes: [UInt8], cap: Int) -> [UInt8]? {
        guard bytes.count <= cap else { overBudget = true; return nil }
        return spend(bytes.count) ? bytes : nil
    }

    static func isWhite(_ c: UInt8) -> Bool { c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 }
    static func isDelimiter(_ c: UInt8) -> Bool {
        c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E || c == 0x5B || c == 0x5D || c == 0x7B || c == 0x7D || c == 0x2F || c == 0x25
    }
    var atEnd: Bool { pos >= b.count }
    func peek(_ o: Int = 0) -> UInt8? { pos + o < b.count ? b[pos + o] : nil }

    mutating func skipWhite() {
        while let c = peek() {
            if Self.isWhite(c) { pos += 1 }
            else if c == 0x25 { while let d = peek(), d != 10, d != 13 { pos += 1 } }
            else { return }
        }
    }

    mutating func keyword() -> [UInt8] {
        skipWhite()
        let start = pos
        while let c = peek(), !Self.isWhite(c), !Self.isDelimiter(c) { pos += 1 }
        // nothing we look for is that long, dont copy a 200 MB junk run
        guard pos - start <= PDFOptionalContent.maxTokenBytes else { return [] }
        return Array(b[start..<pos])
    }

    mutating func expect(_ word: String) -> Bool { keyword() == Array(word.utf8) }

    mutating func unsignedInt() -> Int? {
        skipWhite()
        var v = 0, n = 0
        while let c = peek(), c >= 0x30, c <= 0x39 {
            guard n < 18 else { return nil }
            v = v * 10 + Int(c - 0x30); pos += 1; n += 1
        }
        return n == 0 ? nil : v
    }

    mutating func value(depth: Int = 0) -> PDFValue? {
        guard depth < PDFOptionalContent.maxDepth else { return nil }
        if depth == 0 { heapLeft = PDFOptionalContent.maxValueBytes; overBudget = false }
        skipWhite()
        guard let c = peek(), spend(PDFOptionalContent.nodeBytes) else { return nil }
        switch c {
        case 0x2F:
            pos += 1
            var out: [UInt8] = []
            while let d = peek(), !Self.isWhite(d), !Self.isDelimiter(d) {
                guard out.count <= PDFOptionalContent.maxTokenBytes else { overBudget = true; return nil }
                if d == 0x23, let h = peek(1), let l = peek(2), let x = UInt8(String(bytes: [h, l], encoding: .ascii) ?? "", radix: 16) {
                    out.append(x); pos += 3
                } else { out.append(d); pos += 1 }
            }
            return keep(out, cap: PDFOptionalContent.maxTokenBytes).map(PDFValue.name)
        case 0x28: return literalString()
        case 0x3C:
            if peek(1) == 0x3C {
                pos += 2
                var d = PDFDict()
                while true {
                    skipWhite()
                    if peek() == 0x3E && peek(1) == 0x3E { pos += 2; return .dict(d) }
                    guard d.items.count < PDFOptionalContent.maxContainerItems,
                          case .name(let k)? = value(depth: depth + 1), let v = value(depth: depth + 1) else { return nil }
                    d.items.append((k, v))
                }
            }
            pos += 1
            var hex: [UInt8] = []
            while let d = peek(), d != 0x3E {
                guard hex.count <= 2 * PDFOptionalContent.maxStringBytes else { overBudget = true; return nil }
                if !Self.isWhite(d) { hex.append(d) }
                pos += 1
            }
            guard peek() == 0x3E else { return nil }
            pos += 1
            if hex.count % 2 == 1 { hex.append(0x30) }
            var out: [UInt8] = []
            var i = 0
            while i < hex.count {
                guard let x = UInt8(String(bytes: hex[i...i + 1], encoding: .ascii) ?? "", radix: 16) else { return nil }
                out.append(x); i += 2
            }
            return keep(out, cap: PDFOptionalContent.maxStringBytes).map(PDFValue.string)
        case 0x5B:
            pos += 1
            var a: [PDFValue] = []
            while true {
                skipWhite()
                if peek() == 0x5D { pos += 1; return .array(a) }
                guard a.count < PDFOptionalContent.maxContainerItems, let v = value(depth: depth + 1) else { return nil }
                a.append(v)
            }
        default:
            if (c >= 0x30 && c <= 0x39) || c == 0x2B || c == 0x2D || c == 0x2E { return number() }
            let w = keyword()
            switch String(decoding: w, as: UTF8.self) {
            case "true": return .bool(true)
            case "false": return .bool(false)
            case "null": return .null
            default: return nil
            }
        }
    }

    private mutating func number() -> PDFValue? {
        let start = pos
        while let c = peek(), (c >= 0x30 && c <= 0x39) || c == 0x2B || c == 0x2D || c == 0x2E { pos += 1 }
        // length check before the String, a 200 MB run of digits got copied first
        guard pos - start <= 32 else { return nil }
        let s = String(decoding: b[start..<pos], as: UTF8.self)
        if let i = Int(s) {
            // "n g R"
            if i >= 0 {
                let save = pos
                if let g = unsignedInt() {
                    skipWhite()
                    if peek() == 0x52, peek(1).map({ Self.isWhite($0) || Self.isDelimiter($0) }) ?? true {
                        pos += 1
                        return .ref(i, g)
                    }
                }
                pos = save
            }
            return .int(i)
        }
        if let r = Double(s), r.isFinite { return .real(r) }
        return s == "-" || s == "." ? .int(0) : nil
    }

    private mutating func literalString() -> PDFValue? {
        pos += 1
        var out: [UInt8] = [], nest = 1
        while let c = peek() {
            guard out.count <= PDFOptionalContent.maxStringBytes else { overBudget = true; return nil }
            pos += 1
            switch c {
            case 0x5C:
                guard let e = peek() else { return nil }
                pos += 1
                switch e {
                case 0x6E: out.append(10)
                case 0x72: out.append(13)
                case 0x74: out.append(9)
                case 0x62: out.append(8)
                case 0x66: out.append(12)
                case 0x0D: if peek() == 0x0A { pos += 1 }
                case 0x0A: break
                case 0x30...0x37:
                    var v = Int(e - 0x30), n = 1
                    while n < 3, let d = peek(), d >= 0x30, d <= 0x37 { v = v * 8 + Int(d - 0x30); pos += 1; n += 1 }
                    out.append(UInt8(v & 0xFF))
                default: out.append(e)
                }
            case 0x28: nest += 1; out.append(c)
            case 0x29:
                nest -= 1
                if nest == 0 { return keep(out, cap: PDFOptionalContent.maxStringBytes).map(PDFValue.string) }
                out.append(c)
            default: out.append(c)
            }
        }
        return nil
    }
}

// MARK: - file

/// just enough of a PDF reader to find objects by number: classic xref tables,
/// xref streams, hybrid /XRefStm, /Prev chains and object streams
final class PDFFile {
    enum Loc { case offset(Int, gen: Int), packed(stream: Int, index: Int) }
    let bytes: UnsafeRawBufferPointer
    private(set) var trailer = PDFDict()
    private var xref: [Int: Loc] = [:]
    private var lastXrefOffset = 0
    private var newestIsStream = false
    private var loading: Set<Int> = []

    private struct ObjStm {
        let data: [UInt8]
        let offsets: [Int: Int]
        /// rough bytes held, the offsets table counts too
        var cost: Int { data.count + offsets.count * 16 + 64 }
    }
    private var objStmCache: [Int: ObjStm] = [:]
    /// insertion order for eviction, head moves instead of removeFirst (thats O(n))
    private var objStmOrder: [Int] = []
    private var objStmHead = 0
    private var objStmBroken: Set<Int> = []
    private(set) var cachedObjStmBytes = 0
    private(set) var peakCachedObjStmBytes = 0

    // pass budget counters, see PDFOptionalContent
    private(set) var decodedBytes = 0
    private(set) var parsedBytes = 0
    private(set) var objectLoads = 0
    /// a budget ran out (or the xref got too big), every read after this is nil
    private(set) var exhausted = false
    /// objects that came back nil for being over maxValueBytes or a token cap.
    /// that object is just skipped, the pass goes on
    private(set) var oversizedValues = 0
    /// what the last object() value takes on the heap, the lexer's estimate
    private(set) var lastValueBytes = 0

    private func charge(parsed n: Int) {
        parsedBytes += max(0, n)
        if parsedBytes > PDFOptionalContent.parseSlackBytes + 2 * (bytes.count + decodedBytes) { exhausted = true }
    }

    private func lexValue(_ lx: inout PDFLexer) -> PDFValue? {
        let v = lx.value()
        lastValueBytes = PDFOptionalContent.maxValueBytes - lx.heapLeft
        if lx.overBudget { oversizedValues += 1 }
        return v
    }

    init?(bytes: UnsafeRawBufferPointer) {
        self.bytes = bytes
        guard bytes.count > 32, let start = Self.startxref(bytes) else { return nil }
        lastXrefOffset = start
        var seen: Set<Int> = []
        var queue = [start]
        var first = true
        while let off = queue.first {
            queue.removeFirst()
            guard off >= 0, off < bytes.count, !seen.contains(off), seen.count < PDFOptionalContent.maxXrefSections else {
                if seen.count >= PDFOptionalContent.maxXrefSections { return nil }
                continue
            }
            seen.insert(off)
            guard let (t, isStream) = readSection(at: off), !exhausted else { return nil }
            if first { trailer = t; newestIsStream = isStream; first = false }
            if case .int(let x)? = t["XRefStm"] { queue.insert(x, at: 0) }
            if case .int(let p)? = t["Prev"] { queue.append(p) }
            guard xref.count <= PDFOptionalContent.maxObjects else { return nil }
        }
        guard !xref.isEmpty else { return nil }
    }

    private static func startxref(_ b: UnsafeRawBufferPointer) -> Int? {
        let key = Array("startxref".utf8)
        var i = b.count - key.count
        let stop = max(0, b.count - 2048)
        while i >= stop {
            if b[i] == key[0], Array(b[i..<i + key.count]) == key {
                var lx = PDFLexer(b: b, pos: i + key.count)
                return lx.unsignedInt()
            }
            i -= 1
        }
        return nil
    }

    /// stops taking entries once the table is full, init bails on exhausted.
    /// checked per entry, a 6 byte "0 0 n" row adds up fast in a 512 MiB file
    private func record(_ n: Int, _ loc: Loc) {
        guard xref[n] == nil else { return }
        guard xref.count < PDFOptionalContent.maxObjects else { exhausted = true; return }
        xref[n] = loc
    }
    private func recordFree(_ n: Int) { record(n, .offset(-1, gen: 0)) }

    /// first object number + count of an xref subsection, nil if it runs past maxObjectNumber
    private static func subsection(_ start: Int, _ count: Int) -> (Int, Int)? {
        guard count >= 0, count <= PDFOptionalContent.maxObjects,
              start >= 0, start <= PDFOptionalContent.maxObjectNumber - count else { return nil }
        return (start, count)
    }

    private func readSection(at off: Int) -> (PDFDict, Bool)? {
        var lx = PDFLexer(b: bytes, pos: off)
        let save = lx.pos
        if lx.keyword() == Array("xref".utf8) {
            defer { charge(parsed: lx.pos - off) }
            while true {
                let mark = lx.pos
                if lx.keyword() == Array("trailer".utf8) { break }
                lx.pos = mark
                guard let a = lx.unsignedInt(), let b = lx.unsignedInt(),
                      let (start, count) = Self.subsection(a, b) else { return nil }
                for i in 0..<count {
                    guard !exhausted, let o = lx.unsignedInt(), let g = lx.unsignedInt() else { return nil }
                    let kind = lx.keyword()
                    if kind == [0x6E] { record(start + i, .offset(o, gen: g)) } else { recordFree(start + i) }
                }
            }
            guard case .dict(let t)? = lx.value() else { return nil }
            return (t, false)
        }
        lx.pos = save
        guard let (_, _, d, stream) = indirect(at: off, wantStream: true), case .dict(let t) = d, let raw = stream,
              case .name(let ty)? = t["Type"], ty == Array("XRef".utf8),
              let data = decode(raw, dict: t),
              case .array(let wv)? = t["W"], wv.count == 3 else { return nil }
        let w = wv.map { v -> Int in if case .int(let i) = v { return i } else { return -1 } }
        guard w.allSatisfy({ (0...8).contains($0) }) else { return nil }
        let row = w.reduce(0, +)
        guard row > 0 else { return nil }
        var index: [Int] = []
        if case .array(let ix)? = t["Index"] {
            index = ix.compactMap { if case .int(let i) = $0 { return i } else { return nil } }
        } else if case .int(let size)? = t["Size"] { index = [0, size] }
        guard index.count % 2 == 0 else { return nil }
        var p = 0
        func field(_ width: Int, _ dflt: Int) -> Int {
            guard width > 0 else { return dflt }
            var v = 0
            for _ in 0..<width { v = (v << 8) | Int(data[p]); p += 1 }
            return v
        }
        for pair in stride(from: 0, to: index.count, by: 2) {
            // /Index [9223372036854775807 2] used to trap on start + i
            guard let (start, count) = Self.subsection(index[pair], index[pair + 1]) else { return nil }
            for i in 0..<count {
                guard !exhausted, p + row <= data.count else { return nil }
                let type = field(w[0], 1), a = field(w[1], 0), c = field(w[2], 0)
                switch type {
                case 1: record(start + i, .offset(a, gen: c))
                case 2: record(start + i, .packed(stream: a, index: c))
                default: recordFree(start + i)
                }
            }
        }
        return (t, true)
    }

    /// "n g obj value [stream...]" at an offset. the stream bytes only when
    /// asked: the OCMD scan never needs them, and finding them without a good
    /// /Length means scanning for endstream
    private func indirect(at off: Int, wantStream: Bool) -> (Int, Int, PDFValue, UnsafeRawBufferPointer?)? {
        guard !exhausted, off >= 0, off < bytes.count else { return nil }
        var lx = PDFLexer(b: bytes, pos: off)
        defer { charge(parsed: lx.pos - off) }
        guard let n = lx.unsignedInt(), let g = lx.unsignedInt(), lx.expect("obj"), let v = lexValue(&lx) else { return nil }
        guard wantStream else { return (n, g, v, nil) }
        let mark = lx.pos
        guard case .dict(let d) = v, lx.keyword() == Array("stream".utf8) else {
            lx.pos = mark
            return (n, g, v, nil)
        }
        if lx.peek() == 13 { lx.pos += 1 }
        if lx.peek() == 10 { lx.pos += 1 }
        let start = lx.pos
        var length: Int?
        switch d["Length"] {
        case .int(let l)?: length = l
        case .ref(let ln, _)?: if case .int(let l)? = object(ln) { length = l }
        default: break
        }
        // start <= bytes.count so this side cant overflow, start + l could (/Length 9223372036854775807)
        if let l = length, l >= 0, l <= bytes.count - start {
            return (n, g, v, UnsafeRawBufferPointer(rebasing: bytes[start..<start + l]))
        }
        // no usable /Length, look for endstream. counts against the parse budget
        let key = Array("endstream".utf8)
        var i = start
        defer { charge(parsed: i - start) }
        while i + key.count <= bytes.count {
            if bytes[i] == key[0], Array(bytes[i..<i + key.count]) == key {
                return (n, g, v, UnsafeRawBufferPointer(rebasing: bytes[start..<i]))
            }
            i += 1
        }
        return nil
    }

    /// FlateDecode (optionally with a PNG predictor) or no filter, nothing else.
    /// output counts against the pass budget, past it the whole pass gives up
    private func decode(_ raw: UnsafeRawBufferPointer, dict: PDFDict) -> [UInt8]? {
        let remaining = max(0, PDFOptionalContent.maxTotalInflatedBytes - decodedBytes)
        let limit = min(PDFOptionalContent.maxInflatedBytes, remaining)
        // too big for its own cap = just a broken stream, too big for whats left = stop
        func tooBig() -> [UInt8]? {
            if limit == remaining { exhausted = true }
            return nil
        }
        var filters: [[UInt8]] = []
        switch dict["Filter"] {
        case nil: break
        case .name(let f)?: filters = [f]
        case .array(let a)?: filters = a.compactMap { if case .name(let f) = $0 { return f } else { return nil } }
        default: return nil
        }
        if filters.isEmpty {
            guard raw.count <= limit else { return tooBig() }
            decodedBytes += raw.count
            return Array(raw)
        }
        guard filters == [Array("FlateDecode".utf8)] else { return nil }
        var used = 0
        let inflated = Self.inflate(raw, limit: limit, used: &used)
        decodedBytes += used
        guard var data = inflated else { return used >= limit ? tooBig() : nil }
        var parms = PDFDict()
        switch dict["DecodeParms"] {
        case .dict(let p)?: parms = p
        case .array(let a)?: if case .dict(let p)? = a.first { parms = p }
        default: break
        }
        if case .int(let predictor)? = parms["Predictor"], predictor > 1 {
            guard predictor >= 10 else { return nil }
            func int(_ k: String, _ d: Int) -> Int { if case .int(let v)? = parms[k] { return v } else { return d } }
            let colors = int("Colors", 1), bpc = int("BitsPerComponent", 8), columns = int("Columns", 1)
            guard (1...32).contains(colors), [1, 2, 4, 8, 16].contains(bpc), (1...1_000_000).contains(columns) else { return nil }
            guard let un = Self.unpredict(data, bpp: max(1, colors * bpc / 8), rowBytes: (colors * bpc * columns + 7) / 8) else { return nil }
            data = un
        }
        return data
    }

    /// nil past limit bytes of output. used = how much we decoded, never more than limit
    static func inflate(_ src: UnsafeRawBufferPointer, limit: Int, used: inout Int) -> [UInt8]? {
        used = 0
        // Compression's ZLIB is raw deflate, skip the 2 byte zlib header
        guard src.count > 2, let base = src.baseAddress else { return nil }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
        defer { compression_stream_destroy(stream) }
        let chunk = 64 * 1024
        let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { dst.deallocate() }
        stream.pointee.src_ptr = base.advanced(by: 2).assumingMemoryBound(to: UInt8.self)
        stream.pointee.src_size = src.count - 2
        var out: [UInt8] = []
        while true {
            stream.pointee.dst_ptr = dst
            stream.pointee.dst_size = chunk
            let status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
            let n = chunk - stream.pointee.dst_size
            // check before appending so the array never grows past the cap
            if n > limit - out.count { used = limit; return nil }
            out.append(contentsOf: UnsafeBufferPointer(start: dst, count: n))
            used = out.count
            switch status {
            case COMPRESSION_STATUS_OK: continue
            case COMPRESSION_STATUS_END: return out
            default: return out.isEmpty ? nil : out
            }
        }
    }

    static func unpredict(_ data: [UInt8], bpp: Int, rowBytes: Int) -> [UInt8]? {
        let stride = rowBytes + 1
        guard rowBytes > 0, data.count >= stride else { return nil }
        var out = [UInt8](repeating: 0, count: (data.count / stride) * rowBytes)
        var prev = [UInt8](repeating: 0, count: rowBytes)
        for r in 0..<(data.count / stride) {
            let filter = data[r * stride]
            var row = Array(data[(r * stride + 1)..<(r * stride + stride)])
            for i in 0..<rowBytes {
                let a = i >= bpp ? Int(row[i - bpp]) : 0, b = Int(prev[i]), c = i >= bpp ? Int(prev[i - bpp]) : 0
                let add: Int
                switch filter {
                case 0: add = 0
                case 1: add = a
                case 2: add = b
                case 3: add = (a + b) / 2
                case 4:
                    let p = a + b - c, pa = abs(p - a), pb = abs(p - b), pc = abs(p - c)
                    add = pa <= pb && pa <= pc ? a : pb <= pc ? b : c
                default: return nil
                }
                row[i] = UInt8((Int(row[i]) + add) & 0xFF)
            }
            out.replaceSubrange((r * rowBytes)..<((r + 1) * rowBytes), with: row)
            prev = row
        }
        return out
    }

    func objectNumbers() -> [Int] {
        xref.compactMap { k, v in if case .offset(let o, _) = v, o < 0 { return nil }; return k }.sorted()
    }

    func generation(_ n: Int) -> Int { if case .offset(_, let g)? = xref[n] { return g }; return 0 }

    func object(_ n: Int) -> PDFValue? {
        guard !exhausted, let loc = xref[n], !loading.contains(n), loading.count < 16 else { return nil }
        objectLoads += 1
        guard objectLoads <= PDFOptionalContent.maxObjectLoads else { exhausted = true; return nil }
        loading.insert(n)
        defer { loading.remove(n) }
        switch loc {
        case .offset(let o, _):
            guard o >= 0, let (num, _, v, _) = indirect(at: o, wantStream: false), num == n else { return nil }
            return v
        case .packed(let s, _):
            guard let stm = objectStream(s), let at = stm.offsets[n] else { return nil }
            var lexed = 0
            let v = stm.data.withUnsafeBytes { buf -> PDFValue? in
                var lx = PDFLexer(b: buf, pos: at)
                defer { lexed = lx.pos - at }
                return lexValue(&lx)
            }
            charge(parsed: lexed)
            return v
        }
    }

    /// decoded object stream s. cached up to maxCachedObjStmBytes in total, oldest
    /// out first, and a broken one is remembered so it isnt decoded over and over
    private func objectStream(_ s: Int) -> ObjStm? {
        if let hit = objStmCache[s] { return hit }
        guard !objStmBroken.contains(s) else { return nil }
        guard let stm = loadObjectStream(s) else {
            objStmBroken.insert(s)
            return nil
        }
        // one thats bigger than the whole cache just gets used once
        guard stm.cost <= PDFOptionalContent.maxCachedObjStmBytes else { return stm }
        while cachedObjStmBytes + stm.cost > PDFOptionalContent.maxCachedObjStmBytes, objStmHead < objStmOrder.count {
            let old = objStmOrder[objStmHead]
            objStmHead += 1
            cachedObjStmBytes -= objStmCache.removeValue(forKey: old)?.cost ?? 0
        }
        objStmCache[s] = stm
        objStmOrder.append(s)
        cachedObjStmBytes += stm.cost
        peakCachedObjStmBytes = max(peakCachedObjStmBytes, cachedObjStmBytes)
        return stm
    }

    /// an /N past what the scan would ever read is junk, keeps the offsets table small
    private func loadObjectStream(_ s: Int) -> ObjStm? {
        guard case .offset(let o, _)? = xref[s], let (_, _, d, raw) = indirect(at: o, wantStream: true),
              case .dict(let sd) = d, let raw, let data = decode(raw, dict: sd),
              case .int(let count)? = sd["N"], case .int(let first)? = sd["First"],
              count >= 0, count <= PDFOptionalContent.maxScannedObjects, first >= 0, first <= data.count else { return nil }
        var offsets: [Int: Int] = [:]
        var lexed = 0
        let ok: Bool = data.withUnsafeBytes { buf in
            var lx = PDFLexer(b: buf, pos: 0)
            defer { lexed = lx.pos }
            for _ in 0..<count {
                guard let num = lx.unsignedInt(), let rel = lx.unsignedInt() else { return false }
                // out of range just means that one object cant be read, like before
                if rel < data.count - first { offsets[num] = first + rel }
            }
            return true
        }
        charge(parsed: lexed)
        guard ok else { return nil }
        return ObjStm(data: data, offsets: offsets)
    }

    func resolve(_ v: PDFValue) -> PDFValue? {
        if case .ref(let n, _) = v { return object(n) }
        return v
    }

    /// object numbers out of an array (inline or by ref) of OCG refs
    func refArray(_ v: PDFValue?) -> [Int] {
        guard let v, case .array(let a)? = resolve(v) else { return [] }
        return a.compactMap { if case .ref(let n, _) = $0 { return n } else { return nil } }
    }

    func inlineArray(_ v: PDFValue?) -> [PDFValue] {
        guard let v, case .array(let a)? = resolve(v) else { return [] }
        return a
    }

    /// append-only update: replacement objects + an xref of the same kind as
    /// the newest one in the file, /Prev chained to it
    func incrementalUpdate(_ objects: [(num: Int, gen: Int, body: [UInt8])]) -> Data {
        var out: [UInt8] = []
        if bytes.last != 10 && bytes.last != 13 { out.append(10) }
        var entries: [(num: Int, gen: Int, offset: Int)] = []
        for o in objects.sorted(by: { $0.num < $1.num }) {
            entries.append((o.num, o.gen, bytes.count + out.count))
            out += Array("\(o.num) \(o.gen) obj\n".utf8) + o.body + Array("\nendobj\n".utf8)
        }
        // a junk /Size (say Int.max) is ignored, selfNum + 1 below used to trap on it.
        // object numbers are <= maxObjectNumber already so the + 1s are fine
        var size = 0
        if case .int(let s)? = trailer["Size"], (0...PDFOptionalContent.maxObjectNumber).contains(s) { size = s }
        size = max(size, (xref.keys.max() ?? 0) + 1, (objects.map(\.num).max() ?? 0) + 1)
        var t = PDFDict()
        for key in ["Root", "Info", "ID"] { if let v = trailer[key] { t[key] = v } }
        t["Prev"] = .int(lastXrefOffset)
        let xrefOffset = bytes.count + out.count
        if newestIsStream {
            let selfNum = size
            entries.append((selfNum, 0, xrefOffset))
            var rows: [UInt8] = []
            var index: [PDFValue] = []
            for e in entries {
                index += [.int(e.num), .int(1)]
                rows.append(1)
                for s in stride(from: 56, through: 0, by: -8) { rows.append(UInt8((e.offset >> s) & 0xFF)) }
                rows += [UInt8((e.gen >> 8) & 0xFF), UInt8(e.gen & 0xFF)]
            }
            t["Type"] = .name(Array("XRef".utf8))
            t["Size"] = .int(selfNum + 1)
            t["Index"] = .array(index)
            t["W"] = .array([.int(1), .int(8), .int(2)])
            t["Length"] = .int(rows.count)
            out += Array("\(selfNum) 0 obj\n".utf8) + PDFValue.dict(t).serialized()
            out += Array("\nstream\n".utf8) + rows + Array("\nendstream\nendobj\n".utf8)
        } else {
            out += Array("xref\n".utf8)
            for e in entries {
                out += Array("\(e.num) 1\n".utf8)
                out += Array(String(format: "%010d %05d n\r\n", e.offset, e.gen).utf8)
            }
            t["Size"] = .int(size)
            out += Array("trailer\n".utf8) + PDFValue.dict(t).serialized() + [10]
        }
        out += Array("startxref\n\(xrefOffset)\n%%EOF\n".utf8)
        return Data(out)
    }
}

// MARK: - original file + appended tail as one CGDataProvider

private final class SplicedBytes {
    let original: Data
    let tail: Data
    init(original: Data, tail: Data) { self.original = original; self.tail = tail }

    func copy(into buffer: UnsafeMutableRawPointer, at position: Int, count: Int) -> Int {
        let total = original.count + tail.count
        guard position >= 0, position < total else { return 0 }
        let n = min(count, total - position)
        var done = 0
        if position < original.count {
            let k = min(n, original.count - position)
            original.withUnsafeBytes { src in buffer.copyMemory(from: src.baseAddress!.advanced(by: position), byteCount: k) }
            done = k
        }
        if done < n {
            let tpos = position + done - original.count
            tail.withUnsafeBytes { src in
                (buffer + done).copyMemory(from: src.baseAddress!.advanced(by: tpos), byteCount: n - done)
            }
            done = n
        }
        return done
    }

    static func provider(original: Data, tail: Data) -> CGDataProvider? {
        let box = SplicedBytes(original: original, tail: tail)
        var callbacks = CGDataProviderDirectCallbacks(
            version: 0, getBytePointer: nil, releaseBytePointer: nil,
            getBytesAtPosition: { info, buffer, position, count in
                guard let info else { return 0 }
                return Unmanaged<SplicedBytes>.fromOpaque(info).takeUnretainedValue().copy(into: buffer, at: Int(position), count: count)
            },
            releaseInfo: { info in if let info { Unmanaged<SplicedBytes>.fromOpaque(info).release() } })
        return CGDataProvider(directInfo: Unmanaged.passRetained(box).toOpaque(),
                              size: off_t(original.count + tail.count), callbacks: &callbacks)
    }
}
