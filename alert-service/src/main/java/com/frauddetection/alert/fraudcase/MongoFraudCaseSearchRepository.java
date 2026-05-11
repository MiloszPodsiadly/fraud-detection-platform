package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.persistence.FraudCaseDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
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
        query.with(stableSort(guardedPageable.getSort()));
        List<FraudCaseDocument> content = mongoTemplate.find(query, FraudCaseDocument.class);
        return new PageImpl<>(content, guardedPageable, total);
    }

    @Override
    public Slice<FraudCaseDocument> searchSlice(FraudCaseSearchCriteria criteria, Pageable pageable) {
        Pageable guardedPageable = guardPageSize(pageable);
        Query query = new Query();
        criteria(criteria).forEach(query::addCriteria);
        query.with(FraudCaseWorkQueueQueryPolicy.stableSort(guardedPageable.getSort()));
        query.skip(guardedPageable.getOffset());
        query.limit(guardedPageable.getPageSize() + 1);
        List<FraudCaseDocument> fetched = mongoTemplate.find(query, FraudCaseDocument.class);
        boolean hasNext = fetched.size() > guardedPageable.getPageSize();
        List<FraudCaseDocument> content = hasNext
                ? fetched.subList(0, guardedPageable.getPageSize())
                : fetched;
        return new SliceImpl<>(content, guardedPageable, hasNext);
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
        if (criteria.updatedFrom() != null || criteria.updatedTo() != null) {
            Criteria updatedAt = Criteria.where("updatedAt");
            if (criteria.updatedFrom() != null) {
                updatedAt = updatedAt.gte(criteria.updatedFrom());
            }
            if (criteria.updatedTo() != null) {
                updatedAt = updatedAt.lte(criteria.updatedTo());
            }
            filters.add(updatedAt);
        }
        return filters;
    }

    private Sort stableSort(Sort requestedSort) {
        return FraudCaseWorkQueueQueryPolicy.stableSort(requestedSort);
    }

    private Pageable guardPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= FraudCaseWorkQueueQueryPolicy.MAX_PAGE_SIZE) {
            return pageable;
        }
        return org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(),
                FraudCaseWorkQueueQueryPolicy.MAX_PAGE_SIZE,
                pageable.getSort()
        );
    }
}
