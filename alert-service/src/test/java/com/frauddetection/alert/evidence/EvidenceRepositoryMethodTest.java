package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRepositoryMethodTest {

    @Test
    void evidenceRepositoryExposesBoundedPageableFinders() throws NoSuchMethodException {
        assertPageableFinder("findByTransactionId");
        assertPageableFinder("findByAlertId");
        assertPageableFinder("findByCorrelationId");
    }

    @Test
    void evidenceRepositoryDoesNotExposeUnboundedListFinders() {
        assertThat(EvidenceRepository.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().matches("findBy(TransactionId|AlertId|CorrelationId)"))
                .noneMatch(method -> method.getReturnType().equals(List.class));
    }

    private void assertPageableFinder(String methodName) throws NoSuchMethodException {
        Method method = EvidenceRepository.class.getDeclaredMethod(methodName, String.class, Pageable.class);

        assertThat(method.getReturnType()).isEqualTo(Page.class);
    }
}
