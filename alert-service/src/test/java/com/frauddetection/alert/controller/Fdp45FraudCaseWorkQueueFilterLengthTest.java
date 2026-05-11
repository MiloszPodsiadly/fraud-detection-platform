package com.frauddetection.alert.controller;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.fraudcase.FraudCaseReadQueryPolicy;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class Fdp45FraudCaseWorkQueueFilterLengthTest {

    private final FraudCaseController controller = new FraudCaseController(
            mock(FraudCaseManagementService.class),
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            mock(AlertServiceMetrics.class),
            mock(SensitiveReadAuditService.class)
    );

    @Test
    void shouldRejectOversizedStringFiltersWithoutEchoingRawValues() {
        String longValue = "x".repeat(FraudCaseReadQueryPolicy.MAX_FILTER_VALUE_LENGTH + 1);

        assertFailureDoesNotEcho(() -> controller.workQueue(0, 20, "createdAt,desc", null, longValue, null,
                null, null, null, null, null, null, null, null, new LinkedMultiValueMap<>()), longValue, "INVALID_FILTER");
        assertFailureDoesNotEcho(() -> controller.workQueue(0, 20, "createdAt,desc", null, null, null,
                null, null, null, null, null, null, longValue, null, new LinkedMultiValueMap<>()), longValue, "INVALID_FILTER");
    }

    @Test
    void shouldRejectOversizedSortWithoutEchoingRawValue() {
        String longSort = "createdAt" + "x".repeat(FraudCaseReadQueryPolicy.MAX_SORT_VALUE_LENGTH + 1);

        assertFailureDoesNotEcho(() -> controller.workQueue(0, 20, longSort, null, null, null,
                null, null, null, null, null, null, null, null, new LinkedMultiValueMap<>()), longSort, "UNSUPPORTED_SORT_FIELD");
    }

    private void assertFailureDoesNotEcho(Runnable action, String rawValue, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .satisfies(exception -> {
                    FraudCaseWorkQueueQueryException typed = (FraudCaseWorkQueueQueryException) exception;
                    assertThat(typed.code()).isEqualTo(code);
                    assertThat(typed.getMessage()).doesNotContain(rawValue);
                });
    }
}
