package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceProvider;
import com.frauddetection.alert.governance.exactmodelshadowperformance.ExactModelShadowPerformanceSelector;
import com.frauddetection.alert.governance.exactmodelshadowperformance.VerifiedExactModelShadowPerformanceArtifact;
import com.frauddetection.alert.governance.modelcard.ModelCard;
import com.frauddetection.alert.governance.modelcard.ModelCardProvider;
import com.frauddetection.alert.governance.modelcard.VerifiedModelCardArtifact;
import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ModelPromotionHttpIntegrationTest.IntegrationConfiguration.class)
@ActiveProfiles("test")
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class ModelPromotionHttpIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE_PATH = "/api/v1/governance/model-promotion";
    private static final ExactModelArtifactIdentity IDENTITY = new ExactModelArtifactIdentity(
            "python-logistic-fraud-model",
            "2026-10-15.shadow",
            "feature-contract-v2",
            "a".repeat(64)
    );
    private static final String FROM = "2026-06-01T00:00:00Z";
    private static final String TO = "2026-06-09T23:59:59Z";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ModelPromotionPolicyValidator policyValidator;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("spring.mongodb.uri", () -> FraudPlatformContainers.mongodb().getReplicaSetUrl("model_promotion_http_test"));
        registry.add("app.kafka.topics.transaction-scored", () -> "transactions.scored.model-promotion-http.test");
        registry.add("app.kafka.topics.fraud-alerts", () -> "fraud.alerts.model-promotion-http.test");
        registry.add("app.kafka.topics.fraud-decisions", () -> "fraud.decisions.model-promotion-http.test");
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.model-promotion-http.test");
        registry.add("app.model-promotion.persistence.enabled", () -> "true");
        registry.add("app.security.demo-auth.enabled", () -> "true");
    }

    @BeforeEach
    void setUp() {
        clear(ModelPromotionStateDocument.class);
        clear(ModelPromotionDecisionDocument.class);
        clear(ModelPromotionProposalDocument.class);
        clear(ModelPromotionChampionClaimDocument.class);
        clear(ModelPromotionPolicyDocument.class);
        mongoTemplate.insert(ModelPromotionPolicyDocument.from(institutionalTestPolicy()));
    }

    @Test
    void commitsAnIndependentlyApprovedEvidenceBoundTransitionThroughTheFullHttpSecurityPath() {
        assertThat(post("actor-a", "ANALYST", "/candidates", candidate("register-http-1", ModelGovernanceEnvironment.TEST))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonResponse challengerProposal = post(
                "actor-a", "ANALYST", "/proposals", proposal(
                        ModelPromotionLifecycleState.CANDIDATE, 0, ModelPromotionLifecycleState.CHALLENGER,
                        "proposal-challenger-http-1", "proposal-challenger-http-1", "Initial independent review request",
                        ModelPromotionDecisionRationale.sha256("Initial independent review request")
                )
        );
        assertThat(challengerProposal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) challengerProposal.getBody().get("proposalRationale")).get("content")).isNull();
        assertThat(post(
                "actor-b", "REVIEWER", "/proposals/proposal-challenger-http-1/reviews", review(
                        "approval-challenger-http-1", "review-challenger-http-1", "Independent challenger review"
                )
        ).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonResponse shadowProposal = post(
                "actor-a", "ANALYST", "/proposals", proposal(
                        ModelPromotionLifecycleState.CHALLENGER, 1, ModelPromotionLifecycleState.SHADOW_APPROVED,
                        "proposal-shadow-http-1", "proposal-shadow-http-1", "Verified exact evidence supports shadow use",
                        ModelPromotionDecisionRationale.sha256("Verified exact evidence supports shadow use")
                )
        );
        assertThat(shadowProposal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(shadowProposal.getBody()).containsEntry("reviewStatus", "PENDING");
        assertThat(post(
                "actor-b", "REVIEWER", "/proposals/proposal-shadow-http-1/reviews", review(
                        "approval-shadow-http-1", "review-shadow-http-1", "Independent evidence and policy review"
                )
        ).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonResponse state = get("actor-b", "REVIEWER", statePath(ModelGovernanceEnvironment.TEST));

        assertThat(state.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state.getBody()).containsEntry("lifecycleState", "SHADOW_APPROVED")
                .containsEntry("currentRevision", 2);
        assertThat(state.getBody()).containsKey("structurallyAllowedTargetStates")
                .doesNotContainKey("allowedTargetStates");
        assertThat((List<String>) state.getBody().get("structurallyAllowedTargetStates"))
                .contains("PROMOTION_RECOMMENDED");
        assertThat((List<?>) state.getBody().get("decisionHistory")).hasSize(3);
        assertThat((List<?>) state.getBody().get("proposals")).hasSize(2);
        assertThat(((List<Map<?, ?>>) state.getBody().get("proposals")).stream()
                .map(proposal -> (Map<?, ?>) proposal.get("proposalRationale"))
                .map(rationale -> String.valueOf(rationale.get("content"))))
                .contains("Verified exact evidence supports shadow use");

        JsonResponse ordinaryRead = get("read-only-actor", "READ_ONLY_ANALYST", statePath(ModelGovernanceEnvironment.TEST));
        assertThat(ordinaryRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<Map<?, ?>>) ordinaryRead.getBody().get("proposals"))).allSatisfy(proposal -> {
            assertThat(((Map<?, ?>) proposal.get("proposalRationale")).get("content")).isNull();
            if (proposal.get("approvalRationale") != null) {
                assertThat(((Map<?, ?>) proposal.get("approvalRationale")).get("content")).isNull();
            }
        });
        assertThat(((List<Map<?, ?>>) ordinaryRead.getBody().get("decisionHistory"))).allSatisfy(decision -> {
            if (decision.get("proposalRationale") != null) {
                assertThat(((Map<?, ?>) decision.get("proposalRationale")).get("content")).isNull();
            }
            if (decision.get("approvalRationale") != null) {
                assertThat(((Map<?, ?>) decision.get("approvalRationale")).get("content")).isNull();
            }
        });
        assertThat(get("read-only-actor", "READ_ONLY_ANALYST", statePath(ModelGovernanceEnvironment.PRODUCTION))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ModelPromotionStateDocument persistedState = mongoTemplate.findById(
                ModelPromotionPersistenceKey.aggregate(
                        IDENTITY,
                        new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, ModelGovernanceEnvironment.TEST),
                        ModelPromotionIntendedUse.FRAUD_RISK_SCORING
                ),
                ModelPromotionStateDocument.class
        );
        ModelPromotionProposalDocument persistedProposal = mongoTemplate.findById(
                "proposal-shadow-http-1", ModelPromotionProposalDocument.class
        );

        assertThat(persistedState).isNotNull();
        assertThat(persistedState.toAggregate().lifecycleState()).isEqualTo(ModelPromotionLifecycleState.SHADOW_APPROVED);
        assertThat(persistedProposal).isNotNull();
        assertThat(persistedProposal.approval().approvedBy()).isEqualTo("actor-b");
        assertThat(persistedProposal.proposal().rationale().content())
                .isEqualTo("Verified exact evidence supports shadow use");
        assertThat(persistedProposal.assessmentSnapshot().content().formalRecommendationEligible()).isFalse();
        assertThat(persistedProposal.assessmentSnapshot().content().humanReviewable()).isTrue();
    }

    @Test
    void rejectsSelfApprovalBeforeAnyLifecycleStateCanChange() {
        assertThat(post("actor-self", "ANALYST", "/candidates", candidate("register-http-self", ModelGovernanceEnvironment.TEST))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(
                "actor-self", "ANALYST", "/proposals", proposal(
                        ModelPromotionLifecycleState.CANDIDATE, 0, ModelPromotionLifecycleState.CHALLENGER,
                        "proposal-http-self", "proposal-http-self", "Request challenger review",
                        ModelPromotionDecisionRationale.sha256("Request challenger review")
                )
        ).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonResponse response = post(
                "actor-self", "REVIEWER", "/proposals/proposal-http-self/reviews", review(
                        "approval-http-self", "review-http-self", "Attempted self approval"
                )
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mongoTemplate.findById("proposal-http-self", ModelPromotionProposalDocument.class).reviewStatus())
                .isEqualTo(ModelPromotionProposalDocument.ReviewStatus.PENDING);
    }

    @Test
    void rejectsNormalRoleForProductionScopeAndRejectsIncorrectRationaleDigestAfterEvidenceValidation() {
        JsonResponse production = post(
                "actor-production", "ANALYST", "/candidates", candidate("register-http-production", ModelGovernanceEnvironment.PRODUCTION)
        );
        assertThat(production.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(post("actor-a", "ANALYST", "/candidates", candidate("register-http-digest", ModelGovernanceEnvironment.TEST))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(
                "actor-a", "ANALYST", "/proposals", proposal(
                        ModelPromotionLifecycleState.CANDIDATE, 0, ModelPromotionLifecycleState.CHALLENGER,
                        "proposal-digest-challenger", "proposal-digest-challenger", "Initial independent review request",
                        ModelPromotionDecisionRationale.sha256("Initial independent review request")
                )
        ).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(
                "actor-b", "REVIEWER", "/proposals/proposal-digest-challenger/reviews", review(
                        "approval-digest-challenger", "review-digest-challenger", "Independent challenger review"
                )
        ).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonResponse wrongDigest = post(
                "actor-a", "ANALYST", "/proposals", proposal(
                        ModelPromotionLifecycleState.CHALLENGER, 1, ModelPromotionLifecycleState.SHADOW_APPROVED,
                        "proposal-http-wrong-digest", "proposal-http-wrong-digest", "Evidence digest must bind this rationale",
                        "0".repeat(64)
                )
        );

        assertThat(wrongDigest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mongoTemplate.findById("proposal-http-wrong-digest", ModelPromotionProposalDocument.class)).isNull();
    }

    private void clear(Class<?> documentType) {
        mongoTemplate.remove(new Query(), documentType);
    }

    private ModelPromotionPolicy institutionalTestPolicy() {
        ModelGovernanceScope scope = new ModelGovernanceScope(
                ModelGovernanceScope.CURRENT_INSTITUTION, ModelGovernanceEnvironment.TEST
        );
        ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(
                ModelPromotionPolicy.CURRENT_VERSION,
                "http-integration-policy",
                1,
                scope,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                new ModelPromotionPolicy.PolicyProvenance(
                        ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        "test-governance-author",
                        "test-model-risk-committee",
                        "2025-01-01T00:00:00Z",
                        "http-integration-approval"
                ),
                "2025-01-02T00:00:00Z",
                "2030-01-01T00:00:00Z",
                java.util.Arrays.stream(ModelPromotionCriterion.values()).map(this::policyRule).toList()
        );
        String digest = policyValidator.canonicalSha256(content);
        return new ModelPromotionPolicy(
                content,
                digest,
                new ModelPromotionPolicy.PolicyAuthorizationEvidence(
                        "test-policy-authority", "test-policy-key", "test-signature", digest, "2025-01-01T00:00:00Z"
                )
        );
    }

    private ModelPromotionPolicy.CriterionRule policyRule(ModelPromotionCriterion criterion) {
        Map<String, java.math.BigDecimal> thresholds = switch (criterion) {
            case STATISTICALLY_MEANINGFUL_SAMPLE -> Map.of(
                    "minimumEvaluatedRecords", new java.math.BigDecimal("4"),
                    "minimumPositiveLabels", new java.math.BigDecimal("2"),
                    "minimumNegativeLabels", new java.math.BigDecimal("2")
            );
            default -> ModelPromotionAssessmentTestFixtures.rule(
                    criterion, ModelPromotionPolicy.CriterionApplicability.MANDATORY
            ).thresholds();
        };
        return new ModelPromotionPolicy.CriterionRule(
                criterion,
                ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                thresholds,
                365L * 24 * 60 * 60,
                "VERIFIED_REPORTS_AND_MANIFESTS",
                criterion.evidenceSource(),
                criterion.metricSemantics(),
                criterion.populationDefinition(),
                criterion.denominatorDefinition(),
                null
        );
    }

    private JsonResponse post(String actor, String role, String suffix, Map<String, Object> body) {
        return exchange("POST", BASE_PATH + suffix, headers(actor, role), body);
    }

    private JsonResponse get(String actor, String role, String path) {
        return exchange("GET", path, headers(actor, role), null);
    }

    private JsonResponse exchange(String method, String path, HttpHeaders headers, Map<String, Object> body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Map responseBody = response.body().isBlank() ? Map.of() : objectMapper.readValue(response.body(), Map.class);
            return new JsonResponse(HttpStatus.valueOf(response.statusCode()), responseBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("HTTP request was interrupted", exception);
        } catch (Exception exception) {
            throw new AssertionError("HTTP request failed", exception);
        }
    }

    private HttpHeaders headers(String actor, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(DemoAuthHeaders.USER_ID, actor);
        headers.set(DemoAuthHeaders.ROLES, role);
        return headers;
    }

    private Map<String, Object> candidate(String operationId, ModelGovernanceEnvironment environment) {
        return Map.of("selection", selection(environment), "operationId", operationId);
    }

    private Map<String, Object> proposal(
            ModelPromotionLifecycleState sourceState,
            long expectedRevision,
            ModelPromotionLifecycleState targetState,
            String operationId,
            String proposalId,
            String rationale,
            String rationaleSha256
    ) {
        return Map.of(
                "selection", selection(ModelGovernanceEnvironment.TEST),
                "sourceState", sourceState.name(),
                "expectedRevision", expectedRevision,
                "requestedTargetState", targetState.name(),
                "fromInclusive", FROM,
                "toInclusive", TO,
                "operationId", operationId,
                "proposalId", proposalId,
                "rationale", rationale,
                "rationaleSha256", rationaleSha256
        );
    }

    private Map<String, Object> review(String approvalId, String operationId, String rationale) {
        return Map.of(
                "approvalId", approvalId,
                "outcome", ModelPromotionApproval.Outcome.APPROVED.name(),
                "operationId", operationId,
                "rationale", rationale,
                "rationaleSha256", ModelPromotionDecisionRationale.sha256(rationale)
        );
    }

    private Map<String, Object> selection(ModelGovernanceEnvironment environment) {
        return Map.of(
                "modelName", IDENTITY.modelName(),
                "modelVersion", IDENTITY.modelVersion(),
                "featureContractVersion", IDENTITY.featureContractVersion(),
                "modelArtifactSha256", IDENTITY.modelArtifactSha256(),
                "environment", environment.name(),
                "intendedUse", ModelPromotionIntendedUse.FRAUD_RISK_SCORING.name()
        );
    }

    private String statePath(ModelGovernanceEnvironment environment) {
        return BASE_PATH + "/state?modelName=" + IDENTITY.modelName()
                + "&modelVersion=" + IDENTITY.modelVersion()
                + "&featureContractVersion=" + IDENTITY.featureContractVersion()
                + "&modelArtifactSha256=" + IDENTITY.modelArtifactSha256()
                + "&environment=" + environment.name()
                + "&intendedUse=" + ModelPromotionIntendedUse.FRAUD_RISK_SCORING.name();
    }

    private record JsonResponse(HttpStatus statusCode, Map body) {
        private HttpStatus getStatusCode() {
            return statusCode;
        }

        private Map getBody() {
            return body;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationConfiguration {

        @Bean
        @Primary
        ModelPromotionPolicyAuthorityVerifier integrationPolicyAuthorityVerifier() {
            return policy -> ModelPromotionPolicyAuthorityVerification.verified();
        }

        @Bean
        @Primary
        ModelCardProvider integrationModelCardProvider(ObjectMapper objectMapper) {
            JsonNode shadow = fixture(objectMapper);
            VerifiedModelCardArtifact artifact = modelCardArtifact(objectMapper, shadow);
            return new ModelCardProvider() {
                @Override
                public Optional<VerifiedModelCardArtifact> currentArtifact() {
                    return Optional.of(artifact);
                }

                @Override
                public Optional<VerifiedModelCardArtifact> findExact(ExactModelArtifactIdentity identity) {
                    return IDENTITY.equals(identity) ? Optional.of(artifact) : Optional.empty();
                }
            };
        }

        @Bean
        @Primary
        ExactModelShadowPerformanceProvider integrationShadowPerformanceProvider(ObjectMapper objectMapper) {
            VerifiedExactModelShadowPerformanceArtifact artifact = new VerifiedExactModelShadowPerformanceArtifact(
                    fixture(objectMapper), "c".repeat(64)
            );
            return new ExactModelShadowPerformanceProvider() {
                @Override
                public Optional<VerifiedExactModelShadowPerformanceArtifact> findArtifact(
                        ExactModelShadowPerformanceSelector selector
                ) {
                    return IDENTITY.modelName().equals(selector.modelName())
                            && IDENTITY.modelVersion().equals(selector.modelVersion())
                            && IDENTITY.featureContractVersion().equals(selector.featureContractVersion())
                            && IDENTITY.modelArtifactSha256().equals(selector.modelArtifactSha256())
                            && FROM.equals(selector.fromInclusive())
                            && TO.equals(selector.toInclusive())
                            ? Optional.of(artifact)
                            : Optional.empty();
                }
            };
        }

        private static JsonNode fixture(ObjectMapper objectMapper) {
            try (InputStream input = ModelPromotionHttpIntegrationTest.class.getResourceAsStream(
                    "/fixtures/exact-model-shadow-performance/python-generated-v1/exact_model_shadow_performance.json"
            )) {
                if (input == null) {
                    throw new IllegalStateException("exact model shadow performance fixture is missing");
                }
                return objectMapper.readTree(input);
            } catch (Exception exception) {
                throw new IllegalStateException("exact model shadow performance fixture is unreadable", exception);
            }
        }

        private static VerifiedModelCardArtifact modelCardArtifact(ObjectMapper objectMapper, JsonNode shadow) {
            JsonNode sourceEvaluation = shadow.get("sourceEvaluation");
            ObjectNode evaluationEvidence = objectMapper.createObjectNode();
            evaluationEvidence.put("status", "AVAILABLE");
            evaluationEvidence.put("manifestSha256", sourceEvaluation.get("manifestSha256").textValue());
            evaluationEvidence.put("sourceDatasetVerificationLevel",
                    sourceEvaluation.get("sourceDatasetVerificationLevel").textValue());
            evaluationEvidence.put("reportGeneratedAt", sourceEvaluation.get("reportGeneratedAt").textValue());
            ModelCard card = new ModelCard(
                    "ml-model-card-v1",
                    "ML_MODEL_CARD_V1",
                    shadow.get("generatedAt").textValue(),
                    new ModelCard.Subject(
                            "ML_MODEL",
                            IDENTITY.modelName(),
                            IDENTITY.modelVersion(),
                            IDENTITY.featureContractVersion(),
                            IDENTITY.modelArtifactSha256(),
                            "COMPLETE"
                    ),
                    "NOT_EVALUATED_FOR_PROMOTION",
                    new ModelCard.ModelArtifactVerification(
                            "VERIFIED_AGAINST_ARTIFACT_BYTES", IDENTITY.modelArtifactSha256()
                    ),
                    evaluationEvidence,
                    shadow.get("evaluationSummary").deepCopy(),
                    new ModelCard.VerifiedUsageBoundary(
                            List.of("OFFLINE_EVALUATION"),
                            List.of("EXACT_MODEL_OFFLINE_EVALUATION"),
                            List.of("MODEL_PROMOTION_APPROVAL"),
                            List.of("TEST_EVIDENCE_ONLY"),
                            "NONE", "NONE", "NONE", "NONE", "NONE"
                    )
            );
            return new VerifiedModelCardArtifact(card, "b".repeat(64));
        }
    }
}
