package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.service.FraudCaseLifecycleService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp44FraudCaseLifecycleReplaySnapshotCoverageTest {

    private static final Set<Class<?>> SUPPORTED_REPLAY_RESPONSE_TYPES = Set.of(
            FraudCaseDocument.class,
            FraudCaseNoteResponse.class,
            FraudCaseDecisionResponse.class
    );

    @Test
    void publicLifecycleMutationReturnTypesMustBeReplaySnapshotMapped() throws Exception {
        List<Method> lifecycleMutations = List.of(
                FraudCaseLifecycleService.class.getMethod("createCase", CreateFraudCaseRequest.class, String.class),
                FraudCaseLifecycleService.class.getMethod("assignCase", String.class, AssignFraudCaseRequest.class, String.class),
                FraudCaseLifecycleService.class.getMethod("addNote", String.class, AddFraudCaseNoteRequest.class, String.class),
                FraudCaseLifecycleService.class.getMethod("addDecision", String.class, AddFraudCaseDecisionRequest.class, String.class),
                FraudCaseLifecycleService.class.getMethod("transitionCase", String.class, TransitionFraudCaseRequest.class, String.class),
                FraudCaseLifecycleService.class.getMethod("closeCase", String.class, CloseFraudCaseRequest.class, String.class),
                FraudCaseLifecycleService.class.getMethod("reopenCase", String.class, ReopenFraudCaseRequest.class, String.class)
        );

        assertThat(lifecycleMutations)
                .extracting(Method::getReturnType)
                .allSatisfy(returnType -> assertThat(SUPPORTED_REPLAY_RESPONSE_TYPES)
                        .as(returnType.getSimpleName() + " must be explicitly replay-snapshot mapped")
                        .contains(returnType));
    }

    @Test
    void mapperCreatesExplicitSnapshotForEverySupportedLifecycleResponseType() {
        FraudCaseLifecycleReplaySnapshotMapper mapper = new FraudCaseLifecycleReplaySnapshotMapper();
        FraudCaseLifecycleIdempotencyCommand command = new FraudCaseLifecycleIdempotencyCommand(
                "coverage-key",
                "ADD_FRAUD_CASE_NOTE",
                "analyst-1",
                "case-1",
                "request-hash-1",
                Instant.parse("2026-05-11T10:00:00Z")
        );
        Instant completedAt = Instant.parse("2026-05-11T10:00:15Z");
        Map<Class<?>, Object> examples = Map.of(
                FraudCaseDocument.class, caseDocument(),
                FraudCaseNoteResponse.class, new FraudCaseNoteResponse(
                        "note-1",
                        "case-1",
                        "Mapped note",
                        "analyst-1",
                        Instant.parse("2026-05-11T10:01:00Z"),
                        false
                ),
                FraudCaseDecisionResponse.class, new FraudCaseDecisionResponse(
                        "decision-1",
                        "case-1",
                        FraudCaseDecisionType.FRAUD_CONFIRMED,
                        "Mapped decision",
                        "analyst-1",
                        Instant.parse("2026-05-11T10:02:00Z")
                )
        );

        assertThat(examples.keySet()).containsExactlyInAnyOrderElementsOf(SUPPORTED_REPLAY_RESPONSE_TYPES);
        examples.forEach((type, response) -> {
            FraudCaseLifecycleReplaySnapshot snapshot = mapper.toSnapshot(command, response, completedAt);
            assertThat(snapshot).as(type.getSimpleName()).isNotNull();
            assertThat(snapshot.snapshotType()).as(type.getSimpleName()).isNotNull();
            assertThat(snapshot.completedAt()).as(type.getSimpleName()).isEqualTo(completedAt);
        });
    }

    private FraudCaseDocument caseDocument() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setCaseNumber("FC-20260511-ABCDEF12");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setLinkedAlertIds(List.of("alert-1"));
        document.setCreatedBy("analyst-1");
        document.setReason("Manual investigation");
        document.setCreatedAt(Instant.parse("2026-05-11T09:59:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-11T10:00:00Z"));
        return document;
    }
}
