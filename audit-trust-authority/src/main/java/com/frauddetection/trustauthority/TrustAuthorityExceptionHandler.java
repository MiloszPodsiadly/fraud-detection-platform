package com.frauddetection.trustauthority;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class TrustAuthorityExceptionHandler {

    @ExceptionHandler(TrustAuthorityRequestException.class)
    ResponseEntity<Void> handleRequest(TrustAuthorityRequestException exception) {
        return ResponseEntity.status(exception.status()).build();
    }

    @ExceptionHandler(TrustAuthorityAuditException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void handleAuditFailure() {
    }
}
