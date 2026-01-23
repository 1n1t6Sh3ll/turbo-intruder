# Turbo Intruder

## Building

```bash
./gradlew jar
```

The output JAR is at `build/libs/turbo-intruder.jar`.

## Running the MCP Server

Build and run:
```bash
./gradlew jar
java -jar build/libs/turbo-intruder.jar --mcp
```

Standalone listens on `localhost:31338`. Burp extension uses `localhost:31337`.

## Development

Always use `superpowers:test-driven-development` when editing code.

## Terminology

Use neutral terminology in user-facing text:
- Use "run" not "attack" (e.g., "Start a new run", "Stop the active run")
- Avoid "Intruder" in MCP tool/resource descriptions (intended for GUI users only)
- Exception: "single-packet attack" is established research terminology
- Exception: Security threat descriptions (e.g., "DNS rebinding attacks")

## Reference

MCP Java SDK source is available at `mcp-guidance/java-sdk/` for API reference.
