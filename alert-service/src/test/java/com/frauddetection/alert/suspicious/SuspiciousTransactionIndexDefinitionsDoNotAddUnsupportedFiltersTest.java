package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexedFieldNames;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexesByName;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionIndexDefinitionsDoNotAddUnsupportedFiltersTest {

    private static final Set<String> ALLOWED_INDEX_FIELDS = Set.of(
            "detectedAt",
            "_id",
            "status",
            "riskLevel",
            "customerId",
            "linkedAlertId",
            "transactionId",
            "sourceEventId"
    );

    @Test
    void indexDefinitionsStayLimitedToFdp62CursorAccessPatterns() {
        indexesByName().keySet().forEach(indexName ->
                assertThat(keys(indexName).keySet())
                        .as(indexName)
                        .allMatch(ALLOWED_INDEX_FIELDS::contains)
        );
    }

    @Test
    void documentDoesNotDeclareAdditionalSingleFieldIndexes() {
        assertThat(indexedFieldNames()).isEmpty();
    }

    @Test
    void noUnsupportedIndexFormsAreDeclared() {
        indexesByName().values().forEach(index -> assertThat(index.def())
                .doesNotContain("$**", "text", "hashed", "expireAfterSeconds"));
    }
}
