package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;

import java.util.Objects;

public final class VelocitySignalEngine implements FraudSignalEngine {
    private static final String ENGINE_LANGUAGE = "java";

    private final VelocityFeatureReader featureReader;
    private final VelocityInputValidator inputValidator;
    private final VelocityResultFactory resultFactory;

    public VelocitySignalEngine(FeatureSnapshotReaderFactory readerFactory) {
        this(
                new VelocityFeatureReader(readerFactory),
                new VelocityInputValidator(),
                new VelocityResultFactory()
        );
    }

    VelocitySignalEngine(
            VelocityFeatureReader featureReader,
            VelocityInputValidator inputValidator,
            VelocityResultFactory resultFactory
    ) {
        this.featureReader = Objects.requireNonNull(featureReader, "featureReader is required");
        this.inputValidator = Objects.requireNonNull(inputValidator, "inputValidator is required");
        this.resultFactory = Objects.requireNonNull(resultFactory, "resultFactory is required");
    }

    @Override
    public FraudSignalEvaluation evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        VelocityInputValidation validation = inputValidator.validate(featureReader.read(context));
        if (validation.readiness() != VelocityInputReadiness.READY) {
            return resultFactory.operationalResult(validation);
        }
        ValidatedVelocityInputs inputs = validation.validated();
        VelocitySignalPolicy.VelocityDecision decision = VelocitySignalPolicy.decide(
                new VelocitySignalPolicy.VelocityFacts(
                        inputs.recentTransactionCount(),
                        inputs.recentAmountSumPln(),
                        inputs.transactionVelocityPerMinute()
                )
        );
        return resultFactory.availableResult(decision);
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                FraudEngineType.VELOCITY,
                ENGINE_LANGUAGE,
                VelocitySignalPolicy.ENGINE_VERSION,
                false
        );
    }
}
