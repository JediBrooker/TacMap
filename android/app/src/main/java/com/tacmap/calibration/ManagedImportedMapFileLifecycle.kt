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

    fun reconcile(
        managedParent: File,
        directories: List<File>,
        keeping: Set<File>,
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
            val validated = validateManagedMap(
                retained,
                roots,
                authoritativeOnly = true,
            ) ?: return false
            canonicalKeep += validated
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
