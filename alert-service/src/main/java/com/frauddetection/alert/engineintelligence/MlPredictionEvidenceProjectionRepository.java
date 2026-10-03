package com.frauddetection.alert.engineintelligence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MlPredictionEvidenceProjectionRepository
        extends MongoRepository<MlPredictionEvidenceProjection, String> {
}
