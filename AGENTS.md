# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Build the project (output goes to target/)
./gradlew build

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "org.traccar.protocol.AdmProtocolDecoderTest"

# Run a single test method
./gradlew test --tests "org.traccar.protocol.AdmProtocolDecoderTest.testDecode"

# Run checkstyle (only applied to main sources)
./gradlew checkstyleMain

# Clean build artifacts
./gradlew clean

# Check for dependency updates
./gradlew dependencyUpdates
```

The Gradle wrapper (`gradlew`) is configured with Gradle 9.5.1 and Java 21.

## Configuration

Traccar uses XML configuration files. For local development, place `debug.xml` in the project root — when `Main` runs with no arguments it looks for `./debug.xml` automatically. Configuration keys are defined in `org.traccar.config.Keys` as typed constants. Each key supports retrieval from both XML properties and environment variables. The `Config` class handles type-safe access.

The minimum debug config enables H2 embedded database:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
<properties>
    <entry key="database.driver">org.h2.Driver</entry>
    <entry key="database.url">jdbc:h2:./target/database</entry>
    <entry key="database.user">sa</entry>
    <entry key="database.password"/>
    <entry key="geocoder.enable">true</entry>
    <entry key="geocoder.type">nominatim</entry>
</properties>
```

## Architecture

### Startup Flow

`Main.run()` → Creates Guice injector with `MainModule` + `DatabaseModule` + `WebModule` → Starts lifecycle services in order: `ScheduleManager`, `ServerManager`, `WebServer`, `BroadcastService`.

**DI framework:** Google Guice. All services are `@Singleton`-scoped. `MainModule` provides all major bindings (geocoder, geolocation, storage, handlers, forwarders, etc.).

### Protocol Layer (core data pipeline)

This is the heart of the server. Each GPS device protocol is a Java class under `org.traccar.protocol`.

- **`Protocol`** (interface) — defines protocol name, connector list, and command-sending capabilities.
- **`BaseProtocol`** — abstract base; each protocol class extends this. The naming convention is `<ProtocolName>Protocol.java`, e.g. `Gt06Protocol.java`. The protocol name is derived from the class name by stripping the `Protocol` suffix and lowercasing.
- **`ServerManager`** — scans `org.traccar.protocol` for all `BaseProtocol` subclasses. Instantiates only those with a configured port (`<protocol>.port`). Starts all `TrackerConnector` instances (TCP/UDP servers and clients).
- **`TrackerServer`** / **`TrackerClient`** — Netty-based TCP/UDP listeners and client connections, each backed by a `BasePipelineFactory`.

### Netty Pipeline (per-connection)

Each protocol defines its pipeline via `BasePipelineFactory` two hooks:
1. **`addTransportHandlers()`** — frame decoders and encoders specific to the protocol's wire format.
2. **`addProtocolHandlers()`** — protocol-specific decoder/encoder pair (extending `BaseProtocolDecoder` / `BaseProtocolEncoder`).

The pipeline in order:
```
Transport handlers → IdleStateHandler → OpenChannelHandler → [NetworkForwarderHandler]
→ NetworkMessageHandler → StandardLoggingHandler → [AcknowledgementHandler]
→ Protocol handlers (decoder/encoder) → RemoteAddressHandler → ProcessingHandler → MainEventHandler
```

### Position Processing Chain

When a `Position` object is decoded, `ProcessingHandler.channelRead()` feeds it through a **sequential handler chain** (Netty event-loop-aware, non-blocking):

1. `ComputedAttributesHandler.Early`
2. `OutdatedHandler`
3. `TimeHandler`
4. `GeolocationHandler`
5. `HemisphereHandler`
6. `MapMatcherHandler`
7. `DistanceHandler`
8. `FilterHandler`
9. `GeofenceHandler`
10. `GeocoderHandler`
11. `SpeedLimitHandler`
12. `MotionHandler`
13. `ComputedAttributesHandler.Late`
14. `DriverHandler`
15. `CopyAttributesHandler`
16. `EngineHoursHandler`
17. `PositionForwardingHandler`
18. `DatabaseHandler` (persists to DB)

After position handlers, **event handlers** analyze the position for alarms/events (overspeed, geofence enter/exit, ignition, maintenance, etc.). Finally, `PostProcessHandler` runs.

Positions for each device are **queued** to ensure sequential processing per device.

### Model Hierarchy

- **`BaseModel`** — id, attributes map
  - **`ExtendedModel`** — adds name
    - **`GroupedModel`** — adds groupId
      - **`Device`**, **`Driver`**, **`Geofence`**, **`Maintenance`**
    - **`User`**
  - **`Message`** — adds deviceId, type, fixTime
    - **`Position`** — GPS position with latitude, longitude, speed, course, altitude, plus extensible string-keyed attributes and optional cell tower / WiFi network data
    - **`Event`** — detected events (alarms, geofence triggers, etc.)
  - **`Command`** — commands sent to devices
  - **`Notification`**, **`Calendar`**, **`Order`**, **`Report`**

### Storage Layer

- **`Storage`** (abstract class) — CRUD + permissions + streaming API. Two implementations:
  - **`DatabaseStorage`** — SQL via the `QueryBuilder`. Supports MySQL, PostgreSQL, MariaDB, H2, MSSQL via JDBC.
  - **`MemoryStorage`** — in-memory, used when `database.memory=true` (for testing/lightweight operation). Concurrent-safe per-device position storage.
