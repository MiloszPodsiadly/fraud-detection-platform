package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AlertReadOnlyDetailBridgeDocsContractTest {

    @Test
    void alertReadOnlyDetailBridgeDocsContractTest() throws Exception {
        String docs = Files.readString(docPath());
        String lower = docs.toLowerCase(Locale.ROOT);

        assertThat(lower).contains("read-only navigation");
        assertThat(docs).contains("GET `/api/v1/alerts/{alertId}`");
        assertThat(lower).contains("does not introduce a second alert read api");
        assertThat(lower).contains("does not add a new backend endpoint");
        assertThat(lower).contains("does not mutate");
        assertThat(lower).contains("alert_read");
        assertThat(lower).contains("suspicious_transaction_read");
        assertThat(lower).contains("does not imply alert read access");
        assertThat(lower).contains("does not expose assistant summary");
        assertThat(lower).contains("does not expose an evidence proof panel");
        assertThat(lower).contains("not confirmed fraud");
        assertThat(lower).contains("not an analyst decision");
        assertThat(lower).contains("not a final outcome");
        assertThat(lower).contains("not a case lifecycle action");
        assertThat(lower).contains("not legal proof");
    }

    @Test
    void alertReadOnlyDetailBridgeDocsDoNotContainOverclaimWording() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).doesNotContain("fraud confirmed");
        assertThat(lower).doesNotContain("is confirmed fraud");
        assertThat(lower).doesNotContain("is legal proof");
        assertThat(lower).doesNotContain("is a complete investigation");
        assertThat(lower).doesNotContain("is an analyst disposition");
        assertThat(lower).doesNotContain("is a case outcome");
    }

    private Path docPath() {
        return DocumentationTestSupport.docsRoot().resolve("product/alert_read_only_detail_bridge.md");
    }
}
