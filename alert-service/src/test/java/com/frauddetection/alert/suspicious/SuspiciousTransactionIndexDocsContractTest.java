package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionIndexDocsContractTest {

    private static final Path DOCS = Path.of("..", "docs", "product", "suspicious_transaction_read_api.md");

    @Test
    void documentsMongoIndexSupportWithoutOverclaimingGuarantees() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("Mongo Index Support")
                .contains("idx_suspicious_tx_cursor_detected_at_id_desc")
                .contains("idx_suspicious_tx_status_cursor")
                .contains("idx_suspicious_tx_risk_cursor")
                .contains("idx_suspicious_tx_customer_cursor")
                .contains("idx_suspicious_tx_alert_cursor")
                .contains("suspicious_transaction_source_event_unique_idx")
                .contains("performance support only")
                .contains("does not change API behavior")
                .contains("runtime indexInfo inspection")
                .contains("Spring Data creates")
                .contains("maps to Mongo _id")
                .contains("document @Id")
                .contains("Cursor indexes use _id as the physical tie-breaker field")
                .contains("query planner behavior can depend on Mongo version, data distribution, and selectivity")
                .contains("not a security control")
                .contains("not confirmed-fraud evidence")
                .contains("audit assurance")
                .contains("legal or regulatory")
                .doesNotContain("guaranteed fast")
                .doesNotContain("fraud-proof")
                .doesNotContain("fraud proof")
                .doesNotContain("legal proof")
                .doesNotContain("security guarantee")
                .doesNotContain("audit guarantee")
                .doesNotContain("performance guarantee");
    }
}
