package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.fasterxml.jackson.databind.ObjectMapper

class ErrorHandlingTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `formatErrorWithStackTrace includes error message`() {
        val exception = RuntimeException("Test error message")
        val result = formatErrorWithStackTrace(exception)

        assertTrue(result.containsKey("error"))
        assertEquals("Test error message", result["error"])
    }

    @Test
    fun `formatErrorWithStackTrace includes stack trace`() {
        val exception = RuntimeException("Test error")
        val result = formatErrorWithStackTrace(exception)

        assertTrue(result.containsKey("stack_trace"))
        val stackTrace = result["stack_trace"] as String
        assertTrue(stackTrace.contains("RuntimeException"))
        assertTrue(stackTrace.contains("Test error"))
        assertTrue(stackTrace.contains("at mcp.ErrorHandlingTest"))
    }

    @Test
    fun `formatErrorWithStackTrace handles nested exceptions`() {
        val cause = IllegalArgumentException("Root cause")
        val exception = RuntimeException("Wrapper error", cause)
        val result = formatErrorWithStackTrace(exception)

        val stackTrace = result["stack_trace"] as String
        assertTrue(stackTrace.contains("RuntimeException"))
        assertTrue(stackTrace.contains("Wrapper error"))
        assertTrue(stackTrace.contains("Caused by"))
        assertTrue(stackTrace.contains("IllegalArgumentException"))
        assertTrue(stackTrace.contains("Root cause"))
    }

    @Test
    fun `formatErrorWithStackTrace handles null message`() {
        val exception = RuntimeException()
        val result = formatErrorWithStackTrace(exception)

        assertTrue(result.containsKey("error"))
        // null message should be converted to string or handled gracefully
        assertNotNull(result["error"])
    }
}
