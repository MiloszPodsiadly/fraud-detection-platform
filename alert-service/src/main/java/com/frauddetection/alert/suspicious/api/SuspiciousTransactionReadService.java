package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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

    public Page<SuspiciousTransactionResponse> search(SuspiciousTransactionSearchQuery query) {
        SuspiciousTransactionSearchQuery boundedQuery = Objects.requireNonNull(query, "query is required");
        Query mongoQuery = mongoQuery(boundedQuery);
        long total = mongoTemplate.count(mongoQuery, SuspiciousTransactionDocument.class);
        List<SuspiciousTransactionResponse> content = mongoTemplate.find(
                        mongoQuery.with(boundedQuery.pageable()),
                        SuspiciousTransactionDocument.class
                ).stream()
                .map(SuspiciousTransactionResponse::from)
                .toList();
        return new PageImpl<>(content, boundedQuery.pageable(), total);
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
