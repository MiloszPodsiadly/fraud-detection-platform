package com.frauddetection.scoring.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

@Component("mtlsCert")
public class InternalMtlsCertificateMonitor implements InitializingBean, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(InternalMtlsCertificateMonitor.class);
    private static final Duration EXPIRES_SOON = Duration.ofDays(7);
    private static final Duration EXPIRES_IMMINENTLY = Duration.ofDays(1);
    private static final Duration ROTATION_AGE_WARNING = Duration.ofDays(90);

    private final InternalServiceClientProperties properties;
    private final AtomicLong expirySeconds = new AtomicLong(0);
    private final AtomicLong ageSeconds = new AtomicLong(0);
    private final Clock clock;
    private final CertificateSupplier certificateSupplier;
    private volatile Health lastHealth = Health.up().build();

    @Autowired
    public InternalMtlsCertificateMonitor(InternalServiceClientProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, Clock.systemUTC());
    }

    InternalMtlsCertificateMonitor(InternalServiceClientProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this(properties, meterRegistry, clock, () -> certificateWindow(properties.mtls().clientCertificatePath()));
    }

    InternalMtlsCertificateMonitor(
            InternalServiceClientProperties properties,
            MeterRegistry meterRegistry,
            Clock clock,
            CertificateSupplier certificateSupplier
    ) {
        this.properties = properties;
        this.clock = clock;
        this.certificateSupplier = certificateSupplier;
        Gauge.builder("fraud_internal_mtls_cert_expiry_seconds", expirySeconds, AtomicLong::get)
                .tag("source_service", boundedService(properties.normalizedServiceName()))
                .tag("target_service", boundedService(properties.mtls().expectedServerIdentity()))
                .register(meterRegistry);
        Gauge.builder("fraud_internal_mtls_cert_age_seconds", ageSeconds, AtomicLong::get)
                .tag("source_service", boundedService(properties.normalizedServiceName()))
                .tag("target_service", boundedService(properties.mtls().expectedServerIdentity()))
                .register(meterRegistry);
    }

    @Override
    public void afterPropertiesSet() {
        if (!"MTLS_SERVICE_IDENTITY".equals(properties.normalizedMode())) {
            return;
        }
        CertificateWindow window = certificateSupplier.get();
        refresh(window);
        if (window.expiresIn(clock.instant()).isNegative() || window.expiresIn(clock.instant()).isZero()) {
            lastHealth = Health.down().withDetail("reason", "CERTIFICATE_EXPIRED").build();
            throw new IllegalStateException("Internal mTLS client certificate is expired.");
        }
        if (window.expiresIn(clock.instant()).compareTo(EXPIRES_IMMINENTLY) < 0) {
            log.error("Internal mTLS client certificate expires imminently for source_service={} target_service={}",
                    boundedService(properties.normalizedServiceName()),
                    boundedService(properties.mtls().expectedServerIdentity()));
            lastHealth = Health.status("WARN").withDetail("reason", "CERTIFICATE_EXPIRES_IMMINENTLY").build();
            return;
        }
        if (window.expiresIn(clock.instant()).compareTo(EXPIRES_SOON) < 0) {
            log.warn("Internal mTLS client certificate expires soon for source_service={} target_service={}",
                    boundedService(properties.normalizedServiceName()),
                    boundedService(properties.mtls().expectedServerIdentity()));
            lastHealth = Health.status("WARN").withDetail("reason", "CERTIFICATE_EXPIRES_SOON").build();
            return;
        }
        if (window.age(clock.instant()).compareTo(ROTATION_AGE_WARNING) > 0) {
            log.warn("Internal mTLS client certificate rotation age is high for source_service={} target_service={}",
                    boundedService(properties.normalizedServiceName()),
                    boundedService(properties.mtls().expectedServerIdentity()));
        }
        lastHealth = Health.up().build();
    }

    @Override
    public Health health() {
        if (!"MTLS_SERVICE_IDENTITY".equals(properties.normalizedMode())) {
            return Health.up().build();
        }
        try {
            CertificateWindow window = certificateSupplier.get();
            refresh(window);
            if (window.expiresIn(clock.instant()).isNegative() || window.expiresIn(clock.instant()).isZero()) {
                lastHealth = Health.down().withDetail("reason", "CERTIFICATE_EXPIRED").build();
            } else if (window.expiresIn(clock.instant()).compareTo(EXPIRES_SOON) < 0) {
                lastHealth = Health.status("WARN").withDetail("reason", "CERTIFICATE_EXPIRES_SOON").build();
            } else {
                lastHealth = Health.up().build();
            }
        } catch (RuntimeException exception) {
            lastHealth = Health.down().withDetail("reason", "CERTIFICATE_UNAVAILABLE").build();
        }
        return lastHealth;
    }

    private void refresh(CertificateWindow window) {
        Instant now = clock.instant();
        expirySeconds.set(Math.max(window.expiresIn(now).toSeconds(), 0));
        ageSeconds.set(Math.max(window.age(now).toSeconds(), 0));
    }

    private static CertificateWindow certificateWindow(String certificatePath) {
        try (InputStream inputStream = Files.newInputStream(Path.of(certificatePath))) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(inputStream);
            return new CertificateWindow(certificate.getNotBefore(), certificate.getNotAfter());
        } catch (Exception exception) {
            throw new IllegalStateException("Internal mTLS client certificate is unavailable or invalid.");
        }
    }

    private static String boundedService(String value) {
        if (value == null || value.isBlank()) {
            return "unconfigured";
        }
        return value.trim();
    }

    record CertificateWindow(Date notBefore, Date notAfter) {
        Duration expiresIn(Instant now) {
            return Duration.between(now, notAfter.toInstant());
        }

        Duration age(Instant now) {
            return Duration.between(notBefore.toInstant(), now);
        }
    }

    interface CertificateSupplier {
        CertificateWindow get();
    }
}
