# MCP Server Design for Turbo Intruder

## Overview

Add an MCP server to Turbo Intruder that enables Claude Code to start/stop runs and query results. Works in both standalone mode and when loaded as a Burp Suite extension.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Turbo Intruder                        │
│  ┌─────────────────────────────────────────────────┐    │
│  │              MCP Server (port 31337)             │    │
│  │  ┌─────────────┐  ┌──────────────────────────┐  │    │
│  │  │ HTTP/SSE    │  │      Tool Handlers       │  │    │
│  │  │ Transport   │  │  - start_run             │  │    │
│  │  │ (MCP SDK)   │  │  - start_concurrent_run  │  │    │
│  │  └─────────────┘  │  - stop_run              │  │    │
│  │                   │  - get_status            │  │    │
│  │                   │  - get_results           │  │    │
│  │                   │  - get_request_detail    │  │    │
│  │                   │  - delete_run            │  │    │
│  │                   │  - delete_all_runs       │  │    │
│  │                   └──────────────────────────┘  │    │
│  └─────────────────────────────────────────────────┘    │
│                           │                              │
│                           ▼                              │
│  ┌─────────────────────────────────────────────────┐    │
│  │               RunManager                         │    │
│  │   - currentRun: ActiveRun?                      │    │
│  │   - concurrentRuns: Map<String, ActiveRun>      │    │
│  │   - startRun() → clears all, starts new         │    │
│  │   - startConcurrentRun() → returns runId        │    │
│  │   - getRun(runId?) → current or by id           │    │
│  └─────────────────────────────────────────────────┘    │
│                           │                              │
│           ┌───────────────┼───────────────┐              │
│           ▼               ▼               ▼              │
│      ActiveRun       ActiveRun       ActiveRun          │
│   (RunHandler +    (RunHandler +   (RunHandler +        │
│    ResultStore)     ResultStore)    ResultStore)        │
└─────────────────────────────────────────────────────────┘
```

## Key Decisions

- **Transport**: HTTP on `localhost:31337` (required for Burp extension context)
- **SDK**: MCP Java SDK for protocol compliance
- **Concurrency**: Two modes - simple (single run) and concurrent (multiple runs with IDs)
- **Default behavior**: `start_run` clears previous runs; `start_concurrent_run` preserves them

## MCP Tools

### start_run

Starts a new run, stopping and deleting any existing runs first.

```
Parameters:
  - script: string (Python script content)
  - base_request: string (HTTP request template with %s placeholders)
  - endpoint: string (e.g., "https://example.com:443")
  - base_input: string (optional, default value for first %s)

Returns:
  - status: "started"
```

### start_concurrent_run

Starts a new run without affecting existing runs.

```
Parameters:
  - script: string
  - base_request: string
  - endpoint: string
  - base_input: string (optional)

Returns:
  - run_id: string (UUID)
  - status: "started"
```

### stop_run

Aborts a run.

```
Parameters:
  - run_id: string (optional, defaults to current run)

Returns:
  - status: "stopped" | "not_found" | "no_current_run"
```

### get_status

Gets run status and progress.

```
Parameters:
  - run_id: string (optional, defaults to current run)

Returns:
  - run_id: string
  - running: boolean
  - finished: boolean
  - status_message: string
  - result_count: int
```

### get_results

Queries results with sorting and pagination.

```
Parameters:
  - run_id: string (optional, defaults to current run)
  - sort_by: "id" | "status" | "length" | "time" | "wordcount" | "anomaly_rank" | "arrival" (default: "id")
  - descending: boolean (default: true)
  - limit: int (default: 100)
  - offset: int (default: 0)

Returns:
  - results: array of {id, status, length, time, wordcount, words, label}
  - total_count: int
```

### get_request_detail

Gets full request/response for a specific result.

```
Parameters:
  - run_id: string (optional, defaults to current run)
  - request_id: int

Returns:
  - request: string (full HTTP request)
  - response: string (full HTTP response)
  - status: int
  - length: int
  - time: long
  - words: array of strings
```

### delete_run

Deletes a run and frees memory.

```
Parameters:
  - run_id: string (optional, defaults to current run)

Returns:
  - status: "deleted" | "not_found" | "no_current_run"
```

### delete_all_runs

Deletes all runs.

```
Parameters: (none)

Returns:
  - deleted_count: int
```

## Usage Patterns

### Simple mode (99% of usage)

```python
# Start a run (clears any previous)
start_run(script="...", base_request="...", endpoint="https://target.com")

# Check progress
get_status()  # → {running: true, result_count: 150, ...}

# Get results
get_results(sort_by="status", limit=50)

# Get details for interesting result
get_request_detail(request_id=42)
```

### Concurrent mode

```python
# Start multiple runs
id1 = start_concurrent_run(script=script1, ...)["run_id"]
id2 = start_concurrent_run(script=script2, ...)["run_id"]

# Query specific runs
get_status(run_id=id1)
get_results(run_id=id2)

# Clean up
delete_run(run_id=id1)
```

## Integration

### Standalone mode

```kotlin
fun main(args: Array<String>) {
    if (args.contains("--mcp")) {
        val mcpServer = TurboMcpServer(port = 31337)
        mcpServer.start()
        // Block until shutdown
    } else {
        // Existing CLI behavior
    }
}
```

### Burp extension mode

```kotlin
class BurpExtender : IBurpExtender {
    private var mcpServer: TurboMcpServer? = null

    override fun registerExtenderCallbacks(callbacks: IBurpExtenderCallbacks) {
        // ... existing setup ...

        mcpServer = TurboMcpServer(port = 31337)
        mcpServer?.start()

        callbacks.registerExtensionStateListener {
            mcpServer?.stop()
        }
    }
}
```

## New Files

- `src/mcp/TurboMcpServer.kt` - HTTP server setup using MCP SDK
- `src/mcp/RunManager.kt` - Manages current and concurrent runs
- `src/mcp/ActiveRun.kt` - Bundles RunHandler + ResultStore + metadata
- `src/mcp/ToolHandlers.kt` - Implements the 8 tool handlers

## Dependencies

```gradle
implementation 'io.modelcontextprotocol.sdk:mcp:0.10.0'
```

## Error Handling

- Invalid script → Return error with Python exception message
- Unknown run_id → Return `"not_found"` status
- No current run when required → Return `"no_current_run"` status
- Port 31337 in use → Log error, don't crash extension
- Python execution errors → Captured in status_message, run marked finished

## Testing Strategy

### Unit tests
- `RunManagerTest` - create/get/stop/delete runs, concurrent access
- `ActiveRunTest` - lifecycle states, result storage
- `ToolHandlersTest` - each tool with valid/invalid inputs

### Integration tests
- Start MCP server, call tools via HTTP, verify responses
- Full run lifecycle: start → poll status → get results → delete
