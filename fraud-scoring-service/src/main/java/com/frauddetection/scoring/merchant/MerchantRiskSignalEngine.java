package com.frauddetection.scoring.merchant;

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
import java.util.concurrent.TimeoutException;

import static com.frauddetection.scoring.merchant.MerchantRiskSignalReasonCode.*;

/** Internal candidate only: no Spring bean, registration, production access provider or default analysis windows. */
final class MerchantRiskSignalEngine implements FraudSignalEngine, AutoCloseable {
    static final String ENGINE_ID = "merchant.primary";
    private static final FraudEngineDescriptor DESCRIPTOR = new FraudEngineDescriptor(
            ENGINE_ID, FraudEngineType.MERCHANT_RISK, "java", "merchant-v1", false);

    private final MerchantProfileAccess access;
    private final MerchantRiskSignalPolicy policy;
    private final BoundedFraudEngineExecutor executor;
    private final Duration deadline;

    MerchantRiskSignalEngine() {
        access = null;
        policy = null;
        executor = null;
        deadline = null;
    }

    /** Disconnected component seam. Only test sources currently implement independent occurrence/profile authorization. */
    MerchantRiskSignalEngine(MerchantProfileAccess access, Duration recentWindow, Duration baselineWindow, Duration deadline) {
        this.access = Objects.requireNonNull(access, "MERCHANT_PROFILE_ACCESS_REQUIRED");
        this.policy = new MerchantRiskSignalPolicy(recentWindow, baselineWindow, access);
        this.deadline = new FraudEngineExecutionPolicy(ENGINE_ID, deadline, false).deadline();
        this.executor = new BoundedFraudEngineExecutor(1, 1);
    }

    @Override
    public FraudSignalEvaluation evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "MERCHANT_SCORING_CONTEXT_REQUIRED");
        if (access == null) { return unavailable(MERCHANT_NOT_ADMITTED); }
        var result = executor.execute(() -> evaluateScoped(context), deadline);
        return switch (result.status()) {
            case COMPLETED -> result.value();
            case TIMED_OUT -> unavailable(MERCHANT_PROFILE_ACCESS_TIMEOUT);
            case REJECTED -> unavailable(MERCHANT_PROFILE_ACCESS_REJECTED);
            case FAILED -> unavailable(MERCHANT_PROFILE_ACCESS_UNAVAILABLE);
        };
    }

    private FraudSignalEvaluation evaluateScoped(ScoringContext context) {
        try {
            // Pass the opaque context to the authority boundary; never derive trusted scope from its fields here.
            MerchantProfileAccess.ProfileRead read = access.read(context);
            if (read == null) { return unavailable(MERCHANT_NO_PROFILE); }
            access.requireOccurrence(context, read.scope(), read.occurrence());
            return map(policy.evaluate(read.scope(), read.occurrence(), read.profile()));
        } catch (SecurityException exception) {
            return unavailable(MERCHANT_NOT_ADMITTED);
        } catch (TimeoutException exception) {
            return unavailable(MERCHANT_PROFILE_ACCESS_TIMEOUT);
        } catch (IllegalArgumentException exception) {
            return unavailable(MERCHANT_DATA_CONFLICT);
        } catch (Exception exception) {
            return unavailable(MERCHANT_PROFILE_ACCESS_UNAVAILABLE);
        }
    }

    private static FraudSignalEvaluation map(MerchantRiskSignalPolicy.Evaluation evaluation) {
        boolean informative = evaluation.signals().stream().anyMatch(signal -> signal.state() == MerchantRiskSignalPolicy.State.AVAILABLE);
        String statusReason = informative ? MERCHANT_SCORE_NOT_CALIBRATED.wireValue()
                : evaluation.signals().stream().map(MerchantRiskSignalPolicy.SignalOutcome::reasonCode).distinct().count() == 1
                    ? evaluation.signals().getFirst().reasonCode() : MERCHANT_SIGNALS_UNAVAILABLE.wireValue();
        return new FraudSignalEvaluation(informative ? FraudEngineStatus.DEGRADED : FraudEngineStatus.UNAVAILABLE,
                null, null, FraudEngineConfidence.UNKNOWN,
                evaluation.signals().stream().map(MerchantRiskSignalPolicy.SignalOutcome::reasonCode).toList(), List.of(),
                evaluation.signals().stream().map(MerchantRiskSignalEngine::evidence).toList(),
                null, null, null, statusReason);
    }

    private static FraudEngineEvidence evidence(MerchantRiskSignalPolicy.SignalOutcome signal) {
        String title = switch (signal.signal()) {
            case MERCHANT_FRAUD_RATE -> "Merchant fraud rate";
            case MERCHANT_ACTIVITY_SPIKE -> "Merchant activity spike";
            case MERCHANT_CATEGORY_CONTEXT -> "Merchant category context";
            case MERCHANT_POPULATION_CONCENTRATION -> "Merchant population concentration";
            case MERCHANT_FIRST_OBSERVED_IN_WINDOW -> "Merchant first observation in window";
        };
        String description = switch (signal.reason()) {
            case MERCHANT_FIRST_OBSERVED_IN_WINDOW -> "No earlier matching activity exists in the complete prior window for this institution and source.";
            case MERCHANT_PREVIOUSLY_OBSERVED_IN_WINDOW -> "Earlier matching activity exists in the complete prior window for this institution and source.";
            case MERCHANT_FRAUD_RATE_LABELS_UNSUPPORTED -> "Mature authoritative labels and covered population are unavailable.";
            case MERCHANT_SPIKE_METHOD_NOT_VALIDATED -> "A validated spike method and sample policy are unavailable.";
            case MERCHANT_CATEGORY_METHOD_NOT_VALIDATED -> "A validated category methodology is unavailable.";
            case MERCHANT_CONCENTRATION_POPULATION_UNAVAILABLE -> "A verified population denominator is unavailable.";
            default -> "The required authorized and complete prior history is unavailable.";
        };
        return new FraudEngineEvidence(FraudEngineEvidenceType.MERCHANT_SIGNAL, signal.reasonCode(), title, description, "MERCHANT_RISK",
                signal.state() == MerchantRiskSignalPolicy.State.AVAILABLE ? FraudEngineEvidenceStatus.AVAILABLE : FraudEngineEvidenceStatus.UNAVAILABLE);
    }

    private static FraudSignalEvaluation unavailable(MerchantRiskSignalReasonCode reason) {
        return new FraudSignalEvaluation(FraudEngineStatus.UNAVAILABLE, null, null, FraudEngineConfidence.UNKNOWN,
                List.of(reason.wireValue()), List.of(), List.of(new FraudEngineEvidence(FraudEngineEvidenceType.MERCHANT_SIGNAL,
                reason.wireValue(), "Merchant evidence admission", "Authorized merchant history is unavailable.", "MERCHANT_RISK",
                FraudEngineEvidenceStatus.UNAVAILABLE)), null, null, null, reason.wireValue());
    }

    @Override public FraudEngineDescriptor descriptor() { return DESCRIPTOR; }
    @Override public void close() { if (executor != null) { executor.close(); } }
}
