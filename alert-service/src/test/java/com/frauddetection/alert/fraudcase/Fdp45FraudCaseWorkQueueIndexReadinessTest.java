package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.persistence.FraudCaseDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueIndexReadinessTest {

    @Test
    void everyAllowedWorkQueueSortFieldShouldHaveIndexSupport() throws Exception {
        for (String sortField : FraudCaseReadQueryPolicy.SORT_FIELDS) {
            Field field = FraudCaseDocument.class.getDeclaredField(sortField);
            assertThat(field.getAnnotation(Indexed.class))
                    .as("Allowed work queue sort field must be indexed: " + sortField)
                    .isNotNull();
        }
        assertThat(FraudCaseDocument.class.getDeclaredField("createdAt").getAnnotation(Indexed.class)).isNotNull();
        assertThat(FraudCaseDocument.class.getDeclaredField("updatedAt").getAnnotation(Indexed.class)).isNotNull();
    }
}
