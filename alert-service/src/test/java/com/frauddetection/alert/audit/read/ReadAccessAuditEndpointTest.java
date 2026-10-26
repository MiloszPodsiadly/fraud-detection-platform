package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.ScoredTransactionSearchValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ReadAccessAuditEndpointTest.SensitiveReadController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        ReadAccessAuditEndpointTest.SensitiveReadController.class,
        ReadAccessAuditEndpointTest.AuditEndpointTestConfig.class,
        ReadAccessAuditClassifier.class,
        ReadAccessResultCountExtractor.class,
        ReadAccessAuditService.class,
        ReadAccessAuditResponseAdvice.class,
        ReadAccessAuditFailureInterceptor.class,
        ReadAccessAuditWebConfig.class,
        AlertServiceExceptionHandler.class
})
class ReadAccessAuditEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReadAccessAuditRepository repository;

    @MockitoBean
    private CurrentAnalystUser currentAnalystUser;

    @MockitoBean
    private SensitiveReadAuditPolicy sensitiveReadAuditPolicy;

    @MockitoBean
    private AlertServiceMetrics alertServiceMetrics;

    @BeforeEach
    void setUp() {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(false);
        when(currentAnalystUser.get()).thenReturn(Optional.of(new AnalystPrincipal(
                "opsadmin",
                java.util.Set.of(AnalystRole.FRAUD_OPS_ADMIN),
                java.util.Set.of("transaction-monitor:read")
        )));
    }

    @Test
    void shouldAuditSensitiveReadEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/alert-1").header("X-Correlation-Id", "corr-alert"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/fraud-cases/case-1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/transactions/scored")
                        .queryParam("page", "0")
                        .queryParam("size", "2")
                        .queryParam("card_number", "4111111111111111"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/governance/advisories")
                        .queryParam("severity", "HIGH")
                        .queryParam("model_version", "2026-04-21.trained.v1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/governance/advisories/advisory-1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/governance/advisories/advisory-1/audit"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/governance/advisories/analytics")
                        .queryParam("window_days", "7"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository, org.mockito.Mockito.times(7)).save(captor.capture());
        List<ReadAccessAuditEventDocument> documents = captor.getAllValues();

        assertThat(documents).extracting(ReadAccessAuditEventDocument::endpointCategory)
                .containsExactly(
                        ReadAccessEndpointCategory.ALERT_DETAIL,
                        ReadAccessEndpointCategory.FRAUD_CASE_DETAIL,
                        ReadAccessEndpointCategory.SCORED_TRANSACTION_SEARCH,
                        ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_LIST,
                        ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_DETAIL,
                        ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_AUDIT_HISTORY,
                        ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_ANALYTICS
                );
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.action()).isEqualTo(ReadAccessAuditAction.READ);
            assertThat(document.actorId()).isEqualTo("opsadmin");
            assertThat(document.outcome()).isEqualTo(ReadAccessAuditOutcome.SUCCESS);
            assertThat(document.sourceService()).isEqualTo("alert-service");
            assertThat(document.schemaVersion()).isEqualTo(1);
        });
        assertThat(documents.stream()
                .filter(document -> document.endpointCategory() == ReadAccessEndpointCategory.SCORED_TRANSACTION_SEARCH)
                .findFirst()
                .orElseThrow()
                .resultCount()).isEqualTo(2);
        assertThat(documents.toString())
                .doesNotContain("4111111111111111", "card_number", "alert payload", "customer-1", "account-1", "review note");
    }

    @Test
    void executableHandlerPolicyPersistsExactlyOneGovernanceReadEvent() throws Exception {
        mockMvc.perform(get("/api/v1/governance/policy-trust/requests/request-17/candidate")
                        .header("X-Correlation-Id", "corr-policy-candidate"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository, org.mockito.Mockito.times(1)).save(captor.capture());
        ReadAccessAuditEventDocument event = captor.getValue();
        assertThat(event.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.POLICY_TRUST_CANDIDATE);
        assertThat(event.resourceType()).isEqualTo(ReadAccessResourceType.POLICY_TRUST);
        assertThat(event.resourceId()).isEqualTo("request-17");
        assertThat(event.actorId()).isEqualTo("opsadmin");
        assertThat(event.outcome()).isEqualTo(ReadAccessAuditOutcome.SUCCESS);
        assertThat(event.correlationId()).isEqualTo("corr-policy-candidate");
    }

    @Test
    void strictAuditFailureSuppressesSensitiveCandidateBodyAndReturnsUnavailable() throws Exception {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("private mongo endpoint detail"))
                .when(repository).save(any());

        var result = mockMvc.perform(get("/api/v1/governance/policy-trust/requests/request-17/candidate")
                        .header("X-Correlation-Id", "corr-policy-candidate"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Sensitive read audit unavailable.")
                .doesNotContain("verified", "request-17", "private mongo endpoint detail");
        verify(repository, org.mockito.Mockito.times(1)).save(any());
        verify(alertServiceMetrics, org.mockito.Mockito.times(1))
                .recordReadAccessAuditPersistenceFailure(ReadAccessEndpointCategory.POLICY_TRUST_CANDIDATE);
    }

    @Test
    void strictAuditFailureForBodylessInstallationReadReplacesSuccessStatus() throws Exception {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("mongo unavailable"))
                .when(repository).save(any());

        var result = mockMvc.perform(get("/api/v1/governance/policy-trust/requests/request-17/installation"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Sensitive read audit unavailable.")
                .doesNotContain("installation", "request-17", "mongo unavailable");
        verify(repository, org.mockito.Mockito.times(1)).save(any());
        verify(alertServiceMetrics, org.mockito.Mockito.times(1))
                .recordReadAccessAuditPersistenceFailure(ReadAccessEndpointCategory.POLICY_TRUST_INSTALLATION);
    }

    @Test
    void strictAuditFailureForNotModifiedReadReplacesConditionalSuccess() throws Exception {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("mongo unavailable"))
                .when(repository).save(any());

        var result = mockMvc.perform(get("/api/v1/governance/policy-trust/requests/request-17/not-modified"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Sensitive read audit unavailable.")
                .doesNotContain("request-17", "mongo unavailable");
        verify(repository, org.mockito.Mockito.times(1)).save(any());
        verify(alertServiceMetrics, org.mockito.Mockito.times(1))
                .recordReadAccessAuditPersistenceFailure(ReadAccessEndpointCategory.POLICY_TRUST_INSTALLATION);
    }

    @Test
    void strictPolicyRejectsStreamingSensitiveResponseBeforeControllerRuns() throws Exception {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(true);

        var result = mockMvc.perform(get("/api/v1/governance/policy-trust/requests/request-17/stream"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("stream secret", "request-17");
        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().endpointCategory()).isEqualTo(ReadAccessEndpointCategory.POLICY_TRUST_INSTALLATION);
        assertThat(captor.getValue().outcome()).isEqualTo(ReadAccessAuditOutcome.FAILED);
    }

    @Test
    void strictReadAuditPreservesForbiddenStatusAndAuditsDeniedAccessOnce() throws Exception {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(true);

        var result = mockMvc.perform(get("/api/v1/governance/policy-trust/requests/request-17/denied"))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("private", "request-17");
        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().endpointCategory()).isEqualTo(ReadAccessEndpointCategory.POLICY_TRUST_CANDIDATE);
        assertThat(captor.getValue().outcome()).isEqualTo(ReadAccessAuditOutcome.REJECTED);
    }

    @Test
    void strictAuditFailureSuppressesGovernanceEmergencyHistoryAndCommitmentBodies() throws Exception {
        when(sensitiveReadAuditPolicy.failClosed()).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("private mongo endpoint detail"))
                .when(repository).save(any());

        List<String> sensitiveReads = List.of(
                "/api/v1/governance/model-promotion/emergency-authorizations/active",
                "/api/v1/governance/model-promotion/emergency-authorizations/auth-17/audit",
                "/api/v1/governance/model-policies/policy-17/history",
                "/api/v1/governance/model-policies/policy-17/history/page",
                "/api/v1/governance/audit/commitments/ALERT/event-17"
        );
        for (String path : sensitiveReads) {
            var result = mockMvc.perform(get(path))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .contains("Sensitive read audit unavailable.")
                    .doesNotContain("sensitive", "auth-17", "policy-17", "event-17", "private mongo endpoint detail");
        }

        verify(repository, org.mockito.Mockito.times(sensitiveReads.size())).save(any());
        verify(alertServiceMetrics).recordReadAccessAuditPersistenceFailure(
                ReadAccessEndpointCategory.MODEL_PROMOTION_EMERGENCY_ACTIVE);
        verify(alertServiceMetrics).recordReadAccessAuditPersistenceFailure(
                ReadAccessEndpointCategory.MODEL_PROMOTION_EMERGENCY_AUDIT);
        verify(alertServiceMetrics).recordReadAccessAuditPersistenceFailure(
                ReadAccessEndpointCategory.MODEL_POLICY_HISTORY);
        verify(alertServiceMetrics).recordReadAccessAuditPersistenceFailure(
                ReadAccessEndpointCategory.MODEL_POLICY_HISTORY_PAGE);
        verify(alertServiceMetrics).recordReadAccessAuditPersistenceFailure(
                ReadAccessEndpointCategory.GOVERNANCE_AUDIT_COMMITMENT);
    }

    @Test
    void shouldAuditRejectedAndFailedScoredTransactionSearchWithoutRawQuery() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored")
                        .queryParam("query", "zz"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions/scored")
                        .queryParam("query", "fail-customer-123"))
                .andExpect(status().isInternalServerError());

        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());

        assertThat(captor.getAllValues()).extracting(ReadAccessAuditEventDocument::endpointCategory)
                .containsExactly(ReadAccessEndpointCategory.SCORED_TRANSACTION_SEARCH, ReadAccessEndpointCategory.SCORED_TRANSACTION_SEARCH);
        assertThat(captor.getAllValues()).extracting(ReadAccessAuditEventDocument::outcome)
                .containsExactly(ReadAccessAuditOutcome.REJECTED, ReadAccessAuditOutcome.FAILED);
        assertThat(captor.getAllValues()).allSatisfy(document -> {
            assertThat(document.queryHash()).hasSize(32);
            assertThat(document.resultCount()).isZero();
        });
        assertThat(captor.getAllValues().toString())
                .doesNotContain("zz", "fail-customer-123", "query=");
    }

    @Test
    void shouldNotBreakReadEndpointWhenReadAuditPersistenceFails() throws Exception {
        doThrow(new IllegalStateException("mongo internal down")).when(repository).save(any());

        mockMvc.perform(get("/api/v1/alerts/alert-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    static class AuditEndpointTestConfig {

        @Bean
        AlertServiceMetrics alertServiceMetrics() {
            return new AlertServiceMetrics(new SimpleMeterRegistry());
        }
    }

    @RestController
    public static class SensitiveReadController {

        @GetMapping("/api/v1/alerts/{alertId}")
        Map<String, Object> alert(@PathVariable String alertId) {
            return Map.of("alertId", alertId, "payload", "alert payload", "customerId", "customer-1");
        }

        @GetMapping("/api/v1/fraud-cases/{caseId}")
        Map<String, Object> fraudCase(@PathVariable String caseId) {
            return Map.of("caseId", caseId, "accountId", "account-1");
        }

        @GetMapping("/api/v1/transactions/scored")
        PagedResponse<Map<String, Object>> scoredTransactions(
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "25") int size,
                @RequestParam(required = false) String query
        ) {
            if ("zz".equals(query)) {
                throw new ScoredTransactionSearchValidationException("INVALID_FILTER");
            }
            if ("fail-customer-123".equals(query)) {
                throw new IllegalStateException("mongo unavailable");
            }
            return new PagedResponse<>(
                    List.of(Map.of("transactionId", "txn-1"), Map.of("transactionId", "txn-2")),
                    2,
                    1,
                    page,
                    size
            );
        }

        @GetMapping("/governance/advisories")
        Map<String, Object> advisories() {
            return Map.of("status", "AVAILABLE", "advisory_events", List.of(Map.of("event_id", "advisory-1")));
        }

        @GetMapping("/governance/advisories/{eventId}")
        Map<String, Object> advisory(@PathVariable String eventId) {
            return Map.of("event_id", eventId, "note", "review note");
        }

        @GetMapping("/governance/advisories/{eventId}/audit")
        Map<String, Object> advisoryAudit(@PathVariable String eventId) {
            return Map.of("advisory_event_id", eventId, "audit_events", List.of(Map.of("decision", "ACKNOWLEDGED")));
        }

        @GetMapping("/governance/advisories/analytics")
        Map<String, Object> advisoryAnalytics() {
            return Map.of("status", "AVAILABLE", "totals", Map.of("advisories", 1));
        }

        @GetMapping("/api/v1/governance/policy-trust/requests/{id}/candidate")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_CANDIDATE,
                resourceType = ReadAccessResourceType.POLICY_TRUST,
                resourceIdVariable = "id")
        Map<String, Object> policyTrustCandidate(@org.springframework.web.bind.annotation.PathVariable String id) {
            return Map.of("requestId", id, "candidate", "verified");
        }

        @GetMapping("/api/v1/governance/policy-trust/requests/{id}/installation")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_INSTALLATION,
                resourceType = ReadAccessResourceType.POLICY_TRUST,
                resourceIdVariable = "id")
        org.springframework.http.ResponseEntity<Void> policyTrustInstallation() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }

        @GetMapping("/api/v1/governance/policy-trust/requests/{id}/not-modified")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_INSTALLATION,
                resourceType = ReadAccessResourceType.POLICY_TRUST,
                resourceIdVariable = "id")
        org.springframework.http.ResponseEntity<Void> policyTrustNotModified() {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_MODIFIED).build();
        }

        @GetMapping("/api/v1/governance/policy-trust/requests/{id}/stream")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_INSTALLATION,
                resourceType = ReadAccessResourceType.POLICY_TRUST,
                resourceIdVariable = "id")
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody policyTrustStream() {
            return output -> output.write("stream secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @GetMapping("/api/v1/governance/policy-trust/requests/{id}/denied")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_CANDIDATE,
                resourceType = ReadAccessResourceType.POLICY_TRUST,
                resourceIdVariable = "id")
        Map<String, Object> policyTrustCandidateDenied(@org.springframework.web.bind.annotation.PathVariable String id) {
            throw new org.springframework.security.access.AccessDeniedException("denied");
        }

        @GetMapping("/api/v1/governance/model-promotion/emergency-authorizations/active")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_PROMOTION_EMERGENCY_ACTIVE,
                resourceType = ReadAccessResourceType.MODEL_PROMOTION_EMERGENCY_AUTHORIZATION)
        Map<String, Object> activeEmergencyAuthorizations() {
            return Map.of("authorization", "sensitive authorization");
        }

        @GetMapping("/api/v1/governance/model-promotion/emergency-authorizations/{id}/audit")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_PROMOTION_EMERGENCY_AUDIT,
                resourceType = ReadAccessResourceType.MODEL_PROMOTION_EMERGENCY_AUTHORIZATION,
                resourceIdVariable = "id")
        Map<String, Object> emergencyAuthorizationAudit(@PathVariable String id) {
            return Map.of("authorizationId", id, "audit", "sensitive");
        }

        @GetMapping("/api/v1/governance/model-policies/{id}/history")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_HISTORY,
                resourceType = ReadAccessResourceType.MODEL_POLICY,
                resourceIdVariable = "id")
        List<Map<String, String>> policyHistory(@PathVariable String id) {
            return List.of(Map.of("policyId", id, "history", "sensitive"));
        }

        @GetMapping("/api/v1/governance/model-policies/{id}/history/page")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_HISTORY_PAGE,
                resourceType = ReadAccessResourceType.MODEL_POLICY,
                resourceIdVariable = "id")
        Map<String, Object> policyHistoryPage(@PathVariable String id) {
            return Map.of("policyId", id, "history", "sensitive");
        }

        @GetMapping("/api/v1/governance/audit/commitments/{kind}/{eventId}")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.GOVERNANCE_AUDIT_COMMITMENT,
                resourceType = ReadAccessResourceType.AUDIT_COMMITMENT,
                resourceIdVariable = "eventId")
        Map<String, Object> commitment(@PathVariable String kind, @PathVariable String eventId) {
            return Map.of("kind", kind, "eventId", eventId, "commitment", "sensitive");
        }
    }
}
