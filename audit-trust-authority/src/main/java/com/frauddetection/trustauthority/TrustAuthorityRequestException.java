package com.frauddetection.trustauthority;

import org.springframework.http.HttpStatus;

public class TrustAuthorityRequestException extends RuntimeException {

    private final HttpStatus status;

    TrustAuthorityRequestException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
