import Foundation

/// 3.0.2 SEC-1: the only views we let a pack have. A plain projection of one
/// table or an equi-join of two, column names and aliases only, checked on the
/// view's own text before anything runs it, so no SQL from the file ever gets
/// evaluated on a read. Straight port of _vs_tokens() / view_shape() in
/// scripts/gen_calibration_fixtures.py, pinned by import_limits.json
/// viewShape.cases. Pure, no SQLite in here.
enum MBTilesViewShape {

    enum Refusal: String, Error, Equatable {
        case tooLong, token, shape
    }

    static let maximumSQLBytes = 100_000

    /// sqlite.org/lang_keywords.html (147) plus TRUE and FALSE. A bare word in
    /// a view can't be one of these, so nothing we read as a column can turn
    /// out to be a keyword that makes a value
    static let reservedWords: Set<String> = Set((
        "ABORT ACTION ADD AFTER ALL ALTER ALWAYS ANALYZE AND AS ASC ATTACH AUTOINCREMENT BEFORE BEGIN BETWEEN BY " +
        "CASCADE CASE CAST CHECK COLLATE COLUMN COMMIT CONFLICT CONSTRAINT CREATE CROSS CURRENT CURRENT_DATE " +
        "CURRENT_TIME CURRENT_TIMESTAMP DATABASE DEFAULT DEFERRABLE DEFERRED DELETE DESC DETACH DISTINCT DO DROP EACH " +
        "ELSE END ESCAPE EXCEPT EXCLUDE EXCLUSIVE EXISTS EXPLAIN FAIL FILTER FIRST FOLLOWING FOR FOREIGN FROM FULL " +
        "GENERATED GLOB GROUP GROUPS HAVING IF IGNORE IMMEDIATE IN INDEX INDEXED INITIALLY INNER INSERT INSTEAD " +
        "INTERSECT INTO IS ISNULL JOIN KEY LAST LEFT LIKE LIMIT MATCH MATERIALIZED NATURAL NO NOT NOTHING NOTNULL NULL " +
        "NULLS OF OFFSET ON OR ORDER OTHERS OUTER OVER PARTITION PLAN PRAGMA PRECEDING PRIMARY QUERY RAISE RANGE " +
        "RECURSIVE REFERENCES REGEXP REINDEX RELEASE RENAME REPLACE RESTRICT RETURNING RIGHT ROLLBACK ROW ROWS " +
        "SAVEPOINT SELECT SET TABLE TEMP TEMPORARY THEN TIES TO TRANSACTION TRIGGER UNBOUNDED UNION UNIQUE UPDATE " +
        "USING VACUUM VALUES VIEW VIRTUAL WHEN WHERE WINDOW WITH WITHOUT TRUE FALSE"
    ).split(separator: " ").map(String.init))

    enum Token: Equatable {
        case word(String)
        case quoted(String)
        case punct(UInt8)
    }

    /// the base tables the view reads, in order (quoted ones without their marks)
    static func baseTables(sql: String, relation: String) -> Result<[String], Refusal> {
        let bytes = Array(sql.utf8)
        guard bytes.count <= maximumSQLBytes else { return .failure(.tooLong) }
        do {
            var p = Parser(tokens: try tokens(bytes))
            return .success(try p.view(relation: relation))
        } catch let r as Refusal {
            return .failure(r)
        } catch {
            return .failure(.shape)
        }
    }

