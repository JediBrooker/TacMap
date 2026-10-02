package com.tacmap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dialogs, sheets and menus draw in their own windows, which the activity's
 * night-mode layer does not reach. Every one must go through the wrappers in
 * `ui/NightMode.kt` (or call NightWindowFilter itself) or it would light up
 * white at night.
 */
class NightModeSourceTest {
    private val sourceRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            listOf("src/main/java", "app/src/main/java", "android/app/src/main/java").forEach { relative ->
                val candidate = File(dir, relative)
                if (File(candidate, "com/tacmap").isDirectory) return@lazy candidate
            }
            dir = dir?.parentFile
        }
        error("Could not locate the app sources")
    }

    private val sources: List<Pair<String, String>> by lazy {
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }
            .filterNot { (path, _) -> path == "com/tacmap/ui/NightMode.kt" }
            .toList()
    }

    @Test
    fun windowComposablesGoThroughNightModeWrappers() {
        val forbidden = listOf(
            Regex("""import androidx\.compose\.material3\.(AlertDialog|ModalBottomSheet|DropdownMenu)\s*$""", RegexOption.MULTILINE),
            Regex("""import androidx\.compose\.ui\.window\.(Dialog|Popup)\s*$""", RegexOption.MULTILINE),
            Regex("""androidx\.compose\.material3\.(AlertDialog|ModalBottomSheet|DropdownMenu|BasicAlertDialog)\("""),
            Regex("""androidx\.compose\.ui\.window\.(Dialog|Popup)\("""),
        )
        val offenders = sources.flatMap { (path, text) ->
            forbidden.flatMap { pattern -> pattern.findAll(text).map { "$path: ${it.value.trim()}" }.toList() }
        }
        assertEquals("Use com.tacmap.ui wrappers instead", emptyList<String>(), offenders)
    }

    @Test
    fun wildcardMaterialImportsAlsoImportTheWrappers() {
        val offenders = sources.filter { (_, text) ->
            text.contains("import androidx.compose.material3.*")
        }.flatMap { (path, text) ->
            listOf("AlertDialog", "ModalBottomSheet", "DropdownMenu")
                .filter { Regex("""(?<![\w.])$it\(""").containsMatchIn(text) }
                .filterNot { text.contains("import com.tacmap.ui.$it") }
                .map { "$path uses $it through a wildcard import" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun exposedDropdownMenusApplyTheFilterThemselves() {
        val offenders = sources.filter { (_, text) ->
            val menus = Regex("""(?<![\w.])ExposedDropdownMenu\(""").findAll(text).count()
            menus > 0 && Regex("""NightWindowFilter\(\)""").findAll(text).count() < menus
        }.map { it.first }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun menuPopupsCloseOnSystemBack() {
        // targetSdk 36 routes BACK through OnBackInvokedDispatcher, which compose 1.6 popups
        // never register with, so every menu has to do it itself
        val nightMode = File(sourceRoot, "com/tacmap/ui/NightMode.kt").readText()
        val wrapper = nightMode.substringAfter("fun DropdownMenu(").substringBefore("\n}\n")
        assertTrue(wrapper.contains("PopupBackDismiss(onDismissRequest)"))
        assertTrue(nightMode.contains("registerOnBackInvokedCallback"))

        val offenders = sources.filter { (_, text) ->
            val menus = Regex("""(?<![\w.])ExposedDropdownMenu\(""").findAll(text).count()
            menus > 0 && Regex("""PopupBackDismiss \{""").findAll(text).count() < menus
        }.map { it.first }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun matrixKeepsOnlyScaledLuminanceInRed() {
        val m = NightModeFilter.matrix(0.5f)
        assertEquals(20, m.size)
        assertEquals(0.5f, m[0] + m[1] + m[2], 1e-6f)
        assertTrue((5 until 15).all { m[it] == 0f })
        assertEquals(1f, m[18], 0f)
        assertEquals(1f, NightModeFilter.matrix(3f).let { it[0] + it[1] + it[2] }, 1e-6f)
    }
}
