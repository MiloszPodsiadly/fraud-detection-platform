package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class EngineIntelligenceFeedbackReadService {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 50;

    private final ScoredTransactionRepository scoredTransactionRepository;
    private final EngineIntelligenceFeedbackRepository feedbackRepository;
    private final EngineIntelligenceFeedbackReadModelMapper mapper;

    public EngineIntelligenceFeedbackReadService(
            ScoredTransactionRepository scoredTransactionRepository,
            EngineIntelligenceFeedbackRepository feedbackRepository,
            EngineIntelligenceFeedbackReadModelMapper mapper
    ) {
        this.scoredTransactionRepository = Objects.requireNonNull(scoredTransactionRepository, "scoredTransactionRepository is required");
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    public EngineIntelligenceFeedbackReadModel read(String transactionId, Integer requestedLimit) {
        String boundedTransactionId = EngineIntelligenceTransactionIdPolicy.normalize(transactionId);
        int limit = limit(requestedLimit);
        if (!scoredTransactionRepository.existsById(boundedTransactionId)) {
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
            throw new EngineIntelligenceFeedbackReadUnavailableException();
        }
        boolean hasMore = documents.size() > limit;
        List<EngineIntelligenceFeedbackDocument> bounded = hasMore
                ? documents.subList(0, limit)
                : documents;
        return mapper.map(boundedTransactionId, bounded, limit, hasMore);
    }

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new InvalidEngineIntelligenceFeedbackRequestException(List.of("limit: must be between 1 and 50"));
        }
        return requestedLimit;
    }
}
