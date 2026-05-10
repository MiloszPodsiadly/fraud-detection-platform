package com.frauddetection.alert.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedIdempotencyKeyPolicyTest {

    private final SharedIdempotencyKeyPolicy policy = new SharedIdempotencyKeyPolicy();

    @Test
    void shouldAcceptValidTrimmedKeyAndHashDeterministically() {
        assertThat(policy.normalizeRequired(" key-1:retry_2.3 ")).isEqualTo("key-1:retry_2.3");
        assertThat(policy.hashKey("key-1")).isEqualTo(policy.hashKey(" key-1 "));
    }

    @Test
    void shouldRejectMissingBlankOverlongSpacesAndControlCharacters() {
        assertThatThrownBy(() -> policy.normalizeRequired(null)).isInstanceOf(SharedMissingIdempotencyKeyException.class);
        assertThatThrownBy(() -> policy.normalizeRequired("   ")).isInstanceOf(SharedMissingIdempotencyKeyException.class);
        assertThatThrownBy(() -> policy.normalizeRequired("a".repeat(129))).isInstanceOf(SharedInvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> policy.normalizeRequired("key with space")).isInstanceOf(SharedInvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> policy.normalizeRequired("key\nwith-control")).isInstanceOf(SharedInvalidIdempotencyKeyException.class);
    }

    @Test
    void shouldNotExposeRawKeyInExceptionMessage() {
        assertThatThrownBy(() -> policy.normalizeRequired("secret key"))
                .isInstanceOf(SharedInvalidIdempotencyKeyException.class)
                .hasMessageNotContaining("secret key");
    }
}
