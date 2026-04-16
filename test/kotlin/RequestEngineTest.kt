package burp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class RequestEngineTest {

    private lateinit var engine: TestRequestEngine

    @BeforeEach
    fun setup() {
        engine = TestRequestEngine()
        engine.start()
    }

    @Test
    fun `queue stores connectionId on request`() {
        engine.queue("GET / HTTP/1.1\r\nHost: test.local\r\n\r\n", emptyList<Any>(), 0, null, null, "", 0, 1000, emptyList(), 0, null, null, true, "my-conn")

        val queued = engine.requestQueue.poll()
        assertNotNull(queued)
        assertEquals("my-conn", queued!!.connectionId)
    }

    @Test
    fun `queue allows null connectionId`() {
        engine.queue("GET / HTTP/1.1\r\nHost: test.local\r\n\r\n", emptyList<Any>(), 0, null, null, "", 0, 1000, emptyList(), 0, null, null, true, null)

        val queued = engine.requestQueue.poll()
        assertNotNull(queued)
        assertNull(queued!!.connectionId)
    }
}
