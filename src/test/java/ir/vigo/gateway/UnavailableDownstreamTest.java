package ir.vigo.gateway;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
class UnavailableDownstreamTest {
    @Autowired Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        // Allocate then close a local port so the connection is refused, not silently dropped.
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        registry.add("STUB_BASE_URL", () -> "http://127.0.0.1:" + port);
        registry.add("GATEWAY_CONNECT_TIMEOUT_MS", () -> "300");
        registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "1s");
    }

    @Test void connectionRefusalUsesFramework500AndStillReturnsRequestId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + "/__stub/unavailable"))
                .timeout(Duration.ofSeconds(5)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
            assertThat(UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow()).version())
                    .isEqualTo(4);
        }
    }
}
