# Vigo API Gateway — first slice

Small stateless Spring Cloud Gateway Server WebFlux application in `ir.vigo.gateway`.
Baseline: Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.3 (Oakwood), Maven 3.9.16.
Spring's [compatibility matrix](https://spring.io/projects/spring-cloud/) supports Boot 4.1.x
starting with Cloud 2025.1.2. The compatibility verifier remains enabled.

## Build and run

Install JDK 21 and point `JAVA_HOME` to it. No global Maven installation is needed.
The wrapper downloads Maven on first use; dependency resolution requires network access.

```powershell
java -version
.\mvnw.cmd --version
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

On Linux/macOS use `sh ./mvnw` instead of `.\mvnw.cmd`.
Tests use ephemeral local HTTP stubs and require no teammate services or Docker.

## Configuration

See [route contract readiness](docs/route-contract-readiness.md) for frozen routing decisions and pending service contracts.

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP listening port |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `1000` | Downstream connection timeout, milliseconds |
| `GATEWAY_RESPONSE_TIMEOUT` | `5s` | Downstream response timeout, duration such as `500ms` or `5s` |
| `SPRING_PROFILES_ACTIVE` | unset | Set `stub` to enable the synthetic route |
| `STUB_BASE_URL` | `http://127.0.0.1:9090` | Stub origin when the stub profile is active |

`.env.example` documents safe values. Spring Boot does not automatically read `.env` files;
set shell environment variables or use Docker's `--env-file` option.

There are no routes in the default profile. The `stub` profile enables only `/__stub/**`.
The downstream should be an HTTP stub listening on the configured origin, without a base path.
For example, `GET /__stub/api/v1/example?x=1` is forwarded with that exact path and query.
There is no StripPrefix, path rewrite, real service contract or retry filter.
Ordinary end-to-end headers and request/response bodies are forwarded; HTTP hop-by-hop
headers remain subject to the framework's normal proxy handling.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'stub'
$env:STUB_BASE_URL = 'http://127.0.0.1:9090'
.\mvnw.cmd spring-boot:run
```

## Request IDs and management endpoints

Exactly one `X-Request-Id` value matching `[A-Za-z0-9._-]{1,128}` is preserved.
Missing, empty, invalid, oversized, duplicated or comma-separated values are replaced
with a UUID v4. The selected ID is forwarded downstream and returned as exactly one
response header, overriding any downstream request-ID response header. The same rule
applies to management and gateway error responses. Validation operates on headers after
the HTTP server parses them; invalid HTTP syntax may be rejected before application filters.

Only `/actuator/health` and `/actuator/info` are exposed. Health details and management
discovery are disabled. Health reports application health, not downstream availability.
Boot 4.1.1 includes the default `liveness` and `readiness` group names in the health response.
No custom info contributors are configured. There is no authentication integration yet.

The integration tests observe framework behavior for missing routes (404), refused
downstream connections (500) and response timeouts (504). There is no custom error schema
or error mapping.

## Container

```powershell
docker build -t vigo-api-gateway:first-slice .
docker run --rm --name vigo-gateway -p 8080:8080 --env-file .env.example vigo-api-gateway:first-slice
```

The multi-stage build uses Java 21, runs the test suite, and copies only the application
JAR to a Java 21 runtime image. The runtime user is non-root UID/GID `10001:10001`.
For a stub running on the Docker Desktop host, use `STUB_BASE_URL=http://host.docker.internal:9090`
and enable the `stub` profile. Container loopback refers to the container itself.

```powershell
curl.exe -i http://localhost:8080/actuator/health
curl.exe -i http://localhost:8080/actuator/info
curl.exe -i http://localhost:8080/does-not-exist
curl.exe -i http://localhost:8080/actuator/env
docker exec vigo-gateway id
```

The local GitHub Actions skeleton verifies with Temurin 21 and builds the image. It does
not publish images or deploy anything. No GitHub resources are created by local implementation.

## Scope

This slice includes only the gateway foundation, synthetic route, request IDs, timeouts,
health/info, tests, container packaging and CI skeleton. It includes no real Vigo routes,
business logic, authentication/JWT, database, Kafka, Notification, Chat, Redis, Eureka,
Kubernetes, Config Server, service mesh, retries or custom error framework.
