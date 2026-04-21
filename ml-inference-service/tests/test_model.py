import unittest

from app.model import FraudModel


class FraudModelTest(unittest.TestCase):
    def test_scores_high_risk_signal_as_high_or_critical(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 8,
                "recentAmountSum": {"amount": 7200.0, "currency": "USD"},
                "transactionVelocityPerMinute": 0.7,
                "merchantFrequency7d": 9,
                "deviceNovelty": True,
                "countryMismatch": True,
                "proxyOrVpnDetected": True,
                "featureFlags": ["DEVICE_NOVELTY", "COUNTRY_MISMATCH", "PROXY_OR_VPN", "HIGH_VELOCITY"],
            }
        )

        self.assertTrue(result["available"])
        self.assertIn(result["riskLevel"], {"HIGH", "CRITICAL"})
        self.assertGreaterEqual(result["fraudScore"], 0.75)
        self.assertIn("proxyOrVpnDetected", result["reasonCodes"])

    def test_scores_baseline_signal_as_low(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 1,
                "recentAmountSum": {"amount": 45.0, "currency": "USD"},
                "transactionVelocityPerMinute": 0.05,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
                "featureFlags": [],
            }
        )

        self.assertEqual(result["riskLevel"], "LOW")
        self.assertLess(result["fraudScore"], 0.45)

    def test_scores_rapid_transfer_burst_as_high_or_critical(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 2,
                "recentAmountSum": {"amount": 20000.0, "currency": "PLN"},
                "recentAmountSumPln": 20000.0,
                "rapidTransferTotalPln": 20000.0,
                "rapidTransferFraudCaseCandidate": True,
                "transactionVelocityPerMinute": 2.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
                "featureFlags": ["RAPID_PLN_20K_BURST"],
            }
        )

        self.assertIn(result["riskLevel"], {"HIGH", "CRITICAL"})
        self.assertIn("rapidTransferBurst", result["reasonCodes"])

    def test_keeps_rapid_transfer_seed_without_aggregate_signal_low(self):
        result = FraudModel().score(
            {
                "recentTransactionCount": 2,
                "recentAmountSum": {"amount": 10000.0, "currency": "PLN"},
                "recentAmountSumPln": 10000.0,
                "rapidTransferTotalPln": 10000.0,
                "rapidTransferFraudCaseCandidate": False,
                "transactionVelocityPerMinute": 2.0,
                "merchantFrequency7d": 1,
                "deviceNovelty": False,
                "countryMismatch": False,
                "proxyOrVpnDetected": False,
                "featureFlags": [],
            }
        )

        self.assertEqual(result["riskLevel"], "LOW")
        self.assertNotIn("rapidTransferBurst", result["reasonCodes"])


if __name__ == "__main__":
    unittest.main()
