package com.tacmap.util

import org.junit.rules.ExternalResource

/**
 * Instrumented tests drive the sealed stores straight from the test thread, which in the app
 * only happens with MainActivity in front. Once any earlier test has paused a MainActivity,
 * DataKey stays relocked for the rest of the process and refuses those stores, so open it
 * the way MainActivity does on resume before each test.
 */
class MissionKeyUnlockRule : ExternalResource() {
    override fun before() = DataKey.unlock()
}
