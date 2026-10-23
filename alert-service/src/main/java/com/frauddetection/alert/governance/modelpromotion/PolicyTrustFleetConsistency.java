package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares declared deployment membership with unauthenticated, startup-loaded runtime telemetry. */
public final class PolicyTrustFleetConsistency {
    public static final int MAX_OBSERVATIONS = 100;
    private static final long FRESH_SECONDS = 90;

    private PolicyTrustFleetConsistency() { }

    public static Membership membership(String deploymentId, String alertIds, String publisherIds) {
        List<Member> expected = new ArrayList<>();
        add(expected, "ALERT_VERIFIER", alertIds);
        add(expected, "PUBLISHER", publisherIds);
        boolean complete = deploymentId != null && deploymentId.matches("^[A-Za-z0-9._:-]{1,128}$")
                && expected.stream().anyMatch(member -> "ALERT_VERIFIER".equals(member.role()))
                && expected.stream().anyMatch(member -> "PUBLISHER".equals(member.role()))
                && expected.size() <= MAX_OBSERVATIONS
                && new HashSet<>(expected).size() == expected.size()
                && expected.stream().allMatch(member -> member.instanceId().matches("^[A-Za-z0-9._:-]{1,128}$"));
        return new Membership(complete ? deploymentId : null, List.copyOf(expected), complete);
    }

    private static void add(List<Member> expected, String role, String csv) {
        if (csv == null || csv.isBlank()) return;
        for (String id : csv.split(",", -1)) expected.add(new Member(role, id.trim()));
    }

    public static Assessment assess(Membership membership, List<PolicyTrustInstanceObservation> observations,
            long generation, String digest, long rollbackFloor, Instant now, boolean truncated) {
        List<ObservationView> viewed = new ArrayList<>();
        List<Member> missing = new ArrayList<>();
        List<Member> stale = new ArrayList<>();
        List<Member> mismatched = new ArrayList<>();
        if (!membership.complete()) {
            for (PolicyTrustInstanceObservation observation : observations) {
                viewed.add(new ObservationView(observation.role(), observation.instanceId(),
                        observation.deploymentId(), observation.processEpoch(), observation.processStartedAt(),
                        observation.generation(), observation.digestSha256(), observation.observedAt(),
                        false, "MEMBERSHIP_UNKNOWN"));
            }
            return new Assessment("UNKNOWN", "EXPECTED_MEMBERSHIP_UNAVAILABLE", membership.expected(),
                    List.of(), List.of(), List.of(), List.copyOf(viewed), truncated);
        }
        Instant freshAfter = now.minusSeconds(FRESH_SECONDS);
        Set<Member> expected = Set.copyOf(membership.expected());
        Map<Member, List<PolicyTrustInstanceObservation>> byMember = new HashMap<>();
        boolean hardMismatch = false;
        boolean rollback = false;
        int exactFresh = 0;
        int freshCount = 0;
        for (PolicyTrustInstanceObservation observation : observations) {
            Member member = new Member(observation.role(), observation.instanceId());
            boolean validProcess = observation.processEpoch() != null && !observation.processEpoch().isBlank()
                    && observation.processStartedAt() != null && observation.observedAt() != null
                    && !observation.processStartedAt().isAfter(observation.observedAt());
            boolean fresh = validProcess && !observation.observedAt().isBefore(freshAfter)
                    && !observation.observedAt().isAfter(now.plusSeconds(5));
            String reason = !membership.deploymentId().equals(observation.deploymentId())
                    ? "DEPLOYMENT_ID_MISMATCH"
                    : !expected.contains(member) ? "UNEXPECTED_INSTANCE"
                    : !validProcess ? "UNBOUND_PROCESS"
                    : !fresh ? "STALE"
                    : observation.generation() < rollbackFloor ? "ROLLBACK_BELOW_FLOOR"
                    : observation.generation() != generation ? "GENERATION_MISMATCH"
                    : !digest.equals(observation.digestSha256()) ? "DIGEST_MISMATCH" : "MATCH";
            viewed.add(new ObservationView(observation.role(), observation.instanceId(),
                    observation.deploymentId(), observation.processEpoch(), observation.processStartedAt(),
                    observation.generation(), observation.digestSha256(), observation.observedAt(), fresh, reason));
            if (fresh) {
                freshCount++;
                if ("MATCH".equals(reason)) exactFresh++;
                if ("ROLLBACK_BELOW_FLOOR".equals(reason)) rollback = true;
                if ("DIGEST_MISMATCH".equals(reason) || "UNEXPECTED_INSTANCE".equals(reason)
                        || "DEPLOYMENT_ID_MISMATCH".equals(reason)) hardMismatch = true;
            }
            if (membership.deploymentId().equals(observation.deploymentId()) && expected.contains(member)) {
                byMember.computeIfAbsent(member, ignored -> new ArrayList<>()).add(observation);
            }
        }
        for (Member member : membership.expected()) {
            List<PolicyTrustInstanceObservation> reports = byMember.getOrDefault(member, List.of());
            if (reports.isEmpty()) { missing.add(member); continue; }
            List<PolicyTrustInstanceObservation> live = reports.stream().filter(report ->
                    report.processEpoch() != null && report.processStartedAt() != null
                            && report.observedAt() != null
                            && !report.processStartedAt().isAfter(report.observedAt())
                            && !report.observedAt().isBefore(freshAfter)
                            && !report.observedAt().isAfter(now.plusSeconds(5))).toList();
            if (live.isEmpty()) { stale.add(member); continue; }
            if (live.size() != 1 || live.getFirst().generation() != generation
                    || !digest.equals(live.getFirst().digestSha256())) mismatched.add(member);
            if (live.size() > 1) hardMismatch = true;
        }
        String state = truncated ? "UNKNOWN"
                : rollback ? "REVOKED_OR_ROLLED_BACK"
                : hardMismatch ? "DEPLOYMENT_FAILED"
                : missing.isEmpty() && stale.isEmpty() && mismatched.isEmpty()
                        && exactFresh == membership.expected().size() ? "OBSERVED_CONSISTENT"
                : freshCount == 0 ? "APPROVED_PENDING_OPERATOR_ACTION"
                : exactFresh == 0 ? "DEPLOYMENT_IN_PROGRESS" : "OBSERVED_PARTIAL";
        String reason = truncated ? "OBSERVATION_SET_TRUNCATED"
                : "OBSERVED_CONSISTENT".equals(state) ? "DECLARED_MEMBERSHIP_MATCHES_LOADED_TRUST"
                : "REVOKED_OR_ROLLED_BACK".equals(state) ? "GENERATION_BELOW_PROTECTED_FLOOR"
                : hardMismatch ? "DUPLICATE_UNEXPECTED_OR_WRONG_DIGEST"
                : "EXPECTED_INSTANCES_NOT_ALL_MATCHING";
        return new Assessment(state, reason, membership.expected(), List.copyOf(missing),
                List.copyOf(stale), List.copyOf(mismatched), List.copyOf(viewed), truncated);
    }

    public record Member(String role, String instanceId) { }
    public record Membership(String deploymentId, List<Member> expected, boolean complete) { }
    public record ObservationView(String role, String instanceId, String deploymentId, String processEpoch,
            Instant processStartedAt, long generation, String digestSha256, Instant observedAt,
            boolean fresh, String reason) { }
    public record Assessment(String rolloutState, String mismatchReason, List<Member> expected,
            List<Member> missing, List<Member> stale, List<Member> mismatched,
            List<ObservationView> observations, boolean truncated) { }
}
