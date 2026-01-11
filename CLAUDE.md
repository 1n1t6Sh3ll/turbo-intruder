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
