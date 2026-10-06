import Foundation

/// s15.2 recordProbe (3.0.3 U2, SEC-M1-ANDROID-OLDSQLITE-OOM). sqlite loads all
/// of a TEXT value before length() or substr(CAST(... AS BLOB)) gets to look at
/// it, and only 3.45+ checks SQLITE_LIMIT_LENGTH before that load. iOS 16-18
/// ship older than that, and the schema load at the first statement reads every
/// schema row before any check of ours. so before sqlite gets near those rows we
/// read the file's own b-tree pages: page headers, cell pointers and each leaf
/// cell's first varint, which is that row's whole record size. nothing sqlite
/// loads from the row can be bigger. never a value, an overflow page or a record
/// header. straight port of probe_header() / probe_walk() in
/// scripts/gen_calibration_fixtures.py, pinned by import_limits.json
/// recordProbe.cases. pure, no sqlite in here
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
        case header, structure, pageType, rows, recordBytes, totalBytes
    }

    /// the file couldn't be read (gone, short read, locked). callers refuse on it
    struct ReadFailure: Error {}

    private static let magic = Array("SQLite format 3\u{0}".utf8)

    /// sqlite_master from page 1, before sqlite opens the file. nil = fine
    static func schema(_ url: URL) throws -> Refusal? {
        try walk(url, root: 1, maximumRows: maximumSchemaRows, maximumRecord: maximumSchemaBytes,
                 maximumTotal: maximumSchemaBytes)
    }

    /// one table the metadata relation reads, from its sqlite_master rootpage. nil = fine
    static func metadataTable(_ url: URL, root: Int64) throws -> Refusal? {
        try walk(url, root: root, maximumRows: maximumMetadataRows, maximumRecord: maximumMetadataRecordBytes,
                 maximumTotal: nil)
    }

    /// the reason for the first problem in walk order, nil if there's none.
    /// throws ReadFailure when the file can't be read
    static func walk(_ url: URL, root: Int64, maximumRows: Int, maximumRecord: UInt64,
                     maximumTotal: UInt64?) throws -> Refusal? {
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            throw ReadFailure()
        }
        defer { try? handle.close() }
        do {
            return try walk(handle, root: root, maximumRows: maximumRows, maximumRecord: maximumRecord,
                            maximumTotal: maximumTotal)
        } catch {
            throw ReadFailure()
        }
    }

    /// a page still to visit. flat, an interior page can list ~32k children
    private struct Pending {
        let page: UInt64
        let depth: Int
    }

    private static func walk(_ handle: FileHandle, root: Int64, maximumRows: Int, maximumRecord: UInt64,
                             maximumTotal: UInt64?) throws -> Refusal? {
        let length = try handle.seekToEnd()
        guard length >= 100 else { return .header }
        let head = try read(handle, at: 0, count: 100)
        guard head.starts(with: magic) else { return .header }
        let declared = be16(head, 16)
        let size = declared == 1 ? 65_536 : declared
        guard size >= 512, size <= 65_536, size & (size - 1) == 0 else { return .header }
        let usable = size - Int(head[20])
        guard usable >= 480 else { return .header }
        let pageCount = length / UInt64(size)

        // the root goes through the same check as any page, done here so a
        // negative rootpage never gets near a UInt64
        guard root >= 1, UInt64(root) <= pageCount else { return .structure }
        var stack = [Pending(page: UInt64(root), depth: 0)]
        var seen = Set<UInt64>()
        var rows = 0
        var total: UInt64 = 0
        while let next = stack.popLast() {
            let p = next.page
            guard p >= 1, p <= pageCount, !seen.contains(p), next.depth < maximumDepth,
                  seen.count < maximumPages else { return .structure }
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
                var children: [UInt64] = []
                children.reserveCapacity(n + 1)
                for i in 0..<n {
                    let o = be16(page, h + 12 + 2 * i)
                    guard o >= first, o + 4 <= usable else { return .structure }
                    children.append(be32(page, o))
                }
                children.append(be32(page, h + 8))
                // backwards so the left most child comes off first (pre-order)
                for child in children.reversed() {
                    stack.append(Pending(page: child, depth: next.depth + 1))
                }
            case 0x0D:
                let first = h + 8 + 2 * n
                guard first <= usable else { return .structure }
                for i in 0..<n {
                    let o = be16(page, h + 8 + 2 * i)
                    guard o >= first, o < usable, let record = varint(page, at: o, end: usable) else {
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
                }
            default:
                return .pageType
            }
        }
        return nil
    }

    /// sqlite's varint: up to 8 bytes of 7 bits (high bit = more), a 9th gives
    /// all 8. unsigned. nil if it runs into end
    private static func varint(_ b: [UInt8], at: Int, end: Int) -> UInt64? {
        var value: UInt64 = 0
        for i in 0..<9 {
            guard at + i < end else { return nil }
            let byte = UInt64(b[at + i])
            if i == 8 { return value << 8 | byte }
            value = value << 7 | (byte & 0x7F)
            if byte < 0x80 { return value }
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
