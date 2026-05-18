package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRepositoryIndexTest {

    @Test
    void evidenceDocumentDefinesBoundedIndexedFields() {
        Set<String> indexedFields = Set.of(
                "transactionId",
                "alertId",
                "customerId",
                "correlationId",
                "reasonCode",
                "source",
                "status",
                "createdAt"
        );

        for (String indexedField : indexedFields) {
            assertThat(field(indexedField).isAnnotationPresent(Indexed.class))
                    .as(indexedField + " should be indexed")
                    .isTrue();
        }
    }

    private Field field(String name) {
        try {
            return EvidenceDocument.class.getDeclaredField(name);
        } catch (NoSuchFieldException exception) {
            throw new AssertionError(exception);
        }
    }
}
