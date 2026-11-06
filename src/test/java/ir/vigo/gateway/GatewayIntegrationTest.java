package ir.vigo.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource("/gateway-test.properties")
@ExtendWith(OutputCaptureExtension.class)
class GatewayIntegrationTest {
    private static final String SITE_ORIGIN = "https://vigoapp.ir";
    private static final BlockingQueue<Captured> REQUESTS = new LinkedBlockingQueue<>();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer AUTH = startStub("auth");
    private static final HttpServer USER = startStub("user");
    private static final HttpServer STORAGE = startStub("storage");
    private final HttpClient client = HttpClient.newHttpClient();
    @Autowired Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUTH_PORT", () -> AUTH.getAddress().getPort());
        registry.add("USER_PORT", () -> USER.getAddress().getPort());
        registry.add("STORAGE_PORT", () -> STORAGE.getAddress().getPort());
        registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "500ms");
        registry.add("GATEWAY_CONNECT_TIMEOUT_MS", () -> "300");
    }

    private static HttpServer startStub(String service) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> {
                try (exchange) {
                    REQUESTS.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(),
                            exchange.getRequestHeaders().get("X-Request-Id"),
                            exchange.getRequestHeaders().getFirst("X-Test-Header"),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().get("X-Forwarded-For"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            exchange.getRequestHeaders().get("traceparent")));
                    if (exchange.getRequestURI().getPath().endsWith("/slow")) {
                        try { Thread.sleep(2000); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    byte[] body = "stub-response".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("X-Request-Id", "downstream-must-not-win");
                    exchange.getResponseHeaders().add("X-Upstream", service);
                    if (exchange.getRequestHeaders().containsKey("Origin")) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
                        exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
                    }
                    exchange.sendResponseHeaders(202, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    // A timed-out gateway closes its connection before the delayed response.
                }
            });
            server.start();
            return server;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @BeforeEach void clearRequests() { REQUESTS.clear(); }
    @AfterEach void closeClient() { client.close(); }
    @AfterAll static void stopStubs() {
        AUTH.stop(0);
        USER.stop(0);
        STORAGE.stop(0);
        EXECUTOR.shutdownNow();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String id(HttpResponse<?> response) {
        List<String> ids = response.headers().allValues("X-Request-Id");
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private static void assertUuid(String id) { assertThat(UUID.fromString(id).version()).isEqualTo(4); }

    @Test void forwardsFullPathQueryHeadersAndBody() throws Exception {
        String path = "/api/v1/auth/login-password?term=a%20b&tag=one&tag=two&literal=%2F";
        HttpResponse<String> response = send(request(path).header("X-Test-Header", "keep-me")
                .header("X-Request-Id", "client_42.abc-XYZ").header("Content-Type", "application/json")
                .header("Authorization", "Bearer synthetic-token").header("X-Forwarded-For", "198.51.100.10")
                .POST(HttpRequest.BodyPublishers.ofString("{\"hello\":\"world\"}")));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("stub-response");
        assertThat(response.headers().firstValue("X-Upstream")).contains("auth");
        assertThat(id(response)).isEqualTo("client_42.abc-XYZ");
        Captured captured = REQUESTS.poll(2, TimeUnit.SECONDS);
        assertThat(captured).extracting(Captured::method, Captured::uri, Captured::ids, Captured::header,
                        Captured::authorization, Captured::forwardedFor, Captured::body)
                .containsExactly("POST", path, List.of("client_42.abc-XYZ"), "keep-me", "Bearer synthetic-token",
                        List.of("127.0.0.1"), "{\"hello\":\"world\"}");
    }

    @Test void gatewayAnswersBrowserPreflightWithoutCallingDownstream() throws Exception {
        HttpResponse<String> response = send(request("/api/v1/files/file-id")
                .header("Origin", SITE_ORIGIN)
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type,x-request-id")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(SITE_ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("PATCH");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow())
                .contains("authorization", "content-type", "x-request-id");
        assertThat(response.headers().allValues("Vary")).anyMatch(value -> value.contains("Origin"));
        assertThat(REQUESTS).isEmpty();
    }

    @Test void browserReceivesOnlyTheGatewayCorsPolicyAndBearerTokenIsForwarded() throws Exception {
        HttpResponse<String> response = send(request("/api/v1/users/me")
                .header("Origin", SITE_ORIGIN).header("Authorization", "Bearer synthetic-token"));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(SITE_ORIGIN);
        assertThat(response.headers().allValues("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow())
                .contains("X-Request-Id", "Content-Disposition");
        assertThat(response.headers().allValues("Vary")).anyMatch(value -> value.contains("Accept-Encoding"));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).authorization()).isEqualTo("Bearer synthetic-token");
    }

    @Test void otherOriginsCannotPreflightApiRequests() throws Exception {
        HttpResponse<String> response = send(request("/api/v1/users/me")
                .header("Origin", "https://vigoapp.ir.example.org")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        assertThat(REQUESTS).isEmpty();
    }

    @Test void trustedOriginCanReadJwksAndApiErrorsButHasNoActuatorCorsAccess() throws Exception {
        HttpResponse<String> jwks = send(request("/.well-known/jwks.json").header("Origin", SITE_ORIGIN));
        assertThat(jwks.statusCode()).isEqualTo(202);
        assertThat(jwks.headers().allValues("Access-Control-Allow-Origin")).containsExactly(SITE_ORIGIN);

        HttpResponse<String> missing = send(request("/api/v1/does-not-exist").header("Origin", SITE_ORIGIN));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.headers().allValues("Access-Control-Allow-Origin")).containsExactly(SITE_ORIGIN);

        HttpResponse<String> health = send(request("/actuator/health").header("Origin", SITE_ORIGIN));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test void routesEachUserAndStoragePrefixAndItsSubpaths() throws Exception {
        Map<String, String> routes = Map.of(
                "/api/v1/users", "user", "/api/v1/users/me", "user",
                "/api/v1/coaches", "user", "/api/v1/coaches/me/profile", "user",
                "/api/v1/admin/coaches", "user", "/api/v1/admin/coaches/coach-id/approve", "user",
                "/api/v1/files", "storage", "/api/v1/files/file-id/download?version=2", "storage");
        for (var route : routes.entrySet()) {
            HttpResponse<String> response = send(request(route.getKey()));
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.headers().firstValue("X-Upstream")).contains(route.getValue());
            assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).uri()).isEqualTo(route.getKey());
        }
    }

    @Test void generatesAndPropagatesMissingId() throws Exception {
        HttpResponse<String> response = send(request("/api/v1/auth/settings"));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        Captured captured = REQUESTS.poll(2, TimeUnit.SECONDS);
        assertThat(captured.ids()).containsExactly(id(response));
        assertThat(captured.traceParents()).singleElement().asString().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[13]");
    }

    @Test void continuesIncomingTraceAndCorrelatesAccessLog(CapturedOutput output) throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        String parent = "00-" + traceId + "-" + parentSpanId + "-01";
        HttpResponse<String> response = send(request("/api/v1/auth/settings")
                .header("traceparent", parent).header("X-Request-Id", "trace-correlation-test"));
        assertThat(response.statusCode()).isEqualTo(202);
        List<String> traceParents = REQUESTS.poll(2, TimeUnit.SECONDS).traceParents();
        assertThat(traceParents).singleElement().asString()
                .matches("00-" + traceId + "-(?!" + parentSpanId + ")[0-9a-f]{16}-0[13]");
        String clientSpanId = traceParents.getFirst().split("-")[2];
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(output.getOut())
                .containsPattern("\\[" + traceId + "-(?!" + parentSpanId + "|" + clientSpanId
                        + ")[0-9a-f]{16}\\][^\\r\\n]*trace-correlation-test"));
    }

    @Test void omitsManagementAndPreflightAccessLogs(CapturedOutput output) throws Exception {
        HttpResponse<String> health = send(request("/actuator/health")
                .header("X-Request-Id", "quiet-health"));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(id(health)).isEqualTo("quiet-health");

        HttpResponse<String> preflight = send(request("/api/v1/users/me")
                .header("X-Request-Id", "quiet-preflight")
                .header("Origin", SITE_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(id(preflight)).isEqualTo("quiet-preflight");

        HttpResponse<String> api = send(request("/api/v1/users/me")
                .header("X-Request-Id", "logged-cross-origin-api").header("Origin", SITE_ORIGIN));
        assertThat(api.statusCode()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(output.getOut())
                .contains("request_id=\"logged-cross-origin-api\""));
        assertThat(output.getOut()).doesNotContain("quiet-health", "quiet-preflight");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a,b", "unsafe/value", "a b"})
    void replacesInvalidIds(String incoming) throws Exception {
        HttpResponse<String> response = send(request("/api/v1/auth/settings").header("X-Request-Id", incoming));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly(id(response));
    }

    @Test void replacesOversizedId() throws Exception {
        HttpResponse<String> response = send(request("/api/v1/auth/settings").header("X-Request-Id", "a".repeat(129)));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly(id(response));
    }

    @ParameterizedTest @ValueSource(strings = {"same", "different"})
    void replacesDuplicateIds(String second) throws Exception {
        HttpResponse<String> response = send(request("/api/v1/auth/settings")
                .header("X-Request-Id", "same").header("X-Request-Id", second));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly(id(response));
    }

    @Test void forwardsJwksToAuthWithFullPathAndQuery() throws Exception {
        String path = "/.well-known/jwks.json?test=1";
        HttpResponse<String> response = send(request(path));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("X-Upstream")).contains("auth");
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).uri()).isEqualTo(path);

        assertThat(send(request("/.well-known/jwks.json")
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(404);
        assertThat(REQUESTS).isEmpty();
    }

    @Test void missingRouteReturns404WithoutCallingDownstream() throws Exception {
        for (String path : List.of("/does-not-exist", "/__stub/test", "/api/v1/users-other", "/api/v1/media",
                "/api/v1/notifications", "/internal/v1/users/user-id/summary", "/.well-known/other.json",
                "/.well-known/jwks.json/", "/.well-known/jwks.json/child",
                "/api/v1/auth/../private", "/api/v1/auth/%2e%2e/private", "/api/v1/auth/%2fprivate")) {
            HttpResponse<String> response = send(request(path).header("X-Request-Id", "missing-route"));
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(id(response)).isEqualTo("missing-route");
        }
        assertThat(REQUESTS).isEmpty();
    }

    @Test void responseTimeoutReturns504WithRequestId() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = send(request("/api/v1/auth/slow")
                .header("X-Request-Id", "timeout-test").header("Origin", SITE_ORIGIN));
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(SITE_ORIGIN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(id(response)).isEqualTo("timeout-test");
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly("timeout-test");
    }

    @Test void healthIsUpWithoutDetails() throws Exception {
        HttpResponse<String> response = send(request("/actuator/health"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"")
                .doesNotContain("\"details\"", "\"components\"");
        assertUuid(id(response));
    }

    @Test void infoIsExposedWithoutEnvironmentData() throws Exception {
        HttpResponse<String> response = send(request("/actuator/info"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{}");
        assertUuid(id(response));
    }

    @Test void prometheusScrapeReturnsMetricsWithoutAuthentication() throws Exception {
        HttpResponse<String> scrape = send(request("/actuator/prometheus"));
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "beans", "heapdump", "threaddump", "loggers", "metrics", "gateway", "shutdown"})
    void sensitiveActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        assertThat(send(request("/actuator/" + endpoint)).statusCode()).isEqualTo(404);
    }

    record Captured(String method, String uri, List<String> ids, String header,
                    String authorization, List<String> forwardedFor, String body, List<String> traceParents) { }
}
