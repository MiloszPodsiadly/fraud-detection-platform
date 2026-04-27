package com.frauddetection.scoring.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import javax.net.ssl.SSLHandshakeException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;

final class InternalMtlsClientHandshakeMetrics {

    private InternalMtlsClientHandshakeMetrics() {
    }

    static void recordIfMtlsFailure(Throwable throwable, MeterRegistry meterRegistry) {
        Counter.builder("fraud_internal_mtls_handshake_failures_total")
                .tag("reason", reason(throwable))
                .register(meterRegistry)
                .increment();
    }

    static String reason(Throwable throwable) {
        if (hasCause(throwable, CertificateExpiredException.class)) {
            return "EXPIRED_CERT";
        }
        if (isHostnameMismatch(throwable)) {
            return "HOSTNAME_MISMATCH";
        }
        if (hasCause(throwable, CertificateException.class) || hasCause(throwable, SSLHandshakeException.class)) {
            return "UNTRUSTED_CA";
        }
        return "UNTRUSTED_CA";
    }

    private static boolean isHostnameMismatch(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof SSLHandshakeException
                    && message != null
                    && (message.contains("No subject alternative") || message.contains("No name matching"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
