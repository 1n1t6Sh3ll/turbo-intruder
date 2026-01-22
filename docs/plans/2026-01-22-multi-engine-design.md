# Multi-Engine Support

Support multiple named request engines in a single script for connection isolation, protocol comparison, and multi-target scanning.

## Use Cases

- **Request smuggling verification** - Send smuggle payload on engine A, verify with victim request on fresh connection via engine B
- **Session/state pollution testing** - Test whether server-side state from one connection affects another
- **Connection coalescing attacks** - HTTP/2 connection reuse across different virtual hosts
- **Protocol comparison** - Compare HTTP/1.1 vs HTTP/2 behavior on same target
- **Multi-target scanning** - Coordinated attacks across multiple hosts

## Script Interface

```python
def queueRequests(target, wordlists):
    # Create engines with different configs
    smuggler = RequestEngine(endpoint=target, name="smuggler",
                             engine=Engine.THREADED, concurrentConnections=1)
    verifier = RequestEngine(endpoint=target, name="verifier",
                             engine=Engine.BURP)

    smuggler.queue(smugglePayload)
    smuggler.complete()  # wait for poison to land

    verifier.queue(victimRequest)
    verifier.complete()

def handleResponse(req, interesting):
    # req.engine.name available for filtering
    if req.engine.name == "verifier" and "smuggled" in req.response:
        table.add(req)
```

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Engine coordination | Independent (manual) | KISS - scripts manage timing via `complete()` |
| Result storage | Merged with engine tag | Simple, filter by `req.engine.name` in callbacks |
| Status display | Aggregated totals | Clean UI: "Engines: 2 \| Reqs: 500 \| ..." |
| Engine column in table | Always shown | Needed to disambiguate Queue ID and Connection ID |

## Changes Required

### RunHandler.kt

Replace single engine with list:

```kotlin
private val engines = mutableListOf<RequestEngine>()

fun addRequestEngine(engine: RequestEngine) {
    engines.add(engine)
    running = true
}

fun hasFinished(): Boolean {
    if (engines.isEmpty()) return scriptCompleted
    return engines.all { it.runState.get() >= 3 }
}

fun abort() {
    running = false
    engines.forEach { it.cancel() }
}

fun setComplete() {
    engines.forEach { it.showStats(-1) }
}

fun statusString(): String {
    if (statusOverride != null) return statusOverride!!
    if (engines.isEmpty()) return "Engine warming up..."

    val totalReqs = engines.sumOf { it.successfulRequests.get() }
    val totalQueued = engines.sumOf { it.requestQueue.size }
    val totalConns = engines.sumOf { it.connections.get() }
    val totalRetries = engines.sumOf { it.retries.get() }
    val totalFails = engines.sumOf { it.permaFails.get() }
    val duration = /* max duration across engines */

    return String.format(
        "Engines: %d | Reqs: %d | Queued: %d | Connections: %d | Retries: %d | Fails: %d%s",
        engines.size, totalReqs, totalQueued, totalConns, totalRetries, totalFails,
        if (msg.isNotEmpty()) " | $msg" else ""
    )
}
```

### RequestEngine.kt

Add name field:

```kotlin
var name: String = "default"
```

### ScriptEnvironment.py

Add name parameter and use addRequestEngine:

```python
class RequestEngine:
    def __init__(self, endpoint, name=None, callback=None, ...):
        self.name = name or "default"
        self.engine.name = self.name
        # ...
        handler.addRequestEngine(self.engine)  # was setRequestEngine
```

### RequestTableModel.kt

Add Engine column:

```kotlin
companion object {
    internal val columns = listOf(
        "Row", "Payload", "Status", "Anomaly rank", "Words", "Length",
        "Time", "Arrival", "Label", "Queue ID", "Connection ID", "Engine"
    )
}

override fun getColumnClass(columnIndex: Int): Class<*> {
    return when (columnIndex) {
        // ... existing cases ...
        11 -> String::class.java
        else -> throw RuntimeException("Invalid column requested")
    }
}

override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    val request = requests[rowIndex]
    return when (columnIndex) {
        // ... existing cases ...
        11 -> (request.engine as? RequestEngine)?.name ?: "default"
        else -> throw RuntimeException("Invalid column requested")
    }
}
```

Note: Getting engine name requires casting since `request.engine` is `Any?` (the Python wrapper). May need to add a `engineName: String` field to Request instead for cleaner access.

### Tests

Update RunHandlerTest.kt for multi-engine behavior.

## Files Changed

| File | Change | Effort |
|------|--------|--------|
| `RunHandler.kt` | Multi-engine list, aggregated stats | Medium |
| `RequestEngine.kt` | Add `name` field | Tiny |
| `ScriptEnvironment.py` | Add `name` param, `addRequestEngine()` | Small |
| `RequestTableModel.kt` | Add Engine column | Small |
| `RunHandlerTest.kt` | Update tests | Small |

## Not Changed

- **MCP layer** - Uses RunHandler abstraction, no direct engine access
- **ResultStore** - Already shared across engines
- **Request.kt** - Already has `engine` field pointing to Python wrapper

## Estimated Effort

~100-120 lines of code changes. Small-medium scope.
