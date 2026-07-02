import json
import unittest

from offline_evaluation.fdp123.dataset_reader import read_fdp123_feedback_dataset_jsonl
from offline_evaluation.fdp123.dataset_schema import (
    Fdp123DatasetFormatError,
    Fdp123DatasetValidationError,
    Fdp123FailedDatasetError,
)
try:
    from fdp123.fdp123_fixtures import jsonl, jsonl_file, metadata, record
except ModuleNotFoundError:
    from fdp123_fixtures import jsonl, jsonl_file, metadata, record


class Fdp123DatasetReaderTest(unittest.TestCase):
    def test_readsValidFdp123Jsonl(self):
        with jsonl_file(jsonl(record())) as path:
            parsed = read_fdp123_feedback_dataset_jsonl(path)

        self.assertEqual(1, len(parsed.records))
        self.assertEqual("feedback-dataset-v1", parsed.metadata.dataset_version)
        self.assertEqual("POSITIVE_FRAUD", parsed.records[0].evaluation_label)

    def test_metadataIsNotCountedAsRecord(self):
        with jsonl_file(jsonl(record(), record(
            evaluationRecordId="eval_cccccccccccccccccccccccccccccccc",
            transactionReference="txnref_dddddddddddddddddddddddddddddddd",
        ))) as path:
            parsed = read_fdp123_feedback_dataset_jsonl(path)

        self.assertEqual(2, len(parsed.records))

    def test_emptySuccessfulDatasetIsValid(self):
        with jsonl_file(jsonl()) as path:
            parsed = read_fdp123_feedback_dataset_jsonl(path)

        self.assertEqual(0, len(parsed.records))

    def test_requiresFileToExist(self):
        with self.assertRaises(FileNotFoundError):
            read_fdp123_feedback_dataset_jsonl("missing-fdp123.jsonl")

    def test_rejectsMissingMetadata(self):
        with jsonl_file(json.dumps({"type": "DATASET_RECORD", "record": record()}) + "\n") as path:
            with self.assertRaises(Fdp123DatasetFormatError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_rejectsMetadataAfterFirstLine(self):
        payload = jsonl(record()) + json.dumps(metadata(recordsReturned=0, rawRowsRead=0)) + "\n"

        with jsonl_file(payload) as path:
            with self.assertRaises(Fdp123DatasetFormatError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_rejectsMultipleMetadataLines(self):
        payload = (
            json.dumps(metadata(recordsReturned=0, rawRowsRead=0), separators=(",", ":"))
            + "\n"
            + json.dumps(metadata(recordsReturned=0, rawRowsRead=0), separators=(",", ":"))
            + "\n"
        )

        with jsonl_file(payload) as path:
            with self.assertRaises(Fdp123DatasetFormatError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_rejectsInvalidJson(self):
        with jsonl_file("{not-json}\n") as path:
            with self.assertRaises(Fdp123DatasetFormatError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_rejectsUnknownLineType(self):
        payload = json.dumps(metadata(recordsReturned=0, rawRowsRead=0)) + '\n{"type":"OTHER"}\n'

        with jsonl_file(payload) as path:
            with self.assertRaises(Fdp123DatasetFormatError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_rejectsFdp102ExportMetadata(self):
        payload = '{"type":"EXPORT_METADATA","failureReason":null}\n'

        with jsonl_file(payload) as path:
            with self.assertRaises(Fdp123DatasetFormatError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_rejectsUnsupportedDatasetVersion(self):
        with jsonl_file(jsonl(record(), metadata_overrides={"datasetVersion": "other"})) as path:
            with self.assertRaises(Fdp123DatasetValidationError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_rejectsFailedDatasetMetadata(self):
        with jsonl_file(jsonl(metadata_overrides={"failureReason": "FEEDBACK_STORE_UNAVAILABLE"})) as path:
            with self.assertRaises(Fdp123FailedDatasetError):
                read_fdp123_feedback_dataset_jsonl(path)

    def test_onlyDatasetRecordLinesBecomeRecords(self):
        with jsonl_file(jsonl(record(evaluationRecordId="eval_11111111111111111111111111111111"))) as path:
            parsed = read_fdp123_feedback_dataset_jsonl(path)

        self.assertEqual(["eval_11111111111111111111111111111111"], [item.evaluation_record_id for item in parsed.records])

    def test_rejectsRecordCountMismatch(self):
        with jsonl_file(jsonl(record(), metadata_overrides={"recordsReturned": 2, "rawRowsRead": 2})) as path:
            with self.assertRaises(Fdp123DatasetValidationError):
                read_fdp123_feedback_dataset_jsonl(path)


if __name__ == "__main__":
    unittest.main()
