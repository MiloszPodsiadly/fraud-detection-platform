package com.frauddetection.alert.audit.read;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveReadArchitectureTest {

    @Test
    void operationalSensitiveReadEndpointsMustUseCentralAuditPolicy() throws Exception {
        List<EndpointSource> endpoints = List.of(
                source("src/main/java/com/frauddetection/alert/system/SystemTrustLevelController.java", "/system/trust-level"),
                source("src/main/java/com/frauddetection/alert/trust/TrustIncidentController.java", "/api/v1/trust/incidents"),
                source("src/main/java/com/frauddetection/alert/audit/AuditEventController.java", "/api/v1/audit/events"),
                source("src/main/java/com/frauddetection/alert/audit/external/AuditEvidenceExportController.java", "/api/v1/audit/evidence/export"),
                source("src/main/java/com/frauddetection/alert/audit/external/ExternalAuditIntegrityController.java", "/api/v1/audit/integrity/external"),
                source("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationRecoveryController.java", "/api/v1/regulated-mutations"),
                source("src/main/java/com/frauddetection/alert/outbox/OutboxRecoveryController.java", "/api/v1/outbox"),
                source("src/main/java/com/frauddetection/alert/audit/AuditDegradationController.java", "/api/v1/audit/degradations")
        );

        for (EndpointSource endpoint : endpoints) {
            assertThat(endpoint.source())
                    .as(endpoint.description() + " must declare sensitive read audit")
                    .contains("@AuditedSensitiveRead")
                    .contains("SensitiveReadAuditService")
                    .contains("sensitiveReadAuditService.audit");
        }
    }

    @Test
    void auditedSensitiveReadAnnotationMustBeDocumentedAsMarkerOnly() throws Exception {
        String annotation = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/audit/read/AuditedSensitiveRead.java"
        ));

        assertThat(annotation)
                .contains("Marker-only annotation")
                .contains("does not execute auditing by itself")
                .contains("Architecture tests enforce");
    }

    @Test
    void sensitiveReadAuditMustNotPersistRawIdempotencyKeysOrPayloads() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationRecoveryController.java"
        ));
        String auditMethod = controller.substring(controller.indexOf("private void auditInspection("));

        assertThat(auditMethod)
                .contains("response.idempotencyKeyHash()")
                .doesNotContain("idempotencyKey,")
                .doesNotContain("getRequestURI")
                .doesNotContain("getQueryString");
    }

    private EndpointSource source(String path, String description) throws Exception {
        return new EndpointSource(Files.readString(Path.of(path)), description);
    }

    private record EndpointSource(String source, String description) {
    }
}
