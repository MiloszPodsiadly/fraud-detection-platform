package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspiciousTransactionNoUnboundedQueryTest {

    @Test
    void readServiceSearchReturnsSliceAndRequiresBoundedQuery() throws NoSuchMethodException {
        Method search = SuspiciousTransactionReadService.class.getMethod("search", SuspiciousTransactionSearchQuery.class);

        assertThat(search.getReturnType()).isEqualTo(SuspiciousTransactionSliceResponse.class);
    }

    @Test
    void controllerDoesNotReturnListOfResponses() {
        assertThat(Arrays.stream(SuspiciousTransactionReadController.class.getDeclaredMethods())
                .noneMatch(method -> method.getReturnType().equals(List.class))).isTrue();
        assertThat(Arrays.stream(SuspiciousTransactionReadController.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType().equals(SuspiciousTransactionSliceResponse.class))).isTrue();
    }

    @Test
    void responseTypeIsSliceStyleNotPageWithTotalCount() {
        assertThat(Arrays.stream(SuspiciousTransactionSliceResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList())
                .containsExactly("content", "size", "hasNext", "nextCursor")
                .doesNotContain("page", "totalElements", "totalPages", "totalCount", "offset");
    }

    @Test
    void serviceSearchDoesNotCallCountAndUsesSizePlusOneLimit() {
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());
        SuspiciousTransactionReadService service = new SuspiciousTransactionReadService(
                repository,
                mongoTemplate,
                new SuspiciousTransactionCursorCodec(),
                mock(SuspiciousTransactionSummaryService.class)
        );

        service.search(SuspiciousTransactionSearchQuery.from(new org.springframework.util.LinkedMultiValueMap<>()));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, never()).count(any(Query.class), eq(SuspiciousTransactionDocument.class));
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(SuspiciousTransactionSearchQuery.DEFAULT_SIZE + 1);
        assertThat(queryCaptor.getValue().getSkip()).isZero();
    }

    @Test
    void readServiceSearchDoesNotExposePageOrPageableContract() {
        List<Method> serviceSearchMethods = Arrays.stream(SuspiciousTransactionReadService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("search"))
                .toList();

        assertThat(serviceSearchMethods).hasSize(1);
        assertThat(serviceSearchMethods).allSatisfy(method -> {
            assertThat(method.getReturnType()).isNotEqualTo(Page.class);
            assertThat(Arrays.asList(method.getParameterTypes())).doesNotContain(Pageable.class);
        });
    }
}
