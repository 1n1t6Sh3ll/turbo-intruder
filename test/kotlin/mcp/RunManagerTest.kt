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
    fun `startRun creates a new run and returns it`() {
        val run = manager.startRun()
        assertNotNull(run.id)
        assertNotNull(manager.getRun(run.id))
    }

    @Test
    fun `startRun preserves existing runs`() {
        val run1 = manager.startRun()
        val run2 = manager.startRun()

        assertNotNull(manager.getRun(run1.id))
        assertNotNull(manager.getRun(run2.id))
    }

    @Test
    fun `getRun returns null for unknown id`() {
        assertNull(manager.getRun("unknown-id"))
    }

    @Test
    fun `stopRun aborts the run handler`() {
        val run = manager.startRun()
        val result = manager.stopRun(run.id)
        assertEquals("stopped", result)
    }

    @Test
    fun `stopRun returns not_found for unknown id`() {
        val result = manager.stopRun("unknown-id")
        assertEquals("not_found", result)
    }

    @Test
    fun `deleteRun removes run from manager`() {
        val run = manager.startRun()
        val result = manager.deleteRun(run.id)

        assertEquals("deleted", result)
        assertNull(manager.getRun(run.id))
    }

    @Test
    fun `deleteRun returns not_found for unknown id`() {
        val result = manager.deleteRun("unknown-id")
        assertEquals("not_found", result)
    }

    @Test
    fun `evicts oldest completed run when cap is exceeded`() {
        val manager = RunManager(maxCompletedRuns = 2)
        val run1 = manager.startRun()
        run1.handler.markScriptCompleted()
        val run2 = manager.startRun()
        run2.handler.markScriptCompleted()
        val run3 = manager.startRun()
        run3.handler.markScriptCompleted()

        // 3 completed runs, cap is 2 — starting a new run triggers eviction of oldest
        val run4 = manager.startRun()

        assertNull(manager.getRun(run1.id))
        assertNotNull(manager.getRun(run2.id))
        assertNotNull(manager.getRun(run3.id))
        assertNotNull(manager.getRun(run4.id))
    }

    @Test
    fun `does not evict runs that are still running`() {
        val manager = RunManager(maxCompletedRuns = 2)
        val run1 = manager.startRun() // still running - not completed
        val run2 = manager.startRun()
        run2.handler.markScriptCompleted()
        val run3 = manager.startRun()
        run3.handler.markScriptCompleted()
        val run4 = manager.startRun()
        run4.handler.markScriptCompleted()

        // 3 completed runs, cap 2 — starting a new run evicts oldest completed (run2), not run1
        val run5 = manager.startRun()

        assertNotNull(manager.getRun(run1.id)) // still running, kept
        assertNull(manager.getRun(run2.id))     // oldest completed, evicted
        assertNotNull(manager.getRun(run3.id))
        assertNotNull(manager.getRun(run4.id))
        assertNotNull(manager.getRun(run5.id))
    }

    @Test
    fun `evicted run is reported as evicted not unknown`() {
        val manager = RunManager(maxCompletedRuns = 1)
        val run1 = manager.startRun()
        run1.handler.markScriptCompleted()
        val run2 = manager.startRun()
        run2.handler.markScriptCompleted()

        // 2 completed, cap 1 — triggers eviction of run1
        val run3 = manager.startRun()

        assertNull(manager.getRun(run1.id))
        assertTrue(manager.isEvicted(run1.id))
        assertFalse(manager.isEvicted(run2.id))
        assertFalse(manager.isEvicted("never-existed"))
    }

    @Test
    fun `default cap is 100`() {
        val manager = RunManager()
        val runs = (1..101).map { manager.startRun().also { r -> r.handler.markScriptCompleted() } }

        // 101 completed, cap 100 — starting a new run evicts the first
        val triggerRun = manager.startRun()

        assertNull(manager.getRun(runs.first().id))
        assertNotNull(manager.getRun(runs.last().id))
        assertNotNull(manager.getRun(triggerRun.id))
    }
}
