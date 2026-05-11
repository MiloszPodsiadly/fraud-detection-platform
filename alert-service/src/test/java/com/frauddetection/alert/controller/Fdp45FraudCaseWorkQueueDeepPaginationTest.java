package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.fraudcase.FraudCaseReadQueryPolicy;
import com.frauddetection.alert.fraudcase.MongoFraudCaseSearchRepository;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueDeepPaginationTest {

    private final FraudCaseManagementService service = mock(FraudCaseManagementService.class);
    private final FraudCaseController controller = new FraudCaseController(
            service,
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            mock(AlertServiceMetrics.class),
            mock(SensitiveReadAuditService.class)
    );

    @Test
    void shouldAcceptConfiguredMaximumPageAndRejectDeepOffsetBeforeServiceCall() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER, 100, false, null));

        controller.workQueue(0, 100, "createdAt,desc", null, null, null, null, null, null, null, null, null, null, null, new LinkedMultiValueMap<>());
        controller.workQueue(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER, 100, "createdAt,desc", null, null, null, null, null, null, null, null, null, null, null, new LinkedMultiValueMap<>());

        assertThatThrownBy(() -> controller.workQueue(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER + 1, 100, "createdAt,desc",
                null, null, null, null, null, null, null, null, null, null, null, new LinkedMultiValueMap<>()))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE_REQUEST");
        assertThatThrownBy(() -> controller.workQueue(1_000_000, 100, "createdAt,desc",
                null, null, null, null, null, null, null, null, null, null, null, new LinkedMultiValueMap<>()))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE_REQUEST");

        verify(service, never()).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.argThat(pageable -> pageable.getPageNumber() > FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER));
    }

    @Test
    void repositoryShouldRejectPageableBeyondConfiguredMaximumOffset() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoFraudCaseSearchRepository repository = new MongoFraudCaseSearchRepository(mongoTemplate);

        assertThatThrownBy(() -> repository.searchSlice(
                new com.frauddetection.alert.fraudcase.FraudCaseSearchCriteria(null, null, null, null, null, null, null, null, null),
                PageRequest.of(FraudCaseReadQueryPolicy.MAX_PAGE_NUMBER + 1, 100, Sort.by(Sort.Order.desc("createdAt")))
        ))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE_REQUEST");
        verify(mongoTemplate, never()).find(any(), any(Class.class));
    }
}
