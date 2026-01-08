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
}
