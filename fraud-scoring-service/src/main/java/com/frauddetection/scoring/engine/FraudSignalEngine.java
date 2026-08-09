package com.frauddetection.scoring.engine;

import com.frauddetection.scoring.context.ScoringContext;

public interface FraudSignalEngine {

    FraudSignalEvaluation evaluate(ScoringContext context);

    FraudEngineDescriptor descriptor();
}
