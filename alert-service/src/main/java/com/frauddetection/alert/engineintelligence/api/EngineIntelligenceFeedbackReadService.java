package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackReadMetricReason;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class EngineIntelligenceFeedbackReadService {

    private final ScoredTransactionRepository scoredTransactionRepository;
    private final EngineIntelligenceFeedbackRepository feedbackRepository;
    private final EngineIntelligenceFeedbackReadModelMapper mapper;
    private final EngineIntelligenceFeedbackReadPolicy readPolicy;

    @Autowired
    public EngineIntelligenceFeedbackReadService(
            ScoredTransactionRepository scoredTransactionRepository,
            EngineIntelligenceFeedbackRepository feedbackRepository,
            EngineIntelligenceFeedbackReadModelMapper mapper,
            EngineIntelligenceFeedbackReadPolicy readPolicy
    ) {
        this.scoredTransactionRepository = Objects.requireNonNull(scoredTransactionRepository, "scoredTransactionRepository is required");
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy is required");
    }

    public EngineIntelligenceFeedbackReadModel read(String transactionId, int limit) {
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
            throw new EngineIntelligenceFeedbackReadUnavailableException(
                    EngineIntelligenceFeedbackReadMetricReason.STORE_UNAVAILABLE
            );
        }
        documents = readPolicy.validate(documents);
        boolean hasMore = documents.size() > limit;
        List<EngineIntelligenceFeedbackDocument> bounded = hasMore
                ? documents.subList(0, limit)
                : documents;
        return mapper.map(boundedTransactionId, bounded, limit, hasMore);
    }

    private boolean scoredTransactionExists(String boundedTransactionId) {
        try {
            return scoredTransactionRepository.existsById(boundedTransactionId);
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackReadUnavailableException(
                    EngineIntelligenceFeedbackReadMetricReason.STORE_UNAVAILABLE
            );
        }
    }
}
