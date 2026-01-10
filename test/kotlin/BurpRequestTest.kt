package burp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.URI

class BurpRequestTest {

    @Test
    fun `getEffectivePort returns 443 for https URL without explicit port`() {
        val url = URI("https://example.com").toURL()
        assertEquals(-1, url.port) // Verify the raw port is -1
        assertEquals(443, getEffectivePort(url))
    }

    @Test
    fun `getEffectivePort returns 80 for http URL without explicit port`() {
        val url = URI("http://example.com").toURL()
        assertEquals(-1, url.port)
        assertEquals(80, getEffectivePort(url))
    }

    @Test
    fun `getEffectivePort returns explicit port when specified`() {
        val url = URI("https://example.com:8443").toURL()
        assertEquals(8443, url.port)
        assertEquals(8443, getEffectivePort(url))
    }

    @Test
    fun `getEffectivePort returns explicit port 443 for https`() {
        val url = URI("https://example.com:443").toURL()
        assertEquals(443, url.port)
        assertEquals(443, getEffectivePort(url))
    }

    @Test
    fun `getEffectivePort returns explicit port 80 for http`() {
        val url = URI("http://example.com:80").toURL()
        assertEquals(80, url.port)
        assertEquals(80, getEffectivePort(url))
    }

    @Test
    fun `getEffectivePort handles custom ports`() {
        val url = URI("http://localhost:31338").toURL()
        assertEquals(31338, url.port)
        assertEquals(31338, getEffectivePort(url))
    }
}
