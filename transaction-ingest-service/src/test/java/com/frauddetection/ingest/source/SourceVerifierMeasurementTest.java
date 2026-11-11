package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceSignedInput;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceVerifierMeasurementTest {
    @ParameterizedTest
    @ValueSource(ints = {64, SourceSignedInput.MAX_PAYLOAD_BYTES})
    void measuresRealVerificationAtSmallAndMaximumPayloadSize(int bytes) throws Exception {
        byte[] payload = new byte[bytes]; Arrays.fill(payload, (byte) 'a');
        byte[] proof = TestSourceIssuer.proof(payload, Map.of(), 0);
        var verifier = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(TestSourceIssuer.policy(0, false))),
                Clock.fixed(TestSourceIssuer.NOW, ZoneOffset.UTC));
        var context = new SourceSignatureVerifier.Context(TestSourceIssuer.PURPOSE, TestSourceIssuer.AUDIENCE);
        for (int index = 0; index < 25; index++) { assertThat(verifier.verify(proof, payload, context)).isInstanceOf(SourceSignatureVerifier.Verified.class); }
        long[] samples = new long[250];
        for (int index = 0; index < samples.length; index++) {
            long start = System.nanoTime(); var result = verifier.verify(proof, payload, context); samples[index] = System.nanoTime() - start;
            assertThat(result).isInstanceOf(SourceSignatureVerifier.Verified.class);
        }
        Arrays.sort(samples);
        var measured = new LinkedHashMap<String, Object>();
        measured.put("os", System.getProperty("os.name")); measured.put("arch", System.getProperty("os.arch"));
        measured.put("java", System.getProperty("java.version")); measured.put("processors", Runtime.getRuntime().availableProcessors());
        measured.put("maxHeapBytes", Runtime.getRuntime().maxMemory()); measured.put("warmup", 25); measured.put("samples", samples.length);
        measured.put("payloadBytes", bytes); measured.put("proofBytes", proof.length);
        measured.put("p50Micros", samples[124] / 1_000d); measured.put("p95Micros", samples[237] / 1_000d);
        System.out.println("SOURCE_VERIFIER_MEASUREMENTS " + JsonMapper.builder().build().writeValueAsString(measured));
        assertThat(verifier.verify(proof, new byte[SourceSignedInput.MAX_PAYLOAD_BYTES + 1], context))
                .isEqualTo(SourceSignatureVerifier.Denied.PAYLOAD_TOO_LARGE);
    }
}
