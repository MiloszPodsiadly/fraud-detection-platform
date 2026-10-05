package com.frauddetection.alert.suspicious;

public class SuspiciousTransactionProjectionException extends RuntimeException {

    public SuspiciousTransactionProjectionException(Throwable cause) {
        super("SUSPICIOUS_TRANSACTION_PROJECTION_FAILED", cause);
    }
}
