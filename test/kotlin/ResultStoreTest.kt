package burp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ResultStoreTest {

    private lateinit var store: ResultStore

    @BeforeEach
    fun setUp() {
        store = ResultStore()
    }

    @Test
    fun `add stores request`() {
        val request = Request("GET / HTTP/1.1")

        store.add(request)

        assertEquals(1, store.count())
    }

    @Test
    fun `getAllRquests returns all added requests`() {
        val req1 = Request("GET /1 HTTP/1.1")
        val req2 = Request("GET /2 HTTP/1.1")

        store.add(req1)
        store.add(req2)

        val all = store.getAllRquests()
        assertEquals(2, all.size)
        assertTrue(all.contains(req1))
        assertTrue(all.contains(req2))
    }

    @Test
    fun `count returns zero for empty store`() {
        assertEquals(0, store.count())
    }

    @Test
    fun `clear removes all requests`() {
        store.add(Request("GET /1 HTTP/1.1"))
        store.add(Request("GET /2 HTTP/1.1"))

        store.clear()

        assertEquals(0, store.count())
    }

    @Test
    fun `getRequest returns request at valid index`() {
        val req = Request("GET / HTTP/1.1")
        store.add(req)

        val retrieved = store.getRequest(0)

        assertSame(req, retrieved)
    }

    @Test
    fun `getRequest returns null for negative index`() {
        store.add(Request("GET / HTTP/1.1"))

        assertNull(store.getRequest(-1))
    }

    @Test
    fun `getRequest returns null for index beyond size`() {
        store.add(Request("GET / HTTP/1.1"))

        assertNull(store.getRequest(5))
    }

    @Test
    fun `getResults respects limit`() {
        repeat(10) { store.add(Request("GET /$it HTTP/1.1")) }

        val results = store.getResults(limit = 3)

        assertEquals(3, results.size)
    }

    @Test
    fun `getResults respects offset`() {
        repeat(5) { i ->
            val req = Request("GET /$i HTTP/1.1")
            store.add(req)
        }

        val results = store.getResults(offset = 2, limit = 10)

        assertEquals(3, results.size)
    }

    @Test
    fun `getResults sorts by status code ascending`() {
        val req200 = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 200 OK\r\n\r\n" }
        val req404 = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 404 Not Found\r\n\r\n" }
        val req500 = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 500 Error\r\n\r\n" }

        store.add(req404)
        store.add(req200)
        store.add(req500)

        val results = store.getResults(sortBy = SortField.STATUS, descending = false)

        assertEquals(200, results[0].code)
        assertEquals(404, results[1].code)
        assertEquals(500, results[2].code)
    }

    @Test
    fun `getResults sorts by status code descending`() {
        val req200 = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 200 OK\r\n\r\n" }
        val req404 = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 404 Not Found\r\n\r\n" }
        val req500 = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 500 Error\r\n\r\n" }

        store.add(req404)
        store.add(req200)
        store.add(req500)

        val results = store.getResults(sortBy = SortField.STATUS, descending = true)

        assertEquals(500, results[0].code)
        assertEquals(404, results[1].code)
        assertEquals(200, results[2].code)
    }

    @Test
    fun `getResults sorts by length`() {
        val short = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 200 OK\r\n\r\na" }
        val long = Request("GET / HTTP/1.1").apply { response = "HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(100) }

        store.add(short)
        store.add(long)

        val results = store.getResults(sortBy = SortField.LENGTH, descending = true)

        assertTrue(results[0].length > results[1].length)
    }

    @Test
    fun `getResults sorts by time`() {
        val slow = Request("GET / HTTP/1.1").apply { time = 1000L }
        val fast = Request("GET / HTTP/1.1").apply { time = 100L }

        store.add(slow)
        store.add(fast)

        val results = store.getResults(sortBy = SortField.TIME, descending = false)

        assertEquals(100L, results[0].time)
        assertEquals(1000L, results[1].time)
    }

    @Test
    fun `getResults sorts by anomaly rank with nulls`() {
        val high = Request("GET / HTTP/1.1").apply { anomalyRank = 100 }
        val low = Request("GET / HTTP/1.1").apply { anomalyRank = 10 }
        val none = Request("GET / HTTP/1.1") // anomalyRank is null

        store.add(none)
        store.add(high)
        store.add(low)

        val results = store.getResults(sortBy = SortField.ANOMALY_RANK, descending = true)

        assertEquals(100, results[0].anomalyRank)
        assertEquals(10, results[1].anomalyRank)
        assertEquals(null, results[2].anomalyRank) // null treated as 0
    }

    @Test
    fun `getResults pagination with offset and limit`() {
        repeat(10) { i ->
            val req = Request("GET /$i HTTP/1.1").apply { time = i.toLong() }
            store.add(req)
        }

        // Get page 2 (items 3-5) sorted by time ascending
        val results = store.getResults(sortBy = SortField.TIME, descending = false, offset = 3, limit = 3)

        assertEquals(3, results.size)
        assertEquals(3L, results[0].time)
        assertEquals(4L, results[1].time)
        assertEquals(5L, results[2].time)
    }
}
