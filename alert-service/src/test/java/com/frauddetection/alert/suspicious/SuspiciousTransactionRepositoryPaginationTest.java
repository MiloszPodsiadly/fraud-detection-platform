package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionRepositoryPaginationTest {

    @Test
    void everyMultiRowFinderRequiresPageable() {
        assertThat(Arrays.stream(SuspiciousTransactionRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("findBy"))
                .filter(method -> !method.getName().equals("findByTransactionIdAndSourceEventId"))
                .allMatch(method -> List.of(method.getParameterTypes()).contains(Pageable.class)))
                .isTrue();
    }
}
