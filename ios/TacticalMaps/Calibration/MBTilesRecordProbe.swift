import Foundation

/// s15.2 recordProbe (3.0.3 U2, SEC-M1-ANDROID-OLDSQLITE-OOM). sqlite loads all
/// of a TEXT value before length() or substr(CAST(... AS BLOB)) gets to look at
/// it, and only 3.45+ checks SQLITE_LIMIT_LENGTH before that load. iOS 16-18
/// ship older than that, and the schema load at the first statement reads every
/// schema row before any check of ours. so before sqlite gets near those rows we
/// read the file's own b-tree pages: page headers, cell pointers and each leaf
/// cell's first varint, which is that row's whole record size. nothing sqlite
/// loads from the row can be bigger. never a value, an overflow page or a record
/// header, except the schema's own rows (statistics below), which the walk has
/// capped by then. straight port of probe_header() / probe_walk() /
/// probe_schema() in scripts/gen_calibration_fixtures.py, pinned by
/// import_limits.json recordProbe.cases. pure, no sqlite in here
enum MBTilesRecordProbe {
    static let maximumSchemaRows = 1_000
    static let maximumSchemaBytes: UInt64 = 1_048_576
    static let maximumMetadataRows = MBTilesStore.maximumMetadataRows
    // twice the value cap, a row with one value right at the cap still fits
    static let maximumMetadataRecordBytes = 2 * UInt64(MBTilesStore.maximumValueBytes)
    static let maximumDepth = 20
    static let maximumPages = 4_096

    /// the fixture's reasons, first problem in walk order
    enum Refusal: String {
        case header, structure, pageType, rows, recordBytes, totalBytes, statistics
    }

    /// the file couldn't be read (gone, short read, locked). callers refuse on it
    struct ReadFailure: Error {}

    private static let magic = Array("SQLite format 3\u{0}".utf8)
    // what ANALYZE names its tables (sqlite_stat1, and 2/3/4 on builds that had them)
    private static let statisticsMark = Array("sqlite_stat".utf8)
    private static let createTable = Array("CREATE TABLE ".utf8)
    // body sizes of serial types 0...9, 10 and 11 are reserved
    private static let serialSizes = [0, 1, 2, 3, 4, 6, 8, 8, 0, 0]

    /// sqlite_master from page 1, before sqlite opens the file. nil = fine.
    /// PROBE-RT-1: the schema load at the first statement also reads every row
    /// of the ANALYZE tables whole (sqlite_stat1, sqlite_stat4) and nothing
    /// bounded those. sqlite3_limit kept each value to the value cap here but
    /// not the rows or the total, and the verdict has to match android's. so
    /// each schema row's record gets read, and every row that mentions
    /// sqlite_stat has to be a plain table whose b-tree stays under the schema's
    /// own caps, all of them walked as one
    static func schema(_ url: URL) throws -> Refusal? {
        try reading(url) { handle in
            var cells: [Cell] = []
            if let refusal = try walk(handle, roots: [1], maximumRows: maximumSchemaRows,
                                      maximumRecord: maximumSchemaBytes, maximumTotal: maximumSchemaBytes,
                                      cells: &cells) {
                return refusal
            }
            return try statistics(handle, cells: cells)
        }
    }

    /// one table the metadata relation reads, from its sqlite_master rootpage. nil = fine
    static func metadataTable(_ url: URL, root: Int64) throws -> Refusal? {
        try reading(url) { handle in
            var cells: [Cell] = []
            return try walk(handle, roots: [root], maximumRows: maximumMetadataRows,
                            maximumRecord: maximumMetadataRecordBytes, maximumTotal: nil, cells: &cells)
        }
    }

