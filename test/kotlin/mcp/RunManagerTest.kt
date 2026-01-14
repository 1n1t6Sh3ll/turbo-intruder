package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class RunManagerTest {

    private lateinit var manager: RunManager

    @BeforeEach
    fun setup() {
        manager = RunManager()
    }

    @Test
    fun `startRun clears existing runs and creates new current run`() {
        val run1 = manager.startConcurrentRun()
        val run2 = manager.startRun()

        assertNull(manager.getRun(run1.id))
        assertNotNull(manager.currentRun)
        assertEquals(run2.id, manager.currentRun?.id)
    }

    @Test
    fun `startConcurrentRun preserves existing runs`() {
        val run1 = manager.startConcurrentRun()
        val run2 = manager.startConcurrentRun()

        assertNotNull(manager.getRun(run1.id))
        assertNotNull(manager.getRun(run2.id))
    }

    @Test
    fun `getRun with null returns current run`() {
        val run = manager.startRun()

        assertEquals(run.id, manager.getRun(null)?.id)
    }

    @Test
    fun `getRun with id returns specific run`() {
        val run1 = manager.startConcurrentRun()
        val run2 = manager.startConcurrentRun()

        assertEquals(run1.id, manager.getRun(run1.id)?.id)
        assertEquals(run2.id, manager.getRun(run2.id)?.id)
    }

    @Test
    fun `getAllRuns returns all runs`() {
        manager.startConcurrentRun()
        manager.startConcurrentRun()

        assertEquals(2, manager.getAllRuns().size)
    }

    @Test
    fun `stopRun aborts the run handler`() {
        val run = manager.startRun()

        val result = manager.stopRun(null)

        assertEquals("stopped", result)
    }

    @Test
    fun `stopRun returns not_found for unknown id`() {
        val result = manager.stopRun("unknown-id")

        assertEquals("not_found", result)
    }

    @Test
    fun `deleteRun removes run from manager`() {
        val run = manager.startConcurrentRun()

        val result = manager.deleteRun(run.id)

        assertEquals("deleted", result)
        assertNull(manager.getRun(run.id))
    }

    @Test
    fun `deleteAllRuns clears everything`() {
        manager.startConcurrentRun()
        manager.startConcurrentRun()
        manager.startConcurrentRun()

        val count = manager.deleteAllRuns()

        assertEquals(3, count)
        assertNull(manager.currentRun)
    }

    // Session-scoped tests

    @Test
    fun `startRun with sessionId only clears that session's runs`() {
        val sessionA = "session-a"
        val sessionB = "session-b"

        val runA = manager.startRun(sessionA)
        val runB = manager.startRun(sessionB)

        // Session A starts a new run - should not affect session B's run
        val runA2 = manager.startRun(sessionA)

        // Session B's run should still exist
        assertNotNull(manager.getRun(sessionB, runB.id))
        // Session A's old run should be gone
        assertNull(manager.getRun(sessionA, runA.id))
        // Session A's new run should be current
        assertEquals(runA2.id, manager.getRun(sessionA, null)?.id)
    }

    @Test
    fun `getAllRuns with sessionId returns only that session's runs`() {
        val sessionA = "session-a"
        val sessionB = "session-b"

        manager.startConcurrentRun(sessionA)
        manager.startConcurrentRun(sessionA)
        manager.startConcurrentRun(sessionB)

        assertEquals(2, manager.getAllRuns(sessionA).size)
        assertEquals(1, manager.getAllRuns(sessionB).size)
    }

    @Test
    fun `getRun with sessionId and null returns that session's current run`() {
        val sessionA = "session-a"
        val sessionB = "session-b"

        val runA = manager.startRun(sessionA)
        val runB = manager.startRun(sessionB)

        assertEquals(runA.id, manager.getRun(sessionA, null)?.id)
        assertEquals(runB.id, manager.getRun(sessionB, null)?.id)
    }

    @Test
    fun `getRun with explicit id returns run regardless of session`() {
        val sessionA = "session-a"
        val sessionB = "session-b"

        val runA = manager.startRun(sessionA)

        // Session B can access session A's run by explicit ID
        assertEquals(runA.id, manager.getRun(sessionB, runA.id)?.id)
    }

    @Test
    fun `deleteAllRuns with sessionId only deletes that session's runs`() {
        val sessionA = "session-a"
        val sessionB = "session-b"

        manager.startConcurrentRun(sessionA)
        manager.startConcurrentRun(sessionA)
        manager.startConcurrentRun(sessionB)

        val count = manager.deleteAllRuns(sessionA)

        assertEquals(2, count)
        assertEquals(0, manager.getAllRuns(sessionA).size)
        assertEquals(1, manager.getAllRuns(sessionB).size)
    }

    @Test
    fun `deleteRun can delete any run regardless of session`() {
        val sessionA = "session-a"
        val sessionB = "session-b"

        val runA = manager.startRun(sessionA)

        // Session B can delete session A's run
        val result = manager.deleteRun(sessionB, runA.id)

        assertEquals("deleted", result)
        assertNull(manager.getRun(sessionA, runA.id))
    }
}
