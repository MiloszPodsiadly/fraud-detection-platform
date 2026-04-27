package com.frauddetection.scoring.config;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

final class InternalServiceClientRequestFactory {

    private InternalServiceClientRequestFactory() {
    }

    static ClientHttpRequestFactory create(
            URI targetBaseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            InternalServiceClientProperties internalAuth
    ) {
        if (!"MTLS_SERVICE_IDENTITY".equals(internalAuth.normalizedMode())) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            return requestFactory;
        }
        validateExpectedServerIdentity(targetBaseUrl, internalAuth.mtls().expectedServerIdentity());
        MtlsClientHttpRequestFactory requestFactory = new MtlsClientHttpRequestFactory(sslContext(internalAuth.mtls()));
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }

    private static void validateExpectedServerIdentity(URI targetBaseUrl, String expectedServerIdentity) {
        String host = targetBaseUrl == null ? "" : targetBaseUrl.getHost();
        if (host == null || host.isBlank() || !host.equalsIgnoreCase(expectedServerIdentity)) {
            throw new IllegalStateException("Internal mTLS expected server identity does not match target host.");
        }
    }

    private static SSLContext sslContext(InternalServiceClientProperties.Mtls mtls) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            PrivateKey privateKey = loadPrivateKey(Path.of(mtls.clientPrivateKeyPath()));
            Certificate[] certificateChain = loadCertificates(Path.of(mtls.clientCertificatePath())).toArray(Certificate[]::new);
            keyStore.setKeyEntry("internal-client", privateKey, new char[0], certificateChain);

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int caIndex = 0;
            for (String caPath : mtls.caCertificatePaths().split("[,;]")) {
                String trimmedPath = caPath.trim();
                if (trimmedPath.isBlank()) {
                    continue;
                }
                for (Certificate certificate : loadCertificates(Path.of(trimmedPath))) {
                    trustStore.setCertificateEntry("internal-ca-" + caIndex++, certificate);
                }
            }
            if (caIndex == 0) {
                throw new IllegalStateException("Internal mTLS CA trust material is required.");
            }

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, new char[0]);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception exception) {
            throw new IllegalStateException("Internal mTLS client configuration is invalid.");
        }
    }

    private static List<Certificate> loadCertificates(Path path) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        try (var input = Files.newInputStream(path)) {
            Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(input);
            return new ArrayList<>(certificates);
        }
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        String pem = Files.readString(path, StandardCharsets.UTF_8);
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (Exception ignored) {
                // Try the next supported key type.
            }
        }
        throw new IllegalStateException("Unsupported internal mTLS private key format.");
    }

    private static final class MtlsClientHttpRequestFactory extends SimpleClientHttpRequestFactory {
        private final SSLContext sslContext;

        private MtlsClientHttpRequestFactory(SSLContext sslContext) {
            this.sslContext = sslContext;
        }

        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            if (connection instanceof HttpsURLConnection httpsConnection) {
                httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
            }
            super.prepareConnection(connection, httpMethod);
        }
    }
}
