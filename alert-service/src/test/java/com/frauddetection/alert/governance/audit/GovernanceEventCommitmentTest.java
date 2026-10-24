package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceEventCommitmentTest {
    @Test void storedDatePrecisionProducesSameHashOnHistoricalRead() {
        var conversions = new MongoCustomConversions(List.of());
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        var mongo = mock(MongoTemplate.class);
        when(mongo.getConverter()).thenReturn(converter);
        when(mongo.insert(any(WriteActionAuditOutboxRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var event = new PolicyTrustChangeAuditDocument("event-1", "request-1", "APPROVE",
                "actor-1", "a".repeat(64), "b".repeat(64), "proof-1",
                Instant.parse("2026-10-24T12:00:00.123456789Z"));
        var hash = GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.TRUST_CHANGE,
                event.id(), event, "CURRENT_INSTITUTION/TEST", event.actorId(), event.requestId(),
                "operation-1", event.assertionSha256(), event.requestId(), event.occurredAt());
        var bson = new Document();
        converter.write(event, bson);
        var historical = converter.read(PolicyTrustChangeAuditDocument.class, bson);
        assertThat(GovernanceEventCommitment.hash(GovernanceEventCommitment.Kind.TRUST_CHANGE,
                historical.id(), historical, "CURRENT_INSTITUTION/TEST", historical.actorId(),
                historical.requestId(), "operation-1", historical.assertionSha256(),
                historical.requestId(), historical.occurredAt())).isEqualTo(hash);
    }
}
