package com.frauddetection.alert.audit.read;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class SensitiveReadArchitectureTest {

    @Test
    void auditedSensitiveReadAnnotationExposesRouteMetadata() throws Exception {
        assertThat(AuditedSensitiveRead.class.getDeclaredMethod("category").getReturnType())
                .isEqualTo(ReadAccessEndpointCategory.class);
        assertThat(AuditedSensitiveRead.class.getDeclaredMethod("resourceType").getReturnType())
                .isEqualTo(ReadAccessResourceType.class);
        assertThat(AuditedSensitiveRead.class.getDeclaredMethod("resourceIdVariable").getReturnType())
                .isEqualTo(String.class);
        assertThat(AuditedSensitiveRead.class.getDeclaredMethod("resourceIdParameter").getReturnType())
                .isEqualTo(String.class);
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
}
