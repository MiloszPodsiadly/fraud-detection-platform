package com.frauddetection.alert.idempotency;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyCanonicalHasherTest {

    @Test
    void shouldCanonicalizeMapOrdering() {
        String left = IdempotencyCanonicalHasher.canonicalValue(Map.of("b", 2, "a", 1));
        String right = IdempotencyCanonicalHasher.canonicalValue(Map.of("a", 1, "b", 2));

        assertThat(left).isEqualTo(right);
        assertThat(IdempotencyCanonicalHasher.hash(left)).isEqualTo(IdempotencyCanonicalHasher.hash(right));
    }

    @Test
    void shouldCanonicalizeIterableOrderingAndNulls() {
        assertThat(IdempotencyCanonicalHasher.canonicalValue(List.of("a", "b"))).isEqualTo("[a,b]");
        assertThat(IdempotencyCanonicalHasher.canonicalValue(null)).isEqualTo("null");
    }

    @Test
    void shouldProduceDifferentHashForDifferentPayload() {
        assertThat(IdempotencyCanonicalHasher.hash(Map.of("amount", 1)))
                .isNotEqualTo(IdempotencyCanonicalHasher.hash(Map.of("amount", 2)));
    }
}
