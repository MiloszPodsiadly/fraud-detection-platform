import json
import unittest

from offline_evaluation.model_card_schema import ModelCardValidationError
from offline_evaluation.model_card_writer import model_card_json
from test_model_card_schema import valid_model_card


class ModelCardWriterTest(unittest.TestCase):
    def test_modelCardJsonIsDeterministic(self):
        card = valid_model_card()

        self.assertEqual(model_card_json(card), model_card_json(card))

    def test_modelCardOutputHasStableKeyOrdering(self):
        payload = model_card_json(valid_model_card())

        self.assertTrue(payload.startswith('{"approvedFor":'))

    def test_warningsAreBounded(self):
        card = valid_model_card(warnings=[f"WARNING_{index}" for index in range(10)])

        self.assertEqual(10, len(json.loads(model_card_json(card))["warnings"]))

    def test_limitationsAreBounded(self):
        card = valid_model_card(limitations=[f"LIMITATION_{index}" for index in range(20)])

        self.assertEqual(20, len(json.loads(model_card_json(card))["limitations"]))

    def test_modelCardDoesNotContainEvaluationRecordId(self):
        self.assertNotIn("evaluationRecordId", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainTransactionReference(self):
        self.assertNotIn("transactionReference", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainEvalPrefix(self):
        self.assertNotIn("eval-", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainTxnrefPrefix(self):
        self.assertNotIn("txnref-", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainRawPayload(self):
        self.assertNotIn("rawPayload", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainRawFeatureVector(self):
        self.assertNotIn("rawFeatureVector", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainCustomerAccountCardDeviceMerchantIds(self):
        payload = model_card_json(valid_model_card())

        for term in ("customerId", "accountId", "cardId", "deviceId", "merchantId"):
            self.assertNotIn(term, payload)

    def test_modelCardDoesNotContainGroundTruth(self):
        self.assertNotIn("groundTruth", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainModelTrainingLabel(self):
        self.assertNotIn("modelTrainingLabel", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainFinalDecision(self):
        self.assertNotIn("finalDecision", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainPaymentAuthorization(self):
        self.assertNotIn("paymentAuthorization", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainPromotionApproved(self):
        self.assertNotIn("promotionApproved", model_card_json(valid_model_card()))

    def test_modelCardDoesNotContainThresholdRecommendation(self):
        self.assertNotIn("thresholdRecommendation", model_card_json(valid_model_card()))

    def test_modelCardRejectsEvaluationRecordIdField(self):
        with self.assertRaises(ModelCardValidationError):
            model_card_json(valid_model_card(evaluationRecordId="eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))

    def test_modelCardRejectsTxnrefPrefix(self):
        with self.assertRaises(ModelCardValidationError):
            model_card_json(valid_model_card(warnings=["TXNREF_BAD"]))

    def test_modelCardRejectsRawPayload(self):
        with self.assertRaises(ModelCardValidationError):
            model_card_json(valid_model_card(rawPayload="unsafe"))

    def test_modelCardRejectsProductionApproved(self):
        with self.assertRaises(ModelCardValidationError):
            model_card_json(valid_model_card(productionApproved=True))


if __name__ == "__main__":
    unittest.main()
