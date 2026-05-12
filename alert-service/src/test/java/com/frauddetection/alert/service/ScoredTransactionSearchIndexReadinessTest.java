package com.frauddetection.alert.service;

import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoredTransactionSearchIndexReadinessTest {

    @Test
    void shouldIndexNormalizedSearchAndStableFilterFields() {
        assertThat(indexedFields()).contains(
                "transactionIdSearch",
                "customerIdSearch",
                "merchantIdSearch",
                "currencySearch",
                "riskLevel",
                "alertRecommended",
                "scoredAt"
        );
    }

    private Set<String> indexedFields() {
        return java.util.Arrays.stream(ScoredTransactionDocument.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Indexed.class))
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
    }
}
