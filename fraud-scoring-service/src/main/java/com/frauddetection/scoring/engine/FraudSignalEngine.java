package com.frauddetection.scoring.engine;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.scoring.context.ScoringContext;

public interface FraudSignalEngine {

    FraudEngineResult evaluate(ScoringContext context);

    FraudEngineDescriptor descriptor();
}
