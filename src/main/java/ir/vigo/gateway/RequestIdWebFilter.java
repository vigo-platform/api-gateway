package ir.vigo.gateway;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {
    static final String HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestIdWebFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private final List<PathPattern> managementPaths;

    public RequestIdWebFilter(PathMappedEndpoints endpoints) {
        String basePath = endpoints.getBasePath();
        // With a root management base path, only exclude the exposed endpoint paths.
        List<String> paths = basePath.isEmpty() ? List.copyOf(endpoints.getAllPaths()) : List.of(basePath);
        managementPaths = paths.stream()
                .map(path -> PathPatternParser.defaultInstance.parse(path + "/**"))
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startedAt = System.nanoTime();
        String requestId = selectRequestId(exchange.getRequest().getHeaders().get(HEADER));
        // Set at commit time so downstream headers cannot replace or duplicate the selected ID.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        Mono<Void> response = chain.filter(exchange.mutate().request(request ->
                request.headers(headers -> headers.set(HEADER, requestId))).build());
        var request = exchange.getRequest();
        if (CorsUtils.isPreFlightRequest(request)
                || managementPaths.stream().anyMatch(path -> path.matches(request.getPath().pathWithinApplication()))) {
            return response;
        }
        return response
                .doOnSuccess(ignored -> logExchange(exchange, requestId, startedAt, null, false))
                .doOnError(failure -> logExchange(exchange, requestId, startedAt, failure, false))
                .doOnCancel(() -> logExchange(exchange, requestId, startedAt, null, true));
    }

    private void logExchange(ServerWebExchange exchange, String requestId, long startedAt,
                             Throwable failure, boolean cancelled) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (failure != null && !exchange.getResponse().isCommitted()) {
            status = failure instanceof ErrorResponse error ? error.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
        } else if (status == null && !cancelled) {
            status = HttpStatus.OK;
        }

        Level level = Level.INFO;
        String result = "SUCCESS";
        if (cancelled) {
            result = "CANCELLED";
        } else if (status.is5xxServerError() || (failure != null && !status.isError())) {
            level = Level.ERROR;
            result = "FAILURE";
        } else if (status.is4xxClientError()) {
            level = Level.WARN;
            result = "REJECTED";
        }

        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        var entry = log.atLevel(level).addKeyValue("event", "gateway_request")
                .addKeyValue("result", result)
                .addKeyValue("request_id", requestId)
                .addKeyValue("http_method", exchange.getRequest().getMethod().name())
                .addKeyValue("path", exchange.getRequest().getURI().getRawPath())
                .addKeyValue("route_id", route == null ? null : route.getId())
                .addKeyValue("status", status == null ? null : status.value())
                .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                .addKeyValue("ip_address", address == null ? null : address.getHostString());
        if (failure != null) {
            entry.addKeyValue("reason", NestedExceptionUtils.getMostSpecificCause(failure).getClass().getSimpleName());
        }
        entry.log("Gateway request finished.");
    }

    static String selectRequestId(List<String> values) {
        if (values != null && values.size() == 1 && SAFE_ID.matcher(values.getFirst()).matches()) {
            return values.getFirst();
        }
        return UUID.randomUUID().toString();
    }
}
