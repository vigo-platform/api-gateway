package ir.vigo.gateway;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdWebFilterTest {
    @Test
    void preservesSafeIdsAtBothLengthBoundaries() {
        for (String id : List.of("a", "client-ID_42.7", "a".repeat(128))) {
            assertThat(RequestIdWebFilter.selectRequestId(List.of(id))).isEqualTo(id);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void generatesUuidForInvalidOrAmbiguousValues(List<String> values) {
        String id = RequestIdWebFilter.selectRequestId(values);
        assertThat(UUID.fromString(id).version()).isEqualTo(4);
        assertThat(id).isNotEqualTo(RequestIdWebFilter.selectRequestId(values));
    }

    static Stream<List<String>> invalidValues() {
        return Stream.of(null, List.of(), List.of(""), List.of(" "), List.of(" padded"),
                List.of("padded "), List.of("a,b"), List.of("a", "a"), List.of("a", "b"),
                List.of("a".repeat(129)), List.of("é"), List.of("a\r\nb"), List.of("a\tb"),
                List.of("a/b"), List.of("<script>"));
    }
}
