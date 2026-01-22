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
| Request IDs | Global counter | Avoid ID collisions across engines (see Issue #1) |
| Floodgates | Per-engine scope | Each engine has independent gates (documented limitation) |

## Issues Identified During Review

### Issue #1: Request ID Collision (Critical) - RESOLVED

Each engine has its own `lastRequestID` counter. With multiple engines, both could have requests with id=1, id=2, etc.

**Affected code:**
- `ResultStore.getRequest(id)` - returns first match, ambiguous
- MCP endpoints like `turbo://runs/current/results/5`
- Queue ID column in table

**Solution:** Assign global `globalId` when results are added to ResultStore.

- `globalId` is stable regardless of sort order (unlike list index)
- MCP client uses `globalId` for lookups - unified view, no engine reasoning needed
- Per-engine `request.id` unchanged - backwards compatible for scripts
- MCP responses include both: `{"globalId": 42, "id": 5, "engine": "smuggler", ...}`

### Issue #2: Engine Name Access (Fixed)

**Problem:** Design incorrectly used `request.engine` (Python wrapper) instead of `request._engine` (Kotlin engine).

**Fix:** Use `request._engine?.name ?: "default"` in RequestTableModel, or add `engineName: String` field to Request for cleaner access.

### Issue #3: Anomaly Ranking Race Condition (Fixed)

**Problem:** Each engine calls `calculateAnomalyRankings()` on completion, operating on the shared ResultStore. Multiple engines completing causes redundant work and potential UI races.

**Fix:** Move anomaly ranking to RunHandler. Calculate once when ALL engines have completed.

### Issue #4: Duration Tracking (Fixed)

**Problem:** Aggregated status needs total run duration, but each engine tracks its own `start` time.

**Fix:** RunHandler tracks `earliestStart` timestamp, updated when each engine is added. Duration = `now - earliestStart`.

### Issue #5: Floodgates Are Per-Engine (Documentation)

**Behavior:** Gate names are scoped to each engine. `engineA.openGate("sync")` won't affect `engineB`'s "sync" gate.

**Fix:** Document this limitation. Cross-engine synchronization requires Python-level coordination.

## Changes Required

### RunHandler.kt

Replace single engine with list, centralize anomaly ranking:

```kotlin
private val engines = mutableListOf<RequestEngine>()
private var earliestStart: Long = 0

fun addRequestEngine(engine: RequestEngine) {
    if (engines.isEmpty() || engine.start < earliestStart) {
        earliestStart = engine.start
    }
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
    // Calculate anomaly rankings once, after all engines complete
    calculateAnomalyRankings()
}

fun statusString(): String {
    if (statusOverride != null) return statusOverride!!
    if (engines.isEmpty()) return "Engine warming up..."

    val totalReqs = engines.sumOf { it.successfulRequests.get() }
    val totalQueued = engines.sumOf { it.requestQueue.size }
    val totalConns = engines.sumOf { it.connections.get() }
    val totalRetries = engines.sumOf { it.retries.get() }
    val totalFails = engines.sumOf { it.permaFails.get() }
    val duration = ceil(((System.nanoTime().toFloat() - earliestStart) / 1000000000).toDouble()).toInt()

    return String.format(
        "Engines: %d | Reqs: %d | Queued: %d | Duration: %d | Connections: %d | Retries: %d | Fails: %d%s",
        engines.size, totalReqs, totalQueued, duration, totalConns, totalRetries, totalFails,
        if (msg.isNotEmpty()) " | $msg" else ""
    )
}

private fun calculateAnomalyRankings() {
    // Move logic from RequestEngine.calculateAnomalyRankings() here
    // Operates on shared outputHandler once
}
```

### RequestEngine.kt

Add name field, remove per-engine anomaly ranking:

```kotlin
var name: String = "default"

// Remove or disable calculateAnomalyRankings() - now handled by RunHandler
```

### Request.kt

Add engineName and globalId fields (keep existing `id` unchanged):

```kotlin
var engineName: String = "default"
var globalId: Int = -1    // assigned by ResultStore, used for MCP lookups
// var id: Int = -1       // unchanged, per-engine queue position
```

Set engineName in RequestEngine.queue():
```kotlin
request.engineName = this.name
// request.id assignment unchanged
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

Add Engine column using the new engineName field:

```kotlin
companion object {
    internal val columns = listOf(
        "Row", "Payload", "Status", "Anomaly rank", "Words", "Length",
        "Time", "Arrival", "Label", "Queue ID", "Connection ID", "Engine"
    )
}

override fun getColumnClass(columnIndex: Int): Class<*> {
    return when (columnIndex) {
        // ... existing cases 0-10 ...
        11 -> String::class.java
        else -> throw RuntimeException("Invalid column requested")
    }
}

override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    val request = requests[rowIndex]
    return when (columnIndex) {
        // ... existing cases 0-10 ...
        11 -> request.engineName
        else -> throw RuntimeException("Invalid column requested")
    }
}
```

### ResultStore.kt

Assign global ID on result insertion:

```kotlin
class ResultStore : OutputHandler {
    private val results = CopyOnWriteArrayList<Request>()
    private val nextGlobalId = AtomicInteger(0)

    override fun add(req: Request) {
        req.globalId = nextGlobalId.incrementAndGet()
        results.add(req)
    }

    fun getRequest(globalId: Int): Request? {
        return results.find { it.globalId == globalId }
    }
}
```

### Tests

Update RunHandlerTest.kt for multi-engine behavior.

## Files Changed

| File | Change | Effort |
|------|--------|--------|
| `RunHandler.kt` | Multi-engine list, aggregated stats, centralized anomaly ranking | Medium |
| `RequestEngine.kt` | Add `name` field, remove per-engine anomaly ranking | Small |
| `Request.kt` | Add `engineName`, `globalId` fields | Tiny |
| `ScriptEnvironment.py` | Add `name` param, `addRequestEngine()` | Small |
| `RequestTableModel.kt` | Add Engine column | Small |
| `ResultStore.kt` | Add globalId assignment, update getRequest lookup | Small |
| `McpResourceHandlers.kt` | Use globalId for lookups, include both IDs in responses | Small |
| `McpToolHandlers.kt` | Use globalId in result responses | Tiny |
| `RunHandlerTest.kt` | Update tests | Small |

### MCP Resource Handlers

Update to use `globalId` for lookups and include both IDs in responses:

```kotlin
// McpResourceHandlers.kt - result listings
"globalId" to req.globalId,
"id" to req.id,  // per-engine queue order, kept for backwards compat
"engine" to req.engineName,

// getRequest lookup uses globalId
val request = run.store.getRequest(globalId)  // was getRequest(id)
```

## Not Changed

- **MCP layer** - Uses RunHandler abstraction; minor updates to use globalId for lookups

## Limitations

- **Floodgates are per-engine** - Gate names don't synchronize across engines. Use Python-level coordination for cross-engine synchronization.

## Estimated Effort

~120-150 lines of code changes (excluding Issue #1 resolution). Small-medium scope.
