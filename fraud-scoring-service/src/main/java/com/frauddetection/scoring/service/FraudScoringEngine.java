package com.frauddetection.scoring.service;

import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;

public interface FraudScoringEngine {

    FraudScoreResult score(FraudScoringRequest request);
}
