package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionRepositoryMethodTest {

    @Test
    void noListReturningFinderMethods() {
        assertThat(Arrays.stream(SuspiciousTransactionRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("find"))
                .map(Method::getReturnType))
                .doesNotContain(List.class);
    }

    @Test
    void multiResultFindersReturnPageAndAcceptPageable() {
        assertThat(Arrays.stream(SuspiciousTransactionRepository.class.getDeclaredMethods())
                .filter(method -> !method.getName().equals("findByTransactionIdAndSourceEventId"))
                .filter(method -> method.getName().startsWith("find"))
                .allMatch(method -> method.getReturnType().equals(Page.class)
                        && List.of(method.getParameterTypes()).contains(Pageable.class)))
                .isTrue();
    }

    @Test
    void idempotencyLookupReturnsOptional() throws NoSuchMethodException {
        Method method = SuspiciousTransactionRepository.class.getDeclaredMethod(
                "findByTransactionIdAndSourceEventId",
                String.class,
                String.class
        );

        assertThat(method.getReturnType()).isEqualTo(Optional.class);
    }
}
