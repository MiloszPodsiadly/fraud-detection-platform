package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationArchitectureTest {

    @Test
    void alertManagementServiceMustNotOrchestrateRegulatedDecisionMutationDirectly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/AlertManagementService.java"
        ));

        assertThat(source).doesNotContain("AuditMutationRecorder");
        assertThat(source).doesNotContain("auditService.audit");
        assertThat(source).doesNotContain("saveDecisionWithOutbox");
        assertThat(source).contains("submitDecisionRegulatedMutationService.submit");
    }

    @Test
    void requestPathMustNotExposeFullyAnchoredDecisionStatus() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/SubmitDecisionRegulatedMutationService.java"
        ));

        assertThat(source).doesNotContain("COMMITTED_FULLY_ANCHORED");
    }
}
