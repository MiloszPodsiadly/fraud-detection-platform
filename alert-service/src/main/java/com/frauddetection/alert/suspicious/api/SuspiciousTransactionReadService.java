package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
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

    public SuspiciousTransactionReadService(
            SuspiciousTransactionRepository repository,
            MongoTemplate mongoTemplate
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
    }

    public Optional<SuspiciousTransactionResponse> findById(String suspiciousTransactionId) {
        if (suspiciousTransactionId == null || suspiciousTransactionId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(suspiciousTransactionId.trim())
                .map(SuspiciousTransactionResponse::from);
    }

    public SuspiciousTransactionSliceResponse search(SuspiciousTransactionSearchQuery query) {
        SuspiciousTransactionSearchQuery boundedQuery = Objects.requireNonNull(query, "query is required");
        int size = boundedQuery.size();
        int page = boundedQuery.page();
        Query mongoQuery = mongoQuery(boundedQuery);
        mongoQuery.with(boundedQuery.pageable().getSort());
        mongoQuery.skip((long) page * size);
        mongoQuery.limit(size + 1);

        List<SuspiciousTransactionDocument> documents = mongoTemplate.find(
                mongoQuery,
                SuspiciousTransactionDocument.class
        );
        boolean hasNext = documents.size() > size;
        List<SuspiciousTransactionResponse> content = documents.stream()
                .limit(size)
                .map(SuspiciousTransactionResponse::from)
                .toList();
        return new SuspiciousTransactionSliceResponse(content, page, size, hasNext);
    }

    private Query mongoQuery(SuspiciousTransactionSearchQuery query) {
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
        Query mongoQuery = new Query();
        if (!filters.isEmpty()) {
            mongoQuery.addCriteria(new Criteria().andOperator(filters));
        }
        return mongoQuery;
    }
}
