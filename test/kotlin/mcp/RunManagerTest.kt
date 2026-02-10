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
}
