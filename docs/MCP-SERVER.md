# MCP Server Quick Reference

## Starting the Server

**Standalone mode:**
```bash
java -jar build/libs/turbo-intruder.jar --mcp
```

**Burp extension:** Auto-starts when extension loads.

Server listens on `localhost:31337` using HTTP/SSE transport.

---

## MCP Protocol Flow

1. **Connect to SSE** → `GET /sse` returns session ID
2. **Initialize** → POST to `/mcp/message?sessionId=XXX`
3. **Call tools/resources** → POST messages, responses via SSE

---

## Available Tools

| Tool | Description |
|------|-------------|
| `start_run` | Start attack (clears previous) |
| `start_concurrent_run` | Start parallel attack |
| `stop_run` | Stop a running attack |
| `delete_run` | Remove run and results |
| `delete_all_runs` | Clean up all runs |

**Tool parameters for start_run / start_concurrent_run:**
- `script` - Python script with `queueRequests(target, wordlists)` and `completed(results)` functions
- `base_request` - HTTP request template with `%s` injection points
- `endpoint` - Target URL (e.g., `https://example.com:443`)
- `base_input` - Optional input data for the script

---

## Available Resources

| URI | Description |
|-----|-------------|
| `turbo://runs` | List all runs |
| `turbo://runs/{id}` | Run status |
| `turbo://runs/{id}/results` | Query results (supports `?sort_by=`, `?limit=`, `?offset=`) |
| `turbo://runs/{id}/requests/{n}` | Full request/response detail |

Use `current` as the run ID to reference the most recent run.
