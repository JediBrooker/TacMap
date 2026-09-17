package com.tacmap.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapScreenFlowLifecycleTest {
    @Test fun remoteUpdateCollectorFollowsActiveManagerAndVisibleLifecycle() {
        val source = sourceText("android/app/src/main/java/com/tacmap/map/MapScreen.kt")

        assertTrue(source.contains("LaunchedEffect(syncManager, lifecycleOwner)"))
        assertTrue(source.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
        assertTrue(source.contains("syncManager.remoteUpdates.collect"))
        assertFalse(
            "a Unit-keyed effect would keep collecting a replaced SyncManager",
            source.contains("LaunchedEffect(Unit) {\n        syncManager.remoteUpdates.collect"),
        )
    }

    @Test fun hotCompassFlowsAreContainedOutsideTheMapScreenRoot() {
        val screen = sourceText("android/app/src/main/java/com/tacmap/map/MapScreen.kt")
        val chrome = sourceText("android/app/src/main/java/com/tacmap/map/MapScreenChrome.kt")

        assertFalse(screen.contains("vm.mapBearingDegrees.collectAsState()"))
        assertFalse(screen.contains("vm.headingService.headingNorthReference.collectAsState()"))
        assertFalse(screen.contains("vm.headingService.headingDegrees.collectAsState()"))
        assertTrue(screen.contains("MapCompassChip("))
        assertTrue(screen.contains("deviceHeadingDegrees = vm.headingService.headingDegrees"))
        assertTrue(chrome.contains("vm.mapBearingDegrees.collectAsState()"))
        assertTrue(chrome.contains("vm.headingService.headingNorthReference.collectAsState()"))
    }

    private fun sourceText(relativePath: String): String {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Could not find $relativePath")
    }
}
