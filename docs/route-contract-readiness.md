# Gateway route contract readiness

## Frozen gateway routing decisions

- No real route may be added until its contract and addressing information are approved.
- The full `/api/v1/{service}/...` path is preserved by default. No prefix stripping or path rewriting is assumed.
- Workout / Exercise / Execution remain inside `workout-service`.
- Chat remains deferred.
- The synthetic `/__stub/**` route is available only with the `stub` profile; it is not a real service contract.

The path pattern above is a routing convention, not an approved endpoint list.
This document does not assign endpoints, methods, ports, hostnames, service URLs,
JWT rules or CORS origins.

## Current known implementation state

The gateway is a stateless Spring Cloud Gateway Server WebFlux application.
The default profile defines no gateway routes. The `stub` profile defines only
the synthetic route and preserves the incoming path and query.

Request-ID propagation, downstream connection and response timeouts, and limited
health/info management endpoints are implemented. Errors use framework behavior;
there is no custom error mapping or authentication integration. Existing tests
exercise forwarding with local HTTP stubs. The default-profile regression test
starts the application and verifies that `/__stub/test` returns HTTP 404 without
starting a downstream service.

No real service routes or approved service contracts and addressing information
are recorded in this repository. The entries below are pending confirmation, not
claims that service implementations do not exist elsewhere.

## Contract owners and route-readiness matrix

Each service's owning team must supply and confirm its contract and addressing
information. Named contract owners and approval authorities have not yet been
recorded and must be confirmed. The gateway maintainer records approval before
implementing a route; this document itself grants no route approval.

For every service, the missing approval package comprises a named contract owner
and approver, a versioned API contract (paths, methods, request/response and error
behavior), environment-specific addressing, and any applicable authentication
and cross-origin requirements. No values or rules are assumed.

| Service | Contract owner | Current gateway readiness | Missing information |
| --- | --- | --- | --- |
| Auth | Auth service owning team; named owner pending | Blocked; no real route configured | Full approval package, including confirmation of applicable authentication responsibilities. |
| User | User service owning team; named owner pending | Blocked; no real route configured | Full approval package for User. |
| Order | Order service owning team; named owner pending | Blocked; no real route configured | Full approval package for Order. |
| Payment | Payment service owning team; named owner pending | Blocked; no real route configured | Full approval package for Payment. |
| Storage | Storage service owning team; named owner pending | Blocked; no real route configured | Full approval package for Storage. |
| Workout | workout-service owning team; named owner pending | Blocked; no real route configured | Full approval package covering Workout / Exercise / Execution within workout-service. |
| Notification | Notification service owning team; named owner pending | Blocked; no real route configured | Full approval package and confirmation of whether any gateway-facing API is required. |
| Chat | Chat service owning team; named owner pending | Deferred; no real route configured | Explicit decision to resume Chat, followed by the full approval package. |

Readiness requires recorded approval of both the contract and addressing
information. A service name, proposed owner or completed implementation alone
does not authorize adding a gateway route.
