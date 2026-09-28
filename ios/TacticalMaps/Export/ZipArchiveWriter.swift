import Foundation

/// Minimal ZIP writer for KMZ export. Entries are stored uncompressed: a KMZ
/// holds one small KML document and a few PNG icons (already compressed), so
/// deflate buys little and a stored archive keeps this dependency-free.
/// Timestamps are fixed so the same mission always produces identical bytes.
struct ZipArchiveWriter {
    private var body = Data()
    private var centralDirectory = Data()
    private var entryCount: UInt16 = 0
    private var names = Set<String>()

    /// 1980-01-01 00:00, the earliest DOS date.
    private static let dosTime: UInt16 = 0
    private static let dosDate: UInt16 = (1 << 5) | 1
    /// General-purpose flag bit 11: file names are UTF-8.
    private static let utf8Flag: UInt16 = 0x0800

    /// Adds a file. Duplicate paths are ignored so a caller cannot create an
    /// ambiguous archive.
    mutating func add(path: String, contents: Data) {
        guard names.insert(path).inserted else { return }
        let name = Data(path.utf8)
        let crc = Self.crc32(contents)
        let offset = UInt32(body.count)

        var local = Data()
        local.appendLittleEndian(UInt32(0x0403_4B50))
        local.appendLittleEndian(UInt16(10))            // version needed: stored
        local.appendLittleEndian(Self.utf8Flag)
        local.appendLittleEndian(UInt16(0))             // method: stored
        local.appendLittleEndian(Self.dosTime)
        local.appendLittleEndian(Self.dosDate)
        local.appendLittleEndian(crc)
        local.appendLittleEndian(UInt32(contents.count)) // compressed size
        local.appendLittleEndian(UInt32(contents.count)) // uncompressed size
        local.appendLittleEndian(UInt16(name.count))
        local.appendLittleEndian(UInt16(0))             // extra length
        body.append(local)
        body.append(name)
        body.append(contents)

        var central = Data()
        central.appendLittleEndian(UInt32(0x0201_4B50))
        central.appendLittleEndian(UInt16(20))          // version made by
        central.appendLittleEndian(UInt16(10))          // version needed
        central.appendLittleEndian(Self.utf8Flag)
        central.appendLittleEndian(UInt16(0))
        central.appendLittleEndian(Self.dosTime)
        central.appendLittleEndian(Self.dosDate)
        central.appendLittleEndian(crc)
        central.appendLittleEndian(UInt32(contents.count))
        central.appendLittleEndian(UInt32(contents.count))
        central.appendLittleEndian(UInt16(name.count))
        central.appendLittleEndian(UInt16(0))           // extra length
        central.appendLittleEndian(UInt16(0))           // comment length
        central.appendLittleEndian(UInt16(0))           // disk number
        central.appendLittleEndian(UInt16(0))           // internal attributes
        central.appendLittleEndian(UInt32(0))           // external attributes
        central.appendLittleEndian(offset)
        centralDirectory.append(central)
        centralDirectory.append(name)
        entryCount += 1
    }

    /// The finished archive.
    func archive() -> Data {
        var out = body
        out.append(centralDirectory)
        var end = Data()
        end.appendLittleEndian(UInt32(0x0605_4B50))
        end.appendLittleEndian(UInt16(0))               // this disk
        end.appendLittleEndian(UInt16(0))               // central directory disk
        end.appendLittleEndian(entryCount)
        end.appendLittleEndian(entryCount)
        end.appendLittleEndian(UInt32(centralDirectory.count))
        end.appendLittleEndian(UInt32(body.count))
        end.appendLittleEndian(UInt16(0))               // comment length
        out.append(end)
        return out
    }

    private static let crcTable: [UInt32] = (0..<256).map { index -> UInt32 in
        var value = UInt32(index)
        for _ in 0..<8 {
            value = (value & 1) != 0 ? 0xEDB8_8320 ^ (value >> 1) : value >> 1
        }
        return value
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = crcTable[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }
}
