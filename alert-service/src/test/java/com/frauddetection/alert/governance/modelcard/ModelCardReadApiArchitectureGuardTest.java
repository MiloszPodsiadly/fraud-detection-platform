package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCardReadApiArchitectureGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/com/frauddetection/alert");

    @Test
    void endpointHasDedicatedAuthorityAndSensitiveReadAuditOwnership() throws Exception {
        String controller = Files.readString(PRODUCTION_ROOT.resolve("governance/modelcard/ModelCardController.java"));
        String rules = Files.readString(PRODUCTION_ROOT.resolve("security/config/GovernanceAuthorizationRules.java"));

        assertThat(controller).contains(
                "@AuditedSensitiveRead",
                "/api/v1/governance/model-card",
                "@GetMapping(\"/current\")"
        );
        assertThat(rules).contains("/api/v1/governance/model-card/current", "AnalystAuthority.MODEL_CARD_READ");
        assertThat(AnalystRole.FRAUD_OPS_ADMIN.authorities()).contains(AnalystAuthority.MODEL_CARD_READ);
        assertThat(AnalystRole.READ_ONLY_ANALYST.authorities()).doesNotContain(AnalystAuthority.MODEL_CARD_READ);
        assertThat(AnalystRole.ANALYST.authorities()).doesNotContain(AnalystAuthority.MODEL_CARD_READ);
        assertThat(AnalystRole.REVIEWER.authorities()).doesNotContain(AnalystAuthority.MODEL_CARD_READ);
    }

    @Test
    void classifierOwnsModelCardRead() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/governance/model-card/current");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/governance/model-card/current");

        var target = new ReadAccessAuditClassifier().classify(request).orElseThrow();

        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.MODEL_CARD);
        assertThat(target.resourceType()).isEqualTo(ReadAccessResourceType.MODEL_CARD);
    }

    @Test
    void providerIsExplicitArtifactReadOnlyAndNonDecisioning() throws Exception {
        String provider = Files.readString(PRODUCTION_ROOT.resolve(
                "governance/modelcard/ArtifactBackedModelCardProvider.java"
        ));
        String reader = Files.readString(PRODUCTION_ROOT.resolve("governance/artifact/GovernanceArtifactReader.java"));

        assertThat(provider).contains(
                "ml_model_card.json",
                "ml_model_card.md",
                "manifest.json",
                "GovernanceArtifactReader",
                "validator.validate(card)"
        ).doesNotContain(
                "Files.list", "Files.walk", "DirectoryStream", "KafkaTemplate", "MongoTemplate", "WebClient",
                "promote", "thresholdChange", "trainModel", "registry.save"
        );
        assertThat(reader).contains("LinkOption.NOFOLLOW_LINKS", "changed(before, after)");
    }

    @Test
    void publicContractDocumentsNonDecisioningBoundary() throws Exception {
        String openApi = Files.readString(ROOT.resolve("docs/openapi/alert_service.openapi.yaml"));
        String architecture = Files.readString(ROOT.resolve("docs/architecture/model_card_governance_read_api.md"));
        String securityMap = Files.readString(ROOT.resolve("docs/security/endpoint_authorization_map.md"));

        int modelCardStart = openApi.indexOf("    ModelCardResponse:");
        int modelCardEnd = openApi.indexOf("    GenericObject:", modelCardStart);
        String modelCardSchemas = openApi.substring(modelCardStart, modelCardEnd);
        assertThat(openApi).contains("/api/v1/governance/model-card/current", "ModelCardResponse", "model-card:read");
        assertThat(modelCardSchemas)
                .contains(
                        "ModelCardEvaluationAvailable:",
                        "ModelCardEvaluationUnavailable:",
                        "ModelCardEvaluationSummary:",
                        "ModelCardPredictionMetrics:",
                        "ModelCardDisagreementMetrics:",
                        "ModelCardUsageBoundary:",
                        "intendedUse:",
                        "notIntendedUse:",
                        "useLimitations:"
                )
                .doesNotContain("#/components/schemas/GenericObject", "additionalProperties: true");
        assertThat(architecture).contains("grants no production decision", "scans for a latest artifact");
        assertThat(securityMap).contains("GET /api/v1/governance/model-card/current", "MODEL_CARD_READ");
    }
}
