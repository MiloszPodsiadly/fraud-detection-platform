package com.frauddetection.alert.system;

import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.external.ExternalDurabilityGuarantee;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.audit.external.ExternalWitnessTimestampType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemTrustLevelControllerTest {

    @Test
    void shouldExposeFailClosedSignedExternalTrustLevel() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(metrics.postCommitAuditDegradedCount()).thenReturn(0L);
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                true,
                true,
                true,
                true,
                integrityService,
                sink,
                metrics
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_HEALTHY");
        assertThat(response.publicationEnabled()).isTrue();
        assertThat(response.publicationRequired()).isTrue();
        assertThat(response.failClosed()).isTrue();
        assertThat(response.externalAnchorStrength()).isEqualTo("SIGNED_EXTERNAL");
        assertThat(response.coverageStatus()).isEqualTo("HEALTHY");
        assertThat(response.witnessStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void shouldNotMarketBestEffortAsFdp24FailClosed() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(metrics.postCommitAuditDegradedCount()).thenReturn(0L);
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                false,
                false,
                false,
                false,
                integrityService,
                sink,
                metrics
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("BEST_EFFORT");
        assertThat(response.externalAnchorStrength()).isEqualTo("UNSIGNED_EXTERNAL");
    }

    @Test
    void shouldDowngradeFailClosedWhenCoverageIsDegraded() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                9,
                1,
                null,
                List.of(),
                false,
                100,
                null,
                null
        ));
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(metrics.postCommitAuditDegradedCount()).thenReturn(0L);
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                true,
                true,
                true,
                true,
                integrityService,
                sink,
                metrics
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.externalAnchorStrength()).isEqualTo("NONE");
    }

    @Test
    void shouldReturnNoneWhenPublicationDisabled() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        when(sink.capabilities()).thenReturn(new ExternalWitnessCapabilities(
                "DISABLED",
                "disabled",
                "NONE",
                ExternalImmutabilityLevel.NONE,
                false,
                false,
                false,
                false,
                ExternalWitnessTimestampType.APP_OBSERVED,
                "WEAK",
                false,
                false,
                false,
                ExternalDurabilityGuarantee.NONE
        ));
        SystemTrustLevelController controller = new SystemTrustLevelController(
                false,
                false,
                false,
                false,
                false,
                integrityService,
                sink,
                metrics
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("NONE");
        assertThat(response.externalAnchorStrength()).isEqualTo("NONE");
    }

    @Test
    void shouldDowngradeFailClosedWhenPostCommitAuditDegradedWasObserved() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(metrics.postCommitAuditDegradedCount()).thenReturn(1L);
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                true,
                true,
                true,
                true,
                integrityService,
                sink,
                metrics
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.postCommitAuditDegraded()).isEqualTo(1L);
    }

    private ExternalAuditAnchorCoverageResponse healthyCoverage() {
        return new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                10,
                0,
                0L,
                List.of(),
                false,
                100,
                null,
                null
        );
    }

    private ExternalWitnessCapabilities verifiedCapabilities() {
        return new ExternalWitnessCapabilities(
                "OBJECT_STORE",
                "object-store",
                "CROSS_ORG",
                ExternalImmutabilityLevel.ENFORCED,
                true,
                true,
                true,
                true,
                ExternalWitnessTimestampType.STORAGE_OBSERVED,
                "STRONG",
                true,
                true,
                true,
                ExternalDurabilityGuarantee.LEDGER
        );
    }
}
