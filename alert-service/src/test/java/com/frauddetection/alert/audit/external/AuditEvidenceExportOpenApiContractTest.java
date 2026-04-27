package com.frauddetection.alert.audit.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEvidenceExportOpenApiContractTest {

    @Test
    void shouldDeclareFrozenEvidenceExportContract() throws Exception {
        String openApi = Files.readString(openApiPath());

        assertThat(openApi).contains("/api/v1/audit/evidence/export:");
        assertThat(openApi).contains("name: strict");
        assertThat(openApi).contains("enum: [AVAILABLE, PARTIAL, UNAVAILABLE]");
        assertThat(openApi).contains("enum: [AVAILABLE, PARTIAL, UNAVAILABLE, DISABLED]");
        assertThat(openApi).contains("EXTERNAL_ANCHORS_UNAVAILABLE");
        assertThat(openApi).contains("EXTERNAL_ANCHOR_GAPS");
        assertThat(openApi).contains("INTERNAL_ERROR");
        assertThat(openApi).contains("anchor_coverage:");
        assertThat(openApi).contains("export_fingerprint:");
        assertThat(openApi).contains("AuditEvidenceExportAnchorCoverage:");
    }

    private Path openApiPath() {
        Path fromRoot = Path.of("docs", "openapi", "alert-service.openapi.yaml");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "docs", "openapi", "alert-service.openapi.yaml");
    }
}
