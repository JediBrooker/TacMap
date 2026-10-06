package com.tacmap.calibration

import java.util.Locale

/**
 * s14.1 viewShape: the only views an MBTiles pack may have. A plain projection of one table
 * or an equi-join of two, made of column refs and aliases, checked on the view's text before
 * anything reads it, so no expression stored in the file ever gets evaluated. Straight port
 * of the generator's _vs_tokens() / view_shape() (scripts/gen_calibration_fixtures.py),
 * pinned by import_limits.json viewShape.cases. Pure, no SQLite in here
 */
internal object MBTilesViewShape {
    sealed class Result {
        /** the base tables the view reads, in order, quoted ones without their marks */
        data class Accepted(val tables: List<String>) : Result()
        /** tooLong, token or shape */
        data class Refused(val reason: String) : Result()
    }

    const val MAX_SQL_BYTES = 100_000

    // sqlite.org/lang_keywords.html (147) plus TRUE and FALSE. none of these is a bare identifier here
    val RESERVED_WORDS: Set<String> = (
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
        ).split(' ').toSet()

    private const val WHITESPACE = " \t\r\n"
    private const val PUNCTUATION = "(),.*="
    private val QUOTES = mapOf('"' to '"', '[' to ']', '`' to '`')
    private const val QUOTE_MARKS = "\"[]`"

    private class Reject(val reason: String) : Exception(null, null, false, false)

    /** w = word, q = quoted identifier (body only), p = punctuation */
    private data class Token(val kind: Char, val text: String)

    fun check(sql: String?, relation: String): Result {
        if (sql == null) return Result.Refused("shape")
        if (sql.toByteArray(Charsets.UTF_8).size > MAX_SQL_BYTES) return Result.Refused("tooLong")
        return try {
            Result.Accepted(Parser(tokens(sql), relation).view())
        } catch (r: Reject) {
            Result.Refused(r.reason)
        }
    }

    private fun isWordStart(ch: Char) = ch in 'A'..'Z' || ch in 'a'..'z' || ch == '_'
    private fun isWordPart(ch: Char) = isWordStart(ch) || ch in '0'..'9'

    private fun tokens(sql: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = sql.length
        while (i < n) {
            val ch = sql[i]
            when {
                ch in WHITESPACE -> i++
                ch in PUNCTUATION -> { out += Token('p', ch.toString()); i++ }
                isWordStart(ch) -> {
                    var j = i + 1
                    while (j < n && isWordPart(sql[j])) j++
                    out += Token('w', sql.substring(i, j))
                    i = j
                }
                ch in QUOTES -> {
                    val j = sql.indexOf(QUOTES.getValue(ch), i + 1)
                    val body = if (j > i) sql.substring(i + 1, j) else ""
                    // printable ascii, no quote or bracket inside or straight after the closing mark.
                    // sqlite reads "a""b" as the one name a"b, splitting it would check the wrong table
                    if (j < 0 || body.isEmpty() || body.any { it.code !in 0x20..0x7E || it in QUOTE_MARKS } ||
                        (j + 1 < n && sql[j + 1] in QUOTE_MARKS)
                    ) throw Reject("token")
                    out += Token('q', body)
                    i = j + 1
                }
                else -> throw Reject("token")
            }
        }
        return out
    }

    private class Parser(private val toks: List<Token>, private val relation: String) {
        private var pos = 0
        private val tables = ArrayList<String>()

        private fun peek(): Token? = toks.getOrNull(pos)

        private fun kw(word: String): Boolean {
            val t = peek()
            if (t != null && t.kind == 'w' && t.text.uppercase(Locale.ROOT) == word) {
                pos++
                return true
            }
            return false
        }

        private fun punct(ch: String): Boolean {
            val t = peek()
            if (t != null && t.kind == 'p' && t.text == ch) {
                pos++
                return true
            }
            return false
        }

        private fun need(ok: Boolean) {
            if (!ok) throw Reject("shape")
        }

        private fun maybeIdent(): String? {
            val t = peek() ?: return null
            if (t.kind == 'q' || (t.kind == 'w' && t.text.uppercase(Locale.ROOT) !in RESERVED_WORDS)) {
                pos++
                return t.text
            }
            return null
        }