    /// SEC-M1-SHADOW: does a table row's sqlite_master.sql make exactly the
    /// ordinary table `name`. The words CREATE TABLE (so not CREATE VIRTUAL
    /// TABLE), then one identifier, quoted the three ways the view tokenizer
    /// takes or a non reserved word with whitespace before it, equal to name
    /// ignoring ASCII case and not followed by a quote mark or a dot. That
    /// keeps out IF NOT EXISTS (IF is reserved, sqlite never stores it anyway)
    /// and a row whose name column says t while its sql makes some other
    /// table, which sqlite without the schema name cross-check loads happily.
    /// Port of table_declares() in the generator, pinned by import_limits.json
    /// baseTableShape.cases
    static func tableDeclares(sql: String?, name: String) -> Bool {
        guard let sql else { return false }
        let b = Array(sql.utf8)
        let n = b.count
        var i = 0
        func isSpace(_ c: UInt8) -> Bool { c == 0x20 || c == 0x09 || c == 0x0D || c == 0x0A }
        func isAlpha(_ c: UInt8) -> Bool { (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) }
        func word() -> String? {
            let start = i
            while i < n, isAlpha(b[i]) { i += 1 }
            return i > start ? String(decoding: b[start..<i], as: UTF8.self).uppercased() : nil
        }
        // same as the generator's regex [ \t\r\n]*([A-Za-z]+)[ \t\r\n]+([A-Za-z]+)
        while i < n, isSpace(b[i]) { i += 1 }
        guard word() == "CREATE", i < n, isSpace(b[i]) else { return false }
        while i < n, isSpace(b[i]) { i += 1 }
        guard word() == "TABLE" else { return false }
        let spaced = i < n && isSpace(b[i])
        while i < n, isSpace(b[i]) { i += 1 }
        guard i < n else { return false }
        let ident: ArraySlice<UInt8>
        let c = b[i]
        if c == UInt8(ascii: "\"") || c == UInt8(ascii: "[") || c == UInt8(ascii: "`") {
            let close = c == UInt8(ascii: "[") ? UInt8(ascii: "]") : c
            guard let j = b[(i + 1)...].firstIndex(of: close) else { return false }
            ident = b[(i + 1)..<j]
            guard !ident.isEmpty, ident.allSatisfy({ $0 >= 0x20 && $0 <= 0x7E && !isQuoteMark($0) }) else {
                return false
            }
            i = j + 1
        } else if spaced, isLetter(c) {
            let start = i
            i += 1
            while i < n, isLetter(b[i]) || (b[i] >= 0x30 && b[i] <= 0x39) { i += 1 }
            ident = b[start..<i]
            guard !reservedWords.contains(String(decoding: ident, as: UTF8.self).uppercased()) else { return false }
        } else {
            return false
        }
        if i < n, isQuoteMark(b[i]) || b[i] == UInt8(ascii: ".") { return false }
        // ascii fold only, a non ascii name never matches
        func fold(_ c: UInt8) -> UInt8 { c >= 0x41 && c <= 0x5A ? c + 0x20 : c }
        let want = Array(name.utf8)
        return want.allSatisfy({ $0 < 0x80 }) && ident.count == want.count
            && zip(ident, want).allSatisfy { fold($0) == fold($1) }
    }

    private static func isQuoteMark(_ c: UInt8) -> Bool {
        c == UInt8(ascii: "\"") || c == UInt8(ascii: "[") || c == UInt8(ascii: "]") || c == UInt8(ascii: "`")
    }

