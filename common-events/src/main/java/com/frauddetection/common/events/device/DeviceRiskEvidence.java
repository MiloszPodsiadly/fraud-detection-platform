package com.frauddetection.common.events.device;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Internal, occurrence-bound facts. Construction does not authenticate a source. */
@JsonPropertyOrder({"schemaVersion", "occurrenceKey", "institutionToken", "customerToken", "deviceToken",
        "keyVersion", "source", "eventTime", "windowStart", "asOf", "coverageThrough",
        "priorDevice", "otherCustomers", "switches", "country", "network"})
public record DeviceRiskEvidence(
        int schemaVersion,
        String occurrenceKey,
        String institutionToken,
        String customerToken,
        String deviceToken,
        int keyVersion,
        Source source,
        Instant eventTime,
        Instant windowStart,
        Instant asOf,
        Instant coverageThrough,
        HistoryFact priorDevice,
        HistoryFact otherCustomers,
        HistoryFact switches,
        CountryFact country,
        NetworkFact network
) {
    public static final int VERSION = 1;
    public static final int MAX_OBSERVATIONS = 25_000;
    public static final int MAX_OTHER_CUSTOMERS = 2_000;
    private static final Instant MIN_TIME = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("2100-01-01T00:00:00Z");
    private static final Duration MAX_WINDOW = Duration.ofDays(30);
    private static final Duration MAX_PROVIDER_AGE = Duration.ofMinutes(10);
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    public DeviceRiskEvidence {
        if (schemaVersion != VERSION) {
            throw invalid("VERSION");
        }
        occurrenceKey = token(occurrenceKey, "OCCURRENCE_KEY");
        institutionToken = token(institutionToken, "INSTITUTION_SCOPE");
        customerToken = optionalToken(customerToken, "CUSTOMER_TOKEN");
        deviceToken = optionalToken(deviceToken, "DEVICE_TOKEN");
        if (keyVersion < 1 || keyVersion > 65_535) {
            throw invalid("KEY_VERSION");
        }
        Objects.requireNonNull(source, "SOURCE_REQUIRED");
        eventTime = time(eventTime, "EVENT_TIME");
        windowStart = time(windowStart, "WINDOW_START");
        asOf = time(asOf, "AS_OF");
        if (!eventTime.equals(asOf) || !windowStart.isBefore(asOf)
                || Duration.between(windowStart, asOf).compareTo(MAX_WINDOW) > 0) {
            throw invalid("WINDOW");
        }
        if (source.observedAt().isBefore(asOf.minus(Duration.ofMinutes(5)))
                || source.observedAt().isAfter(asOf.plus(Duration.ofMinutes(2)))) {
            throw invalid("SOURCE_TIME");
        }
        if (coverageThrough != null) {
            coverageThrough = time(coverageThrough, "COVERAGE_TIME");
        }
        Objects.requireNonNull(priorDevice, "PRIOR_DEVICE_REQUIRED");
        Objects.requireNonNull(otherCustomers, "OTHER_CUSTOMERS_REQUIRED");
        Objects.requireNonNull(switches, "SWITCHES_REQUIRED");
        Objects.requireNonNull(country, "COUNTRY_REQUIRED");
        Objects.requireNonNull(network, "NETWORK_REQUIRED");
        if (otherCustomers.count() != null && otherCustomers.count() > MAX_OTHER_CUSTOMERS) {
            throw invalid("OTHER_CUSTOMERS_CAP");
        }
        boolean historyAvailable = priorDevice.state() == State.AVAILABLE
                || otherCustomers.state() == State.AVAILABLE || switches.state() == State.AVAILABLE;
        boolean anyAvailable = historyAvailable || country.state() == State.AVAILABLE
                || network.state() == State.AVAILABLE;
        if (anyAvailable && (source.quality() != SourceQuality.AUTHENTICATED_INSTITUTION
                || customerToken == null || deviceToken == null)) {
            throw invalid("UNTRUSTED_AVAILABLE");
        }
        if (historyAvailable && (coverageThrough == null || coverageThrough.isBefore(asOf))) {
            throw invalid("INCOMPLETE_HISTORY");
        }
        if (source.quality() == SourceQuality.CLIENT_ASSERTED || source.quality() == SourceQuality.SYNTHETIC) {
            Reason expected = source.quality() == SourceQuality.CLIENT_ASSERTED
                    ? Reason.CLIENT_ASSERTION : Reason.SYNTHETIC_INPUT;
            if (!isUntrusted(priorDevice.state(), priorDevice.reason(), expected)
                    || !isUntrusted(otherCustomers.state(), otherCustomers.reason(), expected)
                    || !isUntrusted(switches.state(), switches.reason(), expected)
                    || !isUntrusted(country.state(), country.reason(), expected)
                    || !isUntrusted(network.state(), network.reason(), expected)) {
                throw invalid("SOURCE_FACT_CONFLICT");
            }
        }
        if (country.state() == State.AVAILABLE) {
            fresh(country.providerObservedAt(), asOf, "COUNTRY_STALE");
        }
        if (network.state() == State.AVAILABLE) {
            fresh(network.providerObservedAt(), asOf, "NETWORK_STALE");
        }
    }

    /** The caller must supply the authenticated binding; equality of a payload field is not authentication. */
    public void requireBinding(String expectedOccurrenceKey, String expectedInstitutionToken) {
        if (!occurrenceKey.equals(expectedOccurrenceKey) || !institutionToken.equals(expectedInstitutionToken)) {
            throw invalid("BINDING_MISMATCH");
        }
    }

    /** Stable length-prefixed representation for an internal persisted commitment, never a public DTO. */
    public byte[] canonicalBytes() {
        StringBuilder out = new StringBuilder(1024);
        append(out, schemaVersion, occurrenceKey, institutionToken, customerToken, deviceToken, keyVersion,
                source.quality(), source.authorityCode(), source.sourceEventKey(), source.observedAt(),
                eventTime, windowStart, asOf, coverageThrough);
        append(out, priorDevice.state(), priorDevice.reason(), priorDevice.count(),
                otherCustomers.state(), otherCustomers.reason(), otherCustomers.count(),
                switches.state(), switches.reason(), switches.count());
        append(out, country.state(), country.reason(), country.deviceCountry(), country.referenceCountry(),
                country.providerCode(), country.providerObservedAt(), network.state(), network.reason(),
                network.proxyDetected(), network.vpnDetected(), network.providerCode(), network.providerObservedAt());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "DeviceRiskEvidence[redacted]";
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw invalid("UNKNOWN_FIELD");
    }

    private static void append(StringBuilder out, Object... values) {
        for (Object value : values) {
            String part = value == null ? null : value.toString();
            out.append(part == null ? -1 : part.length()).append(':');
            if (part != null) {
                out.append(part);
            }
        }
    }

    private static boolean isUntrusted(State state, Reason reason, Reason expected) {
        return state == State.UNTRUSTED && reason == expected;
    }

    private static void fresh(Instant observedAt, Instant asOf, String error) {
        if (observedAt.isBefore(asOf.minus(MAX_PROVIDER_AGE)) || observedAt.isAfter(asOf)) {
            throw invalid(error);
        }
    }

    private static String token(String value, String error) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw invalid(error);
        }
        return value;
    }

    private static String optionalToken(String value, String error) {
        return value == null ? null : token(value, error);
    }

    private static String code(String value, String error) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw invalid(error);
        }
        return value;
    }

    private static Instant time(Instant value, String error) {
        if (value == null || value.isBefore(MIN_TIME) || !value.isBefore(MAX_TIME)) {
            throw invalid(error);
        }
        return value;
    }

    private static void stateReason(State state, Reason reason) {
        Objects.requireNonNull(state, "STATE_REQUIRED");
        Objects.requireNonNull(reason, "REASON_REQUIRED");
        boolean valid = switch (state) {
            case AVAILABLE -> reason == Reason.NONE;
            case UNAVAILABLE -> reason == Reason.NO_SOURCE || reason == Reason.INCOMPLETE_HISTORY
                    || reason == Reason.KEY_UNAVAILABLE || reason == Reason.PROVIDER_MISSING
                    || reason == Reason.CAPACITY_EXCEEDED;
            case UNTRUSTED -> reason == Reason.CLIENT_ASSERTION || reason == Reason.SYNTHETIC_INPUT;
            case INVALID -> reason == Reason.INVALID_PROVENANCE || reason == Reason.INVALID_TIME;
        };
        if (!valid) {
            throw invalid("STATE_REASON");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException("DEVICE_EVIDENCE_" + code);
    }

    public enum SourceQuality { AUTHENTICATED_INSTITUTION, CLIENT_ASSERTED, SYNTHETIC }
    public enum State { AVAILABLE, UNAVAILABLE, UNTRUSTED, INVALID }
    public enum Reason { NONE, NO_SOURCE, INCOMPLETE_HISTORY, KEY_UNAVAILABLE, PROVIDER_MISSING,
        CAPACITY_EXCEEDED, CLIENT_ASSERTION, SYNTHETIC_INPUT, INVALID_PROVENANCE, INVALID_TIME }

    @JsonPropertyOrder({"quality", "authorityCode", "sourceEventKey", "observedAt"})
    public record Source(SourceQuality quality, String authorityCode, String sourceEventKey, Instant observedAt) {
        public Source {
            Objects.requireNonNull(quality, "SOURCE_QUALITY_REQUIRED");
            authorityCode = code(authorityCode, "SOURCE_AUTHORITY");
            sourceEventKey = token(sourceEventKey, "SOURCE_EVENT_KEY");
            observedAt = time(observedAt, "SOURCE_OBSERVED_AT");
        }

        @Override
        public String toString() {
            return "Source[redacted]";
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw invalid("UNKNOWN_FIELD");
        }
    }

    @JsonPropertyOrder({"state", "reason", "count"})
    public record HistoryFact(State state, Reason reason, Integer count) {
        public HistoryFact {
            stateReason(state, reason);
            if (state == State.AVAILABLE) {
                if (count == null || count < 0 || count > MAX_OBSERVATIONS) {
                    throw invalid("HISTORY_COUNT");
                }
            } else if (count != null) {
                throw invalid("HISTORY_WITHOUT_COVERAGE");
            }
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw invalid("UNKNOWN_FIELD");
        }

        @Override
        public String toString() {
            return "HistoryFact[redacted]";
        }
    }

    @JsonPropertyOrder({"state", "reason", "deviceCountry", "referenceCountry", "providerCode", "providerObservedAt"})
    public record CountryFact(State state, Reason reason, String deviceCountry, String referenceCountry,
                              String providerCode, Instant providerObservedAt) {
        public CountryFact {
            stateReason(state, reason);
            if (state == State.AVAILABLE) {
                if (deviceCountry == null || !COUNTRY.matcher(deviceCountry).matches()
                        || referenceCountry == null || !COUNTRY.matcher(referenceCountry).matches()) {
                    throw invalid("COUNTRY_CODE");
                }
                providerCode = code(providerCode, "COUNTRY_PROVIDER");
                providerObservedAt = time(providerObservedAt, "COUNTRY_OBSERVED_AT");
            } else if (deviceCountry != null || referenceCountry != null || providerCode != null
                    || providerObservedAt != null) {
                throw invalid("COUNTRY_WITHOUT_PROVIDER");
            }
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw invalid("UNKNOWN_FIELD");
        }

        @Override
        public String toString() {
            return "CountryFact[redacted]";
        }
    }

    @JsonPropertyOrder({"state", "reason", "proxyDetected", "vpnDetected", "providerCode", "providerObservedAt"})
    public record NetworkFact(State state, Reason reason, Boolean proxyDetected, Boolean vpnDetected,
                              String providerCode, Instant providerObservedAt) {
        public NetworkFact {
            stateReason(state, reason);
            if (state == State.AVAILABLE) {
                if (proxyDetected == null || vpnDetected == null) {
                    throw invalid("NETWORK_RESULT");
                }
                providerCode = code(providerCode, "NETWORK_PROVIDER");
                providerObservedAt = time(providerObservedAt, "NETWORK_OBSERVED_AT");
            } else if (proxyDetected != null || vpnDetected != null || providerCode != null
                    || providerObservedAt != null) {
                throw invalid("NETWORK_WITHOUT_PROVIDER");
            }
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw invalid("UNKNOWN_FIELD");
        }

        @Override
        public String toString() {
            return "NetworkFact[redacted]";
        }
    }
}
