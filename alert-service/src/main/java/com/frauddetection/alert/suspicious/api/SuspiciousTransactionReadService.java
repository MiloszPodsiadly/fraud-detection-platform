package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class SuspiciousTransactionReadService {

    private final SuspiciousTransactionRepository repository;
    private final MongoTemplate mongoTemplate;
    private final SuspiciousTransactionCursorCodec cursorCodec;

    public SuspiciousTransactionReadService(
            SuspiciousTransactionRepository repository,
            MongoTemplate mongoTemplate,
            SuspiciousTransactionCursorCodec cursorCodec
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec is required");
    }

    public Optional<SuspiciousTransactionResponse> findById(String suspiciousTransactionId) {
        if (suspiciousTransactionId == null || suspiciousTransactionId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(suspiciousTransactionId.trim())
                .map(SuspiciousTransactionResponse::from);
    }

    public SuspiciousTransactionSummaryResponse summary() {
        return new SuspiciousTransactionSummaryResponse(repository.count());
    }

    public SuspiciousTransactionSliceResponse search(SuspiciousTransactionSearchQuery query) {
        SuspiciousTransactionSearchQuery boundedQuery = Objects.requireNonNull(query, "query is required");
        int size = boundedQuery.size();
        SuspiciousTransactionCursor cursor = Optional.ofNullable(boundedQuery.cursor())
                .map(cursorCodec::decode)
                .orElse(null);
        Query mongoQuery = mongoQuery(boundedQuery, cursor);
        mongoQuery.with(Sort.by(
                Sort.Order.desc(SuspiciousTransactionSearchQuery.SORT_FIELD),
                Sort.Order.desc(SuspiciousTransactionSearchQuery.TIE_BREAKER_SORT_FIELD)
        ));
        mongoQuery.limit(size + 1);

        List<SuspiciousTransactionDocument> documents = mongoTemplate.find(
                mongoQuery,
                SuspiciousTransactionDocument.class
        );
        boolean hasNext = documents.size() > size;
        List<SuspiciousTransactionDocument> returnedDocuments = documents.stream()
                .limit(size)
                .toList();
        List<SuspiciousTransactionResponse> content = returnedDocuments.stream()
                .map(SuspiciousTransactionResponse::from)
                .toList();
        String nextCursor = hasNext && !returnedDocuments.isEmpty()
                ? cursor(returnedDocuments.getLast())
                : null;
        return new SuspiciousTransactionSliceResponse(content, size, hasNext, nextCursor);
    }

    private Query mongoQuery(SuspiciousTransactionSearchQuery query, SuspiciousTransactionCursor cursor) {
        List<Criteria> filters = new ArrayList<>();
        if (query.status() != null) {
            filters.add(Criteria.where("status").is(query.status()));
        }
        if (query.riskLevel() != null) {
            filters.add(Criteria.where("riskLevel").is(query.riskLevel()));
        }
        if (query.customerId() != null) {
            filters.add(Criteria.where("customerId").is(query.customerId()));
        }
        if (query.linkedAlertId() != null) {
            filters.add(Criteria.where("linkedAlertId").is(query.linkedAlertId()));
        }
        if (query.detectedFrom() != null && query.detectedTo() != null) {
            filters.add(Criteria.where("detectedAt").gte(query.detectedFrom()).lte(query.detectedTo()));
        } else if (query.detectedFrom() != null) {
            filters.add(Criteria.where("detectedAt").gte(query.detectedFrom()));
        } else if (query.detectedTo() != null) {
            filters.add(Criteria.where("detectedAt").lte(query.detectedTo()));
        }
        if (cursor != null) {
            filters.add(cursorCriteria(cursor));
        }
        Query mongoQuery = new Query();
        if (filters.size() == 1) {
            mongoQuery.addCriteria(filters.getFirst());
        } else if (!filters.isEmpty()) {
            mongoQuery.addCriteria(new Criteria().andOperator(filters));
        }
        return mongoQuery;
    }

    private Criteria cursorCriteria(SuspiciousTransactionCursor cursor) {
        return new Criteria().orOperator(
                Criteria.where("detectedAt").lt(cursor.detectedAt()),
                new Criteria().andOperator(
                        Criteria.where("detectedAt").is(cursor.detectedAt()),
                        Criteria.where("suspiciousTransactionId").lt(cursor.suspiciousTransactionId())
                )
        );
    }

    private String cursor(SuspiciousTransactionDocument document) {
        return cursorCodec.encode(document.getDetectedAt(), document.getSuspiciousTransactionId());
    }
}
