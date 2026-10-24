package com.frauddetection.alert.governance.modelpromotion;

public class ModelPromotionPersistenceConflictException extends IllegalStateException {

    public ModelPromotionPersistenceConflictException(String message) {
        super(message);
    }

    public ModelPromotionPersistenceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
