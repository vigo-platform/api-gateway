package ir.vigo.gateway;

import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GatewayRoutesSettingsTest {
    private static final URI ORIGIN = URI.create("http://127.0.0.1:1");

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"ftp://notification:8085", "http://notification", "http://notification:0",
            "http://notification:65536", "http://notification:8085/base", "http://notification:8085?query=1",
            "http://notification:8085#fragment", "http://user@notification:8085"})
    void rejectsMissingOrInvalidNotificationOrigin(String origin) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> settings(origin == null ? null : URI.create(origin), "/api/v1/notification"))
                .withMessageContaining("NOTIFICATION needs an http or https origin");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/notification", "/api/v1/notification/", "/api/v1/notification/**"})
    void rejectsMissingOrInvalidNotificationPrefix(String prefix) {
        assertThatIllegalArgumentException().isThrownBy(() -> settings(ORIGIN, prefix))
                .withMessageContaining("NOTIFICATION_ROUTE_PREFIX must be a literal path under /api/v1/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth", "/api/v1/users", "/api/v1/coaches", "/api/v1/admin/coaches",
            "/api/v1/files", "/api/v1/files/notifications", "/api/v1/admin"})
    void rejectsNotificationPrefixOverlaps(String prefix) {
        assertThatIllegalArgumentException().isThrownBy(() -> settings(ORIGIN, prefix))
                .withMessageContaining("Overlapping Gateway route prefixes").withMessageContaining(prefix);
    }

    private static GatewayRoutes.Settings settings(URI notificationOrigin, String notificationPrefix) {
        return new GatewayRoutes.Settings(ORIGIN, "/api/v1/auth", "/.well-known/jwks.json",
                ORIGIN, "/api/v1/users", "/api/v1/coaches", "/api/v1/admin/coaches",
                ORIGIN, "/api/v1/files", 5000, notificationOrigin, notificationPrefix);
    }
}
