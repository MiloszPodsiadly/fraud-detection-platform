package com.frauddetection.alert.governance.promotionreviewreadiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionReviewReadinessReadApiArchitectureGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/com/frauddetection/alert");
    private static final Path PACKAGE_ROOT = PRODUCTION_ROOT.resolve("governance/promotionreviewreadiness");
    private static final Path DOC = ROOT.resolve("docs/architecture/promotion_review_readiness_read_api.md");
    private static final Path OPENAPI = ROOT.resolve("docs/openapi/alert_service.openapi.yaml");

    @Test
    void serializedResponseDoesNotExposeRawDataAndCarriesNonDecisioningFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload = mapper.writeValueAsString(PromotionReviewReadinessReportResponse.from(
                PromotionReviewReadinessReportTestFixtures.validReport()
        ));
        JsonNode json = mapper.readTree(payload);
        List<String> topLevelFields = new ArrayList<>();
        json.fieldNames().forEachRemaining(topLevelFields::add);

        assertThat(topLevelFields).containsExactly(
                "reportType",
                "reportVersion",
                "generatedAt",
                "governanceStatus",
                "readinessStatus",
                "diagnosticOnly",
                "notPromotionApproval",
                "notThresholdRecommendation",
                "notProductionDecisioning",
                "notPaymentAuthorization",
                "notAutomaticDecisioning",
                "notAnalystRecommendation",
                "inputs",
                "checks",
                "reasonCodes",
                "warnings",
                "limitations",
                "banner"
        );

        String compact = compact(payload)
                .replace(compact(PromotionReviewReadinessReportContract.REQUIRED_BANNER), "")
                .replace("notpromotionapproval", "")
                .replace("notthresholdrecommendation", "")
                .replace("notproductiondecisioning", "")
                .replace("notpaymentauthorization", "")
                .replace("notautomaticdecisioning", "")
                .replace("notanalystrecommendation", "");

        assertThat(compact).doesNotContain(
                "rawmodelcard",
                "rawevaluationreport",
                "rawdataset",
                "evaluationrecordid",
                "transactionreference",
                "customerid",
                "accountid",
                "cardid",
                "deviceid",
                "merchantid",
                "analystid",
                "rawpayload",
                "rawfeaturevector",
                "rawmlrequest",
                "rawmlresponse",
                "groundtruth",
                "traininglabel",
                "finaldecision",
                "promotionapproved",
                "approvedforpromotion",
                "promoted",
                "readyforproduction",
                "deployable",
                "recommendedthreshold",
                "thresholdrecommendation",
                "paymentauthorized",
                "autoapprove",
                "autodecline",
                "blocktransaction",
                "analystrecommendation",
                "secret",
                "stacktrace",
                "exceptionmessage"
        );
    }

    @Test
    void readAccessClassifierRecognizesPromotionReviewReadiness() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/v1/governance/promotion-review-readiness/current"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/governance/promotion-review-readiness/current"
        );

        var target = new ReadAccessAuditClassifier().classify(request).orElseThrow();

        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.PROMOTION_REVIEW_READINESS);
        assertThat(target.resourceType()).isEqualTo(ReadAccessResourceType.PROMOTION_REVIEW_READINESS);
        assertThat(target.resourceId()).isNull();
    }

    @Test
    void providerReadsOnlyConfiguredCurrentReportArtifact() throws Exception {
        String source = Files.readString(PACKAGE_ROOT.resolve("ArtifactBackedPromotionReviewReadinessReportProvider.java"));

        assertThat(source).contains(
                "PromotionReviewReadinessReportCurrentProperties",
                "LinkOption.NOFOLLOW_LINKS",
                "startsWith(baseDir)",
                "objectMapper.readValue",
                "validator.validate(report)",
                "Files.isSymbolicLink"
        );
        assertThat(source).doesNotContain(
                "DirectoryStream",
                "Files.list",
                "Files.walk",
                "glob",
                "KafkaTemplate",
                "MongoTemplate",
                "RestTemplate",
                "RestClient",
                "WebClient",
                "ModelRegistry",
                "saveModelArtifact",
                "paymentAuthorization"
        );
    }

    @Test
    void productionSourceDoesNotGenerateMutateOrTriggerOperationalWork() throws Exception {
        String source = promotionReadinessSource();

        assertThat(source).doesNotContain(
                "generate_promotion_review_readiness_report",
                "generate_promotion_readiness_report",
                "build_promotion_review_readiness_report",
                "python",
                "ProcessBuilder",
                "Runtime.exec",
                "make promotion-readiness-report",
                "Files.write",
                "KafkaTemplate",
                "KafkaProducer",
                "@Scheduled",
                "cron",
                "APScheduler",
                "MongoTemplate",
                "MongoClient",
                "ModelRegistry",
                "saveModelArtifact",
                "paymentAuthorization",
                "approveTransaction",
                "declineTransaction",
                "blockTransaction",
                "thresholdRecommendation",
                "recommendedThreshold"
        );
    }

    @Test
    void controllerExposesOnlyGetCurrentEndpoint() throws Exception {
        String controller = Files.readString(PACKAGE_ROOT.resolve("PromotionReviewReadinessReportController.java"));

        assertThat(controller).contains(
                "@RequestMapping(\"/api/v1/governance/promotion-review-readiness\")",
                "@GetMapping(\"/current\")",
                "@AuditedSensitiveRead"
        );
        assertThat(controller).doesNotContain(
                "@PostMapping",
                "@PutMapping",
                "@PatchMapping",
                "@DeleteMapping"
        );
    }

    @Test
    void openApiDocumentsReadOnlyEndpointAndNoWriteOperations() throws Exception {
        String openApi = Files.readString(OPENAPI);

        assertThat(openApi).contains(
                "/api/v1/governance/promotion-review-readiness/current",
                "promotion-readiness:read",
                "PromotionReviewReadinessReportResponse",
                "notAnalystRecommendation",
                "not threshold recommendation",
                "not production decisioning approval",
                "not payment authorization",
                "not automatic approve/decline/block logic",
                "not analyst recommendation logic"
        );
        String pathBlock = openApi.substring(openApi.indexOf("/api/v1/governance/promotion-review-readiness/current"));
        pathBlock = pathBlock.substring(0, pathBlock.indexOf("components:"));
        assertThat(pathBlock).contains("get:");
        assertThat(pathBlock).doesNotContain("post:", "put:", "patch:", "delete:");
    }

    @Test
    void docsStateReadOnlyAuthorizedDiagnosticBoundary() throws Exception {
        String doc = Files.readString(DOC);

        assertThat(doc).contains(
                "FDP-112 exposes the diagnostic Promotion Review Readiness Report as an authorized read-only API. It does not generate reports, approve promotion, recommend thresholds, mutate state, change scoring, authorize payments, trigger workflow, run schedulers, or emit Kafka events.",
                "`GET /api/v1/governance/promotion-review-readiness/current`",
                "`promotion-readiness:read`",
                "Configured-but-broken sources return `503`, not `404`",
                "The Java validator validates the public report contract only.",
                "It does not recompute readiness",
                "not analyst recommendation logic"
        );
    }

    private String promotionReadinessSource() throws Exception {
        return Files.walk(PACKAGE_ROOT)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::readUnchecked)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read " + path, exception);
        }
    }

    private String compact(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
