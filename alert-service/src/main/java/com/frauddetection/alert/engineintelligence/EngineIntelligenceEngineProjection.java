package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.MlModelIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record EngineIntelligenceEngineProjection(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus status,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        List<String> reasonCodes,
        MlModelIdentity modelIdentity
) {
    public EngineIntelligenceEngineProjection {
        reasonCodes = reasonCodes == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(reasonCodes));
    }

    public EngineIntelligenceEngineProjection(
            String engineId,
            FraudEngineType engineType,
            FraudEngineStatus status,
            RiskLevel riskLevel,
            EngineIntelligenceScoreBucket scoreBucket,
            List<String> reasonCodes
    ) {
        this(engineId, engineType, status, riskLevel, scoreBucket, reasonCodes, null);
    }
}
