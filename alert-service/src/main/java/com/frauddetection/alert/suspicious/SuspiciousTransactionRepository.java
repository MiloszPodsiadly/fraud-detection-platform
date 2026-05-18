package com.frauddetection.alert.suspicious;

import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SuspiciousTransactionRepository extends MongoRepository<SuspiciousTransactionDocument, String> {

    Optional<SuspiciousTransactionDocument> findByTransactionIdAndSourceEventId(String transactionId, String sourceEventId);

    Page<SuspiciousTransactionDocument> findByCustomerId(String customerId, Pageable pageable);

    Page<SuspiciousTransactionDocument> findByRiskLevel(RiskLevel riskLevel, Pageable pageable);

    Page<SuspiciousTransactionDocument> findByStatus(SuspiciousTransactionStatus status, Pageable pageable);

    Page<SuspiciousTransactionDocument> findByLinkedAlertId(String linkedAlertId, Pageable pageable);
}
