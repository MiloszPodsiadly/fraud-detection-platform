from pathlib import Path
import re
import unittest

import yaml

from offline_evaluation.promotion_review_readiness_schema import BANNER as PROMOTION_READINESS_BANNER
from offline_evaluation.shadow_performance_schema import BANNER as SHADOW_PERFORMANCE_BANNER


ROOT = Path(__file__).resolve().parents[3]
OPENAPI = ROOT / "docs" / "openapi" / "alert_service.openapi.yaml"
TIMESTAMP_FIXTURE = ROOT / "contract-fixtures" / "governance" / "canonical-utc-timestamp-cases.json"
PROMOTION_READINESS_UI_VALIDATOR = (
    ROOT / "analyst-console-ui" / "src" / "governance" / "promotionReviewReadinessReportValidation.js"
)
SHADOW_GOLDEN_FIXTURE = ROOT / "deployment" / "local-fixtures" / "shadow-performance" / "current-summary.json"


class OpenApiContractTest(unittest.TestCase):
    def test_allLocalRefsResolve(self):
        document = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
        refs = []

        def walk(value, path):
            if isinstance(value, dict):
                for key, nested in value.items():
                    next_path = path + [str(key)]
                    if key == "$ref" and isinstance(nested, str) and nested.startswith("#/"):
                        refs.append((next_path, nested))
                    walk(nested, next_path)
            elif isinstance(value, list):
                for index, nested in enumerate(value):
                    walk(nested, path + [str(index)])

        def exists(ref):
            current = document
            for raw_part in ref[2:].split("/"):
                part = raw_part.replace("~1", "/").replace("~0", "~")
                if isinstance(current, dict) and part in current:
                    current = current[part]
                elif isinstance(current, list) and part.isdigit() and int(part) < len(current):
                    current = current[int(part)]
                else:
                    return False
            return True

        walk(document, [])
        unresolved = [f"{'/'.join(path)} -> {ref}" for path, ref in refs if not exists(ref)]

        self.assertEqual([], unresolved)

    def test_bannerFieldsUseExactSingleValueEnums(self):
        document = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
        schemas = document["components"]["schemas"]

        self.assertEqual(
            [SHADOW_PERFORMANCE_BANNER],
            schemas["ShadowPerformanceSummaryResponse"]["properties"]["banner"]["enum"],
        )
        self.assertEqual(
            [PROMOTION_READINESS_BANNER],
            schemas["PromotionReviewReadinessReportResponse"]["properties"]["banner"]["enum"],
        )

    def test_promotionReadinessUiBannerConstantMatchesOpenApiEnum(self):
        document = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
        openapi_banner = document["components"]["schemas"]["PromotionReviewReadinessReportResponse"]["properties"]["banner"]["enum"][0]
        ui_source = PROMOTION_READINESS_UI_VALIDATOR.read_text(encoding="utf-8")
        match = re.search(r'REQUIRED_PROMOTION_REVIEW_READINESS_BANNER\s*=\s*\n\s*"([^"]+)";', ui_source)

        self.assertIsNotNone(match)
        self.assertEqual(openapi_banner, match.group(1))

    def test_governanceTimestampFieldsUseBoundedFractionalPrecisionPattern(self):
        document = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
        schemas = document["components"]["schemas"]
        expected_pattern = (
            r"^(?:(?:(?!0000)(?:(?:[02468][048]|[13579][26])00|[0-9]{2}(?:0[48]|[2468][048]|[13579][26])))-02-29|"
            r"(?:[0-9]{3}[1-9]|[0-9]{2}[1-9][0-9]|[0-9][1-9][0-9]{2}|[1-9][0-9]{3})-"
            r"(?:(?:01|03|05|07|08|10|12)-(?:0[1-9]|[12][0-9]|3[01])|"
            r"(?:04|06|09|11)-(?:0[1-9]|[12][0-9]|30)|02-(?:0[1-9]|1[0-9]|2[0-8])))"
            r"T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,9})?Z$"
        )
        fields = [
            schemas["ShadowPerformanceSummaryResponse"]["properties"]["generatedAt"],
            schemas["ShadowPerformanceEvaluationResponse"]["properties"]["evaluationReportGeneratedAt"],
            schemas["ShadowPerformanceEvaluationResponse"]["properties"]["evaluationCardGeneratedAt"],
            schemas["PromotionReviewReadinessReportResponse"]["properties"]["generatedAt"],
            schemas["PromotionReviewReadinessReportResponse"]["properties"]["inputs"]["properties"]["shadowPerformanceSummary"]["properties"]["generatedAt"],
            schemas["PromotionReviewReadinessReportResponse"]["properties"]["checkInputs"]["properties"]["shadowPerformanceSummary"]["properties"]["generatedAt"],
        ]

        for field in fields:
            self.assertEqual("date-time", field["format"])
            self.assertEqual(expected_pattern, field["pattern"])

        timestamp_cases = yaml.safe_load(TIMESTAMP_FIXTURE.read_text(encoding="utf-8"))
        compiled = re.compile(expected_pattern)
        for value in timestamp_cases["valid"]:
            with self.subTest(valid=value):
                self.assertRegex(value, compiled)
        for value in timestamp_cases["invalid"]:
            if isinstance(value, str):
                with self.subTest(invalid=value):
                    self.assertIsNone(compiled.fullmatch(value))

    def test_shadowGoldenFixtureIdentityMatchesOpenApiClosedEnums(self):
        document = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
        schemas = document["components"]["schemas"]
        fixture = yaml.safe_load(SHADOW_GOLDEN_FIXTURE.read_text(encoding="utf-8"))

        self.assertEqual(
            schemas["ShadowPerformanceSummaryResponse"]["properties"]["reportType"]["enum"],
            [fixture["reportType"]],
        )
        self.assertEqual(
            schemas["ShadowPerformanceSummaryResponse"]["properties"]["summaryVersion"]["enum"],
            [fixture["summaryVersion"]],
        )
        self.assertEqual(
            schemas["ShadowPerformanceSummaryResponse"]["properties"]["banner"]["enum"],
            [fixture["banner"]],
        )


if __name__ == "__main__":
    unittest.main()
