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

import java.time.Instant;
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
        query.with(stableReadSort(guardedPageable.getSort()));
        List<FraudCaseDocument> content = mongoTemplate.find(query, FraudCaseDocument.class);
        return new PageImpl<>(content, guardedPageable, total);
    }

    @Override
    public Slice<FraudCaseDocument> searchSlice(FraudCaseSearchCriteria criteria, Pageable pageable) {
        Pageable guardedPageable = guardPageSize(pageable);
        Query query = new Query();
        criteria(criteria).forEach(query::addCriteria);
        query.with(FraudCaseReadQueryPolicy.stableReadSort(guardedPageable.getSort()));
        query.skip(guardedPageable.getOffset());
        query.limit(guardedPageable.getPageSize() + 1);
        List<FraudCaseDocument> fetched = mongoTemplate.find(query, FraudCaseDocument.class);
        boolean hasNext = fetched.size() > guardedPageable.getPageSize();
        List<FraudCaseDocument> content = hasNext
                ? fetched.subList(0, guardedPageable.getPageSize())
                : fetched;
        return new SliceImpl<>(content, guardedPageable, hasNext);
    }

    @Override
    public Slice<FraudCaseDocument> searchSliceAfter(
            FraudCaseSearchCriteria criteria,
            int size,
            Sort.Order sortOrder,
            FraudCaseWorkQueueCursor cursor
    ) {
        FraudCaseReadQueryPolicy.validateRepositoryPageBounds(0, size);
        Query query = new Query();
        criteria(criteria).forEach(query::addCriteria);
        query.addCriteria(keysetCriteria(sortOrder, cursor));
        query.with(FraudCaseReadQueryPolicy.stableReadSort(Sort.by(sortOrder)));
        query.limit(size + 1);
        List<FraudCaseDocument> fetched = mongoTemplate.find(query, FraudCaseDocument.class);
        boolean hasNext = fetched.size() > size;
        List<FraudCaseDocument> content = hasNext ? fetched.subList(0, size) : fetched;
        return new SliceImpl<>(content, org.springframework.data.domain.PageRequest.of(0, size, Sort.by(sortOrder)), hasNext);
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

    private Sort stableReadSort(Sort requestedSort) {
        return FraudCaseReadQueryPolicy.stableReadSort(requestedSort);
    }

    private Criteria keysetCriteria(Sort.Order sortOrder, FraudCaseWorkQueueCursor cursor) {
        String field = sortOrder.getProperty();
        Object lastValue = cursorValue(field, cursor.lastValue());
        Criteria primary = sortOrder.isDescending()
                ? Criteria.where(field).lt(lastValue)
                : Criteria.where(field).gt(lastValue);
        Criteria tieBreaker = new Criteria().andOperator(
                Criteria.where(field).is(lastValue),
                Criteria.where("_id").gt(cursor.lastId())
        );
        return new Criteria().orOperator(primary, tieBreaker);
    }

    private Object cursorValue(String field, String value) {
        return switch (field) {
            case "createdAt", "updatedAt" -> Instant.parse(value);
            case "priority" -> com.frauddetection.alert.domain.FraudCasePriority.valueOf(value);
            case "riskLevel" -> com.frauddetection.common.events.enums.RiskLevel.valueOf(value);
            case "caseNumber" -> value;
            default -> throw new FraudCaseWorkQueueQueryException("INVALID_CURSOR", "Invalid fraud case work queue cursor.");
        };
    }

    private Pageable guardPageSize(Pageable pageable) {
        FraudCaseReadQueryPolicy.validateRepositoryPageBounds(pageable.getPageNumber(), Math.min(pageable.getPageSize(), FraudCaseReadQueryPolicy.MAX_PAGE_SIZE));
        if (pageable.getPageSize() <= FraudCaseReadQueryPolicy.MAX_PAGE_SIZE) {
            return pageable;
        }
        return org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(),
                FraudCaseReadQueryPolicy.MAX_PAGE_SIZE,
                pageable.getSort()
        );
    }
}
