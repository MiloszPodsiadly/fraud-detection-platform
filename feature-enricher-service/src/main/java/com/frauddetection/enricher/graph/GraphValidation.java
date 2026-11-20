package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.regex.Pattern;

final class GraphValidation {
    private static final Pattern OPAQUE_TOKEN = Pattern.compile("[0-9a-f]{64}");

    private GraphValidation() { }

    static String token(String value) {
        if (value == null || !OPAQUE_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("GRAPH_REFERENCE_INVALID");
        }
        return value;
    }

    static int version(int value) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException("GRAPH_VERSION_INVALID");
        }
        return value;
    }

    static Instant millisecondInstant(Instant value) {
        if (value == null || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("GRAPH_TIMESTAMP_INVALID");
        }
        return value;
    }

    static Instant exactInstant(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("GRAPH_TIMESTAMP_INVALID");
        }
        return value;
    }
}
