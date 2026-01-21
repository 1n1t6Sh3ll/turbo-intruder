package burp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class RunHandlerTest {

    private lateinit var handler: RunHandler

    @BeforeEach
    fun setUp() {
        handler = RunHandler()
    }

    @Test
    fun `isRunning returns false initially`() {
        assertFalse(handler.isRunning())
    }

    @Test
    fun `statusString returns warming up when no engine`() {
        assertEquals("Engine warming up...", handler.statusString())
    }

    @Test
    fun `hasFinished returns false when no engine`() {
        assertFalse(handler.hasFinished())
    }

    @Test
    fun `setRequestEngine sets running to true`() {
        val engine = TestRequestEngine()

        handler.setRequestEngine(engine)

        assertTrue(handler.isRunning())
    }

    @Test
    fun `hasFinished returns false when engine state below 3`() {
        val engine = TestRequestEngine()
        engine.setRunState(1) // live

        handler.setRequestEngine(engine)

        assertFalse(handler.hasFinished())
    }

    @Test
    fun `hasFinished returns true when engine state is 3 (cancelled)`() {
        val engine = TestRequestEngine()
        engine.setRunState(3) // cancelled

        handler.setRequestEngine(engine)

        assertTrue(handler.hasFinished())
    }

    @Test
    fun `hasFinished returns true when engine state is 4 (completed)`() {
        val engine = TestRequestEngine()
        engine.setRunState(4) // completed

        handler.setRequestEngine(engine)

        assertTrue(handler.hasFinished())
    }

    @Test
    fun `abort sets running to false`() {
        val engine = TestRequestEngine()
        handler.setRequestEngine(engine)
        assertTrue(handler.isRunning())

        handler.abort()

        assertFalse(handler.isRunning())
    }

    @Test
    fun `overrideStatus replaces status string`() {
        val engine = TestRequestEngine()
        handler.setRequestEngine(engine)

        handler.overrideStatus("Custom status")

        assertEquals("Custom status", handler.statusString())
    }

    @Test
    fun `setMessage updates msg field`() {
        handler.setMessage("test message")

        assertEquals("test message", handler.msg)
    }

    @Test
    fun `statusString includes message when engine present`() {
        val engine = TestRequestEngine()
        handler.setRequestEngine(engine)
        handler.setMessage("processing")

        val status = handler.statusString()

        assertTrue(status.contains("processing"))
    }

    @Test
    fun `hasError returns false initially`() {
        assertFalse(handler.hasError())
    }

    @Test
    fun `hasError returns true after overrideStatus called`() {
        handler.overrideStatus("User Python error: something failed")

        assertTrue(handler.hasError())
    }

    @Test
    fun `hasError returns false when overrideStatus called with isError false`() {
        handler.overrideStatus("Non-error status", isError = false)

        assertFalse(handler.hasError())
    }
}
