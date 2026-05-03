package com.frauddetection.alert.trust.failure;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.trust.TrustIncidentDocument;
import com.frauddetection.alert.trust.TrustIncidentMaterializer;
import com.frauddetection.alert.trust.TrustIncidentPolicy;
import com.frauddetection.alert.trust.TrustIncidentRepository;
import com.frauddetection.alert.trust.TrustIncidentSeverity;
import com.frauddetection.alert.trust.TrustIncidentStatus;
import com.frauddetection.alert.trust.TrustSignal;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("failure-injection")
@Tag("invariant-proof")
@Tag("integration")
class TrustIncidentConcurrentMaterializationIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory mongoClientDatabaseFactory;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        String databaseName = "trust_incident_concurrency_" + UUID.randomUUID().toString().replace("-", "");
        mongoClientDatabaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(mongoClientDatabaseFactory);
        mongoTemplate.indexOps(TrustIncidentDocument.class)
                .ensureIndex(new Index().on("active_dedupe_key", Sort.Direction.ASC).unique().sparse());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (mongoClientDatabaseFactory != null) {
            mongoClientDatabaseFactory.destroy();
        }
    }

    @Test
    void shouldCreateOnlyOneActiveTrustIncidentUnderConcurrentMaterialization() throws Exception {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        when(repository.findByActiveDedupeKey(any())).thenAnswer(invocation -> Optional.ofNullable(mongoTemplate.findOne(
                Query.query(Criteria.where("active_dedupe_key").is(invocation.getArgument(0))),
                TrustIncidentDocument.class
        )));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustIncidentMaterializer materializer = new TrustIncidentMaterializer(
                repository,
                mongoTemplate,
                new TrustIncidentPolicy(),
                new AlertServiceMetrics(registry)
        );
        TrustSignal signal = new TrustSignal(
                "OUTBOX_TERMINAL_FAILURE",
                TrustIncidentSeverity.CRITICAL,
                "transactional_outbox",
                "status=FAILED_TERMINAL",
                List.of("outbox:event-1")
        );
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> materializeAfterStart(materializer, signal, start));
            var second = executor.submit(() -> materializeAfterStart(materializer, signal, start));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        List<TrustIncidentDocument> incidents = mongoTemplate.find(
                Query.query(Criteria.where("status").in(List.of(TrustIncidentStatus.OPEN.name(), TrustIncidentStatus.ACKNOWLEDGED.name()))),
                TrustIncidentDocument.class
        );
        assertThat(incidents).hasSize(1);
        TrustIncidentDocument incident = incidents.getFirst();
        assertThat(incident.getActiveDedupeKey()).startsWith("OUTBOX_TERMINAL_FAILURE:transactional_outbox:");
        assertThat(incident.getOccurrenceCount()).isEqualTo(2L);
        assertThat(incident.getSeverity()).isEqualTo(TrustIncidentSeverity.CRITICAL);
        assertThat(registry.counter(
                "trust_incident_materialized_total",
                "type", "OUTBOX_TERMINAL_FAILURE",
                "severity", "CRITICAL",
                "result", "CREATED"
        ).count()).isEqualTo(1.0d);
        assertThat(registry.counter(
                "trust_incident_deduped_total",
                "type", "OUTBOX_TERMINAL_FAILURE",
                "severity", "CRITICAL"
        ).count()).isEqualTo(1.0d);
    }

    private Void materializeAfterStart(
            TrustIncidentMaterializer materializer,
            TrustSignal signal,
            CountDownLatch start
    ) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        materializer.materialize(List.of(signal));
        return null;
    }
}
