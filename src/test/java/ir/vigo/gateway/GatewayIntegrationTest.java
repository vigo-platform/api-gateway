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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
class GatewayIntegrationTest {
    private static final BlockingQueue<Captured> REQUESTS = new LinkedBlockingQueue<>();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer STUB = startStub();
    private final HttpClient client = HttpClient.newHttpClient();
    @Autowired Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("STUB_BASE_URL", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "500ms");
        registry.add("GATEWAY_CONNECT_TIMEOUT_MS", () -> "300");
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> {
                try (exchange) {
                    REQUESTS.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(),
                            exchange.getRequestHeaders().get("X-Request-Id"),
                            exchange.getRequestHeaders().getFirst("X-Test-Header"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    if (exchange.getRequestURI().getPath().endsWith("/slow")) {
                        try { Thread.sleep(2000); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    byte[] body = "stub-response".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("X-Request-Id", "downstream-must-not-win");
                    exchange.getResponseHeaders().add("X-Stub", "yes");
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
    @AfterAll static void stopStub() { STUB.stop(0); EXECUTOR.shutdownNow(); }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
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
        String path = "/__stub/api/v1/synthetic/profile?term=a%20b&tag=one&tag=two&literal=%2F";
        HttpResponse<String> response = send(request(path).header("X-Test-Header", "keep-me")
                .header("X-Request-Id", "client_42.abc-XYZ").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"hello\":\"world\"}")));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("stub-response");
        assertThat(response.headers().firstValue("X-Stub")).contains("yes");
        assertThat(id(response)).isEqualTo("client_42.abc-XYZ");
        Captured captured = REQUESTS.poll(2, TimeUnit.SECONDS);
        assertThat(captured).isEqualTo(new Captured("POST", path, List.of("client_42.abc-XYZ"),
                "keep-me", "{\"hello\":\"world\"}"));
    }

    @Test void generatesAndPropagatesMissingId() throws Exception {
        HttpResponse<String> response = send(request("/__stub/echo"));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly(id(response));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a,b", "unsafe/value", "a b"})
    void replacesInvalidIds(String incoming) throws Exception {
        HttpResponse<String> response = send(request("/__stub/echo").header("X-Request-Id", incoming));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly(id(response));
    }

    @Test void replacesOversizedId() throws Exception {
        HttpResponse<String> response = send(request("/__stub/echo").header("X-Request-Id", "a".repeat(129)));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly(id(response));
    }

    @ParameterizedTest @ValueSource(strings = {"same", "different"})
    void replacesDuplicateIds(String second) throws Exception {
        HttpResponse<String> response = send(request("/__stub/echo")
                .header("X-Request-Id", "same").header("X-Request-Id", second));
        assertThat(response.statusCode()).isEqualTo(202);
        assertUuid(id(response));
        assertThat(REQUESTS.poll(2, TimeUnit.SECONDS).ids()).containsExactly(id(response));
    }

    @Test void missingRouteReturns404WithoutCallingDownstream() throws Exception {
        HttpResponse<String> response = send(request("/does-not-exist").header("X-Request-Id", "missing-route"));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(id(response)).isEqualTo("missing-route");
        assertThat(REQUESTS).isEmpty();
    }

    @Test void responseTimeoutReturns504WithRequestId() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = send(request("/__stub/slow").header("X-Request-Id", "timeout-test"));
        assertThat(response.statusCode()).isEqualTo(504);
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

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "beans", "heapdump", "threaddump", "loggers", "metrics", "gateway", "shutdown"})
    void sensitiveActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        assertThat(send(request("/actuator/" + endpoint)).statusCode()).isEqualTo(404);
    }

    record Captured(String method, String uri, List<String> ids, String header, String body) { }
}
