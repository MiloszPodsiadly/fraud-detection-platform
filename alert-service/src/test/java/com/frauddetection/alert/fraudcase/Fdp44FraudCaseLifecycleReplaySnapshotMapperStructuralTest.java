package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp44FraudCaseLifecycleReplaySnapshotMapperStructuralTest {

    @Test
    void replaySnapshotCarriesPublicResponseDtosInEnvelope() {
        Map<String, Class<?>> components = Arrays.stream(FraudCaseLifecycleReplaySnapshot.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));

        assertThat(components)
                .containsEntry("caseResponse", FraudCaseResponse.class)
                .containsEntry("noteResponse", FraudCaseNoteResponse.class)
                .containsEntry("decisionResponse", FraudCaseDecisionResponse.class);
    }

    @Test
    void mapperStoresAndReturnsStoredDtoInstances() {
        FraudCaseLifecycleReplaySnapshotMapper mapper = new FraudCaseLifecycleReplaySnapshotMapper();
        FraudCaseLifecycleIdempotencyCommand command = new FraudCaseLifecycleIdempotencyCommand(
                "key",
                "CREATE_FRAUD_CASE",
                "analyst-1",
                "CREATE",
                "request-hash",
                Instant.parse("2026-05-11T10:00:00Z")
        );
        Instant completedAt = Instant.parse("2026-05-11T10:00:01Z");
        FraudCaseResponse caseResponse = caseResponse();
        FraudCaseNoteResponse noteResponse = new FraudCaseNoteResponse(
                "note-1",
                "case-1",
                "body",
                "analyst-1",
                completedAt,
                true
        );
        FraudCaseDecisionResponse decisionResponse = new FraudCaseDecisionResponse(
                "decision-1",
                "case-1",
                FraudCaseDecisionType.FRAUD_CONFIRMED,
                "summary",
                "analyst-1",
                completedAt
        );

        FraudCaseLifecycleReplaySnapshot caseSnapshot = mapper.toSnapshot(command, caseResponse, completedAt);
        FraudCaseLifecycleReplaySnapshot noteSnapshot = mapper.toSnapshot(command, noteResponse, completedAt);
        FraudCaseLifecycleReplaySnapshot decisionSnapshot = mapper.toSnapshot(command, decisionResponse, completedAt);

        assertThat(caseSnapshot.caseResponse()).isSameAs(caseResponse);
        assertThat(noteSnapshot.noteResponse()).isSameAs(noteResponse);
        assertThat(decisionSnapshot.decisionResponse()).isSameAs(decisionResponse);
        assertThat(mapper.toResponse(caseSnapshot, FraudCaseResponse.class)).isSameAs(caseResponse);
        assertThat(mapper.toResponse(noteSnapshot, FraudCaseNoteResponse.class)).isSameAs(noteResponse);
        assertThat(mapper.toResponse(decisionSnapshot, FraudCaseDecisionResponse.class)).isSameAs(decisionResponse);
    }

    @Test
    void mapperSourceMustNotReconstructCaseResponseOrDependOnPersistenceDocument() throws Exception {
        String source = Files.readString(sourceRoot().resolve(Path.of("fraudcase", "FraudCaseLifecycleReplaySnapshotMapper.java")));

        assertThat(source)
                .doesNotContain("new FraudCaseResponse(")
                .doesNotContain("FraudCaseResponseMapper")
                .doesNotContain("AlertResponseMapper")
                .doesNotContain("FraudCaseDocument")
                .doesNotContain(".setCaseId(")
                .doesNotContain(".setStatus(")
                .doesNotContain(".setTransactionIds(")
                .doesNotContain(".setTransactions(");
    }

    private FraudCaseResponse caseResponse() {
        Instant now = Instant.parse("2026-05-11T10:00:00Z");
        return new FraudCaseResponse(
                "case-1",
                "FC-20260511-CASE0001",
                "customer-1",
                "RAPID_TRANSFER_BURST_20K_PLN",
                FraudCaseStatus.OPEN,
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                List.of("alert-1"),
                "investigator-1",
                "analyst-1",
                "reason",
                new BigDecimal("20000.00"),
                new BigDecimal("42500.25"),
                "PT5M",
                now,
                now,
                now,
                now,
                "analyst-1",
                "decision",
                List.of("tag-1"),
                now,
                null,
                null,
                1L,
                List.of("tx-1"),
                List.of()
        );
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
