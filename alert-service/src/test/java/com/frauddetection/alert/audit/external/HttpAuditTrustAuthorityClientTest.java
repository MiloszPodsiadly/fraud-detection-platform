package com.frauddetection.alert.audit.external;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAuditTrustAuthorityClientTest {

    @Test
    void shouldSignRequestsWithPerServiceHmacCredentialsWithoutSharedTokenHeader() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://trust-authority");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuditTrustAuthorityProperties properties = new AuditTrustAuthorityProperties();
        properties.setHmacSecret("alert-hmac-secret");
        properties.setCallerServiceName("alert-service");
        properties.setCallerEnvironment("test");
        HttpAuditTrustAuthorityClient client = new HttpAuditTrustAuthorityClient(builder.build(), properties);
        server.expect(once(), requestTo("http://trust-authority/api/v1/trust/sign"))
                .andExpect(headerDoesNotExist("X-Internal-Trust-Token"))
                .andExpect(header("X-Internal-Service-Name", "alert-service"))
                .andExpect(header("X-Internal-Service-Environment", "test"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("X-Internal-Trust-Signed-At")).isNotBlank())
                .andExpect(request -> assertThat(request.getHeaders().getFirst("X-Internal-Trust-Signature")).isNotBlank())
                .andRespond(withSuccess("""
                        {
                          "signature": "sig",
                          "key_id": "key-1",
                          "algorithm": "Ed25519",
                          "signed_at": "2026-04-29T10:00:00Z",
                          "authority": "local-trust-authority"
                        }
                        """, MediaType.APPLICATION_JSON));

        SignedAuditAnchorPayload result = client.sign(new AuditAnchorSigningPayload(
                "source_service:alert-service",
                "anchor-1",
                1L,
                "last-hash",
                "external-key",
                "external-hash",
                ExternalImmutabilityLevel.CONFIGURED
        ));

        assertThat(result.signatureStatus()).isEqualTo("SIGNED");
        assertThat(result.keyId()).isEqualTo("key-1");
        server.verify();
    }
}
