package ir.vigo.gateway;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {
    static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = selectRequestId(exchange.getRequest().getHeaders().get(HEADER));
        // Set at commit time so downstream headers cannot replace or duplicate the selected ID.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request ->
                request.headers(headers -> headers.set(HEADER, requestId))).build());
    }

    static String selectRequestId(List<String> values) {
        if (values != null && values.size() == 1 && SAFE_ID.matcher(values.getFirst()).matches()) {
            return values.getFirst();
        }
        return UUID.randomUUID().toString();
    }
}
