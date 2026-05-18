package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceDocumentMappingTest {

    @Test
    void evidenceDocumentUsesFraudEvidenceCollectionAndStringReasonCode() throws NoSuchFieldException {
        Document document = EvidenceDocument.class.getAnnotation(Document.class);
        Field reasonCode = EvidenceDocument.class.getDeclaredField("reasonCode");

        assertThat(document.collection()).isEqualTo("fraud_evidence");
        assertThat(reasonCode.getType()).isEqualTo(String.class);
    }

    @Test
    void evidenceDocumentDoesNotExposeForbiddenVerdictOrProofFields() {
        Set<String> forbiddenFields = Set.of(
                "fraudConfirmed",
                "isFraud",
                "verdict",
                "finalOutcome",
                "proof",
                "legalProof",
                "worm",
                "notarized"
        );

        assertThat(EvidenceDocument.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContainAnyElementsOf(forbiddenFields);
    }

    @Test
    void documentFactoryRequiresSourceAndStatus() {
        assertThatThrownBy(() -> EvidenceDocument.create(null, EvidenceStatus.AVAILABLE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source is required");
        assertThatThrownBy(() -> EvidenceDocument.create(EvidenceSource.FRAUD_SCORING_SERVICE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status is required");
    }
}
