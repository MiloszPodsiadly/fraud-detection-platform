package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
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
        when(scoredTransactionRepository.findById("txn-1")).thenReturn(Optional.of(current("txn-1", "event-1")));
        when(projectionRepository.findById("txn-1")).thenReturn(Optional.of(projection));
        stubProjectionOwnership(projection, "event-1", "a".repeat(64));
        when(mapper.map(projection)).thenReturn(expected);

        assertThat(service.read("txn-1")).isSameAs(expected);
    }

    @Test
    void returnsAvailableFalseWhenProjectionMissing() {
        when(scoredTransactionRepository.findById("txn-old"))
                .thenReturn(Optional.of(current("txn-old", "event-old")));
        when(projectionRepository.findById("txn-old")).thenReturn(Optional.empty());

        assertThat(service.read("txn-old"))
                .isEqualTo(EngineIntelligenceReadModel.notProjected("txn-old"));
    }

    @Test
    void validatesAccessBeforeProjectionLookup() {
        when(scoredTransactionRepository.findById("txn-1")).thenReturn(Optional.of(current("txn-1", "event-1")));
        when(projectionRepository.findById("txn-1")).thenReturn(Optional.empty());

        service.read("txn-1");

        InOrder order = inOrder(scoredTransactionRepository, projectionRepository);
        order.verify(scoredTransactionRepository).findById("txn-1");
        order.verify(projectionRepository).findById("txn-1");
    }

    @Test
    void unauthorizedOrMissingTransactionDoesNotReadProjectionRepository() {
        when(scoredTransactionRepository.findById("txn-hidden")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read("txn-hidden"))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class);

        verify(projectionRepository, never()).findById("txn-hidden");
    }

    @Test
    void missingProjectionDoesNotReturn500AndOldTransactionWithoutProjectionStillWorks() {
        when(scoredTransactionRepository.findById("txn-old"))
                .thenReturn(Optional.of(current("txn-old", "event-old")));
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
        when(scoredTransactionRepository.findById("txn-trimmed"))
                .thenReturn(Optional.of(current("txn-trimmed", "event-trimmed")));
        when(projectionRepository.findById("txn-trimmed")).thenReturn(Optional.empty());

        assertThat(service.read("  txn-trimmed  "))
                .isEqualTo(EngineIntelligenceReadModel.notProjected("txn-trimmed"));

        verify(scoredTransactionRepository).findById("txn-trimmed");
        verify(projectionRepository).findById("txn-trimmed");
    }

    @Test
    void validTransactionIdAllowsProjectionLookup() {
        when(scoredTransactionRepository.findById("txn.valid:001"))
                .thenReturn(Optional.of(current("txn.valid:001", "event-valid")));
        when(projectionRepository.findById("txn.valid:001")).thenReturn(Optional.empty());

        service.read("txn.valid:001");

        verify(projectionRepository).findById("txn.valid:001");
    }

    @Test
    void projectionRepositoryFailureThrowsStableUnavailableException() {
        when(scoredTransactionRepository.findById("txn-store-failure"))
                .thenReturn(Optional.of(current("txn-store-failure", "event-store")));
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
        when(scoredTransactionRepository.findById("txn-store-failure"))
                .thenReturn(Optional.of(current("txn-store-failure", "event-store")));
        when(projectionRepository.findById("txn-store-failure"))
                .thenThrow(new IllegalStateException("raw repository failure"));

        assertThatThrownBy(() -> service.read("txn-store-failure"))
                .isInstanceOf(EngineIntelligenceProjectionReadUnavailableException.class);
    }

    @Test
    void staleProjectionFromAnotherOccurrenceIsNotReturned() {
        EngineIntelligenceProjection projection = mock(EngineIntelligenceProjection.class);
        when(scoredTransactionRepository.findById("txn-current"))
                .thenReturn(Optional.of(current("txn-current", "event-b")));
        when(projectionRepository.findById("txn-current")).thenReturn(Optional.of(projection));
        stubProjectionOwnership(projection, "event-a", "a".repeat(64));

        assertThat(service.read("txn-current"))
                .isEqualTo(EngineIntelligenceReadModel.notProjected("txn-current"));

        verify(mapper, never()).map(projection);
    }

    @Test
    void projectionWithMatchingEventIdButDifferentFingerprintIsNotReturned() {
        EngineIntelligenceProjection projection = mock(EngineIntelligenceProjection.class);
        when(scoredTransactionRepository.findById("txn-current"))
                .thenReturn(Optional.of(current("txn-current", "event-current")));
        when(projectionRepository.findById("txn-current")).thenReturn(Optional.of(projection));
        stubProjectionOwnership(projection, "event-current", "b".repeat(64));

        assertThat(service.read("txn-current"))
                .isEqualTo(EngineIntelligenceReadModel.notProjected("txn-current"));

        verify(mapper, never()).map(projection);
    }

    @Test
    void occurrenceReplacementBetweenBaselineAndDiagnosticReadFailsClosed() {
        Instant occurrenceTime = Instant.parse("2026-10-04T10:00:00.123456789Z");
        when(scoredTransactionRepository.findById("txn-current"))
                .thenReturn(Optional.of(current("txn-current", "event-b")));

        assertThat(service.readForOccurrence(
                "txn-current",
                ScoringOccurrenceOwnership.authoritative("event-a", occurrenceTime, "a".repeat(64))
        )).isEqualTo(EngineIntelligenceReadModel.notProjected("txn-current"));

        verifyNoInteractions(projectionRepository, mapper);
    }

    @Test
    void legacyProjectionWithoutOccurrenceOwnerFailsClosed() {
        EngineIntelligenceProjection projection = mock(EngineIntelligenceProjection.class);
        when(scoredTransactionRepository.findById("txn-legacy"))
                .thenReturn(Optional.of(current("txn-legacy", "event-current")));
        when(projectionRepository.findById("txn-legacy")).thenReturn(Optional.of(projection));
        when(projection.getSourceEventCreatedAtEpochSecond()).thenReturn(null);
        when(projection.getSourceEventCreatedAtNano()).thenReturn(null);

        assertThat(service.read("txn-legacy"))
                .isEqualTo(EngineIntelligenceReadModel.notProjected("txn-legacy"));

        verify(mapper, never()).map(projection);
    }

    @Test
    void projectionWithPartialPrecisionMetadataFailsClosed() {
        EngineIntelligenceProjection projection = mock(EngineIntelligenceProjection.class);
        Instant sourceEventCreatedAt = occurrenceTime();
        when(scoredTransactionRepository.findById("txn-partial"))
                .thenReturn(Optional.of(current("txn-partial", "event-current")));
        when(projectionRepository.findById("txn-partial")).thenReturn(Optional.of(projection));
        when(projection.getSourceEventId()).thenReturn("event-current");
        when(projection.getSourceEventCreatedAtText()).thenReturn(sourceEventCreatedAt.toString());
        when(projection.getSourceEventCreatedAtEpochSecond()).thenReturn(sourceEventCreatedAt.getEpochSecond());
        when(projection.getSourceEventFingerprint()).thenReturn("a".repeat(64));

        assertThatThrownBy(() -> service.read("txn-partial"))
                .isInstanceOf(EngineIntelligenceProjectionReadUnavailableException.class);

        verify(mapper, never()).map(projection);
    }

    private ScoredTransactionDocument current(String transactionId, String sourceEventId) {
        Instant sourceEventCreatedAt = occurrenceTime();
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(transactionId);
        document.setSourceEventId(sourceEventId);
        document.setSourceEventCreatedAt(sourceEventCreatedAt.toString());
        document.setSourceEventCreatedAtEpochSecond(sourceEventCreatedAt.getEpochSecond());
        document.setSourceEventCreatedAtNano(sourceEventCreatedAt.getNano());
        document.setSourceEventFingerprint("a".repeat(64));
        return document;
    }

    private void stubProjectionOwnership(
            EngineIntelligenceProjection projection,
            String sourceEventId,
            String fingerprint
    ) {
        Instant sourceEventCreatedAt = occurrenceTime();
        when(projection.getSourceEventId()).thenReturn(sourceEventId);
        when(projection.getSourceEventCreatedAtText()).thenReturn(sourceEventCreatedAt.toString());
        when(projection.getSourceEventCreatedAtEpochSecond()).thenReturn(sourceEventCreatedAt.getEpochSecond());
        when(projection.getSourceEventCreatedAtNano()).thenReturn(sourceEventCreatedAt.getNano());
        when(projection.getSourceEventFingerprint()).thenReturn(fingerprint);
    }

    private Instant occurrenceTime() {
        return Instant.parse("2026-10-04T10:00:00.123456789Z");
    }

    private void assertInvalidTransactionId(String transactionId) {
        assertThatThrownBy(() -> service.read(transactionId))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class)
                .hasMessage("Scored transaction not found.")
                .hasMessageNotContaining(transactionId);

        verifyNoInteractions(scoredTransactionRepository, projectionRepository, mapper);
    }
}
