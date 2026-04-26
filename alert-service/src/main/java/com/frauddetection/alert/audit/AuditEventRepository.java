package com.frauddetection.alert.audit;

import org.springframework.data.repository.Repository;

public interface AuditEventRepository extends Repository<AuditEventDocument, String> {

    AuditEventDocument save(AuditEventDocument document);
}
