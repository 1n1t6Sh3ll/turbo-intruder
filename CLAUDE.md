# Turbo Intruder

## Running the MCP Server

Build and run:
```bash
./gradlew fatJar
java -jar build/libs/turbo-intruder.jar --mcp
```

Standalone listens on `localhost:31338`. Burp extension uses `localhost:31337`.
