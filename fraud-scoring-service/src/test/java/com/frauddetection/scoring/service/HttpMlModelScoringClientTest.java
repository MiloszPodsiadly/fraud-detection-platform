package com.frauddetection.scoring.service;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelInput;
import org.junit.jupiter.api.Test;
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
        HttpMlModelScoringClient client = new HttpMlModelScoringClient(builder.build());

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
        server.verify();
    }

    @Test
    void shouldReturnUnavailableOutputWhenModelRequestFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpMlModelScoringClient client = new HttpMlModelScoringClient(builder.build());

        server.expect(requestTo("http://ml-test/v1/fraud/score"))
                .andRespond(withServerError());

        var result = client.score(input());

        assertThat(result.available()).isFalse();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.explanationMetadata()).containsEntry("modelAvailable", false);
        server.verify();
    }

    private MlModelInput input() {
        return MlModelInput.from(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));
    }
}
