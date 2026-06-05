package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackReadMetricReason;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class EngineIntelligenceFeedbackReadService {

    private final ScoredTransactionRepository scoredTransactionRepository;
    private final EngineIntelligenceFeedbackRepository feedbackRepository;
    private final EngineIntelligenceFeedbackReadModelMapper mapper;
    private final EngineIntelligenceFeedbackReadPolicy readPolicy;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceFeedbackReadService(
            ScoredTransactionRepository scoredTransactionRepository,
            EngineIntelligenceFeedbackRepository feedbackRepository,
            EngineIntelligenceFeedbackReadModelMapper mapper,
            EngineIntelligenceFeedbackReadPolicy readPolicy,
            AlertServiceMetrics metrics
    ) {
        this(scoredTransactionRepository, feedbackRepository, mapper, readPolicy, metrics, Clock.systemUTC());
    }

    EngineIntelligenceFeedbackReadService(
            ScoredTransactionRepository scoredTransactionRepository,
            EngineIntelligenceFeedbackRepository feedbackRepository,
            EngineIntelligenceFeedbackReadModelMapper mapper,
            EngineIntelligenceFeedbackReadPolicy readPolicy,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.scoredTransactionRepository = Objects.requireNonNull(scoredTransactionRepository, "scoredTransactionRepository is required");
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EngineIntelligenceFeedbackReadModel read(String transactionId, int limit) {
        Instant startedAt = clock.instant();
        metrics.recordEngineIntelligenceFeedbackReadAttempt();
        try {
            String boundedTransactionId = EngineIntelligenceTransactionIdPolicy.normalize(transactionId);
            if (!scoredTransactionExists(boundedTransactionId)) {
                throw new EngineIntelligenceScoredTransactionNotFoundException();
            }
            List<EngineIntelligenceFeedbackDocument> documents;
            try {
                documents = feedbackRepository.findByTransactionId(
                        boundedTransactionId,
                        PageRequest.of(
                                0,
                                limit + 1,
                                Sort.by(
                                        Sort.Order.desc("submittedAt"),
                                        Sort.Order.asc("feedbackId")
                                )
                            )
                    );
            } catch (RuntimeException exception) {
                metrics.recordEngineIntelligenceFeedbackReadUnavailable(EngineIntelligenceFeedbackReadMetricReason.STORE_UNAVAILABLE);
                throw new EngineIntelligenceFeedbackReadUnavailableException();
            }
            try {
                documents = readPolicy.validate(documents);
            } catch (EngineIntelligenceFeedbackReadUnavailableException exception) {
                metrics.recordEngineIntelligenceFeedbackReadUnavailable(
                        EngineIntelligenceFeedbackReadMetricReason.CORRUPTED_STORED_FEEDBACK
                );
                throw exception;
            }
            boolean hasMore = documents.size() > limit;
            List<EngineIntelligenceFeedbackDocument> bounded = hasMore
                    ? documents.subList(0, limit)
                    : documents;
            return mapper.map(boundedTransactionId, bounded, limit, hasMore);
        } catch (EngineIntelligenceScoredTransactionNotFoundException exception) {
            metrics.recordEngineIntelligenceFeedbackReadValidationFailure();
            throw exception;
        } finally {
            metrics.recordEngineIntelligenceFeedbackReadLatency(Duration.between(startedAt, clock.instant()));
        }
    }

    private boolean scoredTransactionExists(String boundedTransactionId) {
        try {
            return scoredTransactionRepository.existsById(boundedTransactionId);
        } catch (RuntimeException exception) {
            metrics.recordEngineIntelligenceFeedbackReadUnavailable(EngineIntelligenceFeedbackReadMetricReason.STORE_UNAVAILABLE);
            throw new EngineIntelligenceFeedbackReadUnavailableException();
        }
    }
}
