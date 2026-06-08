import unittest

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.dataset_schema import DatasetFormatError, DatasetValidationError, FailedExportError
from fdp103_fixtures import jsonl, record


class Fdp102JsonlReaderTest(unittest.TestCase):
    def test_readsValidFdp102Jsonl(self):
        parsed = read_fdp102_jsonl(jsonl(record()))

        self.assertEqual(1, parsed.metadata_lines_read)
        self.assertEqual(1, parsed.dataset_records_read)
        self.assertEqual("ANALYST_CONFIRMED_FRAUD", parsed.records[0].evaluation_label)

    def test_rejectsEmptyInput(self):
        with self.assertRaises(DatasetFormatError):
            read_fdp102_jsonl("")

    def test_rejectsMissingMetadataLine(self):
        with self.assertRaises(DatasetFormatError):
            read_fdp102_jsonl('{"type":"DATASET_RECORD","record":{}}\n')

    def test_rejectsDatasetRecordBeforeMetadata(self):
        with self.assertRaises(DatasetFormatError):
            read_fdp102_jsonl('{"type":"DATASET_RECORD","record":{}}\n' + jsonl(record()))

    def test_rejectsMultipleMetadataLines(self):
        payload = jsonl(record()) + '{"type":"EXPORT_METADATA","failureReason":null}\n'

        with self.assertRaises(DatasetFormatError):
            read_fdp102_jsonl(payload)

    def test_rejectsMalformedJsonl(self):
        with self.assertRaises(DatasetFormatError):
            read_fdp102_jsonl("{not-json}\n")

    def test_rejectsUnknownLineType(self):
        with self.assertRaises(DatasetFormatError):
            read_fdp102_jsonl('{"type":"EXPORT_METADATA","failureReason":null}\n{"type":"OTHER"}\n')

    def test_abortsWhenMetadataHasFailureReason(self):
        with self.assertRaises(FailedExportError):
            read_fdp102_jsonl(jsonl(metadata_overrides={"failureReason": "CORRUPTED_FEEDBACK"}))

    def test_failedExportIsNotEmptyDataset(self):
        with self.assertRaises(FailedExportError):
            read_fdp102_jsonl(jsonl(metadata_overrides={"failureReason": "FEEDBACK_STORE_UNAVAILABLE"}))

    def test_ignoresSafeUnknownOptionalMetadataFields(self):
        parsed = read_fdp102_jsonl(jsonl(record(), metadata_overrides={"safeOptionalField": "ignored"}))

        self.assertEqual(1, len(parsed.records))

    def test_ignoresSafeUnknownOptionalRecordFields(self):
        parsed = read_fdp102_jsonl(jsonl(record(safeOptionalRecordField="ignored")))

        self.assertEqual(1, len(parsed.records))

    def test_rejectsInvalidKnownFields(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(evaluationLabel="GROUND_TRUTH")))


if __name__ == "__main__":
    unittest.main()
