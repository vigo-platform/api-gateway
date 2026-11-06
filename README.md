# Vigo API Gateway

Stateless Spring Cloud Gateway Server WebFlux application using Java 21. See the
[route contract](docs/route-contract-readiness.md) for the exposed paths and upstreams.

## Configuration and local run

Copy `.env.example` to `.env` and configure the listener, upstream schemes, hosts,
ports, and literal route prefixes. The Gateway reads `.env` from its working
directory on startup. Shell environment variables override values from the file.
Use plain `NAME=value` lines. The local file is ignored by Git and Docker builds.

```bash
cp -n .env.example .env
SERVER_PORT=8088 AUTH_HOST=127.0.0.1 USER_HOST=127.0.0.1 STORAGE_HOST=127.0.0.1 \
  sh ./mvnw spring-boot:run
```

The example runs Gateway on `8088` so it can share the host with Auth on `8080`.
Set `SERVER_PORT` and the upstream addresses to the values for your environment.
In Docker, the example uses DNS names `auth`, `user`, and `storage` on the shared
network. Notification has reserved address settings and no public HTTP route.

Gateway forwards the full path, query, method, body, and `Authorization` header.
The upstream services validate credentials and apply their own access policies.
Connection and response timeouts are configurable. Storage has its own response
timeout in milliseconds to accommodate uploads. Gateway health reports the
application's status. The local management endpoints are `/actuator/health`,
`/actuator/info`, and `/actuator/prometheus` with the example configuration.
These endpoints are accessible without authentication.

Gateway handles CORS for `/api/v1/**` and the configured JWKS path. The default
browser origin is `https://vigoapp.ir`. Set `GATEWAY_CORS_ALLOWED_ORIGINS` to a
comma-separated list of origins to override it. Gateway answers preflight
requests and allows the frontend to send bearer tokens and JSON. It exposes
`X-Request-Id` and `Content-Disposition` to browser scripts.

Gateway sends the connection peer's IP in `X-Forwarded-For` by default. Set
`GATEWAY_FORWARD_HEADERS_STRATEGY=framework` only if every request passes through
a trusted proxy that replaces incoming forwarding headers. Configure that proxy
to clear `Forwarded` and `X-Forwarded-Prefix` and set the client IP itself.

## Logs

Gateway skips request logs for Actuator paths and CORS preflight requests.
Each request log uses Auth's SLF4J key/value layout and includes a request ID,
method, path, route ID, HTTP status, duration, client IP and outcome. A failed
exchange also records the exception type. Query strings, bodies, and credentials
are excluded from the request log. A valid `X-Request-Id` is preserved, otherwise
Gateway generates one and returns it in the response.

Request logs include `traceId` and `spanId` from Micrometer Tracing with
OpenTelemetry. Gateway continues an incoming W3C trace or starts one and sends
the downstream span context in `traceparent`. Startup logs run outside a request
and have no trace IDs. `GATEWAY_TRACING_SAMPLING_PROBABILITY` controls sampling
for traces started by Gateway and defaults to `1.0`, matching Auth.

Outside Docker, the console uses the same text layout as Auth and Gateway sends
JSON lines to Vector over TCP at `LOGGING_VECTOR_DESTINATION` (default
`localhost:5044`). An empty destination selects console-only logging. The Docker
image emits JSON to stdout with `service_name=api-gateway` and the `logging=true`
label for Vector collection.

## Build and release

```bash
sh ./mvnw -B -ntp clean verify
docker build -t logtaha/vigo-gateway-service:local .
docker run --rm --network vigo-app --env-file .env -p 8088:8080 logtaha/vigo-gateway-service:local
```

Use the network shared with your services in place of `vigo-app` if it has a
different name. The container-side port in `-p` must equal `SERVER_PORT`.
On Windows, use `mvnw.cmd` in place of `sh ./mvnw`.

The `Docker Build & Push` workflow uses `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`
to publish to `logtaha/vigo-gateway-service`. Push `build-and-push-<version>` or
enter a tag in a manual run to publish the version and `latest`. A `build-and-push`
tag or a manual run with a blank input publishes `sha-<short-sha>`.
