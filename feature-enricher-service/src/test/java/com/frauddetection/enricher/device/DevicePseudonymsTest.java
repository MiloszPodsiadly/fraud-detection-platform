package com.frauddetection.enricher.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class DevicePseudonymsTest {
    @Test void tenantKindAndFieldBoundariesCannotAliasIdentifiers() {
        var p = new DevicePseudonyms(new byte[32], 1);
        assertThat(p.customer("institution-a", "same-id")).isNotEqualTo(p.customer("institution-b", "same-id"))
                .isNotEqualTo(p.device("institution-a", "same-id"));
        assertThat(p.transaction("institution-a", "ab", "c")).isNotEqualTo(p.transaction("institution-a", "a", "bc"));
    }
    @Test void keyMaterialIsCopiedAndWellFormedUnicodeRetainsItsExactIdentity() {
        byte[] key = new byte[32]; var p = new DevicePseudonyms(key, 1);
        String before = p.customer("institution-a", "customer"); key[0] = 1;
        assertThat(p.customer("institution-a", "customer")).isEqualTo(before);
        assertThat(p.customer("institution-a", "\u00e9")).isNotEqualTo(p.customer("institution-a", "e\u0301"));
        assertThat(p.customer("institution-a", "customer ")).isNotEqualTo(before);
    }
    @ParameterizedTest @MethodSource("malformedIdentifiers")
    void malformedUnicodeIsDeniedWithoutAReplacementTokenOrIdentifierInTheError(String identifier) {
        var p = new DevicePseudonyms(new byte[32], 1);
        assertThatThrownBy(() -> p.customer("institution-a", identifier)).hasMessage("DEVICE_PSEUDONYM_MAPPING_UNAVAILABLE");
        assertThatThrownBy(() -> p.device("institution-a", identifier)).hasMessage("DEVICE_PSEUDONYM_MAPPING_UNAVAILABLE");
    }
    static Stream<String> malformedIdentifiers() {
        String high = String.valueOf((char) 0xD800), low = String.valueOf((char) 0xDC00);
        return Stream.of(high, low, "a" + high + "b");
    }
    @Test void missingShortOrUnversionedKeysCannotActivateMapping() {
        assertThatThrownBy(() -> new DevicePseudonyms(null, 1)).hasMessage("DEVICE_PSEUDONYM_KEY_INVALID");
        assertThatThrownBy(() -> new DevicePseudonyms(new byte[31], 1)).hasMessage("DEVICE_PSEUDONYM_KEY_INVALID");
        assertThatThrownBy(() -> new DevicePseudonyms(new byte[32], 0)).hasMessage("DEVICE_PSEUDONYM_KEY_INVALID");
    }
    @Test void blankAndOversizedIdentifiersFailClosed() {
        var p = new DevicePseudonyms(new byte[32], 1);
        assertThatThrownBy(() -> p.customer("institution-a", " ")).hasMessage("DEVICE_PSEUDONYM_MAPPING_UNAVAILABLE");
        assertThatThrownBy(() -> p.device("institution-a", "x".repeat(513))).hasMessage("DEVICE_PSEUDONYM_MAPPING_UNAVAILABLE");
    }
}
