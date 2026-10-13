package com.frauddetection.alert.system.trustlevel;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import com.frauddetection.alert.system.trustlevel.application.SystemTrustLevelService;
import com.frauddetection.alert.system.trustlevel.application.TrustPostureEvaluator;
import com.frauddetection.alert.system.trustlevel.health.LiveTrustStateCollector;
import com.frauddetection.alert.system.trustlevel.health.OutboxRecoveryHealthCollector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemTrustLevelSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    ApplicationConversionService.getSharedInstance()
            ))
            .withUserConfiguration(TrustLevelConfiguration.class)
            .withBean(ExternalAuditIntegrityService.class, () -> mock(ExternalAuditIntegrityService.class))
            .withBean(ExternalAuditAnchorSink.class, () -> mock(ExternalAuditAnchorSink.class))
            .withBean(AuditDegradationService.class, () -> mock(AuditDegradationService.class))
            .withBean(RegulatedMutationRecoveryService.class, () -> mock(RegulatedMutationRecoveryService.class));

    @Test
    void wiresCollectorsEvaluatorAndThinApplicationCoordinator() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxRecoveryHealthCollector.class);
            assertThat(context).hasSingleBean(LiveTrustStateCollector.class);
            assertThat(context).hasSingleBean(TrustPostureEvaluator.class);
            assertThat(context).hasSingleBean(SystemTrustLevelService.class);
            assertThat(context.getBean(SystemTrustLevelService.class).trustLevel().reasonCode())
                    .isEqualTo("COVERAGE_UNAVAILABLE");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            OutboxRecoveryHealthCollector.class,
            LiveTrustStateCollector.class,
            TrustPostureEvaluator.class,
            SystemTrustLevelService.class
    })
    static class TrustLevelConfiguration {
    }
}
