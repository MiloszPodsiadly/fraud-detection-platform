import hashlib
import json
import tempfile
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.dataset_schema import MAX_DATASET_RECORDS
from offline_evaluation.feedback_dataset_evaluation.evaluation_runner import build_feedback_dataset_evaluation_reports
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import ModelEvaluationIdentity
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    MANIFEST_FILENAME,
    MAX_MANIFEST_BYTES,
    MAX_SUMMARY_BYTES,
    SUMMARY_FILENAME,
    ModelEvaluationArtifactSetError,
    ModelEvaluationArtifactVerificationLevel,
    read_validated_model_evaluation_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.report_writer import write_feedback_dataset_evaluation_reports

try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import GENERATED_AT, jsonl, record


MODEL_IDENTITY = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "2026-06-25.v1",
    "feature-contract-v2",
    "a" * 64,
)


class ModelEvaluationArtifactSetReaderTest(unittest.TestCase):
    def test_validArtifactSetPassesAndReturnsImmutableEvidence(self):
        with model_evaluation_artifacts() as artifact_dir:
            manifest_bytes = (artifact_dir / MANIFEST_FILENAME).read_bytes()

            evidence = read_validated_model_evaluation_artifact_set(artifact_dir)

            self.assertEqual("ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1", evidence.summary["reportType"])
            self.assertEqual(GENERATED_AT, evidence.summary["generatedAt"])
            self.assertEqual(hashlib.sha256(manifest_bytes).hexdigest(), evidence.manifest_sha256)
            self.assertEqual(
                ModelEvaluationArtifactVerificationLevel.CLAIM_ONLY,
                evidence.verification_level,
            )
            self.assertIsInstance(evidence.summary["warnings"], tuple)
            with self.assertRaises(TypeError):
                evidence.summary["generatedAt"] = "2026-01-01T00:00:00Z"
            with self.assertRaises(TypeError):
                evidence.summary["population"]["recordsEvaluated"] = 99
            with self.assertRaises(FrozenInstanceError):
                evidence.verification_level = ModelEvaluationArtifactVerificationLevel.VERIFIED_AGAINST_SOURCE_BYTES

    def test_artifactVerifiesAgainstExactSourceDatasetBytes(self):
        with model_evaluation_artifacts() as artifact_dir:
            source_path = artifact_dir.parent.parent / "feedback-dataset.jsonl"

            evidence = read_validated_model_evaluation_artifact_set(
                artifact_dir,
                source_dataset_path=source_path,
            )

            self.assertEqual(
                hashlib.sha256(source_path.read_bytes()).hexdigest(),
                evidence.summary["sourceDataset"]["sha256"],
            )
            self.assertEqual(
                ModelEvaluationArtifactVerificationLevel.VERIFIED_AGAINST_SOURCE_BYTES,
                evidence.verification_level,
            )

    def test_artifactJsonCannotSupplyVerificationLevel(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["verificationLevel"] = "VERIFIED_AGAINST_SOURCE_BYTES"
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "unsupported fields: verificationLevel"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_artifactFromDatasetACannotVerifyAgainstDifferentDatasetB(self):
        with model_evaluation_artifacts() as artifact_dir:
            different_source = artifact_dir.parent.parent / "feedback-dataset-b.jsonl"
            records = (
                model_evaluation_artifacts._record(
                    "eval_11111111111111111111111111111111",
                    fraudScore=0.89,
                ),
                model_evaluation_artifacts._record(
                    "eval_22222222222222222222222222222222",
                    feedbackLabel="CONFIRMED_LEGITIMATE",
                    evaluationLabel="NEGATIVE_LEGITIMATE",
                ),
            )
            different_source.write_text(jsonl(*records), encoding="utf-8", newline="\n")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "does not match actual source bytes"):
                read_validated_model_evaluation_artifact_set(
                    artifact_dir,
                    source_dataset_path=different_source,
                )

    def test_resealedTamperedSourceShaRejectedAgainstActualDataset(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["sourceDataset"]["sha256"] = "0" * 64
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "does not match actual source bytes"):
                read_validated_model_evaluation_artifact_set(
                    artifact_dir,
                    source_dataset_path=artifact_dir.parent.parent / "feedback-dataset.jsonl",
                )

    def test_tamperedSourceShaRejectedByManifestIntegrity(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary_path = artifact_dir / SUMMARY_FILENAME
            summary = self._summary(artifact_dir)
            original_sha = summary["sourceDataset"]["sha256"].encode("ascii")
            summary_path.write_bytes(
                summary_path.read_bytes().replace(original_sha, b"0" * 64, 1)
            )

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "sha256 does not match"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_resealedTamperedSourcePopulationRejectedAgainstActualDataset(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["sourceDataset"]["rawRowsRead"] = 3
            summary["sourceDataset"]["excludedUnresolvedCount"] = 1
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "does not match actual source bytes"):
                read_validated_model_evaluation_artifact_set(
                    artifact_dir,
                    source_dataset_path=artifact_dir.parent.parent / "feedback-dataset.jsonl",
                )

    def test_badSha256Rejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            self._mutate_manifest_entry(artifact_dir, sha256="0" * 64)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "sha256 does not match"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_badSizeBytesRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            self._mutate_manifest_entry(artifact_dir, sizeBytes=1)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "size does not match"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_missingSummaryRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            (artifact_dir / SUMMARY_FILENAME).unlink()

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "summary is missing"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_missingManifestRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            (artifact_dir / MANIFEST_FILENAME).unlink()

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "manifest is missing"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_extraArtifactRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            (artifact_dir / "notes.txt").write_text("not part of v1", encoding="utf-8")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "unsupported entries"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_duplicateManifestFileEntryRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            manifest = self._manifest(artifact_dir)
            manifest["files"].append(dict(manifest["files"][0]))
            self._write_manifest(artifact_dir, manifest)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "duplicate artifact"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_wrongFilenameRejected(self):
        self._assert_manifest_filename_rejected("other.json", "unsupported artifact")

    def testParentTraversalFilenameRejected(self):
        self._assert_manifest_filename_rejected("../model_evaluation_summary.json", "must be canonical")

    def testAbsoluteFilenameRejected(self):
        self._assert_manifest_filename_rejected("C:\\evidence\\model_evaluation_summary.json", "must be canonical")

    def test_wrongReportTypeRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            self._mutate_manifest(artifact_dir, reportType="OTHER")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "reportType unsupported"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_wrongArtifactSetVersionRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            self._mutate_manifest(artifact_dir, artifactSetVersion="other-v1")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "artifactSetVersion unsupported"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_manifestUnknownFieldRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            self._mutate_manifest(artifact_dir, unexpected="value")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "unsupported fields"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_manifestSummaryGeneratedAtMismatchRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            self._mutate_manifest(artifact_dir, generatedAt="2026-06-12T00:00:00Z")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "must match summary"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_tamperedSummaryRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary_path = artifact_dir / SUMMARY_FILENAME
            original = summary_path.read_bytes()
            tampered = original.replace(b"2026-06-25.v1", b"2026-06-26.v1", 1)
            self.assertNotEqual(original, tampered)
            self.assertEqual(len(original), len(tampered))
            summary_path.write_bytes(tampered)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "sha256 does not match"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_invalidSummaryUnknownFieldRejectedAfterIntegrityChecks(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["unexpected"] = "value"
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "unsupported fields"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_invalidSummaryPopulationRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["population"]["recordsConsidered"] += 1
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "population counts must reconcile"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_invalidSummaryClassCountsRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["classBalance"]["positiveClassCount"] += 1
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "class balance must sum"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_resealedSummaryWithContradictoryWarningsRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["warnings"] = [
                "MODEL_PREDICTION_SIGNAL_UNAVAILABLE",
                "SINGLE_CLASS_MODEL_LINEAGE_RECORDS",
            ]
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "warnings must match evaluated population"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_resealedSummaryWithReversedEvaluationWindowRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["evaluationWindow"]["fromInclusive"] = "2026-09-27T00:00:00.123456789Z"
            summary["evaluationWindow"]["toInclusive"] = "2026-09-27T00:00:00.123456788Z"
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "must not be later"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_resealedSummaryWithReconciledOversizedPopulationRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary = self._summary(artifact_dir)
            summary["population"] = {
                "recordsConsidered": MAX_DATASET_RECORDS + 1,
                "recordsWithPredictionEvidence": MAX_DATASET_RECORDS + 1,
                "recordsEvaluated": MAX_DATASET_RECORDS + 1,
                "recordsExcludedIdentityMismatch": 0,
                "recordsExcludedSourceIdentityMismatch": 0,
                "recordsExcludedMissingPredictionEvidence": 0,
                "recordsExcludedUnexpectedMissingPredictionEvidence": 0,
                "recordsExcludedInvalidPredictionEvidence": 0,
            }
            summary["classBalance"] = {
                "positiveClassCount": MAX_DATASET_RECORDS,
                "negativeClassCount": 1,
            }
            self._write_summary_and_reseal(artifact_dir, summary)

            with self.assertRaisesRegex(
                    ModelEvaluationArtifactSetError,
                    f"must be an integer between 0 and {MAX_DATASET_RECORDS}",
            ):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_symlinkSummaryRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            summary_path = artifact_dir / SUMMARY_FILENAME
            target = artifact_dir.parent / "summary-target.json"
            target.write_bytes(summary_path.read_bytes())
            summary_path.unlink()
            try:
                summary_path.symlink_to(target)
            except OSError as exc:
                self.skipTest(f"symlink creation unavailable: {exc}")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "must not be a symlink"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_symlinkManifestRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            manifest_path = artifact_dir / MANIFEST_FILENAME
            target = artifact_dir.parent / "manifest-target.json"
            target.write_bytes(manifest_path.read_bytes())
            manifest_path.unlink()
            try:
                manifest_path.symlink_to(target)
            except OSError as exc:
                self.skipTest(f"symlink creation unavailable: {exc}")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "must not be a symlink"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_symlinkArtifactDirectoryRejected(self):
        with model_evaluation_artifacts() as artifact_dir:
            link = artifact_dir.parent / "model-evaluation-link"
            try:
                link.symlink_to(artifact_dir, target_is_directory=True)
            except OSError as exc:
                self.skipTest(f"symlink creation unavailable: {exc}")

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "must not be a symlink"):
                read_validated_model_evaluation_artifact_set(link)

    def test_oversizedSummaryRejectedBeforeTrustingManifestSize(self):
        with model_evaluation_artifacts() as artifact_dir:
            (artifact_dir / SUMMARY_FILENAME).write_bytes(b" " * (MAX_SUMMARY_BYTES + 1))

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "exceeds maximum byte size"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_oversizedManifestRejectedBeforeJsonParsing(self):
        with model_evaluation_artifacts() as artifact_dir:
            (artifact_dir / MANIFEST_FILENAME).write_bytes(b" " * (MAX_MANIFEST_BYTES + 1))

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "exceeds maximum byte size"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def test_strictJsonRejectsDuplicateManifestRootField(self):
        with model_evaluation_artifacts() as artifact_dir:
            manifest_path = artifact_dir / MANIFEST_FILENAME
            manifest_path.write_text(
                '{"reportType":"ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1",'
                '"reportType":"ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"}',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, "strict JSON"):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def _assert_manifest_filename_rejected(self, name, message):
        with model_evaluation_artifacts() as artifact_dir:
            self._mutate_manifest_entry(artifact_dir, name=name)

            with self.assertRaisesRegex(ModelEvaluationArtifactSetError, message):
                read_validated_model_evaluation_artifact_set(artifact_dir)

    def _manifest(self, artifact_dir):
        return json.loads((artifact_dir / MANIFEST_FILENAME).read_text(encoding="utf-8"))

    def _summary(self, artifact_dir):
        return json.loads((artifact_dir / SUMMARY_FILENAME).read_text(encoding="utf-8"))

    def _mutate_manifest(self, artifact_dir, **overrides):
        manifest = self._manifest(artifact_dir)
        manifest.update(overrides)
        self._write_manifest(artifact_dir, manifest)

    def _mutate_manifest_entry(self, artifact_dir, **overrides):
        manifest = self._manifest(artifact_dir)
        manifest["files"][0].update(overrides)
        self._write_manifest(artifact_dir, manifest)

    def _write_summary_and_reseal(self, artifact_dir, summary):
        summary_path = artifact_dir / SUMMARY_FILENAME
        summary_path.write_text(
            json.dumps(summary, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        payload = summary_path.read_bytes()
        manifest = self._manifest(artifact_dir)
        manifest["files"][0]["sha256"] = hashlib.sha256(payload).hexdigest()
        manifest["files"][0]["sizeBytes"] = len(payload)
        self._write_manifest(artifact_dir, manifest)

    def _write_manifest(self, artifact_dir, manifest):
        (artifact_dir / MANIFEST_FILENAME).write_text(
            json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )


class model_evaluation_artifacts:
    def __init__(self):
        self.directory = None

    def __enter__(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        output = root / "evaluation-run"
        records = (
            self._record("eval_11111111111111111111111111111111"),
            self._record(
                "eval_22222222222222222222222222222222",
                feedbackLabel="CONFIRMED_LEGITIMATE",
                evaluationLabel="NEGATIVE_LEGITIMATE",
            ),
        )
        input_path = root / "feedback-dataset.jsonl"
        input_path.write_text(jsonl(*records), encoding="utf-8", newline="\n")
        dataset = read_feedback_dataset_jsonl(input_path)
        reports = build_feedback_dataset_evaluation_reports(
            dataset,
            generated_at=GENERATED_AT,
            model_identity=MODEL_IDENTITY,
        )
        paths = write_feedback_dataset_evaluation_reports(reports, output)
        return paths["modelEvaluationDir"]

    def __exit__(self, exc_type, exc, tb):
        self.directory.cleanup()

    @staticmethod
    def _record(evaluation_record_id, **overrides):
        return record(
            evaluationRecordId=evaluation_record_id,
            transactionReference="txnref_" + evaluation_record_id.removeprefix("eval_"),
            mlModelName=MODEL_IDENTITY.model_name,
            mlModelVersion=MODEL_IDENTITY.model_version,
            mlFeatureContractVersion=MODEL_IDENTITY.feature_contract_version,
            **overrides,
        )


if __name__ == "__main__":
    unittest.main()
