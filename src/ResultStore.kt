package burp

import java.util.concurrent.CopyOnWriteArrayList

enum class SortField {
    ID,
    STATUS,
    LENGTH,
    TIME,
    WORDCOUNT,
    ANOMALY_RANK,
    ARRIVAL
}

class ResultStore : OutputHandler {
    private val results = CopyOnWriteArrayList<Request>()

    override fun add(req: Request) {
        results.add(req)
    }

    override fun getAllRquests(): List<Request> = results.toList()

    fun count(): Int = results.size

    fun clear() {
        results.clear()
    }

    fun getRequest(id: Int): Request? {
        return results.find { it.id == id }
    }

    fun getResults(
        sortBy: SortField = SortField.ID,
        descending: Boolean = true,
        limit: Int = 100,
        offset: Int = 0
    ): List<Request> {
        val comparator: Comparator<Request> = when (sortBy) {
            SortField.ID -> compareBy { results.indexOf(it) }
            SortField.STATUS -> compareBy { it.code }
            SortField.LENGTH -> compareBy { it.length }
            SortField.TIME -> compareBy { it.time }
            SortField.WORDCOUNT -> compareBy { it.wordcount }
            SortField.ANOMALY_RANK -> compareBy { it.anomalyRank ?: 0 }
            SortField.ARRIVAL -> compareBy { it.arrival }
        }

        val sorted = if (descending) {
            results.sortedWith(comparator.reversed())
        } else {
            results.sortedWith(comparator)
        }

        return sorted.drop(offset).take(limit)
    }

    fun getUniqueStatusCodes(): Set<Int> {
        return results.map { it.code }.toSet()
    }
}
