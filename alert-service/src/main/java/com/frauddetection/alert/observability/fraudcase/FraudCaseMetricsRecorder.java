package com.frauddetection.alert.observability.fraudcase;

import com.frauddetection.alert.observability.FraudCaseReadModelOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
public class FraudCaseMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public FraudCaseMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordWorkQueueRequest(String outcome) {
        counter(
                "fraud_case_work_queue_requests_total",
                "endpoint_family", "fraud_case_work_queue",
                "outcome", normalizeWorkQueueOutcome(outcome)
        ).increment();
    }

    public void recordWorkQueueQuery(String outcome, String sortField) {
        counter(
                "fraud_case_work_queue_query_total",
                "outcome", normalizeWorkQueueOutcome(outcome),
                "sort_field", normalizeWorkQueueSortField(sortField)
        ).increment();
    }

    public void recordWorkQueuePageSize(int pageSize) {
        DistributionSummary.builder("fraud_case_work_queue_page_size_bucket")
                .tag("endpoint_family", "fraud_case_work_queue")
                .register(meterRegistry)
                .record(Math.max(0, pageSize));
    }

    public void recordWorkQueueSummary(String outcome, Duration latency) {
        counter(
                "fraud_case_work_queue_summary_requests_total",
                "outcome", normalizeWorkQueueSummaryOutcome(outcome)
        ).increment();
        Timer.builder("fraud_case_work_queue_summary_latency_seconds")
                .register(meterRegistry)
                .record(latency == null || latency.isNegative() ? Duration.ZERO : latency);
    }

    public void recordReadModel(String endpoint, FraudCaseReadModelOutcome outcome) {
        FraudCaseReadModelOutcome safeOutcome = outcome == null ? FraudCaseReadModelOutcome.ERROR : outcome;
        counter(
                "fraud.fraud_case.read_model.read",
                "endpoint", endpoint,
                "outcome", safeOutcome.label()
        ).increment();
    }

    public void recordScoredTransactionSearch(String outcome, String filterBucket) {
        counter(
                "scored_transaction_search_requests_total",
                "endpoint_family", "scored_transactions",
                "outcome", normalizeScoredTransactionSearchOutcome(outcome),
                "filter_bucket", normalizeScoredTransactionFilterBucket(filterBucket)
        ).increment();
    }

    public void recordScoredTransactionPageSize(int pageSize) {
        DistributionSummary.builder("scored_transaction_search_page_size_bucket")
                .tag("endpoint_family", "scored_transactions")
                .register(meterRegistry)
                .record(Math.max(0, pageSize));
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private String normalizeWorkQueueOutcome(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return "failure";
        }
        return switch (outcome) {
            case "success", "invalid_filter", "invalid_sort", "invalid_cursor", "unauthorized", "failure" -> outcome;
            default -> "failure";
        };
    }

    private String normalizeWorkQueueSummaryOutcome(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return "failure";
        }
        return switch (outcome) {
            case "success", "failure" -> outcome;
            default -> "failure";
        };
    }

    private String normalizeWorkQueueSortField(String sortField) {
        if (!StringUtils.hasText(sortField)) {
            return "default";
        }
        return switch (sortField) {
            case "createdAt", "updatedAt", "priority", "riskLevel", "caseNumber", "default" -> sortField;
            default -> "default";
        };
    }

    private String normalizeScoredTransactionSearchOutcome(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return "failure";
        }
        return switch (outcome) {
            case "success", "rejected", "failure" -> outcome;
            default -> "failure";
        };
    }

    private String normalizeScoredTransactionFilterBucket(String filterBucket) {
        if (!StringUtils.hasText(filterBucket)) {
            return "none";
        }
        return switch (filterBucket) {
            case "none", "query", "risk", "classification", "combined" -> filterBucket;
            default -> "combined";
        };
    }
}
