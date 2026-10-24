package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicySecurityOperationsControllerTest {
    @Test
    void truncatedObservationReadDoesNotReportTrustConsistency() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(mongo.getDb()).thenReturn(database);
        when(database.runCommand(any(Document.class))).thenReturn(new Document("setName", "rs0")
                .append("logicalSessionTimeoutMinutes", 30).append("isWritablePrimary", true));
        Instant now = Instant.parse("2026-10-21T12:00:00Z");
        List<PolicyTrustInstanceObservation> observations = IntStream.range(0, 1_001)
                .mapToObj(index -> new PolicyTrustInstanceObservation("observation-" + index, "PUBLISHER",
                        "instance-" + index, PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                        1, "a".repeat(64), now))
                .toList();
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class))).thenReturn(observations);
        var controller = new PolicySecurityOperationsController(mongo,
                mock(PolicyTrustRegistrySnapshotService.class), "https://id.example", "localhost",
                "http://localhost:4173", Clock.fixed(now, ZoneOffset.UTC));
        var authentication = new UsernamePasswordAuthenticationToken(
                new AnalystPrincipal("operator", Set.of(), Set.of(), "https://id.example#operator"), null,
                List.of(new SimpleGrantedAuthority(ModelPolicyAuthority.PRODUCTION),
                        new SimpleGrantedAuthority(ModelPolicyAuthority.TRUST_READ)));

        var diagnostics = controller.operations(authentication);

        assertThat(diagnostics.signingWorkerAvailability()).isEqualTo("HEARTBEAT_OBSERVED");
        assertThat(diagnostics.trustRegistryConsistency()).isEqualTo("OBSERVATION_SET_TRUNCATED");
        var query = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(PolicyTrustInstanceObservation.class));
        assertThat(query.getValue().getLimit()).isEqualTo(1_001);
    }
}
