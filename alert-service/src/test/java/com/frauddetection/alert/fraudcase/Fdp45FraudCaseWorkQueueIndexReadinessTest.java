package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.persistence.FraudCaseDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

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

    @Test
    void recommendedCompoundWorkQueueIndexesShouldBeDeclaredInCode() {
        CompoundIndexes indexes = FraudCaseDocument.class.getAnnotation(CompoundIndexes.class);
        assertThat(indexes).isNotNull();
        Map<String, String> byName = Arrays.stream(indexes.value())
                .collect(Collectors.toMap(CompoundIndex::name, CompoundIndex::def));

        assertThat(byName)
                .containsEntry("fraud_case_wq_status_created_id_idx", "{'status': 1, 'createdAt': -1, '_id': 1}")
                .containsEntry("fraud_case_wq_assignee_created_id_idx", "{'assignedInvestigatorId': 1, 'createdAt': -1, '_id': 1}")
                .containsEntry("fraud_case_wq_priority_created_id_idx", "{'priority': 1, 'createdAt': -1, '_id': 1}")
                .containsEntry("fraud_case_wq_risk_created_id_idx", "{'riskLevel': 1, 'createdAt': -1, '_id': 1}")
                .containsEntry("fraud_case_wq_linked_alert_created_id_idx", "{'linkedAlertIds': 1, 'createdAt': -1, '_id': 1}")
                .containsEntry("fraud_case_wq_status_updated_id_idx", "{'status': 1, 'updatedAt': -1, '_id': 1}")
                .containsEntry("fraud_case_wq_assignee_updated_id_idx", "{'assignedInvestigatorId': 1, 'updatedAt': -1, '_id': 1}");
    }
}