- **`QueryBuilder`** translates typed request objects (from `org.traccar.storage.query`) into SQL.
- Database schema migrations are managed by **Liquibase** (changelog files in `schema/`).

### REST API

- Powered by **Jetty 12 EE10** + **Jersey 4** (Jakarta EE 10).
- Resource classes under `org.traccar.api.resource` — one per entity type (`DeviceResource`, `PositionResource`, `EventResource`, etc.).
- Authentication via session tokens (`SessionResource`) + optional OpenID/OIDC (`OpenIdProvider`), LDAP (`LdapProvider`), and TOTP.
- API security: `org.traccar.api.security` with permission-based access control (`UserRestrictions`).
- WebSocket support for real-time position updates (`org.traccar.web`).

### Web Server & MCP

- `WebServer` serves the REST API on the configured `web.port`.
- When `web.mcp.enable=true`, the server exposes a **Model Context Protocol (MCP)** endpoint for LLM integration.
- Static web app files served from `web.path` (points to `traccar-web`).
- Proxy support for forwarding API requests to the web app.

### Event & Position Forwarding

`MainModule` provides configurable forwarding to external systems: JSON HTTP, AMQP (RabbitMQ), Kafka, MQTT, Redis, Wialon. Each is activated by its respective config key (`forward.url`, `event.forward.url`).

### Broadcast Service

In-memory notification distribution among server instances — supports **multicast** and **Redis** backends.

### Key Packages

| Package | Purpose |
|---|---|
| `org.traccar.protocol` | 200+ GPS device protocol decoders/encoders |
| `org.traccar.handler` | Position processing pipeline handlers |
| `org.traccar.handler.events` | Event detection (alarm, geofence, overspeed, etc.) |
| `org.traccar.handler.network` | Netty I/O handlers (ack, forward, logging) |
| `org.traccar.model` | Data model classes (Position, Device, Event, etc.) |
| `org.traccar.storage` | Persistence abstraction + QueryBuilder |
| `org.traccar.database` | Business-level data managers (commands, media, notifications, statistics) |
| `org.traccar.api.resource` | REST API endpoints |
| `org.traccar.api.security` | AuthN/AuthZ (login, permissions, token revocation) |
| `org.traccar.config` | Configuration system (Keys + Config) |
| `org.traccar.geocoder` | Reverse geocoding (Google, Nominatim, Baidu, etc.) |
| `org.traccar.geolocation` | Cell tower / WiFi geolocation providers |
| `org.traccar.geofence` | Geofence circle/polygon calculations |
| `org.traccar.reports` | PDF/Excel report generation (Jxls + Velocity templates) |
| `org.traccar.notification` + `notificators` | Notification system (email, SMS, push, etc.) |
| `org.traccar.session` | Connection session management + caching |
| `org.traccar.broadcast` | Cross-instance event broadcasting |
| `org.traccar.forward` | External forwarding (JSON, Kafka, AMQP, etc.) |
| `org.traccar.schedule` | Scheduled tasks (reports, device cleanup, etc.) |
| `org.traccar.command` | Device command infrastructure |
| `org.traccar.helper` | Utility classes (bit parsing, log, patterns, etc.) |

## Coding Patterns

### Protocol Implementation Pattern

Each protocol requires at minimum:
- `<Name>Protocol.java` extending `BaseProtocol` — registers server/client connectors in constructor.
- `<Name>ProtocolDecoder.java` extending `BaseProtocolDecoder` — decodes device messages into `Position` objects. Injected with `CacheManager`, `ConnectionManager`, `StatisticsManager`, `MediaManager`, `CommandsManager`.
- `<Name>FrameDecoder.java` (optional) — handles framing for stream-based protocols.
- `<Name>ProtocolEncoder.java` (optional) — encodes commands sent to devices.

Protocol decoders call `getDeviceSession()` to map unique device identifiers to internal IDs, then populate `Position` objects with fix time, location, and attributes.

### Position Attributes

The `Position` model uses a `Map<String, Object>` for extensible attributes. Standard attribute keys are defined as `Position.KEY_*` constants. Protocols set attributes like `position.set("fuel", 42.5)` and `position.set(Position.KEY_RSSI, -70)`.

### Test Patterns

- **`BaseTest`** — provides `inject(decoder)` / `inject(encoder)` to wire mock dependencies (CacheManager, ConnectionManager, Config, etc.) into protocol components. The mock device gets ID=1; mock ConnectionManager handles unique ID → DeviceSession resolution.
- **`ProtocolTest`** (extends BaseTest) — provides `verifyPosition()`, `verifyPositions()`, `verifyNull()`, `verifyAttribute()` helpers. `verifyDecodedPosition()` automatically validates location bounds, time reasonableness, and attribute types.
- Test data is embedded inline using `binary("hex...")` or `text("content")` or `buffer("content")` methods.

### Config Key Pattern

```java
// In Keys.java — define typed config keys with optional defaults:
public static final ConfigKey<String> GEOCODER_TYPE = new StringConfigKey(
    "geocoder.type", List.of(KeyType.CONFIG));

// Suffix keys support per-protocol overrides:
public static final ConfigSuffix<Integer> PROTOCOL_PORT = new PortConfigSuffix(
    ".port", List.of(KeyType.CONFIG));
// Usage: config.getInteger(Keys.PROTOCOL_PORT.withPrefix("gt06"))
```

Config key types: `StringConfigKey`, `BooleanConfigKey`, `IntegerConfigKey`, `LongConfigKey`, `DoubleConfigKey`. Suffix variants allow per-protocol prefixing.
