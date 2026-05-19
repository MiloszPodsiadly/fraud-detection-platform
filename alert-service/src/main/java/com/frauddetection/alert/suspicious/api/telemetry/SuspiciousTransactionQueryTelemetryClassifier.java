package com.frauddetection.alert.suspicious.api.telemetry;

import com.frauddetection.alert.suspicious.api.SuspiciousTransactionSearchQuery;
import com.frauddetection.alert.suspicious.api.SuspiciousTransactionSliceResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SuspiciousTransactionQueryTelemetryClassifier {

    public SuspiciousTransactionQueryTelemetrySnapshot search(
            SuspiciousTransactionSearchQuery query,
            String outcome,
            SuspiciousTransactionSliceResponse response,
            Duration duration
    ) {
        int resultSize = response == null ? -1 : response.content().size();
        return search(query, outcome, resultSize, response == null ? null : response.hasNext(), duration);
    }

    public SuspiciousTransactionQueryTelemetrySnapshot search(
            SuspiciousTransactionSearchQuery query,
            String outcome,
            int resultSize,
            Boolean hasNext,
            Duration duration
    ) {
        return new SuspiciousTransactionQueryTelemetrySnapshot(
                "search",
                normalizeOutcome(outcome),
                queryShape(query),
                filterCountBucket(filterCount(query)),
                resultSizeBucket(resultSize),
                triState(hasNext),
                query == null ? "unknown" : Boolean.toString(query.cursor() != null),
                durationBucket(duration),
                duration
        );
    }

    public SuspiciousTransactionQueryTelemetrySnapshot searchValidationError(boolean cursorPresent, Duration duration) {
        return new SuspiciousTransactionQueryTelemetrySnapshot(
                "search",
                "validation_error",
                "unknown",
                "0",
                "unknown",
                "unknown",
                Boolean.toString(cursorPresent),
                durationBucket(duration),
                duration
        );
    }

    public SuspiciousTransactionQueryTelemetrySnapshot read(String outcome, int resultSize, Duration duration) {
        return new SuspiciousTransactionQueryTelemetrySnapshot(
                "read",
                normalizeOutcome(outcome),
                "id_lookup",
                "1",
                resultSizeBucket(resultSize),
                "unknown",
                "unknown",
                durationBucket(duration),
                duration
        );
    }

    private String queryShape(SuspiciousTransactionSearchQuery query) {
        if (query == null) {
            return "unknown";
        }
        int filterCount = filterCount(query);
        if (filterCount == 0) {
            return "unfiltered";
        }
        if (filterCount > 1) {
            return "multi_filter";
        }
        if (query.hasStatusFilter()) {
            return "status";
        }
        if (query.hasRiskLevelFilter()) {
            return "risk";
        }
        if (query.hasCustomerFilter()) {
            return "customer";
        }
        if (query.hasLinkedAlertFilter()) {
            return "linked_alert";
        }
        if (query.hasDetectedRange()) {
            return "date_range";
        }
        return "unknown";
    }

    private int filterCount(SuspiciousTransactionSearchQuery query) {
        if (query == null) {
            return 0;
        }
        int count = 0;
        count += query.hasStatusFilter() ? 1 : 0;
        count += query.hasRiskLevelFilter() ? 1 : 0;
        count += query.hasCustomerFilter() ? 1 : 0;
        count += query.hasLinkedAlertFilter() ? 1 : 0;
        count += query.hasDetectedRange() ? 1 : 0;
        return count;
    }

    private String filterCountBucket(int filterCount) {
        if (filterCount <= 0) {
            return "0";
        }
        if (filterCount == 1) {
            return "1";
        }
        if (filterCount == 2) {
            return "2";
        }
        return "3_plus";
    }

    private String resultSizeBucket(int resultSize) {
        if (resultSize < 0) {
            return "unknown";
        }
        if (resultSize == 0) {
            return "0";
        }
        if (resultSize <= 10) {
            return "1_10";
        }
        if (resultSize <= 50) {
            return "11_50";
        }
        return "51_100";
    }

    private String durationBucket(Duration duration) {
        long millis = duration == null || duration.isNegative() ? 0L : duration.toMillis();
        if (millis < 50L) {
            return "lt_50ms";
        }
        if (millis < 100L) {
            return "50_100ms";
        }
        if (millis < 250L) {
            return "100_250ms";
        }
        if (millis < 500L) {
            return "250_500ms";
        }
        return "500ms_plus";
    }

    private String triState(Boolean value) {
        return value == null ? "unknown" : Boolean.toString(value);
    }

    private String normalizeOutcome(String outcome) {
        if (outcome == null) {
            return "error";
        }
        return switch (outcome) {
            case "success", "not_found", "validation_error", "forbidden", "error" -> outcome;
            default -> "error";
        };
    }
}
