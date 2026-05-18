package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionNoUnboundedQueryTest {

    @Test
    void readServiceSearchReturnsPageAndRequiresBoundedQuery() throws NoSuchMethodException {
        Method search = SuspiciousTransactionReadService.class.getMethod("search", SuspiciousTransactionSearchQuery.class);

        assertThat(search.getReturnType()).isEqualTo(Page.class);
    }

    @Test
    void controllerDoesNotReturnListOfResponses() {
        assertThat(Arrays.stream(SuspiciousTransactionReadController.class.getDeclaredMethods())
                .noneMatch(method -> method.getReturnType().equals(List.class))).isTrue();
        assertThat(Arrays.stream(SuspiciousTransactionReadController.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType().equals(PagedResponse.class))).isTrue();
    }

    @Test
    void repositorySearchMethodsUsePageableAndPage() {
        List<Method> searchMethods = Arrays.stream(SuspiciousTransactionRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("findBy"))
                .filter(method -> !method.getName().equals("findByTransactionIdAndSourceEventId"))
                .toList();

        assertThat(searchMethods).isNotEmpty();
        assertThat(searchMethods).allSatisfy(method -> {
            assertThat(method.getReturnType()).isEqualTo(Page.class);
            assertThat(Arrays.asList(method.getParameterTypes())).contains(Pageable.class);
        });
    }
}
