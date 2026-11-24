package com.frauddetection.scoring.graph;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudEngineExecutionPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Disabled, disconnected diagnostic candidate. It has no production provider or runtime registration. */
final class GraphRiskSignalEngine implements FraudSignalEngine, AutoCloseable {
    static final String ENGINE_ID = "graph.primary";
    static final String NOT_CALIBRATED_REASON = "GRAPH_SIGNAL_NOT_CALIBRATED";
    private static final String ENGINE_VERSION = "graph-v1";
    private static final String EVIDENCE_SOURCE = "GRAPH_RISK";
    private static final FraudEngineDescriptor DESCRIPTOR = new FraudEngineDescriptor(
            ENGINE_ID, FraudEngineType.GRAPH_RISK, "java", ENGINE_VERSION, false);

    private final GraphRiskEvidenceAccess access;
    private final GraphRiskDiagnosticPolicy policy;
    private final BoundedFraudEngineExecutor executor;
    private final Duration deadline;

    /** No provider is the production-safe default; it never attempts an evidence read. */
    GraphRiskSignalEngine() {
        access = null;
        policy = null;
        executor = null;
        deadline = null;
    }

    /** Package-private test seam; no production configuration constructs this candidate. */
    GraphRiskSignalEngine(GraphRiskEvidenceAccess access, Duration deadline) {
        this(access, deadline, BoundedFraudEngineExecutor.defaultInternalExecutor());
    }

    GraphRiskSignalEngine(GraphRiskEvidenceAccess access, Duration deadline,
                          BoundedFraudEngineExecutor executor) {
        this.access = Objects.requireNonNull(access, "GRAPH_EVIDENCE_ACCESS_REQUIRED");
        this.policy = new GraphRiskDiagnosticPolicy();
        this.deadline = new FraudEngineExecutionPolicy(ENGINE_ID, deadline, false).deadline();
        this.executor = Objects.requireNonNull(executor, "GRAPH_ENGINE_EXECUTOR_REQUIRED");
    }

    @Override
    public FraudSignalEvaluation evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "GRAPH_SCORING_CONTEXT_REQUIRED");
        if (access == null) {
            return unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED);
        }
        BoundedFraudEngineExecutor.ExecutionResult<FraudSignalEvaluation> execution = executor.execute(
                () -> evaluateScoped(context), deadline);
        return switch (execution.status()) {
            case COMPLETED -> execution.value();
            case TIMED_OUT -> unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED);
            case REJECTED -> unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_ACCESS_REJECTED);
            case FAILED -> unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE);
        };
    }

    private FraudSignalEvaluation evaluateScoped(ScoringContext context) {
        try {
            GraphRiskEvidenceRead read = access.read(context);
            if (read == null) {
                return unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED);
            }
            if (read.status() == GraphRiskEvidenceRead.Status.VERIFIED_POSITIVE) {
                if (!read.isBoundTo(context) || read.binding() == null) {
                    return unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_AUTHORITY_DENIED);
                }
                // A shaped positive description is not authority; require a fresh typed owner result.
                GraphRiskEvidenceRead current = access.requireOccurrence(context, read);
                if (current == null) {
                    return unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED);
                }
                if (current.status() == GraphRiskEvidenceRead.Status.VERIFIED_POSITIVE
                        && (!current.isBoundTo(context)
                        || !read.sameBoundWitnessAtNonRegressingReadTime(current))) {
                    return unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_AUTHORITY_DENIED);
                }
                read = current;
            }
            return map(policy.evaluate(read));
        } catch (SecurityException denied) {
            return unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_AUTHORITY_DENIED);
        } catch (RuntimeException unavailable) {
            // Never surface provider messages, identifiers or causes in the scoring result.
            return unavailable(GraphRiskDiagnosticPolicy.ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE);
        }
    }

    private static FraudSignalEvaluation map(GraphRiskDiagnosticPolicy.Diagnostic diagnostic) {
        boolean observed = diagnostic.state() == GraphRiskDiagnosticPolicy.State.OBSERVED;
        String reasonCode = diagnostic.reasonCode().wireValue();
        List<String> reasonCodes = observed
                ? List.of(reasonCode, NOT_CALIBRATED_REASON)
                : List.of(reasonCode);
        FraudEngineEvidence evidence = new FraudEngineEvidence(FraudEngineEvidenceType.GRAPH_SIGNAL,
                reasonCode, observed ? "Earlier scoped device association" : "Graph evidence status",
                diagnostic.explanation(), EVIDENCE_SOURCE,
                observed ? FraudEngineEvidenceStatus.AVAILABLE : FraudEngineEvidenceStatus.UNAVAILABLE);
        return new FraudSignalEvaluation(observed ? FraudEngineStatus.DEGRADED : FraudEngineStatus.UNAVAILABLE,
                null, null, FraudEngineConfidence.UNKNOWN, reasonCodes, List.of(), List.of(evidence),
                null, null, null, observed ? NOT_CALIBRATED_REASON : reasonCode);
    }

    private static FraudSignalEvaluation unavailable(GraphRiskDiagnosticPolicy.ReasonCode reason) {
        String code = reason.wireValue();
        FraudEngineEvidence evidence = new FraudEngineEvidence(FraudEngineEvidenceType.GRAPH_SIGNAL,
                code, "Graph evidence status", reason.explanation(), EVIDENCE_SOURCE,
                FraudEngineEvidenceStatus.UNAVAILABLE);
        return new FraudSignalEvaluation(FraudEngineStatus.UNAVAILABLE, null, null, FraudEngineConfidence.UNKNOWN,
                List.of(code), List.of(), List.of(evidence), null, null, null, code);
    }

    @Override public FraudEngineDescriptor descriptor() { return DESCRIPTOR; }

    @Override public void close() {
        if (executor != null) { executor.close(); }
    }
}