    /// opens url for body. any read problem in there comes out as ReadFailure
    private static func reading(_ url: URL, _ body: (FileHandle) throws -> Refusal?) throws -> Refusal? {
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            throw ReadFailure()
        }
        defer { try? handle.close() }
        do {
            return try body(handle)
        } catch {
            throw ReadFailure()
        }
    }

    private struct Geometry {
        let size: Int
        let usable: Int
        let pageCount: UInt64
    }

    /// page size, usable size and page count off the 100 byte header, nil = header
    private static func geometry(_ handle: FileHandle) throws -> Geometry? {
        let length = try handle.seekToEnd()
        guard length >= 100 else { return nil }
        let head = try read(handle, at: 0, count: 100)
        guard head.starts(with: magic) else { return nil }
        let declared = be16(head, 16)
        let size = declared == 1 ? 65_536 : declared
        guard size >= 512, size <= 65_536, size & (size - 1) == 0 else { return nil }
        let usable = size - Int(head[20])
        guard usable >= 480 else { return nil }
        // schema text gets read as UTF-8 below. sqlite goes by the low two bits,
        // 0 is UTF-8 too. admission refuses any other encoding anyway, just later
        guard be32(head, 56) & 3 <= 1 else { return nil }
        return Geometry(size: size, usable: usable, pageCount: length / UInt64(size))
    }

    /// a leaf cell the walk let through, for the statistics step
    private struct Cell {
        let page: UInt64
        let offset: Int
    }

    /// a page still to visit. flat, an interior page can list ~32k children.
    /// signed so a silly root is just another page that isn't in the file
    private struct Pending {
        let page: Int64
        let depth: Int
    }

    /// the reason for the first problem in walk order, nil if there's none.
    /// several roots walk as one: same pages seen, rows and total, each one at
    /// depth 0 in order. cells gets every leaf cell that passed, in walk order
    private static func walk(_ handle: FileHandle, roots: [Int64], maximumRows: Int, maximumRecord: UInt64,
                             maximumTotal: UInt64?, cells: inout [Cell]) throws -> Refusal? {
        guard let g = try geometry(handle) else { return .header }
        let size = g.size
        let usable = g.usable
        // backwards so the first root comes off first
        var stack = roots.reversed().map { Pending(page: $0, depth: 0) }
        var seen = Set<UInt64>()
        var rows = 0
        var total: UInt64 = 0
        while let next = stack.popLast() {
            // a root goes through the same check as any page, done before the
            // UInt64 so a negative one never gets near it
            guard next.page >= 1, UInt64(next.page) <= g.pageCount else { return .structure }
            let p = UInt64(next.page)
            guard !seen.contains(p), next.depth < maximumDepth, seen.count < maximumPages else { return .structure }
            seen.insert(p)
            let page = try read(handle, at: (p - 1) * UInt64(size), count: size)
            // page 1 starts with the 100 byte file header, cell offsets still count from the page start
            let h = p == 1 ? 100 : 0
            let n = be16(page, h + 3)
            switch page[h] {
            case 0x05:
                let first = h + 12 + 2 * n
                guard first <= usable else { return .structure }
                // every cell checked before going down, same as the reference, so the first problem matches
                var children: [Int64] = []
                children.reserveCapacity(n + 1)
                for i in 0..<n {
                    let o = be16(page, h + 12 + 2 * i)
                    guard o >= first, o + 4 <= usable else { return .structure }
                    children.append(Int64(be32(page, o)))
                }
                children.append(Int64(be32(page, h + 8)))
                // backwards so the left most child comes off first (pre-order)
                for child in children.reversed() {
                    stack.append(Pending(page: child, depth: next.depth + 1))
                }
            case 0x0D:
                let first = h + 8 + 2 * n
                guard first <= usable else { return .structure }
                for i in 0..<n {
                    let o = be16(page, h + 8 + 2 * i)
                    guard o >= first, o < usable, let record = varint(page, at: o, end: usable)?.value else {
                        return .structure
                    }
                    rows += 1
                    if rows > maximumRows { return .rows }
                    if record > maximumRecord { return .recordBytes }
                    if let maximumTotal {
                        // a 9 byte varint goes up to 2^64 - 1, a wrapped sum is over any cap
                        let (sum, overflow) = total.addingReportingOverflow(record)
                        if overflow || sum > maximumTotal { return .totalBytes }
                        total = sum
                    }
                    cells.append(Cell(page: p, offset: o))
                }
            default:
                return .pageType
            }
        }
        return nil
    }

    /// PROBE-RT-1, the step after the schema walk: every schema leaf cell in
    /// walk order read whole. structure if one can't be, statistics if one that
    /// mentions sqlite_stat isn't sqlite's own kind of statistics table, then
    /// their b-trees walked as one under the schema caps
    private static func statistics(_ handle: FileHandle, cells: [Cell]) throws -> Refusal? {
        guard let g = try geometry(handle) else { return .header }
        var page: [UInt8] = []
        var loaded: UInt64 = 0
        var roots: [Int64] = []
        for cell in cells {
            if cell.page != loaded {
                page = try read(handle, at: (cell.page - 1) * UInt64(g.size), count: g.size)
                loaded = cell.page
            }
            guard let record = try record(handle, g, page, at: cell.offset) else { return .structure }
            if mentionsStatistics(record) {
                guard let root = statisticsRoot(record) else { return .statistics }
                roots.append(root)
            }
        }
        guard !roots.isEmpty else { return nil }
        var unused: [Cell] = []
        return try walk(handle, roots: roots, maximumRows: maximumSchemaRows, maximumRecord: maximumSchemaBytes,
                        maximumTotal: maximumSchemaBytes, cells: &unused)
    }

    /// the whole record of the table leaf cell at offset, read like sqlite does:
    /// after the size and rowid varints the local part (all of it up to U - 35,
    /// else M = (U - 12) * 32 / 255 - 23 and K = M + (size - M) % (U - 4), K if
    /// that's <= U - 35 else M), then the overflow chain, U - 4 bytes off each
    /// page after its 4 byte next pointer. nil = structure. schema rows only,
    /// the walk already capped them at maximumSchemaBytes
    private static func record(_ handle: FileHandle, _ g: Geometry, _ page: [UInt8], at offset: Int) throws -> [UInt8]? {
        let u = g.usable
        guard let (length, rowidAt) = varint(page, at: offset, end: u),
              let (_, start) = varint(page, at: rowidAt, end: u),
              // can't miss, the schema walk refused anything bigger
              length <= maximumSchemaBytes else { return nil }
        let total = Int(length)
        var local = total
        if total > u - 35 {
            let least = (u - 12) * 32 / 255 - 23
            local = least + (total - least) % (u - 4)
            if local > u - 35 { local = least }
        }
        guard start + local + (local < total ? 4 : 0) <= u else { return nil }
        var out: [UInt8] = []
        out.reserveCapacity(total)
        out.append(contentsOf: page[start..<start + local])
        var next = local < total ? be32(page, start + local) : 0
        while out.count < total {
            guard next >= 1, next <= g.pageCount else { return nil }
            let over = try read(handle, at: (next - 1) * UInt64(g.size), count: g.size)
            let take = min(total - out.count, u - 4)
            out.append(contentsOf: over[4..<4 + take])
            next = be32(over, 0)
        }
        return out
    }

    /// sqlite_stat anywhere in the record, ASCII case folded like sqlite compares names
    private static func mentionsStatistics(_ r: [UInt8]) -> Bool {
        guard r.count >= statisticsMark.count else { return false }
        outer: for i in 0...(r.count - statisticsMark.count) {
            for j in 0..<statisticsMark.count {
                let b = r[i + j]
                if (b >= 0x41 && b <= 0x5A ? b + 32 : b) != statisticsMark[j] { continue outer }
            }
            return true
        }
        return false
    }

    /// rootpage of a schema record that mentions sqlite_stat, or nil
    /// (statistics) unless it's what sqlite itself writes for those: exactly
    /// five columns filling the record exactly (no serial type 10 or 11),
    /// rootpage an integer and sql text starting CREATE TABLE. a view or
    /// virtual table by that name would run SQL from the file when sqlite reads it
    private static func statisticsRoot(_ r: [UInt8]) -> Int64? {
        guard let (header, first) = varint(r, at: 0, end: r.count),
              header >= UInt64(first), header <= UInt64(r.count) else { return nil }
        let end = Int(header)
        var at = first
        var types: [UInt64] = []
        while at < end {
            guard let (type, after) = varint(r, at: at, end: end) else { return nil }
            types.append(type)
            at = after
            // six's already wrong, no point reading the rest of a huge header
            if types.count > 5 { return nil }
        }
        guard types.count == 5 else { return nil }
        var starts: [Int] = []
        var body = end
        for type in types {
            let n: UInt64
            switch type {
            case 10, 11: return nil
            case 0..<12: n = UInt64(serialSizes[Int(type)])
            default: n = (type - 12) / 2
            }
            // too big for what's left is too big, however it'd add up
            guard n <= UInt64(r.count - body) else { return nil }
            starts.append(body)
            body += Int(n)
        }
        guard body == r.count else { return nil }
        let rootType = types[3]
        let sqlType = types[4]
        guard sqlType >= 13, sqlType % 2 == 1, r.count - starts[4] >= createTable.count,
              r[starts[4]..<starts[4] + createTable.count].elementsEqual(createTable) else { return nil }
        switch rootType {
        case 8: return 0
        case 9: return 1
        case 1...6:
            // big endian two's complement, sign from the first byte
            let at = starts[3]
            var value: Int64 = r[at] & 0x80 != 0 ? -1 : 0
            for k in 0..<serialSizes[Int(rootType)] { value = value << 8 | Int64(r[at + k]) }
            return value
        default:
            return nil
        }
    }

    /// sqlite's varint and where the next thing starts: up to 8 bytes of 7 bits
    /// (high bit = more), a 9th gives all 8. unsigned. nil if it runs into end
    private static func varint(_ b: [UInt8], at: Int, end: Int) -> (value: UInt64, next: Int)? {
        var value: UInt64 = 0
        for i in 0..<9 {
            guard at + i < end else { return nil }
            let byte = UInt64(b[at + i])
            if i == 8 { return (value << 8 | byte, at + 9) }
            value = value << 7 | (byte & 0x7F)
            if byte < 0x80 { return (value, at + i + 1) }
        }
        return nil
    }

    private static func be16(_ b: [UInt8], _ at: Int) -> Int {
        Int(b[at]) << 8 | Int(b[at + 1])
    }

    private static func be32(_ b: [UInt8], _ at: Int) -> UInt64 {
        UInt64(b[at]) << 24 | UInt64(b[at + 1]) << 16 | UInt64(b[at + 2]) << 8 | UInt64(b[at + 3])
    }

    /// exactly count bytes at offset or it throws, a short read never passes as zeros
    private static func read(_ handle: FileHandle, at offset: UInt64, count: Int) throws -> [UInt8] {
        try handle.seek(toOffset: offset)
        var bytes: [UInt8] = []
        bytes.reserveCapacity(count)
        while bytes.count < count {
            guard let chunk = try handle.read(upToCount: count - bytes.count), !chunk.isEmpty else {
                throw ReadFailure()
            }
            bytes.append(contentsOf: chunk)
        }
        return bytes
    }
}
