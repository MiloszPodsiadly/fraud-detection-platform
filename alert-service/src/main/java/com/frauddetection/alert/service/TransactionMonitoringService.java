package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TransactionMonitoringService implements TransactionMonitoringUseCase {

    private final ScoredTransactionRepository repository;
    private final ScoredTransactionDocumentMapper mapper;
    private final MongoTemplate mongoTemplate;
    private final ScoredTransactionSearchPolicy searchPolicy;

    public TransactionMonitoringService(
            ScoredTransactionRepository repository,
            ScoredTransactionDocumentMapper mapper,
            MongoTemplate mongoTemplate,
            ScoredTransactionSearchPolicy searchPolicy
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
        this.searchPolicy = searchPolicy;
    }

    @Override
    public void recordScoredTransaction(TransactionScoredEvent event) {
        repository.save(mapper.toDocument(event));
    }

    @Override
    public Page<ScoredTransaction> listScoredTransactions(Pageable pageable) {
        return repository.findAll(pageable)
                .map(mapper::toDomain);
    }

    @Override
    public Page<ScoredTransaction> listScoredTransactions(Pageable pageable, ScoredTransactionSearchCriteria criteria) {
        if (criteria == null || !criteria.hasFilters()) {
            return listScoredTransactions(pageable);
        }

        Query query = buildQuery(criteria).with(pageable);
        List<ScoredTransaction> content = mongoTemplate.find(query, ScoredTransactionDocument.class).stream()
                .map(mapper::toDomain)
                .toList();
        long total = cappedTotal(criteria);
        return new PageImpl<>(content, pageable, total);
    }

    private long cappedTotal(ScoredTransactionSearchCriteria criteria) {
        Query countProbe = buildQuery(criteria)
                .limit(ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT + 1);
        countProbe.fields().include("_id");
        int boundedCount = mongoTemplate.find(countProbe, ScoredTransactionDocument.class).size();
        return Math.min(boundedCount, ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT);
    }

    private Query buildQuery(ScoredTransactionSearchCriteria criteria) {
        List<Criteria> filters = new ArrayList<>();
        addQueryFilter(filters, criteria.query());
        addRiskFilter(filters, criteria.riskLevel());
        addClassificationFilter(filters, criteria.alertRecommended());

        Query query = new Query();
        if (!filters.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(filters.toArray(Criteria[]::new)));
        }
        return query;
    }

    private void addQueryFilter(List<Criteria> filters, String queryText) {
        if (!hasText(queryText)) {
            return;
        }
        String normalizedQuery = searchPolicy.normalizeQueryForSearch(queryText);
        String prefixPattern = "^" + Pattern.quote(normalizedQuery);
        String exactPattern = "^" + Pattern.quote(normalizedQuery) + "$";
        filters.add(new Criteria().orOperator(
                Criteria.where("transactionIdSearch").regex(prefixPattern),
                Criteria.where("customerIdSearch").regex(prefixPattern),
                Criteria.where("merchantIdSearch").regex(prefixPattern),
                Criteria.where("currencySearch").regex(exactPattern)
        ));
    }

    private void addRiskFilter(List<Criteria> filters, com.frauddetection.common.events.enums.RiskLevel riskLevel) {
        if (riskLevel == null) {
            return;
        }
        filters.add(Criteria.where("riskLevel").is(riskLevel));
    }

    private void addClassificationFilter(List<Criteria> filters, Boolean alertRecommended) {
        if (alertRecommended == null) {
            return;
        }
        filters.add(Criteria.where("alertRecommended").is(alertRecommended));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
