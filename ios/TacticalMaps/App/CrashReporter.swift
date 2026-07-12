import Foundation
import Darwin

/// Minimal local-only crash capture. No telemetry SDK, nothing leaves the
/// device (consistent with privacy policy). Installs an uncaught-exception
/// handler + a few fatal-signal handlers that write a short report to a file
/// in Application Support. Next launch `lastReport()` returns it so user
/// can review / export from About; `clear()` removes it.
///
/// This is the privacy-preserving answer to "a field app shouldn't crash
/// silently" - the user opts in to sharing by exporting the file themselves.
///
/// Signal-handler safety: the fatal-signal path uses ONLY async-signal-safe
/// calls (open/write/close/backtrace_symbols_fd) over a path and buffers
/// computed at install time. No Foundation, no allocation, no locks. The
/// richer Foundation-based report is only used for NSSetUncaughtExceptionHandler
/// which runs in a normal ObjC context, not a signal handler.
enum CrashReporter {

    private static var fileURL: URL? {
        let dir = try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        )
        return dir?.appendingPathComponent("last_crash.log")
    }

    // Pre-computed at install time so the signal handler never touches
    // FileManager or allocates. pathBytes is a null-terminated C string.
    private static var pathBytes: [CChar] = []
    private static let preamble = Array("TacMap fatal signal ".utf8)
    private static let newline: [UInt8] = [0x0A]
    private static var numBuf = [UInt8](repeating: 0, count: 12)
    private static var frames = [UnsafeMutableRawPointer?](repeating: nil, count: 64)

    /// Install the handlers. Call once at launch.
    static func install() {
        NSSetUncaughtExceptionHandler { exception in
            let frames = exception.callStackSymbols.prefix(24).joined(separator: "\n")
            CrashReporter.write("Uncaught \(exception.name.rawValue): \(exception.reason ?? "")\n\n\(frames)")
        }
        if let path = fileURL?.path {
            pathBytes = path.utf8CString.map { $0 }
        }
        for sig in [SIGABRT, SIGILL, SIGSEGV, SIGFPE, SIGBUS, SIGTRAP] {
            signal(sig) { s in
                CrashReporter.writeSignalReport(s)
                // Re-raise with default handler so the OS still records it.
                signal(s, SIG_DFL)
                raise(s)
            }
        }
    }

    /// Async-signal-safe crash write. open/write/close and
    /// backtrace_symbols_fd are all on the async-signal-safe list;
    /// path + scratch buffers were allocated at install time.
    private static func writeSignalReport(_ s: Int32) {
        guard !pathBytes.isEmpty else { return }
        let fd = pathBytes.withUnsafeBufferPointer {
            open($0.baseAddress!, O_WRONLY | O_CREAT | O_TRUNC, 0o600)
        }
        guard fd >= 0 else { return }
        preamble.withUnsafeBufferPointer { _ = Darwin.write(fd, $0.baseAddress, $0.count) }
        // Signal number -> ASCII, in place (no alloc).
        var n = Int(s)
        var i = numBuf.count
        if n == 0 { i -= 1; numBuf[i] = UInt8(ascii: "0") }
        while n > 0 && i > 0 { i -= 1; numBuf[i] = UInt8(ascii: "0") + UInt8(n % 10); n /= 10 }
        numBuf.withUnsafeBufferPointer { _ = Darwin.write(fd, $0.baseAddress!.advanced(by: i), $0.count - i) }
        newline.withUnsafeBufferPointer { _ = Darwin.write(fd, $0.baseAddress, $0.count) }
        // Symbolicated backtrace, written straight to fd.
        let count = frames.withUnsafeMutableBufferPointer { backtrace($0.baseAddress, Int32($0.count)) }
        frames.withUnsafeMutableBufferPointer { backtrace_symbols_fd($0.baseAddress, count, fd) }
        close(fd)
    }

    /// Previous run's crash report if any.
    static func lastReport() -> String? {
        guard let url = fileURL,
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8),
              !text.isEmpty else { return nil }
        return text
    }

    /// Write report to a temp .txt for sharing via system share sheet.
    static func exportURL() -> URL? {
        guard let report = lastReport() else { return nil }
        guard let url = try? ExportFileSecurity.freshURL(fileName: "TacMap-crash.txt"),
              let data = report.data(using: .utf8) else { return nil }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            try ExportFileSecurity.protect(url)
            return url
        } catch {
            ExportFileSecurity.remove(url)
            return nil
        }
    }

    static func clear() {
        if let url = fileURL { try? FileManager.default.removeItem(at: url) }
    }

    /// Rich report path. Foundation-based, only used for the ObjC exception
    /// handler (runs in normal context, not a signal handler).
    private static func write(_ body: String) {
        guard let url = fileURL else { return }
        let stamp = ISO8601DateFormatter().string(from: Date())
        let report = "TacMap crash\n\(stamp)\n\n\(body)\n"
        try? report.data(using: .utf8)?.write(to: url)
    }
}
