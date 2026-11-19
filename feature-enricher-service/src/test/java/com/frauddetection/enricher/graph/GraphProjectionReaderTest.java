package com.frauddetection.enricher.graph;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GraphProjectionReaderTest {
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00.000Z");
    private static final Instant EVENT = Instant.parse("2026-11-18T10:00:00.000Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRINCIPAL = "graph-diagnostics-test";
    private static final byte[] CURSOR_KEY = new byte[32];

    @Test
    void twoIndependentlyAuthorizedOccurrencesReturnOnlyTheirBoundedEdges() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge first = stored(scope, "occurrence-one", "shared-device");
        GraphStoredEdge second = stored(scope, "occurrence-two", "shared-device");
        FakeStore store = new FakeStore(List.of(first, second));
        RecordingAuthority owners = new RecordingAuthority();
        GraphProjectionReader reader = reader(store, owners, GraphPolicyDecision.AUTHORIZED);

        GraphReadOutcome firstRead = read(reader, grant(scope), scope, first.edge().evidence().occurrence(), 10, null);
        GraphReadOutcome secondRead = read(reader, grant(scope), scope, second.edge().evidence().occurrence(), 10, null);

        assertThat(firstRead.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(secondRead.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(firstRead.edges()).containsExactly(first.edge());
        assertThat(secondRead.edges()).containsExactly(second.edge());
        assertThat(owners.revalidations).hasValue(4);
        assertThat(store.reads).hasValue(6);
    }

    @Test
    void pagesAreDeterministicKeysetPagesAndCursorBindsTheExactBoundedDataset() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphOccurrenceRef root = new GraphOccurrenceRef(scope, token("occurrence-root"), EVENT);
        List<GraphStoredEdge> edges = List.of(stored(scope, "occurrence-root", "device-c"),
                stored(scope, "occurrence-root", "device-a"), stored(scope, "occurrence-root", "device-b"));
        List<String> expected = edges.stream().map(row -> row.edge().canonicalId()).sorted().toList();
        FakeStore store = new FakeStore(edges);
        GraphProjectionReader reader = reader(store, new RecordingAuthority(), GraphPolicyDecision.AUTHORIZED);

        GraphReadOutcome first = read(reader, grant(scope), scope, root, 2, null);
        GraphReadOutcome second = read(reader, grant(scope), scope, root, 2, first.nextCursor().orElseThrow());

        assertThat(first.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(first.edges()).extracting(edge -> edge.canonicalId()).containsExactly(expected.get(0), expected.get(1));
        assertThat(first.nextCursor()).isPresent();
        assertThat(second.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(second.edges()).extracting(edge -> edge.canonicalId()).containsExactly(expected.get(2));
        assertThat(second.nextCursor()).isEmpty();

        store.rows.removeIf(row -> row.edge().canonicalId().equals(expected.get(2)));
        GraphReadOutcome changed = read(reader, grant(scope), scope, root, 2, first.nextCursor().orElseThrow());
        assertThat(changed.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
    }

    @Test
    void forgedCursorAndWrongScopeGrantAreDeniedBeforeGraphRead() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge edge = stored(scope, "occurrence-one", "device-one");
        FakeStore store = new FakeStore(List.of(edge, stored(scope, "occurrence-one", "device-two")));
        GraphProjectionReader reader = reader(store, new RecordingAuthority(), GraphPolicyDecision.AUTHORIZED);

        GraphReadOutcome wrongScope = read(reader, grant(scope), scope("epoch-b"), edge.edge().evidence().occurrence(), 10, null);
        assertThat(wrongScope.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(wrongScope.reason()).isEqualTo(GraphReadReason.GRANT_DENIED);
        assertThat(store.reads).hasValue(0);

        GraphReadOutcome first = read(reader, grant(scope), scope, edge.edge().evidence().occurrence(), 1, null);
        String valid = first.nextCursor().orElseThrow();
        int signatureStart = valid.lastIndexOf('.') + 1;
        String forged = valid.substring(0, signatureStart)
                + (valid.charAt(signatureStart) == 'A' ? "B" : "A")
                + valid.substring(signatureStart + 1);
        GraphReadOutcome rejected = read(reader, grant(scope), scope, edge.edge().evidence().occurrence(), 10, forged);
        assertThat(rejected.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(rejected.reason()).isEqualTo(GraphReadReason.REFERENCE_INVALID);
    }

    @Test
    void mismatchedCurrentPurposeAudienceGrantAndUnboundedNeighborhoodFailClosed() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge edge = stored(scope, "occurrence-root", "device-one");
        GraphOccurrenceRef root = edge.edge().evidence().occurrence();
        FakeStore deniedStore = new FakeStore(List.of(edge));
        GraphReadOutcome denied = read(reader(deniedStore, new RecordingAuthority(), GraphPolicyDecision.DENIED),
                grant(scope), scope, root, 10, null);
        assertThat(denied.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(deniedStore.reads).hasValue(0);

        List<GraphStoredEdge> huge = new ArrayList<>();
        for (int index = 0; index <= GraphQueryLimits.DEFAULT.maxEdges(); index++) {
            huge.add(stored(scope, "occurrence-root", "device-" + index));
        }
        FakeStore hugeStore = new FakeStore(huge);
        RecordingAuthority owners = new RecordingAuthority();
        GraphReadOutcome bounded = read(reader(hugeStore, owners, GraphPolicyDecision.AUTHORIZED),
                grant(scope), scope, root, 50, null);
        assertThat(bounded.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(bounded.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(bounded.edges()).isEmpty();
        assertThat(owners.revalidations).hasValue(0);
    }

    @Test
    void missingCoverageAndRotatedOwnerIdentityNeverProduceVerifiedRows() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge row = stored(scope, "occurrence-one", "device-one");
        FakeStore store = new FakeStore(List.of(row));
        GraphRelationAuthority unavailableOwner = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                revalidations.incrementAndGet();
                return GraphRelationResolution.unavailable(GraphReadReason.COVERAGE_UNAVAILABLE);
            }
        };
        GraphReadOutcome missing = read(reader(store, unavailableOwner, GraphPolicyDecision.AUTHORIZED),
                grant(scope), scope, row.edge().evidence().occurrence(), 10, null);
        assertThat(missing.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);

        GraphRelationAuthority rotatedOwner = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                revalidations.incrementAndGet();
                GraphEvidenceRef rotated = evidence(scope, expected.occurrence(), "device-rotated", 8);
                return GraphRelationResolution.verified(rotated, rotated.expiresAt());
            }
        };
        GraphReadOutcome rotated = read(reader(store, rotatedOwner, GraphPolicyDecision.AUTHORIZED),
                grant(scope), scope, row.edge().evidence().occurrence(), 10, null);
        assertThat(rotated.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(rotated.reason()).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(rotated.edges()).isEmpty();
    }

    @Test
    void outcomesAndGrantsDoNotSerializeTokensOrRawEvidence() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge edge = stored(scope, "private-event-id", "private-device-id");
        GraphReadOutcome outcome = read(reader(new FakeStore(List.of(edge)), new RecordingAuthority(),
                GraphPolicyDecision.AUTHORIZED), grant(scope), scope, edge.edge().evidence().occurrence(), 10, null);

        assertThat(outcome.toString()).doesNotContain("private-event-id", "private-device-id",
                edge.edge().canonicalId(), edge.edge().evidence().associatedIdentityToken());
        assertThat(grant(scope).toString()).doesNotContain("graph-diagnostics-test", scope.institutionToken());
    }

    @Test
    void revocationDuringTheTwoOwnerPassesCannotEscapeAsVerified() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge row = stored(scope, "occurrence-one", "device-one");
        AtomicInteger checks = new AtomicInteger();
        GraphRelationAuthority authority = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                checks.incrementAndGet();
                return checks.get() == 1 ? GraphRelationResolution.verified(expected, expected.expiresAt())
                        : GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED);
            }
        };

        GraphReadOutcome result = read(reader(new FakeStore(List.of(row)), authority,
                GraphPolicyDecision.AUTHORIZED), grant(scope), scope, row.edge().evidence().occurrence(), 10, null);

        assertThat(result.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(result.reason()).isEqualTo(GraphReadReason.AUTHORITY_REVOKED);
        assertThat(result.edges()).isEmpty();
        assertThat(checks).hasValue(2);
    }

    @Test
    void graphLifecycleChangeBetweenReadAndOwnerRecheckInvalidatesThePage() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge active = stored(scope, "occurrence-one", "device-one");
        FakeStore store = new FakeStore(List.of(active));
        store.afterFirstRead = () -> store.rows.set(0, new GraphStoredEdge(active.edge(), GraphEdgeLifecycle.REVOKED,
                active.admittedAt(), active.validUntil()));

        GraphReadOutcome result = read(reader(store, new RecordingAuthority(), GraphPolicyDecision.AUTHORIZED),
                grant(scope), scope, active.edge().evidence().occurrence(), 10, null);

        assertThat(result.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(result.edges()).isEmpty();
        assertThat(store.reads).hasValue(2);
    }

    @Test
    void grantExpiryDuringOwnerScanReturnsDeniedAndDoesNotReturnTheVerifiedPage() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge row = stored(scope, "occurrence-one", "device-one");
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock advancingClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        GraphPrivateReadGrant expiring = GraphPrivateReadGrant.testOnly(PRINCIPAL, scope, 1, NOW.plusSeconds(1));
        GraphRelationAuthority authority = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                now.set(NOW.plusSeconds(2));
                return super.revalidate(expected, principal);
            }
        };
        GraphProjectionReader reader = new GraphProjectionReader(new FakeStore(List.of(row)), authority,
                (grant, principal, grantedScope, at) -> GraphPolicyDecision.AUTHORIZED,
                GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(CURSOR_KEY), advancingClock);

        GraphReadOutcome result = read(reader, expiring, scope, row.edge().evidence().occurrence(), 10, null);

        assertThat(result.state()).isEqualTo(GraphProjectionState.DENIED);
        assertThat(result.reason()).isEqualTo(GraphReadReason.GRANT_DENIED);
        assertThat(result.edges()).isEmpty();
    }

    @Test
    void ownerConnectionFailureAndSlowReadReturnUnavailable() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge row = stored(scope, "occurrence-one", "device-one");
        GraphRelationAuthority disconnected = new RecordingAuthority() {
            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                throw new IllegalStateException("owner connection unavailable");
            }
        };
        GraphReadOutcome disconnectedResult = read(reader(new FakeStore(List.of(row)), disconnected,
                GraphPolicyDecision.AUTHORIZED), grant(scope), scope, row.edge().evidence().occurrence(), 10, null);
        assertThat(disconnectedResult.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(disconnectedResult.reason()).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);

        AtomicLong ticker = new AtomicLong();
        GraphProjectionReader slowReader = new GraphProjectionReader(new FakeStore(List.of(row)),
                new RecordingAuthority(), (grant, principal, grantedScope, now) -> GraphPolicyDecision.AUTHORIZED,
                GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(CURSOR_KEY), CLOCK,
                () -> ticker.getAndAdd(Duration.ofSeconds(3).toNanos()));
        GraphReadOutcome slow = read(slowReader, grant(scope), scope, row.edge().evidence().occurrence(), 10, null);
        assertThat(slow.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(slow.reason()).isEqualTo(GraphReadReason.DEADLINE_EXCEEDED);
        assertThat(slow.edges()).isEmpty();
    }

    @Test
    void deadlineWhilePreparingPostOwnerSnapshotIsNotReportedAsSourceFailure() {
        GraphIdentityScope scope = scope("epoch-a");
        GraphStoredEdge row = stored(scope, "occurrence-one", "device-one");
        AtomicInteger ticks = new AtomicInteger();
        long overBudget = GraphQueryLimits.DEFAULT.maxQueryTime().toNanos() + 1;
        GraphProjectionReader reader = new GraphProjectionReader(new FakeStore(List.of(row)),
                new RecordingAuthority(), (grant, principal, grantedScope, now) -> GraphPolicyDecision.AUTHORIZED,
                GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(CURSOR_KEY), CLOCK,
                () -> ticks.getAndIncrement() < 4 ? 0L : overBudget);

        GraphReadOutcome result = read(reader, grant(scope), scope, row.edge().evidence().occurrence(), 10, null);

        assertThat(result.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(GraphReadReason.DEADLINE_EXCEEDED);
        assertThat(result.edges()).isEmpty();
    }

    @Test
    void readerRemainsPackagePrivateAndHasNoWriteOrFrameworkEntryPoints() {
        assertThat(java.lang.reflect.Modifier.isPublic(GraphProjectionReader.class.getModifiers())).isFalse();
        assertThat(java.util.Arrays.stream(GraphProjectionReader.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("append", "write", "publish", "register");
        assertThat(java.util.Arrays.stream(GraphProjectionReader.class.getDeclaredMethods())
                .anyMatch(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))).isFalse();
    }

    @Test
    void graphProductionPackageHasNoSpringHttpOrKafkaActivationAnnotations() throws Exception {
        Set<String> forbiddenAnnotations = Set.of(
                "org.springframework.stereotype.Component",
                "org.springframework.stereotype.Controller",
                "org.springframework.context.annotation.Configuration",
                "org.springframework.context.annotation.Bean",
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.kafka.annotation.KafkaListener"
        );
        Path classes = Path.of(GraphProjectionReader.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        Path graphPackage = classes.resolve(GraphProjectionReader.class.getPackageName().replace('.', '/'));
        List<String> activatedMembers = new ArrayList<>();
        try (var classFiles = Files.list(graphPackage)) {
            for (Path classFile : classFiles.filter(path -> path.getFileName().toString().endsWith(".class")).toList()) {
                String className = GraphProjectionReader.class.getPackageName() + "."
                        + classFile.getFileName().toString().replaceFirst("\\.class$", "");
                Class<?> graphType = Class.forName(className, false, GraphProjectionReader.class.getClassLoader());
                for (var annotation : graphType.getDeclaredAnnotations()) {
                    if (forbiddenAnnotations.contains(annotation.annotationType().getName())) {
                        activatedMembers.add(className + " " + annotation.annotationType().getSimpleName());
                    }
                }
                for (var method : graphType.getDeclaredMethods()) {
                    for (var annotation : method.getDeclaredAnnotations()) {
                        if (forbiddenAnnotations.contains(annotation.annotationType().getName())) {
                            activatedMembers.add(className + "#" + method.getName() + " "
                                    + annotation.annotationType().getSimpleName());
                        }
                    }
                }
            }
        }
        assertThat(activatedMembers).isEmpty();
    }

    private static GraphProjectionReader reader(FakeStore store, GraphRelationAuthority owners,
                                                GraphPolicyDecision grantDecision) {
        return new GraphProjectionReader(store, owners,
                (grant, principal, scope, now) -> grantDecision, GraphQueryLimits.DEFAULT,
                new GraphReadCursorCodec(CURSOR_KEY), CLOCK);
    }

    private static GraphReadOutcome read(GraphProjectionReader reader, GraphPrivateReadGrant grant,
                                         GraphIdentityScope scope, GraphOccurrenceRef root, int pageSize, String cursor) {
        return reader.readAuthorizedOccurrenceNeighbors(PRINCIPAL, grant, scope, root,
                EVENT.minus(Duration.ofHours(1)), EVENT.plus(Duration.ofHours(1)), pageSize, cursor);
    }

    private static GraphPrivateReadGrant grant(GraphIdentityScope scope) {
        return GraphPrivateReadGrant.testOnly(PRINCIPAL, scope, 1, NOW.plus(Duration.ofDays(1)));
    }

    private static GraphStoredEdge stored(GraphIdentityScope scope, String occurrenceToken, String deviceToken) {
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token(occurrenceToken), EVENT);
        GraphEvidenceRef evidence = evidence(scope, occurrence, deviceToken, 7);
        GraphEdgeFact edge = new GraphEdgeFact(GraphProjectionVersion.CURRENT,
                GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE, GraphNodeRef.occurrence(occurrence),
                GraphNodeRef.device(scope, evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion()), evidence);
        return new GraphStoredEdge(edge, GraphEdgeLifecycle.ACTIVE, EVENT.plusSeconds(1), evidence.expiresAt());
    }

    private static GraphEvidenceRef evidence(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                             String device, int keyVersion) {
        String identity = token(device);
        GraphScoredOccurrenceRef score = new GraphScoredOccurrenceRef(token("score:" + occurrence.occurrenceToken()),
                EVENT.plusSeconds(1), token("fingerprint:" + occurrence.occurrenceToken()), GraphScoredOccurrenceRef.INITIAL_RUN);
        return new GraphEvidenceRef(scope, occurrence, identity, keyVersion, token("receipt:" + occurrence.occurrenceToken()),
                token("raw:" + occurrence.occurrenceToken()), token("enriched:" + occurrence.occurrenceToken()),
                token("assertion:" + occurrence.occurrenceToken()), token("coverage:" + occurrence.occurrenceToken()),
                token("policy:" + occurrence.occurrenceToken()), score, token("snapshot:" + occurrence.occurrenceToken()),
                EVENT.minusSeconds(60), EVENT, 1, EVENT.plus(Duration.ofDays(30)));
    }

    private static GraphIdentityScope scope(String epoch) {
        return new GraphIdentityScope(token("issuer"), token("institution"), token("source"), token(epoch), 1);
    }

    private static String token(String value) { return GraphCanonical.digest("reader-test", value); }

    private static class RecordingAuthority implements GraphRelationAuthority {
        final AtomicInteger revalidations = new AtomicInteger();

        @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
            return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
        }

        @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
            revalidations.incrementAndGet();
            return GraphRelationResolution.verified(expected, expected.expiresAt());
        }
    }

    private static final class FakeStore implements GraphProjectionDao {
        final List<GraphStoredEdge> rows;
        final AtomicInteger reads = new AtomicInteger();
        Runnable afterFirstRead;

        FakeStore(List<GraphStoredEdge> rows) { this.rows = new ArrayList<>(rows); }
        @Override public Optional<GraphStoredEdge> findLogicalRelation(GraphEdgeFact candidate) { return Optional.empty(); }
        @Override public List<GraphStoredEdge> findNeighbors(GraphNodeRef node, int limit) { return List.of(); }

        @Override public List<GraphStoredEdge> findOccurrenceNeighbors(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                                                        Instant asOf, int limit, Duration maxTime) {
            boolean firstRead = reads.incrementAndGet() == 1;
            List<GraphStoredEdge> result = rows.stream().filter(row -> row.edge().from().scope().equals(scope)
                            && row.edge().evidence().occurrence().equals(occurrence)
                            && row.lifecycle() == GraphEdgeLifecycle.ACTIVE && !row.admittedAt().isAfter(asOf)
                            && row.validUntil().isAfter(asOf))
                    .sorted(Comparator.comparing(row -> row.edge().canonicalId()))
                    .limit(limit).toList();
            if (firstRead && afterFirstRead != null) { afterFirstRead.run(); }
            return result;
        }
    }
}