        private fun ident(): String = maybeIdent() ?: throw Reject("shape")

        private fun colref() {
            ident()
            if (punct(".")) ident()
        }

        private fun resultColumn() {
            if (punct("*")) return
            ident()
            if (punct(".")) {
                if (punct("*")) return
                ident()
            }
            if (kw("AS")) ident() else maybeIdent()
        }

        private fun tableRef() {
            tables += ident()
            if (kw("AS")) ident() else maybeIdent()
        }

        // no IF NOT EXISTS (SEC-M1-SHADOW): sqlite drops it when it stores a view, so only a hand edit has it,
        // and the duplicate it lets sqlite skip is how a decoy row sat behind the live view
        fun view(): List<String> {
            need(kw("CREATE"))
            need(kw("VIEW"))
            need(ident().lowercase(Locale.ROOT) == relation)
            if (punct("(")) {
                ident()
                while (punct(",")) ident()
                need(punct(")"))
            }
            need(kw("AS"))
            need(kw("SELECT"))
            resultColumn()
            while (punct(",")) resultColumn()
            need(kw("FROM"))
            tableRef()
            var joined = false
            if (kw("INNER") || kw("CROSS")) {
                need(kw("JOIN"))
                joined = true
            } else if (kw("LEFT")) {
                kw("OUTER")
                need(kw("JOIN"))
                joined = true
            } else if (kw("JOIN")) {
                joined = true
            }
            if (joined) {
                tableRef()
                if (kw("ON")) {
                    val paren = punct("(")
                    colref()
                    need(punct("="))
                    colref()
                    if (paren) need(punct(")"))
                } else if (kw("USING")) {
                    need(punct("("))
                    ident()
                    while (punct(",")) ident()
                    need(punct(")"))
                } else {
                    throw Reject("shape")
                }
            }
            need(peek() == null)
            return tables
        }
    }

    /** the first two ascii words of a table's sql, they have to be CREATE TABLE so not CREATE VIRTUAL TABLE */
    private val TABLE_PREFIX = Regex("^[ \\t\\r\\n]*([A-Za-z]+)[ \\t\\r\\n]+([A-Za-z]+)")

    /**
     * SEC-M1-SHADOW: does a table row's sqlite_master.sql make exactly the ordinary table [name]. CREATE TABLE,
     * then one identifier (the view tokenizer's quoted forms, or a non reserved word with whitespace before it)
     * equal to name ignoring ascii case, not followed by a quote mark or a dot. keeps out IF NOT EXISTS (IF is
     * reserved, sqlite never stores it anyway) and a row whose name column says t while its sql makes another
     * table, which sqlite without the schema name cross-check (older android) loads fine. port of
     * table_declares() in the generator, pinned by import_limits.json baseTableShape.cases
     */
    fun tableDeclares(sql: String?, name: String): Boolean {
        val m = TABLE_PREFIX.find(sql ?: return false) ?: return false
        if (m.groupValues[1].uppercase(Locale.ROOT) != "CREATE" || m.groupValues[2].uppercase(Locale.ROOT) != "TABLE") {
            return false
        }
        val n = sql.length
        var i = m.range.last + 1
        val spaced = i < n && sql[i] in WHITESPACE
        while (i < n && sql[i] in WHITESPACE) i++
        if (i >= n) return false
        val ident: String
        val ch = sql[i]
        if (ch in QUOTES) {
            val j = sql.indexOf(QUOTES.getValue(ch), i + 1)
            if (j < 0) return false
            ident = sql.substring(i + 1, j)
            if (ident.isEmpty() || ident.any { it.code !in 0x20..0x7E || it in QUOTE_MARKS }) return false
            i = j + 1
        } else if (spaced && isWordStart(ch)) {
            var j = i + 1
            while (j < n && isWordPart(sql[j])) j++
            ident = sql.substring(i, j)
            if (ident.uppercase(Locale.ROOT) in RESERVED_WORDS) return false
            i = j
        } else {
            return false
        }
        if (i < n && (sql[i] in QUOTE_MARKS || sql[i] == '.')) return false
        // ascii fold only, a non ascii name never matches
        return name.all { it.code < 0x80 } && ident.equals(name, ignoreCase = true)
    }
}
