package ir.vigo.gateway;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer.PathSegment;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRoutes.Settings.class)
class GatewayRoutes {
    private static final Logger log = LoggerFactory.getLogger(GatewayRoutes.class);

    @Bean
    RouteLocator serviceRoutes(RouteLocatorBuilder builder, Settings settings) {
        RouteLocator routes = builder.routes()
                .route("auth", route -> route.path(settings.authPrefix(), settings.authPrefix() + "/**")
                        .and().predicate(GatewayRoutes::isUnambiguousPath)
                        .uri(settings.authOrigin()))
                .route("auth-jwks", route -> route.method(HttpMethod.GET, HttpMethod.HEAD)
                        .and().predicate(exchange -> settings.authJwksPath().equals(exchange.getRequest().getURI().getRawPath()))
                        .uri(settings.authOrigin()))
                .route("user", route -> route.path(
                                settings.userPrefix(), settings.userPrefix() + "/**",
                                settings.coachesPrefix(), settings.coachesPrefix() + "/**",
                                settings.adminCoachesPrefix(), settings.adminCoachesPrefix() + "/**")
                        .and().predicate(GatewayRoutes::isUnambiguousPath)
                        .uri(settings.userOrigin()))
                .route("storage", route -> route.path(settings.storagePrefix(), settings.storagePrefix() + "/**")
                        .and().predicate(GatewayRoutes::isUnambiguousPath)
                        .metadata(RESPONSE_TIMEOUT_ATTR, settings.storageResponseTimeoutMs())
                        .uri(settings.storageOrigin()))
                .build();
        log.atInfo().addKeyValue("event", "gateway_routes_configured")
                .addKeyValue("auth_origin", settings.authOrigin().toString())
                .addKeyValue("user_origin", settings.userOrigin().toString())
                .addKeyValue("storage_origin", settings.storageOrigin().toString())
                .log("Gateway routes configured.");
        return routes;
    }

    private static boolean isUnambiguousPath(ServerWebExchange exchange) {
        // An upstream must not decode or normalize a routed path outside its configured prefix.
        return exchange.getRequest().getPath().elements().stream()
                .filter(PathSegment.class::isInstance)
                .map(PathSegment.class::cast)
                .map(PathSegment::valueToMatch)
                .noneMatch(segment -> segment.equals(".") || segment.equals("..")
                        || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.indexOf(';') >= 0);
    }

    @Bean
    HttpHeadersFilter clientAddressHeader() {
        // Runs after Gateway's ordered filters remove inbound forwarding headers.
        // Auth uses the resulting address for auditing and rate limits.
        return (headers, exchange) -> {
            InetSocketAddress address = exchange.getRequest().getRemoteAddress();
            if (address != null) {
                headers.set("X-Forwarded-For", address.getHostString());
            }
            return headers;
        };
    }

    @ConfigurationProperties("gateway.routes")
    record Settings(URI authOrigin, String authPrefix, String authJwksPath,
                    URI userOrigin, String userPrefix, String coachesPrefix, String adminCoachesPrefix,
                    URI storageOrigin, String storagePrefix, int storageResponseTimeoutMs) {
        private static final Pattern API_PREFIX = Pattern.compile("/api/v1(?:/[a-z0-9][a-z0-9-]*)+");
        private static final Pattern WELL_KNOWN_JSON_PATH = Pattern.compile("/\\.well-known/[a-z0-9][a-z0-9-]*\\.json");

        Settings {
            validateOrigin(authOrigin, "AUTH");
            validateOrigin(userOrigin, "USER");
            validateOrigin(storageOrigin, "STORAGE");
            if (authJwksPath == null || !WELL_KNOWN_JSON_PATH.matcher(authJwksPath).matches()) {
                throw new IllegalArgumentException("AUTH_JWKS_PATH must be a literal JSON path under /.well-known/");
            }
            if (storageResponseTimeoutMs < 1) {
                throw new IllegalArgumentException("STORAGE_RESPONSE_TIMEOUT_MS must be positive");
            }

            List<String> prefixes = List.of(authPrefix, userPrefix, coachesPrefix, adminCoachesPrefix, storagePrefix);
            for (int index = 0; index < prefixes.size(); index++) {
                String prefix = prefixes.get(index);
                if (!API_PREFIX.matcher(prefix).matches()) {
                    throw new IllegalArgumentException("Gateway route prefixes must be literal paths under /api/v1/");
                }
                for (String previous : prefixes.subList(0, index)) {
                    if (prefix.equals(previous) || prefix.startsWith(previous + "/") || previous.startsWith(prefix + "/")) {
                        throw new IllegalArgumentException("Overlapping Gateway route prefixes: " + previous + " and " + prefix);
                    }
                }
            }
        }

        private static void validateOrigin(URI origin, String service) {
            if (origin == null || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                    || origin.getHost() == null || origin.getPort() < 1 || origin.getPort() > 65535
                    || !origin.getRawPath().isEmpty() || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null) {
                throw new IllegalArgumentException(service + " needs an http or https origin with a host and explicit port");
            }
        }
    }
}
