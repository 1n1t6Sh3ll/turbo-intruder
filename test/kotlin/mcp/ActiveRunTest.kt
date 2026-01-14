package mcp

import burp.ResultStore
import burp.RunHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ActiveRunTest {

    @Test
    fun `creates ActiveRun with unique id`() {
        val run1 = ActiveRun("test-session")
        val run2 = ActiveRun("test-session")

        assertNotNull(run1.id)
        assertNotNull(run2.id)
        assertNotEquals(run1.id, run2.id)
    }

    @Test
    fun `provides access to RunHandler and ResultStore`() {
        val run = ActiveRun("test-session")

        assertNotNull(run.handler)
        assertNotNull(run.store)
    }

    @Test
    fun `tracks creation time`() {
        val before = System.currentTimeMillis()
        val run = ActiveRun("test-session")
        val after = System.currentTimeMillis()

        assertTrue(run.createdAt >= before)
        assertTrue(run.createdAt <= after)
    }
}
