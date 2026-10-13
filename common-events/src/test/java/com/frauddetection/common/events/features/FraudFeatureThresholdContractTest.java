package com.frauddetection.common.events.features;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudFeatureThresholdContractTest {

    @Test
    void rapidTransferPredicateUsesSharedCountAndAmountBoundaries() {
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(1, new BigDecimal("20000.00"))).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(2, new BigDecimal("19999.99"))).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(2, new BigDecimal("20000.00"))).isTrue();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(3, new BigDecimal("20000.01"))).isTrue();
    }

    @Test
    void rapidTransferPredicateRejectsInvalidFactsBeforeClassification() {
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(-1, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                FraudFeatureValueBoundsContract.MAX_RECENT_TRANSACTION_COUNT + 1,
                BigDecimal.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(1, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                1,
                FraudFeatureValueBoundsContract.MAX_RECENT_AMOUNT_SUM_PLN.add(new BigDecimal("0.01"))
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FraudFeatureThresholdContract.isRapidTransferPlnBurst(1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rapidTransferSnapshotPredicateUsesCanonicalFactsAndEvidenceIds() {
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2")
        ))).isTrue();
    }

    @Test
    void rapidTransferAssessmentSeparatesApplicabilityThresholdAndEvidenceValidity() {
        Map<String, Object> validBurst = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2")
        );
        Map<String, Object> validSubthreshold = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("100.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1")
        );

        assertThat(FraudFeatureThresholdContract.assessRapidTransferPlnBurst(Map.of(), "txn-1", false).status())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.NOT_APPLICABLE);
        assertThat(FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                validSubthreshold,
                "txn-1",
                false
        ).status()).isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.NOT_A_BURST);
        FraudFeatureThresholdContract.RapidTransferAssessment accepted =
                FraudFeatureThresholdContract.assessRapidTransferPlnBurst(validBurst, "txn-2", false);
        assertThat(accepted.status()).isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.VALID_BURST);
        assertThat(accepted.transactionIds()).containsExactly("txn-1", "txn-2");

        FraudFeatureThresholdContract.RapidTransferAssessment invalidMembership =
                FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                        with(validBurst, FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS,
                                List.of("txn-1", "txn-1")),
                        "txn-2",
                        false
                );
        assertThat(invalidMembership.status())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.INVALID_BURST_EVIDENCE);
        assertThat(invalidMembership.issue())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferEvidenceIssue.MEMBERSHIP_INVALID);
    }

    @Test
    void explicitBurstClaimAndCurrentTransactionMembershipFailClosed() {
        FraudFeatureThresholdContract.RapidTransferAssessment absentClaimedEvidence =
                FraudFeatureThresholdContract.assessRapidTransferPlnBurst(Map.of(), "txn-2", true);
        assertThat(absentClaimedEvidence.status())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.INVALID_BURST_EVIDENCE);
        assertThat(absentClaimedEvidence.issue())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferEvidenceIssue.MEMBERSHIP_MISSING);

        FraudFeatureThresholdContract.RapidTransferAssessment missingCurrentTransaction =
                FraudFeatureThresholdContract.assessRapidTransferPlnBurst(Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                        FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                        FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-3")
                ), "txn-2", false);
        assertThat(missingCurrentTransaction.status())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.INVALID_BURST_EVIDENCE);
        assertThat(missingCurrentTransaction.issue())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferEvidenceIssue.CURRENT_TRANSACTION_MISSING);
    }

    @Test
    void recognizableQualifyingEvidenceWithMalformedCountFailsClosedWithoutAReasonCode() {
        Map<String, Object> recognizableBurst = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, "2",
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2")
        );

        FraudFeatureThresholdContract.RapidTransferAssessment stringCount =
                FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                        recognizableBurst,
                        "txn-2",
                        false
                );
        FraudFeatureThresholdContract.RapidTransferAssessment fractionalCount =
                FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                        with(
                                recognizableBurst,
                                FraudFeatureContract.RECENT_TRANSACTION_COUNT,
                                new BigDecimal("2.5")
                        ),
                        "txn-2",
                        false
                );

        assertThat(stringCount.status())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.INVALID_BURST_EVIDENCE);
        assertThat(stringCount.issue())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferEvidenceIssue.SNAPSHOT_INCONSISTENT);
        assertThat(fractionalCount.status())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.INVALID_BURST_EVIDENCE);
        assertThat(fractionalCount.issue())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferEvidenceIssue.SNAPSHOT_INCONSISTENT);

        FraudFeatureThresholdContract.RapidTransferAssessment contradictoryCount =
                FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                        with(recognizableBurst, FraudFeatureContract.RECENT_TRANSACTION_COUNT, 3),
                        "txn-2",
                        false
                );
        assertThat(contradictoryCount.status())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.INVALID_BURST_EVIDENCE);
        assertThat(contradictoryCount.issue())
                .isEqualTo(FraudFeatureThresholdContract.RapidTransferEvidenceIssue.MEMBERSHIP_INVALID);
    }

    @Test
    void noClaimPreservesSubthresholdAndUnrelatedClassifications() {
        Map<String, Object> countBelowThreshold = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1")
        );
        Map<String, Object> amountBelowThreshold = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("19999.99"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2")
        );

        assertThat(FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                countBelowThreshold,
                "txn-1",
                false
        ).status()).isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.NOT_A_BURST);
        assertThat(FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                amountBelowThreshold,
                "txn-2",
                false
        ).status()).isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.NOT_A_BURST);
        assertThat(FraudFeatureThresholdContract.assessRapidTransferPlnBurst(
                Map.of(FraudFeatureContract.COUNTRY_MISMATCH, true),
                "txn-2",
                false
        ).status()).isEqualTo(FraudFeatureThresholdContract.RapidTransferStatus.NOT_APPLICABLE);
    }

    @Test
    void rapidTransferSnapshotPredicateDoesNotFabricateIncompleteCases() {
        Map<String, Object> complete = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2")
        );

        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(Map.of())).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(without(complete, FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS))).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(without(complete, FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW))).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P1D",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2")
        ))).isFalse();
    }

    @Test
    void rapidTransferSnapshotPredicateFailsClosedForMalformedCanonicalFacts() {
        Map<String, Object> complete = Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2")
        );

        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_TRANSACTION_COUNT, new BigDecimal("2.9"))
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_TRANSACTION_COUNT, "2")
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_TRANSACTION_COUNT, true)
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_TRANSACTION_COUNT, -1)
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(
                        complete,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT,
                        FraudFeatureValueBoundsContract.MAX_RECENT_TRANSACTION_COUNT + 1
                )
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, "20000.00")
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("-0.01"))
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(
                        complete,
                        FraudFeatureContract.RECENT_AMOUNT_SUM_PLN,
                        FraudFeatureValueBoundsContract.MAX_RECENT_AMOUNT_SUM_PLN.add(new BigDecimal("0.01"))
                )
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT2M")
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1"))
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", " "))
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-1"))
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 3,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", "txn-2", "txn-2")
        ))).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", 2))
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("txn-1", " txn-2"))
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(complete, FraudFeatureContract.RECENT_TRANSACTION_COUNT, 3)
        )).isFalse();
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(
                with(
                        complete,
                        FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS,
                        java.util.Arrays.asList("txn-1", null)
                )
        )).isFalse();
    }

    private Map<String, Object> without(Map<String, Object> source, String key) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.remove(key);
        return Map.copyOf(copy);
    }

    private Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.put(key, value);
        return Map.copyOf(copy);
    }
}
