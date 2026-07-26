import unittest

from offline_evaluation.json_contract import JsonContractError, loads_strict_json


class JsonContractTest(unittest.TestCase):
    def test_rejectsDuplicateObjectKeysAtEveryLevel(self):
        for payload in (
                '{"reportType":"EXPECTED","reportType":"FORGED"}',
                '{"metrics":{"value":0.5,"value":0.6}}',
                '{"files":[{"sha256":"a","sha256":"b"}]}',
                '{"files":[{"sizeBytes":1,"sizeBytes":2}]}',
        ):
            with self.subTest(payload=payload):
                with self.assertRaises(JsonContractError):
                    loads_strict_json(payload)

    def test_acceptsUniqueObjectKeys(self):
        self.assertEqual({"reportType": "EXPECTED", "nested": {"value": 1}}, loads_strict_json(
            '{"reportType":"EXPECTED","nested":{"value":1}}'
        ))


if __name__ == "__main__":
    unittest.main()
