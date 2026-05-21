package com.frauddetection.alert.suspicious.api;

class SuspiciousTransactionLinkedAlertContextNotFoundException extends RuntimeException {

    SuspiciousTransactionLinkedAlertContextNotFoundException() {
        super("Suspicious transaction not found.");
    }
}
