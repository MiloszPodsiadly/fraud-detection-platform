package com.frauddetection.alert.governance.promotionreviewreadiness;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PromotionReviewReadinessReportReadServiceTest {

    private final PromotionReviewReadinessReportProvider provider = mock(PromotionReviewReadinessReportProvider.class);
    private final PromotionReviewReadinessReportValidator validator = mock(PromotionReviewReadinessReportValidator.class);
    private final PromotionReviewReadinessReportReadService service = new PromotionReviewReadinessReportReadService(provider, validator);

    @Test
    void defaultProviderReturnsEmptyWhenNoCurrentReportConfigured() {
        assertThat(new EmptyPromotionReviewReadinessReportProvider().currentReport()).isEmpty();
    }

    @Test
    void returnsCurrentPromotionReviewReadinessReport() {
        PromotionReviewReadinessReport report = PromotionReviewReadinessReportTestFixtures.validReport();
        when(provider.currentReport()).thenReturn(Optional.of(report));

        PromotionReviewReadinessReportResponse response = service.currentReport();

        assertThat(response.reportType()).isEqualTo("PROMOTION_REVIEW_READINESS_REPORT_V1");
        assertThat(response.readinessStatus()).isEqualTo("REVIEWABLE");
        assertThat(response.notAnalystRecommendation()).isTrue();
        verify(validator).validate(report);
    }

    @Test
    void returns404WhenReportDoesNotExist() {
        when(provider.currentReport()).thenReturn(Optional.empty());

        assertThatThrownBy(service::currentReport)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(404);

        verifyNoInteractions(validator);
    }

    @Test
    void invalidReportReturnsServiceUnavailable() {
        PromotionReviewReadinessReport report = PromotionReviewReadinessReportTestFixtures.validReport();
        when(provider.currentReport()).thenReturn(Optional.of(report));
        org.mockito.Mockito.doThrow(new PromotionReviewReadinessReportValidationException("invalid"))
                .when(validator).validate(report);

        assertThatThrownBy(service::currentReport)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void providerUnavailableReturnsServiceUnavailable() {
        when(provider.currentReport()).thenThrow(new PromotionReviewReadinessReportProviderUnavailableException("unavailable"));

        assertThatThrownBy(service::currentReport)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(503);

        verifyNoInteractions(validator);
    }
}
