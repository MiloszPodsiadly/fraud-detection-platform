package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EngineIntelligenceReadServiceTest {

    private final ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
    private final EngineIntelligenceProjectionRepository projectionRepository =
            mock(EngineIntelligenceProjectionRepository.class);
    private final EngineIntelligenceReadModelMapper mapper = mock(EngineIntelligenceReadModelMapper.class);
    private final EngineIntelligenceReadService service =
            new EngineIntelligenceReadService(scoredTransactionRepository, projectionRepository, mapper);

    @Test
    void returnsReadModelWhenProjectionExists() {
        EngineIntelligenceProjection projection = mock(EngineIntelligenceProjection.class);
        EngineIntelligenceReadModel expected = EngineIntelligenceReadModel.notProjected("placeholder");
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(projectionRepository.findById("txn-1")).thenReturn(Optional.of(projection));
        when(mapper.map(projection)).thenReturn(expected);

        assertThat(service.read("txn-1")).isSameAs(expected);
    }

    @Test
    void returnsAvailableFalseWhenProjectionMissing() {
        when(scoredTransactionRepository.existsById("txn-old")).thenReturn(true);
        when(projectionRepository.findById("txn-old")).thenReturn(Optional.empty());

        assertThat(service.read("txn-old"))
                .isEqualTo(EngineIntelligenceReadModel.notProjected("txn-old"));
    }

    @Test
    void validatesAccessBeforeProjectionLookup() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(projectionRepository.findById("txn-1")).thenReturn(Optional.empty());

        service.read("txn-1");

        InOrder order = inOrder(scoredTransactionRepository, projectionRepository);
        order.verify(scoredTransactionRepository).existsById("txn-1");
        order.verify(projectionRepository).findById("txn-1");
    }

    @Test
    void unauthorizedOrMissingTransactionDoesNotReadProjectionRepository() {
        when(scoredTransactionRepository.existsById("txn-hidden")).thenReturn(false);

        assertThatThrownBy(() -> service.read("txn-hidden"))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class);

        verify(projectionRepository, never()).findById("txn-hidden");
    }

    @Test
    void missingProjectionDoesNotReturn500AndOldTransactionWithoutProjectionStillWorks() {
        when(scoredTransactionRepository.existsById("txn-old")).thenReturn(true);
        when(projectionRepository.findById("txn-old")).thenReturn(Optional.empty());

        assertThat(service.read("txn-old").available()).isFalse();
        verify(mapper, never()).map(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blankTransactionIdReturnsNotFoundWithoutRepositoryLookup() {
        assertInvalidTransactionId("   ");
    }

    @Test
    void overlongTransactionIdReturnsNotFoundWithoutRepositoryLookup() {
        assertInvalidTransactionId("x".repeat(129));
    }

    @Test
    void controlCharacterTransactionIdReturnsNotFoundWithoutRepositoryLookup() {
        assertInvalidTransactionId("txn-raw\nsecret");
    }

    @Test
    void invalidPatternTransactionIdReturnsNotFoundWithoutRepositoryLookup() {
        assertInvalidTransactionId("txn/raw-secret");
    }

    @Test
    void trimmedValidTransactionIdIsUsedForLookup() {
        when(scoredTransactionRepository.existsById("txn-trimmed")).thenReturn(true);
        when(projectionRepository.findById("txn-trimmed")).thenReturn(Optional.empty());

        assertThat(service.read("  txn-trimmed  "))
                .isEqualTo(EngineIntelligenceReadModel.notProjected("txn-trimmed"));

        verify(scoredTransactionRepository).existsById("txn-trimmed");
        verify(projectionRepository).findById("txn-trimmed");
    }

    @Test
    void validTransactionIdAllowsProjectionLookup() {
        when(scoredTransactionRepository.existsById("txn.valid:001")).thenReturn(true);
        when(projectionRepository.findById("txn.valid:001")).thenReturn(Optional.empty());

        service.read("txn.valid:001");

        verify(projectionRepository).findById("txn.valid:001");
    }

    @Test
    void projectionRepositoryFailureThrowsStableUnavailableException() {
        when(scoredTransactionRepository.existsById("txn-store-failure")).thenReturn(true);
        when(projectionRepository.findById("txn-store-failure"))
                .thenThrow(new IllegalStateException("raw mongodb endpoint token secret"));

        assertThatThrownBy(() -> service.read("txn-store-failure"))
                .isInstanceOf(EngineIntelligenceProjectionReadUnavailableException.class)
                .hasMessage("Engine intelligence projection is temporarily unavailable.")
                .hasMessageNotContaining("mongodb")
                .hasMessageNotContaining("endpoint")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("secret");
    }

    @Test
    void projectionRepositoryFailureDoesNotReturnNotProjected() {
        when(scoredTransactionRepository.existsById("txn-store-failure")).thenReturn(true);
        when(projectionRepository.findById("txn-store-failure"))
                .thenThrow(new IllegalStateException("raw repository failure"));

        assertThatThrownBy(() -> service.read("txn-store-failure"))
                .isInstanceOf(EngineIntelligenceProjectionReadUnavailableException.class);
    }

    private void assertInvalidTransactionId(String transactionId) {
        assertThatThrownBy(() -> service.read(transactionId))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class)
                .hasMessage("Scored transaction not found.")
                .hasMessageNotContaining(transactionId);

        verifyNoInteractions(scoredTransactionRepository, projectionRepository, mapper);
    }
}
