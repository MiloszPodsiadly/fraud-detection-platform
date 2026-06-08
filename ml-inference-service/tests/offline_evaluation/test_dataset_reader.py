import unittest
import json

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.dataset_schema import DatasetFormatError, DatasetValidationError, FailedExportError
from fdp103_fixtures import jsonl, metadata, record


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
            read_fdp102_jsonl(json.dumps(metadata(), separators=(",", ":")) + '\n{"type":"OTHER"}\n')

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

    def test_rejectsMetadataMissingFromInclusive(self):
        self._assert_missing_metadata_rejected("fromInclusive")

    def test_rejectsMetadataMissingToInclusive(self):
        self._assert_missing_metadata_rejected("toInclusive")

    def test_rejectsMetadataMissingExportedAt(self):
        self._assert_missing_metadata_rejected("exportedAt")

    def test_rejectsMetadataMissingMaxRecords(self):
        self._assert_missing_metadata_rejected("maxRecords")

    def test_rejectsMetadataMissingRawRowsRead(self):
        self._assert_missing_metadata_rejected("rawRowsRead")

    def test_rejectsMetadataMissingRecordsReturned(self):
        self._assert_missing_metadata_rejected("recordsReturned")

    def test_rejectsMetadataMissingTruncated(self):
        self._assert_missing_metadata_rejected("truncated")

    def test_rejectsMetadataMissingTimeBasis(self):
        self._assert_missing_metadata_rejected("timeBasis")

    def test_rejectsMetadataMissingDeduplicationPolicy(self):
        self._assert_missing_metadata_rejected("deduplicationPolicy")

    def test_requiresFailureReasonField(self):
        self._assert_missing_metadata_rejected("failureReason")

    def test_rejectsUnsupportedTimeBasis(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(metadata_overrides={"timeBasis": "EVENT_TIME"}))

    def test_rejectsUnsupportedDeduplicationPolicy(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(metadata_overrides={"deduplicationPolicy": "UNKNOWN"}))

    def test_rejectsRecordsReturnedGreaterThanMaxRecords(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(metadata_overrides={"maxRecords": 1, "recordsReturned": 2}))

    def test_rejectsRawRowsReadLessThanRecordsReturned(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(metadata_overrides={"rawRowsRead": 0, "recordsReturned": 1}))

    def test_rejectsDatasetRecordCountDifferentFromMetadataRecordsReturned(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(), metadata_overrides={"recordsReturned": 2, "rawRowsRead": 2}))

    def test_rejectsDatasetRecordCountGreaterThanMetadataMaxRecords(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(record(), record(evaluationRecordId="eval-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), metadata_overrides={"maxRecords": 1}))

    def test_rejectsMoreThanFiveHundredDatasetRecords(self):
        records = [
            record(evaluationRecordId=f"eval-{index:032x}", transactionReference=f"txnref-{index:032x}")
            for index in range(501)
        ]

        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(*records, metadata_overrides={"maxRecords": 500, "recordsReturned": 501, "rawRowsRead": 501}))

    def test_rejectsMoreThanFiveHundredAndOneNonEmptyLines(self):
        records = [
            record(evaluationRecordId=f"eval-{index:032x}", transactionReference=f"txnref-{index:032x}")
            for index in range(501)
        ]

        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(*records, metadata_overrides={"recordsReturned": 501, "rawRowsRead": 501}))

    def test_rejectsOverlongJsonlLine(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl("X" * 64001 + "\n")

    def test_doesNotProcessUnboundedRecordStream(self):
        class Unbounded:
            def __iter__(self):
                yield json.dumps(metadata(maxRecords=500, recordsReturned=500, rawRowsRead=500))
                index = 0
                while True:
                    yield json.dumps({"type": "DATASET_RECORD", "record": record(
                        evaluationRecordId=f"eval-{index:032x}",
                        transactionReference=f"txnref-{index:032x}",
                    )})
                    index += 1

        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(Unbounded())

    def test_rejectsInputRecordsBeyondMetadataMaxRecords(self):
        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(jsonl(
                record(),
                record(evaluationRecordId="eval-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                metadata_overrides={"maxRecords": 1, "recordsReturned": 2, "rawRowsRead": 2},
            ))

    def _assert_missing_metadata_rejected(self, field: str):
        payload = metadata()
        payload.pop(field)

        with self.assertRaises(DatasetValidationError):
            read_fdp102_jsonl(json.dumps(payload, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    unittest.main()
