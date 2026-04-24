package com.frauddetection.scoring.service;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.observability.ScoringMetrics;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpMlModelScoringClientTest {

    @Test
    void shouldMapSuccessfulModelResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        HttpMlModelScoringClient client = new HttpMlModelScoringClient(builder.build(), new ScoringMetrics(meterRegistry));

        server.expect(requestTo("http://ml-test/v1/fraud/score"))
                .andRespond(withSuccess("""
                        {
                          "available": true,
                          "fraudScore": 0.82,
                          "riskLevel": "HIGH",
                          "modelName": "python-logistic-fraud-model",
                          "modelVersion": "2026-04-21.v1",
                          "inferenceTimestamp": "2026-04-21T10:00:00Z",
                          "reasonCodes": ["countryMismatch"],
                          "scoreDetails": {"logit": 1.52},
                          "explanationMetadata": {"modelAvailable": true},
                          "fallbackReason": null
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.score(input());

        assertThat(result.available()).isTrue();
        assertThat(result.fraudScore()).isEqualTo(0.82d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.modelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(result.reasonCodes()).containsExactly("countryMismatch");
        assertThat(meterRegistry.get("fraud.scoring.ml.client.requests")
                .tags("outcome", "available")
                .counter()
                .count()).isEqualTo(1.0d);
        server.verify();
    }

    @Test
    void shouldReturnUnavailableOutputWhenModelRequestFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        HttpMlModelScoringClient client = new HttpMlModelScoringClient(builder.build(), new ScoringMetrics(meterRegistry));

        server.expect(requestTo("http://ml-test/v1/fraud/score"))
                .andRespond(withServerError());

        var result = client.score(input());

        assertThat(result.available()).isFalse();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.explanationMetadata()).containsEntry("modelAvailable", false);
        assertThat(meterRegistry.get("fraud.scoring.ml.client.requests")
                .tags("outcome", "error")
                .counter()
                .count()).isEqualTo(1.0d);
        server.verify();
    }

    private MlModelInput input() {
        return MlModelInput.from(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));
    }
}
