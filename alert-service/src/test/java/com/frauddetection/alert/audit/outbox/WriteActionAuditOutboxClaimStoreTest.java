package com.frauddetection.alert.audit.outbox;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteActionAuditOutboxClaimStoreTest {

    private static final Instant NOW = Instant.parse("2026-06-26T12:00:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final WriteActionAuditOutboxClaimStore store = new WriteActionAuditOutboxClaimStore(mongoTemplate);

    @Test
    void claimUsesAtomicFindAndModifyWithStatusAndRetryWindowCondition() {
        WriteActionAuditOutboxRecord claimed = new WriteActionAuditOutboxRecord();
        when(mongoTemplate.findAndModify(
                org.mockito.ArgumentMatchers.any(Query.class),
                org.mockito.ArgumentMatchers.any(UpdateDefinition.class),
                org.mockito.ArgumentMatchers.any(FindAndModifyOptions.class),
                eq(WriteActionAuditOutboxRecord.class)
        )).thenReturn(claimed);

        Optional<WriteActionAuditOutboxRecord> result = store.claimForPublishing("wao-1", NOW, "publisher-1");

        assertThat(result).containsSame(claimed);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).findAndModify(
                query.capture(),
                update.capture(),
                org.mockito.ArgumentMatchers.any(FindAndModifyOptions.class),
                eq(WriteActionAuditOutboxRecord.class)
        );
        Document queryObject = query.getValue().getQueryObject();
        assertThat(queryObject.toString())
                .contains("_id=wao-1")
                .contains("status")
                .contains("PENDING")
                .contains("FAILED_RETRYABLE")
                .contains("next_attempt_at");
        assertThat(update.getValue().getUpdateObject().toString())
                .contains("PUBLISHING")
                .contains("last_attempt_at")
                .contains("claimed_at")
                .contains("claim_owner");
    }

    @Test
    void blankOutboxIdDoesNotAttemptClaim() {
        assertThat(store.claimForPublishing(" ", NOW, "publisher-1")).isEmpty();
    }
}
