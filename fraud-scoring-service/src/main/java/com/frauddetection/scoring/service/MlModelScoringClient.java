package com.frauddetection.scoring.service;

import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;

public interface MlModelScoringClient {

    MlModelOutput score(MlModelInput input);
}
