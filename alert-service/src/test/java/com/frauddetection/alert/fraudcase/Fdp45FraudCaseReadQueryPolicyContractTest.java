package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fdp45FraudCaseReadQueryPolicyContractTest {

    @Test
    void shouldPreserveLegacyListBoundsWithoutWorkQueueSpecificParams() {
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateLegacyListPagination(-1, 20));
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateLegacyListPagination(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER + 1, 20));
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateLegacyListPagination(0, 0));
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateLegacyListPagination(0, FraudCaseReadQueryPolicy.MAX_PAGE_SIZE + 1));

        assertThatCode(() -> FraudCaseReadQueryPolicy.validateLegacyListPagination(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER, FraudCaseReadQueryPolicy.MAX_PAGE_SIZE))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldPreserveWorkQueueAndRepositoryBounds() {
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateWorkQueuePagination(-1, 20));
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateWorkQueuePagination(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER + 1, 20));
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateWorkQueuePagination(0, 0));
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateWorkQueuePagination(0, FraudCaseReadQueryPolicy.MAX_PAGE_SIZE + 1));
        assertInvalidPage(() -> FraudCaseReadQueryPolicy.validateRepositoryPageBounds(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER + 1, 20));
    }

    @Test
    void shouldPreserveWorkQueueOnlyFilterSortAndCursorRules() {
        LinkedMultiValueMap<String, String> duplicate = new LinkedMultiValueMap<>();
        duplicate.add("status", "OPEN");
        duplicate.add("status", "CLOSED");
        LinkedMultiValueMap<String, String> unsupported = new LinkedMultiValueMap<>();
        unsupported.add("customerId", "customer-1");

        assertCode(() -> FraudCaseReadQueryPolicy.validateWorkQueueSingleValueParameters(duplicate), "DUPLICATE_QUERY_PARAM");
        assertCode(() -> FraudCaseReadQueryPolicy.validateWorkQueueAllowedParameters(unsupported), "UNSUPPORTED_FILTER");
        assertCode(() -> FraudCaseReadQueryPolicy.validateWorkQueueStringFilters("x".repeat(FraudCaseReadQueryPolicy.MAX_FILTER_VALUE_LENGTH + 1), null, null, "createdAt,desc", null), "INVALID_FILTER");
        assertCode(() -> FraudCaseReadQueryPolicy.validateWorkQueueStringFilters(null, null, null, "customerId,desc", "x".repeat(FraudCaseReadQueryPolicy.MAX_CURSOR_LENGTH + 1)), "INVALID_CURSOR");
        assertCode(() -> FraudCaseReadQueryPolicy.workQueueSortOrder("customerId,desc"), "UNSUPPORTED_SORT_FIELD");
        assertCode(() -> FraudCaseReadQueryPolicy.workQueueSortOrder("createdAt,sideways"), "UNSUPPORTED_SORT_DIRECTION");

        assertThatCode(() -> FraudCaseReadQueryPolicy.stableReadSort(Sort.by(Sort.Order.desc("createdAt"))))
                .doesNotThrowAnyException();
    }

    private void assertInvalidPage(Runnable action) {
        assertCode(action, "INVALID_PAGE_REQUEST");
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo(code);
    }
}
