# ScanEngine: Mass-Scale HTTP/1 Library

## Overview

A new HTTP/1 engine for Turbo Intruder optimised for sending 1-100 requests to 50,000 websites concurrently. Designed for security scanning use cases requiring raw socket control, crafted/malformed requests, and precise timing.

## Key Decisions

1. **Concurrency model:** Java 21 virtual threads. One virtual thread per host, 50K concurrent. Blocking IO throughout — no NIO, no coroutines.
2. **Fork strategy:** ScanEngine gets its own copy of the HTTP/1 socket and parsing code, forked from `ThreadedRequestEngine`. No shared layer. Free to diverge. `ThreadedRequestEngine` stays frozen, eventually deprecated, probably never removed.
3. **Transport only:** The library sends raw bytes and returns parsed responses. Request construction is the caller's responsibility.
4. **Java API:** Public API uses standard Java types. No Kotlin-specific types (no `suspend`, `Flow`, inline classes).
5. **Direct to targets:** Connections go direct to hosts, not through Burp's HTTP stack.
6. **TLS first-class:** Virtually all targets will be HTTPS. TLS handshake performance is a primary concern.
7. **Timeouts:** `socket.soTimeout` for per-request timeouts. `StructuredTaskScope.joinUntil()` for per-host scancheck deadlines.

## Architecture

```
┌──────────────────────────────────────────────┐
│                  ScanEngine                   │
│                                               │
│  scan(hosts, check) launches 50K virtual      │
│  threads via StructuredTaskScope              │
│                                               │
│  ┌─────────────┐  ┌─────────────┐            │
│  │  VThread 1  │  │  VThread N  │  ...50K    │
│  │  host-a.com │  │  host-n.com │            │
│  │             │  │             │            │
│  │ scancheck() │  │ scancheck() │            │
│  │  sendReq()  │  │  sendReq()  │            │
│  │  sendReq()  │  │  sendReq()  │            │
│  └──────┬──────┘  └──────┬──────┘            │
│         │                │                    │
│         ▼                ▼                    │
│  ┌────────────────────────────────┐          │
│  │   HTTP/1 Socket Layer (forked) │          │
│  │   connect, TLS, send, parse    │          │
│  │   (own copy, free to evolve)   │          │
│  └────────────────────────────────┘          │
└──────────────────────────────────────────────┘
```

Each virtual thread owns a connection to one host. The scancheck calls `sendRequest()` which blocks the virtual thread while doing DNS, TCP connect, TLS handshake, send, and response parsing. The JVM's virtual thread scheduler multiplexes onto a small number of carrier threads (default: core count).

Socket reuse across multiple `sendRequest()` calls within the same scancheck (same host) is supported via keep-alive.

## Bottlenecks at 50K Scale

### 1. DNS Resolution

**Problem:** Java's `InetAddress.getByName()` is blocking and hits the system resolver. 50K virtual threads resolving simultaneously will overwhelm the OS resolver (typically limited to 3 nameservers, each with 5s timeout).

**Mitigation:** Batch DNS pre-resolution with bounded parallelism before launching connections. Alternatively, use an async DNS library (dnsjava, Netty DNS) that talks directly to nameservers over UDP, bypassing the system resolver.

### 2. TLS Handshake Thundering Herd

**Problem:** 50K simultaneous TLS handshakes are CPU-intensive (ECDHE key exchange) and each requires 1-2 network round-trips. Carrier threads will be saturated doing crypto.

**Mitigation:** The virtual thread scheduler's carrier pool (core count) naturally throttles CPU-bound work. This may be sufficient — crypto runs on carrier threads, limiting true parallelism. If handshake latency queuing is excessive, stagger connection establishment.

### 3. File Descriptor Limits

**Problem:** Default `ulimit -n` is 1024-4096. Need 50K+ fds.

**Mitigation:** Document as prerequisite. Detect at startup, fail fast with clear error message.

### 4. Ephemeral Port Exhaustion

**Problem:** Default range is ~28K ports. 50K outbound connections from one IP may not fit.

**Mitigation:** Connections to different remote IPs reuse the same ephemeral port (5-tuple is unique), so this may not bite unless many hosts share an IP (CDNs). Needs empirical verification. Widen port range via `sysctl` if needed.

### 5. Memory — Response Bodies

**Problem:** 50K concurrent responses at ~10KB average = ~500MB. Outlier responses (10MB+) cause GC pressure.

**Mitigation:** Cap response body reads at a configurable limit (e.g. 256KB default). Security scanning rarely needs full bodies. Stream-discard beyond the cap.

### 6. Memory — TLS Session State

**Problem:** Each `SSLSocket` holds ~20-50KB of session state. At 50K: ~1-2.5GB.

**Mitigation:** Budget for it. Disable TLS session caching since each host is typically visited once.

### 7. GC Pressure

**Problem:** High allocation rate from 50K response objects, socket buffers, byte arrays.

**Mitigation:** Use ZGC. Buffer pooling for socket reads. Minimise intermediate String/byte[] copies in the HTTP parser.

### 8. Connection Timeouts and Slow Hosts

**Problem:** Some of 50K hosts will be unreachable, slow, or hang. Virtual threads sit blocked, consuming memory.

**Mitigation:** Aggressive `socket.soTimeout` (e.g. 10s). Connect timeout via `socket.connect(addr, timeout)`. Per-host deadline via `StructuredTaskScope.joinUntil()`.

## Relationship to Existing Code

- **ThreadedRequestEngine:** Stays frozen. Not modified. Eventually deprecated.
- **BurpRequestEngine:** Unrelated. Routes through Burp's HTTP stack.
- **HTTP2RequestEngine:** Unrelated. Different protocol.
- **RequestEngine base class:** ScanEngine may or may not extend it. TBD based on whether RunHandler/RunManager integration is useful.
- **RunHandler/RunManager:** ScanEngine integrates if it helps with lifecycle management and MCP orchestration. Otherwise standalone.

## Out of Scope

- Scancheck API design (caller's responsibility)
- Profiling harness (separate design)
- HTTP/2 support
- Proxy/Burp routing
- Request construction or templating
