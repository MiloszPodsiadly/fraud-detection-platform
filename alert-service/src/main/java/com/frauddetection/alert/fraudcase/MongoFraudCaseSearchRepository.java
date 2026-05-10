package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.persistence.FraudCaseDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Repository
public class MongoFraudCaseSearchRepository implements FraudCaseSearchRepository {

    private static final int MAX_PAGE_SIZE = 100;

    private final MongoTemplate mongoTemplate;

    public MongoFraudCaseSearchRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Page<FraudCaseDocument> search(FraudCaseSearchCriteria criteria, Pageable pageable) {
        Pageable guardedPageable = guardPageSize(pageable);
        Query query = new Query();
        criteria(criteria).forEach(query::addCriteria);
        long total = mongoTemplate.count(query, FraudCaseDocument.class);
        query.with(guardedPageable);
        query.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("_id")));
        List<FraudCaseDocument> content = mongoTemplate.find(query, FraudCaseDocument.class);
        return new PageImpl<>(content, guardedPageable, total);
    }

    private List<Criteria> criteria(FraudCaseSearchCriteria criteria) {
        List<Criteria> filters = new ArrayList<>();
        if (criteria.status() != null) {
            filters.add(Criteria.where("status").is(criteria.status()));
        }
        if (StringUtils.hasText(criteria.assignedInvestigatorId())) {
            filters.add(Criteria.where("assignedInvestigatorId").is(criteria.assignedInvestigatorId()));
        }
        if (criteria.priority() != null) {
            filters.add(Criteria.where("priority").is(criteria.priority()));
        }
        if (criteria.riskLevel() != null) {
            filters.add(Criteria.where("riskLevel").is(criteria.riskLevel()));
        }
        if (StringUtils.hasText(criteria.linkedAlertId())) {
            filters.add(Criteria.where("linkedAlertIds").is(criteria.linkedAlertId()));
        }
        if (criteria.createdFrom() != null || criteria.createdTo() != null) {
            Criteria createdAt = Criteria.where("createdAt");
            if (criteria.createdFrom() != null) {
                createdAt = createdAt.gte(criteria.createdFrom());
            }
            if (criteria.createdTo() != null) {
                createdAt = createdAt.lte(criteria.createdTo());
            }
            filters.add(createdAt);
        }
        return filters;
    }

    private Pageable guardPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(),
                MAX_PAGE_SIZE,
                pageable.getSort()
        );
    }
}
