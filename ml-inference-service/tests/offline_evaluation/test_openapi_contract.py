from pathlib import Path
import re
import unittest

import yaml

from offline_evaluation.promotion_review_readiness_schema import BANNER as PROMOTION_READINESS_BANNER
from offline_evaluation.shadow_performance_schema import BANNER as SHADOW_PERFORMANCE_BANNER


ROOT = Path(__file__).resolve().parents[3]
OPENAPI = ROOT / "docs" / "openapi" / "alert_service.openapi.yaml"
PROMOTION_READINESS_UI_VALIDATOR = (
    ROOT / "analyst-console-ui" / "src" / "governance" / "promotionReviewReadinessReportValidation.js"
)


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
        expected_pattern = r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$"
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


if __name__ == "__main__":
    unittest.main()