    private static func isLetter(_ c: UInt8) -> Bool {
        (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c == UInt8(ascii: "_")
    }

    /// ASCII whitespace, six punctuation marks, ASCII words and the three
    /// quoted identifier forms. Anything else (digits starting a token, string
    /// or blob literals, comments, operators, parameters, ;, non-ASCII) is a
    /// token refusal. Never has to understand SQL it won't admit
    static func tokens(_ bytes: [UInt8]) throws -> [Token] {
        var out: [Token] = []
        var i = 0
        let n = bytes.count
        while i < n {
            let c = bytes[i]
            switch c {
            case 0x20, 0x09, 0x0D, 0x0A:
                i += 1
            case UInt8(ascii: "("), UInt8(ascii: ")"), UInt8(ascii: ","), UInt8(ascii: "."),
                 UInt8(ascii: "*"), UInt8(ascii: "="):
                out.append(.punct(c))
                i += 1
            case UInt8(ascii: "\""), UInt8(ascii: "["), UInt8(ascii: "`"):
                let close = c == UInt8(ascii: "[") ? UInt8(ascii: "]") : c
                guard let j = bytes[(i + 1)...].firstIndex(of: close) else { throw Refusal.token }
                let body = bytes[(i + 1)..<j]
                // "a""b" is one name to SQLite (a"b), splitting it would check the wrong table
                guard !body.isEmpty,
                      body.allSatisfy({ $0 >= 0x20 && $0 <= 0x7E && !isQuoteMark($0) }),
                      j + 1 >= n || !isQuoteMark(bytes[j + 1]) else { throw Refusal.token }
                out.append(.quoted(String(decoding: body, as: UTF8.self)))
                i = j + 1
            default:
                guard isLetter(c) else { throw Refusal.token }
                var j = i + 1
                while j < n, isLetter(bytes[j]) || (bytes[j] >= 0x30 && bytes[j] <= 0x39) { j += 1 }
                out.append(.word(String(decoding: bytes[i..<j], as: UTF8.self)))
                i = j
            }
        }
        return out
    }

    /// CREATE VIEW <relation> [(ident, ...)] AS SELECT col [, col ...]
    /// FROM table [[INNER | LEFT [OUTER] | CROSS] JOIN table (ON equalities |
    /// USING (ident, ...)) | , table WHERE equalities], nothing after it, where
    /// equalities = colref = colref [AND colref = colref ...], bare or in one
    /// pair of parens. no IF NOT EXISTS: sqlite drops it when it stores a view,
    /// so only a hand edit has it, and the duplicate it lets sqlite skip is how
    /// a decoy row sat behind the live view (SEC-M1-SHADOW). the comma join and
    /// the AND are SHADOW-PARITY-2, gdal2mbtiles and tippecanoe write those and
    /// 3.0.1 opened them. still just column refs, nothing to evaluate
    private struct Parser {
        let tokens: [Token]
        var pos = 0
        var tables: [String] = []

        init(tokens: [Token]) { self.tokens = tokens }

        private var peek: Token? { pos < tokens.count ? tokens[pos] : nil }

        private mutating func kw(_ word: String) -> Bool {
            if case .word(let w)? = peek, w.uppercased() == word {
                pos += 1
                return true
            }
            return false
        }

        private mutating func punct(_ ch: Character) -> Bool {
            if case .punct(let c)? = peek, c == ch.asciiValue {
                pos += 1
                return true
            }
            return false
        }

        private func need(_ ok: Bool) throws {
            if !ok { throw Refusal.shape }
        }

        private mutating func maybeIdent() -> String? {
            switch peek {
            case .quoted(let q)?:
                pos += 1
                return q
            case .word(let w)? where !MBTilesViewShape.reservedWords.contains(w.uppercased()):
                pos += 1
                return w
            default:
                return nil
            }
        }

        private mutating func ident() throws -> String {
            guard let name = maybeIdent() else { throw Refusal.shape }
            return name
        }

        private mutating func colref() throws {
            _ = try ident()
            if punct(".") { _ = try ident() }
        }

        private mutating func resultColumn() throws {
            if punct("*") { return }
            _ = try ident()
            if punct(".") {
                if punct("*") { return }
                _ = try ident()
            }
            if kw("AS") {
                _ = try ident()
            } else {
                _ = maybeIdent()
            }
        }

        private mutating func tableRef() throws {
            tables.append(try ident())
            if kw("AS") {
                _ = try ident()
            } else {
                _ = maybeIdent()
            }
        }

        /// colref = colref, as many as you like joined by AND, parens round
        /// the whole lot or none at all
        private mutating func equalities() throws {
            let paren = punct("(")
            repeat {
                try colref()
                try need(punct("="))
                try colref()
            } while kw("AND")
            if paren { try need(punct(")")) }
        }

        mutating func view(relation: String) throws -> [String] {
            try need(kw("CREATE"))
            try need(kw("VIEW"))
            try need(try ident().lowercased() == relation)
            if punct("(") {
                _ = try ident()
                while punct(",") { _ = try ident() }
                try need(punct(")"))
            }
            try need(kw("AS"))
            try need(kw("SELECT"))
            try resultColumn()
            while punct(",") { try resultColumn() }
            try need(kw("FROM"))
            try tableRef()
            var joined = false
            if punct(",") {
                // gdal2mbtiles' comma join, the WHERE is only the join's equalities
                try tableRef()
                try need(kw("WHERE"))
                try equalities()
            } else if kw("INNER") || kw("CROSS") {
                try need(kw("JOIN"))
                joined = true
            } else if kw("LEFT") {
                _ = kw("OUTER")
                try need(kw("JOIN"))
                joined = true
            } else if kw("JOIN") {
                joined = true
            }
            if joined {
                try tableRef()
                if kw("ON") {
                    try equalities()
                } else if kw("USING") {
                    try need(punct("("))
                    _ = try ident()
                    while punct(",") { _ = try ident() }
                    try need(punct(")"))
                } else {
                    throw Refusal.shape
                }
            }
            try need(peek == nil)
            return tables
        }
    }
}
