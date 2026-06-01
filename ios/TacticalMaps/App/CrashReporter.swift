import Foundation

/// Minimal, **local-only** crash capture — no telemetry SDK, nothing leaves the
/// device (consistent with the privacy policy). Installs an uncaught-exception
/// handler and a few fatal-signal handlers that write a short report to a file
/// in Application Support. On the next launch `lastReport()` returns it so the
/// user can review / export it from About; `clear()` removes it.
///
/// This is the privacy-preserving answer to "a field app shouldn't crash
/// silently": the user opts in to sharing by exporting the file themselves.
enum CrashReporter {

    private static var fileURL: URL? {
        let dir = try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        )
        return dir?.appendingPathComponent("last_crash.log")
    }

    /// Install the handlers. Call once at launch.
    static func install() {
        NSSetUncaughtExceptionHandler { exception in
            let frames = exception.callStackSymbols.prefix(24).joined(separator: "\n")
            CrashReporter.write("Uncaught \(exception.name.rawValue): \(exception.reason ?? "")\n\n\(frames)")
        }
        for sig in [SIGABRT, SIGILL, SIGSEGV, SIGFPE, SIGBUS, SIGTRAP] {
            signal(sig) { s in
                CrashReporter.write("Fatal signal \(s)\n\n" +
                    Thread.callStackSymbols.prefix(24).joined(separator: "\n"))
                // Re-raise with the default handler so the OS still records it.
                signal(s, SIG_DFL)
                raise(s)
            }
        }
    }

    /// The previous run's crash report, if any.
    static func lastReport() -> String? {
        guard let url = fileURL,
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8),
              !text.isEmpty else { return nil }
        return text
    }

    /// Write the report to a temp `.txt` for sharing via the system share sheet.
    static func exportURL() -> URL? {
        guard let report = lastReport() else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("TacticalMaps-crash.txt")
        try? report.data(using: .utf8)?.write(to: url)
        return url
    }

    static func clear() {
        if let url = fileURL { try? FileManager.default.removeItem(at: url) }
    }

    // Async-signal-safety note: writing a file from a signal handler isn't
    // strictly async-signal-safe, but for a single best-effort *local* crash
    // log (no networking, no heavy allocation) this is the pragmatic trade-off
    // most lightweight in-app crash loggers make.
    private static func write(_ body: String) {
        guard let url = fileURL else { return }
        let stamp = ISO8601DateFormatter().string(from: Date())
        let report = "TacticalMaps crash\n\(stamp)\n\n\(body)\n"
        try? report.data(using: .utf8)?.write(to: url)
    }
}
