import unittest
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[4] / "offline_evaluation" / "fdp123" / "model_card"
DOC_PATHS = [
    Path(__file__).resolve().parents[5] / "docs" / "architecture" / "model_card_v1.md",
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
    "paymentAuthorization",
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
    "offline_evaluation.model_card_schema",
    "offline_evaluation.model_card_generator",
    "offline_evaluation.model_card_writer",
}


class Fdp123ModelCardScopeGuardTest(unittest.TestCase):
    def test_newPackageDoesNotUseForbiddenRuntimeOrPromotionTerms(self):
        text = self._package_text()

        for term in FORBIDDEN_TERMS:
            with self.subTest(term=term):
                self.assertNotIn(term, text)

    def test_newPackageDoesNotImportOldFdp103ModelCardModules(self):
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
        self.assertIn("real RFC3339 date-times with explicit timezone", text)
        self.assertIn("does not copy FDP-124 `disagreementSummary` into Model Card v1", compact_text)
        self.assertIn("not a signature, notarization, external attestation", text)

    def _package_text(self):
        return "\n".join(path.read_text(encoding="utf-8") for path in PACKAGE_ROOT.glob("*.py"))


if __name__ == "__main__":
    unittest.main()
