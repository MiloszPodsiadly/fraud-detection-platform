import unittest
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[4] / "offline_evaluation" / "fdp123" / "evaluation_card"
DOC_PATHS = [
    Path(__file__).resolve().parents[5] / "docs" / "architecture" / "evaluation_card_v1.md",
    Path(__file__).resolve().parents[5] / "docs" / "architecture" / "python_ml_evaluation_suite.md",
]
FORBIDDEN_TERMS = {
    "train_model",
    "retrain",
    "promote_model",
    "promotion_workflow",
    "promotionRecommended",
    "promotionReady",
    "write_threshold",
    "approve_transaction",
    "decline_transaction",
    "block_transaction",
    '"paymentAuthorization":',
    "groundTruth",
    "trainingLabel",
    "FastAPI",
    "Flask",
    "scheduler",
    "KafkaProducer",
    "MongoClient",
    "requests.",
    "httpx.",
    "boto3",
    "dashboard component",
    "model registry mutation",
}
FORBIDDEN_IMPORTS = {
    "offline_evaluation.evaluation_card_schema",
    "offline_evaluation.evaluation_card_generator",
    "offline_evaluation.evaluation_card_writer",
}


class Fdp123EvaluationCardScopeGuardTest(unittest.TestCase):
    def test_newPackageDoesNotUseForbiddenRuntimeOrPromotionTerms(self):
        text = self._package_text()

        for term in FORBIDDEN_TERMS:
            with self.subTest(term=term):
                self.assertNotIn(term, text)

    def test_newPackageDoesNotImportOldFdp103EvaluationCardModules(self):
        text = self._package_text()

        for import_path in FORBIDDEN_IMPORTS:
            with self.subTest(import_path=import_path):
                self.assertNotIn(import_path, text)

    def test_docsDoNotOverclaimProductionApproval(self):
        text = "\n".join(path.read_text(encoding="utf-8") for path in DOC_PATHS if path.exists())

        self.assertIn("not model promotion", text)
        self.assertIn("not production approval", text)
        self.assertIn("not threshold recommendation", text)
        self.assertIn("not payment authorization", text)

    def test_docsDescribeFailClosedFdp126SourceBoundary(self):
        text = "\n".join(path.read_text(encoding="utf-8") for path in DOC_PATHS if path.exists())
        compact_text = " ".join(text.split())

        self.assertIn("`evaluation_summary.json`", text)
        self.assertIn("`manifest.json`", text)
        self.assertIn("positiveClassCount + negativeClassCount == recordsEvaluated", text)
        self.assertIn("real RFC3339 UTC", text)
        self.assertIn("`Z` date-times", text)
        self.assertIn("does not copy FDP-124 `disagreementSummary` into Platform Recommendation Evaluation Card v1", compact_text)
        self.assertIn("not a signature, notarization, external attestation", text)

    def test_safetyPolicyHasSingleExecutableOwner(self):
        policy = (PACKAGE_ROOT / "safety_policy.py").read_text(encoding="utf-8")
        schema = (PACKAGE_ROOT / "schema.py").read_text(encoding="utf-8")
        generator = (PACKAGE_ROOT / "generator.py").read_text(encoding="utf-8")
        writer = (PACKAGE_ROOT / "writer.py").read_text(encoding="utf-8")

        self.assertIn("def compact_policy_token", policy)
        self.assertIn("def reject_unsafe_structure", policy)
        self.assertIn("def reject_unsafe_serialized_payload", policy)
        for source in (schema, generator, writer):
            self.assertNotIn("character.isalnum()", source)
        self.assertIn("reject_unsafe_structure", schema)
        self.assertIn("reject_unsafe_structure", generator)
        self.assertIn("reject_unsafe_serialized_payload", writer)

    def test_timestampContractHasSingleExecutableOwner(self):
        schema = (PACKAGE_ROOT / "schema.py").read_text(encoding="utf-8")
        shared = (PACKAGE_ROOT.parent / "timestamp_contract.py").read_text(encoding="utf-8")

        self.assertIn("RFC3339_DATETIME_PATTERN", shared)
        self.assertIn("def normalize_rfc3339_timestamp", shared)
        self.assertIn("def timestamp_instant", shared)
        self.assertIn("fromisoformat", shared)
        self.assertIn("normalize_rfc3339_timestamp as _normalize_rfc3339_timestamp", schema)
        self.assertIn("timestamp_instant as _timestamp_instant", schema)
        self.assertNotIn("RFC3339_DATETIME_PATTERN", schema)
        self.assertNotIn("def normalize_rfc3339_timestamp", schema)
        self.assertNotIn("fromisoformat", schema)
        self.assertNotIn("isoformat(timespec=", schema)

    def _package_text(self):
        return "\n".join(path.read_text(encoding="utf-8") for path in PACKAGE_ROOT.glob("*.py"))


if __name__ == "__main__":
    unittest.main()
