import hashlib
import json
import unittest

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.dataset_schema import (
    FeedbackDatasetFormatError,
    FeedbackDatasetValidationError,
    FeedbackDatasetFailedDatasetError,
)
try:
    from feedback_dataset_evaluation.feedback_dataset_fixtures import jsonl, jsonl_file, metadata, record
except ModuleNotFoundError:
    from feedback_dataset_fixtures import jsonl, jsonl_file, metadata, record


class FeedbackDatasetReaderTest(unittest.TestCase):
    def test_readsValidFeedbackDatasetJsonl(self):
        with jsonl_file(jsonl(record())) as path:
            parsed = read_feedback_dataset_jsonl(path)

        self.assertEqual(1, len(parsed.records))
        self.assertEqual("feedback-dataset-v3", parsed.metadata.dataset_version)
        self.assertEqual("POSITIVE_FRAUD", parsed.records[0].evaluation_label)

    def test_sourceSha256UsesExactDatasetBytesDeterministically(self):
        payload = jsonl(record())
        with jsonl_file(payload) as path:
            source_bytes = path.read_bytes()
            first = read_feedback_dataset_jsonl(path)
            second = read_feedback_dataset_jsonl(path)

        expected = hashlib.sha256(source_bytes).hexdigest()
        self.assertEqual(expected, first.source_sha256)
        self.assertEqual(first.source_sha256, second.source_sha256)

    def test_oneSourceByteChangesDatasetIdentityWithoutChangingParsedPopulation(self):
        with jsonl_file(jsonl(record())) as path:
            before = read_feedback_dataset_jsonl(path)
            path.write_bytes(path.read_bytes() + b" ")
            after = read_feedback_dataset_jsonl(path)

        self.assertEqual(before.metadata, after.metadata)
        self.assertEqual(before.records, after.records)
        self.assertNotEqual(before.source_sha256, after.source_sha256)

    def test_sameWindowVersionAndRecordCountDoNotCollapseDifferentSourceBytes(self):
        first_payload = jsonl(record(fraudScore=0.91))
        second_payload = jsonl(record(fraudScore=0.92))
        with jsonl_file(first_payload) as path:
            first = read_feedback_dataset_jsonl(path)
        with jsonl_file(second_payload) as path:
            second = read_feedback_dataset_jsonl(path)

        self.assertEqual(first.metadata.dataset_version, second.metadata.dataset_version)
        self.assertEqual(first.metadata.from_inclusive, second.metadata.from_inclusive)
        self.assertEqual(first.metadata.to_inclusive, second.metadata.to_inclusive)
        self.assertEqual(len(first.records), len(second.records))
        self.assertNotEqual(first.source_sha256, second.source_sha256)

    def test_metadataIsNotCountedAsRecord(self):
        with jsonl_file(jsonl(record(), record(
            evaluationRecordId="eval_cccccccccccccccccccccccccccccccc",
            transactionReference="txnref_dddddddddddddddddddddddddddddddd",
        ))) as path:
            parsed = read_feedback_dataset_jsonl(path)

        self.assertEqual(2, len(parsed.records))

    def test_emptySuccessfulDatasetIsValid(self):
        with jsonl_file(jsonl()) as path:
            parsed = read_feedback_dataset_jsonl(path)

        self.assertEqual(0, len(parsed.records))

    def test_requiresFileToExist(self):
        with self.assertRaises(FileNotFoundError):
            read_feedback_dataset_jsonl("missing-feedback-dataset.jsonl")

    def test_rejectsMissingMetadata(self):
        with jsonl_file(json.dumps({"type": "DATASET_RECORD", "record": record()}) + "\n") as path:
            with self.assertRaises(FeedbackDatasetFormatError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsMetadataAfterFirstLine(self):
        payload = jsonl(record()) + json.dumps(metadata(recordsReturned=0, rawRowsRead=0)) + "\n"

        with jsonl_file(payload) as path:
            with self.assertRaises(FeedbackDatasetFormatError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsMultipleMetadataLines(self):
        payload = (
            json.dumps(metadata(recordsReturned=0, rawRowsRead=0), separators=(",", ":"))
            + "\n"
            + json.dumps(metadata(recordsReturned=0, rawRowsRead=0), separators=(",", ":"))
            + "\n"
        )

        with jsonl_file(payload) as path:
            with self.assertRaises(FeedbackDatasetFormatError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsInvalidJson(self):
        with jsonl_file("{not-json}\n") as path:
            with self.assertRaises(FeedbackDatasetFormatError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsUnknownLineType(self):
        payload = json.dumps(metadata(recordsReturned=0, rawRowsRead=0)) + '\n{"type":"OTHER"}\n'

        with jsonl_file(payload) as path:
            with self.assertRaises(FeedbackDatasetFormatError):
                read_feedback_dataset_jsonl(path)

    def test_rejects_legacy_export_metadata(self):
        payload = '{"type":"EXPORT_METADATA","failureReason":null}\n'

        with jsonl_file(payload) as path:
            with self.assertRaises(FeedbackDatasetFormatError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsUnsupportedDatasetVersion(self):
        with jsonl_file(jsonl(record(), metadata_overrides={"datasetVersion": "other"})) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsRetiredFeedbackDatasetV1(self):
        with jsonl_file(jsonl(record(), metadata_overrides={"datasetVersion": "feedback-dataset-v1"})) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)

    def test_rejectsEveryCanonicalFailedDatasetMetadataReason(self):
        reasons = (
            "INVALID_REQUEST",
            "FEEDBACK_STORE_UNAVAILABLE",
            "ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE",
            "ML_PREDICTION_EVIDENCE_INTEGRITY_FAILURE",
            "DATASET_SERIALIZATION_FAILED",
        )
        for reason in reasons:
            with self.subTest(reason=reason):
                with jsonl_file(jsonl(metadata_overrides={"failureReason": reason})) as path:
                    with self.assertRaisesRegex(FeedbackDatasetFailedDatasetError, reason):
                        read_feedback_dataset_jsonl(path)

    def test_rejectsUnknownDatasetFailureReason(self):
        with jsonl_file(jsonl(metadata_overrides={"failureReason": "UNKNOWN_FAILURE"})) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)

    def test_onlyDatasetRecordLinesBecomeRecords(self):
        with jsonl_file(jsonl(record(evaluationRecordId="eval_11111111111111111111111111111111"))) as path:
            parsed = read_feedback_dataset_jsonl(path)

        self.assertEqual(["eval_11111111111111111111111111111111"], [item.evaluation_record_id for item in parsed.records])

    def test_rejectsRecordCountMismatch(self):
        with jsonl_file(jsonl(record(), metadata_overrides={"recordsReturned": 2, "rawRowsRead": 2})) as path:
            with self.assertRaises(FeedbackDatasetValidationError):
                read_feedback_dataset_jsonl(path)


if __name__ == "__main__":
    unittest.main()
