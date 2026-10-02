package com.tacmap.calibration

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Filesystem-only lifecycle policy for app-managed PDF/GeoPDF and MBTiles
 * bytes. A caller supplies the authenticated files that remain reachable;
 * every other validated direct child of a managed import root is removed.
 *
 * This deliberately does not recurse or follow links. A malformed root,
 * symlink, directory named like a map, or unvalidated retained file makes the
 * pass incomplete and is left untouched.
 */
internal object ManagedImportedMapFileLifecycle {
    private val sqliteSidecarSuffixes = listOf("-wal", "-shm", "-journal")

    /** every bake PdfBaker publishes is named this, Remove and the sweep won't touch anything else */
    const val BAKE_PREFIX = "tacmap-bake-"

    /** tacmap-bake-<something>.mbtiles, the middle can't be empty. same rule as iOS isGeneratedBakeName */
    fun isGeneratedBakeName(name: String): Boolean =
        name.startsWith(BAKE_PREFIX) && name.length > BAKE_PREFIX.length + ".mbtiles".length &&
            name.lowercase().endsWith(".mbtiles")

    fun reconcile(
        managedParent: File,
        directories: List<File>,
        keeping: Set<File>,
        /** imports / bakes still being written (.partial and not-yet-committed copies), never touched */
        inFlight: Set<File> = emptySet(),
    ): Boolean {
        val canonicalParent = validateDirectory(managedParent, expectedParent = null)
            ?: return false
        var complete = true
        val roots = mutableListOf<File>()
        directories.forEach { directory ->
            if (!directory.exists()) return@forEach
            val root = validateDirectory(directory, expectedParent = canonicalParent)
            if (root == null) {
                complete = false
            } else if (root !in roots) {
                roots += root
            }
        }

        val canonicalKeep = mutableSetOf<File>()
        keeping.forEach { retained ->
            // a library entry's sqlite sidecars are kept with it, so residue names count here too
            val validated = validateManagedMap(
                retained,
                roots,
                authoritativeOnly = !isSqliteSidecar(retained.name),
            ) ?: return false
            canonicalKeep += validated
        }
        // in flight files may not exist yet (or any more), only the ones that do matter
        inFlight.forEach { busy ->
            if (!busy.exists()) return@forEach
            canonicalKeep += runCatching { busy.canonicalFile }.getOrNull() ?: return false
        }

        roots.forEach { root ->
            val children = root.listFiles()
            if (children == null) {
                complete = false
                return@forEach
            }
            children
                .filter { isManagedCandidateName(it.name) }
                .forEach { child ->
                    val candidate = validateManagedMap(
                        child,
                        listOf(root),
                        authoritativeOnly = false,
                    )
                    if (candidate == null) {
                        // Never follow or recursively remove an unexpected
                        // symlink/directory merely because its suffix is a map.
                        complete = false
                    } else if (candidate !in canonicalKeep) {
                        val deleted = runCatching {
                            Files.deleteIfExists(child.toPath()) || !child.exists()
                        }.getOrDefault(false)
                        if (!deleted) complete = false
                    }
                }
        }
        return complete
    }

    /**
     * Remove Offline Tiles (R2-S2): [name] and its -journal/-wal/-shm, straight children of
     * [root] only. No reconcile needed, so it works while the PDF is missing. Same caution as
     * above, a name with a path in it, a symlink or a directory is left alone and reported.
     * True when nothing by that name is left. Caller holds the managed files lock
     */
    fun deleteMBTiles(root: File, name: String): Boolean {
        if (name != File(name).name || name.startsWith(".") || !name.lowercase().endsWith(".mbtiles")) return false
        if (!root.exists()) return true
        val dir = runCatching { root.canonicalFile }.getOrNull() ?: return false
        if (Files.isSymbolicLink(root.toPath()) || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        var clean = true
        for (child in listOf(name) + sqliteSidecarSuffixes.map { name + it }) {
            val path = File(dir, child).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                clean = false
                continue
            }
            val gone = runCatching { Files.deleteIfExists(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)
            if (!gone) clean = false
        }
        return clean
    }

    /**
     * Bake-only sweep (R3-2): regular files directly in [root] named tacmap-bake-*.mbtiles,
     * plus their -journal/-wal/-shm, except [keep] (what the sealed session names, null =
     * none). Catches what a Remove whose delete failed left behind, which the reconcile
     * can't get while the PDF is missing. No recursion, symlinks and dirs are left alone and
     * reported. Caller holds the managed files lock and has actually read the record
     */
    fun sweepBakes(root: File, keep: String?): Boolean = sweepBakes(root, setOfNotNull(keep))

    /**
     * same, keeping every name in [keep] (the library can hold a bake per PDF) and anything
     * a bake publish still has in flight (moved in, record not written yet)
     */
    fun sweepBakes(root: File, keep: Set<String>, inFlight: Set<File> = emptySet()): Boolean {
        if (!root.exists()) return true
        val busy = inFlight.mapNotNullTo(HashSet()) { runCatching { it.canonicalPath }.getOrNull() }
        if (Files.isSymbolicLink(root.toPath()) || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val dir = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val children = dir.listFiles() ?: return false
        var clean = true
        for (child in children) {
            val name = child.name
            if (!name.startsWith(BAKE_PREFIX)) continue
            val base = sqliteSidecarSuffixes.firstOrNull(name::endsWith)?.let { name.removeSuffix(it) } ?: name
            if (!isGeneratedBakeName(base) || base in keep) continue
            if (runCatching { File(dir, base).canonicalPath }.getOrNull() in busy) continue
            val path = child.toPath()
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                clean = false
                continue
            }
            val gone = runCatching { Files.deleteIfExists(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)
            if (!gone) clean = false
        }
        return clean
    }

    private fun validateDirectory(directory: File, expectedParent: File?): File? {
        val path = directory.toPath()
        if (Files.isSymbolicLink(path) ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        ) return null
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (expectedParent != null && canonical.parentFile != expectedParent) return null
        return canonical
    }

    private fun validateManagedMap(
        candidate: File,
        roots: List<File>,
        authoritativeOnly: Boolean,
    ): File? {
        val validName = if (authoritativeOnly) {
            isAuthoritativeMapName(candidate.name)
        } else {
            isManagedCandidateName(candidate.name)
        }
        if (!validName) return null
        val path = candidate.toPath()
        if (Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val canonicalParent = canonical.parentFile ?: return null
        if (canonicalParent !in roots) return null
        return canonical
    }

    private fun isManagedCandidateName(name: String): Boolean =
        isAuthoritativeMapName(name) || isCrashResidueName(name)

    private fun isAuthoritativeMapName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".pdf") || lower.endsWith(".mbtiles")
    }

    private fun isSqliteSidecar(name: String): Boolean {
        val lower = name.lowercase()
        return sqliteSidecarSuffixes.any { lower.endsWith(".mbtiles$it") }
    }

    private fun isCrashResidueName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".pdf.partial") || lower.endsWith(".mbtiles.partial")) {
            return true
        }
        val base = sqliteSidecarSuffixes
            .firstOrNull(lower::endsWith)
            ?.let { suffix -> lower.removeSuffix(suffix) }
            ?: return false
        return base.endsWith(".mbtiles") || base.endsWith(".mbtiles.partial")
    }
}
