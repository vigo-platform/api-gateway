# Gateway route contract

The prefixes below include the exact path and its descendants. Gateway keeps the
incoming path and query intact. Upstream origins are assembled from each service's
scheme, host, and port in `.env`.

| Service | Routes in the example configuration | Default example origin |
| --- | --- | --- |
| Auth | `/api/v1/auth` | `http://auth:8080` |
| Auth JWKS | Exact `/.well-known/jwks.json` for GET and HEAD | `http://auth:8080` |
| User | `/api/v1/users`, `/api/v1/coaches`, `/api/v1/admin/coaches` | `http://user:8081` |
| Storage | `/api/v1/files` | `http://storage:8083` |
| Notification | None | Reserved settings |
| API Gateway | `/actuator/health`, `/actuator/info` | Served locally |

For deployments where the reverse proxy forwards only `/api/v1/` to Gateway,
add an exact match for `/.well-known/jwks.json` at the proxy as well. Services
that validate JWTs use their configured JWKS origin. The old synthetic route,
internal service paths and Storage media paths are outside the routing table.
Requests with dot segments or encoded separators in routed paths return `404`.
