package com.frauddetection.alert.audit.read;

import org.springframework.data.repository.Repository;

public interface ReadAccessAuditRepository extends Repository<ReadAccessAuditEventDocument, String> {

    ReadAccessAuditEventDocument save(ReadAccessAuditEventDocument event);
}
