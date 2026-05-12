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
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

    @MockBean
    private ReadAccessAuditRepository repository;

    @MockBean
    private CurrentAnalystUser currentAnalystUser;

    @BeforeEach
    void setUp() {
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
    }
}
