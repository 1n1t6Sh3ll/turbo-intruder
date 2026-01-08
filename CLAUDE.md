# Turbo Intruder

## Building

Always use the fatJar task for all builds:
```bash
./gradlew fatJar
```

The output JAR is at `build/libs/turbo-intruder.jar`.

## Running the MCP Server

Build and run:
```bash
./gradlew fatJar
java -jar build/libs/turbo-intruder.jar --mcp
```

Standalone listens on `localhost:31338`. Burp extension uses `localhost:31337`.
