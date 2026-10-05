package com.tacmap.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The relock gate DataKey runs its general cache through. */
class DataKeyCacheTest {
    private val dek = ByteArray(32) { (it + 1).toByte() }
    private var unwraps = 0

    private fun unwrap(): ByteArray {
        unwraps += 1
        return dek.copyOf()
    }

    @Test fun firstUseUnwrapsOnceThenHandsOutCopies() {
        val cache = DataKeyCache()
        val first = cache.get(::unwrap)
        first.fill(0)
        assertArrayEquals("a caller zeroing its copy doesn't touch the cache", dek, cache.get(::unwrap))
        assertEquals(1, unwraps)
        assertTrue(cache.isCached)
        assertFalse(cache.isRelocked)
    }

    @Test fun lockedCacheRefusesInsteadOfUnwrappingAgain() {
        val cache = DataKeyCache()
        cache.get(::unwrap).fill(0)
        cache.lock()
        // every late background write after the pause, none of them may refill it
        repeat(3) {
            assertThrows(DataKey.LockedException::class.java) { cache.get(::unwrap) }
        }
        assertEquals("no unwrap behind the lock", 1, unwraps)
        assertFalse(cache.isCached)
        assertTrue(cache.isRelocked)
    }

    @Test fun onlyTheExplicitUnlockReopensIt() {
        val cache = DataKeyCache()
        cache.lock()
        assertThrows(DataKey.LockedException::class.java) { cache.get(::unwrap) }
        assertEquals(0, unwraps)
        cache.unlock(::unwrap)
        assertEquals(1, unwraps)
        assertTrue(cache.isCached)
        assertFalse(cache.isRelocked)
        assertArrayEquals(dek, cache.get(::unwrap))
        assertEquals("served from the cache now", 1, unwraps)
    }

    @Test fun failedUnlockKeepsTheGateShut() {
        val cache = DataKeyCache()
        cache.lock()
        assertThrows(DataKey.LockedException::class.java) {
            cache.unlock { throw DataKey.LockedException() }
        }
        assertThrows(IOException::class.java) { cache.unlock { throw IOException("keystore hiccup") } }
        assertTrue(cache.isRelocked)
        assertThrows(DataKey.LockedException::class.java) { cache.get(::unwrap) }
        assertEquals(0, unwraps)
    }

    @Test fun dropForcesAFreshUnwrapWithoutRelocking() {
        val cache = DataKeyCache()
        cache.get(::unwrap).fill(0)
        cache.drop()
        assertFalse(cache.isCached)
        assertFalse(cache.isRelocked)
        cache.get(::unwrap).fill(0)
        assertEquals(2, unwraps)
    }

    @Test fun awaitUnlockedParksBehindTheLockTillTheExplicitUnlock() = runBlocking {
        val cache = DataKeyCache()
        // never locked: straight back, nothing unwrapped for it
        withTimeout(1_000) { cache.awaitUnlocked() }
        cache.lock()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { cache.awaitUnlocked(); true }
        yield()
        assertFalse("went through the lock", waiter.isCompleted)
        assertThrows(DataKey.LockedException::class.java) { cache.unlock { throw DataKey.LockedException() } }
        yield()
        assertFalse("a failed unlock let it through", waiter.isCompleted)
        assertEquals("waiting never unwraps", 0, unwraps)
        cache.unlock(::unwrap)
        assertTrue(withTimeout(1_000) { waiter.await() })
        assertEquals(1, unwraps)
        // and a drop isn't a lock, nothing to wait for
        cache.drop()
        withTimeout(1_000) { cache.awaitUnlocked() }
    }

    @Test fun aCancelGetsOutOfTheWait() = runBlocking {
        val cache = DataKeyCache()
        cache.lock()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { cache.awaitUnlocked() }
        waiter.cancel()
        waiter.join()
        assertTrue(waiter.isCancelled)
        assertTrue(cache.isRelocked)
    }

    @Test fun lockZeroesTheCachedBytes() {
        val cache = DataKeyCache()
        cache.get(::unwrap).fill(0)
        val field = DataKeyCache::class.java.getDeclaredField("cached").apply { isAccessible = true }
        val held = field.get(cache) as ByteArray
        cache.lock()
        assertTrue(held.all { it == 0.toByte() })
    }
}
