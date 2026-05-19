package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionQueryTelemetryDocsContractTest {

    @Test
    void documentsBoundedQueryTelemetryContract() throws Exception {
        String docs = Files.readString(Path.of("../docs/product/suspicious_transaction_read_api.md"));
        String normalized = docs.toLowerCase();

        assertThat(docs).contains("## Query Telemetry");
        assertThat(normalized)
                .contains("future index decisions")
                .contains("low-cardinality")
                .contains("query shape")
                .contains("filter count bucket")
                .contains("result size bucket")
                .contains("hasnext")
                .contains("cursorused")
                .contains("timer histogram")
                .contains("telemetry label values are normalized at the telemetry boundary")
                .contains("production telemetry wiring is required")
                .contains("runtime telemetry recording failures do not alter api responses")
                .contains("missing telemetry beans must not silently disable telemetry")
                .contains("does not contain or")
                .contains("derive the cursor token value")
                .contains("slow query warning")
                .contains("raw identifiers")
                .contains("cursor token")
                .contains("raw filters")
                .contains("raw query")
                .contains("raw exception message")
                .contains("does not change api behavior")
                .contains("does not add filters")
                .contains("does not add indexes")
                .contains("does not guarantee latency")
                .contains("not a security control")
                .contains("not audit assurance")
                .contains("not fraud proof")
                .contains("not legal or regulatory evidence");
        assertThat(normalized).doesNotContain(
                "guaranteed performance",
                "guaranteed query speed",
                "fraud-proof",
                "legal proof",
                "security guarantee",
                "audit guarantee"
        );
    }
}
