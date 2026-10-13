package com.example.sync.authclient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineGuardTest {
    private val day = 24L * 60 * 60 * 1000
    private val clock = FakeClock()
    private val store = MemoryOfflineStateStore()
    private val guard = OfflineGuard(store, clock)

    @Test
    fun `locked until the first sync`() {
        assertEquals(LockStatus.Locked(LockStatus.Reason.NEVER_SYNCED), guard.status())
    }

    @Test
    fun `unlocked within the limit and locked after it`() {
        guard.recordSync(serverTimeSec = 100, maxOfflineDays = 7)
        clock.advance(6 * day)
        assertIs<LockStatus.Unlocked>(guard.status())
        clock.advance(2 * day)
        assertEquals(LockStatus.Locked(LockStatus.Reason.OFFLINE_TOO_LONG), guard.status())
    }

    @Test
    fun `rolling the wall clock back does not extend the window`() {
        guard.recordSync(100, 7)
        clock.advance(8 * day)
        clock.wall -= 30 * day // 時計を巻き戻す(同一ブート)
        assertTrue(guard.isLocked())
    }

    @Test
    fun `rolling the wall clock back after reboot is limited by the high-water mark`() {
        guard.recordSync(100, 7)
        clock.advance(8 * day)
        assertTrue(guard.isLocked()) // 高水位を記録
        clock.boot = "boot-2"
        clock.elapsed = 1_000
        clock.wall -= 30 * day
        assertTrue(guard.isLocked())
    }

    @Test
    fun `moving the wall clock forward locks earlier (fail safe)`() {
        guard.recordSync(100, 7)
        clock.wall += 10 * day
        assertTrue(guard.isLocked())
    }

    @Test
    fun `new sync unlocks and policy days are taken from the server`() {
        guard.recordSync(100, 1)
        clock.advance(2 * day)
        assertTrue(guard.isLocked())
        guard.recordSync(200, 3)
        clock.advance(2 * day)
        assertIs<LockStatus.Unlocked>(guard.status())
        assertEquals(200, guard.lastSyncServerTimeSec())
    }
}
