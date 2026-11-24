package com.frauddetection.enricher.device;

import com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Signed Source/Device/Kafka/Graph owner proof; this does not claim a live scorer bridge. */
@Tag("UNTRUSTED_TEST_ONLY")
class SignedGraphTwoHopAuthorityIntegrationTest {
    @Test
    void freshOwnerReadRejectsConflictAfterPriorPositiveReadAndSurvivesTransientOutage() throws Exception {
        SignedGraphABTestFixture.Evidence evidence = SignedGraphABTestFixture.create();
        GraphDeviceMongoVerticalTestFacade.Result graph = evidence.graph();

        assertThat(evidence.priorTime()).isBefore(evidence.rootTime());
        assertThat(evidence.deviceOccurrenceRows()).isEqualTo(2);
        assertThat(evidence.scoredOccurrenceRows()).isEqualTo(2);
        assertThat(evidence.adapterReconstructions()).isEqualTo(1);
        assertThat(graph.appendStates()).containsExactly("APPENDED", "APPENDED");
        assertThat(graph.replayStates()).containsExactly("EXISTING", "EXISTING");
        assertThat(graph.postChangeReplayStates()).containsExactly("EXISTING", "EXISTING");
        assertThat(graph.replayPreservedEvidence()).isTrue();
        assertThat(graph.persistedRows()).isEqualTo(2);

        assertThat(graph.twoHopRead()).satisfies(read -> {
            assertThat(read.state()).isEqualTo("VERIFIED_BOUNDED");
            assertThat(read.reason()).isEqualTo("NONE");
            assertThat(read.rootTime()).isEqualTo(evidence.rootTime());
            assertThat(read.priorTime()).isEqualTo(evidence.priorTime());
            assertThat(read.pseudonymKeyVersion()).isEqualTo(1);
            assertThat(read.graphAssociationAdmittedAt()).isBeforeOrEqualTo(read.diagnosticReadAt());
            assertThat(read.diagnosticReadAt()).isEqualTo(evidence.readAt());
        });
        assertThat(graph.outageRead()).satisfies(read -> {
            assertThat(read.state()).isEqualTo("UNAVAILABLE");
            assertThat(read.reason()).isEqualTo("OWNER_UNAVAILABLE");
            assertThat(read.priorTime()).isNull();
        });
        assertThat(graph.recoveredRead()).satisfies(read -> {
            assertThat(read.state()).isEqualTo("VERIFIED_BOUNDED");
            assertThat(read.priorTime()).isEqualTo(evidence.priorTime());
        });
        assertThat(graph.conflictRead()).satisfies(read -> {
            assertThat(read.state()).isEqualTo("CONFLICT");
            assertThat(read.reason()).isEqualTo("CONFLICTING_EVIDENCE");
            assertThat(read.priorTime()).isNull();
        });
    }
}
