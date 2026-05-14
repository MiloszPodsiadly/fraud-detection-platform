package com.frauddetection.alert.security.config;

import com.frauddetection.alert.assistant.AnalystCaseSummaryUseCase;
import com.frauddetection.alert.audit.AuditDegradationController;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditEventController;
import com.frauddetection.alert.audit.AuditEventReadService;
import com.frauddetection.alert.audit.AuditIntegrityController;
import com.frauddetection.alert.audit.AuditIntegrityService;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.AuditTrustAttestationController;
import com.frauddetection.alert.audit.AuditTrustAttestationService;
import com.frauddetection.alert.audit.external.AuditEvidenceExportController;
import com.frauddetection.alert.audit.external.AuditEvidenceExportService;
import com.frauddetection.alert.audit.external.AuditTrustAuthorityClient;
import com.frauddetection.alert.audit.external.AuditTrustKeysController;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditCoverageRateLimiter;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityController;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.controller.AlertController;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.controller.FraudCaseWorkQueueSummaryController;
import com.frauddetection.alert.controller.ScoredTransactionController;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryController;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryProjectionService;
import com.frauddetection.alert.governance.audit.GovernanceAuditController;
import com.frauddetection.alert.governance.audit.GovernanceAuditService;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.OutboxRecoveryController;
import com.frauddetection.alert.outbox.OutboxRecoveryService;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationInspectionRateLimiter;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryController;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.security.session.AnalystSessionController;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.DecisionOutboxReconciliationController;
import com.frauddetection.alert.service.DecisionOutboxReconciliationService;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import com.frauddetection.alert.service.ScoredTransactionSearchPolicy;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.alert.system.SystemTrustLevelController;
import com.frauddetection.alert.trust.TrustIncidentController;
import com.frauddetection.alert.trust.TrustIncidentPreviewRateLimiter;
import com.frauddetection.alert.trust.TrustIncidentService;
import com.frauddetection.alert.trust.TrustSignalCollector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest({
        AlertController.class,
        FraudCaseController.class,
        FraudCaseWorkQueueSummaryController.class,
        ScoredTransactionController.class,
        AuditEventController.class,
        AuditIntegrityController.class,
        ExternalAuditIntegrityController.class,
        AuditEvidenceExportController.class,
        AuditTrustAttestationController.class,
        AuditTrustKeysController.class,
        AuditDegradationController.class,
        DecisionOutboxReconciliationController.class,
        RegulatedMutationRecoveryController.class,
        OutboxRecoveryController.class,
        TrustIncidentController.class,
        SystemTrustLevelController.class,
        GovernanceAdvisoryController.class,
        GovernanceAuditController.class,
        AnalystSessionController.class
})
@Import({
        AlertResponseMapper.class,
        FraudCaseResponseMapper.class,
        ScoredTransactionResponseMapper.class,
        ScoredTransactionSearchPolicy.class,
        AlertServiceExceptionHandler.class
})
@ActiveProfiles("test")
class RouteCoverageAgainstMvcMappingsTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean
    private AlertManagementUseCase alertManagementUseCase;

    @MockBean
    private AnalystCaseSummaryUseCase analystCaseSummaryUseCase;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockBean
    private FraudCaseQueryService fraudCaseQueryService;

    @MockBean
    private TransactionMonitoringUseCase transactionMonitoringUseCase;

    @MockBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockBean
    private AuditEventReadService auditEventReadService;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockBean
    private AuditIntegrityService auditIntegrityService;

    @MockBean
    private ExternalAuditIntegrityService externalAuditIntegrityService;

    @MockBean
    private ExternalAuditCoverageRateLimiter externalAuditCoverageRateLimiter;

    @MockBean
    private AuditEvidenceExportService auditEvidenceExportService;

    @MockBean
    private AuditTrustAttestationService auditTrustAttestationService;

    @MockBean
    private AuditTrustAuthorityClient auditTrustAuthorityClient;

    @MockBean
    private AuditDegradationService auditDegradationService;

    @MockBean
    private DecisionOutboxReconciliationService decisionOutboxReconciliationService;

    @MockBean
    private RegulatedMutationRecoveryService regulatedMutationRecoveryService;

    @MockBean
    private OutboxRecoveryService outboxRecoveryService;

    @MockBean
    private TrustIncidentService trustIncidentService;

    @MockBean
    private TrustSignalCollector trustSignalCollector;

    @MockBean
    private TrustIncidentPreviewRateLimiter trustIncidentPreviewRateLimiter;

    @MockBean
    private RegulatedMutationInspectionRateLimiter regulatedMutationInspectionRateLimiter;

    @MockBean
    private ReadAccessAuditService readAccessAuditService;

    @MockBean
    private AuditService auditService;

    @MockBean
    private ExternalAuditAnchorSink externalAuditAnchorSink;

    @MockBean
    private CurrentAnalystUser currentAnalystUser;

    @MockBean
    private AlertRepository alertRepository;

    @MockBean
    private GovernanceAuditService governanceAuditService;

    @MockBean
    private GovernanceAdvisoryProjectionService governanceAdvisoryProjectionService;

    @Test
    void everyApplicationControllerMappingHasExplicitFdp49SecurityOwnership() {
        var applicationMappings = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getName().startsWith("com.frauddetection.alert"))
                .toList();
        List<String> methodlessMappings = methodlessMappingViolations(applicationMappings);
        List<String> unmappedRoutes = applicationMappings.stream()
                .flatMap(this::routeDescriptors)
                .filter(route -> !SecurityRouteOwnershipRegistry.hasMvcOwnership(route.method(), route.pattern()))
                .map(route -> "Controller mapping " + route.method() + " " + route.pattern()
                        + " has no explicit FDP-49 security ownership.")
                .sorted()
                .toList();

        assertThat(methodlessMappings).as(String.join(System.lineSeparator(), methodlessMappings)).isEmpty();
        assertThat(unmappedRoutes).as(String.join(System.lineSeparator(), unmappedRoutes)).isEmpty();
    }

    @Test
    void everyApplicationControllerIsIncludedInFdp49MvcCoverage() throws IOException {
        Set<String> coveredControllers = Arrays.stream(RouteCoverageAgainstMvcMappingsTest.class
                        .getAnnotation(WebMvcTest.class)
                        .value())
                .map(Class::getName)
                .collect(java.util.stream.Collectors.toSet());

        List<String> missingControllers = discoverApplicationControllers().stream()
                .filter(controller -> !coveredControllers.contains(controller))
                .map(controller -> "Controller " + controller
                        + " is not included in FDP-49 MVC route ownership coverage.")
                .sorted()
                .toList();

        assertThat(missingControllers).as(String.join(System.lineSeparator(), missingControllers)).isEmpty();
    }

    @Test
    void methodlessMappingsAreRejectedByFdp49Guard() throws NoSuchMethodException {
        Method method = MethodlessController.class.getDeclaredMethod("methodless");
        RequestMappingInfo mapping = RequestMappingInfo.paths("/methodless").build();
        HandlerMethod handlerMethod = new HandlerMethod(new MethodlessController(), method);

        assertThat(methodlessMappingViolations(List.of(Map.entry(mapping, handlerMethod))))
                .containsExactly("FDP-49 requires explicit HTTP methods for security-owned controller mapping: "
                        + "/methodless handled by MethodlessController#methodless.");
    }

    private Stream<RouteDescriptor> routeDescriptors(Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        Set<String> patterns = entry.getKey().getPathPatternsCondition() == null
                ? entry.getKey().getPatternsCondition().getPatterns()
                : entry.getKey().getPathPatternsCondition().getPatternValues();
        Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();

        return patterns.stream()
                .sorted(Comparator.naturalOrder())
                .flatMap(pattern -> methods.stream()
                        .map(method -> new RouteDescriptor(method.name(), pattern)));
    }

    private List<String> methodlessMappingViolations(List<Map.Entry<RequestMappingInfo, HandlerMethod>> mappings) {
        return mappings.stream()
                .filter(entry -> entry.getKey().getMethodsCondition().getMethods().isEmpty())
                .flatMap(entry -> patterns(entry.getKey())
                        .map(pattern -> "FDP-49 requires explicit HTTP methods for security-owned controller mapping: "
                                + pattern + " handled by " + entry.getValue().getBeanType().getSimpleName()
                                + "#" + entry.getValue().getMethod().getName() + "."))
                .sorted()
                .toList();
    }

    private Stream<String> patterns(RequestMappingInfo info) {
        Set<String> patterns = info.getPathPatternsCondition() == null
                ? info.getPatternsCondition().getPatterns()
                : info.getPathPatternsCondition().getPatternValues();
        return patterns.stream().sorted(Comparator.naturalOrder());
    }

    private List<String> discoverApplicationControllers() throws IOException {
        return SecurityRuleSource.discoveredApplicationControllerClasses().stream().toList();
    }

    private record RouteDescriptor(String method, String pattern) {
    }

    private static final class MethodlessController {

        @SuppressWarnings("unused")
        void methodless() {
        }
    }
}
