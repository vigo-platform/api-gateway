package ir.vigo.gateway;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

@Configuration(proxyBeanMethods = false)
class GatewayCors {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    CorsWebFilter gatewayCorsFilter(GatewayRoutes.Settings routes,
                                   @Value("${GATEWAY_CORS_ALLOWED_ORIGINS:https://vigoapp.ir}") List<String> origins) {
        var api = new CorsConfiguration();
        api.setAllowedOrigins(origins);
        api.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        api.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, RequestIdWebFilter.HEADER));
        api.setExposedHeaders(List.of(RequestIdWebFilter.HEADER, HttpHeaders.CONTENT_DISPOSITION));
        api.setMaxAge(600L);

        var jwks = new CorsConfiguration(api);
        jwks.setAllowedMethods(List.of("GET", "HEAD"));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", api);
        source.registerCorsConfiguration(routes.authJwksPath(), jwks);
        return new CorsWebFilter(source);
    }

    @Bean
    HttpHeadersFilter upstreamCorsHeadersFilter() {
        return new HttpHeadersFilter() {
            @Override
            public boolean supports(Type type) {
                return type == Type.RESPONSE;
            }

            @Override
            public HttpHeaders filter(HttpHeaders headers, ServerWebExchange exchange) {
                // Preserve the Gateway's policy if a service adds its own CORS headers.
                var filtered = new HttpHeaders();
                headers.forEach((name, values) -> {
                    if (!StringUtils.startsWithIgnoreCase(name, "Access-Control-")) {
                        filtered.addAll(name, values);
                    }
                });
                return filtered;
            }
        };
    }
}
