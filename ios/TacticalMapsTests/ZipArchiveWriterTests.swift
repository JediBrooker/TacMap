import XCTest
@testable import TacticalMaps

final class ZipArchiveWriterTests: XCTestCase {
    func testCRC32MatchesStandardCheckValue() {
        XCTAssertEqual(ZipArchiveWriter.crc32(Data("123456789".utf8)), 0xCBF4_3926)
    }

    func testStoredArchiveListsEntriesInOrderAndIgnoresDuplicates() throws {
        var zip = ZipArchiveWriter()
        let kml = Data("<kml>Grüße</kml>".utf8)
        let icon = Data((0..<300).map { UInt8($0 % 251) })
        zip.add(path: "doc.kml", contents: kml)
        zip.add(path: "files/icons/unit.png", contents: icon)
        zip.add(path: "doc.kml", contents: Data("duplicate".utf8))
        let archive = zip.archive()

        // Local header of the first entry, then its name and stored bytes.
        XCTAssertEqual(Array(archive.prefix(4)), [0x50, 0x4B, 0x03, 0x04])
        let firstNameStart = 30
        XCTAssertEqual(archive.subdata(in: firstNameStart..<(firstNameStart + 7)), Data("doc.kml".utf8))
        XCTAssertEqual(archive.subdata(in: (firstNameStart + 7)..<(firstNameStart + 7 + kml.count)), kml)

        // End-of-central-directory record: two entries, not three.
        let end = archive.suffix(22)
        XCTAssertEqual(Array(end.prefix(4)), [0x50, 0x4B, 0x05, 0x06])
        let entries = UInt16(end[end.startIndex + 10]) | UInt16(end[end.startIndex + 11]) << 8
        XCTAssertEqual(entries, 2)
    }

    func testSameInputProducesIdenticalBytes() {
        func build() -> Data {
            var zip = ZipArchiveWriter()
            zip.add(path: "doc.kml", contents: Data("<kml/>".utf8))
            return zip.archive()
        }
        XCTAssertEqual(build(), build())
    }
}
