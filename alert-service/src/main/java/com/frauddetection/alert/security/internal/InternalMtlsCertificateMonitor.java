package com.frauddetection.alert.security.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component("mtlsCert")
public class InternalMtlsCertificateMonitor implements InitializingBean, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(InternalMtlsCertificateMonitor.class);
    private static final Duration EXPIRES_SOON = Duration.ofDays(7);
    private static final Duration EXPIRES_ESCALATED = Duration.ofDays(3);
    private static final Duration EXPIRES_IMMINENTLY = Duration.ofDays(1);
    private static final Duration ROTATION_AGE_WARNING = Duration.ofDays(90);

    private final InternalServiceClientProperties properties;
    private final AtomicLong expirySeconds = new AtomicLong(0);
    private final AtomicLong ageSeconds = new AtomicLong(0);
    private final MeterRegistry meterRegistry;
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
        this.meterRegistry = meterRegistry;
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
        validateClientCertificateTrustedByConfiguredCa(properties.mtls());
        CertificateWindow window = certificateSupplier.get();
        LifecycleState state = refreshAndEvaluate(window);
        logLifecycleState(state);
        if ("DOWN".equals(state.status())) {
            throw new IllegalStateException("Internal mTLS client certificate is expired.");
        }
    }

    @Override
    public Health health() {
        if (!"MTLS_SERVICE_IDENTITY".equals(properties.normalizedMode())) {
            return Health.up().build();
        }
        try {
            CertificateWindow window = certificateSupplier.get();
            refreshAndEvaluate(window);
        } catch (RuntimeException exception) {
            lastHealth = Health.down().withDetail("reason", "CERTIFICATE_UNAVAILABLE").build();
            recordState("DOWN");
        }
        return lastHealth;
    }

    @Scheduled(fixedDelay = 21_600_000L)
    public void monitorLifecycle() {
        if (!"MTLS_SERVICE_IDENTITY".equals(properties.normalizedMode())) {
            return;
        }
        try {
            CertificateWindow window = certificateSupplier.get();
            LifecycleState state = refreshAndEvaluate(window);
            logLifecycleState(state);
        } catch (RuntimeException exception) {
            lastHealth = Health.down().withDetail("reason", "CERTIFICATE_UNAVAILABLE").build();
            recordState("DOWN");
            log.error("Internal mTLS client certificate unavailable for source_service={} target_service={} days_remaining=0",
                    boundedService(properties.normalizedServiceName()),
                    boundedService(properties.mtls().expectedServerIdentity()));
        }
    }

    private LifecycleState refreshAndEvaluate(CertificateWindow window) {
        Instant now = clock.instant();
        expirySeconds.set(Math.max(window.expiresIn(now).toSeconds(), 0));
        ageSeconds.set(Math.max(window.age(now).toSeconds(), 0));
        Duration expiresIn = window.expiresIn(now);
        long daysRemaining = daysRemaining(expiresIn);
        LifecycleState state;
        if (expiresIn.isNegative() || expiresIn.isZero()) {
            lastHealth = Health.down().withDetail("reason", "CERTIFICATE_EXPIRED").build();
            state = new LifecycleState("DOWN", "CERTIFICATE_EXPIRED", daysRemaining);
        } else if (expiresIn.compareTo(EXPIRES_IMMINENTLY) < 0) {
            lastHealth = Health.status("CRITICAL").withDetail("reason", "CERTIFICATE_EXPIRES_IMMINENTLY").build();
            state = new LifecycleState("CRITICAL", "CERTIFICATE_EXPIRES_IMMINENTLY", daysRemaining);
        } else if (expiresIn.compareTo(EXPIRES_ESCALATED) < 0) {
            lastHealth = Health.status("WARN").withDetail("reason", "CERTIFICATE_EXPIRES_WITHIN_3_DAYS").build();
            state = new LifecycleState("WARN", "CERTIFICATE_EXPIRES_WITHIN_3_DAYS", daysRemaining);
        } else if (expiresIn.compareTo(EXPIRES_SOON) < 0) {
            lastHealth = Health.status("WARN").withDetail("reason", "CERTIFICATE_EXPIRES_SOON").build();
            state = new LifecycleState("WARN", "CERTIFICATE_EXPIRES_SOON", daysRemaining);
        } else {
            lastHealth = Health.up().build();
            state = new LifecycleState("UP", "CERTIFICATE_VALID", daysRemaining);
        }
        if (window.age(now).compareTo(ROTATION_AGE_WARNING) > 0) {
            log.warn("Internal mTLS client certificate rotation age is high for source_service={} target_service={} days_remaining={}",
                    boundedService(properties.normalizedServiceName()),
                    boundedService(properties.mtls().expectedServerIdentity()),
                    daysRemaining);
        }
        recordState(state.status());
        return state;
    }

    private void logLifecycleState(LifecycleState state) {
        if ("DOWN".equals(state.status()) || "CRITICAL".equals(state.status())) {
            log.error("Internal mTLS client certificate lifecycle state={} reason={} source_service={} target_service={} days_remaining={}",
                    state.status(),
                    state.reason(),
                    boundedService(properties.normalizedServiceName()),
                    boundedService(properties.mtls().expectedServerIdentity()),
                    state.daysRemaining());
        } else if ("WARN".equals(state.status())) {
            log.warn("Internal mTLS client certificate lifecycle state={} reason={} source_service={} target_service={} days_remaining={}",
                    state.status(),
                    state.reason(),
                    boundedService(properties.normalizedServiceName()),
                    boundedService(properties.mtls().expectedServerIdentity()),
                    state.daysRemaining());
        }
    }

    private void recordState(String state) {
        meterRegistry.counter("fraud_internal_mtls_cert_expiry_state_total", "state", state).increment();
    }

    private static long daysRemaining(Duration expiresIn) {
        if (expiresIn.isNegative() || expiresIn.isZero()) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(expiresIn.toSeconds() / 86_400.0));
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

    private static void validateClientCertificateTrustedByConfiguredCa(InternalServiceClientProperties.Mtls mtls) {
        X509Certificate certificate = singleCertificate(mtls.clientCertificatePath());
        List<X509Certificate> caCertificates = certificates(mtls.caCertificatePaths());
        for (X509Certificate caCertificate : caCertificates) {
            try {
                certificate.verify(caCertificate.getPublicKey());
                return;
            } catch (GeneralSecurityException ignored) {
                // Try the next configured trust anchor.
            }
        }
        throw new IllegalStateException("Internal mTLS client certificate is not trusted by configured CA material.");
    }

    private static X509Certificate singleCertificate(String certificatePath) {
        List<X509Certificate> certificates = certificates(certificatePath);
        if (certificates.isEmpty()) {
            throw new IllegalStateException("Internal mTLS client certificate is unavailable or invalid.");
        }
        return certificates.getFirst();
    }

    private static List<X509Certificate> certificates(String certificatePaths) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certificates = new ArrayList<>();
            for (String path : certificatePaths.split("[,;]")) {
                String trimmedPath = path.trim();
                if (trimmedPath.isBlank()) {
                    continue;
                }
                try (InputStream inputStream = Files.newInputStream(Path.of(trimmedPath))) {
                    certificateFactory.generateCertificates(inputStream)
                            .forEach(certificate -> certificates.add((X509Certificate) certificate));
                }
            }
            if (certificates.isEmpty()) {
                throw new IllegalStateException("Internal mTLS CA trust material is required.");
            }
            return certificates;
        } catch (Exception exception) {
            throw new IllegalStateException("Internal mTLS certificate trust material is unavailable or invalid.");
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

    record LifecycleState(String status, String reason, long daysRemaining) {
    }
}
